# Release Checklist For Darpan 3.4.17

## Backlog prework

- Included backlog item IDs (`roadmap/BACKLOG.md`): DAR-UI-152 (fix), DAR-BE-373 (part c).
- Open release issues: 0
- Scope: Aditi 2026-11-26 15:18 IST "One release, both changes"; DAR-BE-374 left out (15:22, its design changed).
  darpan `main` after `v3.4.16`: ba8a8f24, 06eec322 only; every other component's `main` equals its pin.

## Release notes

- `release-notes.md`; public notes fold into the 3.4 section of darpan-docs `releases/updates.mdx`.

## Technical changelog

- `technical-changelog.md`.

## Upgrade data

- 0 records: no `data/` or `entity/` change since `v3.4.16`; the 3.4.16 archive equals the `v3.4.16` root.

## Documentation

- `docs-impact.md`; `mint broken-links` and `mint validate` (node 20) on both docs repos; `check_service_catalog.py`.

## Verification

- After the version bump (`--rerun`): ApiContractGeneratorTests, FacadeContractSnapshotTests; `release_preflight.py validate`.
- On the commits: `technical-changelog.md` (Tests). Not run: the full suites (workspace rule: focused tests only).
- `verify_release_pins.py --version 3.4.17` before the docker-config tag; `verify_release_docs.py` offline before the tag,
  live after deploy.
- Roll gate: the cause-review session's "clear" + `darpan-admin --target prod live` empty.

## Approval

- Release owner sign-off: Aditi, 2026-11-26 ("One release, both changes").
