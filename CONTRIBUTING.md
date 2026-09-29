# Contributing

Thanks for contributing to this repository. Follow these rules — they keep the work professional, safe, and
legally clean.

## Before you start
- Read the project rules below and in `AGENTS.md`.

## Data safety (required)
- Never commit real data, database dumps, exports with records, `.env` values, credentials, or client identifiers.
- Stage named files deliberately. Never `git add -A` where data can live.
- Run the secret scan before staging any config or exported file.

## Branching & commits
- Branch off `main`: `feat/...`, `fix/...`, `chore/...`. Prefer git worktrees for parallel work.
- Conventional Commits: `type(scope): summary`. One logical change per commit.
- Company voice only — no third-party tool attribution or co-author trailers.

## Pull requests
- Open a PR into `main`. Describe what/why/how-tested and any data-safety notes.
- CI must pass. At least one review approval (explicit self-review while solo).
- No force-push or direct commits to `main`.

## Releases
- Tag `vX.Y.Z` with a short changelog entry.

## Project rules (Charge Monitor)
- **Data class:** Internal. Never commit real charge exports (CSV), databases pulled from a phone, or keystores.
- **Permissions:** the manifest carries exactly five permissions and no `INTERNET`. Adding one needs a reason in
  the PR; a short, explainable list is what keeps a sideloaded build from being flagged.
- **Generated icon:** `ic_launcher_foreground.xml` and `ic_launcher_monochrome.xml` come from
  `tools/glyphgen/generate.py`. Edit `appicon.txt` and regenerate, never the XML.
- **Commands:** build `./gradlew assembleDebug`; check `./gradlew testDebugUnitTest lintDebug`; install
  `adb install --user 0 -r app/build/outputs/apk/debug/app-debug.apk`; mock charge
  `adb shell setprop debug.chargemonitor.mock 1`.
