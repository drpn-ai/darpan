# Reconciliation Flow: Ingestion to Alert

This page describes the real end-to-end pipeline as implemented in this component: how source
data gets in, how the Spark diff runs, where Drools rule evaluation happens, how a completed run
turns into a tenant notification, and how the automation layer schedules and supervises all of
it. Every service named here exists in `service/**` and every class in `src/main/groovy/**`.

```text
ingest (file upload | API window | SFTP poll)
  -> stage artifacts under runtime://datamanager/reconciliation-runs/{runId}/{timestamp}/
  -> Spark base compare (missing-object Diffs + matched pairs)
  -> Drools RuleSet execution on matched pairs (rule failures -> Diff rows)
  -> persist result JSON + ReconciliationRunResult manifest
  -> Google Chat run-completed notification (chat-space registry fan-out, all terminal states)
```

## 1. Ingestion

Three entry paths feed the same compare pipeline:

| Path | Entry services | Notes |
| --- | --- | --- |
| File upload (UI) | `facade.ReconciliationFacadeServices.run#GenericDiff`, `run#SavedRunDiff` | JSON-RPC friendly: accepts `file1Name`/`file1Text` and `file2Name`/`file2Text` instead of multipart uploads. Payload-shape mismatches (JSON into a CSV run, schema documents as record data) are rejected before Spark staging. |
| API window extraction | `run#SavedRunDiff` (manual, with `windowStartDate`/`windowEndDate`) and `reconciliation.ReconciliationAutomationServices.execute#Automation` (scheduled) | API-backed compare sources use `sourceTypeEnumId=AUT_SRC_API` plus either a `SystemMessageRemote` (for example Shopify or HotWax OMS) or a tenant `NsRestletConfig` (NetSuite Restlet). Window normalization lives in `darpan/facade/reconciliation/ReconciliationApiWindowSupport.groovy`; Shopify extraction runs a single Bulk Operations `created_at` query (shopify-darpan `extractShopifyOrders.groovy` + `ShopifyBulkOperationClient`) with no client-side window splitting. The requested period is always the active tenant time zone; source-system time zones only affect API parameter representation. |
| SFTP polling | `reconciliation.ReconciliationAutomationServices.poll#SftpAndReconcile`, `run#SftpFileAutomation` | Checks configured `SftpServer` locations (via the `moqui-sftp` component), stages the newest matching files, archives consumed files, and routes into the compare pipeline. Support classes: `darpan/reconciliation/automation/SftpAutomationSupport.groovy`, `pollSftpAndRunReconciliation.groovy`. |

All paths stage source files into the run artifact folder
`runtime://datamanager/reconciliation-runs/{runId}/{timestamp}/` and create a
`darpan.reconciliation.ReconciliationRunResult` manifest with status `AUT_STAT_RUNNING` **before**
long-running compare work starts (the create-before-poll contract that run-history surfaces
depend on).

## 2. Source extraction contract

Object identity and per-file primary-ID extraction come from RuleSet compare scopes:

- `darpan.rule.RuleSetCompareScope` defines the compared object for a `RuleSet`.
- `darpan.rule.RuleSetCompareSource` defines one row per file side (`FILE_1`, `FILE_2`) with
  system, source type (file upload or API), file type, optional JSON schema, record-root and
  primary-ID expressions, and an optional ID normalizer.
- `reconciliation.ReconciliationCoreServices.prepare#RuleSetCompareScope` resolves and validates
  that configuration; the adapter logic is in
  `darpan/reconciliation/core/RuleSetCompareScopeAdapter.groovy`.
- Legacy mapping-backed runs (`darpan.mapping.ReconciliationMapping` +
  `ReconciliationMappingMember`) still execute through
  `reconciliation.ReconciliationCoreServices.reconcile#FilesByMapping` as a bridge; see
  [domain README](../domains/reconciliation/README.md) for current cutover status.

## 3. Spark base compare

`reconciliation.ReconciliationCoreServices` runs the diff on Apache Spark 3.5.1 (local master,
in-process; JVM module flags documented in [runtime-baseline](../runtime-baseline.md)):

- `reconcile#IdDataFrames` / `reconcile#UnifiedFiles` — core anti-join comparison primitives.
- `reconcile#RuleSetCompareScopeBaseDiff` — extracts per-side primary IDs and emits the
  missing-object Diffs before any rule runs:
  - `MISSING_IN_FILE_1`: primary ID exists in file 2 but not file 1.
  - `MISSING_IN_FILE_2`: primary ID exists in file 1 but not file 2.
- `reconcile#RuleSetCompareScope` — orchestrates base compare plus rule execution for one
  compare scope.

CSV compare-scope extraction validates the configured primary-ID column before Spark projection,
so header/file-type mismatches fail with a contract error listing available columns instead of a
raw `UNRESOLVED_COLUMN` stack fragment.

## 4. Drools rule evaluation

Rule storage and execution live in `reconciliation.ReconciliationRuleEngineServices`
(documented contract-by-contract in [rule-engine-services](rule-engine-services.md)):

- `save#RuleSet`, `save#Rule`, `delete#Rule`, `delete#RuleSet` — tenant rule authoring CRUD.
- `compile#RuleSet` — compiles the active `darpan.rule.Rule` rows of a RuleSet into a Drools
  KieBase (DRL), cached until `clear#RuleSetCache` or a rule change.
- `execute#RuleSetMatchedPairs` / `execute#RuleSet` — runs the compiled rules.

Key behavioral contract: **DRL rules receive only matched object pairs** (primary ID present in
both files). Missing-object Diffs are already emitted by the base compare stage, so rules can
assume the compared object exists on both sides. When a rule's condition fails for a pair, the
engine emits a field/business-rule Diff row (for example SKU or price mismatch) with the rule's
severity — this is the "rule failure raises a flag" core of the product.

Support classes: `darpan/reconciliation/rule/RuleEngineSupport.groovy` (compilation/execution),
`RuleConditionParser.groovy` (structured rule-expression parsing, including per-field
`preActions` such as `STRING_TO_INT`), and `RuleDiffSupport.groovy` (Diff row shaping).

Runtime stack note: Drools 7.73 with MVEL2 forced to 2.5.x — MVEL2 2.4.x fails on JDK 21
(see [runtime-baseline](../runtime-baseline.md)).

## 5. Result persistence

- Result JSON is written into the same run artifact folder as the staged source files.
- The `ReconciliationRunResult` row is updated to `AUT_STAT_SUCCESS` (or `AUT_STAT_FAILED`) with
  `file1DataManagerPath`, `file2DataManagerPath`, `resultDataManagerPath`, and difference counts
  (`differenceCount`, `onlyInFile1Count`, `onlyInFile2Count`).
- The UI reads results through `facade.ReconciliationFacadeServices.list#GeneratedOutputs`,
  `get#GeneratedOutput` (JSON or on-demand CSV, plus source-artifact downloads limited to the
  manifest's `sourceDetails.files` paths), and `delete#GeneratedOutput`. All are scoped to the
  active tenant.

## 6. Alert / notification path

Every run that reaches a terminal state — `AUT_STAT_SUCCESS` (including success with rule
processing warnings), `AUT_STAT_FAILED`, or a reaper-killed stale run — can notify the owning
tenant. The silent no-op statuses `AUT_STAT_NO_DATA` and `AUT_STAT_SKIP_DUP` never trigger
notification.

1. Four terminal-state call sites invoke
   `darpan.reconciliation.notification.TenantNotificationSupport.notifyRunCompleted(...)`:
   - `darpan/facade/reconciliation/runSavedRunDiff.groovy` — manual saved-run executions
     (success and failure)
   - `darpan/reconciliation/automation/AutomationExecutionSupport.groovy` — scheduled API-window
     automations (success and failure)
   - `darpan/reconciliation/automation/SftpAutomationSupport.groovy` — SFTP automations
     (best-effort: wrapped so a notify failure never fails the run)
   - `darpan/reconciliation/automation/StuckRunReaper.groovy` — stale `RUNNING`/`PENDING` runs the
     reaper flips to `AUT_STAT_FAILED`; the payload gets an extra watchdog `terminationReason` line
2. `notifyRunCompleted` re-reads the trusted `ReconciliationRunResult` row (not caller input) to
   re-check the tenant and dedupe, then resolves fan-out destinations in
   `resolveDestinationChatSpaces`: the union of the run's automation-linked
   `darpan.reconciliation.TenantChatSpace` (via `ReconciliationAutomation.chatSpaceId`, when set)
   and every `darpan.reconciliation.ReconciliationRunNotifySubscription` row for that run — one
   per user who opted in with "notify me," each snapshotting that user's default chat space at
   subscribe time. Destinations are deduplicated by chat-space ID and re-pinned to the run's
   tenant; retired (`isActive='N'`) or webhook-less registry rows are dropped from the fan-out. No
   destinations means no delivery attempt (`NO_DESTINATIONS`).
3. Delivery is dedupe-guarded by an atomic claim-then-deliver compare-and-swap on `notifiedDate`:
   a conditional `updateAll` (`WHERE reconciliationRunResultId = ? AND notifiedDate IS NULL`) is
   the race guard, so only one concurrent caller can claim a given run result; the loser reports
   `ALREADY_NOTIFIED` without delivering. A failed delivery still consumes the claim — there are no
   automatic retries. Other skip reasons: `NO_TENANT`, `NO_RESULT_ID`, `RESULT_NOT_FOUND`,
   `TENANT_MISMATCH`.
4. `reconciliation.ReconciliationNotificationServices.build#RunCompletedPayload` builds the
   Google Chat text payload: run name, tenant label, result ID, a deep link to the run result
   (base URL from the `DARPAN_APP_BASE_URL` env var, the `darpan.app.baseUrl` property, or the
   first allowed web origin), the three difference counts, and — for failed or reaper-killed runs
   — a status/termination-reason warning line.
5. `TenantNotificationSupport.deliverGoogleChat` posts the payload to each resolved destination's
   webhook. URLs are validated to be HTTPS `chat.googleapis.com` space-message endpoints with
   key/token parameters.

Tenants manage the named chat-space registry through
`facade.SettingsFacadeServices.list#TenantChatSpaces` / `save#TenantChatSpace` /
`delete#TenantChatSpace` (tenant write access required; a space still referenced by an automation
or a subscription must be deactivated, not deleted). Each user's personal default chat space is
`get#UserNotificationDefault` / `save#UserNotificationDefault` — a personal preference, so no
tenant write access is required. Per-run opt-in notifications use
`facade.ReconciliationFacadeServices.subscribe#RunNotification` /
`unsubscribe#RunNotification`, callable only while the run is `AUT_STAT_PENDING` or
`AUT_STAT_RUNNING`; `get#ReconciliationRunStatus` reports the caller's `mySubscription` /
`mySubscriptionSpaceName` for that run. The one-webhook-per-tenant
`darpan.reconciliation.TenantNotificationSetting` entity and its `get#TenantNotificationSettings`
/ `save#TenantNotificationSettings` facade services are retired; existing tenant webhooks migrate
to the registry through the one-time `migrate#TenantNotificationSettings` service (invocation
mechanics: [code-map.md](../code-map.md#shared-resources)).

## 7. Automation: scheduling and supervision

The automation layer is documented in detail in
[order-reconciliation-automation](automation/order-reconciliation-automation.md) and
[sftp-reconciliation](automation/sftp-reconciliation.md). The moving parts:

- Tenant-owned config: `ReconciliationAutomation` (input mode `AUT_IN_API_RANGE` or
  `AUT_IN_SFTP_FILES`, Quartz-style `scheduleExpr`, window settings) plus exactly two
  `ReconciliationAutomationSource` rows, managed through the
  `facade.ReconciliationFacadeServices` automation services
  (`list/get/save/delete/pause/resume#Automation`, `run#AutomationNow`,
  `list#AutomationExecutions`, `list#AutomationSourceOptions`).
- Execution: `execute#Automation` resolves the scheduled date window, creates idempotent child
  execution rows (`ReconciliationAutomationExecution`), extracts sources, and runs the RuleSet
  compare-scope pipeline. Support classes: `AutomationExecutionSupport.groovy`,
  `AutomationRuntimeSupport.groovy`.
- Scheduled jobs (seeded in `data/ReconciliationJobSeedData.xml`):

| Moqui ServiceJob | Cron | Service | Purpose |
| --- | --- | --- | --- |
| `scan_ReconciliationAutomations_5m` | every 5 min | `scan#DueAutomations` | Find active automations with `nextScheduledFireTime` due, call `execute#Automation`, advance the fire time from `scheduleExpr`. |
| `sweep_StuckReconciliationRuns_10m` | every 10 min | `sweep#StuckReconciliationRuns` | Reaper (`darpan/reconciliation/automation/StuckRunReaper.groovy`): flips `ReconciliationRunResult` and `ReconciliationAutomationExecution` rows stale in `AUT_STAT_RUNNING`/`AUT_STAT_PENDING` for more than `staleMinutes` (default 120) to `AUT_STAT_FAILED`, so the UI stops blocking re-triggers and the scanner stops skipping those windows. |
| `purge_ReconciliationGeneratedFiles_daily` | 02:00 daily | `reconciliation.ReconciliationGenericServices.purge#GeneratedOutputFiles` | Deletes generated outputs older than the retention window (default 15 days). |
