# Technical Changelog For Darpan 2.0.0

## Versioning decision

| Repo | From | To | Call |
| --- | --- | --- | --- |
| `drpn-ai/darpan` | `v1.5.1` | `v2.0.0` | **Major** (requested, and supported by the breaking changes below) |
| `drpn-ai/darpan-ui` | `v2.6.1` | `v3.0.0` | **Major** |
| `drpn-ai/darpan-hotwax` | `v0.8.0` | `v0.9.0` | **Minor** (0.x: a minor bump signals a break) |
| `drpn-ai/shopify-darpan` | `v0.6.1` | `v0.7.0` | **Minor** (0.x) |
| `drpn-ai/netsuite-darpan` | `v0.2.0` | `v0.3.0` | **Minor** (0.x) |
| `drpn-ai/database-darpan` | `v0.2.0` | `v0.2.1` | **Patch** |
| `drpn-ai/darpan-docker-config` | `v1.5.1` | `v2.0.0` | Matches the darpan tag, as every release since `v1.1.0` |

**darpan: major.** The facade contract is additive (`check_contract_compat.py v1.5.1 -> HEAD`: 30
methods added, all 110 preserved, contract version stays `3`), so the major is not a contract
break. It is a break at three seams the contract gate cannot see: the `/rpc/json` method fence
(DAR-BE-078) refuses every method outside `facade.*` / `admin.*` / `gql.*`; the run step codes and
sequence numbers change (DAR-BE-029, DAR-UI-044, DAR-UI-048); and the in-JVM API the connectors
compile against changes (`SourceFilterSupport.firstMatchingRule` deleted, DAR-BE-054). The release
also changes what a run *is* (flowcharts, single-sided runs, lifecycles), which is the user-facing
reason for the number.

**darpan-ui: major.** It hard-depends on the 2.0 backend: `src/` calls 25 facade methods that do
not exist in darpan `v1.5.1`, and `2b6ca08` renders the new stage codes. It also retires
workflows operators had learned: the Ask Darpan pill, the user menu and the floating Home/Account
buttons are gone (sign out and theme are `/logout`, `/light`, `/dark`), Create Run starts with
Sync or Lifecycle, the separate flowchart runs list redirects to Home, and the zone code `v2.6.1`
appended to every timestamp moves to a hover. No method the app called at `v2.6.1` was dropped.

**Connectors: minor, not major.** All three are pre-1.0, where a minor bump is the SemVer signal
for a breaking change, and each gains capabilities (new extract services) rather than changing
existing ones: across the three, 0 services removed or renamed, and the two refactors (`acc1479`,
`dc4d431`) add only `unresolvedIds` out-parameters and the `sourceFilters` in-parameter. They are
breaking only in their pairing with darpan (they call `SourceFilterSupport.evaluate` and
`ExcludedRecordsSidecar`, which `v1.5.1` lacks). Promoting any of them to `1.0.0` would declare a
stable API, which is a product decision this cut does not take.

**database-darpan: patch.** `9f18549` moves four list/delete services from Groovy to XML actions
with identical names, parameters, `authenticate` and `allow-remote`; every helper it calls already
exists in darpan `v1.5.1`. It is re-cut so the prod image carries the explicit tenant condition.

`netsuite-darpan`'s `v0.2.0` tag shipped with `component.xml` still saying `0.1.0`; this cut sets
it to `0.3.0`.

## Source ranges

| Repo | Range | Commits |
| --- | --- | --- |
| `drpn-ai/darpan` | `v1.5.1..b473ecf` | 89 |
| `drpn-ai/darpan-ui` | `v2.6.1..46539b4` | 108 |
| `drpn-ai/darpan-hotwax` | `v0.8.0..a9aac48` | 26 |
| `drpn-ai/shopify-darpan` | `v0.6.1..822b155` | 19 |
| `drpn-ai/netsuite-darpan` | `v0.2.0..586bffe` | 14 |
| `drpn-ai/database-darpan` | `v0.2.0..7908b9e` | 2 |
| `drpn-ai/darpan-docker-config` | `v1.5.1..92c496c` | 1 |

Every commit in every range is accounted for below (checked by grepping each hash).

## Breaking changes

1. **`/rpc/json` dispatches only `facade.*`, `admin.*` and `gql.*`; `/rest/login` is closed** (DAR-BE-078, `a6e41ce`, `6de5b08`).
   - What: `RpcMethodFence` runs first in the webroot before-request hook (`MoquiConf.xml`). It checks the top-level
     method and every batch entry, and any other method gets an `ArtifactAuthorizationException` (HTTP 403).
     `/rest/login` (plus any trailing segments) is refused. `/rest/s1/*`, including `/rest/s1/graphql`, is still open.
   - Who: any integration calling a stock Moqui service (`org.moqui.impl.*`) or a non-facade Darpan service over
     JSON-RPC, and anything that logs in through `/rest/login`. Checked: darpan-ui, darpan-admin-ui and darpan-cli
     call nothing outside the three prefixes (the only `reconciliation.*` string in darpan-ui is test fixture data).
     No Darpan component exposes a non-facade `allow-remote="true"` service.
   - Action: move callers to the published method list (`docs/api-contract/methods.txt`, 140 methods) and log in with
     `facade.AuthFacadeServices`. After deploy, run the anonymous probe on each environment:
     `org.moqui.impl.UserServices.update#Password` must answer 403.
2. **Connector builds must move with darpan: `SourceFilterSupport.firstMatchingRule` is deleted** (DAR-BE-054, `9278d85`).
   - What: the method is removed outright, and callers now read `SourceFilterSupport.evaluate()`.
   - Who: `darpan-hotwax v0.8.0` calls it at `OmsRestSourceSupport.groovy:1651` and `OmsReturnsSourceSupport.groovy:186`.
     `shopify-darpan v0.6.1` calls it at `ShopifyReturnRefsSupport.groovy:251`. On darpan 2.0.0 those filtered extracts
     fail at runtime. `netsuite-darpan v0.2.0` and `database-darpan v0.2.0` do not call it.
   - Action: deploy darpan-hotwax with `9c8d00a`/`d1e097b` and shopify-darpan with `21391af` or later (the planned
     v0.9.0 / v0.7.0). See Cross-component pairing for the full set.
3. **Run step codes and sequence numbers changed** (DAR-BE-029 `9aa6d27`, DAR-UI-044 `0032267`, DAR-UI-048 `8012094`).
   - What: new runs no longer enter `VERIFY`. Each pass writes its own code: `VERIFY_MISSING` (5), `VERIFY_EXCHANGE` (6)
     or `VERIFY_RETURNS` (7). `CONCLUDE` is new at 8. `WRITE_OUTPUT` moves 6 -> 9 and `NOTIFY` moves 7 -> 10. A new
     terminal status, `AUT_STAT_NOT_RUN`, is used for flowchart questions under a failed one. Old rows keep their old
     codes, and sorting only happens within one run, so history does not reorder. The contract check cannot see this
     because it is a change of values, not types.
   - Who: a darpan-ui build older than `2b6ca08` renders the raw codes. Any API consumer that matches `stageCode` or
     `stageSequence` is also affected. darpan-ui main's `RUN_STAGE_SEQUENCE` (`src/lib/reconciliationDisplay.ts`)
     matches the backend map (index + 1).
   - Action: deploy darpan-ui main BEFORE or WITH this backend. Load the upgrade data, because `ReconciliationRunResult.statusId`
     FKs `AUT_STAT_NOT_RUN` and flowchart runs fail without that row.
4. **Tenant filter on 13 more entities hides rows with a NULL tenant** (DAR-BE-076, `f112c78`).
   - What: the upgrade data adds `DARPAN_ACTIVE_COMPANY_SCOPE` EntityFilter rows (`filterMap="[companyUserGroupId: activeTenantUserGroupId]"`)
     on: DatabaseSourceConfig, DatabaseSourceQuery, NsSuiteQlSourceQuery, Reconciliation, ReconciliationRun,
     ReconciliationAutomationSourceFilter, ReconciliationRunStep, SlackOAuthState, SlackWorkspaceInstall,
     SourceConfigEndpointAccess, RuleSetCompareSourceFilter, RuleSetConclusionRule, RuleSetConclusionCondition.
   - Who: any authz-enabled read of those entities. Rows with a NULL `companyUserGroupId` disappear from it. The
     upgrade-data header warns that loading these rows on a pre-`f112c78` build breaks three things: shared-config
     endpoint decisions fail open, run steps written without a tenant stop heartbeating, and `save#RuleSetRun`
     fails with 23505.
   - Action: deploy darpan (and database-darpan `9f18549`+) BEFORE or WITH the data load. Run the NULL counts in the
     `upgrade-data.xml` header first. If the filter or scope count is non-zero, run
     `reconciliation.ReconciliationRuleEngineServices.sweep#RuleChildTenantStamps`. (The gorjana tenant-config files
     stamp every row on these entities; checked.)
5. **The Shopify return-status pill was withdrawn and replaced, and its values changed** (DAR-BE-026, `b13f51f`).
   - What: `$.records[*].returnWorkflowStatus` (1.5.1, Shopify `Return.status`) is replaced by `orderReturnStatus`
     (`Order.returnStatus`: `IN_PROGRESS`, `INSPECTION_COMPLETE`, `NO_RETURN`, `RETURN_FAILED`, `RETURN_REQUESTED`,
     `RETURNED`). Both old paths are now in `RETIRED_FIELDS`. The new field is order-wide, so excluding `IN_PROGRESS`
     also drops that order's refund rows.
   - Who: any exclusion rule saved on the 1.5.1 pill. After the load it matches nothing and reports `excludedCount 0`.
   - Action: once per environment, after the load, run
     `admin.MigrationAdminServices.run#Migration migrationId=RETIRED_FIELDS force=true`. Then re-draw the rule on
     "Return status" with `IN_PROGRESS` (it was `REQUESTED,OPEN`). This needs shopify-darpan `69dbe96`+.
6. **Saved source filters on Shopify orders start applying** (DAR-BE-063, `fa9c554`).
   - What: the `SHOPIFY` connector gains `filterParameterName="sourceFilters"`. Source-filter rows that were already
     saved against a Shopify-orders source, and silently ignored until now, begin removing records after the load.
   - Who: any environment with such rows. Their counts move with no change to the run.
   - Action: run the pre-load `RULE_SET_COMPARE_SOURCE_FILTER` query in the upgrade-data header (it needs its join
     completed). This needs shopify-darpan `7350a97`+. Older builds keep ignoring the filters.
7. **Run window boundaries are now ISO instants and carry `timeZone`** (DAR-UI-026, `e7b82a4`).
   - What: `dateRange.start`/`end` in `get#GeneratedOutput` and `get#ReconciliationRunStatus` used to be
     `Timestamp.toString()` (JVM zone, unmarked, e.g. `2026-09-01 17:00:00.0`). They are now `Instant.toString()`
     (e.g. `2026-09-01T11:30:00Z`), with a new nullable `timeZone`.
   - Who: API consumers that parse the old string. darpan-ui is paired with this change. Other consumers: UNVERIFIED.
   - Action: parse ISO-8601. Treat a missing `timeZone` as "not recorded".
8. **Low risk, internal only: three services deleted** (`0ae5b85`).
   - What: `ReconciliationJsonServices.run#JsonReconciliation`, `ReconciliationMixedServices.reconcile#MixedFiles`
     (whole file) and `JsonSchemaServices.update#JsonSchemaText` are gone.
   - Who: none of the three was `allow-remote`, and none of the five components calls them (grepped). Only an
     out-of-tree component calling them in-JVM would break.
   - Action: use `facade.*.save#JsonSchemaText`.

Checked and dropped (not breaking):
- DAR-BE-060/061/072 (disable/archive): `RuleSet.isActive` and `isArchived` read absent as enabled and not archived,
  `delete#SavedRun` is still present and unchanged (the Ruleset Manager still calls it for Sync runs).
- DAR-BE-063 "every filtering connector must declare its sidecar": this is a test-time contract
  (`FilteringConnectorSidecarContractTests`), not a runtime check.
- DAR-BE-044 `NETSUITE_ORDERS` -> `NETSUITE_SUITEQL`: `NETSUITE_ORDERS` never shipped in a v1.5.1 seed, and it stays
  as a `systemAliases` entry.
- DAR-BE-049 `scopeMode`: null reads as `COMPARE`.
- DAR-BE-053 blank `queryTemplate`: reads as `RECORDS`.
- The facade contract is additive: 110 -> 140 methods, `x-darpan-contract-version` stays 3.

## Backend — drpn-ai/darpan

### Added
- Reconciliation flowcharts (DAR-UI-048). A run's questions form a parent-first tree, walked per window by
  `run#Reconciliation` and the new `reconciliation` automation type. Parent keys narrow FILE_1 at prepare. Facade:
  save/get/run flowchart, `list#Reconciliations`, soft `delete#ReconciliationQuestion`, `list#ReconciliationExecutions`
  and `import#NetSuiteChecksAsRuns` (existing NetSuite checks become runs under a START, and a repeat import adds only
  new checks). The internal walker services are `anonymous-all` but `allow-remote="false"`. The final review pass
  covers scheduled walks, refusal rows, isolated failures, stored no/unasked counts, and a mid-walk tenant switch.
  `8012094` `728fae7` `4f7be56` `cbdcba4` `4012bdf` `dcabf4b` `e973e64` `0f3bbf0` `27bd38f` `1c2f927` `85c9a24` `8962dc9` `268d218` `9f7cb40`
- Lifecycle checks (DAR-UI-050, DAR-BE-073). `save#Lifecycle` compiles a start record and its stages into a question
  tree in which every record lands in exactly one named place. Also new:
  - leave-out rules, stage exceptions (`waiveRules`), an EXCUSED track, written findings (`matchRules`),
    `notFindingPlaces`, record details, and reorder/rebuild;
  - `itemsBackordered`;
  - a lifecycle walk reads the window once (`RunSnapshotScope`; switch off with `-Ddarpan.reconciliation.runSnapshot=off`);
  - `stageReach` for stages linked by a shared field rather than `nexttransactionlink`.
  `df1411b` `9cf1fe9` `2bf3e76` `fb54e20` `5236fa2`
- Run conclusions (DAR-UI-044, DAR-BE-063). Every finding carries a named conclusion and its evidence, from a
  per-run Drools tree (`ConclusionTreeEngine`, own ReleaseId, shared watchdog). The flat evaluator is kept as its
  parity oracle. Also:
  - a new `CONCLUDE` stage and an `UNEXPLAINED` catch-all;
  - `SourceFilterSupport.applyToRecords`, one filter-and-sidecar loop for every extractor, writing the
    `ExcludedRecordsSidecar`;
  - per-side, opt-in duplicate-key findings that count as differences;
  - Shopify order fields on the rules board;
  - gorjana's conclusion catalogue and the Shopify-fulfilled vs NetSuite-billed run, shipped as tenant config.
  `0032267` `d9ac584` `5419c36` `a4bc632` `c903d81` `3e091b3` `09f6482` `c025cc4` `fa9c554` `1a68903` `7ced4ff` `944c4c0` `be3716b`
- Single-sided EVALUATE runs (DAR-BE-049, DAR-BE-058). `RuleSetCompareScope.scopeMode` (null = COMPARE) and
  `evaluate#RuleSetCompareScope`: one source, and its rows are the findings, in the missing-diff row shape. There is
  one early fork in the interactive path, and `EXTRACT_FILE2` is never opened. `create#RuleSetRun` takes `scopeMode`,
  and the wizard source picker lists NetSuite checks. An unknown mode, or a FILE_2 source on an EVALUATE scope, is refused.
  `5b62f79` `5a48142` `c9c9aa3` `02b9fb0` `7bd6847` `c9721e6` `31d9dba`
- NetSuite SuiteQL connector (DAR-BE-044/053/045/057/062). One `NETSUITE_SUITEQL` row, with the population held in
  per-side `NsSuiteQlSourceQuery` / `NsSuiteQlSourceQueryField`. Also new:
  - vetted query templates and status-aware fields (`disallowedStates`, `requireParentRecordType`, `minAmount`, ...);
  - `lineKeyColumn`;
  - the missing `NETSUITE_SUITEQL` enum row (`b470281`; before it, no compare source could be created);
  - `windowDateOnly`, so date-granular connectors get the local calendar date (set on `NETSUITE_SUITEQL` only).
  `0f1657e` `9b87d0d` `b470281` `7bd6847`
- Tri-system order runs (DAR-BE-062). `OMS_ORDER_ITEMS` connector: the exchange exclusion is off, and items without
  a Shopify line id are kept. NetSuite line keys. gorjana tri-system rule sets, and a status check for HotWax completed
  vs NetSuite billed and cancelled vs closed (`GORJANA_OMS_NS_CANCEL_RS`). `47e5785` `fb6a4a1`
- Order-line unit presence (DAR-BE-050). `SHOPIFY_ORDER_LINE_UNITS` and `OMS_ORDER_LINE_UNITS` endpoints and
  connectors, so a quantity shortfall on a line is visible. There is no point lookup, by design. gorjana rule set
  as tenant config. `f890e38` `4b1ec6a` `067149c` `eea1920`
- OMS over GraphQL (DAR-BE-064/040/068). `OMS_GQL` order-grain endpoint ("HotWax orders and payments" after DAR-BE-069; remote
  `HOTWAX_ORDERS_API`) with its pills and Shopify `hasPaymentTransaction`. `OMS_RETURNS_GQL` mirrors `OMS_RETURNS`
  pill for pill, so moving a run is a source switch. A filter-only "Order product store" pill. `4cb8901` `ffcacb4` `e452cc0` `c354634` `73e64ab`
- Include-mode source filters (DAR-BE-054). An `INCLUDE_IN` operator, and an `evaluate()` verdict that says why a
  record was rejected. A record with no value is rejected. `b3664f1` `514abc1` `9278d85`
- Retire without deleting (DAR-BE-060/061/072). `disable#`/`enable#SavedRun` (`RuleSet.isActive`), and
  `archive#`/`restore#` for runs and automations (`isArchived`). Both execution paths refuse a disabled or archived
  run, with different messages. Archived rows leave `list#SavedRuns` unless `includeArchived` is set, and an archived
  automation is no longer scheduled. `931b7de` `c8206cf` `5f82233`
- Instance-wide schedule board: `admin.ScheduleAdminServices.get#ScheduleBoard`, super-admin only, with projected
  fires snapped to the 5-minute scan tick. `9475f47`
- Run window time zone: `ReconciliationRunResult.windowTimeZone`, with boundaries sent as instants (DAR-UI-026). `e7b82a4`

### Changed
- Shopify return exclusion now works as typed: `orderReturnStatus` replaces `returnWorkflowStatus` (DAR-BE-026).
  The forced `RETIRED_FIELDS` re-run is owed. `b13f51f`
- Returns verify pass (DAR-BE-036). A failed lookup chunk strands only its own ids (`unresolvedIds`, surfaced as
  `unresolvedCount` plus a warning), rather than voiding the pass. `SHOPIFY_RETURN_REFS.lookupMaxIds` goes 10000 -> 50000. `e5cb878`
- One stage code per verification pass (DAR-BE-029). `9aa6d27`
- Labels (DAR-BE-069/071): 31 short pill labels, and source names that say what they return. The returns ID card
  offers only "Shopify return ID" and "Refund or return ID". Display and picker only. `8f6bff3` `e3e4bd9` `506da4b`
- `save#TenantChatSpace` refuses to change the chat product of an existing space (DAR-UI-022). `8fdeda4`

### Fixed
- A weekly automation no longer fires twice when its window crosses a month boundary. The month cut applies only
  above `splitWindowDays` (default 28), which is now actually read (DAR-BE-042). `d763aa1`
- The run-notification streak is scoped to one automation, not to every automation sharing a saved run (DAR-BE-043). `00589f4`
- The return-presence pass also runs on GraphQL returns runs (`isOmsReturnsSystem`, DAR-BE-040). `c354634`
- `save#Rule` accepts `<` and `>` in `ruleText`, `ruleLogic` and `expression` (DAR-BE-065). `cb12dcd`

### Security
- `/rpc/json` method fence, and `/rest/login` closed (DAR-BE-078). `a6e41ce` `6de5b08`
- Every tenant-owned entity is under the tenant filter. The three callers that would have broken were fixed first (DAR-BE-076). `f112c78`
- A tenant can no longer point an automation at another tenant's chat space (`findOneOwnedByTenant`), and
  `TenantPinBesidePrimaryKeyGuardTest` now guards against it (DAR-BE-077). `09fa81b`
- `save#SftpServer` no longer overwrites and re-owns another tenant's server (DAR-BE-079). `e038176`
- The authz ratchet now counts service XML and all five components, with exact per-component baselines (DAR-BE-075). `b7ef5a3`
- `TenantNotificationSetting.googleChatWebhookUrl` keeps `encrypt="true"`, and the code now documents it as the
  decoder for migrated rows. No behaviour change (DAR-BE-030). `ddfa5d4`

### Removed
- `SourceFilterSupport.firstMatchingRule` (`9278d85`; see Breaking 2).
- `run#JsonReconciliation`, `reconcile#MixedFiles`, `update#JsonSchemaText` (`0ae5b85`).
- New runs no longer enter the `VERIFY` stage code; the constant stays for old rows (`9aa6d27`).

### Housekeeping
`118caf8` (comment indent), `460f0ef` (interactive EXTRACT lifted into `RunExtractSupport`, no behaviour change),
`2533293` (upgrade-data rebuild + contract snapshot), `45bed7a` (refactor), `ff346be` (DAR-BE-074 admin/migration
services as XML actions, parameters unchanged), `96f2b5b` and `2d03122` (contract regeneration), `9043526` (CI
clones database-darpan), `a7a9649` (OWASP dependency-check 13.0.0, scanning the drpn-ai framework fork), `b473ecf`
(DAR-DOCS-002 docs).


## UI — drpn-ai/darpan-ui


### Added

- **Run flowchart (DAR-UI-048).** A run can now be a chart of yes/no questions: build it, run it,
  watch each question finish, open a finding's records, and browse past executions with their
  summary. Lifecycle tiles are not pinnable yet: `save#DashboardPinnedSavedRuns` validates RuleSet ids
  only. Commits: `e40e2bd`, `d8dbc16`, `d49106b`, `b45cdd2`, `16356f5`, `2aa6899`, `8d6794b`,
  `3833e09`, `fc1460a`, `500c2c6`, `3dc8706` (rebuilt on the design system), `5991582`, `51b2dba`,
  `96ca4c1`, `0d4581f`.
- **Import NetSuite Checks on Home (DAR-UI-048)** — `bcfb810`. Shown to editors whose tenant has a
  one-source `NETSUITE_SUITEQL` run. It reports each skipped check's reason from the backend.
- **Two run types, Sync and Lifecycle (DAR-UI-049)** — `1e1fb7e`: one Create Run, a type badge on
  every Home tile.
- **Lifecycle builder (DAR-UI-050).** Four dropdowns: account, records, what happens next, late
  after. Every option is read from the NetSuite account with its evidence (counts, share, typical
  days). There is a Where Records Stopped board, a settings spine with leave-out rules and stage
  exceptions, findings defined per place, How It Works, and delete-as-archive. Commits: `92e6c6f`,
  `3b7fdca`, `0a7c018`, `b82e7c1`, `012084e`, `eeb5bfb`.
- **Conclusions on the run result page (DAR-UI-044)** — `fcea29b`, `41dc44d`. Conclusion tiles,
  expand-in-place evidence limited to the fields the winning rule tested, and "stop flagging this"
  pre-filling the rules board.
- **Filter mode: "only these values" beside "exclude these" (DAR-BE-054)** — `e939cfa`, `084255d`,
  `611760b`, `8724b37`. Pills read `!=` / `=`, and a new filter always opens on Exclude.
- **Single-sided runs (DAR-BE-049 / DAR-BE-058)** — `aeee103`, `0168cdf`. The result page, history
  tile and gear handle one source. `0168cdf` added a "Compare two systems / Check one system" wizard step; DAR-UI-054 (`35da4e4`) removed it again, so no app screen creates a one-system run in this release (API only).
- **The mascot (DAR-UI-017, DAR-UI-019..035).** A red panda replaces the Ask Darpan pill. It is
  still the launcher, and it now explains whatever you rest on: labels, headings, timestamps, every
  button and icon link, and the run result page. It offers one idle hint per stretch, only for
  controls actually on the page. Answers come from darpan-docs, and voice guards cover all three
  registries. Commits: `c06ad1e`, `e3eaee5`, `cfb85d3`, `0456f51`, `a7ac939`, `00096f9`,
  `2772292`, `a13ef4e`, `906693e`, `edcb947`, `c336422`, `7d31bd1`, `af2a86f`, `514d498`,
  `a9719cf`, `64758e2`, `69cd26c`, `8a81b47`, `0dea884` (019 lashes), `9fb2eaa`, `b6c4968` (020
  blink), `f5fe51d` (027), `f40e616` (028), `ae3f0f6` (029), `c77c19c`, `0732815`, `8120a3e` (030,
  35 of 39 routes), `8a98560`, `2b05521` (032), `c06b40f`, `09c90a7` (035).
- **Slash commands `/logout`, `/light`, `/dark` (DAR-UI-017)** — `033b2c5`.
- **Mascot warning channel (DAR-UI-039).** A warning makes the mascot jump once and hold an alerted
  posture. Clicking opens a popup on the shared popup pattern, one warning at a time; Close and
  Dismiss are different acts. The automation edit page raises drift, deleted-run and
  inactive-chat-space warnings through it. Commits: `1816fea`, `c6871e1`, `e90193f`, `00be8ee`,
  `b87f530`, `3c7437d`, `148affd`, `b9fba33`, `79f7e54`, `a00da3a`, `a13f384`, `f204f7a`,
  `4376157`, `8b31507`.
- **Correctable automation timezone (DAR-UI-036)** — `5f726c7`. The zone is an AppSelect, and an
  untouched form sends back the zone it loaded.
- **Run Details back to Run Editor (DAR-UI-038)** — `210f0df`.
- **Run workflow list + settings actions (DAR-UI-040)** — `9dd8ab3`.

### Changed

- **Pages draw their last visit at once, then refresh (DAR-UI-051)** — `1492532`, `2452373`,
  `a1e0c0c`. Reads are cached, and every write drops the whole cache. Reads are prefetched on
  sign-in and on 80 ms link intent. Wizards, edit forms and chat spaces are deliberately uncached.
- **Home groups runs by system family (DAR-UI-043)** — `702c799`. Page size goes 12 -> 100, and each
  heading carries a count.
- **Run windows are shown in the zone they were anchored in (DAR-UI-025/026/033)** — `c683bd8`,
  `de26ef9`, `c56b5cf`, `587ef4d`. The mascot gives the exact instants a run used, and zone-less
  stamps are never shifted into the viewer's zone.
- **Timestamps no longer carry a zone code; hover names the zone** — `e3eaee5`. This reverses
  1.5.1's `59b12aa` behaviour.
- **Timeline names each verification pass (DAR-BE-029)** — `2b6ca08`. Needs the darpan 2.0 stage codes.
- **Automation trash archives instead of deleting (DAR-BE-060 -> DAR-BE-061)** — `8a75187` removed it,
  `382e0c8` restored it on `archive#Automation`.
- **Sync never asks the run kind twice (DAR-UI-054); A/B keys work on "What kind of run?"
  (DAR-UI-055); run settings name each side by its system (DAR-UI-053)** — `35da4e4`, `7413fb3`,
  `9e13d9d`.
- **Sentence-case source names ("HotWax orders", "HotWax transfer orders") (DAR-BE-069)** — `0063af3`.
- **The automation wizard's webhook URL is no longer masked (DAR-UI-021)** — `b1b71de`. The
  provider card is gone from chat-space edit (DAR-UI-022) — `90c0bff`.

### Fixed

- `10b3173` (DAR-UI-037) — Create new run always starts at card one. Before, it opened wearing the
  last saved run's answers and could save a duplicate through `createRuleSetRun`.
- `453aa6c` (DAR-UI-031) — a page of failures no longer hides the only control that fetches older
  results.
- `449e744` (DAR-UI-047) — Escape on a rules-board popover closes the popover, not the whole draft.
- `f285a19` (DAR-UI-041) — the launcher scrolls to the row it highlights when Data and Navigate
  both show.
- `aeee103` — a leaked 5 s poll timer in the automation dashboard spec, which made the suite flaky.

### Removed

- The Ask Darpan pill, the Home/Account FABs and the user menu (`c06ad1e`, `c336422`, CSS `31ee8df`).
  Unprefixed sign-out/theme launcher actions are gone too (`b80c43f`).
- The appended timezone code on timestamps (`e3eaee5`), the help cursor (`2b05521`), and the
  flowchart Runs list (`1e1fb7e`).

### Housekeeping

`8937bdf` (knip dead code), `3334d8c` (dedupe), `31ee8df` (dead CSS; App.spec fix), `46539b4`
(DAR-DOCS-002 docs), `f8c478c` and `900aa2b` (merge commits, PRs #45/#46).

## Backend connectors

### `drpn-ai/darpan-hotwax` (`v0.8.0..HEAD`, 26 commits)

#### Added

- **OMS order-line units (DAR-BE-050)** — `f404a06`, `83fdb18`, `e220a5a`. There is one record per
  OMS unit, keyed `(shopifyOrderId, shopifyLineId, unitOrdinal)`, with ordinals following
  `orderItemSeqId`. Dropped units become a run warning, because they are lost rather than excluded.
  `83fdb18` also fixes the page consumer counting orders where it wrote units.
- **OMS order-item units for NetSuite hops, plus the excluded-records sidecar (DAR-BE-062 /
  DAR-UI-044)** — `12783cc`: `extract#HotWaxOmsOrderItems`. The exchange exclusion is off, and
  null-Shopify-id items are kept and counted.
- **OMS orders over GraphQL (DAR-BE-064)** — `7685b71`, `77b9c6f`, `43c0394`, `5a389f6`, `c7d7db0`,
  `a4f6eb3`, `174e443`, `b00a661`, `fce002d`, `ea6bb91`, `48d43a8`: `extract#HotWaxOmsGqlOrders`
  with a cost-bucket governor and THROTTLED retry. Unsupported filters fail loudly, and THROTTLED
  fails the extract, never empties it.
- **OMS returns over GraphQL (DAR-BE-040)** — `856edc4`: `extract#HotWaxOmsGqlReturns`. Live on
  gorjana it returned 120 records against REST's 110, with all 110 identical, plus 10 pending
  returns. It took 258 s against 17 s.
- **The returns GraphQL record carries the origin order's product store (DAR-BE-068)** — `d71af28`.
  It is a filter-only field.

#### Changed

- **Include-mode filters (DAR-BE-054)** — `9c8d00a`, `d1e097b`. The orders and returns getters
  read the filter verdict, and field-absent drops are counted apart in `fieldAbsentCount`.

#### Fixed

- `0a666b4` (DAR-BE-040) — pending OMS returns are found by GraphQL in the by-id lookup. Before,
  10/10 pending returns read as confirmed missing. Unchecked ids now come back as `unresolvedIds`,
  which is declared as an out-parameter.
- `960e91f` (DAR-BE-067) — the GraphQL returns lookup stops on a server error and reports it once.
- `12486ee` (DAR-BE-070) — the returns lookup retries 429/502/503/504 after 60 s, up to 3 times.

#### Housekeeping

`63c578e` and `6c5edcb` (test and comment only), `acc1479` (script consolidation; 27
characterization tests; effective parameters re-verified unchanged), `a9aac48` (DAR-DOCS-002).

### `drpn-ai/shopify-darpan` (`v0.6.1..HEAD`, 19 commits)

#### Added

- **Shopify order-line units (DAR-BE-050)** — `ae2ab13`, `7f4fb4c`, `a11fa15`, `d57b8d5`:
  `extract#ShopifyOrderLineUnits`, a bulk source with an opt-in for connection fields, numeric
  legacy ids, and orphan/zero-quantity counts that are always reported.
- **Unit state, channel and the excluded-records sidecar (DAR-BE-062 / DAR-UI-044)** — `2f2f840`.
- **The orders extract filters per order and carries `sourceName` + `requiresShipping` (DAR-BE-063)** —
  `2cae6ec`, `7350a97`. It adds the in-param `sourceFilters` to `extract#ShopifyOrders`.
- **`hasPaymentTransaction`, plus a canary when no order in a non-empty extract is paid
  (DAR-BE-064)** — `07a6fea`, `a2d14eb`.

#### Changed

- **The return status is now Shopify's own `Order.returnStatus` (`orderReturnStatus`), replacing
  `returnWorkflowStatus` (DAR-BE-026)** — `69dbe96`. Excluding `IN_PROGRESS` now also drops that
  order's refund rows. This is deliberate. Live check: 50 of 346 rows dropped, exactly the
  in-progress ones.
- **Returns are discovered from `return_created` / `refund_created` events, not an order
  `updated_at` net (DAR-BE-037)** — `862cf42`. The probe reached 96.7% of the population, against
  33.4% before. Event retention caps windows at 1 year, with a warning past that.
- **Include-mode filters on return refs (DAR-BE-054)** — `21391af`.
- **Catalog labels "Shopify returns" and "Shopify order lines" (DAR-BE-069)** — `3b363d2`.

#### Fixed

- `bc7883f` (DAR-BE-027) — CLOSED returns with no refund are no longer reported missing in OMS. The
  suppression count is always present.
- `5d01db8` (DAR-BE-036) — a failed lookup chunk strands only its own ids, returned as
  `unresolvedIds`.
- `ddb681e` (DAR-BE-040) — `lookup#ShopifyRefundOrReturnIds` declares `unresolvedIds`, so the key
  survives the service boundary.

#### Housekeeping

`eb8e339` (comments), `dc4d431` (consolidation; 28 golden-file tests; effective parameters
re-verified), `822b155` (DAR-DOCS-002).

### `drpn-ai/netsuite-darpan` (`v0.2.0..HEAD`, 14 commits)

#### Added

- **SuiteQL order extraction driven by per-side config (DAR-BE-032/044)** — `ef71607`:
  `extract#NetSuiteOrders`, with an OAuth2 M2M minter and identifier-validated fragments.
- **Vetted query templates (DAR-BE-053)** — `cef4d4e`: `RECORDS`, `LINKED_CHILD_ABSENT`,
  `LINKED_CHILD_PRESENT_ABSENT`. It also fixes an injection through `recordType`
  (`ItemShip' OR '1'='1`).
- **Status-aware checks (DAR-BE-045)** — `dd8d446`: `disallowedStates`, `requireParentRecordType`,
  and an amount floor. The gorjana config is client-config, not seed.
- **Create a NetSuite check through the API (DAR-BE-058; no darpan-ui screen calls it yet)** — `a2abb2f`, `f5e63cb`:
  `save/get/list#NsSuiteQlSourceQuery`, validated by the extractor's own `normalizeSpec`. The list is
  paged, each row carries its owner, and save accepts `lineKeyColumn`.
- **SALES_LINES line keys and the excluded-records sidecar (DAR-BE-062 / DAR-UI-044)** — `4c4f7d9`,
  `561f2e0` (`quantityBilled`, one `lineState` per line).
- **Lifecycle backend (DAR-UI-050)** — `0cf6fdf` (`get#LifecycleOptions`), `d59a26b`
  (`LINKED_CHILD_PRESENT`, stage exceptions, `get#LifecycleRuleOptions`), `a74645c`
  (`LINKED_CHILD_EXCUSED`, `LINE_SUM`), `2165d3c` (`RECORDS_MATCHING`, finding options).

#### Changed

- **The getter reads the filter verdict and reports per-rule counts (DAR-BE-054)** — `754c1a3`.
- **Lifecycle questions are answered from one read of the window; stage reach by field
  (DAR-BE-073)** — `545b0c3`. Live gorjana parity was 9/9 questions, with the walk going from 97.7 s
  to 32.7 s.

#### Housekeeping

`586bffe` (DAR-DOCS-002; inventory docs archived).

### `drpn-ai/database-darpan` (`v0.2.0..HEAD`, 2 commits)

#### Changed

- `9f18549` (DAR-BE-074) — the four list/delete facade services move from Groovy scripts to XML
  actions with the same contract. Lists carry an explicit tenant condition, deletes keep their exact
  "not available in your active tenant" message and orphan guard, and there are 16 new service tests.

#### Housekeeping

`7908b9e` (DAR-DOCS-002 README).

## Cross-component pairing
Every one of these extract services is ABSENT at the connector's current tag and PRESENT at its HEAD (checked with
`git grep` on the tag and on HEAD):
- darpan-hotwax (v0.8.0 -> HEAD `a9aac48`, 26 commits):
  - `OMS_GQL` -> `extract#HotWaxOmsGqlOrders`;
  - `OMS_RETURNS_GQL` -> `extract#HotWaxOmsGqlReturns`;
  - `OMS_ORDER_LINE_UNITS` -> `extract#HotWaxOmsOrderLineUnits`;
  - `OMS_ORDER_ITEMS` -> `extract#HotWaxOmsOrderItems`.
  It also needs the `firstMatchingRule` -> `evaluate()` migration (`9c8d00a`, `d1e097b`). HEAD in turn needs darpan
  2.0.0: it calls `ExcludedRecordsSidecar` and `SourceFilterSupport.evaluate`. `lookup#HotWaxOmsReturnsByExternalId`
  exists at v0.8.0 but gains the GraphQL pending-return path in `0a666b4` (DAR-BE-040).
- shopify-darpan (v0.6.1 -> HEAD `822b155`, 19 commits):
  - `SHOPIFY_ORDER_LINE_UNITS` -> `extract#ShopifyOrderLineUnits`;
  - `SHOPIFY.filterParameterName=sourceFilters` needs `extract#ShopifyOrders` to declare `sourceFilters` (absent at
    v0.6.1, present at HEAD, `7350a97`);
  - the `orderReturnStatus` pill and `keepFieldsBase` need `69dbe96`;
  - the new SHOPIFY pills (`hasPaymentTransaction` `07a6fea`; `sourceName`, `requiresShipping` `7350a97`) and
    `unresolvedIds` from `lookup#ShopifyRefundOrReturnIds` (`ddb681e`, honoured by darpan `e5cb878`).
  It also needs the `evaluate()` migration (`21391af`). HEAD needs darpan 2.0.0 (`applyToRecords`, `ExcludedRecordsSidecar`, `evaluate`).
- netsuite-darpan (v0.2.0 -> HEAD `586bffe`, 14 commits): `NETSUITE_SUITEQL` -> `extract#NetSuiteOrders` (absent at
  v0.2.0). The `NsSuiteQlSourceQuery*` entities, which netsuite-darpan reads in 5 files, are defined in darpan,
  including `queryTemplate`, `lineKeyColumn`, `waiveRules`, `matchRules` and `stageReach`. HEAD uses darpan's
  `RunSnapshotScope`, `ExcludedRecordsSidecar` and `evaluate`. The lifecycle and NetSuite-check facade services
  (`get#LifecycleOptions`, paged `list#NsSuiteQlSourceQueries`) are in darpan's generated contract.
- database-darpan (v0.2.0 -> HEAD `7908b9e`, 2 commits): `9f18549` (DAR-BE-074) puts an explicit tenant condition
  next to the new DAR-BE-076 filter on `DatabaseSourceConfig`/`Query`. darpan's `DisableAuthzRatchetTest` pins
  database-darpan at 10 sites, and darpan tests read its entities, which is why CI now clones it (`9043526`).
- darpan-ui (`v2.6.1..46539b4`, 108 commits): calls 25 facade methods absent from darpan `v1.5.1`
  (two of them in netsuite-darpan), and `2b6ca08` needs the new stage codes. It ships as `v3.0.0` with
  this backend.
- Rollback: darpan 2.0.0 with any connector at its old tag is the broken combination (`firstMatchingRule`, missing
  extract services). Connector HEADs on darpan 1.5.1 do not compile against it (new darpan classes).


## Data and configuration

- `data/upgrade-data.xml`: an 80-record delta on 1.5.1, rebuilt 2026-10-03 and re-checked for this
  cut against the full `v1.5.1..b473ecf` seed diff (a scratch-clone `release_preflight.py generate`
  candidate of 188 records). All 80 non-tenant-config records owed are present and identical,
  child content included, and nothing in the file is outside that diff. The other 108 candidates
  are the four `darpan-tenant-config` files, excluded by design; the 28 conclusion enumerations
  among them belong to those files. Hand-ordered parent-first; `release_preflight.py` sorts
  alphabetically and must not be used to regenerate it.
- Header updated for this cut: version and coverage range, companion builds named by tag, and the
  pre-load NULL-stamp counts widened from 5 tables to 15 (all 13 newly filtered entities plus
  `RULE_SET_COMPARE_SCOPE` and `RULE_SET_COMPARE_SOURCE_KEY_FIELD`).
- `data/releases/1.5.1/upgrade-data.xml` confirmed byte-identical to `git show v1.5.1:data/upgrade-data.xml`,
  so the delta's baseline is the real tagged file. It was not touched.
- No connector repo carries seed data changes in its range; database-darpan's
  `DatabaseConnectorSeedData.xml` is unchanged.
- Test fix: `SourceFilterEntityContractTests.neitherTableShipsSeedOrUpgradeRows` had been red on
  `main` since `f112c78`. It rejected any mention of the two source-filter entity names in
  `upgrade-data.xml`, and DAR-BE-076's `EntityFilter` rows correctly name both in `entityName`.
  The test now matches row elements only; negative-checked against a real row element.

## Validation and rollout notes

See `release-checklist.md` for what ran. Rollout order, per environment:

1. Build the prod image from `darpan-docker-config v2.0.0` (pins all five components at their
   2.0.0-set tags) and deploy it with darpan-ui `v3.0.0`.
2. Run the pre-load counts in the `upgrade-data.xml` header; sweep if needed.
3. `./gradlew loadDarpanUpgradeData`.
4. `admin.MigrationAdminServices.run#Migration migrationId=RETIRED_FIELDS force=true`, then
   `run#PendingMigrations`.
5. Probe: an anonymous `org.moqui.impl.UserServices.update#Password` over `/rpc/json` answers 403.
6. Browser smoke on the flowchart, lifecycle and conclusions paths.

prod's UI already runs a build past `v2.6.1` (`a1e0c0c`) against a `1.5.x` backend, so lifecycle,
flowchart, archive and include-filter saves fail on prod until this backend deploys. UAT and
sm-darpan run an older UI and are unaffected until both move.

## References

- https://github.com/drpn-ai/darpan/compare/v1.5.1...v2.0.0
- https://github.com/drpn-ai/darpan-ui/compare/v2.6.1...v3.0.0
- https://github.com/drpn-ai/darpan-hotwax/compare/v0.8.0...v0.9.0
- https://github.com/drpn-ai/shopify-darpan/compare/v0.6.1...v0.7.0
- https://github.com/drpn-ai/netsuite-darpan/compare/v0.2.0...v0.3.0
- https://github.com/drpn-ai/database-darpan/compare/v0.2.0...v0.2.1
- https://github.com/drpn-ai/darpan-docker-config/compare/v1.5.1...v2.0.0
- Facade contract: `docs/api-contract/openapi.json`, `docs/api-contract/methods.txt` (140 methods)
