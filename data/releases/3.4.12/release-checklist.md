# Release Checklist For Darpan 3.4.12

## Backlog prework

- Included backlog item IDs (`roadmap/BACKLOG.md`): DAR-BE-371, DAR-BE-372, DAR-CLI-008 (part 2); app: DAR-UI-149,
  DAR-UI-150, DAR-UI-151 (and the UI side of DAR-BE-371/372).
- Open release issues: 0
- Scope: Aditi 2026-11-26 12:12 IST "Let that session release" (to the DAR-CLI-008 session), then 12:14 IST "yes" to:
  everything on darpan `main` after `v3.4.11` and darpan-ui `main` after `v4.4.13`; backend deploy first, a browser look,
  then the app. The six other components' `main` equal their pins.
- Deferred and named on the backlog: DAR-CLI-008 part 3, DAR-CLI-009, DAR-BE-370.

## Release notes

- `release-notes.md`; public notes fold into the 3.4 section of darpan-docs `releases/updates.mdx`.

## Technical changelog

- `technical-changelog.md`.

## Upgrade data

- 0 records. `git diff v3.4.11..HEAD -- data/` held only this pack; the 3.4.11 archive equals `v3.4.11`'s root, and the
  root file is this pack's.

## Documentation

- Every shipped item has a docs decision in `docs-impact.md`. `mint broken-links` and `mint validate` (node 20) pass on
  darpan-docs and darpan-developers; `check_service_catalog.py` 332/332.

## Verification

- Backend, after the version bump (`--rerun`, fresh XML): ApiContractGeneratorTests 8, FacadeContractSnapshotTests 1.
- Backend, on the shipped commits (this session, 2026-11-26): FindingCardRulesTests 6, AuditReportAssemblerSmokeTests 45,
  RunConclusionSupportTests 39, AuditAutomationSmokeTests 29, RunConclusionStepTests 27, Order/Payment/ShopifyReturn
  record facts 14; DAR-CLI-008 session: AuditChangeApplySmokeTests 57, CliMethodAllowlistTests 8, RpcMethodFenceTests 23.
- UI: auditColumns, auditReport, reconciliation and audit specs (705 / 688 runs), vue-tsc, eslint; `npm run check` before
  the app tag.
- Not run: the full suites (workspace rule: focused tests only). verify_release_docs run `--offline` before the tag (no
  local stack; GraphQL unchanged), then live against https://api.drpn.ai SDL after deploy.
- `verify_release_pins.py --version 3.4.12` before the docker-config tag.
- Browser look of the audit page against the 3.4.12 backend before the app deploy.

## Approval

- Release owner sign-off: Aditi, 2026-11-26 ("Let that session release"; "yes").
