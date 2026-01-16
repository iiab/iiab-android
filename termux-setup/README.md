## Where am I?

This README covers the **Android/Termux-side** setup development (before actually installing IIAB).

- :world_map: **Start here to get the overall picture:** [`iiab-android/README.md`](../README.md)
- **IIAB-side services in PRoot-Distro (`proot_services` role):**
  https://github.com/iiab/iiab/blob/master/roles/proot_services/README.md

# termux-setup modules

Welcome to the **termux-setup** modular "suite". These scripts help evolve the Termux setup
process into a **ready-to-install IIAB** state.

For full instructions on how to install IIAB on Android, see the main overview:
[`iiab-android/README.md`](https://github.com/iiab/iiab-android/blob/main/README.md).

We invite you to continue reading to find out more about the usage, components and development for `0_termux-setup.sh` script.

## Usage for `0_termux-setup.sh`

Here is a summary of `0_termux-setup.sh` usage:

```
Usage:
  ./0_termux-setup.sh
    -> Termux baseline + IIAB Debian bootstrap (idempotent). No ADB prompts.

  ./0_termux-setup.sh --with-adb
    -> Termux baseline + IIAB Debian bootstrap + ADB pair/connect if needed (skips if already connected).

  ./0_termux-setup.sh  --adb-only [--connect-port PORT]
    -> Only ADB pair/connect if needed (no IIAB Debian; skips if already connected).
       Tip: --connect-port skips the CONNECT PORT prompt (you’ll still be asked for PAIR PORT + PAIR CODE).

  ./0_termux-setup.sh --connect-only [CONNECT_PORT]
    -> Connect-only (no pairing). Use this after the device was already paired before.

  ./0_termux-setup.sh --ppk-only
    -> Set PPK only: max_phantom_processes=256 (requires ADB already connected).
       Android 14-16 usually achieve this via "Disable child process restrictions" in Developer Options.

  ./0_termux-setup.sh --check
    -> Check readiness: developer options flag (if readable),
       (Android 14+) "Disable child process restrictions" proxy flag, and (Android 12-13) PPK effective value.

  ./0_termux-setup.sh --all
    -> baseline + IIAB Debian + ADB pair/connect if needed + (Android 12-13 only) apply --ppk + run --check.

  Optional:
    --connect-port 41313    (5 digits) Skip CONNECT PORT prompt used with --adb-only
    --timeout 180           Seconds to wait per prompt
    --reset-iiab            Reset (reinstall) IIAB Debian in proot-distro
    --no-log                Disable logging
    --log-file /path/file   Write logs to a specific file
    --debug                 Extra logs

Notes:
- ADB prompts require: `pkg install termux-api` + Termux:API app installed + notification permission.
- Wireless debugging must be enabled on Android 12 & 13
- This script doesn't use adb root.
```



## Development notes

This project is maintained to simplify development an splited into multiple Bash "modules" that
are bundled into a single script:

```
0_termux-setup.sh
```

### Rules

- Modules MUST NOT include a shebang (`#!...`).
- Modules SHOULD NOT run top-level code (prefer functions), except `99_main.sh`.
- Do not add `set -euo pipefail` in modules (the bundle already sets it once).
- Keep module names stable and ordered via `manifest.sh`.

Recommended header for every module:

```
# termux-setup module.
# DO NOT add a shebang or "set -euo pipefail" here.
# Keep only function/variable definitions (no top-level execution).
# See: termux-setup/README.md
```

### Rebuild:

```
cd termux-setup
bash build_bundle.sh
```
