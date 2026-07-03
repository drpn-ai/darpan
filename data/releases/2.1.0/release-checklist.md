# Release Checklist For Darpan 2.1.0

## Linear prework

- Release tracking issue: not created; this request is release prep only and classified as `neither` under workspace policy.
- Included issue IDs from candidate history: `DAR-271`; related release-candidate commits also include setup/docs, settings, order-source, and result-viewing work without a dedicated release issue.
- Linear bug audit: checked non-archived `Bug` label issues in project `Darpan` on `2026-05-05`.
- Open release issues: 0
- Scope changes documented in Linear: no release tracking issue was updated.
- Deferred issues moved to the next release: none for tag/release cut; deploy and live smoke remain separate release activities.

## Release notes

- User-facing release notes drafted: yes.
- Operator-visible changes reviewed: yes.
- Release notes link or path: `release-notes.md`

## Technical changelog

- Technical changelog curated from compare ranges, not copied from raw commit log: yes.
- Compare URLs captured: yes.
- Technical changelog link or path: `technical-changelog.md`

## Upgrade data

- Generic source data file updated for every release upgrade record: yes; upgrade records come from `data/SecuritySeedData.xml`.
- Candidate diff reviewed: yes; records are limited to `UgtDarpanPermission`, `DARPAN_ADMIN`, and `DARPAN_SUPER_ADMIN`.
- Final load path decided: `./gradlew -b runtime/component/darpan/build.gradle loadDarpanUpgradeData`.
- Current upgrade data file link or path: `runtime/component/darpan/data/upgrade-data.xml`
- Release mirror path: `runtime/component/darpan/data/releases/2.1.0/upgrade-data.xml`
- Previous upgrade data archived under prior tag folder: `runtime/component/darpan/data/releases/2.0.3/upgrade-data.xml`

## Verification

- XML well-formedness checked: yes. `xmllint --noout component.xml data/SecuritySeedData.xml data/upgrade-data.xml data/releases/2.0.3/upgrade-data.xml data/releases/2.1.0/upgrade-data.xml entity/ReconciliationEntities.xml service/facade/AuthFacadeServices.xml service/facade/JsonSchemaFacadeServices.xml service/facade/ReconciliationFacadeServices.xml service/facade/SettingsFacadeServices.xml service/jsonschema/JsonSchemaServices.xml service/reconciliation/ReconciliationGenericServices.xml service/reconciliation/ReconciliationJsonServices.xml service/reconciliation/ReconciliationMixedServices.xml service/reconciliation/ReconciliationRuleEngineServices.xml` passed.
- Backend checks complete: targeted. `./gradlew --no-daemon :runtime:component:darpan:test --tests darpan.reconciliation.automation.AutomationEntityContractTests --tests darpan.facade.reconciliation.ShopifyCreatedAtWindowPaginatorTests --tests darpan.facade.reconciliation.ReconciliationApiWindowSupportTests --tests darpan.facade.reconciliation.ReconciliationOutputSupportTests --tests reconciliation.rule.RuleEngineSupportTests` passed, and `./gradlew --no-daemon :runtime:component:darpan:test --tests darpan.facade.reconciliation.SavedRunsFacadeSmokeTests` passed. Production Docker refs were aligned to the release tags before the final cut.
- Backend full-suite note: `./gradlew --no-daemon :runtime:component:darpan:test :runtime:component:shopify-darpan:test :runtime:component:darpan-hotwax:test` was attempted first and exposed existing full-suite Moqui smoke-test order isolation plus release-data test assumptions after `data/upgrade-data.xml` moved to the 2.1 scoped payload; the release-data assertions were aligned to the archived `2.0.3` upgrade payload before targeted reruns.
- UI checks complete: yes. `npm run check` passed with 64 test files and 455 tests.
- Integration component checks complete: partial. `./gradlew --no-daemon :runtime:component:shopify-darpan:test :runtime:component:darpan-hotwax:test` passed. `netsuite-darpan` is not registered as a Gradle subproject in this checkout; XML well-formedness passed for `component.xml`, entity, screen, facade, and inventory service XML.
- Public docs checks complete: yes. `mint validate` and `mint broken-links` passed when run with the bundled Node runtime first on `PATH`.
- Release-pack validation complete: yes. `release_preflight.py validate --version 2.1.0 --backend-repo /Users/aditipatel/sandbox/darpan-master/darpan-backend/runtime/component/darpan --backend-previous-ref v2.0.3 --backend-current-ref HEAD --ui-repo /Users/aditipatel/sandbox/darpan-master/darpan-ui --ui-previous-ref v2.0.0 --ui-current-ref HEAD` passed.
- Live/deployed smoke coverage noted: not run in this tag-only pass.
- Unverified items called out: yes; deploys, production image builds, and live smoke remain deferred.

## Approval

- Release owner sign-off: local prep and push to `main` requested on `2026-05-05`; tag/release cut requested on `2026-05-05`.
- Cut/tag blocked until this checklist is fully complete: no.
- Blocking reasons: none after final release validation passes.
