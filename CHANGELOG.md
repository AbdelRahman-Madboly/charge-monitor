# Changelog

## v1.0.0 - 2026-11-01

First finished version. Merges `feat/ui-refresh` and `feat/measured-module-loss` into `main`.

### Added
- Bank screen with the test guide, results against the label, the list of drains, energy flow per
  full bank, the product sheet and a shareable report.
- Measured module loss: a drain runs from a *Recharged* mark to the next *Ran empty* mark, and the
  module efficiency is port energy over the cells' datasheet energy. Drains are graded A, B or C.
- Refill marks take a resting cell voltage and an optional USB meter reading in Wh; an NCR18650B
  voltage table scales a partial drain to a full bank.
- Product details on each bank: advertised capacity, rated output at 5 V, datasheet cell
  capacity, cells, module, maximum output, cutoff and notes.
- Per-reading energy model: what the phone used while plugged in, what left the source's port,
  and what that cost the bank's cells.
- "Delivered to phone" figure that counts use while charging, so a bank is not penalised for a
  screen-on session.
- History grouped by source type, with sort and filters; a charge run stays whole across a short
  blip from another source.
- Redesigned Live, Dashboard and Settings screens, and a System, Light or Dark appearance setting.
- Unit tests for number formatting, CSV export rows and power source energy figures.

### Changed
- Database version 6: product columns on `power_source`, `cellV` and `meterWh` on `bank_event`.
- App version 1.0.0 (version code 3).

## v0.2.0 - 2026-09-29

- Renamed to Bank Tester, with named chargers and the banks under test as presets.
- Laptops recognised by the USB data link; the fuel gauge's charge counter trusted once it moves.
- Phone design capacity read from the power profile, with the AOSP placeholder rejected.

## v0.1.0 - 2026-09-28

- Background charge monitoring, session tracking, alerts and a home-screen widget.
- Live, Dashboard, History, Settings and onboarding screens.
- CSV export through the system share sheet.
