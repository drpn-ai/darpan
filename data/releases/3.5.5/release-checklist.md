# Release Checklist For Darpan 3.5.5

## Backlog prework

- Included backlog item IDs (`roadmap/BACKLOG.md`): DAR-BE-383.
- Open release issues: 0
- Scope: Aditi 20:12 via the coordinator ("Fix + deploy 3.5.5 tonight"). darpan `main` after `v3.5.4`: 1f57a936 only.

## Release notes

- `release-notes.md`; public notes: the 3.5 section of darpan-docs `releases/updates.mdx`.

## Technical changelog

- `technical-changelog.md`.

## Upgrade data

- 0 records: no `data/`, `entity/` or `service/` change since `v3.5.4`; the 3.5.4 archive equals the `v3.5.4` root.

## Documentation

- `docs-impact.md`; `mint broken-links` (node 20) on both docs repos.

## Verification

- Reviewed: both `toJsonLines` callers delete the temp file in a `finally`; a failed conversion deletes its own.
- After the bump (`--rerun`): IdIndexerSparkTests, ApiContractGeneratorTests, FacadeContractSnapshotTests,
  TenantOwnedEntityFilterContractTests, DisableAuthzRatchetTest; `release_preflight.py validate`. Not run: the full suites
  (focused tests only).
- `verify_release_docs.py` (offline) before the tag; `verify_release_pins.py --version 3.5.5` before the docker-config tag.

## Approval

- Release owner sign-off: Aditi via the coordinator, 2026-11-26 20:12 IST.
