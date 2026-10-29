# Prompt for an AI coding agent

Paste this into Claude Code, Codex or another coding agent that can run commands, with the TV or box on the same
network and debugging turned on (see "Before you start" in the README). It works on Android TV and Google TV
TVs and boxes. The agent looks at your TV first and asks before it changes anything.

```text
Install the Sukoon launcher (github.com/MultiX0/sukoon-tv) on my Android TV and clean it up. Work only through
adb, never root, never uninstall a system app, and ask me before every change to the TV.

0. Setup: install adb on this computer if it is missing (Windows: winget install Google.PlatformTools,
   macOS: brew install android-platform-tools, Linux: the adb package). Connect with
   `adb connect <the TV's IP>:5555` (or `adb pair` then `adb connect` when the TV uses Wireless debugging).
   Tell me to accept "Allow debugging?" on the TV, then check `adb devices` shows it as "device".

1. Look, change nothing: brand and model (ro.product.brand, ro.product.model), Android version, whether it is a
   TV with a tuner and HDMI inputs or a box, RAM and MemAvailable, the number of running processes, the home
   screen now in use, every app that answers the HOME intent (`cmd package query-activities --brief
   -a android.intent.action.MAIN -c android.intent.category.HOME`), the 15 largest processes (dumpsys meminfo)
   and the full package list.

2. Save a backup in a folder named after the serial: enabled and disabled packages, the current home activity,
   the default keyboard and the three animation scales.

3. Sort the packages into three lists and show them to me with one reason per package, then wait for my answer:
   - KEEP, never touch: system, settings, Play Store and Google Play services, the keyboard in use, the remote and
     Bluetooth services, network, and on a smart TV everything for inputs and HDMI switching, the tuner and live
     TV, CEC, picture and sound settings, the input picker, and vendor services the remote's buttons use (for
     example TCL's com.tcl.suspension, which runs the Inputs button).
   - USEFUL, keep unless I say otherwise: YouTube, casting (Chromecast built-in), the file manager, the video
     player, screen mirroring.
   - SAFE TO TURN OFF: the stock launchers and their helpers (on Google TV also the setup wraith that brings the
     launcher back), recommendation and ad rows, ambient mode, voice search if I don't use it, vendor telemetry,
     factory test apps, vendor OTA updaters, phone-only sync adapters and printing.

4. After I say yes:
   a. Download https://github.com/MultiX0/sukoon-tv/releases/latest/download/sukoon-launcher.apk and
      `adb install -r` it.
   b. `cmd package set-home-activity dev.multix.tvhome/.HomeActivity`, and allow its notification listener
      (`cmd notification allow_listener dev.multix.tvhome/dev.multix.tvhome.media.NowPlayingListener`).
   c. Grant: pm grant DUMP, WRITE_SECURE_SETTINGS, RECORD_AUDIO, READ_EXTERNAL_STORAGE; appops set
      GET_USAGE_STATS, REQUEST_INSTALL_PACKAGES and SYSTEM_ALERT_WINDOW to allow (all for dev.multix.tvhome).
   d. If a TV keyboard (com.google.android.leanback.ime) exists and I agreed to turn the phone keyboard off,
      switch to it first with `ime set`.
   e. Turn off the agreed list with `pm disable-user --user 0`, at most 10 packages at a time, the stock
      launchers last. After the launchers, check that the HOME intent now resolves to dev.multix.tvhome. If it
      does not, turn the launchers back on and tell me.
   f. Set the window, transition and animator scales to 0.5.

5. Reboot, wait two minutes, and show me before and after side by side: MemAvailable, used RAM, process count and
   the home screen's memory (PSS).

6. Write restore.sh (and restore.ps1 on Windows) next to the backup that turns every package back on, sets the
   old home screen and keyboard back, revokes the grants and resets the animation scales. Tell me where it is.
```

To undo everything at any time, run the restore script it wrote, or `scripts/restore.sh` from this repository.
