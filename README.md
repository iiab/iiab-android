# :world_map: Internet-in-a-Box on Android

**[Internet-in-a-Box (IIAB)](https://internet-in-a-box.org) on Android** will allow millions of people worldwide to build their own family libraries, inside their own phones!

As of March 2026, these IIAB Apps are supported:

* **Calibre-Web** (eBooks & videos)
* **Kiwix** (Wikipedias, etc)
* **Kolibri** (lessons & quizzes)
* **Maps** (satellite photos, terrain, buildings)
* **Matomo** (metrics)

The default port for the web server is **8085**, for example:

```
http://localhost:8085/maps
```

## What are the current components of "IIAB on Android"?

* **[termux-setup](https://github.com/iiab/iiab-android/tree/main/termux-setup) (iiab-termux)** — sets up a Debian-like environment on Android (it's called [PRoot](https://wiki.termux.com/wiki/PRoot))
* **Wrapper to install IIAB (iiab-android)** — sets up [`local_vars_android.yml`](https://github.com/iiab/iiab/blob/master/vars/local_vars_android.yml), then launches IIAB's installer
* **Core IIAB portability layer** — modifications across IIAB and its existing roles, based on [PR #4122](https://github.com/iiab/iiab/pull/4122)
* **proot-distro service manager (PDSM)** — like systemd, but for `proot_services`

## Related Docs

* **Android bootstrap (in this repo):** [`termux-setup/README.md`](https://github.com/iiab/iiab-android/blob/main/termux-setup/README.md)
* **proot_services role (in IIAB's main repo):** [`roles/proot_services/README.md`](https://github.com/iiab/iiab/blob/master/roles/proot_services/README.md)

---

## :clipboard: Installation guide

Before installing, you need to set up your Android device. These initial steps apply to all users:

### Part 1: Prerequisites & Device Setup

1. **Install F-Droid & Termux**
   * Download and install **F-Droid** ([https://f-droid.org/F-Droid.apk](https://f-droid.org/F-Droid.apk)). You will need to allow installation from unknown sources from your default web browser.
   * Open F-Droid and wait a moment for the repositories to update automatically in the background.
   * Install **Termux** and **Termux:API**. Most browsers will recognize these direct links after installing F-Droid: 
     * [**Termux**](https://f-droid.org/packages/com.termux)
     * [**Termux:API**](https://f-droid.org/packages/com.termux.api)
     * *(Alternatively, you can just search for them directly inside the F-Droid app).*

   > **Note:** You might see a *"This app was built for an older version of Android..."* label. Ignore this; it only affects auto-updates. Manual updates will continue to work. [You can learn more on the subject here.](https://github.com/termux/termux-packages/wiki/Termux-and-Android-10/3e8102ecd05c4954d67971ff2b508f32900265f7)

2. **Configure Battery Settings (Important)**
   To run the installation or keep IIAB services alive in the background, you must allow Termux to run without battery restrictions. 
   * Go to your Android **Settings -> Apps -> Termux -> Battery**.
   * Set it to **Unrestricted**, **Don't optimize**, or **Allow background activity** (the exact label varies by vendor). If you leave this restricted, Android may kill the process when your screen turns off!

   > **Note:** Because this policy is important for a successful setup, our installation script will prompt you to verify this later. Thank you for paying attention to the manual! 😉

3. **Enable Developer Options & Process Limits**
   * Go to **Settings > About phone** (or About tablet) and tap **Build number** seven times rapidly to enable Developer Options.
   * **For Android 14 and newer:** Go back to **Settings -> System -> Developer options** and turn on `Disable child process restrictions`.
   * **For Android 8 to 11:** No special process restrictions apply. You are good to go!
   * **For Android 12 and 13:** *Please see the special section at the bottom of this guide regarding the "Phantom Process Killer" (PPK) before proceeding, as it might interrupt your installation.*

---

### Part 2: Choose your installation path

There are two main ways to install IIAB on Android. If you are unsure, we recommend the **Pre-built** method.

**Pre-built - Fast & simple**  
This is the recommended path for most users. Instead of compiling the software on your phone, it downloads a ready-to-use, pre-configured IIAB system. It saves a lot of time, minimizes potential errors, and gets you up and running quickly.

**DIY build - From scratch**  
This is the foundational build path with no shortcuts. Your device will download and configure every component one by one. While it takes significantly longer and uses more battery than the pre-built option, it provides complete control for developers or those needing a deeply customized setup.

---

#### Option A: Pre-built

1. Open Termux and run the following command to install the base tools:

    ```bash
    curl iiab.io/termux.txt | bash -s barebones
    ```

2. Download and extract the pre-built IIAB system:

    **For most common modern devices (64-bit):**

    ```bash
    iiab-termux --pull-rootfs https://si-n.cc/latest_arm64-v8a.meta4
    ```

3. Once the process finishes successfully, your installation is complete!  
    In order to start it run: `iiab-termux --login`
4. **Please proceed directly to the [Test your IIAB install](#test-your-iiab-install) section below.**

#### Option B: DIY build

1. Open Termux and prepare the full environment:

    ```bash
    curl iiab.io/termux.txt | bash
    ```

2. Enter PRoot Distro's IIAB Debian environment:

    ```bash
    iiab-termux --login
    ```

3. Run the installer script. This will set up [`local_vars_android.yml`](https://wiki.iiab.io/go/FAQ#What_is_local_vars.yml_and_how_do_I_customize_it?) and launch the core IIAB installer:

    ```bash
    iiab-android
    ```

    *Tip: As with any custom IIAB installation, if the installer fails or gets interrupted, you can always resume from where it left off by running `iiab -f`.*

4. If the installer completes successfully, you'll see a text box reading:

    > INTERNET-IN-A-BOX (IIAB) SOFTWARE INSTALL IS COMPLETE

---

### ⚠️ Special Notes for Android 12 & 13 users

Android 12 and 13 introduced a strict system limitation called the ["Phantom Process Killer" (PPK)](https://github.com/agnostic-apollo/Android-Docs/blob/master/en/docs/apps/processes/phantom-cached-and-empty-processes.md). If left unaddressed, it can aggressively kill background tasks or corrupt your installation midway through (especially during long downloads or heavy extractions).

To fix this safely, we use a built-in ADB workaround. Before choosing your installation path above, please do the following:

1. Run `iiab-termux --all` in Termux.
2. Make sure to opt-in to the ADB Pair/Connect steps when prompted.
3. In your Android **Developer Options**, enable **Wireless debugging**, select **Pair device with pairing code**, and enter the Pair Code back in Termux. 
4. *Need help?* Check this [video tutorial](https://iiab.switnet.org/android/vids/A15_mDNS_hb.mp4) for a visual guide. Once connected to ADB, our script will handle the PPK workaround automatically so your installation runs smoothly!

---

## Test your IIAB install

IIAB [`pdsm` services](https://github.com/iiab/iiab/tree/master/roles/proot_services) start automatically after installation. To check that your IIAB Apps are working (using a browser on your Android device) by visiting these URLs:

| App                    | URL                                                            |
|------------------------|----------------------------------------------------------------|
| Calibre-Web            | [http://localhost:8085/books](http://localhost:8085/books)     |
| Kiwix (for ZIM files!) | [http://localhost:8085/kiwix](http://localhost:8085/kiwix)     |
| Kolibri                | [http://localhost:8085/kolibri](http://localhost:8085/kolibri) |
| IIAB Maps              | [http://localhost:8085/maps](http://localhost:8085/maps)       |
| Matomo                 | [http://localhost:8085/matomo](http://localhost:8085/matomo)   |

If you encounter an error or problem, please open an [issue](https://github.com/iiab/iiab/issues) so we can help you (and others) as quickly as possible.

### Add a ZIM file

A copy of Wikipedia (in almost any language) can now be put on your Android phone or tablet! Here's how...

1. Browse to website: [download.kiwix.org/zim](https://download.kiwix.org/zim/)
2. Pick a `.zim` file (ZIM file) and copy its full URL, for example:

   ``` 
   https://download.kiwix.org/zim/wikipedia/wikipedia_en_100_maxi_2026-01.zim
   ```

3. Open Android's Termux app, and then run:

   ```
   iiab-termux --login
   ```

   EXPLANATION: Starting from Termux's high-level CLI (Command-Line Interface), you've "shelled into" [PRoot Distro](https://wiki.termux.com/wiki/PRoot)'s low-level IIAB Debian CLI:

   ```
          +----------------------------------+
          |   Android GUI (Apps, Settings)   |
          +-----------------+----------------+
                            |
                   open the | Termux app
                            v
              +-------------+------------+
              |   Termux (Android CLI)   |
              | $ iiab-termux --login    |
              +-------------+------------+
                            |
           "shell into" the | low-level environment
                            v
      +---------------------+---------------------+
      |   proot-distro: IIAB Debian (userspace)   |
      | debian root# cd /opt/iiab/iiab            |
      +-------------------------------------------+
   ```

4. Enter the folder where IIAB stores ZIM files:

   ```
   cd /library/zims/content/
   ```

5. Download the ZIM file, using the URL you chose above, for example:

   ```
   wget https://download.kiwix.org/zim/wikipedia/wikipedia_en_100_maxi_2026-01.zim
   ```

6. Once the download is complete, re-index your IIAB's ZIM files: (so the new ZIM file appears for users, on page http://localhost:8085/kiwix)

   ```
   iiab-make-kiwix-lib
   ```

   TIP: Repeat this last step whenever removing or adding new ZIM files from `/library/zims/content/`

## Remote Access

While using the phone keyboard and screen is practical when on the move, accessing the PRoot Distro's IIAB Debian environment from a PC or laptop is very useful for debugging. You can use an existing Wi-Fi connection or enable the native Android hotspot if no wireless LAN is available.

Before you begin, obtain your Android phone or tablet’s IP address by running `ifconfig` in Termux. Or obtain the IP by checking **About device → Status** in Android settings.

### SSH

To log in to IIAB on Android from your computer, follow these SSH command-line interface (CLI) instructions:

1. On your Android phone or tablet, find your way to Termux's CLI. **If you earlier ran `iiab-termux --login` to get to PRoot Distro's low-level IIAB Debian CLI — you MUST step back up to Termux's high-level CLI — e.g. by running:**

   ```
   exit
   ```

2. The fastest way to SSH into your Android phone (or tablet) is to set a password for its Termux user. In Termux's high-level CLI, run:

   ```
   passwd
   ```

   Optionally, security can be improved by using standard SSH key-based authentication via the `~/.ssh/authorized_keys` file.

3. Start the SSH service. In Termux's high-level CLI, run:

   ```
   sshd
   ```

   The `sshd` service can be automated to start when Termux launches (see [Termux-services](https://wiki.Termux.com/wiki/Termux-services)). We recommend doing this only after improving login security using SSH keys.

4. SSH to your Android phone.

   From your laptop or PC, connected to the same network as your Android phone, and knowing the phone’s IP address (for example, `192.168.10.100`), you would run:

   ```
   ssh -p 8022 192.168.10.100
   ```

   A username is NOT needed!

   Note that port **8022** is used for SSH. Since Android runs without root permissions, SSH cannot use lower-numbered ports. (For the same reason, the IIAB web server [nginx] uses port **8085** instead of port 80.)

### Log in to the IIAB environment

Once you have an SSH session on your remote device, log into PRoot Distro to access and run the IIAB applications, just as during installation:

```
iiab-termux --login
```

You will then be in a IIAB Debian shell with access to the IIAB CLI (command-line interface) tools.

## What about 32-bit?

IIAB on Android runs on older 32-bit devices, and we are making progress! [**Maps is now supported**](https://github.com/iiab/iiab/pull/4302).

However, there are still limitations:

* Kiwix: Currently not supported on 32-bit.

While we would love to close [this gap](https://github.com/iiab/iiab-android/issues/35), porting Kiwix to this architecture requires significant development resources. As a result, active development for this feature is currently on hold. Community contributions are welcome if you have the expertise to help us tackle this!

In the meantime, you can try the current state of our pre-built rootfs:

**For older 32-bit devices:**

```bash
iiab-termux --pull-rootfs https://si-n.cc/latest_armeabi-v7a.meta4
```

Alternatively, you can follow the full build-from-scratch steps noted in the [Choose your installation path](#part-2-choose-your-installation-path) section above.

## Removal

If you want to remove the IIAB installation and all associated apps, follow these steps:

1. Remove the IIAB installation and data:

    ```bash
    iiab-termux --remove-rootfs
    ```

    > **Note:** All content in your IIAB installation will be deleted when executing this command.  
    Back up your library content first if you plan to reinstall later!

2. Uninstall both Android apps, **Termux** and **Termux:API**, if you no longer need them.

3. Disable Developer Options in your Android settings, especially if you only enabled it for this installation.

## Advanced Usage (`iiab-termux`)

For power users, debugging, or specific system tuning, `iiab-termux` includes several built-in tools for backup, restore, ADB pairing, and some other neat features.

> **Tip:** Don't forget to run `iiab-termux --update` occasionally to pull the latest version of the script. Keep an eye on our repo history to check for new features or changes.

Below is the output of `iiab-termux --help`:

```text
Usage: iiab-termux [MODE] [OPTIONS]

=== CORE & INSTALL ===
  (no args)       Baseline + IIAB Debian bootstrap
  --all           Full setup: baseline, Debian, ADB, PPK, & checks
  --barebones     Minimal installation: Termux base + proxy (no rootfs)
  --login         Login into IIAB Debian
  --iiab-android  Install/update 'iiab-android' tool inside proot

=== ADB & SYSTEM TUNING ===
  --with-adb      Baseline + Debian + ADB wireless pair/connect
  --adb-only      Only ADB pair/connect (skips Debian)
  --connect-only  Connect to an already-paired device
  --ppk-only      Set max_phantom_processes=256 via ADB
  --check         Check Android readiness (Process restrictions, PPK)

=== BACKUP & RESTORE ===
  --backup-rootfs Backup IIAB Debian to .tar.gz
  --restore-rootfs Restore IIAB Debian from local .tar.gz
  --pull-rootfs   Download & restore rootfs from URL (P2P enabled)
  --remove-rootfs Delete IIAB Debian rootfs and all data

=== PROXY (BOXYPROXY) ===
  --proxy-start   Start background proxy
  --proxy-stop    Stop background proxy
  --proxy-status  Show proxy status

Options:
  --connect-port [P]  Skip CONNECT PORT prompt
  --timeout [SECS]    Wait time per prompt (default 180)
  --no-meta4          Disable Metalink/P2P for --pull-rootfs
  --keep-tarball      Keep the downloaded archive after --pull-rootfs
  --reset-iiab        Reinstall IIAB Debian
  --install-self      Install the current script to Termux bin path
  --welcome           Show the welcome screen
  --debug             Enable extra logs
  --help, --version   Show this help or version

Notes: Setup on Android 12 & 13 requires ADB due to OS design. 14+ simplifies this with system UI toggles
```
