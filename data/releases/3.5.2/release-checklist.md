# Release Checklist For Darpan 3.5.2

## Backlog prework

- Included backlog item IDs (`roadmap/BACKLOG.md`): DAR-UI-152 (migration fix).
- Open release issues: 0
- Scope: Aditi via the cause-review session, 2026-11-26 17:22 IST: 3.5.2 with e2a6e16f alone (DAR-BE-320 moved out). darpan
  `main` after `v3.5.1`: e2a6e16f only; every other component's `main` equals its pin.

## Release notes

- `release-notes.md`; public notes: the 3.5 section of darpan-docs `releases/updates.mdx`.

## Technical changelog

- `technical-changelog.md`.

## Upgrade data

- 0 records: no `data/`, `entity/` or `service/` change since `v3.5.1`; the 3.5.1 archive equals the `v3.5.1` root.

## Documentation

- `docs-impact.md`; `mint broken-links` and `mint validate` (node 20) on both docs repos; `check_service_catalog.py`.

## Verification

- After the version bump (`--rerun`): ApiContractGeneratorTests, FacadeContractSnapshotTests; `release_preflight.py validate`.
- mysqlTest: AcknowledgementNoteMigrationMoquiMysqlTests 1 (red on the old code with XAER_RMFAIL), AcknowledgementNoteMigrationMysqlTests 2.
- Not run: the full suites (workspace rule: focused tests only).
- `verify_release_docs.py` before the tag (offline), live after deploy; `verify_release_pins.py --version 3.5.2` before the
  docker-config tag. Roll gate: the cause-review "clear" + empty prod live.

## Approval

- Release owner sign-off: Aditi ("do it all"; 3.5.2 with e2a6e16f alone, via the cause-review session).
