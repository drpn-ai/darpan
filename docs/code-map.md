# Code Map

This wiki lives in `runtime/component/darpan/docs/Home.md`. The Darpan component code is in `runtime/component/darpan`.

## Production Entrypoints

- Component descriptor: `runtime/component/darpan/component.xml`
- Main app screen (legacy backend screens, super-admin only): `runtime/component/darpan/screen/darpan.xml`
- Reconciliation route (legacy): `runtime/component/darpan/screen/Reconciliation.xml`
- Webroot before-request hook (JSON-RPC namespace fence): `runtime/component/darpan/MoquiConf.xml`
- Remote API contract (generated): `runtime/component/darpan/docs/api-contract/methods.txt`, `runtime/component/darpan/docs/api-contract/openapi.json`

### Service contracts (`service/`)

- Reconciliation compare: `service/reconciliation/ReconciliationCoreServices.xml` (Spark base
  compare, RuleSet compare-scope pipeline, mapping bridge), `ReconciliationGenericServices.xml`
  (generic file compare + generated-output delete/purge), `ReconciliationJsonServices.xml`
- Rule engine: `service/reconciliation/ReconciliationRuleEngineServices.xml` (compile/execute
  RuleSets, rule CRUD, rule cache) — contracts documented in
  `docs/reconciliation/rule-engine-services.md`
- Lifecycle / flowchart runs: `service/reconciliation/ReconciliationFlowchartServices.xml`
  (`run#Reconciliation`, `run#FlowchartQuestion`)
- Automation: `service/reconciliation/ReconciliationAutomationServices.xml`
  (`poll#SftpAndReconcile`, `run#SftpFileAutomation`, `execute#Automation`,
  `scan#DueAutomations`, `sweep#StuckReconciliationRuns`)
- Run-completion notifications: `service/reconciliation/ReconciliationNotificationServices.xml`
  (`build#RunCompletedPayload` for the chat payload; `migrate#TenantNotificationSettings`
  is the one-time v1.2.0 upgrade service, internal only — see Shared Resources below)
- JSON schema: `service/jsonschema/JsonSchemaServices.xml`
- Data migrations: `service/migration/MigrationServices.xml` (the one-time migration
  runner and ledger behind `darpan.migration.DarpanMigration*`)
- UI facade (`facade.*`, JSON-RPC surface for `darpan-ui`): `service/facade/AuthFacadeServices.xml`,
  `SettingsFacadeServices.xml`, `ReconciliationFacadeServices.xml`,
  `JsonSchemaFacadeServices.xml`, `SearchFacadeServices.xml`, `ConfigSharingFacadeServices.xml`
  (share source configs across tenants), `SlackFacadeServices.xml` (Slack install and channels),
  `SourceEndpointFacadeServices.xml` (which endpoints a source config may use) — behaviour notes in
  `docs/reconciliation/platform/facade-behaviour-notes.md`, method list in `docs/api-contract/methods.txt`
- Partner facades under the same `facade.` namespace, in their own components:
  `darpan-hotwax/service/facade/HotWaxOmsFacadeServices.xml`,
  `shopify-darpan/service/facade/ShopifyFacadeServices.xml`,
  `netsuite-darpan/service/facade/NetSuiteQueryFacadeServices.xml` and
  `NetSuiteLifecycleFacadeServices.xml`, `database-darpan/service/facade/DatabaseFacadeServices.xml`
- Operator surface (`admin.*`, super-admin only, used by `darpan-admin-ui`): `service/admin/`
  (`TenantAdminServices.xml`, `UserAdminServices.xml`, `MembershipAdminServices.xml`,
  `MigrationAdminServices.xml`, `ScheduleAdminServices.xml`, `ObserveAdminServices.xml`)

### Processing and helpers (`src/main/groovy/darpan/`)

- Reconciliation core: `reconciliation/core/ReconciliationServices.groovy` (Spark compare
  implementation), `reconciliation/core/RuleSetCompareScopeAdapter.groovy` (compare-scope
  extraction contract), `reconciliation/core/reconcileFilesByMapping.groovy` (legacy mapping
  bridge), `reconciliation/generic/reconcileGenericFiles.groovy`
- Rule engine: `reconciliation/rule/RuleEngineSupport.groovy` (Drools compile/execute),
  `reconciliation/rule/RuleConditionParser.groovy` (structured expression + preActions parsing),
  `reconciliation/rule/RuleDiffSupport.groovy` (Diff row shaping)
- Verification and conclusions: `facade/reconciliation/RunVerificationSupport.groovy` (shared
  by manual and scheduled runs), `MissingDiffVerificationSupport.groovy`,
  `ExchangePairVerificationSupport.groovy`, `ReturnPresenceVerificationSupport.groovy`,
  `RunConclusionStep.groovy`; `reconciliation/conclusion/` (`ConclusionTreeEngine`,
  `RunConclusionSupport`, `ConclusionRuleLogicGenerator`); run stages in
  `facade/reconciliation/RunObservability.groovy`
- Source filters: `reconciliation/source/SourceFilterSupport.groovy`
- Lifecycle / flowchart runs: `reconciliation/flowchart/` (`RunFlowchartTree`,
  `RunFlowchartWalker`, `RunFlowchartSupport`, `LifecycleSupport`, `LifecycleStageCompiler`,
  `NetSuiteCheckImportSupport`)
- Automation: `reconciliation/automation/AutomationExecutionSupport.groovy` (window resolution,
  child executions, API extraction, retries and dead-lettering), `AutomationRuntimeSupport.groovy`,
  `SftpAutomationSupport.groovy`, `pollSftpAndRunReconciliation.groovy`,
  `StuckRunReaper.groovy` (stale RUNNING/PENDING reaper behind `sweep#StuckReconciliationRuns`)
- Notifications: `reconciliation/notification/TenantNotificationSupport.groovy`
  (`notifyRunCompleted` — chat-space registry fan-out + claim-then-deliver dedupe on
  `notifiedDate`, webhook validation, Google Chat / Slack webhook / Slack bot delivery),
  `SlackApiClient.groovy`, `SlackOAuthSupport.groovy`, `facade/settings/ChatSpaceFacadeSupport.groovy`,
  `facade/settings/UserNotificationPreferenceSupport.groovy` (per-user default chat space, stored
  as a per-tenant `UserPreference` JSON map)
- Facade auth/session: `facade/auth/AuthFacadeSupport.groovy`, `facade/auth/AuthSessionSupport.groovy`
- Facade shared support: `facade/common/FacadeSupport.groovy`, `TenantAccessSupport.groovy`
  (active-tenant scoping and role gates), `TenantScopedFinder.groovy` (the required route for
  tenant-scoped reads in Groovy; bare `disableAuthz` is ratcheted in tests),
  `SharedConfigAccessSupport.groovy` / `SharedConfigGrantSupport.groovy` (config sharing),
  `RpcMethodFence.groovy` (`/rpc/json` dispatches only `facade.*`, `admin.*`, `gql.*`; closes
  `/rest/login`), `PaginationSupport.groovy`, `DataManagerSupport.groovy` (safe data-manager
  paths), `OutboundHttpPolicy.groovy`, `DarpanMdcSupport.groovy` (structured log context)
- Admin support: `admin/` (`AdminAccessSupport` super-admin re-check, `AdminTenantSupport`,
  `AdminUserSupport`, `AdminMembershipSupport`, `AdminScheduleSupport`, `AdminAuditSupport`)
- Migrations: `migration/` (`MigrationLedgerSupport`, `MigrationSupervisorSupport`,
  `MigrationAdminSupport`)
- API contract generator: `contract/ApiContractGenerator.groovy` (Gradle tasks
  `generateApiContract` / `checkApiContract`; see `docs/reconciliation/platform/api-contract-testing.md`)
- Facade reconciliation: `facade/reconciliation/runGenericDiff.groovy`,
  `runSavedRunDiff.groovy` (saved-run execution incl. notification call),
  `ReconciliationMappingSupport.groovy`, `ReconciliationOutputSupport.groovy`,
  `ReconciliationSavedRunSupport.groovy`, `ReconciliationApiWindowSupport.groovy`,
  `AutomationFacadeSupport.groovy`,
  `ReconciliationDashboardPreferenceSupport.groovy`
- Facade settings/search/jsonschema: `facade/settings/SettingsFacadeSupport.groovy`,
  `facade/settings/LlmSettingsSupport.groovy`, `facade/search/NavigationSearchSupport.groovy`,
  `facade/jsonschema/JsonSchemaFacadeSupport.groovy`
- JSON schema scripts/helpers: `jsonschema/service/crud/createJsonSchemaFromJson.groovy`,
  `jsonschema/service/crud/saveRefinedSchema.groovy`, `jsonschema/service/processing/*.groovy`,
  `jsonschema/service/validation/*.groovy`, `jsonschema/common/JsonSchemaUtil.groovy` and
  siblings (`JsonSchemaValidator`, `JsonSchemaInferenceUtil`, `SchemaFlattener`,
  `JsonSchemaConstants`)
- Shared constants/utilities: `common/DarpanEntityConstants.groovy`, `common/ValueSupport.groovy`,
  `common/CryptRotationSupport.groovy`, `common/TransactionDetachSupport.groovy`

### Runbooks and platform docs

- End-to-end pipeline: `docs/reconciliation/reconciliation-flow.md`
- Tenancy model: `docs/tenancy-model.md`; access policy and RPC fence: `docs/reconciliation/platform/api-access-policy.md`
- RuleSet compare-scope cutover project (archived; runtime path shipped, Mapping migration not
  landed): `docs/archive/reconciliation/projects/ruleset-only-cutover/README.md`
- Production tenant settings runbook: `docs/reconciliation/platform/production-settings-surfaces.md`
- Tenant-scoped access model: `docs/reconciliation/platform/company-scoped-access-and-user-preferences.md`
- Platform security and service notes: `docs/reconciliation/platform/security.md`, `docs/reconciliation/platform/services.md`
- Automation setup and validation notes: `docs/reconciliation/automation/order-reconciliation-automation.md`, `docs/reconciliation/automation/sftp-reconciliation.md`

## Shared Resources

- Entities: `entity/ReconciliationEntities.xml` (runs, results, run steps, automations,
  SFTP/NetSuite settings incl. SuiteQL queries, the `SourceSystemConnector` registry, Slack installs,
  `TenantChatSpace` + `ReconciliationRunNotifySubscription` for the chat notification registry, `TenantNotificationSetting` RETIRED 2026-07-29 — kept one release as
  `migrate#TenantNotificationSettings` input only), `entity/RuleEntities.xml` (RuleSet, Rule, compare
  scopes/sources), `entity/MappingEntities.xml`, `entity/AuthEntities.xml` (tenant permission
  membership, tenant settings, `ConfigTenantAccess`), `entity/JsonSchemaEntities.xml`
  (`darpan.reconciliation.JsonSchema`), `entity/AdminEntities.xml` (`AdminAuditLog`),
  `entity/MigrationEntities.xml` (migration registry and run ledger)
- Setup seed data: generic source files in `data/` (for example type, security, system-message,
  job, mapping, and reconciliation seed files) use the `darpan-seed-initial` and `darpan-seed`
  reader types; NetSuite setup data uses `netsuite-seed-initial` and `netsuite-seed`. Ordered
  setup loads through the standard Moqui `./gradlew load` task at the framework root (there is
  no `loadDarpanData` task).
- Scheduled jobs seed: `data/ReconciliationJobSeedData.xml` (automation scanner every 5 min,
  stuck-run reaper every 10 min, generated-output purge daily)
- Current upgrade data for customer/self-hosted upgrades: `data/upgrade-data.xml`, loaded in
  Docker startup through the component-owned `loadDarpanUpgradeData` Gradle task invoked as
  `:runtime:component:darpan:loadDarpanUpgradeData` (Gradle 9 removed `-b`), the `darpan-seed`
  reader type, and the `component://darpan/data/upgrade-data.xml` location
- Release upgrade records must also be reflected in the appropriate generic source data file; release preflight generates upgrade candidates from generic source data diffs against the previous tag
- Archived release upgrade data: versioned files such as `data/releases/1.5.1/upgrade-data.xml` (and the pre-reset history under `data/releases/pre-reset/`); these are generated release artifacts and should not be treated as source seed files when diffing upgrade data
- **v1.2.0 one-time migration action** (not covered by `loadDarpanUpgradeData`, which only loads
  declarative seed data): `reconciliation.ReconciliationNotificationServices.migrate#TenantNotificationSettings`
  copies each tenant's retired `TenantNotificationSetting` webhook into a new `TenantChatSpace`
  named "Default space" and links that tenant's webhook-less automations to it. The service is
  `authenticate="false"` and has no `allow-remote="true"` — it is **not** reachable through
  `/rpc/json` and must be invoked once per environment from a server-side context with authz
  disabled (a system/admin execution context, for example a Moqui Tools service-run screen under
  an `ADMIN` login, or a one-off `ec.service.sync().name(...).disableAuthz().call()` script), never
  as a normal tenant-user request — `create#darpan.reconciliation.TenantChatSpace` inside the
  migration is tenant-scope-filtered when a normal user context is active, so running it as a
  regular authenticated user silently creates nothing. It is idempotent: tenants that already have
  any `TenantChatSpace` row are skipped, so it is safe to invoke more than once.
- Theme library (legacy backend screens only; see `docs/theme-library.md`): `theme-library/css/tokens.css`, `theme-library/css/components.css`, `theme-library/js/theme-runtime.js`, `theme-library/html/blocks.html`
- Build config + organization guardrails: `build.gradle` (JDK 21 toolchain, Spark and Drools
  8.44.0.Final pins, `sparkJava21Opens` JVM flags, `unitTest`/`smokeTest`/`testAll`/`mysqlTest`
  pools, `generateApiContract`, `verifyOrganization`) — see `docs/runtime-baseline.md`
- Docker production config: lives in the private `drpn-ai/darpan-docker-config` repo —
  `MoquiProductionConf.xml` (the transactional datasource must keep
  `startup-add-missing="${entity_add_missing_startup}"` so deployed MySQL environments
  create newly introduced entity tables before upgrade-data load or user-facing finds run) and
  `docker/entrypoint.sh` / `docker/prod/entrypoint.sh` (JDK 21 `JAVA_TOOL_OPTIONS` flags, required
  secrets), with one Dockerfile per image (`docker/Dockerfile` for UAT, `docker/prod/Dockerfile` for prod)

## Framework References

- DataDocument entity (used by RunDataDocument) lives in `framework/entity/EntityEntities.xml`.
- SystemMessageRemote entity (used by RunSystemInstance) lives in `framework/entity/ServiceEntities.xml`.
