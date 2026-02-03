# shellcheck shell=bash
# Module file (no shebang). Bundled by build_bundle.sh

RED="\033[31m"; YEL="\033[33m"; GRN="\033[32m"; BLU="\033[34m"; RST="\033[0m"; BOLD="\033[1m"

log()      { printf "${BLU}[iiab]${RST} %s\n" "$*"; }
ok()       { printf "${GRN}[iiab]${RST} %s\n" "$*"; }
warn()     { printf "${YEL}[iiab] WARNING:${RST} %s\n" "$*" >&2; }
warn_red() { printf "${RED}${BOLD}[iiab] WARNING:${RST} %s\n" "$*" >&2; }
indent()   { sed 's/^/ /'; }

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

# Package name for the Termux app.
TERMUX_PACKAGE="${TERMUX_PACKAGE:-com.termux}"

# One-time helper: guide user to set Termux battery policy to keep sessions alive.
POWER_MODE_BATTERY_PROMPT="${POWER_MODE_BATTERY_PROMPT:-1}"  # 1=ask, 0=never ask
POWER_MODE_BATTERY_STAMP="${POWER_MODE_BATTERY_STAMP:-$STATE_DIR/stamp.termux_battery_settings}"

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
  local prompt="$1" ans="Y"
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
  local prompt="$1" ans="N"
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
  elif [[ "$sdk" =~ ^[0-9]+$ ]] && (( sdk >= 34 )); then
    # Android 14+: rely on 'Disable child process restrictions' (monitor=false).
    if have adb; then
      adb start-server >/dev/null 2>&1 || true
      if adb_pick_loopback_serial >/dev/null 2>&1; then
        check_readiness || true
      else
        warn "Android 14+: ensure 'Disable child process restrictions' is enabled in Developer Options."
      fi
    else
      warn "Android 14+: ensure 'Disable child process restrictions' is enabled in Developer Options."
    fi
  fi

  ok "Entering IIAB Debian (via: iiab-termux --login)"
  power_mode_login_enter || true
  # Preserve interactivity even if logging is enabled (avoid pipes/tee issues).
  local rc=0
  local outfd errfd
  outfd="$(console_outfd)"
  errfd="$(console_errfd)"

  if [[ -r /dev/tty ]]; then
    proot-distro login iiab </dev/tty >&"$outfd" 2>&"$errfd"
    rc=$?
  else
    proot-distro login iiab
    rc=$?
  fi

  power_mode_login_exit || true
  return $rc
}
