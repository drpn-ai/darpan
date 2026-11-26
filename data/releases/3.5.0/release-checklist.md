# Release Checklist For Darpan 3.5.0

## Backlog prework

- Included backlog item IDs (`roadmap/BACKLOG.md`): DAR-BE-374, DAR-UI-152 (fix), DAR-BE-373 (part c).
- Open release issues: 0
- Scope: Aditi 2026-11-26 15:50 IST "One roll: re-cut with the ID index" (contract 6, minor). darpan `main` after
  `v3.4.16`: the 3.4.17 commits, 927b3417 (MySQL test), e38ccb16 (DAR-BE-374). System repos: one DAR-BE-374 commit each,
  tagged here as darpan-hotwax v1.4.0, shopify-darpan v0.12.0, netsuite-darpan v0.8.0.

## Release notes

- `release-notes.md`; public notes: a v3.5.0 section in darpan-docs `releases/updates.mdx`.

## Technical changelog

- `technical-changelog.md`.

## Upgrade data

- Generated with `release_preflight.py generate --backend-previous-ref v3.4.17` in a throwaway worktree at `origin/main`
  (e38ccb16); the 3.4.17 archive equals the `v3.4.17` root. 4 records.

## Documentation

- `docs-impact.md`; `mint broken-links` and `mint validate` (node 20) on both docs repos; `check_service_catalog.py`.

## Verification

- `check_contract_compat.py` v3.4.16 -> HEAD passes with contract 6. After the bump (`--rerun`): ApiContractGeneratorTests,
  FacadeContractSnapshotTests, TenantOwnedEntityFilterContractTests; `release_preflight.py validate`.
- Not run: the full suites (workspace rule: focused tests only).
- `verify_release_pins.py --version 3.5.0` before the docker-config tag; `verify_release_docs.py` offline before the tag,
  live after deploy. Roll gate: the cause-review "clear" + empty prod live.

## Approval

- Release owner sign-off: Aditi, 2026-11-26 ("One roll: re-cut with the ID index").
