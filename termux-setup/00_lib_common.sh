# shellcheck shell=bash
# Module file (no shebang). Bundled by build_bundle.sh

RED="\033[31m"; YEL="\033[33m"; GRN="\033[32m"; BLU="\033[34m"; RST="\033[0m"; BOLD="\033[1m"

log()       { printf "${BLU}[iiab]${RST} %s\n" "$*"; }
log_yel()   { printf "${YEL}[iiab]${RST} %s\n" "$*"; }
ok()        { printf "${GRN}[iiab]${RST} %s\n" "$*"; }
warn()      { printf "${YEL}[iiab] WARNING:${RST} %s\n" "$*" >&2; }
warn_red()  { printf "${RED}${BOLD}[iiab] WARNING:${RST} %s\n" "$*" >&2; }
boxyp_log() { printf "${BLU}[boxyproxy]${RST} %s\n" "$*"; }
indent()    { sed 's/^/ /'; }

have() { command -v "$1" >/dev/null 2>&1; }
need() { have "$1" || return 1; }
die()  { echo "[!] $*" >&2; exit 1; }

blank() {
  local n="${1:-1}" fd=1
  [[ "$n" =~ ^[0-9]+$ ]] || n=1
  if { : >&3; } 2>/dev/null; then fd=3; fi
  while (( n-- > 0 )); do printf '\n' >&"$fd"; done
}

# Choose warning level depending on context.
# - In explicit readiness checks (--check/--all), use red for "will likely fail".
# - In passive/self-check (baseline runs), keep it yellow to avoid over-alarming.
warn_red_context() {
  # args: long message
  if [[ "${MODE:-}" == "check" || "${MODE:-}" == "all" || "${MODE:-}" == "ppk-only" ]]; then
    warn_red "$*"
  else
    warn "$*"
  fi
}

# -------------------------
# Global defaults (may be overridden via environment)
# -------------------------
STATE_DIR="${STATE_DIR:-${HOME}/.iiab-android}"
ADB_STATE_DIR="${ADB_STATE_DIR:-${STATE_DIR}/adbw_pair}"
LOG_DIR="${LOG_DIR:-${STATE_DIR}/logs}"

HOST="${HOST:-127.0.0.1}"
CONNECT_PORT="${CONNECT_PORT:-}"
TIMEOUT_SECS="${TIMEOUT_SECS:-180}"

# Defaults used by ADB flows / logging / misc
CLEANUP_OFFLINE="${CLEANUP_OFFLINE:-1}"
DEBUG="${DEBUG:-0}"
BOXYPROXY_NO_EXTERNAL="${BOXYPROXY_NO_EXTERNAL:-0}"

# Package name for the Termux app.
TERMUX_PACKAGE="${TERMUX_PACKAGE:-com.termux}"

# Version and Update logic
IIAB_TERMUX_RAW_URL="${IIAB_TERMUX_RAW_URL:-https://raw.githubusercontent.com/iiab/iiab-android/main/iiab-termux}"

get_iiab_termux_version() {
  local file="${1:-$0}"
  if [[ -f "$file" ]]; then
    grep '^# GENERATED FILE:' "$file" 2>/dev/null | sed -E 's/.*GENERATED FILE: ([^ ]+).*/\1/' | head -n1 || echo "unknown"
  else
    echo "unknown"
  fi
}

update_iiab_termux() {
  local current_file="${1:-$0}"
  log "downloading latest version..."
  have curl || { warn_red "curl is required to update. Run: pkg install curl"; return 1; }

  # Use the state directory since Termux lacks a standard /tmp
  local tmp_file="${STATE_DIR}/iiab-termux-update.tmp.$$"

  if ! curl -fsSL --retry 5 --retry-connrefused --retry-delay 2 "$IIAB_TERMUX_RAW_URL" -o "$tmp_file"; then
    warn_red "Failed to download update from $IIAB_TERMUX_RAW_URL"
    rm -f "$tmp_file" >/dev/null 2>&1 || true
    return 1
  fi

  local first_line=""
  IFS= read -r first_line < "$tmp_file" || true
  if [[ "$first_line" != \#!*bash* ]]; then
    warn_red "Downloaded file doesn't look like a valid bash script (bad shebang)."
    rm -f "$tmp_file" >/dev/null 2>&1 || true
    return 1
  fi

  local old_v new_v backup_dir ts
  old_v="$(get_iiab_termux_version "$current_file")"
  new_v="$(get_iiab_termux_version "$tmp_file")"

  # Compare versions if both are successfully parsed
  if [[ "$old_v" != "unknown" && "$new_v" != "unknown" ]]; then
    if [[ "$old_v" == "$new_v" ]]; then
      ok "You already have the latest version ($old_v)."
      rm -f "$tmp_file" >/dev/null 2>&1 || true
      return 0
    elif [[ "$old_v" > "$new_v" ]]; then
      log_yel "Your local version ($old_v) appears to be newer than the repository version ($new_v)."
      log_yel "You might be running local uncommitted changes."
      if ! tty_yesno_default_n "[iiab] Do you want to overwrite your local version with the repository version? [y/N]: "; then
        log "Update aborted by user. Keeping local version."
        rm -f "$tmp_file" >/dev/null 2>&1 || true
        return 0
      fi
    fi
  fi

  log "updating iiab-termux $new_v over $old_v"

  backup_dir="${STATE_DIR}/termux"
  mkdir -p "$backup_dir"
  ts="$(date +%Y%m%d-%H%M%S)"
  cp -f "$current_file" "${backup_dir}/iiab-termux.old.${ts}" 2>/dev/null || true

  mv -f "$tmp_file" "$current_file" && chmod 700 "$current_file"
  log "installed version: $new_v"
  return 0
}

# One-time helper: guide user to set Termux battery policy to keep sessions alive.
POWER_MODE_BATTERY_PROMPT="${POWER_MODE_BATTERY_PROMPT:-1}"  # 1=ask, 0=never ask
POWER_MODE_BATTERY_STAMP="${POWER_MODE_BATTERY_STAMP:-$STATE_DIR/stamp.termux_battery_settings}"

export PIP_EXTRA_INDEX_URL="https://iiab.switnet.org/simple"

tty_prompt_print() {
  local prompt="$1" outfd=1
  # Prefer original console FD3 if available (set by setup_logging)
  if [[ -t 3 ]]; then
    outfd=3
  fi
  printf '%b' "$prompt" >&"$outfd"
}

fd3_available() { : >&3 2>/dev/null; }
fd4_available() { : >&4 2>/dev/null; }

console_outfd() { fd3_available && echo 3 || echo 1; }
console_errfd() { fd4_available && echo 4 || echo 2; }

tty_yesno_default_y() {
  # args: prompt
  # Returns 0 for Yes, 1 for No. Default is Yes.
  local prompt="$1" ans="Y" outfd
  outfd="$(console_outfd)"
  if [[ -r /dev/tty ]]; then
    tty_prompt_print "$prompt"
    if ! read -r ans < /dev/tty; then
      ans="Y"
    fi
  else
    warn "No /dev/tty available; defaulting to YES."
    ans="Y"
  fi
  ans="${ans:-Y}"
  [[ "$ans" =~ ^[Nn]$ ]] && return 1
  return 0
}

tty_yesno_default_n() {
  # args: prompt
  # Returns 0 for Yes, 1 for No. Default is No.
  local prompt="$1" ans="N" outfd
  outfd="$(console_outfd)"
  if [[ -r /dev/tty ]]; then
    tty_prompt_print "$prompt"
    read -r ans < /dev/tty || ans="N"
  else
    warn "No /dev/tty available; defaulting to NO."
    ans="N"
  fi
  ans="${ans:-N}"
  [[ "$ans" =~ ^[Yy]$ ]] && return 0
  return 1
}

iiab_login() {
  local stamp="$STATE_DIR/stamp.termux_base"

  # Baseline stamp is advisory only for login (do not block).
  if [[ -f "$stamp" ]]; then
    ok "Baseline stamp found: $stamp"
  else
    warn_red "Baseline stamp not found ($stamp)."
    warn "Tip: run the baseline once: iiab-termux"
  fi

  have proot-distro || die "proot-distro not found. Install baseline first (pkg install proot-distro or run iiab-termux)."
  if ! iiab_exists; then
    warn_red "IIAB Debian is not installed in proot-distro (alias 'iiab' missing)."
    warn "Recommended: iiab-termux --all"
    warn "Or:          proot-distro install --override-alias iiab debian"
    return 1
  fi

  # Reminder: Android battery policy must be configured before long installs.
  if [[ "${POWER_MODE_BATTERY_PROMPT:-1}" -eq 1 ]]; then
    local bst="$POWER_MODE_BATTERY_STAMP"
    if [[ ! -f "$bst" ]]; then
      warn "Reminder: for reliable long installs, set Termux -> Battery to 'Unrestricted'."
      power_mode_battery_instructions
      if tty_yesno_default_n "[iiab] Open Termux App info now to adjust Battery policy? [y/N]: "; then
        if android_open_termux_app_info; then
          tty_prompt_print "[iiab] When done, return to Termux and press Enter to continue... "
          if [[ -r /dev/tty ]]; then
            read -r _ </dev/tty || true
          else
            local outfd
            outfd="$(console_outfd)"
            printf "\n" >&"$outfd"
          fi
          date > "$bst" 2>/dev/null || true
        else
          warn "Unable to open Settings automatically. Open manually: Settings -> Apps -> Termux."
        fi
      fi
    fi
  fi

  # Best-effort Android advice before user starts doing heavy installs inside proot.
  local sdk="${ANDROID_SDK:-}"
  if [[ "$sdk" =~ ^[0-9]+$ ]] && (( sdk >= 31 && sdk <= 33 )); then
    # Android 12-13: PPK is a common hard failure point.
    if have adb; then
      adb start-server >/dev/null 2>&1 || true
      if adb_pick_loopback_serial >/dev/null 2>&1; then
        check_readiness || true
      else
        warn_red "Android 12-13: ADB is not connected, so PPK=256 cannot be verified/applied."
        warn "Before running the IIAB installer inside proot, run:"
        ok   "  iiab-termux --all"
      fi
    else
      warn_red "Android 12-13: adb is missing, so PPK=256 cannot be verified/applied."
      warn "Install adb (android-tools) and run:"
      ok   "  iiab-termux --all"
    fi
  fi

  ok "Entering IIAB Debian (via: iiab-termux --login)"
  power_mode_login_enter || true

  # Auto-start boxyproxy for the duration of this proot session.
  BOXYPROXY_MANAGED=1
  if boxyproxy_is_running; then
    if [[ "${BOXYPROXY_NO_EXTERNAL:-0}" -eq 1 ]]; then
      boxyp_log "Auto-start: restarting proxy to apply --no-external..."
      boxyproxy_stop >/dev/null 2>&1 || true
      boxyproxy_start >/dev/null 2>&1 || true
    else
      boxyp_log "Auto-start: already running (will stop on exit)."
    fi
  else
    # Best-effort: install once if missing (avoid forcing updates every login).
    if ! boxyproxy_is_installed; then
      boxyp_log "Auto-start: installing boxyproxy.py (best-effort)..."
      boxyproxy_install_or_update >/dev/null 2>&1 || true
    fi
    boxyproxy_start >/dev/null 2>&1 || true
  fi

  # Preserve interactivity even if logging is enabled (avoid pipes/tee issues).
  local rc=0
  local outfd errfd
  outfd="$(console_outfd)"
  errfd="$(console_errfd)"

  # Don't let a non-zero exit from proot skip cleanup (set -e).
  set +e
  if [[ -r /dev/tty ]]; then
    proot-distro login iiab </dev/tty >&"$outfd" 2>&"$errfd"
    rc=$?
  else
    proot-distro login iiab
    rc=$?
  fi
  set -e

  # Stop proxy after leaving proot session (also covered by trap for signals).
  boxyproxy_stop >/dev/null 2>&1 || true
  BOXYPROXY_MANAGED=0

  power_mode_login_exit || true
  return $rc
}

# -------------------------
# Help / usage (static text)
# -------------------------
usage() {
  cat <<'EOF'
Usage:
  iiab-termux  --version
    -> Show current installed version of iiab-termux

  iiab-termux --update
    -> Update iiab-termux script to the latest version

  iiab-termux
    -> Termux baseline + IIAB Debian bootstrap (idempotent). No ADB prompts.

  iiab-termux --login
    -> Login into IIAB Debian (iiab-termux --login).

  iiab-termux --with-adb
    -> Termux baseline + IIAB Debian bootstrap + ADB pair/connect if needed (skips if already connected).

  iiab-termux  --adb-only [--connect-port PORT|IP:PORT]
    -> Only ADB pair/connect if needed (no IIAB Debian; skips if already connected).
       Tip: --connect-port skips the CONNECT PORT prompt (you’ll still be asked for PAIR PORT + PAIR CODE).

  iiab-termux --connect-only [PORT|IP:PORT]
    -> Connect-only (no pairing). Use this after the device was already paired before.

  iiab-termux --ppk-only
    -> Set PPK only: max_phantom_processes=256 (requires ADB already connected).
       Android 14-16 usually achieve this via "Disable child process restrictions" in Developer Options.

  iiab-termux --iiab-android
    -> Install/update 'iiab-android' command inside IIAB Debian (does NOT run it).

  iiab-termux --check
    -> Check readiness: developer options flag (if readable),
       (Android 14+) "Disable child process restrictions" proxy flag, and (Android 12-13) PPK effective value.

  iiab-termux --all
    -> baseline + IIAB Debian +
       (Android 12-13) ADB pair/connect + apply PPK + run --check
       (Android 14+) optionally skip ADB (reminds to disable child process restrictions).

  iiab-termux --proxy-start
    -> Start the background proxy (boxyproxy).

  iiab-termux --proxy-stop
    -> Stop the background proxy.

  iiab-termux --proxy-status
    -> Show the current status and configuration of the background proxy.

  Optional:
    --connect-port [IP:PORT|PORT]  Skip CONNECT PORT prompt (ADB modes)
    --timeout 180                  Seconds to wait per prompt
    --reset-iiab                   Reset (reinstall) IIAB Debian in proot-distro
    --no-log                       Disable logging
    --log-file /path/file          Write logs to a specific file
    --proxy-no-external            Start proxy in walled-garden mode (blocks external SOCKS5 hosts)
    --debug                        Extra logs

Notes:
- ADB prompts require: `pkg install termux-api` + Termux:API app installed + notification permission.
- Wireless debugging must be enabled on Android 12 & 13
- Wireless debugging (pairing code / QR) is available on Android 11 and later versions.
- Android 8-10: there is no Wireless debugging pairing flow. 
- CONNECT PORT and PAIR PORT are auto-detected via mDNS when possible; still prompting for the PAIR CODE.
EOF
}
