# Release Checklist For Darpan 3.4.10

## Backlog prework

- Included backlog item IDs (`roadmap/BACKLOG.md`): DAR-UI-148
- Open release issues: 0
- Scope: Aditi 2026-11-25 19:29 IST "yes": everything on darpan `main` after `v3.4.9` (only DAR-UI-148) and darpan-ui after
  `v4.4.11` (only DAR-UI-148).
- Deferred and named on the backlog: Rails R5 options on and automations synced (DAR-BE-368), right after deploy.

## Release notes

- `release-notes.md`; public notes fold into the 3.4 section of darpan-docs `releases/updates.mdx`.

## Technical changelog

- `technical-changelog.md`.

## Upgrade data

- Generated with `release_preflight.py generate --backend-previous-ref v3.4.9` in the isolated clone at `origin/main`;
  root and archive both equal `v3.4.9`'s before generating. 0 records.

## Documentation

- Every shipped item has a docs decision in `docs-impact.md`.

## Verification

- Backend (isolated clone, `--rerun`, fresh XML, none zero-byte): RunSideOptionsSmokeTests 11, SavedRunsFacadeSmokeTests
  62, FacadeContractSnapshotTests 1, ApiContractGeneratorTests 8 (re-run after the version bump).
- UI (rebased on `v4.4.11`): ReconciliationRuleSetManagerPage.spec + savedRunEditorRoute.spec + src/__tests__ + src/lib/api
  246/246, `vue-tsc --build --force`, eslint, stylelint.
- Not run: the full suites (workspace rule: focused tests only).
- `verify_release_pins.py --version 3.4.10` before the docker-config tag.

## Approval

- Release owner sign-off: Aditi, 2026-11-25 19:29 IST ("yes").
