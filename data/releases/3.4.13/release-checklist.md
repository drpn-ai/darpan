# Release Checklist For Darpan 3.4.13

## Backlog prework

- Included backlog item IDs (`roadmap/BACKLOG.md`): DAR-BE-372 (fix), DAR-BE-375, DAR-CLI-008 (part 3).
- Open release issues: 0
- Scope: Aditi 2026-11-26 13:00 IST, "All of main" for the 3.4.13 hotfix and "After the hotfix + R13 re-check" for app
  4.4.14. The DAR-CLI-008 session confirmed part 3 complete at 96cdc906 and tagged darpan-hotwax v1.3.5, shopify-darpan
  v0.11.3 and netsuite-darpan v0.7.2 (each `main` equals its tag, checked).
- Deferred and named on the backlog: DAR-CLI-009, DAR-BE-370.

## Release notes

- `release-notes.md`; public notes fold into the 3.4 section of darpan-docs `releases/updates.mdx`.

## Technical changelog

- `technical-changelog.md`.

## Upgrade data

- Generated with `release_preflight.py generate --backend-previous-ref v3.4.12` in a throwaway worktree at `origin/main`
  (96cdc906); the 3.4.12 archive equals the `v3.4.12` root. 2 records.

## Documentation

- Every shipped item has a docs decision in `docs-impact.md`; `mint broken-links` and `mint validate` (node 20) on both
  docs repos; `check_service_catalog.py` 338/338.

## Verification

- After the version bump (`--rerun`): ApiContractGeneratorTests, FacadeContractSnapshotTests,
  TenantOwnedEntityFilterContractTests (with `PENDING_NEXT_PACK` emptied); `release_preflight.py validate`.
- On the shipped commits: see `technical-changelog.md` (Tests).
- Not run: the full suites (workspace rule: focused tests only). verify_release_docs `--offline` before the tag, live
  against https://api.drpn.ai SDL after deploy.
- `verify_release_pins.py --version 3.4.13` before the docker-config tag.
- After deploy: re-check Gorjana R13 and see F-130294 show HotWax's status before app 4.4.14 deploys.

## Approval

- Release owner sign-off: Aditi, 2026-11-26 ("All of main"; app "After the hotfix + R13 re-check").
