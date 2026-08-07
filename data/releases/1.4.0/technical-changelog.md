# Technical Changelog For Darpan 1.4.0

This file is the engineer-facing companion to the user release notes. Keep it curated and diffable.

## Versioning decision

- `1.4.0` is a **minor** release. The backend range carries 9 `feat` commits (transfer-order
  connector, state window mode, per-source status list, state-extract ceiling, `errorDetail`,
  automation run-step rows, product-store exclusion field) alongside 12 `fix` commits. No breaking
  change: the facade contract is additive only (90 methods preserved, contract version `2`), the
  entity changes add fields to existing entities, and the upgrade data adds records without
  modifying any.
- `darpan-hotwax` goes to **`0.7.0`** — minor, 5 `feat` commits (transfer-order extract service,
  configurable window field, optional window, server-side filters, state-extract ceiling).
- `darpan-ui` goes to **`2.5.0`** — minor. 12 of its 13 commits are `fix`, but one is a `feat`
  (transfer-order system and config type recognition), and a patch bump would misreport a release
  that adds a source system to the product surface.

## Source ranges

- Backend: `v1.3.0..HEAD` — https://github.com/drpn-ai/darpan/compare/v1.3.0...v1.4.0
- HotWax connector: `v0.6.0..HEAD` — https://github.com/drpn-ai/darpan-hotwax/compare/v0.6.0...v0.7.0
- UI: `v2.4.0..HEAD` — https://github.com/drpn-ai/darpan-ui/compare/v2.4.0...v2.5.0

## Backend

### Added

- `OMS_TRANSFER_ORDERS` connector registration — new `SourceSystemConnector` row and
  `DarpanSystemSource` enum, resolving to `extract#HotWaxOmsTransferOrders`. Carries its own
  `expectedSourceConfigType` (`HOTWAX_OMS_REST_TRANSFER`) and `extractServiceName` because both
  connector resolvers take the first enabled match on those attributes; two rows sharing either
  would resolve ambiguously and silently. No `pairLookupServiceName` — exchange pair verification is
  a sales-order concern. (`802efa1`, RQ-1/7/8/9)
- `AUT_WIN_STATE` window mode: enum row, `WINDOW_STATE` constant, `isStateWindowMode`, and
  `resolveWindows` dispatch. (`d328ef4`, RQ-11)
- Date parameters are omitted entirely for state-mode extracts rather than passed empty. (`ff6ee30`,
  RQ-12/13)
- Per-source status list for state-based extraction, carried on the new `extractStatusIds` field and
  passed to the parameter the connector names in `statusParameterName`. (`1ada3e4`, RQ-14)
- `AutomationFacadeSupport.validateStateModeSources` rejects a state-mode automation whose side's
  connector does not declare `supportsStateExtract`. Without it, one side gets a date window while
  the other does not, and every non-overlapping record reads as a genuine discrepancy at run time.
  Adds `SourceSystemConnectorSupport.resolveBySystemEnumId` — exact PK lookup, null for
  unresolvable/disabled, so the caller treats it as incapable rather than throwing. (`688a481`,
  RQ-15)
- `errorDetail` on `get#ReconciliationRunStatus`. The full text was already stored in a
  `text-very-long` column on the failure path; nothing exposed it. Contract regenerated,
  `methods.txt` unchanged at 90. (`d3170ec`)
- `ReconciliationRunStep` rows written at the five automation phase boundaries the heartbeat and
  cancel checkpoints already use: `RESOLVE`, `EXTRACT_FILE1`, `EXTRACT_FILE2`, `COMPARE`,
  `WRITE_OUTPUT`. `RunObservability.beginStep/endStep` are best-effort (own transaction, swallow
  every throwable), so this cannot change how an attempt is classified. (`012c7b2`)
- `productStoreId` added to `HOTWAX_OMS_ORDER_FIELD_OPTIONS`. Config-only: exclusion filters run
  before keep-field projection, so no extractor, query, keep-field, validation, or upgrade-data
  change was needed. Verified against real captured OMS extracts first — present as a top-level key
  on 100% of 482 records — because `SourceFilterSupport` matches top-level keys only and *keeps* a
  record lacking the field, so a wrong field name persists a rule that excludes nothing and reports
  no error. OMS only; `OMS_TRANSFER_ORDERS` does not share the list. (`4ff831e`, DAR-BE-017)

### Changed

- `executeAutomation` runs its whole body through
  `TransactionDetachSupport.runDetachedFromCallerTransaction`, so `run#AutomationNow` no longer
  joins the JSON-RPC request's ~60s JTA transaction. `scan#DueAutomations` is unaffected — the
  scheduler has no ambient transaction to suspend. (`0492039`)
- Transaction detach moved into `darpan.common` so the automation path can reuse it. (`b98911a`)
- `ReconciliationRunResult` is minted at `AUT_STAT_RUNNING` and its id carried in the *same*
  execution-row update, on both the API path (`f483b06`) and the SFTP path (`b709388`). Two updates
  would leave the gap the "Run now" poll falls into. Terminal paths reuse that row, so one execution
  attempt owns exactly one run-result row; `persistFailureRunResult` is now only the fallback for a
  failed mint.
- `resolveExtractKeepFields` unions rule-referenced fields with base/key fields instead of disabling
  projection whenever a rule set has any rule. Adds `ruleKeepFieldsForSide`. (`c2815c8`, RQ-18/19)
- `runSavedRunDiff.groovy` reads `windowFieldName` from the connector map and passes it to the
  extract service, matching `AutomationExecutionSupport.applyWindowFieldParameter`. Previously only
  the scheduled path honoured it, so an interactive run of the same saved run could query a
  different date field. (`c5e49d3`)
- `list#AutomationSourceOptions` filters `AUT_WIN_STATE` out of `relativeWindows`. The enum row,
  constant, predicate, and dispatch all stay intact — this hides the dropdown option only, because
  the per-source status list write path does not exist yet. Remove the filter when that surface
  ships. (`c5e49d3`)

### Fixed

- Cancel is honoured for automation runs on both input modes, ending the attempt `CANCELLED` on both
  the run-result and execution rows. Nothing previously read the flag the Cancel button sets: the
  run finished normally and reported SUCCESS. A cancel outranks an abort that merely looks like a
  failure, or it would be classified transient and requeue the run the operator just stopped.
  (`e8e4b90`)
- The API path's *non-throwing* FAILED terminal (`ruleExecutionFailed` is a flag on the reconcile
  result, not a throw) is now also outranked by a cancel. The window spans
  `requireReconcileOutput`, the artifact write, and `persistAutomationRunResult` — seconds of work
  on a large diff with the row still RUNNING and Cancel still on screen. Mutation-proven: disabling
  the guard fails exactly one test. (`88dacf6`)
- `heartbeatAutomationRun` at each phase boundary refreshes `lastHeartbeatDate`/`lastUpdatedDate` via
  `.update()`, which is what bumps Moqui's auto-maintained `lastUpdatedStamp` — the column
  `StuckRunReaper` actually reads. Minting at RUNNING newly exposed automation runs to that sweep.
  Never touches `statusEnumId` or `notifiedDate`. Residual: boundary heartbeat, not progress
  heartbeat. (`594edf3`)
- Notify-me subscriptions are carried across retries by `reassignRunSubscriptions` at the RUNNING
  mint (create-then-delete, since `reconciliationRunResultId` is part of the PK) instead of being
  purged at the transient close. The previous purge silently broke the dead-letter alert:
  `execution.reconciliationRunResultId` is never cleared across retries, so `reprocessDueRetries`
  notified against the row whose subscriptions had just been deleted. (`7e470af`)
- Subscriptions are purged on the two terminal paths that end a minted row *without* notifying
  (`NO_DATA` close, transient-failure close re-driven under a new row). Paths that do notify are
  untouched — they purge inside `notifyRunCompleted` after the claim is won, and hoisting a purge
  above them would delete the subscriber destinations the alert is about to resolve. (`a2937b5`)
- A terminal SFTP automation failure notifies through `notifyAutomationFailure`, matching the API
  path's payload shape. Every no-output SFTP terminal previously closed the row and deleted the
  subscriber without delivering anything. `NO_DATA` stays silent, matching the API path. (`d3050a8`)
- `unsubscribe#RunNotification` reports an error when it removed nothing instead of answering
  `ok=true` while a moved subscription went on firing. Nothing stored links an old run-result id to
  its successor, so the chain cannot be followed without new state; the service is made honest
  instead. Caller-scoped, parameters unchanged. (`e8e4b90`)
- All-clear verification runs stop reporting WITH ISSUES. Both passes emit one audit sentence on
  every run into the same `processingWarnings` list the notification header counts. Partitioned at
  the notification layer on each pass's audit-note prefix. Reported from prod 2026-08-05 (Gorjana API
  Order Sync: 0 differences, 0 missing, 0 pending, still flagged). (`a32887d`)
- `ruleKeepFieldsForSide` gates on the side's own `primaryIdExpression` record-array prefix before
  delegating to `topLevelRecordField`, which strips up to the first `[*]` unconditionally and so
  cannot tell a genuinely nested per-record wildcard from a safe `$.records[*].field` path. Both
  resolve to a plausible-but-wrong field name rather than null, which would have added a field
  matching no top-level key and made `trimRecord` drop the real nested field — fabricating a diff
  for every record. Also: a rule row naming neither side now disables projection rather than being
  skipped. (`8be40ac`, RQ-19 review)
- Two stale comments corrected in `AutomationFacadeSupport.groovy` and
  `ReconciliationSavedRunSupport.groovy`, left over from before rule-referenced fields were unioned
  into the keep-set. (`c5e49d3`)

### Security

- None. No auth, permission, tenant-scoping, or exposure change in this range.

## UI

### Added

- Transfer-order system and config type recognized in the source-system and configuration surfaces.
  (`0b7b3b6`, RQ-10)

### Changed

- "Run now" follows the started run into the live progress view. (`d96fe9d`)
- Automation schedules render in the viewer's preferred timezone. (`3842fd8`)
- A side that cannot carry exclusions explains why rather than silently doing nothing. (`c4d5559`)
- "Based On" renders as a label again, not display text. (`fd14d11`)

### Fixed

- The full `errorDetail` is displayed rather than the 255-char truncated copy, in a scrollable
  container. (`342bc69`)
- Error banners stay visible instead of auto-hiding after 10 seconds, which was invalidating "no
  error was shown" as evidence during triage. (`0c30954`)
- Typed exclusion text is committed when "Save exclusion" is clicked. (`ee7f590`)
- A typed primary id counts and is committed on wizard submit. (`1a2e744`)
- Tenant settings stop asserting values while they are still loading. (`3338e30`)
- `keydown` events carrying no `key` no longer crash the app shell. (`6f48a59`)
- Automation-dashboard review rounds 1 and 2: deterministic poll test, one-sided match window,
  status-line scope and a11y, and the unreadable-timestamp door in the match predicate.
  (`472b204`, `ce311de`)

### Security

- None.

## HotWax connector

### Added

- `extract#HotWaxOmsTransferOrders` service. (`90096cf`, RQ-2)
- Order-type-aware record filtering. (`7ae3b03`, RQ-3/4)
- Configurable window field, optional window as a whole, and server-side type/status filters.
  (`fcbfa9f`, RQ-5/6/7)
- `extractOptions` threaded through `extractOrders`/`extractOrdersToFile`/`extractOrdersInternal`,
  normalized once and passed raw down through `extractAllOrderPages`/`prepareOrdersPage`/
  `fetchOrdersPage` into `buildOrdersUrl` and `filterComparableOrderRecords`. A status-only extract
  needs no date bounds; a half-supplied window or a window-and-status-less request are both
  rejected. (`8f01964`, RQ-6)
- `DEFAULT_STATE_EXTRACT_MAX_RECORDS` (50000, overridable via `maxRecords`) caps a window-less
  extract's raw record count and fails with an error naming the limit rather than truncating — a
  short state extract would otherwise read exactly like records genuinely missing on that side.
  `requestMetadata.filters.stateExtract` is reported only for a window-less extract, absent (not
  empty) for a windowed one. (`e7afde2`, RQ-16/17)

### Fixed

- `normalizeExtractOptions` honours an explicit `maxRecords: 0`. Groovy's `?:` treats the boxed `0`
  as falsy, so it silently became the 50000 default — precisely the looks-like-success failure the
  ceiling exists to prevent. (`11907da`)
- Window guards branch on whether `windowStart`/`windowEnd` were *supplied*, not on whether they
  parsed. `parseWindowMillis`'s null return conflated "absent" with "unparseable", so a
  malformed-but-supplied bound was misreported as missing — and with both bounds malformed, the
  operator was wrongly told to add a status filter. (`71b86b9`)
- `buildOrdersQueryParams` extracted as the single derivation of the orders query, shared by
  `buildOrdersUrl` and `extractOrdersInternal`'s request metadata, so the reported query cannot
  drift from the one issued. Metadata was missing `statusId_op`. (`71b86b9`)
- `filterOrderTypeServerSide` treats a blank `orderTypeId` as unset. (`619b8e1`)

### Security

- None.

## Data and configuration

- Generic source data files under `runtime/component/darpan/data/*.xml` are the source of truth for seeded data.
- Candidate upgrade records are generated from generic source data diffs into `upgrade-data-review.md` and the current load target `runtime/component/darpan/data/upgrade-data.xml`.
- **Entity changes (additive, no migration).** `extractStatusIds` (`text-medium`) added to the
  automation source config; `windowFieldName` (`text-short`), `supportsStateExtract`
  (`text-indicator`, default `'N'`), and `statusParameterName` (`text-short`) added to
  `SourceSystemConnector`. Moqui adds the columns on startup. No new entities and no new entity
  groups, so no new tables. All four are narrow types, well clear of the MySQL row-size limit.
- **Upgrade data: three added records**, no modified records — `AUT_WIN_STATE` and
  `OMS_TRANSFER_ORDERS` Enumerations plus the transfer-order `SourceSystemConnector` row. Ordered
  parent-before-child by hand-check, not by trusting the generator's sort.
- **Version metadata bumped**: `darpan/component.xml` `1.3.0` to `1.4.0`,
  `darpan-hotwax/component.xml` `0.6.0` to `0.7.0`, `darpan-ui/package.json` and the two
  `package-lock.json` version fields `2.4.0` to `2.5.0`. The lock file was edited at those two keys
  only — a global replace would rewrite dependency versions that coincide with the app version.
- **API contract regenerated** from the bumped component version: `openapi.json` `info.version` only,
  `methods.txt` unchanged at 90 methods, `x-darpan-contract-version` stays `2` (additive change).
- No new one-time migration service in this release.

## Validation and rollout notes

- **Deploy order: backend, then UI.** The UI reads `errorDetail` and the transfer-order system and
  config type, none of which a `1.3.0` backend returns.
- **`1.3.0`'s upgrade data is a hard prerequisite.** This release's upgrade file is release-scoped
  and does not re-carry the `1.3.0` OMS `filterParameterName` record. An environment that skipped
  `1.3.0`'s load keeps exclusion filters inert while the UI presents them as working. Same for the
  `1.3.0` one-time backfill `migrate#AutomationExcludeFilters`, which is idempotent.
- **Backward compatibility.** Facade contract additive only, verified by
  `scripts/check_contract_compat.py` against `v1.3.0` (90 base methods preserved, version `2`).
  Entity changes additive. Upgrade records additive. Rollback to `1.3.0` needs no data revert.
- **`FacadeContractSnapshotTests` was red on `main`** before this pack was prepared — `errorDetail`
  reached `openapi.json` (which `ApiContractGeneratorTests` enforces) but not the committed facade
  snapshot (which nothing regenerates automatically). Regenerated and the diff reviewed line by line
  before acceptance: regenerating under `CONTRACT_SNAPSHOT_UPDATE=true` rewrites the whole snapshot
  from live service definitions, so an unreviewed regeneration would silently bless any other drift
  in the range. The accepted diff is one line in one service.
- **Suites must be re-run with `--rerun-tasks` or `cleanTest`.** A `component.xml` version bump is
  not a test input, so a plain `test` invocation reports `UP-TO-DATE` and executes nothing — a cache
  hit that reads as a passing suite.
- **Live verification is absent for this tag.** No run against a real OMS tenant, no browser pass
  over the release candidate, no deployed-environment smoke. The transfer-order connector has never
  resolved against a live source, and the state-extract ceiling has never fired against real data.

## References

- Keep a Changelog: https://keepachangelog.com/en/1.1.0/
- GitHub generated release notes: https://docs.github.com/en/repositories/releasing-projects-on-github/automatically-generated-release-notes
- Semantic Versioning: https://semver.org/
