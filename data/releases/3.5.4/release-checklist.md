# Release Checklist For Darpan 3.5.4

## Backlog prework

- Included backlog item IDs (`roadmap/BACKLOG.md`): DAR-BE-382 (on 3.5.3's DAR-BE-379, DAR-BE-320, DAR-UI-152, DAR-BE-374).
- Open release issues: 0
- Scope: Aditi via the coordinator 18:33 (DAR-BE-382 P0, in tonight's roll if ready before CLEAR). darpan `main` after
  `v3.5.3`: 56b4c88f, eab82caf only; components as 3.5.3.

## Release notes

- `release-notes.md`; public notes: the 3.5 section of darpan-docs `releases/updates.mdx`.

## Technical changelog

- `technical-changelog.md`.

## Upgrade data

- 0 records: no `data/`, `entity/` or `service/` change since `v3.5.3`; the 3.5.3 archive equals the `v3.5.3` root. 3.5.3's
  records load from their darpan-seed files on boot.

## Documentation

- `docs-impact.md`; `mint broken-links` and `mint validate` (node 20); `check_service_catalog.py`.

## Verification

- After the bump (`--rerun`): ApiContractGeneratorTests, FacadeContractSnapshotTests, TenantOwnedEntityFilterContractTests,
  DisableAuthzRatchetTest; `release_preflight.py validate`. Not run: the full suites (focused tests only).
- `verify_release_docs.py` (offline) before the tag; `verify_release_pins.py --version 3.5.4` before the docker-config tag.

## Approval

- Release owner sign-off: Aditi via the coordinator (one roll tonight; DAR-BE-382 in if ready before CLEAR).
