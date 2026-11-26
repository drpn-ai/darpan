# Release Checklist For Darpan 3.4.11

## Backlog prework

- Included backlog item IDs (`roadmap/BACKLOG.md`): DAR-CLI-008 (part 1)
- Open release issues: 0
- Scope: Aditi 2026-11-26 10:55 IST "deploy and release the cli", then "Release 3.4.11 (Recommended)": everything on
  darpan `main` after `v3.4.10` (only DAR-CLI-008 part 1). The six other components' `main` equal their 3.4.10 pins.
- Deferred and named on the backlog: DAR-CLI-008 parts 2 and 3, DAR-CLI-009.

## Release notes

- `release-notes.md`; public notes fold into the 3.4 section of darpan-docs `releases/updates.mdx`.

## Technical changelog

- `technical-changelog.md`.

## Upgrade data

- Generated with `release_preflight.py generate --backend-previous-ref v3.4.10` in the isolated clone at `origin/main`;
  the 3.4.10 archive equals `v3.4.10`'s root before generating. 0 records.

## Documentation

- Every shipped item has a docs decision in `docs-impact.md`.

## Verification

- Backend (isolated clone, fresh XML, none zero-byte): CliMethodAllowlistTests 8, RpcMethodFenceTests 23,
  SourceEndpointAccessSupportTests 12, CliParityViewRefusalSmokeTests 1 (20 writes), DisableAuthzRatchetTest 3,
  AutomationEndpointGateTests 6, FacadeContractSnapshotTests and ApiContractGeneratorTests (re-run after the version bump).
- CLI: node --test 113/113, sh test-darpan.sh 24/24 (wrapper and the public clone).
- Not run: the full suites (workspace rule: focused tests only).
- `verify_release_pins.py --version 3.4.11` before the docker-config tag.

## Approval

- Release owner sign-off: Aditi, 2026-11-26 ("deploy and release the cli"; "Release 3.4.11").
