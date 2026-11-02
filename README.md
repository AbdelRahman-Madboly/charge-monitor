# Charge Monitor

Installed as **Bank Tester**. The package id stays `com.vinex.chargemonitor`, so an update keeps
every recorded session.

An Android app that records every charge of the phone in the background, per power source, so
you can see how many full phone charges each power bank really gives, its usable capacity against
the label, and what current, voltage and wattage each charger delivers.

The design and its reasoning are in
[`docs/specs/2026-09-28-charge-monitor-design.md`](docs/specs/2026-09-28-charge-monitor-design.md).

## Status

v1.0.0 plus the unreleased changes in CHANGELOG.md. Built as a personal tool and used daily on one
Samsung phone (Android 16). Live, Dashboard, History, the bank screen and Settings work end to
end; 246 JVM tests and 12 icon generator tests pass. Not yet checked on a device: the home-screen
widget, CSV export, and Samsung's fast-charge flag. Other phones report charge current in
different units and signs; `CurrentNormalizer` handles the common cases, but a phone it has not
seen may need a setting.

| Live | Dashboard | History |
|---|---|---|
| ![Live](docs/screenshots/02-live-charging.png) | ![Dashboard](docs/screenshots/03-dashboard.png) | ![History](docs/screenshots/04-history.png) |

Screenshots are from the emulator with mock data.

## Hardware

The app was built to test PB-3, a home-built 10 x 18650 power bank in a 3D-printed case. Its STL
files, print materials and a render are in [`hardware/pb3-power-bank`](hardware/pb3-power-bank).

| Design | Charging a phone (concept image) |
|---|---|
| ![PB-3 power bank case](hardware/pb3-power-bank/images/pb3-case-b.png) | ![PB-3 charging a phone](hardware/pb3-power-bank/images/concept-charging-phone.jpg) |

## How it measures

- **Full charges** per power bank are the sum of % added across its sessions, divided by 100.
  Partial charges add up: 15 to 80 % plus 5 to 35 % is 0.95.
- **Charge and energy** come from the fuel gauge's charge counter once it has moved 30 mAh, and
  from integrating current for shorter sessions.
- **Delivered to phone** adds what the phone used while charging to what the battery gained, so
  a bank used with the screen on is not penalised. The phone's draw is the median charge current
  with the screen off minus the median with it on (below 80 %), counted over screen-on time; a
  session with no screen-off baseline uses the phone's typical measured draw. Screen-off idle
  use is not counted, so the figure is a lower bound. Banks rank by it by default.
- **Real bank capacity** is the energy delivered between tapping *Recharged* and *Ran empty* on
  the bank's card, divided by a conversion-loss factor (0.85 by default).
- **Laptops** are recognised by the USB data connection and never count toward a power bank. A
  laptop port that fast-charges reports as AC, so plug type alone cannot tell.
- **Phone charges per full bank** is the median, over marked drains, of the % added between a
  *Recharged* and the next *Ran empty*. Before the first drain the card shows what the label
  promises instead: rated Wh × loss factor ÷ the phone battery's Wh.
- **Chargers** can be added by name with their rated watts. A charger card shows peak power into
  the battery against that rating, average power, and charging speed in %/h. It is all measured
  at the battery, so a 30 W charger on a phone that accepts 25 W never reads 30.
- **Comparing banks** uses Wh, not mAh. A mAh label only compares with another rated at the
  same cell voltage.
- **Mains or power bank?** The phone cannot tell, so plugging into AC raises a notification that
  asks. Unconfirmed sessions stay out of bank totals.

## What it does not do

- It does not measure the power bank directly. Every figure is read at the phone's battery, so
  the bank's own losses are estimated from marked drains and a conversion-loss factor.
- It cannot tell mains from a power bank on its own; an AC session needs a tap to confirm.
- It does not sync, upload or back up anything. There is no internet permission; data leaves
  the phone only through CSV export.
- It is not on Google Play. It is built from source and installed with `adb`.

## Preset sources

A new install, and the first launch after an upgrade, adds the sources listed in
`SourcePresets` once: a home-built 1S10P NCR18650B bank and a 30 W USB-C wall charger. Edit that
file to ship your own. A preset whose name is already taken is skipped, and existing rows are never
changed. The Appearance setting picks System, Light or Dark.

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

The icon is the 18650 tester's, `tools/glyphgen/source_icon.png`, used as-is. The generator
renders it per density on a background of its own tile colour, sized so the battery stays inside
the 66 dp circle every launcher mask keeps. `appicon.txt` is a pixel trace of it, used only for
the themed (monochrome) icon. Never edit the generated files by hand:

```bash
uv run --no-project --with pillow python tools/glyphgen/generate.py
uv run --no-project --with pillow --with pytest python tools/glyphgen/test_generate.py
```

`tools/glyphgen/preview.png` shows the result under the common mask shapes.

![Launcher icon under common masks](tools/glyphgen/preview.png)

## License

MIT, see [LICENSE](LICENSE).

## Permissions

Five, all explainable: notifications, foreground service (plus its special-use type), boot
completed, and the battery-optimisation exemption request. No internet access; CSV export goes
through the system share sheet.
