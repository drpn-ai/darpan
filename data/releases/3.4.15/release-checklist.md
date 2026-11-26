# Release Checklist For Darpan 3.4.15

## Backlog prework

- Included backlog item IDs: DAR-BE-373 (code part a), DAR-CLI-008 (editor-only change proposals)
- Open release issues: 0
- Scope: everything on darpan `main` after `v3.4.14` (de186515, e12e8027). The DAR-CLI-008 session asked this cut to carry
  de186515 and holds off its own; design-doc-approval-and-plan has no app release to pair.

## Upgrade data

- Generated with `release_preflight.py generate --backend-previous-ref v3.4.14`; the 3.4.14 archive and root equalled
  `v3.4.14`'s before generating. 0 records.

## Documentation

- Every shipped item has a docs decision in `docs-impact.md`.

## Verification

- Backend (main checkout, fresh XML): `darpan.conclusion.brain.*` 121/121 and RunConclusionStepTests 27/27 on main after
  de186515; AuditChangeApplySmokeTests 58/58 (DAR-CLI-008 session); ApiContractGeneratorTests 8 and
  FacadeContractSnapshotTests 1 after the version bump (`--rerun`).
- Not run: the full suites (workspace rule: focused tests only).
- `verify_release_pins.py --version 3.4.15` before the docker-config tag.

## Approval

- Release owner sign-off: Aditi, 2026-11-26 14:17 IST ("yes": commit DAR-BE-373 and start the release). Deploy is a
  separate go.
