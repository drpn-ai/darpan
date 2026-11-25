# Release Checklist For Darpan 3.4.6

## Backlog prework

- Included backlog item IDs (`roadmap/BACKLOG.md`): DAR-BE-015, DAR-BE-355, DAR-BE-356, DAR-BE-357, DAR-BE-358,
  DAR-BE-359, DAR-BE-360, DAR-UI-143
- Open release issues: 0
- Scope: Aditi 2026-11-25 16:33 IST, "cut a hotfix and deploy backend and UI"; 16:36 IST "cut now": everything on darpan
  `main` after `v3.4.5`, darpan-hotwax after `v1.3.1`, shopify-darpan after `v0.11.0`, darpan-ui after `v4.4.6`.
- Deferred and named on the backlog: none. Prod settings after deploy (`audit.unexplained.email.to`, `errors.email.to`,
  `errors.chat.webhookUrl`) stay on DAR-BE-358 / DAR-BE-015's prod-owed lines.

## Release notes

- User-facing release notes: `release-notes.md`; public notes fold into the 3.4 section of darpan-docs
  `releases/updates.mdx` (`## v3.4.6`).
- Operator-visible: upgrade data loads on boot (37 records); new entities by add-missing; the image needs darpan-hotwax
  `v1.3.2` and shopify-darpan `v0.11.1`.

## Technical changelog

- Curated from the compare ranges: `technical-changelog.md`.

## Upgrade data

- Generated with `release_preflight.py generate --backend-previous-ref v3.4.5` in an APFS clone of darpan-backend with
  every component detached at `origin/main` (gotcha 3b). `origin/main`'s `data/upgrade-data.xml` held 3.4.5's pack plus
  hand-restated DAR-BE-015 rows; reset to the `v3.4.5` copy first (the archive `data/releases/3.4.5/upgrade-data.xml`
  is byte-identical to the tag's), then generated.
- The generated set is a superset of the hand rows (it adds DAR-BE-358's `subject.unexplained`, `unexplained.intro`,
  `unexplained.body`, `unexplained.next`). Records kept verbatim from seed; reordered for FKs (EmailServer by id alone,
  templates, copy blocks, ServiceJobs) and the duplicate `EmailServer SYSTEM` row dropped: 37 records, `xmllint` clean.
- Previous upgrade data archived: `data/releases/3.4.5/upgrade-data.xml` (unchanged, equals `v3.4.5`).

## Documentation

- Every shipped item has a docs decision in `docs-impact.md`.
- `verify_release_docs.py`: docs written in parallel with the deploy (as on 3.4.4 / 3.4.5); run against the prod SDL
  after deploy. GraphQL schema unchanged (moqui-gql stays `v1.1.1`).

## Verification

- Backend, Gradle in an isolated clone, `--rerun`, 15 classes / 202 tests, 0 failures, fresh JUnit XML, none zero-byte:
  ApiContractGeneratorTests 8, FacadeContractSnapshotTests 1, AutomationEntityContractTests 8,
  ConclusionEntityContractTests 11, SourceFilterEntityContractTests 4, RunFlowchartSchemaContractTests 3,
  TenantOwnedEntityFilterContractTests 5, ScheduleJobDefaultsSmokeTests 6, AuditCatalogContractTests 3,
  EmailTemplatesRenderTests 27, ErrorDigestServicesSmokeTests 5, ErrorDigestSupportTests 16,
  PaymentStatusRecordFactsTests 8, AuditReportAssemblerSmokeTests 35, SavedRunsFacadeSmokeTests 62.
- UI (darpan-ui `v4.4.7`): auditReport.spec + AuditReportPage.spec 84/84, `vue-tsc --build --force` clean, eslint clean
  on every file changed since `v4.4.6`; browser look on the release stack before the app deploy.
- Not run: the full suites (workspace rule: focused tests only); darpan-hotwax / shopify-darpan suites ran green in
  their DAR-BE-356 / DAR-BE-359 lanes on the released code.
- `openapi.json` version set to 3.4.6 by hand; ApiContractGeneratorTests confirms it. Facade snapshot unchanged.
- `verify_release_pins.py`: run after the docker-config pin move, before its tag.

## Approval

- Release owner sign-off: Aditi, 2026-11-25 16:33 / 16:36 IST ("cut a hotfix and deploy backend and UI", "cut now").
