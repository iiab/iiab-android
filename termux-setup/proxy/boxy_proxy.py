#!/usr/bin/env python3
"""
Unified local proxy for Android/Termux:
- SOCKS5 (CONNECT only) with hostname->IP mapping (no /etc/hosts needed)
- Port rewrite for mapped hosts: 80 -> http_listen_port (default 8080)
- Reverse HTTP proxy that rewrites Location to strip backend port (e.g. :8085)
- IMPORTANT: auto_decompress=False to avoid Content-Encoding mismatch
- Optional daemon mode: -d, with --status and --stop (pidfile/logfile)
"""

import argparse
import asyncio
import atexit
import ipaddress
import os
import signal
import socket
import struct
import sys
import time
from typing import Dict, Optional, Tuple

from aiohttp import web, ClientSession


# ----------------------------
# SOCKS5 server (CONNECT only)
# ----------------------------

def parse_hostmap(item: str) -> Tuple[str, str]:
    # Format: name=ip
    name, ip = item.split("=", 1)
    name = name.strip().lower().rstrip(".")
    ip = ip.strip()
    ipaddress.ip_address(ip)  # validate
    return name, ip


async def _pipe(reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
    try:
        while True:
            data = await reader.read(16384)
            if not data:
                break
            writer.write(data)
            await writer.drain()
    except Exception:
        pass
    finally:
        try:
            writer.close()
            await writer.wait_closed()
        except Exception:
            pass


def _socks_reply(rep: int, bind_addr: str = "0.0.0.0", bind_port: int = 0) -> bytes:
    # VER, REP, RSV
    hdr = b"\x05" + bytes([rep]) + b"\x00"

    try:
        ip = ipaddress.ip_address(bind_addr)
        if ip.version == 4:
            atyp = b"\x01"
            addr = socket.inet_pton(socket.AF_INET, bind_addr)
        else:
            atyp = b"\x04"
            addr = socket.inet_pton(socket.AF_INET6, bind_addr)
    except Exception:
        atyp = b"\x01"
        addr = b"\x00\x00\x00\x00"

    port = struct.pack("!H", bind_port & 0xFFFF)
    return hdr + atyp + addr + port


async def handle_socks_client(
    reader: asyncio.StreamReader,
    writer: asyncio.StreamWriter,
    host_map: Dict[str, str],
    rewrite80_to: Optional[int],
) -> None:
    try:
        # ---- Greeting ----
        head = await reader.readexactly(2)
        ver, nmethods = head[0], head[1]
        if ver != 5:
            writer.close()
            await writer.wait_closed()
            return

        methods = await reader.readexactly(nmethods)
        if 0x00 not in methods:
            writer.write(b"\x05\xff")
            await writer.drain()
            writer.close()
            await writer.wait_closed()
            return

        writer.write(b"\x05\x00")  # no-auth
        await writer.drain()

        # ---- Request ----
        req = await reader.readexactly(4)
        ver, cmd, _rsv, atyp = req
        if ver != 5 or cmd != 1:
            writer.write(_socks_reply(0x07))  # Command not supported
            await writer.drain()
            writer.close()
            await writer.wait_closed()
            return

        if atyp == 1:  # IPv4
            raw = await reader.readexactly(4)
            dst_host = socket.inet_ntop(socket.AF_INET, raw)
        elif atyp == 4:  # IPv6
            raw = await reader.readexactly(16)
            dst_host = socket.inet_ntop(socket.AF_INET6, raw)
        elif atyp == 3:  # DOMAIN
            ln = await reader.readexactly(1)
            raw = await reader.readexactly(ln[0])
            dst_host = raw.decode("utf-8", errors="replace")
        else:
            writer.write(_socks_reply(0x08))  # Address type not supported
            await writer.drain()
            writer.close()
            await writer.wait_closed()
            return

        raw_port = await reader.readexactly(2)
        dst_port = struct.unpack("!H", raw_port)[0]

        key = dst_host.strip().lower().rstrip(".")
        out_host = host_map.get(key, dst_host)

        # Rewrite 80 -> http proxy port, only for mapped hosts
        out_port = dst_port
        if rewrite80_to is not None and key in host_map and dst_port == 80:
            out_port = rewrite80_to

        # ---- Connect ----
        try:
            remote_reader, remote_writer = await asyncio.open_connection(out_host, out_port)
        except ConnectionRefusedError:
            writer.write(_socks_reply(0x05))  # Connection refused
            await writer.drain()
            writer.close()
            await writer.wait_closed()
            return
        except OSError:
            writer.write(_socks_reply(0x01))  # General failure
            await writer.drain()
            writer.close()
            await writer.wait_closed()
            return

        sockname = remote_writer.get_extra_info("sockname")
        bind_addr, bind_port = ("0.0.0.0", 0)
        if isinstance(sockname, (tuple, list)) and len(sockname) >= 2:
            bind_addr, bind_port = sockname[0], sockname[1]

        writer.write(_socks_reply(0x00, bind_addr, bind_port))  # success
        await writer.drain()

        # ---- Relay ----
        t1 = asyncio.create_task(_pipe(reader, remote_writer))
        t2 = asyncio.create_task(_pipe(remote_reader, writer))
        done, pending = await asyncio.wait({t1, t2}, return_when=asyncio.FIRST_COMPLETED)
        for t in pending:
            t.cancel()

    except asyncio.IncompleteReadError:
        pass
    except Exception:
        pass
    finally:
        try:
            writer.close()
            await writer.wait_closed()
        except Exception:
            pass


# ----------------------------
# Reverse HTTP proxy (aiohttp)
# ----------------------------

HOP_BY_HOP = {
    "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
    "te", "trailers", "transfer-encoding", "upgrade",
}

async def http_handler(request: web.Request) -> web.StreamResponse:
    app = request.app
    backend = app["backend"]          # e.g. http://127.0.0.1:8085
    public_host = app["public_host"]  # e.g. box.lan
    strip_port = app["strip_port"]    # e.g. 8085

    upstream_url = f"{backend}{request.rel_url}"

    # Copy request headers, remove hop-by-hop
    headers = {k: v for k, v in request.headers.items() if k.lower() not in HOP_BY_HOP}
    headers.pop("Proxy-Connection", None)

    # Keep upstream "Host" consistent (often matters for vhosts)
    headers["Host"] = public_host

    data = await request.read()

    async with app["session"].request(
        method=request.method,
        url=upstream_url,
        headers=headers,
        data=data if data else None,
        allow_redirects=False,
    ) as resp:
        # Raw bytes; no auto-decompress
        body = await resp.read()

        out_headers = {k: v for k, v in resp.headers.items() if k.lower() not in HOP_BY_HOP}

        # Rewrite absolute redirects:
        #   http://box.lan:8085/maps/ -> http://box.lan/maps/
        loc = out_headers.get("Location")
        if loc and strip_port:
            out_headers["Location"] = (
                loc.replace(f"http://{public_host}:{strip_port}", f"http://{public_host}")
                   .replace(f"https://{public_host}:{strip_port}", f"https://{public_host}")
            )

        return web.Response(status=resp.status, headers=out_headers, body=body)


async def start_http_proxy(listen: str, port: int, backend: str, public_host: str, strip_port: int):
    app = web.Application()
    app["backend"] = backend.rstrip("/")
    app["public_host"] = public_host
    app["strip_port"] = strip_port

    # Critical: avoid mismatching Content-Encoding
    app["session"] = ClientSession(auto_decompress=False)

    app.router.add_route("*", "/{tail:.*}", http_handler)

    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, host=listen, port=port)
    await site.start()
    return runner


async def stop_http_proxy(runner: web.AppRunner):
    app = runner.app
    try:
        await app["session"].close()
    except Exception:
        pass
    await runner.cleanup()


# ----------------------------
# Daemon helpers (Termux-friendly)
# ----------------------------

def _pid_is_running(pid: int) -> bool:
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def _read_pid(pidfile: str) -> Optional[int]:
    try:
        with open(pidfile, "r", encoding="utf-8") as f:
            s = f.read().strip()
        return int(s) if s else None
    except FileNotFoundError:
        return None
    except Exception:
        return None


def _write_pid(pidfile: str, pid: int) -> None:
    os.makedirs(os.path.dirname(pidfile) or ".", exist_ok=True)
    with open(pidfile, "w", encoding="utf-8") as f:
        f.write(str(pid))


def _remove_pid(pidfile: str) -> None:
    try:
        os.remove(pidfile)
    except FileNotFoundError:
        pass
    except Exception:
        pass


def daemonize(logfile: str, pidfile: str) -> None:
    # If already running, refuse
    existing = _read_pid(pidfile)
    if existing and _pid_is_running(existing):
        print(f"[boxproxy] already running (pid {existing})")
        sys.exit(0)

    pid = os.fork()
    if pid > 0:
        # Parent exits; child continues
        print(f"[boxproxy] started in background (pid {pid})")
        sys.exit(0)

    # Child
    os.setsid()
    os.umask(0)

    # Redirect stdio (fd-level) so everything logs properly
    os.makedirs(os.path.dirname(logfile) or ".", exist_ok=True)
    lf = open(logfile, "a", buffering=1, encoding="utf-8")
    devnull = open(os.devnull, "r", encoding="utf-8")

    os.dup2(devnull.fileno(), 0)
    os.dup2(lf.fileno(), 1)
    os.dup2(lf.fileno(), 2)

    # Also update sys.* wrappers
    sys.stdin = devnull
    sys.stdout = lf
    sys.stderr = lf

    _write_pid(pidfile, os.getpid())
    atexit.register(lambda: _remove_pid(pidfile))

    print(f"[boxproxy] daemon up (pid {os.getpid()})")
    print(f"[boxproxy] logfile: {logfile}")
    print(f"[boxproxy] pidfile: {pidfile}")


def stop_daemon(pidfile: str, timeout_s: float = 3.0) -> None:
    pid = _read_pid(pidfile)
    if not pid:
        print("[boxproxy] not running (no pidfile)")
        return

    try:
        os.kill(pid, signal.SIGTERM)
    except ProcessLookupError:
        print("[boxproxy] stale pidfile; removing")
        _remove_pid(pidfile)
        return

    deadline = time.time() + timeout_s
    while time.time() < deadline:
        if not _pid_is_running(pid):
            print("[boxproxy] stopped")
            _remove_pid(pidfile)
            return
        time.sleep(0.1)

    # Last resort
    try:
        os.kill(pid, signal.SIGKILL)
    except Exception:
        pass

    print("[boxproxy] stopped (killed)")
    _remove_pid(pidfile)


def status_daemon(pidfile: str) -> None:
    pid = _read_pid(pidfile)
    if pid and _pid_is_running(pid):
        print(f"[boxproxy] running (pid {pid})")
    else:
        print("[boxproxy] not running")


# ----------------------------
# Main / orchestration
# ----------------------------

async def main_async(args) -> None:
    host_map: Dict[str, str] = dict(parse_hostmap(x) for x in args.map)
    rewrite80_to = None if args.rewrite80_to == 0 else args.rewrite80_to

    # Start HTTP reverse proxy first
    http_runner = await start_http_proxy(
        listen=args.http_listen,
        port=args.http_port,
        backend=args.backend,
        public_host=args.public_host,
        strip_port=args.strip_backend_port,
    )

    # Start SOCKS server
    socks_server = await asyncio.start_server(
        lambda r, w: handle_socks_client(r, w, host_map, rewrite80_to),
        host=args.socks_listen,
        port=args.socks_port,
        reuse_address=True,
    )

    socks_addrs = ", ".join(str(s.getsockname()) for s in socks_server.sockets or [])
    print(f"[boxproxy] SOCKS5 listening on {socks_addrs}")
    print(f"[boxproxy] HTTP   listening on {args.http_listen}:{args.http_port} -> {args.backend}")
    print(f"[boxproxy] host map: {host_map or '(none)'}")
    if rewrite80_to:
        print(f"[boxproxy] rewrite: mapped hosts port 80 -> {rewrite80_to}")
    else:
        print("[boxproxy] rewrite: disabled")
    if args.strip_backend_port:
        print(f"[boxproxy] Location rewrite: strip :{args.strip_backend_port}")
    else:
        print("[boxproxy] Location rewrite: disabled")

    stop_event = asyncio.Event()

    def _ask_stop(*_):
        stop_event.set()

    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, _ask_stop)
        except NotImplementedError:
            pass

    await stop_event.wait()

    socks_server.close()
    await socks_server.wait_closed()
    await stop_http_proxy(http_runner)


def parse_args():
    ap = argparse.ArgumentParser()

    # SOCKS
    ap.add_argument("--socks-listen", default="127.0.0.1")
    ap.add_argument("--socks-port", type=int, default=1080)

    # HTTP reverse proxy
    ap.add_argument("--http-listen", default="127.0.0.1")
    ap.add_argument("--http-port", type=int, default=8080)
    ap.add_argument("--backend", default="http://127.0.0.1:8085")

    # Behavior
    ap.add_argument("--public-host", default="box.lan")
    ap.add_argument(
        "--map",
        action="append",
        default=[],
        help="Hostname to IP mapping, e.g. --map box.lan=127.0.0.1 (repeatable)",
    )
    ap.add_argument(
        "--rewrite80-to",
        type=int,
        default=8080,
        help="For mapped hosts only: rewrite port 80 to this port (default: 8080). Use 0 to disable.",
    )
    ap.add_argument(
        "--strip-backend-port",
        type=int,
        default=8085,
        help="Rewrite Location to strip this port (default: 8085). Use 0 to disable.",
    )

    # Daemon controls
    ap.add_argument("-d", "--daemon", action="store_true", help="Run in background (daemon mode)")
    ap.add_argument("--logfile", default="~/boxproxy.log", help="Log file path (default: ~/boxproxy.log)")
    ap.add_argument("--pidfile", default="~/.boxproxy.pid", help="PID file path (default: ~/.boxproxy.pid)")
    ap.add_argument("--stop", action="store_true", help="Stop background instance (uses pidfile)")
    ap.add_argument("--status", action="store_true", help="Show status (uses pidfile)")

    return ap.parse_args()


def main():
    args = parse_args()

    # Default mapping: if user didn't provide --map, assume <public-host>=127.0.0.1
    if not args.map:
        args.map = [f"{args.public_host}=127.0.0.1"]

    pidfile = os.path.expanduser(args.pidfile)
    logfile = os.path.expanduser(args.logfile)

    if args.stop:
        stop_daemon(pidfile)
        return

    if args.status:
        status_daemon(pidfile)
        return

    if args.daemon:
        daemonize(logfile, pidfile)

    try:
        asyncio.run(main_async(args))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
