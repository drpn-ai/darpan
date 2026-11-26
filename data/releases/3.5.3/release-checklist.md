# Release Checklist For Darpan 3.5.3

## Backlog prework

- Included backlog item IDs (`roadmap/BACKLOG.md`): DAR-UI-152 (fix), DAR-BE-379, DAR-BE-320, DAR-BE-374 (ratchet).
- Open release issues: 0
- Scope: Aditi 18:02 (via the coordinator) and 18:11. darpan `main` after `v3.5.2`: f229364f, 2600e352, 82b059a6 only;
  darpan-hotwax `v1.4.0..` addce6b only; netsuite-darpan `v0.8.0..` 6dbbaff only; other components equal their pins.

## Release notes

- `release-notes.md`; public notes: the 3.5 section of darpan-docs `releases/updates.mdx`.

## Technical changelog

- `technical-changelog.md`.

## Upgrade data

- Generated with `release_preflight.py generate --backend-previous-ref v3.5.2` in a throwaway worktree at `origin/main`
  (82b059a6); the 3.5.2 archive equals the `v3.5.2` root. 5 records.

## Documentation

- `docs-impact.md`; `mint broken-links` and `mint validate` (node 20) on both docs repos; `check_service_catalog.py` 337/337.

## Verification

- `check_contract_compat.py` v3.5.2 -> HEAD compatible (contract 6). After the bump (`--rerun`, component checkouts at
  their release commits): ApiContractGeneratorTests 8, FacadeContractSnapshotTests 1, TenantOwnedEntityFilterContractTests 5,
  DisableAuthzRatchetTest 3; `release_preflight.py validate`.
- Not run: the full suites (workspace rule: focused tests only).
- `verify_release_docs.py` (offline) before the tag; `verify_release_pins.py --version 3.5.3` before the docker-config tag.
- Roll gate: the cause-review "clear" + empty prod live.

## Approval

- Release owner sign-off: Aditi, 2026-11-26 (18:02 one roll; 18:11 DAR-BE-320 in if ready; ratchet re-baseline).
