# shellcheck shell=bash
# Module file (no shebang). Bundled by build_bundle.sh

install_iiab_android_cmd() {
  have proot-distro || die "proot-distro not found"
  iiab_exists || { warn_red "IIAB Debian (alias 'iiab') not installed."; return 1; }

  local url="${IIAB_ANDROID_URL:-https://raw.githubusercontent.com/iiab/iiab-android/main/iiab-android}"
  local dest="${IIAB_ANDROID_DEST:-/usr/local/sbin/iiab-android}"
  local tmp="/tmp/iiab-android.$$"

  local meta old new rc=0
  set +e
  meta="$(proot-distro login iiab -- env URL="$url" DEST="$dest" TMP="$tmp" bash -lc '
    set -e
    old=""
    if [ -r "$DEST" ]; then old="$(sha256sum "$DEST" 2>/dev/null | cut -d" " -f1 || true)"; fi
    if ! command -v curl >/dev/null 2>&1; then
      export DEBIAN_FRONTEND=noninteractive
      apt-get update
      apt-get -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold install ca-certificates curl coreutils
    fi
    curl -fsSL --retry 5 --retry-connrefused --retry-delay 2 "$URL" -o "$TMP"
    head -n1 "$TMP" | grep -q "bash" || { echo "BAD_SHEBANG"; exit 2; }
    new="$(sha256sum "$TMP" | cut -d" " -f1)"
    echo "OLD=$old"
    echo "NEW=$new"
  ' 2>&1)"
  rc=$?
  set -e

  if (( rc != 0 )); then
    if printf '%s\n' "$meta" | grep -q 'BAD_SHEBANG'; then
      warn_red "Downloaded iiab-android does not look like a bash script (bad shebang)."
    else
      warn_red "Failed to fetch/install iiab-android in proot (rc=$rc)."
      printf "%s\n" "$meta" | indent >&2
    fi
    return 1
  fi

  old="$(printf '%s\n' "$meta" | sed -n 's/^OLD=//p' | head -n1)"
  new="$(printf '%s\n' "$meta" | sed -n 's/^NEW=//p' | head -n1)"

  if [[ -n "$old" && "$old" == "$new" ]]; then
    ok "iiab-android already up to date inside proot."
    proot-distro login iiab -- env TMP="$tmp" bash -lc 'rm -f "$TMP" >/dev/null 2>&1 || true' || true
    return 0
  fi

  if [[ -n "$old" && "$old" != "$new" ]]; then
    warn "iiab-android exists and differs inside proot."
    if ! tty_yesno_default_n "[iiab] Replace existing iiab-android inside proot? [y/N]: "; then
      warn "Keeping existing iiab-android."
      proot-distro login iiab -- env TMP="$tmp" bash -lc 'rm -f "$TMP" >/dev/null 2>&1 || true' || true
     return 0
    fi
  fi

  proot-distro login iiab -- env DEST="$dest" TMP="$tmp" bash -lc '
    set -e
    mkdir -p "$(dirname "$DEST")"
    if [ -f "$DEST" ]; then
      ts="$(date +%Y%m%d-%H%M%S 2>/dev/null || echo now)"
      mv -f "$DEST" "${DEST}.old.${ts}" 2>/dev/null || true
    fi
    install -m 0755 "$TMP" "$DEST"
    rm -f "$TMP" >/dev/null 2>&1 || true
  ' || { warn_red "Failed to finalize iiab-android install inside proot."; return 1; }

  ok "Installed inside proot: $dest"
  ok "Next (inside proot): iiab-android"
}
