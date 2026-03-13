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
MODE="baseline"      # baseline|with-adb|adb-only|connect-only|ppk-only|check|all|login|proxy-start|proxy-stop|proxy-status

MODE_SET=0
CONNECT_PORT_FROM=""   # "", "flag", "positional"
BOXYPROXY_MANAGED=0

# Set backup / restore vars
PULL_USE_META4=1
PULL_KEEP_TARBALL=0
PULL_ARCH_MISMATCH_OK=0
BACKUP_RESTORE_TARGET=""

trap 'if [[ "${BOXYPROXY_MANAGED:-0}" -eq 1 ]]; then boxyproxy_stop >/dev/null 2>&1 || true; fi; power_mode_login_exit >/dev/null 2>&1 || true; adb_hint_notif_remove >/dev/null 2>&1 || true; cleanup_notif >/dev/null 2>&1 || true; release_wakelock >/dev/null 2>&1 || true' EXIT INT TERM
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
  local wlan_ip
  wlan_ip="$(adb_local_ipv4s_csv 2>/dev/null)"
  [[ -z "$wlan_ip" ]] && wlan_ip="Disconnected"

  log "Self-check summary:"
  log " iiab-termux version: $(get_iiab_termux_version "$0")"
  log " Android system details:"
  echo "        - Release: ${ANDROID_REL:-?}"
  echo "        - SDK:     ${ANDROID_SDK:-?}"
  echo "        - Arch:    $(get_android_arch)"
  log " Wireless IP:  ${wlan_ip}"
  if have proot-distro; then
    log " proot-distro: present"
    if iiab_exists; then log " IIAB Debian: present"; else log_yel " IIAB Debian: not present"; fi
  else
    warn " proot-distro: not present"
  fi

  if have adb; then
    log " android-tools: present"
    if adb_pick_loopback_serial >/dev/null 2>&1; then
      adb devices -l 2>/dev/null | indent || true
      #re-enable in need for verbose output.
      #run_if_adb_connected log " adb shell id (first device):"
      #run_if_adb_connected adb -s "$serial" shell id 2>/dev/null | indent || true
    fi
  else
    warn " android-tools: not present"
  fi

# Network & Proxy tools
  if python_has_zeroconf >/dev/null 2>&1; then
    log " mDNS/Zeroconf: present"
  else
    if sdk_le 29; then
      log " mDNS/Zeroconf: no wireless debugging"
    else
      warn " mDNS/Zeroconf: not present"
    fi
  fi

  if boxyproxy_is_installed >/dev/null 2>&1; then
    log " Boxyproxy: present"
  else
    warn " Boxyproxy: not present"
  fi

  # Quick Android flags check
  run_if_adb_connected self_check_android_flags || true

  if have termux-wake-lock; then log " Termux:API wakelock: available"; else log_yel " Termux:API wakelock: not available"; fi
  if have termux-notification; then log " Termux:API notifications: command present"; else log_yel " Termux:API notifications: missing"; fi
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

  # Passive ADB detection
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
        warn "Android 12-13: PPK fix hasn't been verified (max_phantom_processes may be low, e.g. 32)."
        warn "Before starting the IIAB install, run the complete setup so it can apply/check PPK fix; otherwise the installation may fail:"
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
          mon="$(adb_get_monitor_phantom_procs "$serial")"
        fi

        if [[ "${mon:-}" == "false" ]]; then
          : # Restrictions already disabled -> ok to continue
        else
          if [[ "${mon:-}" == "true" ]]; then
            log_yel "Child process restrictions appear ENABLED (monitor=true)."
            log_yel "Please make sure to set 'Disable child process restrictions' enabled; otherwise the installation may fail."
            return 0
          fi
        fi
      fi
    fi
  fi

  if [[ "${CHECK_NO_ADB:-0}" -eq 1 ]]; then
    # If we could not check, still warn on A12-13 because PPK is critical there
    if [[ "$sdk" =~ ^[0-9]+$ ]] && (( sdk >= 31 && sdk <= 33 )); then
      warn "A12-13: verify PPK fix before installing IIAB."
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
    baseline|with-adb|pull-rootfs|all)
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

cmd_install_self() {
  local current_file="$1"
  local target="${PREFIX}/bin/iiab-termux"
  
  if [[ -z "${PREFIX:-}" ]]; then
    die "PREFIX is not defined. Are you running inside Termux?"
  fi

  # Get absolute path safely
  local abs_current; abs_current="$(realpath "$current_file" 2>/dev/null || echo "$current_file")"

  if [[ "$abs_current" == "$target" ]]; then
    ok "Script is already installed at: $target"
    return 0
  fi

  log "Installing script to: $target"
  mkdir -p "${PREFIX}/bin" >/dev/null 2>&1 || true
  cp -f "$abs_current" "$target" || die "Failed to copy script to $target"
  chmod 700 "$target" || true
  ok "Successfully installed! You can now run 'iiab-termux' from anywhere."
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
        connect_norm=""
        if connect_norm="$(normalize_port_5digits "${2:-}" 2>/dev/null)"; then
          if [[ -n "${CONNECT_PORT_FROM:-}" && "${CONNECT_PORT_FROM}" != "positional" ]]; then
            die "CONNECT PORT specified twice (positional + --connect-port). Use only one."
          fi
          CONNECT_PORT="$connect_norm"
          CONNECT_PORT_FROM="positional"
          shift 2
          continue
        fi
      fi
      shift
      ;;
    --barebones) set_mode "barebones"; shift ;;
    --ppk-only) set_mode "ppk-only"; shift ;;
    --iiab-android) set_mode "iiab-android"; shift ;;
    --check) set_mode "check"; shift ;;
    --all) set_mode "all"; shift ;;
    --proxy-start) set_mode "proxy-start"; shift ;;
    --proxy-stop) set_mode "proxy-stop"; shift ;;
    --proxy-status) set_mode "proxy-status"; shift ;;
    --proxy-no-external) BOXYPROXY_NO_EXTERNAL=1; shift ;;
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
    --reset-iiab) RESET_IIAB=1; shift ;;
    --remove-iiab|--remove-rootfs) set_mode "remove-iiab"; shift ;;
    --no-log) LOG_ENABLED=0; shift ;;
    --log-file) LOG_FILE="${2:-}"; shift 2 ;;
    --debug) DEBUG=1; shift ;;
    --install-self) set_mode "install-self"; shift ;;
    --welcome) set_mode "welcome"; shift ;;
    -h|--help) usage; exit 0 ;;
    --version)
      log "installed version: $(get_iiab_termux_version "$0")"
      exit 0
      ;;
    --update) set_mode "update"; shift ;;
    --backup-rootfs)
      set_mode "backup-rootfs"
      # Check if the next argument is a path (and not another flag)
      if [[ -n "${2:-}" && "${2}" != -* ]]; then
        BACKUP_RESTORE_TARGET="$2"
        shift 2
      else
        shift 1
      fi
      ;;
    --restore-rootfs)
      set_mode "restore-rootfs"
      if [[ -n "${2:-}" && "${2}" != -* ]]; then
        BACKUP_RESTORE_TARGET="$2"
        shift 2
      else
        die "--restore-rootfs requires a file path."
      fi
      ;;
    --pull-rootfs)
      set_mode "pull-rootfs"
      if [[ -n "${2:-}" && "${2}" != -* ]]; then
        BACKUP_RESTORE_TARGET="$2"
        shift 2
      else
        BACKUP_RESTORE_TARGET="AUTO"
        shift 1
      fi
      ;;
    --no-meta4)
      PULL_USE_META4=0
      shift
      ;;
    --keep-tarball)
      PULL_KEEP_TARBALL=1
      shift
      ;;
    --arch-mismatch-ok)
      PULL_ARCH_MISMATCH_OK=1
      shift
      ;;
    --*) die "Unknown option: $1. See --help." ;;
    *) shift ;;
  esac
done

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
    norm="$(normalize_port_5digits "$raw" 2>/dev/null)" || {
      die "Invalid --connect-port (must be 5 digits PORT or IP:PORT): '$raw'"
    }
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

run_barebones_logic() {
  power_mode_offer_battery_settings_once || true
  repo_selector_ask_configure
  step_termux_base || baseline_bail
  boxyproxy_install_or_update || true
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
    update)
      update_iiab_termux "$0"
      ;;

    install-self)
      cmd_install_self "$0"
      ;;

    welcome)
      welcome_interactive "Preview"
      ;;

    login)
      iiab_login
      ;;

    backup-rootfs)
      cmd_backup_rootfs "$BACKUP_RESTORE_TARGET"
      ;;

    restore-rootfs)
      cmd_restore_rootfs "$BACKUP_RESTORE_TARGET"
      ;;

    pull-rootfs)
      run_barebones_logic
      cmd_pull_rootfs "$BACKUP_RESTORE_TARGET" "$PULL_USE_META4" "$PULL_KEEP_TARBALL" "$PULL_ARCH_MISMATCH_OK"
      self_check
      ;;

    remove-iiab)
      cmd_remove_iiab
      ;;

    barebones)
      offer_welcome_once "Barebones"
      check_preflight_requirements
      run_barebones_logic
      self_check
      ;;

    baseline)
      offer_welcome_once "Baseline"
      power_mode_offer_battery_settings_once || true
      repo_selector_ask_configure
      step_termux_base || baseline_bail
      boxyproxy_install_or_update || true
      step_iiab_bootstrap_default
      install_iiab_android_cmd || true
      self_check
      ;;

    with-adb)
      offer_welcome_once "With ADB"
      power_mode_offer_battery_settings_once || true
      repo_selector_ask_configure
      step_termux_base || baseline_bail
      boxyproxy_install_or_update || true
      step_iiab_bootstrap_default
      install_iiab_android_cmd || true
      # Android 8-10: skip ADB (no Wireless debugging pairing).
      if sdk_le 29; then
        warn_skip_adb_pre11
      else
        adb_pair_connect_if_needed
      fi
      self_check
      ;;

    adb-only)
      step_termux_base || baseline_bail
      # Android 8-10: no Wireless debugging pairing flow (Android 11+ feature).
      if sdk_le 29; then
        warn_adb_only_pre11
        return 0
      fi
      adb_pair_connect_if_needed
      self_check
      ;;

    connect-only)
      step_termux_base || baseline_bail
      adb_pair_connect
      self_check
      ;;

    ppk-only)
      # No baseline. Only requires adb already available + connected.
      require_adb_connected || exit 1
      ppk_fix_via_adb || true
      self_check
      ;;

    iiab-android)
      offer_welcome_once "IIAB Android"
      power_mode_offer_battery_settings_once || true
      repo_selector_ask_configure
      step_termux_base || baseline_bail
      step_iiab_bootstrap_default
      boxyproxy_install_or_update || true
      install_iiab_android_cmd || true
      ;;

    check)
      step_termux_base || baseline_bail
      check_readiness || true
      check_preflight_requirements
      self_check
      ;;

    all)
      offer_welcome_once "All - Full Setup"
      power_mode_offer_battery_settings_once || true
      repo_selector_ask_configure
      step_termux_base || baseline_bail
      step_iiab_bootstrap_default
      boxyproxy_install_or_update || true
      #boxyproxy_start || true # enable on stage 2
      install_iiab_android_cmd || true
      if sdk_is_num && (( ANDROID_SDK >= 34 )); then
        # Android 14+
        log_yel "Android 14+: Skipping ADB setup."
        log_yel "Reminder: enable Developer Options -> 'Disable child process restrictions' (otherwise installs may fail)."
        CHECK_NO_ADB=1
        CHECK_SDK="${ANDROID_SDK:-}"
      elif sdk_eq 30; then
        # Android 11
        log_yel "Android 11: Skipping ADB setup."
        log_yel "Note: Wireless debugging is optional here; installs usually work without ADB."
        CHECK_NO_ADB=1
        CHECK_SDK="${ANDROID_SDK:-}"
      elif sdk_le 29; then
        # Android 8-10
        warn_skip_adb_pre11
        CHECK_NO_ADB=1
        CHECK_SDK="${ANDROID_SDK:-}"
      else
        # Android 12-13 (SDK 31-33): ADB + PPK still needed
        adb_pair_connect_if_needed
        attempt_auto_apply_ppk
        check_readiness || true
      fi
      self_check
      ;;

    proxy-start)
      termux_prepare_boxyproxy_deps || baseline_bail
      boxyproxy_start
      ;;

    proxy-stop)
      boxyproxy_stop
      ;;

    proxy-status)
      boxyproxy_status
      ;;

    *)
      die "Unknown MODE='$MODE'"
      ;;
  esac

  blank
  ok "Setup tasks for mode '${MODE}' completed."
  printf "${BLU}[iiab]${RST} You can see the iiab-termux mode list with:\n"
  printf "  ${BOLD}iiab-termux --help${RST}\n"

  # Do not print generic "next steps" for certain modes.
  case "$MODE" in
    # Backup and system utilities
    backup-rootfs | remove-iiab)
      : ;;
    # Proxy Management
    proxy-start | proxy-stop | proxy-status)
      : ;;
    # Single commands with no final advice
    login | update | install-self | welcome)
      : ;;
    *) final_advice ;;
  esac
}

main "$@"
