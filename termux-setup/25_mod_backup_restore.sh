# shellcheck shell=bash
# Module file (no shebang). Bundled by build_bundle.sh

get_android_arch() {
  case "$(uname -m)" in
    aarch64)       echo "arm64-v8a" ;;
    armv7l|armv8l) echo "armeabi-v7a" ;;
    x86_64)        echo "x86_64" ;;
    i686)          echo "x86" ;;
    *)             echo "unknown" ;;
  esac
}

cmd_backup_rootfs() {
  local custom_path="${1:-}"
  local out_file

  have proot-distro || die "proot-distro is not installed. Run the baseline first."
  iiab_exists || die "IIAB Debian (alias 'iiab') is not installed. Nothing to backup."

  if [[ -n "$custom_path" ]]; then
    out_file="$custom_path"
  else
    local ts arch
    # Format: YYYY.DDD.HHMM (Day of year: %j)
    ts="$(date -u +"%Y.%j.%H%M")"
    arch="$(get_android_arch)"
    out_file="iiab-android_rootfs_${ts}_${arch}.tar.gz"
  fi

  log "Starting backup of IIAB Debian..."
  log "Destination: $out_file"

  if proot-distro backup iiab --output "$out_file"; then
    ok "Backup completed successfully: $out_file"
  else
    warn_red "Failed to create backup."
    return 1
  fi
}

cmd_restore_rootfs() {
  local backup_file="${1:-}"

  [[ -z "$backup_file" ]] && die "You must specify the path to the backup file to restore."
  [[ -f "$backup_file" ]] || die "Backup file does not exist: $backup_file"

  have proot-distro || die "proot-distro is not installed."

  log "Restoring IIAB Debian from: $backup_file"

  if iiab_exists; then
    log_yel "Restoration will overwrite the current system."
  fi

  if proot-distro restore "$backup_file"; then
    ok "Restoration completed successfully."
    BASELINE_OK=1
  else
    warn_red "Failed to restore backup."
    return 1
  fi
}

check_url_architecture() {
  local url="$1" dev_arch="$2" allow_mismatch="$3"
  local url_lower; url_lower="$(printf '%s' "$url" | tr '[:upper:]' '[:lower:]')"

  local count=0 detected=""

  # Identification by keywords
  if [[ "$url_lower" == *arm64* || "$url_lower" == *aarch64* || "$url_lower" == *v8a* ]]; then
    detected="arm64-v8a"; count=$((count + 1))
  fi
  if [[ "$url_lower" == *armv7* || "$url_lower" == *v7a* || "$url_lower" == *armeabi* ]]; then
    detected="armeabi-v7a"; count=$((count + 1))
  fi
  if [[ "$url_lower" == *x86_64* || "$url_lower" == *amd64* ]]; then
    detected="x86_64"; count=$((count + 1))
  elif [[ "$url_lower" == *x86* || "$url_lower" == *i686* || "$url_lower" == *i386* ]]; then
    detected="x86"; count=$((count + 1)) # Excluded if x86_64 matched
  fi

  # Case B: None or a contradictory mixture
  if (( count == 0 )); then
   log_yel "Cannot verify device compatibility from URL or Meta4."
   log_yel "Proceed only if you trust this file matches your device ($dev_arch)."
   read -r -p "Press [Enter] to continue, or [Ctrl+C] to cancel... "
   return 0
  elif (( count > 1 )); then
   log_yel "Contradictory architectures detected between URL and Meta4!"
   log_yel "Proceed only if you trust this file matches your device ($dev_arch)."
   read -r -p "Press [Enter] to continue, or [Ctrl+C] to cancel... "
   return 0
  fi

  # Case A: Exact match (Silent to avoid screen clutter)
  [[ "$detected" == "$dev_arch" ]] && return 0

  # Case C: Clear mismatch
  if [[ "$allow_mismatch" -eq 1 ]]; then
    log_yel "Arch mismatch ($detected vs $dev_arch) ignored via --arch-mismatch-ok."
    return 0
  else
    warn_red "The target file indicates an architecture ($detected) that doesn't match your device ($dev_arch)."
    die "To proceed anyway, run again with: --arch-mismatch-ok"
  fi
}

cmd_pull_rootfs() {
  local target_url="${1:-}"
  local use_meta4="${2:-1}"  # 1 = yes, 0 = no (--no-meta4)
  local keep_tarball="${3:-0}" # 1 = keep, 0 = delete (default)
  local arch_mismatch_ok="${4:-0}"

  if [[ -z "$target_url" || "$target_url" == "AUTO" ]]; then
    local arch
    arch="$(get_android_arch)"

    if [[ "$arch" == "arm64-v8a" ]]; then
      target_url="$IIAB_ROOTFS_URL_ARM64"
    elif [[ "$arch" == "armeabi-v7a" ]]; then
      target_url="$IIAB_ROOTFS_URL_ARM32"
    else
      die "Arquitectura no soportada para descarga automática: $arch. Especifica una URL manualmente."
    fi

    log "Seleccionando automáticamente imagen base para $arch:"
    log "> $target_url"
  fi

  [[ -z "$target_url" ]] && die "You must provide a URL to download the rootfs."

  # Ensure aria2 and curl are available
  have aria2c || { log "Installing aria2..."; termux_apt update || true; termux_apt install aria2 || die "Failed to install aria2."; }
  have curl || { log "Installing curl..."; termux_apt install curl || die "Failed to install curl."; }

  local dest_dir="${STATE_DIR}/downloads"
  mkdir -p "$dest_dir"

 log "Resolving target URL and checking availability..."
 local curl_out; curl_out=$(curl -sLI -o /dev/null -w "%{http_code}|%{url_effective}" "$target_url" || true)
 local http_code="${curl_out%%|*}"
 local download_url="${curl_out##*|}"

 # Fail quickly if the link is broken or misspelled.
 if [[ "$http_code" == "404" ]]; then
   die "HTTP 404 Not Found: There is nothing here. Please verify the URL for typos."
 elif [[ "$http_code" == "000" || -z "$http_code" ]]; then
   die "Connection failed: Could not reach the server. Please check your internet or the URL."
 elif [[ "$http_code" -ge 400 ]]; then
   die "HTTP Error $http_code: Unable to access the requested file."
 fi

 [[ -z "$download_url" ]] && download_url="$target_url"

  if [[ "$download_url" != "$target_url" ]]; then
    log "Resolved to: $download_url"
  fi

  # Meta4 Logic
  if [[ "$use_meta4" -eq 1 ]]; then
    if [[ "$download_url" == *.meta4 ]]; then
      log "URL directly provides a .meta4 file."
    else
      if curl -IsLf "${download_url}.meta4" > /dev/null 2>&1; then
        download_url="${download_url}.meta4"
      else
        log_yel "No .meta4 file found. Falling back to direct download."
      fi
    fi
  else
    log "Mode --no-meta4 is active. Skipping Metalink check."
  fi

  local dht_file="${STATE_DIR}/dht.dat"

  # UX trick: "Preheat" DHT before execution.
  if [[ ! -f "$dht_file" ]]; then
    aria2c --enable-dht=true \
           --dht-file-path="$dht_file" \
           --stop=1 \
           "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567" \
           >/dev/null 2>&1 || true
  fi

  # 1. Determine local file name
  local file_name=""
  local expected_name="${target_url##*/}"
  expected_name="${expected_name%.meta4}"

  if [[ "$use_meta4" -eq 1 && "$download_url" == *.meta4 ]]; then
    # Parse the <name> or fallback to the file part of the first <url>
    file_name=$(curl -sL "$download_url" | awk -F'[<>]' '/<name>/ && !found {print $3; found=1}' | tr -d '\r ' || true)
    if [[ -z "$file_name" ]]; then
      local meta_url; meta_url=$(curl -sL "$download_url" | awk -F'[<>]' '/<url.*>/ && !found {print $3; found=1}' | tr -d '\r ' || true)
      file_name="${meta_url##*/}"
    fi

   # Validate meta4 content matches requested tarball (if a tarball was originally requested)
   if [[ "$target_url" != *.meta4 && "$file_name" != "$expected_name" ]]; then
     log_yel "Meta4 content ($file_name) does not match requested tarball ($expected_name)."
     log_yel "Discarding .meta4 and falling back to direct download."
     download_url="${download_url%.meta4}"
     file_name="$expected_name"
   fi
  fi

  if [[ -z "$file_name" ]]; then
    file_name="${download_url##*/}"     # Extract filename from URL
    file_name="${file_name%.meta4}"     # Strip .meta4 extension if present
  fi

 # --- DEFINITIVE ARCHITECTURE CHECKUP ---
 # We check both the original target URL and the final resolved file_name.
 # This detects missing info OR contradictory info across both sources.
 check_url_architecture "${target_url} | ${file_name}" "$(get_android_arch)" "$arch_mismatch_ok"

  local out_path="${dest_dir}/${file_name}"

  # Smart Toggle: verify integrity only if available.
  local check_int="false"
  if [[ -f "$out_path" ]]; then
    log "Local file detected ($file_name). Verifying integrity via P2P/Metalink..."
    check_int="true"
  fi

  # 2. Check size before downloading
  local remote_bytes=""

  # Priority 1: Get exact size directly from the Metalink XML file
  if [[ "$use_meta4" -eq 1 && "$download_url" == *.meta4 ]]; then
    log "Fetching image size from Metalink file..."
    # Downloads the XML, finds the <size> tag, extracts the number, and cleans any carriage returns
    remote_bytes=$(curl -sL "$download_url" | awk -F'[<>]' '/<size>/ && !found {print $3; found=1}' | tr -d '\r ' || true)
  fi

  # Priority 2: Fallback to HTTP headers
  if [[ -z "$remote_bytes" ]]; then
    log "Checking image size via HTTP headers..."
    local size_url="${download_url%.meta4}"
    # Follow redirects (-L) and safely parse with awk to survive 'set -e pipefail'
    remote_bytes=$(curl -sIL "$size_url" | awk 'tolower($1) ~ /^content-length/ {print $2}' | tail -n 1 | tr -d '\r' || true)
  fi || true

  # Get free space in MB
  local free_mb; free_mb=$(df -k "$PREFIX" | awk 'END{print $4 / 1024}' | cut -d. -f1)

  if [[ -n "$remote_bytes" ]]; then
     local req_space=$(( remote_bytes * 25 / 10 / 1048576 )) # 2.5x in MB
     # Floor fallback: ensure at least 5GB for pull-rootfs
     [[ "$req_space" -lt 5120 ]] && req_space=5120
     local img_gb; img_gb=$(awk -v bytes="$remote_bytes" 'BEGIN { printf "%.2f", bytes / 1073741824 }')
     local req_gb; req_gb=$(awk -v mb="$req_space" 'BEGIN { printf "%.2f", mb / 1024 }')
     local free_gb_disp; free_gb_disp=$(awk -v mb="$free_mb" 'BEGIN { printf "%.2f", mb / 1024 }')
     log "Image size: ${img_gb} GB. Safety threshold: ${req_gb} GB."

     if [[ "$free_mb" -lt "$req_space" ]]; then
        die "Insufficient space! You need ${req_gb} GB, but only ${free_gb_disp} GB are free."
     fi
  else
     # Fallback if server doesn't report size
     log_yel "Could not determine remote size. Applying 5.00 GB safety floor."
     if [[ "$free_mb" -lt 5120 ]]; then
        local free_gb_disp; free_gb_disp=$(awk -v mb="$free_mb" 'BEGIN { printf "%.2f", mb / 1024 }')
        die "Risk of saturation! At least 5.00 GB free required when size is unknown (You have ${free_gb_disp} GB)."
     fi
  fi

  log "Downloading rootfs..."

  # Mimic aria2 arguments in ansible playbooks
  local aria_args=(
    # --- Dirs and files ---
    "--dir=$dest_dir"
    "--continue=true"
    "--allow-overwrite=false"
    "--auto-file-renaming=false"

    # --- Connections and performance ---
    "--max-connection-per-server=4"
    "--file-allocation=falloc"
    "--enable-http-pipelining=true"
    "--async-dns=false"
    "--connect-timeout=60"

    # --- P2P & metalink ---
    "--follow-metalink=mem"
    "--enable-dht=true"
    "--dht-file-path=$dht_file"
    "--bt-enable-lpd=true"
    "--check-integrity=$check_int"
    "--seed-time=0"

    # --- Console output & logs ---
    "--log-level=warn"
    "--console-log-level=warn"
    "--summary-interval=0"
    "--show-console-readout=true"
    "--download-result=hide"

    # --- Target ---
    "$download_url"
  )

  # Run aria2 bypassing the log to maintain the interactive bar
  if : >&3 2>/dev/null && : >&4 2>/dev/null; then
    aria2c "${aria_args[@]}" >&3 2>&4 || die "aria2 download failed."
  else
    aria2c "${aria_args[@]}" || die "aria2 download failed."
  fi

  # As a fallback, ensure the targeted file actually exists
  if [[ ! -f "$out_path" ]]; then
    log_yel "Expected file not found at $out_path, scanning directory..."
    out_path=$(ls -1t "$dest_dir"/*.tar.gz 2>/dev/null | head -n 1 || true)
    if [[ -z "$out_path" || ! -f "$out_path" ]]; then
      die "Download finished, but no .tar.gz file was found in $dest_dir"
    fi
  fi

  ok "Download finished: $out_path"

  # Proceed with restoration
  cmd_restore_rootfs "$out_path"

  # --- POST-RESTORE CLEANUP ---
  if [[ "$keep_tarball" -eq 1 ]]; then
    ok "Flag '--keep-tarball' active. Keeping rootfs archive at: $out_path"
  else
    # 1. Gather stats before deletion
    local count; count=$(ls -1 "$dest_dir"/*.tar.gz 2>/dev/null | wc -l)
    local folder_size; folder_size=$(du -sm "$dest_dir" 2>/dev/null | awk '{printf "%.2f GB", $1 / 1024}')
    if rm -f "$dest_dir"/*.tar.gz >/dev/null 2>&1; then
      local final_free; final_free=$(df -h "$PREFIX" | awk 'END{print $4}')
      ok "Space freed: $folder_size out of $count tarball(s)."
      log "Remaining free space: $final_free"
      log "Tip: Use '--keep-tarball' next time to preserve the downloaded images."
    else
      warn "Cleanup failed. Please check permissions in $dest_dir"
    fi
  fi
}
