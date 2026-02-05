# shellcheck shell=bash
# Module file (no shebang). Bundled by build_bundle.sh

# -------------------------
# IIAB Debian bootstrap
# -------------------------
iiab_exists() {
  have proot-distro || return 1
  proot-distro login iiab -- true >/dev/null 2>&1
}

ensure_proot_distro() {
  if have proot-distro; then return 0; fi
  warn "proot-distro not found; attempting to install..."
  termux_apt install proot-distro || true
  have proot-distro
}

proot_install_iiab_safe() {
  local out rc
  set +e
  local help
  help="$(proot-distro install --help 2>&1 || true)"
  if ! printf '%s\n' "$help" | grep -q -- '--override-alias'; then
    warn_red "proot-distro is too old (missing --override-alias). Please upgrade Termux packages and retry."
    return 1
  fi
  out="$(proot-distro install --override-alias iiab debian 2>&1)"
  rc=$?
  set -e
  if [[ $rc -eq 0 ]]; then return 0; fi
  if echo "$out" | grep -qi "already installed"; then
    warn "IIAB Debian is already installed; continuing."
    return 0
  fi
  printf "%s\n" "$out" >&2
  return $rc
}

step_iiab_bootstrap_default() {
  if ! ensure_proot_distro; then
    warn "Unable to ensure proot-distro; skipping IIAB Debian bootstrap."
    return 0
  fi

  if [[ "$RESET_IIAB" -eq 1 ]]; then
    warn "Reset requested: reinstalling IIAB Debian (clean environment)..."
    if proot-distro help 2>/dev/null | grep -qE '\breset\b'; then
      proot-distro reset iiab || true
      # If reset was requested but iiab wasn't installed yet (or reset failed), ensure it's present.
      iiab_exists || proot_install_iiab_safe || true
    else
      if iiab_exists; then proot-distro remove iiab || true; fi
      proot_install_iiab_safe || true
    fi
  else
    if iiab_exists; then
      ok "IIAB Debian already present in proot-distro. Not reinstalling."
    else
      log "Installing IIAB Debian (proot-distro install --override-alias iiab debian)..."
      proot_install_iiab_safe || true
    fi
  fi

  log "Installing minimal tools inside IIAB Debian (noninteractive)..."
  if ! iiab_exists; then
    warn_red "IIAB Debian is not available in proot-distro (install may have failed). Rerun later."
    return 0
  fi
  local rc=0
  set +e
  proot-distro login iiab -- bash -lc '
    set -e
    export DEBIAN_FRONTEND=noninteractive
    apt-get update
    apt-get -y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold \
      install ca-certificates \
              coreutils \
              curl \
              e2fsprogs \
              iputils-ping \
              netcat-traditional \
              sudo
  '
  rc=$?
  set -e
  if [[ $rc -eq 0 ]]; then
    ok "IIAB Debian bootstrap complete."
  else
    warn_red "IIAB Debian bootstrap incomplete (inner apt-get failed, rc=$rc)."
    warn "You can retry later with: iiab-termux --login"
  fi
}


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
