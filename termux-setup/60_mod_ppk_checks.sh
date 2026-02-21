# shellcheck shell=bash
# Module file (no shebang). Bundled by build_bundle.sh

# PPK / phantom-process checks and tuning via ADB (best-effort)
# Moved out of 99_main.sh to keep it as an orchestrator.

# -------------------------
# PPK / phantom-process tuning (best-effort)
# -------------------------
ppk_fix_via_adb() {
  need adb || die "Missing adb. Install: pkg install android-tools"

  local serial
  if ! serial="$(adb_pick_loopback_serial)"; then
    CHECK_NO_ADB=1
    warn "No ADB loopback device connected (expected ${HOST}:${CONNECT_PORT:-*})."
    return 1
  fi
  ok "Using ADB device: $serial"

  local sdk max
  sdk="$(adb -s "$serial" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || true)"
  max="${PPK_MAX:-256}"

  # Android 14+ (SDK 34+) -> max_phantom_processes is informational; rely on "Disable child process restrictions".
  if [[ "${sdk:-}" =~ ^[0-9]+$ ]] && (( sdk >= 34 )); then
    log "PPK: sdk=${sdk} (A14+). Skipping max_phantom_processes; rely on Child restrictions."
    return 0
  fi

  log "Setting PPK: max_phantom_processes=${max}"
# Some Android versions may ignore/rename this; we don't hard-fail.
adb -s "$serial" shell sh -s -- "$max" <<'EOF' || true
    set -e
    max="${1:-256}"

    # Prefer device_config binary; fallback to "cmd device_config" on some ROMs.
    if command -v device_config >/dev/null 2>&1; then
      dc() { device_config "$@"; }
    elif command -v cmd >/dev/null 2>&1; then
      dc() { cmd device_config "$@"; }
    else
      echo "device_config not available; skipping."
      exit 0
    fi

    dc set_sync_disabled_for_tests persistent >/dev/null 2>&1 || true
    dc put activity_manager max_phantom_processes "$max" >/dev/null 2>&1 || true

    echo "dumpsys effective max_phantom_processes:"
    dumpsys activity settings 2>/dev/null | grep -i "max_phantom_processes=" | head -n 1 || true
EOF

  ok "PPK set done (best effort)."
  return 0
}

# Prefer Android 14+ feature-flag override if present:
# OFF -> true, ON -> false
adb_get_child_restrictions_flag() {
  local serial="$1"
  adb -s "$serial" shell getprop persist.sys.fflag.override.settings_enable_monitor_phantom_procs 2>/dev/null | tr -d '\r' || true
}

# SDK 34 / Android 14 can return 1 / 0 , instead of "true" / "false".
normalize_bool_tf01() {
  local v="${1:-}"
  v="${v//$'\r'/}"
  v="${v,,}"
  case "$v" in
    0|false) echo "false" ;;
    1|true)  echo "true" ;;
    *)       echo "${1:-}" ;;
  esac
}

adb_settings_get_key() {
  # args: serial namespace key
  local serial="$1" ns="$2" key="$3"
  adb -s "$serial" shell settings get "$ns" "$key" 2>/dev/null | tr -d '\r' | tr -d '[:space:]' || true
}

adb_device_config_get_key() {
  # args: serial namespace key  (namespace here is device_config "activity_manager")
  local serial="$1" ns="$2" key="$3"
  local out=""
  out="$(adb -s "$serial" shell device_config get "$ns" "$key" 2>/dev/null || true)"
  # Some ROMs expose it only via "cmd device_config"
  if [[ -z "${out//[$'\r\n\t ']/}" || "${out//$'\r'/}" == "null" ]]; then
    out="$(adb -s "$serial" shell cmd device_config get "$ns" "$key" 2>/dev/null || true)"
  fi
  printf '%s' "$out" | tr -d '\r' | tr -d '[:space:]' || true
}

# Unified reader for Android "Disable child process restrictions"
# Prints: true | false | unknown
adb_get_monitor_phantom_procs() {
  local serial="$1"
  local v=""

  # 1) settings global/secure (keys vary by Android version/OEM)
  local keys=(
    settings_enable_monitor_phantom_procs
    enable_monitor_phantom_procs
    settings_enable_monitor_phantom_processes
    enable_monitor_phantom_processes
  )
  local ns k
  for ns in global secure; do
    for k in "${keys[@]}"; do
      v="$(adb_settings_get_key "$serial" "$ns" "$k")"
      v="$(normalize_bool_tf01 "$v")"
      [[ "$v" == "true" || "$v" == "false" ]] && { echo "$v"; return 0; }
    done
  done

  # 2) device_config fallbacks
  if adb -s "$serial" shell 'command -v device_config >/dev/null 2>&1 || command -v cmd >/dev/null 2>&1'; then
    for k in "${keys[@]}"; do
      v="$(adb_device_config_get_key "$serial" activity_manager "$k")"
      v="$(normalize_bool_tf01 "$v")"
      [[ "$v" == "true" || "$v" == "false" ]] && { echo "$v"; return 0; }
    done
  fi

  # 3) legacy helper (last resort)
  v="$(adb_get_child_restrictions_flag "$serial" 2>/dev/null | tr -d '\r' | tr -d '[:space:]' || true)"
  v="$(normalize_bool_tf01 "$v")"
  [[ "$v" == "true" || "$v" == "false" ]] && { echo "$v"; return 0; }

  echo "unknown"
}

adb_disable_child_process_restrictions_via_adb() {
  # Best-effort: try to force "Disable child process restrictions" ON
  # Expected readback: monitor=false
  local serial="$1"
  local keys=(
    settings_enable_monitor_phantom_procs
    enable_monitor_phantom_procs
    settings_enable_monitor_phantom_processes
    enable_monitor_phantom_processes
  )

  log "Android 14+: trying to disable child process restrictions via ADB (best-effort)..."
  adb -s "$serial" shell sh -s <<'EOF' || true
set -e
keys="settings_enable_monitor_phantom_procs enable_monitor_phantom_procs settings_enable_monitor_phantom_processes enable_monitor_phantom_processes"

# Prefer settings (some ROMs use global, others secure)
for ns in global secure; do
  for k in $keys; do
    settings put "$ns" "$k" 0 >/dev/null 2>&1 || true
  done
done

# DeviceConfig fallback
if command -v device_config >/dev/null 2>&1; then
  for k in $keys; do
    device_config put activity_manager "$k" false >/dev/null 2>&1 || true
  done
fi
EOF

  local mon
  mon="$(adb_get_monitor_phantom_procs "$serial")"
  if [[ "$mon" == "false" ]]; then
    ok "Child restrictions: OK (monitor=false)"
    return 0
  fi
  warn "Child restrictions: could not verify (monitor='${mon:-}'). You may still need to toggle it manually in Developer options."
  return 1
}

# -------------------------
# Check readiness (best-effort)
# -------------------------
check_readiness() {
  # Reset exported check signals so final_advice() never sees stale values
  CHECK_NO_ADB=0
  CHECK_SDK=""
  CHECK_MON=""
  CHECK_PPK=""

  need adb || die "Missing adb. Install: pkg install android-tools"
  adb start-server >/dev/null 2>&1 || true

  local serial
  if ! serial="$(adb_pick_loopback_serial)"; then
    CHECK_NO_ADB=1
    # Best-effort: keep local SDK so final_advice can still warn on A12-13.
    CHECK_SDK="${ANDROID_SDK:-}"
    warn_red "No ADB device connected. Cannot run checks."
    warn "If already paired before: run --connect-only [PORT]."
    warn "Otherwise: run --adb-only to pair+connect."
    return 1
  fi

  ok "Check using ADB device: $serial"

  local dev_enabled sdk rel mon mon_fflag ds ppk_eff
  sdk="$(adb -s "$serial" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || true)"
  rel="$(adb -s "$serial" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r' || true)"
  dev_enabled="$(adb -s "$serial" shell settings get global development_settings_enabled 2>/dev/null | tr -d '\r' || true)"
  mon="$(adb_get_monitor_phantom_procs "$serial")"

  # If user opted-in to ADB and we're in --all, be proactive on Android 14+.
  # Allow override: CHILD_RESTRICTIONS_AUTOFIX=0
  if [[ "${MODE:-}" == "all" ]] && [[ "${CHILD_RESTRICTIONS_AUTOFIX:-1}" -eq 1 ]]; then
    if [[ "${sdk:-}" =~ ^[0-9]+$ ]] && (( sdk >= 34 )) && [[ "${dev_enabled:-}" == "1" ]]; then
      if [[ "$mon" != "false" ]]; then
        adb_disable_child_process_restrictions_via_adb "$serial" || true
        mon="$(adb_get_monitor_phantom_procs "$serial")"
      fi
    fi
  fi

  # Get effective value from dumpsys (device_config get may return 'null' even when an effective value exists)
  ds="$(adb -s "$serial" shell dumpsys activity settings 2>/dev/null | tr -d '\r' || true)"
  ppk_eff="$(printf '%s\n' "$ds" | awk -F= '/max_phantom_processes=/{print $2; exit}' | tr -d '[:space:]' || true)"

  # Export check signals for the final advice logic
  CHECK_SDK="${sdk:-}"
  CHECK_MON="${mon:-}"
  CHECK_PPK="${ppk_eff:-}"

  log " Android release=${rel:-?} sdk=${sdk:-?}"

  if [[ "${dev_enabled:-}" == "1" ]]; then
    ok " Developer options: enabled (development_settings_enabled=1)"
  elif [[ -n "${dev_enabled:-}" ]]; then
    warn " Developer options: unknown/disabled (development_settings_enabled=${dev_enabled})"
  else
    warn " Developer options: unreadable (permission/ROM differences)."
  fi

  # Android 14+ only: "Disable child process restrictions" proxy flag
  if [[ "${sdk:-}" =~ ^[0-9]+$ ]] && (( sdk >= 34 )); then
    if [[ "${mon:-}" == "false" ]]; then
      ok " Child restrictions: OK (monitor=false)"
    elif [[ "${mon:-}" == "true" ]]; then
      warn_red " Child restrictions: NOT OK (monitor=true)"
    elif [[ -n "${mon:-}" && "${mon:-}" != "null" ]]; then
      warn " Child restrictions: unknown (${mon})"
    else
      warn " Child restrictions: unreadable/absent"
    fi
  fi

  # Android 12-13 only: PPK matters (use effective value from dumpsys)
  if [[ "${sdk:-}" =~ ^[0-9]+$ ]] && (( sdk >= 31 && sdk <= 33 )); then
    if [[ "${ppk_eff:-}" =~ ^[0-9]+$ ]]; then
      if (( ppk_eff >= 256 )); then
        ok " PPK: OK (max_phantom_processes=${ppk_eff})"
      else
        warn_red " PPK: low (max_phantom_processes=${ppk_eff}) -> suggest: run --ppk-only"
      fi
    else
      warn " PPK: unreadable (dumpsys max_phantom_processes='${ppk_eff:-}')."
    fi
  fi

  log " dumpsys (phantom-related):"
  printf '%s\n' "$ds" | grep -i phantom || true

  if [[ "${sdk:-}" =~ ^[0-9]+$ ]] && (( sdk >= 34 )); then
    log " Note: On A14+, max_phantom_processes is informational; rely on Child restrictions."
  fi
  if [[ "${sdk:-}" =~ ^[0-9]+$ ]] && (( sdk >= 34 )) && [[ "${mon:-}" == "false" ]]; then
    log " Child restrictions OK."
  fi
  return 0
}

self_check_android_flags() {
  have adb || return 0
  adb start-server >/dev/null 2>&1 || true

  local serial sdk rel mon mon_fflag ds ppk_eff
  serial="$(adb_pick_loopback_serial 2>/dev/null)" || {
    log "ADB: no loopback device connected. Tip: run --adb-only or --check if you require it."
    return 0
  }

  sdk="$(adb -s "$serial" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || true)"
  rel="$(adb -s "$serial" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r' || true)"
  log " Android flags (quick): release=${rel:-?} sdk=${sdk:-?} serial=$serial"

  if [[ "$sdk" =~ ^[0-9]+$ ]] && (( sdk >= 34 )); then
    mon="$(adb_get_monitor_phantom_procs "$serial")"
    if [[ "$mon" == "false" ]]; then
      ok " Child restrictions: OK (monitor=false)"
    elif [[ "$mon" == "true" ]]; then
      warn_red_context " Child restrictions: NOT OK (monitor=true) -> check Developer Options"
    else
      warn " Child restrictions: unknown/unreadable (monitor='${mon:-}')"
    fi
  fi

  if [[ "$sdk" =~ ^[0-9]+$ ]] && (( sdk >= 31 && sdk <= 33 )); then
    ds="$(adb -s "$serial" shell dumpsys activity settings 2>/dev/null | tr -d '\r' || true)"
    ppk_eff="$(printf '%s\n' "$ds" | awk -F= '/max_phantom_processes=/{print $2; exit}' | tr -d '[:space:]' || true)"

    if [[ "$ppk_eff" =~ ^[0-9]+$ ]]; then
      if (( ppk_eff >= 256 )); then
        ok " PPK: OK (max_phantom_processes=$ppk_eff)"
      else
        warn_red_context " PPK: low (max_phantom_processes=$ppk_eff) -> suggest: --ppk-only"
      fi
    else
      warn " PPK: unreadable (max_phantom_processes='${ppk_eff:-}')"
    fi
  fi

  # Avoid redundant tip when we're already in --check mode.
  if [[ "${MODE:-}" != "check" && "${MODE:-}" != "all" ]]; then
    log " Tip: run --check for full details."
  fi
}
