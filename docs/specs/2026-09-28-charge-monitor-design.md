# Charge Monitor — design

Date: 2026-09-28. Status: approved in conversation, build starting.

## 1. Purpose

An Android app that records every charge of the phone, in the background, at negligible battery
cost, so the owner can answer:

- **How many full phone charges does each power bank give?** (primary question)
- What is each power bank's real usable capacity compared with its label?
- What current, voltage and wattage does each source actually deliver?

Charges are often partial (15→80 %, 5→35 %), the phone may be in use while charging, or left until
full. Every one of those must accumulate correctly per source.

Target device: Samsung Galaxy (Android 10+). iOS is out of scope — iOS exposes no charge current.

## 2. What the phone can and cannot measure

The fuel gauge measures current **into the battery**, not current leaving the source.

- **Σ(Δ%) / 100 = full-charge equivalents** is exact. This is the headline number.
- **Energy into the battery (Wh)** is measured: ∫ V·I dt.
- **Power bank real capacity** is an *estimate*:
  `bank_mAh_at_nominal ≈ Wh_into_battery / loss_factor / bank_nominal_V × 1000`,
  `loss_factor` default 0.85 (bank boost + phone charger losses), user-adjustable.
- Phone use while charging is not visible to the gauge, so it lowers the estimate. Such sessions
  are flagged `usedWhileCharging` (screen on > 20 % of the session) and the dashboard can include
  or exclude them. The default is to exclude them from the capacity estimate but include them in
  full-charge totals.

The UI states this plainly in an info sheet; numbers derived from estimates are labelled "est."

## 3. Features

### Measurement (all live)
- Voltage (V), current (A), power (W = V·I), temperature (°C), level with one decimal
  (from charge counter ÷ full capacity when available, else integer %), plug type, charging status,
  fast-charge indicator (≥ 10 W sustained, or Samsung's fast-charge status when readable).
- Estimated time to full / to target level, from a smoothed current and Samsung's taper above 80 %.

### Sources
- **Power banks**: user-defined, any number. Fields: name, rated capacity (mAh), nominal voltage
  (default 3.7 V), colour, archived flag.
- **Charger** and **Laptop (USB)**: built-in, cannot be deleted.
- Every session has exactly one source. Totals never mix sources.

### Automatic source selection
- Plug type `USB` → source = Laptop, no prompt. Laptop sessions are logged but **never counted
  toward any power bank**. Their notification carries "It's a power bank" to reassign (cheap banks
  that enumerate as USB).
- Plug type `AC` (chargers and nearly all power banks) → heads-up notification "Connected to?"
  with up to three action buttons: the last two power banks used + Charger, plus a tap-to-open
  full picker. If ignored for 60 s, the last used non-laptop source is kept. The source can be
  changed later from the session screen.
- Wireless → built-in "Wireless" source (excluded from bank totals).

### Sessions
A session runs from plug-in to unplug. Stored: source, start/end time, start/end level (decimal),
mAh in (current integral, cross-checked with charge-counter delta when available), Wh in, peak W,
average W, max temperature, reachedFull (status FULL seen), usedWhileCharging, screenOnFraction,
sample count. Unplug-replug within 30 s continues the same session (loose connector).
Process death: the service resumes the open session on restart.

### Samples
Persisted every 10 s while charging in background, 2 s while the Live screen is visible (live
samples above 10 s cadence are kept in memory only). Fields: t, mV, mA, level×10, temp×10,
screenOn. About 40 bytes/row; a year of daily charging is well under 20 MB.

### Alerts (each toggleable)
- Target level reached (default 80 %).
- Temperature above threshold (default 42 °C).
- Charging stopped / full.

### Battery health (bonus)
Estimated phone battery capacity = mAh_in / (Δlevel/100) over sessions with Δlevel ≥ 30 % and
not used while charging; median of recent qualifying sessions vs design capacity (user-entered,
default read from `PowerProfile` when accessible).

### Home-screen widget
Classic `AppWidgetProvider` + RemoteViews (no Glance dependency). Shows W, %, source, and
bank full-charges total. Updated by the service at most every 30 s while charging, never while
unplugged.

## 4. Screens

Bottom navigation, four tabs. Light and dark theme follow the system; accent is the icon green
(#5FBF3F). Dense instrument style, large numerals for the primary readout.

1. **Live** — big power readout (W) with V / A / °C / level tiles, source chip (tap to change),
   fast-charge badge, time-to-full, live chart (W and A over the current session). Idle state when
   unplugged shows last session summary.
2. **Dashboard** — source filter (All / each bank / Charger / Laptop). Per bank: full charges
   delivered (Σ%/100), total Wh, estimated real capacity vs rated (bar + %), sessions count,
   average W; charts: % added per session (bars), cumulative full charges over time (line),
   average power per session. Toggle "include sessions used while charging".
3. **History** — session list grouped by day (source colour dot, start→end %, duration, mAh, Wh,
   flags). Session detail: chart of W / A / V / level over time, stats, change source, delete.
   Export all sessions + samples as CSV via the system share sheet (no network permission).
4. **Settings** — manage power banks (add/edit/archive), loss factor, phone design capacity,
   alert toggles and thresholds, sample interval, battery-optimisation status + fix button,
   current-unit calibration override, mock mode toggle (debug builds), info sheet on accuracy.

First run: short onboarding — notification permission, battery-optimisation exemption, add first
power bank.

## 5. Architecture

Kotlin, Jetpack Compose, Material 3, Room, coroutines. minSdk 29, target/compile 34. Same
toolchain versions as the bench-station app (AGP 8.5.0, Kotlin 1.9.24, KSP, Compose BOM
2024.06.00, Room 2.6.1) so cached dependencies are reused. Charts are drawn with Compose Canvas —
no chart library.

Package `com.vinex.chargemonitor`:

| Unit | Responsibility |
|---|---|
| `battery/BatteryReader` | Reads a `BatterySnapshot` (mV, µA-normalised current, level, charge counter, temp, plug, status) from `BatteryManager` + sticky `ACTION_BATTERY_CHANGED`. |
| `battery/CurrentNormalizer` | Pure. Detects unit (µA vs mA) and sign convention from observed values + charging status; persists the verdict. |
| `session/SessionAccumulator` | Pure. Folds snapshots into running session totals (trapezoidal mAh/Wh, peaks, screen-on fraction, reconnect grace). |
| `session/SessionTracker` | Glue: opens/closes sessions in Room, applies source rules, writes samples. |
| `source/SourceResolver` | Pure. Plug type + last-used → source decision / whether to prompt. |
| `stats/StatsCalculator` | Pure. Full-charge equivalents, bank capacity estimate, health estimate, time-to-full. |
| `service/ChargeMonitorService` | Foreground service (`specialUse`), dynamic receivers for power connect/disconnect, battery changed, screen on/off; sampling loop only while plugged. |
| `service/BootReceiver` | `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` → start service. |
| `notify/Notifications` | Channels: status (min importance), source prompt (high), alerts (default). Action receiver for source buttons. |
| `data/*` | Room entities `PowerSource`, `ChargeSession`, `Sample`; DAOs; repository. |
| `ui/*` | Compose screens + `LiveViewModel`, `DashboardViewModel`, `HistoryViewModel`, `SettingsViewModel`; `ui/chart/*` Canvas charts. |
| `widget/ChargeWidgetProvider` | RemoteViews widget. |
| `mock/MockBatterySource` | Debug: replays a synthetic 15→80 % charge in ~60 s; enabled by `adb shell setprop debug.chargemonitor.mock 1`. |

`BatteryReader` sits behind a `BatterySource` interface so the mock and tests substitute it.

## 6. Battery cost and background survival

- One persistent foreground service with a single min-importance notification ("Monitoring
  charging"; when charging it shows live W and %).
- Unplugged: no timers, no wakelocks — only broadcast receivers. Plugged: a coroutine loop at
  10 s (2 s when Live is visible). No wakelock is needed: the device does not doze while charging.
- Service type `specialUse` with the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property; allowed to start
  from `BOOT_COMPLETED` on Android 14/15.
- `START_STICKY`; onboarding requests `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and links Samsung's
  "Never sleeping apps" setting.

## 7. Permissions (and why this avoids malware heuristics)

Only: `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`,
`RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.
No `INTERNET`, no accessibility, no overlay, no usage-stats, no storage permission (export uses
the share sheet / SAF). Few, explainable permissions and no network are what keep Play Protect
quiet for a sideloaded app.

## 8. Icon

Reuse the 18650-tester pixel battery: copy `tools/glyphgen` (generator + `appicon.txt`) from the
bench-station project into `tools/glyphgen/`, generate `ic_launcher_foreground.xml` and
monochrome, light grey background (#D8DCDF) like the tester's PNG. Generated files are not edited
by hand.

## 9. Testing

- JVM unit tests for every pure unit: `CurrentNormalizer` (µA/mA/sign cases incl. Samsung),
  `SessionAccumulator` (integration accuracy vs analytic ramp, reconnect grace, screen fraction),
  `SourceResolver`, `StatsCalculator` (full-charge sum across partial sessions, capacity estimate,
  exclusion of used-while-charging, laptop exclusion).
- Mock mode for UI verification without a charger.
- On-device: install via `adb install --user 0` on the Galaxy, run a real charge, compare mAh in
  against charge-counter delta.

## 10. Out of scope

AOD / charging animations (battery cost), cloud sync, iOS, discharge analytics beyond the health
estimate.
