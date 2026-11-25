# Release Checklist For Darpan 3.4.8

## Backlog prework

- Included backlog item IDs (`roadmap/BACKLOG.md`): DAR-BE-368
- Open release issues: 0
- Scope: Aditi 2026-11-25 18:39 IST "work on next tickets" -> "Finish this thread" -> "Declare as options (Recommended)":
  everything on darpan `main` after `v3.4.7` and darpan-hotwax after `v1.3.3` (only this item and the 3.4.7 pack fix).
- Deferred and named on the backlog: ticking the options on RAILS R5's runs and syncing their automations.

## Release notes

- `release-notes.md`; public notes fold into the 3.4 section of darpan-docs `releases/updates.mdx`.

## Technical changelog

- `technical-changelog.md`.

## Upgrade data

- Generated with `release_preflight.py generate --backend-previous-ref v3.4.7` in the isolated clone at `origin/main`; the
  `data/releases/3.4.7/upgrade-data.xml` archive equals the `v3.4.7` tag's. 1 record (the SHOPIFY connector row), `xmllint`
  clean; the OMS_GQL row loads from darpan-hotwax's `data/HotWaxConnectorSeedData.xml` on every boot.

## Documentation

- Every shipped item has a docs decision in `docs-impact.md`.

## Verification

- Backend, Gradle in the isolated clone, `--rerun`, fresh JUnit XML (counts in the DAR-BE-368 backlog note).
- Not run: the full suites (workspace rule: focused tests only).
- `verify_release_pins.py --version 3.4.8` before the docker-config tag.

## Approval

- Release owner sign-off: Aditi, 2026-11-25 ("Declare as options (Recommended)": "hotfix 3.4.8 ... then tick them on
  RS_100039 to RS_100043 in the run editor and sync the 5 automations").
