# shellcheck shell=bash
# Module file (no shebang). Bundled by build_bundle.sh

RED="\033[31m"; YEL="\033[33m"; GRN="\033[32m"; BLU="\033[34m"; RST="\033[0m"; BOLD="\033[1m"

log()       { printf "${BLU}[iiab]${RST} %s\n" "$*"; }
log_red()   { printf "${RED}[iiab]${RST} %s\n" "$*"; }
log_yel()   { printf "${YEL}[iiab]${RST} %s\n" "$*"; }
ok()        { printf "${GRN}[iiab]${RST} %s\n" "$*"; }
warn()      { printf "${YEL}[iiab] WARNING:${RST} %s\n" "$*" >&2; }
warn_red()  { printf "${RED}${BOLD}[iiab] WARNING:${RST} %s\n" "$*" >&2; }
boxyp_log() { printf "${BLU}[boxyproxy]${RST} %s\n" "$*"; }
indent()    { sed 's/^/ /'; }

have() { command -v "$1" >/dev/null 2>&1; }
need() { have "$1" || return 1; }
die()  { printf "${RED}[!] ERROR${RST}: %s\n" "$*" >&2; exit 1; }

blank() {
  local n="${1:-1}" fd=1
  [[ "$n" =~ ^[0-9]+$ ]] || n=1
  if { : >&3; } 2>/dev/null; then fd=3; fi
  while (( n-- > 0 )); do printf '\n' >&"$fd"; done
}

clear_line() {
  local outfd; outfd="$(console_outfd)"
  printf "\r%-80s\r" " " >&"$outfd"
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

# Android info
get_android_sdk()     { getprop ro.build.version.sdk 2>/dev/null || true; }
get_android_release() { getprop ro.build.version.release 2>/dev/null || true; }
ANDROID_SDK="$(get_android_sdk)"
ANDROID_REL="$(get_android_release)"

# Default URLs for auto-pulling rootfs
IIAB_ROOTFS_URL_ARM64="${IIAB_ROOTFS_URL_ARM64:-https://iiab.switnet.org/android/rootfs/latest_arm64-v8a.meta4}"
IIAB_ROOTFS_URL_ARM32="${IIAB_ROOTFS_URL_ARM32:-https://iiab.switnet.org/android/rootfs/latest_armeabi-v7a.meta4}"

get_iiab_termux_version() {
  local file="${1:-$0}"
  local raw_ts=""
  if [[ -f "$file" ]]; then
    raw_ts="$(grep '^# GENERATED FILE:' "$file" 2>/dev/null | sed -E 's/.*GENERATED FILE: ([^ ]+).*/\1/' | head -n1)"
    if [[ -n "$raw_ts" ]]; then
      # Convert ISO 8601 to YYYY.DDD.HHMM in UTC
      # %Y = Year, %j = Day of year (001-366), %H%M = Hours/Minutes
      date -u -d "$raw_ts" +"%Y.%j.%H%M" 2>/dev/null && return 0
    fi
  fi
  echo "unknown"
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

countdown_timer() {
  # $1 = Seconds (e.g., 5)
  # $2 = Format string with %d (e.g., "\r${YEL}Waiting %d secs...${RST}")
  local secs="${1:-5}"
  local msg="$2"
  local outfd; outfd="$(console_outfd)"

  if [[ -r /dev/tty ]]; then
    for (( i=secs; i>=1; i-- )); do
      printf "$msg" "$i" >&"$outfd"
      sleep 1
    done
    clear_line
  else
    sleep "$secs"
  fi
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
        warn_red "Android 12-13: ADB is not connected, so PPK fix cannot be verified/applied."
        warn "Before running the IIAB installer inside proot, run:"
        ok   "  iiab-termux --all"
      fi
    else
      warn_red "Android 12-13: adb is missing, so PPK fix cannot be verified/applied."
      warn "Install adb (android-tools) and run:"
      ok   "  iiab-termux --all"
    fi
  fi

  ok "Starting IIAB Debian (via: iiab-termux --start)"
  local wlan_ip; wlan_ip="$(adb_local_ipv4s_csv 2>/dev/null | cut -d, -f1)"
  if [[ -n "$wlan_ip" && "$wlan_ip" != "Disconnected" && "$wlan_ip" != "0.0.0.0" ]]; then
    if ! have qrencode; then
      log "Installing missing dependency: libqrencode..."
      termux_apt install libqrencode >/dev/null 2>&1 || true
    fi
    blank
    log "To use your IIAB on Android over the network, please scan the following QR Code:"
    qrencode -t UTF8 "http://${wlan_ip}:8085/"
    blank
  else
    blank
    warn "It seems there is no network available to share IIAB on Android over the network."
    blank
  fi
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
   if [[ "${INTENT_MODE:-}" == "headless" ]]; then
     boxyp_log "Running in APK Headless mode (no TTY, sleep infinity)"
     proot-distro login iiab -- bash -lc "/usr/local/bin/pdsm start && sleep infinity"
     rc=$?
   elif [[ -r /dev/tty ]]; then
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

iiab_stop() {
  log "Stopping IIAB environment..."

  # Stop the 'boxyproxy' proxy
  boxyproxy_stop >/dev/null 2>&1 || true

  # Graceful shutdown of internal services using 'pdsm'
  if iiab_exists; then
    log "Executing 'pdsm stop' for internal services..."
    # Send the command into proot. The script won't fail if a service fails.
    proot-distro login iiab -- /usr/local/bin/pdsm stop || warn "Partial failure while stopping services via pdsm."
  fi

  # Allow a few seconds for processes to save data and exit
  sleep 3

  # Final zombie cleanup
  # We kill any orphaned processes pdsm might left hanging after 3 seconds
  if pkill -0 -f "proot.*iiab" 2>/dev/null; then
    log "Cleaning up remaining processes..."
    pkill -TERM -f "proot.*iiab" || true
    sleep 1
    pkill -KILL -f "proot.*iiab" 2>/dev/null || true
  fi

  ok "All IIAB services have been successfully stopped."
}

### --- Pre-flight Check & Metrics ---
check_preflight_requirements() {
  local outfd; outfd="$(console_outfd)"
  printf "${BLU}[iiab] --- Pre-flight System Checks ---${RST}\n" >&"$outfd"

  # 1. Architecture Check
  local arch; arch=$(get_android_arch)
  local arch_stat="${GRN}OK${RST}"
  [[ "$arch" == "unknown" ]] && arch_stat="${YEL}WARN${RST}"
  printf "${BLU}[iiab]${RST}  * %-30s [%b]\n" "Architecture ($arch)" "$arch_stat" >&"$outfd"

  # 2. Disk Space Logic (Thresholds: 2.5GB and 5GB)
  local free_mb; free_mb=$(df -k "$PREFIX" | awk 'END{print $4 / 1024}' | cut -d. -f1)
  local free_gb; free_gb=$(awk -v mb="$free_mb" 'BEGIN { printf "%.2f", mb / 1024 }')
  local disk_stat="${GRN}OK${RST}"
  if [[ "$free_mb" -lt 2500 ]]; then disk_stat="${RED}FAIL${RST}"; elif [[ "$free_mb" -lt 5000 ]]; then disk_stat="${YEL}WARN${RST}"; fi
  printf "${BLU}[iiab]${RST}  * %-30s [%b]\n" "Free Space (${free_gb} GB)" "$disk_stat" >&"$outfd"

  # 3. Network Latency Check (Inside Table)
  local net_stat="${RED}FAIL${RST}"
  local net_text="Network Latency (Offline)"
  local start_t; start_t=$(date +%s%N 2>/dev/null || echo 0)
  if curl -Isf --connect-timeout 5 google.com >/dev/null 2>&1; then
    local end_t; end_t=$(date +%s%N 2>/dev/null || echo 0)
    if [[ "$start_t" != "0" && "$end_t" != "0" ]]; then
      local delta=$(( (end_t - start_t) / 1000000 ))
      net_text="Network Latency (${delta}ms)"
      if [[ "$delta" -le 50 ]]; then net_stat="${BOLD}${BLU}EXCEL${RST}"
      elif [[ "$delta" -le 150 ]]; then net_stat="${GRN}GOOD${RST}"
      elif [[ "$delta" -le 250 ]]; then net_stat="${YEL}FAIR${RST}"
      else net_stat="${RED}SLOW${RST}"
      fi
    fi
  fi
  printf "${BLU}[iiab]${RST}  * %-30s [%b]\n" "$net_text" "$net_stat" >&"$outfd"

  # 4. Developer Options Contextual Reminder
  local sdk="${ANDROID_SDK:-0}"
  local rel="${ANDROID_REL:-0}"
  local dev_stat="${YEL}NOTE${RST}"
  if [[ "$sdk" -ge 34 ]]; then
    printf "${BLU}[iiab]${RST}  * %-30s [%b]\n" "Dev. Options (Child Process)" "$dev_stat" >&"$outfd"
  elif [[ "$sdk" -ge 31 ]]; then
    printf "${BLU}[iiab]${RST}  * %-30s [%b]\n" "Dev. Options (Phantom Process)" "$dev_stat" >&"$outfd"
   else
    printf "${BLU}[iiab]${RST}  * %-30s [%b]\n" "Dev. Options (Not Required)" "${GRN}N/A${RST}" >&"$outfd"
  fi

  printf "${BLU}[iiab] --------------------------------------${RST}\n" >&"$outfd"

  # --- Checks and warnings (summary) ---
  [[ "$arch" == "unknown" ]] && warn "Unknown architecture. Results may vary."

  if [[ "$free_mb" -lt 2500 ]]; then
    warn_red "Error: Insufficient space to install IIAB on Android."
    die "Please free up space (at least 2.5GB) to continue."
  elif [[ "$free_mb" -lt 5000 ]]; then
    log_yel "Warning: Limited storage detected (${free_gb} GB)."
    warn "You may face limitations when adding additional content later."
    tty_yesno_default_n "[iiab] Do you want to continue with a tight installation? [y/N]: " || die "Aborted by user."
  else
    ok "Sufficient disk space for installation (${free_gb} GB)"
    log "Note: Space for multimedia content depends on the specific modules you add later."
  fi

  if [[ "$net_stat" == *FAIL* ]]; then
    warn_red "Network unreachable or timeout. Downloads may fail."
  elif [[ "$net_stat" == *SLOW* ]]; then
    warn "High network latency detected."
  fi

  if [[ "$sdk" -ge 34 ]]; then
    log_yel "Reminder: Developer Options must be enabled to toggle 'Disable child process restrictions'."
  elif [[ "$sdk" -ge 31 ]]; then
    log_yel "Reminder: Developer Options must be enabled to pair ADB and fix 'Phantom Process Killer'."
  elif [[ "$sdk" -gt 0 && "$sdk" -le 30 ]]; then
    ok "No known process restrictions for Android $rel."
  fi

  ok "Pre-flight checks completed."
  countdown_timer 7 "\r${BLU}[iiab]${RST} Proceeding in %d seconds... "
}

welcome_interactive() {
  local mode_label="${1:-Installation}"
  local prompt_text="Press [ENTER] to start system validation... "
  [[ "$mode_label" == "Preview" ]] && prompt_text="Press [ENTER] to exit welcome message... "

  local outfd; outfd="$(console_outfd)"
  {
    clear
    printf "${BLU}==================================================${RST}\n"
    printf " 🌐 WELCOME TO IIAB on Android 🌐\n"
    printf "     Starting mode: ${mode_label}\n"
    printf "${BLU}==================================================${RST}\n"
    printf "\n${BOLD}Internet-in-a-Box (IIAB) on Android${RST} will allow\n"
    printf "millions of people worldwide to build their own \n"
    printf "family libraries, inside their own phones!\n"
    blank
    printf "This script prepares your Android device as an\n"
    printf "Offline Learning Environment (Internet-in-a-Box).\n"
    blank
    printf "Please follow the installation guide at:\n"
    printf "${YEL}*${RST} 📄: ${BLU}https://github.com/iiab/iiab-android${RST}\n"
    blank
    printf "Other online resources:\n"
    printf "${YEL}*${RST} 🔗: ${BOLD}https://internet-in-a-box.org${RST}\n"
    printf "${YEL}*${RST} 🐛: ${BOLD}https://github.com/iiab/iiab-android/issues${RST}\n"
    blank
    printf " TIP: Keep the charger around and ensure Termux\n"
    printf " battery settings are set to 'Unrestricted'.\n"
    printf "${BLU}==================================================${RST}\n"
    
    if [[ -r /dev/tty ]]; then
      printf "%s" "$prompt_text"
    fi
  } >&"$outfd"

  if [[ -r /dev/tty ]]; then
    read -r _ < /dev/tty || true
   fi
 }

offer_welcome_once() {
  local mode_label="$1"
  local stamp="${STATE_DIR}/stamp.welcome"
  
  if [[ ! -f "$stamp" ]]; then
    welcome_interactive "$mode_label"
    date > "$stamp" 2>/dev/null || true
  fi
}

measure_disk_usage() {
  # $1 = Function or mode name to execute
  local before; before=$(du -sm "$PREFIX/.." | awk '{print $1}')
  log "Calculating installation footprint..."
  
  # Execute the passed logic
  "$@" 

  local after; after=$(du -sm "$PREFIX/.." | awk '{print $1}')
  local footprint=$((after - before))
  ok "Installation completed. System footprint: +${footprint}MB."
}
# -------------------------
# Help / usage (static text)
# -------------------------
usage() {
  printf '%b\n' "
${BOLD}Usage:${RST} iiab-termux [MODE] [OPTIONS]

${BLU}=== CORE & INSTALL ===${RST}
  ${GRN}(no args)${RST}       Baseline + IIAB Debian bootstrap
  ${GRN}--all${RST}           Full setup: baseline, Debian, ADB, PPK, & checks
  ${GRN}--barebones${RST}     Minimal installation: Termux base + proxy (no rootfs)
  ${GRN}--start, --login${RST} Start / Login IIAB Debian
  ${GRN}--iiab-android${RST}  Install/update 'iiab-android' tool inside proot

${BLU}=== ADB & SYSTEM TUNING ===${RST}
  ${GRN}--with-adb${RST}      Baseline + Debian + ADB wireless pair/connect
  ${GRN}--adb-only${RST}      Only ADB pair/connect (skips Debian)
  ${GRN}--connect-only${RST}  Connect to an already-paired device
  ${GRN}--ppk-only${RST}      Set max_phantom_processes=256 via ADB
  ${GRN}--check${RST}         Check Android readiness (Process restrictions, PPK)

${BLU}=== BACKUP & RESTORE ===${RST}
  ${GRN}--backup-rootfs${RST} Backup IIAB Debian to .tar.gz
  ${GRN}--restore-rootfs${RST} Restore IIAB Debian from local .tar.gz
  ${GRN}--pull-rootfs${RST}   Download & restore rootfs from URL (P2P enabled)
  ${GRN}--remove-rootfs${RST} Delete IIAB Debian rootfs and all data

${BLU}=== PROXY (BOXYPROXY) ===${RST}
  ${GRN}--proxy-start${RST}   Start background proxy
  ${GRN}--proxy-stop${RST}    Stop background proxy
  ${GRN}--proxy-status${RST}  Show proxy status

${BOLD}Options:${RST}
  --connect-port [P]  Skip CONNECT PORT prompt
  --timeout [SECS]    Wait time per prompt (default 180)
  --no-meta4          Disable Metalink/P2P for --pull-rootfs
  --keep-tarball      Keep the downloaded archive after --pull-rootfs
  --reset-iiab        Reinstall IIAB Debian
  --install-self      Install the current script to Termux bin path
  --welcome           Show the welcome screen
  --debug             Enable extra logs
  --help, --version   Show this help or version

${YEL}Notes:${RST} Setup on Android 12 & 13 requires ADB due to OS design. 14+ simplifies this with system UI toggles
"
}
