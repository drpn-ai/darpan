# Release Checklist For Darpan 3.4.14

## Backlog prework

- Included backlog item IDs: DAR-CLI-008 (part 3 live-check fix)
- Open release issues: 0
- Scope: everything on darpan `main` after `v3.4.13` (a60b5604 only). darpan-master-4e confirmed nothing pending (13:31 IST).

## Upgrade data

- Generated with `release_preflight.py generate --backend-previous-ref v3.4.13` in the isolated clone at `origin/main`;
  the 3.4.13 archive equals `v3.4.13`'s root before generating. 0 records.

## Documentation

- Every shipped item has a docs decision in `docs-impact.md`.

## Verification

- Backend (isolated clone, fresh XML): LookupFacadeSmokeTests 9, OrderLookupTests 20, TenantOwnedEntityFilterContractTests 5,
  FacadeContractSnapshotTests and ApiContractGeneratorTests after the version bump.
- Not run: the full suites (workspace rule: focused tests only).
- `verify_release_pins.py --version 3.4.14` before the docker-config tag.

## Approval

- Release owner sign-off: Aditi, 2026-11-26 12:49 IST ("go ahead").
