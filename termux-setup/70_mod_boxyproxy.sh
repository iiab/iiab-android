# shellcheck shell=bash
# Module file (no shebang). Bundled by build_bundle.sh

# -----------------------------------------------------------------------------
# boxyproxy.py companion management
# - Installs/updates to: $PREFIX/bin/boxyproxy.py
# - Runtime state: ~/.iiab-android/boxyproxy/
# -----------------------------------------------------------------------------

BOXYPROXY_BIN="${PREFIX}/bin/boxyproxy.py"
BOXYPROXY_PIDFILE="${STATE_DIR}/boxyproxy.pid"
BOXYPROXY_LOG="${LOG_DIR}/boxyproxy.log"

# DNF: Confirm final URL
BOXYPROXY_RAW_URL_DEFAULT="https://raw.githubusercontent.com/iiab/iiab-android/main/termux-setup/proxy/boxyproxy.py"
BOXYPROXY_RAW_URL="${BOXYPROXY_RAW_URL:-$BOXYPROXY_RAW_URL_DEFAULT}"

boxyproxy_is_installed() { [[ -x "$BOXYPROXY_BIN" ]]; }

boxyproxy_state_init() {
  mkdir -p "$STATE_DIR" "$LOG_DIR" >/dev/null 2>&1 || true
}

boxyproxy_is_running() {
  boxyproxy_is_installed || return 1
  "$BOXYPROXY_BIN" --status --pidfile "$BOXYPROXY_PIDFILE" 2>/dev/null | grep -q "running"
}

boxyproxy_install_or_update() {
  boxyproxy_state_init
  have curl || { log "Installing curl (needed to fetch boxyproxy.py)"; termux_apt update || true; termux_apt install curl || return 1; }

  mkdir -p "${PREFIX}/bin" >/dev/null 2>&1 || true
  local tmp="${BOXYPROXY_BIN}.tmp.$$"
  log "Updating boxyproxy.py -> ${BOXYPROXY_BIN}"
  if ! curl -fsSL --retry 3 --retry-delay 1 --connect-timeout 8 --max-time 45 \
      "$BOXYPROXY_RAW_URL" -o "$tmp"; then
    warn_red "Failed downloading boxyproxy.py from: $BOXYPROXY_RAW_URL"
    rm -f "$tmp" >/dev/null 2>&1 || true
    return 1
  fi

  # Minimal sanity check: must look like a python script.
  if ! head -n1 "$tmp" 2>/dev/null | grep -qE '^#!.*python'; then
    warn_red "Downloaded file does not look like a python script (missing shebang)."
    rm -f "$tmp" >/dev/null 2>&1 || true
    return 1
  fi

  chmod 700 "$tmp" >/dev/null 2>&1 || true
  mv -f "$tmp" "$BOXYPROXY_BIN" || return 1
  chmod 700 "$BOXYPROXY_BIN" >/dev/null 2>&1 || true
  ok "boxyproxy.py installed/updated: $BOXYPROXY_BIN"
  return 0
}

boxyproxy_start() {
  boxyproxy_state_init
  boxyproxy_is_running && { ok "boxyproxy already running."; return 0; }

  boxyproxy_is_installed || {
    warn "boxyproxy.py not installed yet. Installing now..."
    boxyproxy_install_or_update || return 1
  }

  local -a args=()
  if [[ -n "${BOXYPROXY_ARGS:-}" ]]; then
    args=( $BOXYPROXY_ARGS )
  fi
  "$BOXYPROXY_BIN" -d \
    --pidfile "$BOXYPROXY_PIDFILE" \
    --logfile "$BOXYPROXY_LOG" \
    "${args[@]}" >/dev/null 2>&1 || true

  "$BOXYPROXY_BIN" --status --pidfile "$BOXYPROXY_PIDFILE" 2>/dev/null | indent || true
  boxyproxy_is_running || { warn_red "boxyproxy failed to start (see $BOXYPROXY_LOG)"; return 1; }
  ok "boxyproxy started."
  return 0
}

boxyproxy_stop() {
  boxyproxy_state_init
  boxyproxy_is_installed || return 0
  "$BOXYPROXY_BIN" --stop --pidfile "$BOXYPROXY_PIDFILE" 2>/dev/null | indent || true
  return 0
}

boxyproxy_status() {
  boxyproxy_state_init
  boxyproxy_is_installed || { echo "[boxyproxy] installed=no"; return 0; }
  "$BOXYPROXY_BIN" --status --pidfile "$BOXYPROXY_PIDFILE" 2>/dev/null || true
  echo "[boxyproxy] bin=$BOXYPROXY_BIN"
  echo "[boxyproxy] raw_url=$BOXYPROXY_RAW_URL"
  echo "[boxyproxy] log=$BOXYPROXY_LOG"
}
