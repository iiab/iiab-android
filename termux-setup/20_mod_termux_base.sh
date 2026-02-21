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

  warn "Python module 'zeroconf' not found. Trying to install it: python -m pip install --upgrade zeroconf"
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

  warn "Python module 'aiohttp' not found. Trying pip install..."
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
    ok "mDNS autodetect dependency already present (python: zeroconf)."
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

# -------------------------
# Android info
# -------------------------
get_android_sdk()     { getprop ro.build.version.sdk 2>/dev/null || true; }
get_android_release() { getprop ro.build.version.release 2>/dev/null || true; }
ANDROID_SDK="$(get_android_sdk)"
ANDROID_REL="$(get_android_release)"

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
    # Print header in yellow. + bold
    printf '%b' "${YEL}${BOLD}"
    cat <<'EOF'
[iiab] Power-mode needs one manual Android setting:
EOF

    # Print body in blue
    printf '%b' "${RST}"
    printf '%b' "${BOLD}"
    cat <<'EOF'

Some devices let Termux set "Battery usage" correctly by default, but not all do; please double-check:

 Settings -> Apps -> Termux -> Battery
   - Set: Unrestricted
     - or: Don't optimize / No restrictions
   - Allow background activity = ON (if present)

 If you can't find Battery under App info, use Android's Battery optimization list and set Termux to "Don't optimize".

> Note: Power-mode (wakelock + notification) helps keep the session alive, but it cannot override Android's battery restrictions.

EOF

    # Reset colors
    printf '%b' "${RST}"
  } >&"$fd"
}

power_mode_offer_battery_settings_once() {
  [[ "${POWER_MODE_BATTERY_PROMPT:-1}" -eq 1 ]] || return 0
  mkdir -p "$STATE_DIR" >/dev/null 2>&1 || true

  local stamp="$POWER_MODE_BATTERY_STAMP"
  [[ -f "$stamp" ]] && return 0

  power_mode_battery_instructions

  if tty_yesno_default_y "${YEL}[iiab] Open Termux App info to adjust Battery policy? [Y/n]: ${RST}"; then
    if android_open_termux_app_info; then
      local outfd
      outfd="$(console_outfd)"
      printf "[iiab] When done, return to Termux and press Enter to continue... " >&"$outfd"
      if [[ -r /dev/tty ]]; then
        read -r _ </dev/tty || true
      else
        local outfd
        outfd="$(console_outfd)"
        printf "\n" >&"$outfd"
      fi
      date > "$stamp" 2>/dev/null || true
    else
      warn "Unable to open Settings automatically. Open manually: Settings -> Apps -> Termux."
      warn "Fallback: you may try opening the Battery optimization list from Android settings."
      # Best-effort fallback (ignore errors)
      android_open_battery_optimization_list || true
      # Do not stamp here: user likely still needs to configure it.
    fi
  else
    warn "Battery settings step skipped by user; you'll be asked again next time."
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
      # Ensure optional mDNS deps are ready from the start (does not affect stamp).
      termux_prepare_mdns_deps || true
      # Ensure boxyproxy deps are ready from the start (does not affect stamp).
      termux_prepare_boxyproxy_deps || true
      return 0
    fi
    warn "Baseline stamp found but prerequisites are missing; forcing reinstall."
    rm -f "$stamp"
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
    ca-certificates
    coreutils
    curl
    gawk
    grep
    jq
    openssh
    proot
    proot-distro
    python
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
