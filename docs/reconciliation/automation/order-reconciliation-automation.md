# Order Reconciliation Automation

This page describes the current automation contract for Shopify/OMS order reconciliation. It covers the dashboard workflow, the backend facade services, the two input modes, execution records, and the validation checks needed before production handoff.

## User Workflow

The automation surface starts at `/reconciliation/automations` in `darpan-ui`.

- The dashboard lists automations for the active tenant with search, active-state, input-mode, and latest-status filters.
- Each row shows the saved run, input summary, schedule summary, active state, latest execution, next scheduled fire time, and permission-aware actions.
- The only dashboard create action is **Create Automation**.
- The create workflow first asks whether the automation should use an existing saved run or create a new reconciliation run first.
- Existing-run setup asks for a saved run, input mode, sources, schedule, and automation name.
- New-run setup sends the user through the existing reconciliation create flow, creates the saved run, then returns to automation setup with that run selected.

View-only tenant sessions can list automation rows and execution history. They cannot create, edit, pause, resume, delete, or mutate settings. Run access is controlled separately from edit access through the active-tenant permission flags returned by the auth facade.

## Backend Services

Dashboard and workflow callers should use the remote facade services in `facade.ReconciliationFacadeServices`:

- `list#Automations`
- `get#Automation`
- `save#Automation`
- `delete#Automation`
- `pause#Automation`
- `resume#Automation`
- `run#AutomationNow`
- `list#AutomationExecutions`
- `list#AutomationSourceOptions`

Execution is handled by internal services in `reconciliation.ReconciliationAutomationServices`:

- `execute#Automation`
- `scan#DueAutomations`
- `run#SftpFileAutomation`
- `poll#SftpAndReconcile`

`save#Automation` persists one tenant-owned `ReconciliationAutomation` row and exactly two `ReconciliationAutomationSource` rows, one for `FILE_1` and one for `FILE_2`. Each source must match the selected saved-run file side.

## API Date-Range Mode

API date-range automations use `inputModeEnumId=AUT_IN_API_RANGE` and source rows with `sourceTypeEnumId=AUT_SRC_API`.

The executor resolves a date window for each run, extracts both source files, then runs `reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope`.

Supported relative windows are:

- previous day
- previous week
- previous month
- last N days
- last N weeks
- custom range

Long windows are split on calendar-month boundaries before extraction. The execution table stores the parent window and each child window so backfills and duplicate checks remain auditable.

API source extraction is pluggable. Each source row must resolve to a configured extraction service through `safeMetadataJson.extractServiceName`. The executor sends:

- `automationId`
- `fileSide`
- `systemEnumId`
- `sourceTypeEnumId`
- `windowStartDate`
- `windowEndDate`
- the same window values under `dateFromParameterName` and `dateToParameterName`

If `dateFromParameterName` and `dateToParameterName` are omitted, the fallback names are `fromDate` and `toDate`.

Example OMS source metadata:

```json
{
  "extractServiceName": "reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrders",
  "parameters": {
    "omsRestSourceConfigId": "GORJANA_OMS"
  }
}
```

For the HotWax OMS extractor, set:

- `dateFromParameterName=windowStart`
- `dateToParameterName=windowEnd`

The system picker displays this source as `HotWax` while preserving the existing `OMS` enum id used by saved runs and endpoint metadata. Dropdown options merge legacy OMS aliases such as `DarSysOms` into that single `HotWax` choice, and upgrade data removes stale `HOTWAX` aliases so the picker does not show both `HotWax` and `OMS`.

`list#AutomationSourceOptions` returns active tenant OMS source configs with this extractor metadata and the required window parameter names. `save#Automation` also applies the same OMS defaults when a source row omits `extractServiceName` and the active tenant has exactly one readable OMS REST source config.

The current Shopify component provides auth config, source catalog, query builder, and GraphQL transport contracts. Darpan seeds `SystemMessageRemote` `SHOPIFY_REMOTE` for the Shopify Admin GraphQL orders endpoint (`/admin/api/{apiVersion}/graphql.json`) and points it at `facade.ShopifyFacadeServices.execute#ShopifyGraphql`. Darpan source options expose only that extractable Shopify GraphQL remote with the endpoint label `Admin GraphQL Orders`; generic system placeholder remotes such as `SHOPIFY` are not Create Run endpoint choices. The endpoint includes the known normalized Shopify orders identity fields as `primaryIdOptions`:

```json
[
  { "fieldPath": "$.records[*].id", "label": "Order ID" },
  { "fieldPath": "$.records[*].name", "label": "Order name" }
]
```

Setup flows should render those fields as a picker instead of asking for a raw expression. API date-range automation uses `safeMetadataJson.extractServiceName=reconciliation.ShopifyOrderExtractionServices.extract#ShopifyOrders` plus `safeMetadataJson.parameters.shopifyAuthConfigId` to write the Shopify source file from the selected Shopify auth config.

Manual saved-run API execution accepts the selected calendar dates along with the timestamp payload. Generic API sources normalize calendar-date payloads through the active tenant timezone before extraction. Shopify sources normalize the same calendar dates through the selected `ShopifyAuthConfig.timeZone` before creating UTC GraphQL filters. For example, a UI-selected `2026-03-01` through `2026-04-01` window for a Shopify config in `America/Chicago` is dispatched as `2026-03-01T06:00:00Z` through `2026-04-01T05:00:00Z`, preserving the shop-local month across DST. HotWax receives epoch milliseconds for the normalized window as `orderDate_from` and `orderDate_thru`.

For API Order Sync saved runs, Shopify and HotWax are extracted independently from the same normalized date window before the RuleSet compare runs:

1. Shopify orders are extracted with a GraphQL `orders(first: 100, after: $after, query: $search)` query using `created_at:>=<windowStartUtc> created_at:<windowEndUtc>`, where the UTC window is derived from the Shopify config timezone.
2. HotWax OMS orders are extracted with `orderDate_from` and `orderDate_thru`.
3. Darpan compares the two extracted files and writes the diff output.

The Shopify extractor normalizes GraphQL order GIDs to numeric `legacyResourceId` values because API Order Sync compares Shopify orders to HotWax order `externalId` values.

## SFTP File Mode

SFTP automations use `inputModeEnumId=AUT_IN_SFTP_FILES` and source rows with `sourceTypeEnumId=AUT_SRC_SFTP`.

SFTP-file mode does not use date-window fields. Each source row must provide:

- `sftpServerId`
- `remotePathTemplate`
- file-side system metadata from the saved run

`run#SftpFileAutomation` records an execution row, loads the two SFTP source rows, validates SFTP access through the centralized SFTP guard, then delegates file movement and reconciliation to `poll#SftpAndReconcile`.

The SFTP path selects the newest matching file on each side, stages both files under `runtime://tmp/reconciliation/automation/input`, archives consumed inputs under the configured archive subdirectory, and writes the result artifact to the data-manager reconciliation run folder unless `outputLocation` overrides it.

## Execution Records And Output

Each execution writes a `ReconciliationAutomationExecution` row. Successful API and SFTP runs also persist a `ReconciliationRunResult` row and attach the result id plus data-manager result path to the execution row.

When the active tenant has `TenantNotificationSetting.isActive=Y` and a Google Chat webhook configured, successful API and SFTP automation runs send a run-completion message after the result row is stored. Manual saved-run execution uses the same tenant notification setting. Notification failures are logged and do not convert a completed reconciliation run into a failed run.

Common statuses:

- `AUT_STAT_PENDING`
- `AUT_STAT_RUNNING`
- `AUT_STAT_SUCCESS`
- `AUT_STAT_NO_DATA`
- `AUT_STAT_FAILED`
- `AUT_STAT_SKIP_DUP`

Generated source files and result artifacts should live under the data-manager reconciliation run tree. Error metadata and facade responses must not include access tokens, passwords, authorization headers, or raw secret values.

## Production Validation

Run these checks before closing the automation feature for production handoff:

- Create a Shopify auth config with order-read permission enabled.
- Create a HotWax OMS REST source config.
- Create or select a saved Shopify-vs-OMS order reconciliation run.
- Create an API date-range automation from the saved run.
- Run the automation manually for a previous-day window.
- Verify both source files are written to data-manager storage.
- Verify a reconciliation result artifact and execution history row.
- Create an SFTP-file automation from the same saved run.
- Drop two test files, run the automation manually, verify the result artifact, and confirm consumed-file archive behavior.
- Configure a tenant Google Chat webhook through Settings, run a successful manual or automation execution, and confirm exactly one completion message is posted for the tenant.
- Confirm a view-only tenant session cannot mutate automations or source settings.
- Confirm cross-tenant automation, SFTP, Shopify, OMS, saved-run, and result access is denied.

The API date-range smoke is complete only when both Shopify and OMS source rows carry concrete extractor metadata from the UI defaults or the backend save defaults. Without `safeMetadataJson.extractServiceName`, `save#Automation` rejects the automation source before a scheduled execution can be created with a guaranteed failure.
