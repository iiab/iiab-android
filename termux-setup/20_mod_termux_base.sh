# shellcheck shell=bash
# Module file (no shebang). Bundled by build_bundle.sh

# -------------------------
# Python helpers + mDNS deps (zeroconf)
# -------------------------
# Rationale: If mDNS autodetect is enabled, prepare it here so it's available from the start.

TERMUX_ZEROCONF_STAMP="${STATE_DIR}/stamp.termux_zeroconf"

# Cache: avoid repeating zeroconf checks/installs/warnings in the same run.
PY_ZEROCONF_CHECKED=0
PY_ZEROCONF_OK=0

# -------------------------
# Python deps for boxyproxy (aiohttp)
# -------------------------
# Prepare it early so proxy-start works reliably.

TERMUX_AIOHTTP_STAMP="${STATE_DIR}/stamp.termux_aiohttp"

# Cache: avoid repeating aiohttp checks/installs/warnings in the same run.
PY_AIOHTTP_CHECKED=0
PY_AIOHTTP_OK=0

# Allow disabling auto-install (keep default enabled).
BOXYPROXY_DEPS_INSTALL="${BOXYPROXY_DEPS_INSTALL:-1}"
BOXYPROXY_DEPS_PIP_INSTALL="${BOXYPROXY_DEPS_PIP_INSTALL:-1}"

python_cmd() {
  command -v python 2>/dev/null || command -v python3 2>/dev/null || true
}

python_has_zeroconf() {
  local py=""
  py="$(python_cmd)"
  [[ -n "$py" ]] || return 1
  "$py" -c 'import zeroconf' >/dev/null 2>&1
}

python_has_aiohttp() {
  local py=""
  py="$(python_cmd)"
  [[ -n "$py" ]] || return 1
  "$py" -c 'import aiohttp' >/dev/null 2>&1
}

python_pip_install_zeroconf() {
  local py=""
  py="$(python_cmd)"
  [[ -n "$py" ]] || return 1
  # Some environments may lack pip initially; try ensurepip if available.
  if ! "$py" -m pip --version >/dev/null 2>&1; then
    if "$py" -c 'import ensurepip' >/dev/null 2>&1; then
      "$py" -m ensurepip --upgrade || return 1
      "$py" -m pip --version || return 1
    else
      return 1
    fi
  fi
  # Run pip directly on the real TTY when FD 3/4 are available.
  if : >&3 2>/dev/null && : >&4 2>/dev/null; then
    ( exec 1>&3 2>&4; "$py" -m pip install --upgrade zeroconf --progress-bar on )
  else
    "$py" -m pip install --upgrade zeroconf --progress-bar on
  fi
}

python_ensure_zeroconf() {
  # Fast path: if we already decided in this run, return the same result.
  if [[ "${PY_ZEROCONF_CHECKED:-0}" -eq 1 ]]; then
    [[ "${PY_ZEROCONF_OK:-0}" -eq 1 ]] && return 0
    return 1
  fi

  PY_ZEROCONF_CHECKED=1

  # Android < 11 does not use Wireless debugging pairing; skip mDNS prep.
  if [[ "${ANDROID_SDK:-}" =~ ^[0-9]+$ ]] && (( ANDROID_SDK < 30 )); then
    PY_ZEROCONF_OK=0
    return 1
  fi
  if python_has_zeroconf; then
    PY_ZEROCONF_OK=1
    return 0
  fi
  [[ "${ADB_MDNS_PIP_INSTALL:-1}" -eq 1 ]] || { PY_ZEROCONF_OK=0; return 1; }

  log_yel "Python module 'zeroconf' not found. Installing it..."
  if python_pip_install_zeroconf && python_has_zeroconf; then
    ok "Installed Python module 'zeroconf' (mDNS autodetect enabled)."
    PY_ZEROCONF_OK=1
    return 0
  fi
  warn "Could not install 'zeroconf' (no network, pip missing, or install failed). Falling back to manual prompts."
  PY_ZEROCONF_OK=0
  return 1
}

python_pip_install_aiohttp() {
  local py=""
  py="$(python_cmd)"
  [[ -n "$py" ]] || return 1
  if ! "$py" -m pip --version >/dev/null 2>&1; then
    if "$py" -c 'import ensurepip' >/dev/null 2>&1; then
      "$py" -m ensurepip --upgrade || return 1
      "$py" -m pip --version || return 1
    else
      return 1
    fi
  fi
  if : >&3 2>/dev/null && : >&4 2>/dev/null; then
    ( exec 1>&3 2>&4; "$py" -m pip install --upgrade aiohttp --progress-bar on )
  else
    "$py" -m pip install --upgrade aiohttp --progress-bar on
  fi
}

termux_prepare_boxyproxy_deps() {
  have python || have python3 || return 0
  python_has_aiohttp && return 0

  log_yel "Python module 'aiohttp' not found. Installing it ..."
  python_pip_install_aiohttp >/dev/null 2>&1 || true
  python_has_aiohttp && { ok "Installed 'aiohttp' via pip."; return 0; }

  warn "Could not install 'aiohttp'. boxyproxy may fail until it's installed."
  return 1
}

termux_prepare_mdns_deps() {
  # Only if mDNS autodetect is enabled & pip-install is allowed.
  [[ "${ADB_MDNS:-0}" -eq 1 ]] || return 0
  [[ "${ADB_MDNS_PIP_INSTALL:-1}" -eq 1 ]] || return 0
  have python || have python3 || return 0

  # If we stamped success before and it's still present, do nothing.
  if [[ -f "$TERMUX_ZEROCONF_STAMP" ]] && python_has_zeroconf; then
    return 0
  fi
  # If stamp exists but module disappeared, drop stamp and retry.
  [[ -f "$TERMUX_ZEROCONF_STAMP" ]] && rm -f "$TERMUX_ZEROCONF_STAMP" >/dev/null 2>&1 || true

  log "Preparing mDNS autodetect dependency (python module: zeroconf)..."
  if python_ensure_zeroconf; then
    date > "$TERMUX_ZEROCONF_STAMP" 2>/dev/null || true
  else
    # python_ensure_zeroconf already warned; keep this generic to avoid duplicates.
    warn "mDNS autodetect may fall back to manual prompts."
  fi
  return 0
}

# If baseline fails, store the last command that failed for better diagnostics.
BASELINE_ERR=""

baseline_need_python() {
  # Android 11+ (SDK 30+) only
  [[ "${ANDROID_SDK:-}" =~ ^[0-9]+$ ]] && (( ANDROID_SDK >= 30 ))
}

baseline_prereqs_ok() {
  have proot-distro && have adb && have termux-notification && have termux-dialog && have sha256sum || return 1
  if baseline_need_python; then
    have python || return 1
  fi
  return 0
}

baseline_missing_prereqs() {
  local req=(adb proot-distro termux-notification termux-dialog)
  baseline_need_python && req+=(python)
  for b in "${req[@]}"; do
    have "$b" || echo "$b"
  done
  have sha256sum || echo "sha256sum (coreutils)"
}

baseline_bail_details() {
  warn "Baseline package installation failed (network / repo unreachable or packages missing)."
  [[ -n "${BASELINE_ERR:-}" ]] && warn "Last failing command: ${BASELINE_ERR}"
  local miss=()
  mapfile -t miss < <(baseline_missing_prereqs || true)
  ((${#miss[@]})) && warn "Missing prerequisites: ${miss[*]}"
  warn "Not stamping; rerun later when prerequisites are available."
}

# Termux apt options (avoid conffile prompts)
TERMUX_APT_OPTS=( "-y" "-o" "Dpkg::Options::=--force-confdef" "-o" "Dpkg::Options::=--force-confold" )
termux_apt() { apt-get "${TERMUX_APT_OPTS[@]}" "$@"; }

# Default: enable mDNS autodetect only on Android 11+ (SDK 30+).
if [[ -z "${ADB_MDNS+x}" ]]; then
  if [[ "${ANDROID_SDK:-}" =~ ^[0-9]+$ ]] && (( ANDROID_SDK >= 30 )); then
    ADB_MDNS=1
  else
    ADB_MDNS=0
  fi
fi
ADB_MDNS_PIP_INSTALL="${ADB_MDNS_PIP_INSTALL:-1}"

# -------------------------
# Wakelock (Termux:API)
# -------------------------
WAKELOCK_HELD=0
acquire_wakelock() {
  if have termux-wake-lock; then
    if termux-wake-lock; then
      WAKELOCK_HELD=1
      ok "Wakelock acquired (termux-wake-lock)."
    else
      warn "Failed to acquire wakelock (termux-wake-lock)."
    fi
  else
    warn "termux-wake-lock not available. Install: pkg install termux-api + Termux:API app."
  fi
}
release_wakelock() {
  if [[ "$WAKELOCK_HELD" -eq 1 ]] && have termux-wake-unlock; then
    termux-wake-unlock || true
    ok "Wakelock released (termux-wake-unlock)."
    WAKELOCK_HELD=0
  fi
}

# -------------------------
# Set Battery usage step.
# -------------------------
android_am_bin() {
  # Return a usable 'am' binary path.
  if have am; then
    command -v am
    return 0
  fi
  [[ -x /system/bin/am ]] && { echo /system/bin/am; return 0; }
  return 1
}

android_start_activity() {
  # Start an Android activity via 'am'.
  local ambin
  ambin="$(android_am_bin 2>/dev/null)" || return 1
  "$ambin" start "$@" >/dev/null 2>&1
}

android_open_developer_options() {
  # Open Developer options to enable Wireless debugging.
  android_start_activity -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS && return 0
  android_start_activity -a android.settings.DEVELOPMENT_SETTINGS && return 0
  return 1
}

android_open_termux_app_info() {
  # Open Settings -> App info -> Termux (most standard across vendors).
  android_start_activity -a android.settings.APPLICATION_DETAILS_SETTINGS -d "package:${TERMUX_PACKAGE}"
}

android_open_battery_optimization_list() {
  # Optional fallback screen (varies by vendor).
  android_start_activity -a android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS
}

power_mode_battery_instructions() {
  local fd=1
  if { : >&3; } 2>/dev/null; then fd=3; fi
  {
    printf '%b' "\n${YEL}${BOLD}[iiab] IMPORTANT: Android Battery Restrictions${RST}\n"
    printf '%b' "To prevent Android from killing the installation, apply:\n"
    printf '%b' "  ${BLU}1.${RST} Battery -> ${BOLD}Unrestricted${RST} (or 'Don't optimize')\n"
    printf '%b' "  ${BLU}2.${RST} Allow background activity -> ${BOLD}ON${RST}\n\n"
   } >&"$fd"
}

power_mode_offer_battery_settings_once() {
  [[ "${POWER_MODE_BATTERY_PROMPT:-1}" -eq 1 ]] || return 0
  if [[ -f "${ANDROID_SHARED_STATE_DIR}/flag_perm_battery" ]]; then
    return 0
  fi

  mkdir -p "$STATE_DIR" >/dev/null 2>&1 || true

  local stamp="$POWER_MODE_BATTERY_STAMP"
  [[ -f "$stamp" ]] && return 0

  local outfd; outfd="$(console_outfd)"
  power_mode_battery_instructions
  countdown_timer 5 "\r${YEL}[iiab] Opening Battery Settings in %d seconds...${RST}"

  if android_open_termux_app_info; then
    printf "${YEL}[iiab] Adjust the settings. When done, return here.${RST}\n" >&"$outfd"
    if [[ -r /dev/tty ]]; then
      # -n 1 captures a single character, -s makes it silent
      read -n 1 -s -r -p "Press any key to continue... " </dev/tty || true
      printf "\n" >&"$outfd"
    else
      printf "\n" >&"$outfd"
    fi
    date > "$stamp" 2>/dev/null || true
  else
    warn "Unable to open Settings automatically. Open manually: Settings -> Apps -> Termux."
    android_open_battery_optimization_list || true
  fi
  return 0
}

# -------------------------
# Set Display Over Other Apps (Overlay) step.
# -------------------------
POWER_MODE_OVERLAY_STAMP="${STATE_DIR}/stamp.termux_overlay_settings"

power_mode_offer_overlay_settings_once() {
  [[ "${POWER_MODE_OVERLAY_PROMPT:-1}" -eq 1 ]] || return 0
  if [[ -f "${ANDROID_SHARED_STATE_DIR}/flag_perm_overlay" ]]; then
    return 0
  fi

  mkdir -p "$STATE_DIR" >/dev/null 2>&1 || true

  local stamp="$POWER_MODE_OVERLAY_STAMP"
  [[ -f "$stamp" ]] && return 0

  local outfd; outfd="$(console_outfd)"
  local sdk="${ANDROID_SDK:-}"

  # Stage 1 (Android 13+ only): The One-Step Restricted Settings & Overlay bypass
  if [[ "$sdk" =~ ^[0-9]+$ ]] && (( sdk >= 33 )); then
    {
      printf '%b' "\n${YEL}${BOLD}[iiab] Android 13+ Security Requirement${RST}\n"
      printf '%b' "Android restricts background permissions for sideloaded apps.\n"
      printf '%b' "For this to work, follow these 5 steps:\n\n"
      printf '%b' "  ${BLU}1.${RST} Tap on ${BOLD}'Display over other apps'${RST}\n     and toggle the switch.\n"
      printf '%b' "  ${BLU}2.${RST} A security warning will pop up. Tap ${BOLD}'OK'${RST}.\n"
      printf '%b' "  ${BLU}3.${RST} Go ${BOLD}BACK${RST} to the main screen (App info).\n"
      printf '%b' "  ${BLU}4.${RST} Now, on the top right you'll see ${BOLD}3 dots (⋮)${RST}\n     tap them and select ${BOLD}'Allow restricted settings'${RST}.\n"
      printf '%b' "  ${BLU}5.${RST} Finally you can go and enable\n     ${BOLD}'Display over other apps'${RST}.\n\n"
    } >&"$outfd"

    printf "\r${YEL}[iiab] Press Enter to open App Info when ready...${RST}\n"
    if [[ -r /dev/tty ]]; then
      # We use normal read (with Enter) to give the user time to read and return
      read -r _ </dev/tty || true
    fi

    if android_open_termux_app_info; then
      printf "${YEL}[iiab] When done, return here and press Enter to continue...${RST}\n" >&"$outfd"
      if [[ -r /dev/tty ]]; then
        # We use normal read (with Enter) to give the user time to read and return
        read -r _ </dev/tty || true
      fi
      printf "\n" >&"$outfd"
      date > "$stamp" 2>/dev/null || true
    else
      warn "Unable to open Settings automatically."
    fi

  # Stage 2 (Android 12 and below): Direct Overlay Permission
  else
    {
      printf '%b' "\n${YEL}${BOLD}[iiab] UX REQUIREMENT: Bring to Foreground${RST}\n"
      printf '%b' "To allow the IIAB Controller App to open Termux automatically:\n"
      printf '%b' "  Please grant the ${BOLD}'Display over other apps'${RST} permission.\n\n"
    } >&"$outfd"

    countdown_timer 5 "\r${YEL}[iiab] Opening Overlay Settings in %d seconds...${RST}"

    if android_open_overlay_settings; then
      printf "${YEL}[iiab] Enable the switch. When done, return here.${RST}\n" >&"$outfd"
      if [[ -r /dev/tty ]]; then
        read -n 1 -s -r -p "Press any key to continue... " </dev/tty || true
        printf "\n" >&"$outfd"
      else
        printf "\n" >&"$outfd"
      fi
      date > "$stamp" 2>/dev/null || true
    else
      warn "Unable to open Settings automatically."
    fi
  fi

  return 0
}

# -------------------------
# One-time repo selector
# -------------------------
repo_selector__mirror_base_dir() {
  echo "${PREFIX}/etc/termux/mirrors"
}

repo_selector__chosen_path() {
  echo "${PREFIX}/etc/termux/chosen_mirrors"
}

repo_selector__is_all_mirrors() {
  # Returns 0 if in "All mirrors" mode, 1 otherwise.
  local chosen; chosen="$(repo_selector__chosen_path)"

  # Missing -> treat as all mirrors.
  [[ -e "$chosen" || -L "$chosen" || -d "$chosen" ]] || return 0

  # Broken symlink -> pkg treats as all mirrors.
  if [[ -L "$chosen" ]] && [[ ! -e "$chosen" ]]; then
    return 0
  fi

  # If it resolves to mirrors/all, treat as all mirrors (in some installs it exists).
  local base; base="$(repo_selector__mirror_base_dir)"
  local resolved=""
  resolved="$(readlink -f "$chosen" 2>/dev/null || true)"
  if [[ -n "$resolved" && "$resolved" == "$base/all" ]]; then
    return 0
  fi

  return 1
}

repo_selector__android_country_code() {
  # Best-effort ISO-3166-1 alpha-2 (e.g. MX, US, CN, RU). Empty if unknown.
  local v=""
  for k in \
    gsm.operator.iso-country \
    gsm.sim.operator.iso-country \
    persist.sys.country \
    ro.product.locale.region \
    ro.boot.wificountrycode \
  ; do
    v="$(getprop "$k" 2>/dev/null | tr -d '\r' | tr '[:lower:]' '[:upper:]')"
    [[ -n "$v" ]] && { echo "$v"; return 0; }
  done
  return 1
}

repo_selector__android_timezone() {
  getprop persist.sys.timezone 2>/dev/null | tr -d '\r'
}

repo_selector__detect_group() {
  # Echo one of: asia|chinese_mainland|europe|north_america|oceania|russia
  # or empty if unknown.
  local cc=""; cc="$(repo_selector__android_country_code 2>/dev/null || true)"
  case "$cc" in
    CN) echo "chinese_mainland"; return 0 ;;
    RU) echo "russia"; return 0 ;;
  esac

  local tz=""; tz="$(repo_selector__android_timezone)"
  case "$tz" in
    Asia/*)      echo "asia"; return 0 ;;
    Europe/*)    echo "europe"; return 0 ;;
    Africa/*)    echo "europe"; return 0 ;;   # closest available group
    America/*)   echo "north_america"; return 0 ;;
    Australia/*) echo "oceania"; return 0 ;;
    Pacific/*)   echo "oceania"; return 0 ;;
    *)           return 1 ;;
  esac
}

repo_selector__label_for_group() {
  # args: group_id [tz_hint]
  local g="$1" tz="${2:-}"
  case "$g" in
    russia)           echo "Russia" ;;
    chinese_mainland) echo "Chinese (Mainland)" ;;
    asia)             echo "Asia" ;;
    europe)
      # If timezone suggests Africa, show that we chose Europe as closest.
      if [[ "$tz" == Africa/* ]]; then
        echo "Europe (Closest)"
      else
        echo "Europe"
      fi
      ;;
    north_america)    echo "North America" ;;
    oceania)          echo "Oceania" ;;
    all)              echo "All mirrors" ;;
    *)                echo "$g" ;;
  esac
}

repo_selector__apply_group() {
  local group="$1"
  local base chosen target
  base="$(repo_selector__mirror_base_dir)"
  chosen="$(repo_selector__chosen_path)"
  target="${base}/${group}"

  if [[ ! -d "$target" ]]; then
    warn "Repo group dir not found: $target"
    return 1
  fi

  # Remove existing chosen_mirrors if it's a symlink (same behavior as termux-change-repo).
  if [[ -L "$chosen" ]]; then
    unlink "$chosen" 2>/dev/null || rm -f "$chosen" 2>/dev/null || true
  elif [[ -e "$chosen" ]]; then
    # Unexpected: file/dir created by user. Don't delete; just warn and stop.
    warn "chosen_mirrors exists and is not a symlink: $chosen (leaving unchanged)"
    return 1
  fi

  ln -s "$target" "$chosen"

  # Force pkg to re-pick mirror from the new group and rewrite apt sources now.
  # Same intent as termux-change-repo's final step.
  ok "Repo group set: $group (chosen_mirrors -> $target)"
  pkg --check-mirror update || true
}

repo_selector_ask_configure() {
  local group="" tz="" label=""

  # Only run if still on "All mirrors".
  if ! repo_selector__is_all_mirrors; then
    return 0
  fi

  log "Configuring Termux repository location..."
  tz="$(repo_selector__android_timezone)"
  group="$(repo_selector__detect_group 2>/dev/null || true)"
  label="$(repo_selector__label_for_group "$group" "$tz")"

  if [[ -z "$group" ]]; then
    warn "Unable to detect region reliably."
    warn "Tip: run 'termux-change-repo' to select a nearby mirror group manually."
    return 0
  fi

  log "Detected repo group: "
  printf "> ${BOLD}${BLU}$label${RST}\n"
  # Auto-apply to optimize zero-touch installation
  log "Automatically applying this mirror group to optimize downloads."
  repo_selector__apply_group "$group" || true
  ok "Region repo set. You can always change it later by running: termux-change-repo"
}

# -------------------------
# Baseline packages
# -------------------------
step_termux_base() {
  local stamp="$STATE_DIR/stamp.termux_base"

  BASELINE_OK=0

  # Even if we have a stamp, validate that core commands still exist.
  if [[ -f "$stamp" ]]; then
    if baseline_prereqs_ok; then
      BASELINE_OK=1
      ok "Termux baseline already prepared (stamp found)."
      if declare -f python_patch_sysconfig_armv8l > /dev/null; then
        # Safe call: check if declared at 15_mod_prepatch_env.sh
        python_patch_sysconfig_armv8l
      fi
      # Ensure optional mDNS deps are ready from the start (does not affect stamp).
      termux_prepare_mdns_deps || true
      # Ensure boxyproxy deps are ready from the start (does not affect stamp).
      termux_prepare_boxyproxy_deps || true
      return 0
    fi
    warn "Baseline stamp found but prerequisites are missing; forcing reinstall."
    rm -f "$stamp"
  fi

# Enable communication with Controller APK
  local props="${HOME}/.termux/termux.properties"
  mkdir -p "${HOME}/.termux"
  touch "$props"

  if ! grep -qE '^allow-external-apps\s*=\s*true' "$props"; then
    log "Enabling allow-external-apps for the IIAB Controller..."

    if grep -qE '^#?\s*allow-external-apps\s*=' "$props"; then
      sed -i 's/^#\?\s*allow-external-apps\s*=.*/allow-external-apps = true/' "$props"
    else
      echo "allow-external-apps = true" >> "$props"
    fi
    have termux-reload-settings && termux-reload-settings || true
  fi

  # Test if the APK already granted us real Android storage access
  if touch /sdcard/.iiab_storage_test 2>/dev/null; then
    rm -f /sdcard/.iiab_storage_test

    # We have access! Just ensure the Termux symlinks exist silently.
    if [[ ! -d "${HOME}/storage/shared" ]]; then
      termux-setup-storage
      sleep 1 # Give it a second to create symlinks
    fi
    ok "Storage access already granted (Managed by IIAB Controller)."
  else
    log_yel "Termux needs storage access to communicate with the IIAB Controller App."
    warn "An Android permission dialog will appear."
    warn "Please tap 'Allow' (or 'All files access')."
    termux-setup-storage
    # termux-setup-storage is asynchronous, so we must pause to let the user tap 'Allow'
    countdown_timer 10 "\r${BLU}[iiab]${RST} Waiting for storage permission... %d secs "
  fi

  log "Updating Termux packages (noninteractive) and installing baseline dependencies..."
  export DEBIAN_FRONTEND=noninteractive

  if ! termux_apt update; then
    BASELINE_ERR="termux_apt update"
    baseline_bail_details
    return 1
  fi

  if ! termux_apt upgrade; then
    BASELINE_ERR="termux_apt upgrade"
    baseline_bail_details
    return 1
  fi

  local pkgs=(
    android-tools
    aria2
    ca-certificates
    coreutils
    curl
    gawk
    git
    grep
    jq
    libqrencode
    openssh
    proot
    proot-distro
    python
    rsync
    sed
    termux-api
    which
  )
  baseline_need_python && pkgs+=(python)

  if ! termux_apt install "${pkgs[@]}"; then
    BASELINE_ERR="termux_apt install (baseline deps)"
    baseline_bail_details
    return 1
  fi

  if declare -f python_patch_sysconfig_armv8l > /dev/null; then
    # Safe call: check if declared at 15_mod_prepatch_env.sh
    python_patch_sysconfig_armv8l
  fi

  if baseline_prereqs_ok; then
    BASELINE_OK=1
    ok "Termux baseline ready."
    # Prepare Python zeroconf *now* if mDNS autodetect is enabled.
    termux_prepare_mdns_deps || true
    # Prepare aiohttp now so proxy-start is reliable.
    termux_prepare_boxyproxy_deps || true
    date > "$stamp"
    return 0
  fi

  BASELINE_ERR="post-install check (commands missing after install)"
  baseline_bail_details
  return 1
}

# -------------------------
# Python + zeroconf prep (Android 11+)
# -------------------------
step_termux_python_zeroconf() {
  baseline_need_python || return 0
  if [[ -f "$TERMUX_ZEROCONF_STAMP" ]] && python_has_zeroconf; then
    return 0
  fi
  [[ -f "$TERMUX_ZEROCONF_STAMP" ]] && rm -f "$TERMUX_ZEROCONF_STAMP" >/dev/null 2>&1 || true

  if ! have python; then
    warn "Android 11+: python is expected but missing; skipping zeroconf prep."
    return 0
  fi

  termux_prepare_mdns_deps
  python_has_zeroconf && date > "$TERMUX_ZEROCONF_STAMP" 2>/dev/null || true
}
