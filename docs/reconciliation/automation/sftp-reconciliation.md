# SFTP-Driven Reconciliation Automation

Service: `reconciliation.ReconciliationAutomationServices.poll#SftpAndReconcile`

The automation polls configured SFTP locations, stages the newest matching files locally, and then routes the staged files into one of two backend paths:

- preferred RuleSet path: `reconciliation.ReconciliationCoreServices.reconcile#RuleSetCompareScope`
- temporary migration bridge: `reconciliation.ReconciliationCoreServices.reconcile#FilesByMapping`

When `ruleSetId` is provided, SFTP automation now uses the same compare-scope + DRL path as Generic reconciliation. Legacy scheduled jobs can continue to run through the mapping bridge until they are migrated.

## Prerequisites
- Install/configure the `moqui-sftp` component. For Docker builds, `runtime/component/darpan/docker/Dockerfile` now clones `https://github.com/hotwax/moqui-sftp.git` before running `addRuntime`.
- Create `darpan.reconciliation.SftpServer` records with host, port, username, and either `password` or `privateKey`.
- For RuleSet mode:
  - define a `RuleSet`
  - define one or more `RuleSetCompareScope` rows under that RuleSet
  - define exactly one `RuleSetCompareSource` for `FILE_1` and one for `FILE_2`
- For legacy mapping mode:
  - keep valid `ReconciliationMappingMember` rows for both systems
  - each mapping member still needs a saved `JsonSchema` row when the current mapping-readiness contract requires one

## Inputs (key parameters)
- `ruleSetId` – preferred. Enables the RuleSet compare-scope pipeline.
- `compareScopeId` – optional compare-scope override; required only when the RuleSet has multiple compare scopes.
- `reconciliationMappingId` – temporary legacy bridge for unmigrated jobs.
- `file1SystemEnumId`, `file2SystemEnumId`
  - optional in RuleSet mode; when provided they are validated against the compare-scope file sides
  - required in mapping mode
- `file1SftpServerId`, `file2SftpServerId` – required SFTP server records to poll.
- `file1FileTypeEnumId`, `file2FileTypeEnumId` – optional file-type overrides (`DftCsv`, `DftJson`).
- `file1SchemaFileName`, `file2SchemaFileName` – optional per-run saved-schema overrides.
- `file1RemotePath`, `file2RemotePath` – optional path override (recommended: point to the directory to scan). If omitted, the path portion embedded in the `SftpServer.host` value is used or `/` if none is provided.
- Files are always selected as the newest file in the directory; no regex is needed. After download, the source file is moved into an `archive` subfolder (configurable via `archiveSubdir`; created if missing).
- Outputs default to a `reconciled` subfolder under the stage directory; override with `outputLocation` if needed.
- `stageLocation` (default `runtime://tmp/reconciliation/automation/input`) – where SFTP files are copied.
- `outputLocation` (optional) – where diff files are written. By default the service writes to a `reconciled` subfolder under the stage directory.
- `sparkMaster`, `sparkAppName` – optional Spark overrides.

## Behavior
- Uses `SftpClient` from `moqui-sftp` to list and download the newest file. Returns `dataAvailable=false` if either side has no file.
- If the configured host contains a path (e.g., `sftp://host:22/drop/`), that path is used as the default remote path unless you override with `fileXRemotePath`.
- Strips any protocol prefix (e.g., `sftp://`, `ssh://`, `ftp://`) from configured hosts before connecting; prefer storing hosts without a scheme, but prefixed values now work.
- Run log messages are emitted as service messages, so they appear in the Service Job run history (Messages column) for quick troubleshooting.
- Skips the archive subfolder when picking the newest file, and uploads the diff back into the same base path (creating it if missing); root uploads may be rejected by the server.
- Absolute output locations outside runtime are treated as remote upload targets: the diff is written locally under the default `reconciled` folder, then uploaded to that remote path (or the file 1 base path if none is provided).
- In RuleSet mode, the service resolves the compare scope first, stages both files, then delegates to `reconcile#RuleSetCompareScope` and writes one unified JSON diff artifact containing both missing-object and rule-generated rows.
- In mapping mode, the service delegates to `reconcile#FilesByMapping` as a temporary bridge.
- RuleSet diff filenames are based on `compareScopeId`; legacy mapping diff filenames continue to use the mapping-based output path.
- Returns diff file path/name and per-side counts; `processingWarnings` include mapping or detection fallbacks.

## Scheduling Example (service-job)
```xml
<service-job job-name="PollSftpAndReconcileOrders"
    service-name="reconciliation.ReconciliationAutomationServices.poll#SftpAndReconcile"
    description="Check OMS vs Shopify SFTP drops and run reconciliation"
    frequency="600" pause-time="0">
    <parameter name="ruleSetId" value="OmsVsShopifyOrders"/>
    <parameter name="compareScopeId" value="ORDER_JSON_SCOPE"/>
    <parameter name="file1SftpServerId" value="OMS_SFTP"/>
    <parameter name="file2SftpServerId" value="SHOPIFY_SFTP"/>
    <parameter name="stageLocation" value="runtime://tmp/reconciliation/automation/input/orders"/>
    <parameter name="outputLocation" value="runtime://tmp/reconciliation/automation/input/orders/reconciled"/>
</service-job>
```

The service returns `dataAvailable=false` when no files are found so the job can be scheduled safely at short intervals without raising errors.

## UI: SFTP Automation Screen
- Navigate to **Reconciliation → SFTP Automation** to create/update schedules without editing XML.
- File 1/2 sections: choose systems, SFTP remotes, optional file type (CSV/JSON) and schema overrides, plus remote path overrides.
- Schedule section: pick cadence (minutes/hours/daily) or supply a cron expression, set output location, and pause/resume.
- Advanced toggle: stage location defaults to `runtime://tmp/reconciliation/automation/input`; output defaults to a `reconciled` subfolder under that stage directory; archive folder name defaults to `archive`; Spark overrides are optional.
- Existing jobs for this service are listed with edit/pause/resume actions; editing preloads parameters so you can adjust paths or cadence quickly.

## UI: Settings Screen (SFTP credentials)
- Navigate to **Settings → SFTP** to add/update `darpan.reconciliation.SftpServer` entries used by the automation screen.
- Store host, port, username, and either a password or private key; sensitive fields are encrypted and not shown in clear text in the listing.
