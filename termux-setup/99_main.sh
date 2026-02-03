# shellcheck shell=bash
# Module file (no shebang). Bundled by build_bundle.sh

# iiab-termux
# - Termux bootstrap (packages, wakelock)
# - proot-distro + IIAB Debian bootstrap
# - ADB wireless pair/connect via Termux:API notifications (no Shizuku)
# - Optional PPK / phantom-process tweaks (best-effort)

# -------------------------
# Defaults
# -------------------------
# NOTE: Core defaults live in 00_lib_common.sh to guarantee availability for all modules.

# Ensure state directories exist (safe even if user overrides via environment).
mkdir -p "$STATE_DIR" "$ADB_STATE_DIR" "$LOG_DIR"

BASELINE_OK=0
BASELINE_ERR=""
RESET_IIAB=0
ONLY_CONNECT=0

CHECK_NO_ADB=0
CHECK_SDK=""
CHECK_MON=""
CHECK_PPK=""

# Modes are mutually exclusive (baseline is default)
MODE="baseline"      # baseline|with-adb|adb-only|connect-only|ppk-only|check|all|login
MODE_SET=0
CONNECT_PORT_FROM=""   # "", "flag", "positional"

trap 'power_mode_login_exit >/dev/null 2>&1 || true; adb_hint_notif_remove >/dev/null 2>&1 || true; cleanup_notif >/dev/null 2>&1 || true; release_wakelock >/dev/null 2>&1 || true' EXIT INT TERM

# NOTE: Termux:API prompts live in 40_mod_termux_api.sh

# -------------------------
# OS guardrails
# -------------------------
# Guard: avoid running iiab-termux inside proot-distro rootfs.
in_proot_rootfs() {
  # Debian rootfs indicator
  [ -f /etc/debian_version ] && return 0
  return 1
}

termux_path_leaked() {
  # Termux prefix on PATH indicates we're inside proot but inheriting host tools
  printf '%s' "${PATH:-}" | grep -q '/data/data/com\.termux/files/usr/'
}

guard_no_iiab_termux_in_proot() {
  if in_proot_rootfs && termux_path_leaked; then
    warn_red_context "Detected proot environment: IIAB Debian"
    warn "Don't run iiab-termux inside IIAB Debian"
    ok   "In order to run a first-time install run:"
    ok   "  iiab-android"
    blank
    warn "To resume or continue an installation in progress, use the usual IIAB command:"
    ok   "  iiab"
    blank
    warn "If you meant to prepare Termux, exit proot and run:"
    ok   "  iiab-termux --all"
    exit 2
  fi
}

guard_no_iiab_termux_in_proot

# -------------------------
# Self-check
# -------------------------
self_check() {
  log "Self-check summary:"
  log " Android release=${ANDROID_REL:-?} sdk=${ANDROID_SDK:-?}"

  if have proot-distro; then
    log " proot-distro: present"
    log " proot-distro list:"
    proot-distro list 2>/dev/null | indent || true
    if iiab_exists; then ok " IIAB Debian: present"; else warn " IIAB Debian: not present"; fi
  else
    warn " proot-distro: not present"
  fi

  if have adb; then
    log " adb: present"
    adb devices -l 2>/dev/null | indent || true
    local serial
#    re-enable in need for verbose output.
#    if serial="$(adb_pick_loopback_serial 2>/dev/null)"; then
#      log " adb shell id (first device):"
#      adb -s "$serial" shell id 2>/dev/null | indent || true
#    fi
  else
    warn " adb: not present"
  fi
  # Quick Android flags check (best-effort; no prompts)
  self_check_android_flags || true

  if have termux-wake-lock; then ok " Termux:API wakelock: available"; else warn " Termux:API wakelock: not available"; fi
  if have termux-notification; then ok " Termux:API notifications: command present"; else warn " Termux:API notifications: missing"; fi
}

baseline_bail() {
  warn_red "Cannot continue: Termux baseline is incomplete."
  [[ -n "${BASELINE_ERR:-}" ]] && warn "Reason: ${BASELINE_ERR}"
  baseline_bail_details || true
  exit 1
}

final_advice() {
  if [[ "${BASELINE_OK:-0}" -ne 1 ]]; then
    warn_red "Baseline is not ready, so ADB prompts / IIAB Debian bootstrap may be unavailable."
    [[ -n "${BASELINE_ERR:-}" ]] && warn "Reason: ${BASELINE_ERR}"
    warn "Fix: check network + Termux repos, then re-run the script."
    return 0
  fi

  # 1) Android-related warnings (only meaningful if we attempted checks)
  local sdk="${CHECK_SDK:-${ANDROID_SDK:-}}"
  local _active=0
  case "${MODE:-}" in
    with-adb|adb-only|connect-only|ppk-only|check|all) _active=1 ;;
    *) _active=0 ;;
  esac

  local adb_connected=0
  local serial="" mon="" mon_fflag=""

  # Best-effort: detect whether an ADB loopback device is already connected.
  # (We do NOT prompt/pair here; we only check current state.)
  if have adb; then
    adb start-server >/dev/null 2>&1 || true
    if adb_pick_loopback_serial >/dev/null 2>&1; then
      adb_connected=1
      serial="$(adb_pick_loopback_serial 2>/dev/null || true)"
    fi
  fi
  # Escalate to red only when user is actively checking/fixing,
  # OR when we already have ADB connected (strong evidence).
  advice_warn_bad() {  # args: message
    if (( _active || adb_connected )); then
      warn_red "$*"
    else
      warn "$*"
    fi
  }

  # Baseline safety gate:
  # On Android 12-13 (SDK 31-33), IIAB/proot installs can fail if PPK is low (often 32).
  # Baseline mode does NOT force ADB pairing nor run check_readiness(), so PPK may be unknown.
  # If PPK is not determined, suggest running --all BEFORE telling user to proceed to proot-distro.
  if [[ "$MODE" == "baseline" ]]; then
    if [[ "$sdk" =~ ^[0-9]+$ ]] && (( sdk >= 31 && sdk <= 33 )); then
      # If we didn't run checks, CHECK_PPK will be empty. Even with adb_connected=1, baseline
      # still doesn't populate CHECK_PPK unless user ran --check/--all.
      if [[ "${CHECK_PPK:-}" != "" && "${CHECK_PPK:-}" =~ ^[0-9]+$ ]]; then
        : # PPK determined -> ok to continue with normal advice below
      else
        warn "Android 12-13: PPK value hasn't been verified (max_phantom_processes may be low, e.g. 32)."
        warn "Before starting the IIAB install, run the complete setup so it can apply/check PPK=256; otherwise the installation may fail:"
        ok   "  iiab-termux --all"
        return 0
      fi
    elif [[ "$sdk" =~ ^[0-9]+$ ]] && (( sdk >= 34 )); then
      # On Android 14+, rely on "Disable child process restrictions"
      # Proxy signals: settings_enable_monitor_phantom_procs (or the fflag override).
      # Baseline does not run check_readiness(), so CHECK_MON is usually empty.
      if [[ "${CHECK_MON:-}" == "false" ]]; then
        : # Verified OK (rare in baseline) -> continue
      else
        # If ADB is already connected, try to read the flag best-effort (no prompts).
        if [[ "$adb_connected" -eq 1 && -n "${serial:-}" ]]; then
          mon_fflag="$(adb_get_child_restrictions_flag "$serial")"
          if [[ "$mon_fflag" == "true" || "$mon_fflag" == "false" ]]; then
            mon="$mon_fflag"
          else
            mon="$(adb -s "$serial" shell settings get global settings_enable_monitor_phantom_procs 2>/dev/null | tr -d '\r' || true)"
          fi
        fi

        if [[ "${mon:-}" == "false" ]]; then
          : # Restrictions already disabled -> ok to continue
        else
          if [[ "${mon:-}" == "true" ]]; then
            advice_warn_bad "Android 14+: child process restrictions appear ENABLED (monitor=true)."
          else
            warn "Android 14+: child process restrictions haven't been verified (monitor flag unreadable/unknown)."
          fi
          warn "For Android 14 and later, there is no strict need to connect to ADB, on the other hand:"
          warn "Please make sure to set 'Disable child process restrictions' enabled; otherwise the installation may fail."
          return 0
        fi
      fi
    fi
  fi

  if [[ "${CHECK_NO_ADB:-0}" -eq 1 ]]; then
    # If we could not check, still warn on A12-13 because PPK is critical there
    if [[ "$sdk" =~ ^[0-9]+$ ]] && (( sdk >= 31 && sdk <= 33 )); then
      warn "A12-13: verify PPK=256 before installing IIAB."
    fi
  else
    # A14+ child restrictions proxy (only if readable)
    if [[ "$sdk" =~ ^[0-9]+$ ]] && (( sdk >= 34 )) && [[ "${CHECK_MON:-}" == "true" ]]; then
      advice_warn_bad "A14+: disable child process restrictions before installing IIAB."
    fi

    # Only warn about PPK on A12-13 (A14+ uses child restrictions)
    if [[ "$sdk" =~ ^[0-9]+$ ]] && (( sdk >= 31 && sdk <= 33 )); then
      if [[ "${CHECK_PPK:-}" =~ ^[0-9]+$ ]] && (( CHECK_PPK < 256 )); then
        advice_warn_bad "PPK is low (${CHECK_PPK}); consider --ppk-only."
      fi
    fi
  fi

  # 2) IIAB Debian "next step" should only be shown for modes that actually bootstrap IIAB
  case "$MODE" in
    baseline|with-adb|all)
      if iiab_exists; then
        ok "Next: iiab-termux --login"
      else
        warn "IIAB Debian not present. Run:"
        warn "Preferred: iiab-termux --all"
      fi
      ;;
    *)
      # adb-only/connect-only/ppk-only/check: do not suggest Debian login as a generic ending
      ;;
  esac
}
# -------------------------
# Args
# -------------------------
set_mode() {
  local new="$1"
  if [[ "$MODE_SET" -eq 1 ]]; then
    die "Modes are mutually exclusive. Already set: --${MODE}. Tried: --${new}"
  fi
  MODE="$new"
  MODE_SET=1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --with-adb) set_mode "with-adb"; shift ;;
    --adb-only) set_mode "adb-only"; shift ;;
    --login) set_mode "login"; shift ;;
    --connect-only)
      set_mode "connect-only"
      ONLY_CONNECT=1
      # Optional positional connect spec (accept PORT or IP:PORT)
      if [[ -n "${2:-}" ]]; then
        local_norm=""
        if local_norm="$(normalize_port_5digits "${2:-}" 2>/dev/null)"; then
          if [[ -n "${CONNECT_PORT_FROM:-}" && "${CONNECT_PORT_FROM}" != "positional" ]]; then
            die "CONNECT PORT specified twice (positional + --connect-port). Use only one."
          fi
        CONNECT_PORT="$local_norm"
        CONNECT_PORT_FROM="positional"
        shift 2
          continue
        fi
      fi
      shift
      ;;
    --ppk-only) set_mode "ppk-only"; shift ;;
    --iiab-android) set_mode "iiab-android"; shift ;;
    --check) set_mode "check"; shift ;;
    --all) set_mode "all"; shift ;;
    --connect-port)
      if [[ -n "${CONNECT_PORT_FROM:-}" && "${CONNECT_PORT_FROM}" != "flag" ]]; then
        die "CONNECT PORT specified twice (positional + --connect-port). Use only one."
      fi
      CONNECT_PORT="$(normalize_port_5digits "${2:-}" 2>/dev/null)" || {
        die "Invalid --connect-port (must be 5 digits PORT or IP:PORT): '${2:-}'"
      }
      CONNECT_PORT_FROM="flag"
      shift 2
      ;;
    --timeout) TIMEOUT_SECS="${2:-180}"; shift 2 ;;
    --host) HOST="${2:-127.0.0.1}"; shift 2 ;;
    --reset-iiab|--clean-iiab) RESET_IIAB=1; shift ;;
    --no-log) LOG_ENABLED=0; shift ;;
    --log-file) LOG_FILE="${2:-}"; shift 2 ;;
    --debug) DEBUG=1; shift ;;
    -h|--help) usage; exit 0 ;;
    --*) die "Unknown option: $1. See --help." ;;
    *) shift ;;
  esac
done

all_optional_adb_connect_and_check() {
  # Args:
  #   $1 = label (e.g. "Android 14+" or "Android 11")
  #   $2 = reminder line (can be "")

  local label="${1:-Android}"
  local reminder="${2:-}"

  local serial=""

  if have adb; then
    adb start-server >/dev/null 2>&1 || true
    if serial="$(adb_pick_loopback_serial 2>/dev/null)"; then
      ok "ADB already connected: $serial (running checks, no prompts)."
      check_readiness || true
      return 0
    fi
  fi

  if tty_yesno_default_y "[iiab] ${label}: Connect via Wireless ADB now (recommended)? [Y/n]: "; then
    adb_pair_connect_if_needed
    check_readiness || true
    return 0
  fi

  warn "Continuing without ADB (${label})."
  [[ -n "$reminder" ]] && warn "$reminder"
  CHECK_NO_ADB=1
  CHECK_SDK="${ANDROID_SDK:-}"
  return 0
}

sdk_is_num() { [[ "${ANDROID_SDK:-}" =~ ^[0-9]+$ ]]; }
sdk_le() { local n="$1"; sdk_is_num && (( ANDROID_SDK <= n )); }
sdk_eq() { local n="$1"; sdk_is_num && (( ANDROID_SDK == n )); }

warn_skip_adb_pre11() {
  warn "Android 8-10: skipping ADB steps (Wireless debugging pairing is not available)."
  warn "This is OK: so far, our testing indicates ADB is not required on those versions."
}

warn_adb_only_pre11() {
  warn "Android 8-10: --adb-only cannot run Wireless debugging pairing (Android 11+ feature)."
  warn "So far, our testing indicates ADB is not required on Android 8-10."
}

validate_args() {
  if [[ -n "${CONNECT_PORT:-}" ]]; then
    local raw="$CONNECT_PORT" norm=""
    norm="$(normalize_port_5digits "$raw" 2>/dev/null)" || \
      die "Invalid --connect-port (must be 5 digits PORT or IP:PORT): '$raw'"
    CONNECT_PORT="$norm"
    # Android 8-10 (SDK <=29): Wireless debugging pairing isn't available.
    # If user provided --connect-port, make it explicit it's ignored here.
    if sdk_le 29; then
      warn "Android 8-10: ignoring --connect-port (ADB wireless pairing/connect is not available)."
      CONNECT_PORT=""
      CONNECT_PORT_FROM=""
      return 0
    fi
    case "$MODE" in
      adb-only|with-adb|connect-only|ppk-only|check|all) : ;;
      baseline)
        log "--connect-port requires an ADB mode."
        die "Use along with: --adb-only / --with-adb / --connect-only / --check / --ppk-only / --all"
        ;;
      *)
        die "--connect-port is not valid with mode=$MODE"
        ;;
    esac
  fi
}

# Android 12-13 only (SDK 31-33): apply PPK tuning automatically
attempt_auto_apply_ppk() {
  local sdk="${ANDROID_SDK:-}"
  if [[ "$sdk" =~ ^[0-9]+$ ]] && (( sdk >= 31 && sdk <= 33 )); then
    log "Android SDK=${sdk} detected -> applying --ppk automatically (12-13 rule)."
    ppk_fix_via_adb || true
  else
    log "Android SDK=${sdk:-?} -> skipping auto-PPK (only for Android 12-13)."
  fi
}

# -------------------------
# Main flows
# -------------------------
main() {
  setup_logging "$@"
  validate_args
  sanitize_timeout
  acquire_wakelock

  case "$MODE" in
    login)
      iiab_login
      return $?
      ;;
    baseline)
      power_mode_offer_battery_settings_once || true
      step_termux_repo_select_once
      step_termux_base || baseline_bail
      step_iiab_bootstrap_default
      install_iiab_android_cmd || true
      ;;

    with-adb)
      power_mode_offer_battery_settings_once || true
      step_termux_repo_select_once
      step_termux_base || baseline_bail
      step_iiab_bootstrap_default
      install_iiab_android_cmd || true
      # Android 8-10: skip ADB (no Wireless debugging pairing).
      if sdk_le 29; then
        warn_skip_adb_pre11
        break
      fi
      adb_pair_connect_if_needed
      ;;

    adb-only)
      step_termux_base || baseline_bail
      # Android 8-10: no Wireless debugging pairing flow (Android 11+ feature).
      if sdk_le 29; then
        warn_adb_only_pre11
        return 0
      fi
      adb_pair_connect_if_needed
      ;;

    connect-only)
      step_termux_base || baseline_bail
      adb_pair_connect
      ;;

    ppk-only)
      # No baseline, no IIAB Debian. Requires adb already available + connected.
      require_adb_connected || exit 1
      ppk_fix_via_adb || true
      ;;

    iiab-android)
      power_mode_offer_battery_settings_once || true
      step_termux_repo_select_once
      step_termux_base || baseline_bail
      step_iiab_bootstrap_default
      install_iiab_android_cmd || true
      ;;

    check)
      step_termux_base || baseline_bail
      check_readiness || true
      ;;

    all)
      power_mode_offer_battery_settings_once || true
      step_termux_repo_select_once
      step_termux_base || baseline_bail
      step_iiab_bootstrap_default
      install_iiab_android_cmd || true
      if sdk_is_num && (( ANDROID_SDK >= 34 )); then
        # Android 14+
        all_optional_adb_connect_and_check \
          "Android 14+" \
          "Reminder: enable Developer Options -> 'Disable child process restrictions' (otherwise installs may fail)."
      elif sdk_eq 30; then
        # Android 11
        all_optional_adb_connect_and_check \
          "Android 11" \
          "Note: Wireless debugging is optional here; installs usually work without ADB."
      elif sdk_le 29; then
        # Android 8-10
        warn_skip_adb_pre11
      else
        # Android 12-13 (SDK 31-33): ADB + PPK still needed
        adb_pair_connect_if_needed
        attempt_auto_apply_ppk
        check_readiness || true
      fi
      ;;

    *)
      die "Unknown MODE='$MODE'"
      ;;
  esac

  self_check
  ok "iiab-termux completed (mode=$MODE)."
  log "---- Mode list ----"
  log "Connect-only             --connect-only [PORT]"
  log "Pair+connect             --adb-only [--connect-port PORT]"
  log "Login (proot)            --login"
  log "Check                    --check"
  log "Apply PPK                --ppk-only"
  log "Base+IIAB Debian+Pair+connect --with-adb"
  log "Full run                 --all"
  log "Reset IIAB Debian env    --reset-iiab"
  log "-------------------"
  final_advice
}

main "$@"
