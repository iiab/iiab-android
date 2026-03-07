# shellcheck shell=bash
# Module file (no shebang). Bundled by build_bundle.sh

# -------------------------
# ADB wireless pair/connect wizard
# -------------------------

# Local stamp so we can detect "connect-only" misuse after reinstall/clear-data.
ADB_PAIRED_STAMP="${ADB_STATE_DIR}/stamp.adb_paired"

# -------------------------
# mDNS / Zeroconf autodiscovery (Wireless debugging ports)
# -------------------------
# Defaults + python/zeroconf helpers are defined in 20_mod_termux_base.sh.
ADB_MDNS_WAIT_SECS="${ADB_MDNS_WAIT_SECS:-90}"
# If ports were discovered via mDNS, use a shorter/relaxed timeout for the PAIR CODE prompt
ADB_CODE_TIMEOUT_SECS="${ADB_CODE_TIMEOUT_SECS:-90}"
# Notification ID for the "open wireless debugging" hint (separate from ask/reply notifications)
ADB_HINT_NOTIF_ID="${ADB_HINT_NOTIF_ID:-$((NOTIF_BASE_ID + 30))}"

adb_local_ipv4s_csv() {
  termux-wifi-connectioninfo 2>/dev/null \
    | jq -r '.ip // empty' \
    | awk 'NF && $0!="127.0.0.1"{print $0}' \
    | paste -sd, - 2>/dev/null \
    || true
}

adb_hint_notif_post() {
  have termux-notification || return 1
  have termux-notification-remove && termux-notification-remove "$ADB_HINT_NOTIF_ID" >/dev/null 2>&1 || true
  termux-notification \
    --id "$ADB_HINT_NOTIF_ID" \
    --ongoing \
    --priority max \
    --title "Wireless debugging" \
    --content "Go to: Developer options -> Wireless debugging -> Pair device (pairing code)." \
    >/dev/null 2>&1 || return 1
  return 0
}

adb_hint_notif_remove() {
  have termux-notification-remove || return 0
  termux-notification-remove "$ADB_HINT_NOTIF_ID" >/dev/null 2>&1 || true
}

adb_prepare_wireless_debugging_ui() {
  # Best-effort: bring user close to Wireless debugging before starting the mDNS timer.
  warn "Opening Developer options. Please enable Wireless debugging, then choose 'Pair device with pairing code'."
  if android_open_developer_options; then
    ok "Developer options opened."
  else
    warn "Could not open Developer options automatically. Open manually: Settings -> System -> Developer options."
  fi
  # Optional hint notification while user navigates UI
  adb_hint_notif_post || true
}

adb_mdns_scan_ports_py() {
  # args: wait_seconds local_ips_csv
  local wait="${1:-90}" ips="${2:-}"
  local py=""
  py="$(command -v python 2>/dev/null || command -v python3 2>/dev/null || true)"
  [[ -n "$py" ]] || return 1
  LOCAL_IPS="$ips" "$py" - "$wait" <<'PY'
import os, sys, time
try:
    from zeroconf import Zeroconf, ServiceBrowser
except Exception:
    sys.exit(1)

CONNECT_TYPE = "_adb-tls-connect._tcp.local."
PAIR_TYPE    = "_adb-tls-pairing._tcp.local."

deadline = time.time() + max(1, int(sys.argv[1])) if len(sys.argv) > 1 else time.time() + 90
local_ips = set([x.strip() for x in os.environ.get("LOCAL_IPS","").split(",") if x.strip()])

found = {CONNECT_TYPE: [], PAIR_TYPE: []}  # list of (port, addrs)

def addr_match(addrs):
    if not local_ips:
        return True
    return any(a in local_ips for a in addrs)

class Listener:
    def add_service(self, zc, type_, name):
        info = zc.get_service_info(type_, name, timeout=1200)
        if not info:
            return
        try:
            addrs = info.parsed_addresses()
        except Exception:
            addrs = []
        if not addr_match(addrs):
            return
        found.setdefault(type_, []).append((info.port, addrs))

    def update_service(self, zc, type_, name):
        self.add_service(zc, type_, name)

    def remove_service(self, zc, type_, name):
        return

zc = Zeroconf()
try:
    ServiceBrowser(zc, [CONNECT_TYPE, PAIR_TYPE], listener=Listener())
    while time.time() < deadline:
        # stop early when we have at least one candidate for both
        if found.get(CONNECT_TYPE) and found.get(PAIR_TYPE):
            break
        time.sleep(0.2)
finally:
    try:
        zc.close()
    except Exception:
        pass

def pick_first_port(type_):
    items = found.get(type_) or []
    if not items:
        return None
    # pick first seen; on-device should usually be unique
    return items[0][0]

cp = pick_first_port(CONNECT_TYPE)
pp = pick_first_port(PAIR_TYPE)
if cp:
    print(f"CONNECT_PORT={cp}")
if pp:
    print(f"PAIR_PORT={pp}")
PY
}

adb_mdns_autodetect_pair_and_connect_ports() {
  # Returns: "CONNECT_PORT PAIR_PORT" (5-digit ports only), or fails.
  python_ensure_zeroconf || return 1
  local ips out cp pp
  ips="$(adb_local_ipv4s_csv 2>/dev/null || true)"
  out="$(adb_mdns_scan_ports_py "${ADB_MDNS_WAIT_SECS:-90}" "$ips" 2>/dev/null || true)"
  cp="$(printf '%s\n' "$out" | sed -n 's/^CONNECT_PORT=//p' | head -n1)"
  pp="$(printf '%s\n' "$out" | sed -n 's/^PAIR_PORT=//p' | head -n1)"
  [[ "$cp" =~ ^[0-9]{5}$ ]] || return 1
  [[ "$pp" =~ ^[0-9]{5}$ ]] || return 1
  printf '%s %s\n' "$cp" "$pp"
}

adb_mdns_autodetect_connect_port_only() {
  # Returns: "CONNECT_PORT" (5-digit only), or fails.
  python_ensure_zeroconf || return 1
  local ips out cp
  ips="$(adb_local_ipv4s_csv 2>/dev/null || true)"
  out="$(adb_mdns_scan_ports_py "${ADB_MDNS_WAIT_SECS:-90}" "$ips" 2>/dev/null || true)"
  cp="$(printf '%s\n' "$out" | sed -n 's/^CONNECT_PORT=//p' | head -n1)"
  [[ "$cp" =~ ^[0-9]{5}$ ]] || return 1
  printf '%s\n' "$cp"
}

adb_hostkey_fingerprint() {
  # Returns a stable fingerprint for THIS Termux install's adb host key.
  # Defaulted to sha256 being available/confirmed in the baseline.
  local pub="${HOME}/.android/adbkey.pub"
  [[ -r "$pub" ]] || return 1
  sha256sum "$pub" | awk '{print $1}'
}

adb_stamp_write() {
  # args: mode serial
  local mode="$1" serial="$2" fp=""
  fp="$(adb_hostkey_fingerprint 2>/dev/null || true)"
  {
    echo "ts=$(date -Is 2>/dev/null || date || true)"
    echo "mode=${mode}"
    echo "host=${HOST}"
    echo "serial=${serial}"
    echo "connect_port=${CONNECT_PORT:-}"
    echo "hostkey_fp=${fp}"
  } >"$ADB_PAIRED_STAMP" 2>/dev/null || true
  chmod 600 "$ADB_PAIRED_STAMP"
}

adb_stamp_read_fp() {
  [[ -r "$ADB_PAIRED_STAMP" ]] || return 1
  sed -n 's/^hostkey_fp=//p' "$ADB_PAIRED_STAMP" 2>/dev/null | head -n 1
}

adb_warn_connect_only_if_suspicious() {
  # Called only in connect-only flows.
  local cur_fp old_fp
  cur_fp="$(adb_hostkey_fingerprint 2>/dev/null || true)"
  old_fp="$(adb_stamp_read_fp 2>/dev/null || true)"

  if [[ ! -f "$ADB_PAIRED_STAMP" ]]; then
    warn "connect-only assumes THIS Termux install has been paired before."
    warn "No local pairing stamp found ($ADB_PAIRED_STAMP)."
    warn "If you reinstalled Termux / cleared data / changed user, you must re-pair (run: --adb-only)."
    [[ -n "$cur_fp" ]] && warn "Current ADB hostkey fingerprint: ${cur_fp:0:12}..."
    return 0
  fi

  if [[ -n "$old_fp" && -n "$cur_fp" && "$old_fp" != "$cur_fp" ]]; then
    warn_red "ADB host key changed since last pairing stamp."
    warn "Old fingerprint: ${old_fp:0:12}...  Current: ${cur_fp:0:12}..."
    warn "Android Wireless debugging -> Paired devices: remove the old entry, then run: --adb-only"
  fi
}

adb_connect_verify() {
  # args: serial (HOST:PORT)
  local serial="$1" out rc start now state
  set +e
  out="$(adb connect "$serial" 2>&1)"
  rc=$?
  set -e

  # Always verify via `adb devices` (adb may exit 0 even on failure).
  start="$(date +%s)"
  while true; do
    state="$(adb_device_state "$serial" || true)"
    [[ "$state" == "device" ]] && { printf '%s\n' "$out"; return 0; }
    now="$(date +%s)"
    (( now - start >= 5 )) && break
    sleep 1
  done

  warn_red "adb connect did not result in a usable device entry for: $serial (state='${state:-none}')."
  warn "adb connect output: ${out:-<none>}"
  warn "If you recently reinstalled Termux/cleared data, the phone may show an OLD paired device. Remove it and re-pair."
  return 1
}

cleanup_offline_loopback() {
  local keep_serial="$1"  # e.g. 127.0.0.1:41313
  local serial state rest
  while read -r serial state rest; do
    [[ -n "${serial:-}" ]] || continue
    [[ "$serial" == ${HOST}:* ]] || continue
    [[ "$state" == "offline" ]] || continue
    [[ "$serial" == "$keep_serial" ]] && continue
    adb disconnect "$serial" >/dev/null 2>&1 || true
  done < <(adb devices 2>/dev/null | tail -n +2 | sed '/^[[:space:]]*$/d')
}

adb_pair_connect() {
  need adb || die "Missing adb. Install: pkg install android-tools"

  # Only require Termux:API when we will prompt the user
  if [[ "$ONLY_CONNECT" != "1" || -z "${CONNECT_PORT:-}" ]]; then
    termux_api_ready || die "Termux:API not ready."
  fi

  echo "[*] adb: $(adb version | head -n 1)"
  adb start-server >/dev/null 2>&1 || true

  if [[ "$ONLY_CONNECT" == "1" ]]; then
    adb_warn_connect_only_if_suspicious
    if [[ -n "$CONNECT_PORT" ]]; then
      local raw="$CONNECT_PORT" norm=""
      norm="$(normalize_port_5digits "$raw" 2>/dev/null)" || \
        die "Invalid CONNECT PORT (must be 5 digits PORT or IP:PORT): '$raw'"
      CONNECT_PORT="$norm"
    else
      echo "[*] CONNECT PORT not provided; asking..."
      CONNECT_PORT="$(ask_connect_port_5digits connect "CONNECT PORT")" || die "Timeout waiting CONNECT PORT."
    fi

    local serial="${HOST}:${CONNECT_PORT}"
    adb disconnect "$serial" >/dev/null 2>&1 || true
    echo "[*] adb connect $serial"
    adb_connect_verify "$serial" >/dev/null || die "adb connect failed to $serial. Verify Wireless debugging is enabled, and pairing exists for THIS Termux install."

    if [[ "$CLEANUP_OFFLINE" == "1" ]]; then
      cleanup_offline_loopback "$serial"
    fi

    echo "[*] Devices:"
    adb devices -l

    echo "[*] ADB check (shell):"
    adb -s "$serial" shell sh -lc 'echo "it worked: adb shell is working"; id' || true
    adb_stamp_write "connect-only" "$serial"

    cleanup_notif
    ok "ADB connected (connect-only): $serial"
    return 0
  fi

  if [[ -n "$CONNECT_PORT" ]]; then
    local raw="$CONNECT_PORT" norm=""
    norm="$(normalize_port_5digits "$raw" 2>/dev/null)" || \
      die "Invalid --connect-port (must be 5 digits PORT or IP:PORT): '$raw'"
    CONNECT_PORT="$norm"
  else
    # mDNS window (90s): open Developer options, scan for both connect+pair ports.
    local ports="" pair_port="" auto_ports=0
    if [[ "${ADB_MDNS:-0}" -eq 1 ]] && [[ "${ANDROID_SDK:-}" =~ ^[0-9]+$ ]] && (( ANDROID_SDK >= 30 )); then
      if python_ensure_zeroconf; then
        adb_prepare_wireless_debugging_ui
        if ports="$(adb_mdns_autodetect_pair_and_connect_ports 2>/dev/null)"; then
          CONNECT_PORT="${ports%% *}"
          pair_port="${ports##* }"
          auto_ports=1
          ok "Auto-detected ports via mDNS: CONNECT=$CONNECT_PORT PAIR=$pair_port"
        else
          warn "mDNS auto-detect timed out (${ADB_MDNS_WAIT_SECS}s). Falling back to manual prompts."
        fi
      else
        # python_ensure_zeroconf already warned; keep this generic to avoid duplicates.
        warn "mDNS auto-detect unavailable. Falling back to manual prompts."
      fi
    else
      warn "mDNS auto-detect disabled (ADB_MDNS=0). Falling back to manual prompts."
    fi

    if [[ "$auto_ports" -ne 1 ]]; then
      warn "Manual ports needed. On Android open: Settings -> System -> Developer options -> Wireless debugging."
      warn "CONNECT PORT: shown as 'IP address & port' on the Wireless debugging screen."
      warn "PAIR PORT: shown inside 'Pair device with pairing code' dialog (keep it open)."
      echo "[*] Asking CONNECT PORT..."
      CONNECT_PORT="$(ask_connect_port_5digits connect "CONNECT PORT")" || die "Timeout waiting CONNECT PORT."
      echo "[*] Asking PAIR PORT..."
      pair_port="$(ask_pair_port_5digits pair "PAIR PORT")" || die "Timeout waiting PAIR PORT."
    fi
    adb_hint_notif_remove || true

    echo "[*] Asking PAIR CODE..."
    local code old_timeout
    if [[ "$auto_ports" -eq 1 ]]; then
      old_timeout="$TIMEOUT_SECS"
      TIMEOUT_SECS="${ADB_CODE_TIMEOUT_SECS:-90}"
      code="$(ask_code_6digits)" || { TIMEOUT_SECS="$old_timeout"; die "Timeout waiting PAIR CODE."; }
      TIMEOUT_SECS="$old_timeout"
    else
      code="$(ask_code_6digits)" || die "Timeout waiting PAIR CODE."
    fi

    local serial="${HOST}:${CONNECT_PORT}"
    adb disconnect "$serial" >/dev/null 2>&1 || true

    echo "[*] adb pair ${HOST}:${pair_port}"
    printf '%s\n' "$code" | adb pair "${HOST}:${pair_port}" || die "adb pair failed. Verify PAIR PORT and PAIR CODE (and that the pairing dialog is showing)."

    echo "[*] adb connect $serial"
    adb_connect_verify "$serial" >/dev/null || die "adb connect failed after pairing. Re-check CONNECT PORT and Wireless debugging."

    if [[ "$CLEANUP_OFFLINE" == "1" ]]; then
      cleanup_offline_loopback "$serial"
    fi

    echo "[*] Devices:"
    adb devices -l

    echo "[*] ADB check (shell):"
    adb -s "$serial" shell sh -lc 'echo "it worked: adb shell is working"; getprop ro.product.model; getprop ro.build.version.release' || true
    adb_stamp_write "paired" "$serial"

    cleanup_notif
    ok "ADB connected: $serial"
    return 0
  fi

  # If --connect-port was provided, keep the original manual prompts (no 90s delay).
  echo "[*] Asking PAIR PORT..."
  local pair_port
  pair_port="$(ask_pair_port_5digits pair "PAIR PORT")" || die "Timeout waiting PAIR PORT."

  echo "[*] Asking PAIR CODE..."
  local code
  code="$(ask_code_6digits)" || die "Timeout waiting PAIR CODE."

  local serial="${HOST}:${CONNECT_PORT}"
  adb disconnect "$serial" >/dev/null 2>&1 || true

  echo "[*] adb pair ${HOST}:${pair_port}"
  printf '%s\n' "$code" | adb pair "${HOST}:${pair_port}" || die "adb pair failed. Verify PAIR PORT and PAIR CODE (and that the pairing dialog is showing)."

  echo "[*] adb connect $serial"
  adb_connect_verify "$serial" >/dev/null || die "adb connect failed after pairing. Re-check CONNECT PORT and Wireless debugging."

  if [[ "$CLEANUP_OFFLINE" == "1" ]]; then
    cleanup_offline_loopback "$serial"
  fi

  echo "[*] Devices:"
  adb devices -l

  echo "[*] ADB check (shell):"
  adb -s "$serial" shell sh -lc 'echo "it worked: adb shell is working"; getprop ro.product.model; getprop ro.build.version.release' || true
  adb_stamp_write "paired" "$serial"

  cleanup_notif
  ok "ADB connected: $serial"
}

# Return state for an exact serial (e.g. "device", "offline", empty)
adb_device_state() {
  local s="$1"
  adb devices 2>/dev/null | awk -v s="$s" 'NR>1 && $1==s {print $2; exit}'
}

# Return first loopback serial in "device" state (e.g. 127.0.0.1:41313)
adb_any_loopback_device() {
  adb devices 2>/dev/null | awk -v h="$HOST" '
    NR>1 && $2=="device" && index($1, h":")==1 {print $1; found=1; exit}
    END { exit (found ? 0 : 1) }
  '
}

# Pick the loopback serial we will operate on:
# - If CONNECT_PORT is set, require that exact HOST:PORT to be in "device" state.
# - Otherwise, return the first loopback device.
adb_pick_loopback_serial() {
  if [[ -n "${CONNECT_PORT:-}" ]]; then
      local raw="$CONNECT_PORT" p=""
      p="$(normalize_port_5digits "$raw" 2>/dev/null)" || return 1
      local target="${HOST}:${p}"
    [[ "$(adb_device_state "$target")" == "device" ]] && { echo "$target"; return 0; }
    return 1
  fi
  adb_any_loopback_device
}

# If already connected, avoid re-pairing/re-connecting prompts (useful for --all),
# BUT only consider loopback/target connections as "already connected".
adb_pair_connect_if_needed() {
  need adb || die "Missing adb. Install: pkg install android-tools"
  adb start-server >/dev/null 2>&1 || true

  local serial=""

  # If user provided a connect-port, insist on that exact target serial.
  if [[ -n "${CONNECT_PORT:-}" ]]; then
    local raw="$CONNECT_PORT" norm=""
    norm="$(normalize_port_5digits "$raw" 2>/dev/null)" || \
      die "Invalid --connect-port (must be 5 digits PORT or IP:PORT): '$raw'"
    CONNECT_PORT="$norm"

    local target="${HOST}:${CONNECT_PORT}"

    if [[ "$(adb_device_state "$target")" == "device" ]]; then
      ok "ADB already connected to target: $target (skipping pair/connect)."
      return 0
    fi

    # Try connect-only first (in case it was already paired before)
    adb connect "$target" >/dev/null 2>&1 || true
    if [[ "$(adb_device_state "$target")" == "device" ]]; then
      ok "ADB connected to target: $target (connect-only succeeded; skipping pair)."
      return 0
    fi

    # Not connected: run full wizard (pair+connect)
    adb_pair_connect
    return $?
  fi

  # No explicit port: only skip if we already have a loopback device connected.
  if serial="$(adb_any_loopback_device 2>/dev/null)"; then
    ok "ADB already connected (loopback): $serial (skipping pair/connect)."
    return 0
  fi

  adb_pair_connect
}

require_adb_connected() {
  need adb || { warn_red "Missing adb. Install: pkg install android-tools"; return 1; }
  adb start-server >/dev/null 2>&1 || true
  if ! adb_pick_loopback_serial >/dev/null 2>&1; then
    warn_red "No ADB device connected."
    warn "If already paired before: run --connect-only [PORT]."
    warn "Otherwise: run --adb-only to pair+connect."
    return 1
  fi
  return 0
}

adb_loopback_serial_or_die() {
  local s
  s="$(adb_pick_loopback_serial 2>/dev/null)" || return 1
  echo "$s"
}

run_if_adb_connected() {
  # run input command only if adb_pick_loopback_serial is connected 
  if adb_pick_loopback_serial >/dev/null 2>&1; then
    "$@"
  fi
}
