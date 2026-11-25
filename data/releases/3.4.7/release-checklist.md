# Release Checklist For Darpan 3.4.7

## Backlog prework

- Included backlog item IDs (`roadmap/BACKLOG.md`): DAR-BE-362, DAR-BE-363, DAR-BE-366, DAR-BE-368
- Open release issues: 0
- Scope: Aditi 2026-11-25 18:04 IST, "cut a hotfix and deploy backend and UI"; asked whether to ship the other three
  ready-to-ship items on `main` with DAR-BE-368: "Ship all". Everything on darpan `main` after `v3.4.6`, darpan-hotwax
  after `v1.3.2`, shopify-darpan after `v0.11.1`, darpan-ui after `v4.4.8`.
- Deferred and named on the backlog: the RAILS R5 source parameters that turn DAR-BE-368 on (DAR-BE-368 prod-owed).

## Release notes

- `release-notes.md`; public notes fold into the 3.4 section of darpan-docs `releases/updates.mdx`.
- Operator-visible: 1 upgrade record on boot; the image needs darpan-hotwax `v1.3.3` and shopify-darpan `v0.11.2`.

## Technical changelog

- `technical-changelog.md`, curated from the compare ranges.

## Upgrade data

- Generated with `release_preflight.py generate --backend-previous-ref v3.4.6` in an APFS clone of darpan-backend with
  every component detached at `origin/main` (gotcha 3b). `origin/main`'s `data/upgrade-data.xml` and the archive
  `data/releases/3.4.6/upgrade-data.xml` were both byte-identical to `v3.4.6`'s before generating.
- 1 record, verbatim from `data/OpsNoticeSeedData.xml`; its template `DARPAN_ERROR_NOTICE` is on prod since 3.4.6.

## Documentation

- Every shipped item has a docs decision in `docs-impact.md`; docs written alongside the deploy (as on 3.4.4 to 3.4.6);
  `verify_release_docs.py` against the prod SDL after deploy. GraphQL schema unchanged.

## Verification

- Backend, Gradle in an isolated APFS clone at `origin/main` + the release edits, `--rerun`, fresh JUnit XML, none
  zero-byte, 0 failures: darpan 22 classes / 383 tests (ApiContractGeneratorTests 8, FacadeContractSnapshotTests 1,
  AutomationEntityContractTests 8, ConclusionEntityContractTests 11, SourceFilterEntityContractTests 4,
  RunFlowchartSchemaContractTests 3, TenantOwnedEntityFilterContractTests 5, ScheduleJobDefaultsSmokeTests 6,
  AuditCatalogContractTests 3, EmailTemplatesRenderTests 27, AuditReportAssemblerSmokeTests 35,
  PaymentStatusRecordFactsTests 8, OrderLinesRecordFactsTests 3, OrderLinesTests 4, AutomationExecutionSupportTests 131,
  RunNotificationSubscriptionSmokeTests 13, ErrorChatSupportTests 17, AuditDigestSupportTests 8, AuditDigestSmokeTests 9,
  SettingAdminServicesSmokeTests 12, RunObservabilityPipelineIntegrationTest 5, SavedRunsFacadeSmokeTests 62);
  darpan-hotwax 4 / 73 (OmsGqlOrderPageSourceTests 33, OmsGqlQueriesTests 24, KeptFieldsProjectionTieTests 6,
  OmsKeptFieldsProjectionTests 10); shopify-darpan 3 / 55 (ShopifyExtractScriptCharacterizationTests 27,
  ShopifySourceCatalogAndQueryBuilderTests 25, ShopifyOrderLinesNestingTests 3).
- UI (`v4.4.10` = `2f361d9` + the one-grid fix `1f32672` + version; `v4.4.9` was tagged before the browser look and never deployed): auditReport.spec + AuditReportPage.spec, `vue-tsc --build --force`, eslint.
- Not run: the full suites (workspace rule: focused tests only).
- `openapi.json` version set to 3.4.7 by hand; ApiContractGeneratorTests confirms it.
- `verify_release_pins.py --version 3.4.7`: exit 0 (run against the release files: the shared darpan checkout held another
  session's unpushed commits at 3.4.5); docker-config `v3.4.7` ok.
- Browser look before the app deploy: the Lines block placed on prod RAILS F-130316 with the live styles; rows did not
  line up across a long SKU, fixed in `1f32672` (one grid), re-looked, then app `v4.4.10` deployed.

## Approval

- Release owner sign-off: Aditi, 2026-11-25 18:04 IST ("cut a hotfix and deploy backend and UI", "Ship all").
