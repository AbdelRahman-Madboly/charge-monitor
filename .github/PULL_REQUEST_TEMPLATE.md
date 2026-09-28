<!-- rename to .github/PULL_REQUEST_TEMPLATE.md -->

## What changed
<!-- Short summary of the change. -->

## Why
<!-- The problem or goal this addresses. -->

## How it was tested
<!-- Commands run, tests added, manual checks. -->

## Data-safety check
- [ ] No real data, dumps, exports, `.env` values, credentials, or client identifiers are included.
- [ ] Secret scan run on any config / exported file.
- [ ] Files were staged deliberately (no blind `git add -A`).

## Checklist
- [ ] Conventional Commit messages, company voice, no tool attribution.
- [ ] CI passes (lint / test / build).
- [ ] Docs updated if behavior changed.
