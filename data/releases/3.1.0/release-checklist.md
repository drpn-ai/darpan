# Release Checklist For Darpan 3.1.0

## Backlog prework

- Included backlog item IDs (`roadmap/BACKLOG.md`): DAR-BE-028, DAR-BE-197 (gitleaks part), DAR-BE-255, DAR-BE-256 (app part), DAR-BE-257, DAR-BE-258, DAR-BE-260, DAR-BE-261, DAR-BE-262, DAR-BE-263, DAR-BE-264, DAR-BE-265, DAR-BE-266, DAR-BE-267, DAR-BE-268, DAR-BE-269, DAR-BE-270, DAR-BE-271, DAR-BE-272, DAR-BE-273, DAR-BE-275, DAR-BE-276, DAR-BE-279, DAR-BE-280, DAR-BE-281, DAR-BE-282, DAR-BE-284, DAR-BE-286, DAR-BE-287, DAR-BE-289, DAR-BE-290, DAR-BE-291, DAR-BE-292, DAR-BE-293, DAR-BE-294, DAR-ADMIN-011, DAR-OPS-002, DAR-OPS-004, DAR-OPS-018, DAR-OPS-019, DAR-OPS-020, DAR-UI-113, DAR-UI-114, DAR-UI-115, DAR-UI-116, DAR-UI-117, DAR-UI-118
- Open release issues: 0
- Scope changes recorded on the backlog items: twelve items whose code is on `main` moved from active to ready-to-ship on 2026-11-17 (DAR-BE-255, 256, 257, 258, 271, 275, 290, 294, DAR-UI-113, 114, 115, 118); their deploy steps stay in prod-owed.
- Deferred items named and marked on the backlog: DAR-BE-256 backend deliverable field (prod-owed "deferred"), DAR-BE-275 first install and app UI, DAR-BE-266 retention periods, DAR-BE-265 database grants and locked copy, DAR-ADMIN-012 CRM (uncommitted, kept out of the cut).

## Release notes

- User-facing release notes drafted: yes, `release-notes.md`; public notes in darpan-docs `releases/updates.mdx` (764cbc0).
- Operator-visible changes reviewed: yes, including the PBKDF2 and tenant-filter rollback caveats and the `deploy.sh` health-check change.
- Release notes link or path: `release-notes.md`

## Technical changelog

- Technical changelog curated from compare ranges, not copied from raw commit log: yes.
- Compare URLs captured: yes, one per repo.
- Technical changelog link or path: `technical-changelog.md`

## Upgrade data

- Generic source data file updated for every release upgrade record: yes, all 90 records come from `data/*.xml` (`v3.0.3..HEAD`).
- Candidate diff reviewed: yes. Dropped against main's hand-edited file: only the two 3.0.3 `itemQuantity` rows, which shipped in 3.0.3. Added: `NS_AUTH_ACCOUNTS`, the two jobs and their parameter, the two `OMS_RETURNS_GQL` join-key texts. `ServiceJobParameter` moved after its `ServiceJob` (FK order).
- Final load path decided: first start of the new image with `DARPAN_LOAD_UPGRADE_DATA` unset or `Y`; otherwise `loadDarpanUpgradeData` once.
- Current upgrade data file link or path: `runtime/component/darpan/data/upgrade-data.xml`
- Previous upgrade data archived under prior tag folder: yes, `data/releases/3.0.3/upgrade-data.xml`, byte-identical to `v3.0.3`'s.

## Documentation

- Every shipped item has a docs decision in `docs-impact.md`: yes, 33 rows plus the developers-site sections.
- `verify_release_docs.py` exit status (before tagging): every content check passes. Online run with the local backend up (2026-11-17): graphql pages PASS (55 generated pages current). Offline run after filling docs-impact: release notes, graphql log, service catalog (266 of 266), public openapi (3.1.0), runtime stack, docs impact (46 references) PASS. Only "published" fails for both repos, until they are pushed.
- Docs repos pushed (Mintlify publishes from `main`): no, awaiting Aditi: darpan-docs 764cbc0, darpan-developers 36c51fb.
- Post-deploy GraphQL re-check against prod (`--sdl-url https://api.drpn.ai/rest/s1/graphql/sdl`) owed: yes, after the deploy.

## Verification

- Backend checks complete: yes. JDK 21, `--rerun`, fresh result XMLs, 2026-11-17: ApiContractGeneratorTests 8/8, FacadeContractSnapshotTests 1/1, TenantOwnedEntityFilterContractTests 5/5, DisableAuthzRatchetTest 2/2, CliMethodAllowlistTests 6/6, RpcMethodFenceTests 22/22, AuditCatalogContractTests 3/3, AuditLedgerServicesSmokeTests 14/14, AuditFixActionTests 3/3, darpan.migration.* 50/50 (8 classes). CliMethodAllowlistTests first failed 2/6 on `main`: `send#TenantWelcome` (DAR-BE-290) was in no CLI list; fixed by placing it in EXCLUDED.
- Contract: `check_contract_compat.py` 3.0.3 -> 3.1.0 compatible, 23 added, 243 preserved, version 4. `generateApiContract` 266 methods, diff = version line only.
- UI checks complete: the contract copy synced to darpan 3.1.0 (`npm run sync-contract`, `generate-api-types`: adds `send#TenantWelcome` and `sendWelcome`, which only the admin app uses); `npm run type-check` exit 0; vitest `contractDrift` 2/2 and `registryContract` 18/18.
- Deploy pins: `verify_release_pins.py` fails only on the eight tags not yet pushed (seven component tags and docker-config v3.1.0); `DARPAN_REF` matches `component.xml`. Re-run after the tags are pushed.
- Live/deployed smoke coverage noted: release-notes "Operator actions after deploy" lists the read-backs.
- Unverified items called out: the full suites were not run (focused-tests rule); the upgrade file was not loaded into an empty database; two-factor and passkeys in a browser on app.drpn.ai; Shopify webhooks against Shopify; the GraphQL SDL against prod.

## Approval

- Release owner sign-off: pending Aditi.
- Cut/tag blocked until this checklist is fully complete: yes; tags, pushes and GitHub releases wait for Aditi.
