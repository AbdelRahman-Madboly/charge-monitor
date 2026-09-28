# Charge Monitor

An Android app that records every charge of the phone in the background, per power source, so
you can see how many full phone charges each power bank really gives, its usable capacity against
the label, and what current, voltage and wattage each charger delivers.

The design and its reasoning are in
[`docs/specs/2026-09-28-charge-monitor-design.md`](docs/specs/2026-09-28-charge-monitor-design.md).

Status: prototype, running on a Galaxy A54 (Android 16). Live, Dashboard, History and Settings
work end to end; 133 JVM tests pass. Not yet checked on a device: the home-screen widget, CSV
export, and Samsung's fast-charge flag.

| Live | Dashboard | History |
|---|---|---|
| ![Live](docs/screenshots/02-live-charging.png) | ![Dashboard](docs/screenshots/03-dashboard.png) | ![History](docs/screenshots/04-history.png) |

Screenshots are from the emulator with mock data.

## How it measures

- **Full charges** per power bank are the sum of % added across its sessions, divided by 100.
  Partial charges add up: 15 to 80 % plus 5 to 35 % is 0.95.
- **Charge and energy** come from the fuel gauge's charge counter once it has moved 30 mAh, and
  from integrating current for shorter sessions.
- **Real bank capacity** is the energy delivered between tapping *Recharged* and *Ran empty* on
  the bank's card, divided by a conversion-loss factor (0.85 by default).
- **Laptops** are recognised by the USB data connection and never count toward a power bank. A
  laptop port that fast-charges reports as AC, so plug type alone cannot tell.
- **Mains or power bank?** The phone cannot tell, so plugging into AC raises a notification that
  asks. Unconfirmed sessions stay out of bank totals.

## Build

Needs JDK 17 and the Android SDK (platform 34). Put the SDK path in `local.properties`
(`sdk.dir=/path/to/Android/Sdk`, not committed).

```bash
./gradlew assembleDebug testDebugUnitTest
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

## Install

On Samsung phones with a Secure Folder or work profile, install for the main user only:

```bash
adb install --user 0 -r app/build/outputs/apk/debug/app-debug.apk
```

Open the app once; that starts the monitoring service. After that it restarts itself on boot and
after updates. For reliable background logging, allow notifications and exempt the app from
battery optimisation (on Samsung also add it to "Never sleeping apps").

## Mock mode

For working on the UI without a charger, a synthetic source replays a 15 to 80 % charge in about
a minute, then unplugs for 15 s, and repeats:

```bash
adb shell setprop debug.chargemonitor.mock 1   # on
adb shell setprop debug.chargemonitor.mock 0   # off
```

Debug builds also have a mock toggle in Settings, for devices where the property cannot be read.

## Launcher icon

The icon is pixel art in `tools/glyphgen/appicon.txt`. The drawables are generated, never edited
by hand:

```bash
python3 tools/glyphgen/generate.py
python3 tools/glyphgen/test_generate.py   # needs pytest
```

## Permissions

Five, all explainable: notifications, foreground service (plus its special-use type), boot
completed, and the battery-optimisation exemption request. No internet access; CSV export goes
through the system share sheet.
