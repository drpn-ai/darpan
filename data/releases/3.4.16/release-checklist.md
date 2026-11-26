# Release Checklist For Darpan 3.4.16

## Backlog prework

- Included backlog item IDs (`roadmap/BACKLOG.md`): DAR-UI-152.
- Open release issues: 0
- Scope: Aditi 2026-11-26 15:05 IST "Yes, all four steps". darpan `main` after `v3.4.15` held only ce175d4a; darpan-ui
  `main` after `v4.4.14` only 731d561; every other component's `main` equals its pin (checked).

## Release notes

- `release-notes.md`; public notes fold into the 3.4 section of darpan-docs `releases/updates.mdx`.

## Technical changelog

- `technical-changelog.md`.

## Upgrade data

- Generated with `release_preflight.py generate --backend-previous-ref v3.4.15` in a throwaway worktree at `origin/main`
  (ce175d4a); the 3.4.15 archive equals the `v3.4.15` root. 1 record.

## Documentation

- `docs-impact.md`; `mint broken-links` and `mint validate` (node 20) on both docs repos; `check_service_catalog.py` 338/338.

## Verification

- After the version bump (`--rerun`): ApiContractGeneratorTests, FacadeContractSnapshotTests; `release_preflight.py validate`.
- On ce175d4a: see `technical-changelog.md` (Tests). UI on 731d561: audit and reconciliation specs 689/689, vue-tsc, eslint.
- Not run: the full suites (workspace rule: focused tests only).
- `verify_release_pins.py --version 3.4.16` before the docker-config tag; `verify_release_docs.py` offline before the
  tag, live after deploy.
- After deploy: migration dry run, then run; browser look; then app v4.4.15.

## Approval

- Release owner sign-off: Aditi, 2026-11-26 ("Yes, all four steps").
