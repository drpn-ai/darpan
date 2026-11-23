# Darpan 3.4.0

Release date: 2026-11-23 (the tag date is the cut date)

## Scope note

- A minor release on the 3.x line: everything on `main` in every repo since 3.3.2. Aditi, 2026-11-23: cut once
  DAR-BE-328 (peak periods and sync pulse) and the moqui-rules fact-conversion speed work were pushed, ahead of
  Gorjana's sale (starts 2026-11-24, Black Friday 2026-11-27). Prod go or no-go is decided after the UAT smoke.
- Headline: peak periods and the sync pulse (DAR-BE-328), and one Keep decision for every run, made in Spark after the
  read, with one filter store: automations read their run's filters (DAR-BE-318 plans 3b-2b and 3b-2c). moqui-rules
  0.2.0 joins the build and the prod image. Scripts become queries on moqui-jexl 0.2.2 (DAR-BE-334). Aditi added 3b-2c
  (11:25 IST), DAR-BE-337 (11:46 IST), DAR-BE-334 (11:57 IST) and DAR-UI-136 (12:27 IST) the same day.
- Facade contract moves to version 5 (Aditi, 12:18 IST): the 8 `facade.TenantScriptFacadeServices` methods are
  removed (queries replace scripts; public since 3.0.0), `save#Automation` sources no longer take `excludeFilters`
  (3b-2c), and `admin.MembershipAdminServices.add#TenantMember` answers `welcomeSent` and `welcomeWithPassword`
  instead of `welcomeQueued` (DAR-BE-325). 25 methods added (301 -> 318 in all). `check_contract_compat.py` 3.3.2 ->
  3.4.0 accepts the breaks under version 5. Darpan stays 3.4.0, not a major (Aditi, 12:20 IST): the next major is
  DAR-REL-006's.

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.3.2` | `v3.4.0` |
| `drpn-ai/moqui-rules` | no tags | `v0.2.0` (first tag; joins the build, CI and the prod image) |
| `drpn-ai/moqui-jexl` | `v0.2.0` | `v0.2.2` (queries' sandbox, read step, deliveries; tagged by the DAR-BE-334 session) |
| `drpn-ai/darpan-hotwax` | `v1.2.0` | `v1.3.0` (Keep lines as data, DAR-BE-318; R10 shipments, DAR-BE-329) |
| `drpn-ai/netsuite-darpan` | `v0.6.0` | `v0.7.0` (Keep lines as data, DAR-BE-318; R10 shipments, DAR-BE-329) |
| `drpn-ai/shopify-darpan` | `v0.10.0` | `v0.11.0` (Keep in Spark, DAR-BE-318) |
| `drpn-ai/darpan-agent` | `v0.4.0` | `v0.5.0` (audit read review, DAR-BE-320; the query-author agent, DAR-BE-334) |
| `drpn-ai/darpan-ui` | `v4.3.2` | `v4.4.0` |
| `drpn-ai/darpan-docker-config` | `v3.3.2` | `v3.4.0` (every product ref moves together, `MOQUI_RULES_REF` v0.2.0, `MOQUI_JEXL_REF` v0.2.2) |
| `drpn-ai/database-darpan`, `moqui-gql` | `v0.4.0`, `v1.1.0` | unchanged, no commits |
| `drpn-ai/darpan-docs`, `drpn-ai/darpan-developers` | no tags | `main` (Mintlify publishes on push) |

Every tag is cut from `main`; the exact commits are in `release-checklist.md`. darpan, darpan-hotwax,
netsuite-darpan, shopify-darpan and moqui-rules ship together: the system repos no longer filter at the read, and
core keeps what they used to drop. No image may carry the system commits without core 3.4.0.

## User-visible changes

For tenant users:

- Scripts are now Queries. Ask in your own words: the agent writes a query from what your audits keep (kept datasets,
  a saved run's newest result, an audit's findings), runs it, and shows the answer with a preview of each file before
  you save. Schedule a query to email its answer as CSV files and/or post a summary to a chat space. Only tenant admins
  schedule; a schedule sends as whoever saved it, and every emailed file is logged with its recipients (DAR-BE-334).
- Peak periods. Tenant Settings lists peak periods; during one, automations run at the period's run times, and a
  check's sync pair pulses every hour (DAR-BE-328).
- The sync pulse asks NetSuite only about orders it has not seen, posts what is late to the automation's chat space,
  and marks daily-check rows it caught first ("flagged by sync pulse HH:mm"). The pair page shows the period ("pulses
  every hour through 1 Dec") and stays editable during a period, with a note.
- Conclusions are edited on their own page (DAR-UI-135); a CLI session can save a run's conclusion tree, make a
  tenant cause code and sync an automation with its run (DAR-CLI-007).
- A found HotWax order, HotWax return or Shopify return shows its status in a run's expanded row (DAR-BE-336).
- Every run filters after the read, in one place, and reports what it set aside. A side whose records cannot be
  filtered fails with a message instead of comparing unfiltered (DAR-BE-318).
- An automation always uses its run's filters: change a filter on the run and the automation's next run uses it, with
  no sync. Sync copies the source setup only (DAR-BE-318).
- Cause conditions can test "is less than" one number (DAR-BE-327).
- An order NetSuite lacks counts as on its way for a day (DAR-BE-192).
- Lookups are a last resort: a run looks up missing records only when few are missing, and the run update has a
  reads line (DAR-BE-320).
- After a sign-in with a two-factor code, the app offers to add a passkey (DAR-UI-132).
- A check that tests no statement is skipped instead of run (DAR-BE-332).
- A run no longer warns about keys on more than one record when a conclusion rule hides every repeated-key row; a
  repeat no rule explains still warns (DAR-BE-337).
- The tenant welcome email carries the temporary password (DAR-BE-325).
- Motion follows one rule: tokens, eased entrances, popovers that open from their trigger (DAR-UI-134); a success
  pulse and icon crossfade, every transition on the motion tokens, guarded by a spec (DAR-UI-136).

## Operator-visible changes

### Keep in Spark (DAR-BE-318 plan 3b-2b)

- Extractors no longer filter at the read. Stored source filters and each connector's built-in drops are applied by
  one Keep decision in Spark, for interactive runs, automations, retries, replays and evaluate runs alike.
- Built-in drops are declared as Keep lines (`SourceSystemConnector.keepLinesJson`), seeded by each system repo's own
  `darpan-seed` file (darpan-hotwax, netsuite-darpan, shopify-darpan). Without them every Spark-kept side fails with
  "Keep lines (keepLinesJson) are not loaded". The prod entrypoint loads every `darpan-seed` file at boot.
- Prod pre-deploy checks (read-only, 2026-11-23): no filter row without a tenant, no side that will fail permanently,
  no `sourceFilters` in automation metadata, every stored flag a JSON boolean, no kept copy in use.
- Rollback is the whole set: core and the three system repos together, never one alone.

### One filter store (DAR-BE-318 plan 3b-2c)

- Every automation run reads its run's (compare scope's) filters. The automation's own copy
  (`ReconciliationAutomationSourceFilter`) is retired: migration `AUTOMATION_FILTER_TABLE` drops the table (80 rows on
  prod, none without a tenant; dry run supported), and the old `AUTOMATION_FILTERS` migration is switched off by a
  hand row in the pack (the registry seed no longer states `enabled`, DAR-BE-319).
- Sync, the automation editor and `darpan automation sync` no longer copy or compare automation filters; the agent's
  exclusion lever writes the run's filter only. An old bundle or config version whose automation filters differ from
  its run's is refused by name (`AUTOMATION_FILTERS_DIFFER`).
- Scope changes at the deploy: MALBON automation 100513 starts excluding product 142036 (x-redo), Aditi's choice; the
  prod drift check found no other drifted automation (Gorjana, RAILS, Dolls Kill, Mephisto, New Era Caps unchanged).
- Until `run#PendingMigrations` has run, saving, syncing or deleting an automation that still has old filter rows
  fails on the old foreign key; scheduled runs are unaffected.

### Queries on moqui-jexl (DAR-BE-334)

- Images clone moqui-jexl (`MOQUI_JEXL_REF=v0.2.2`); darpan 3.4.0 declares `depends-on moqui-jexl` and does not start
  without it. darpan's own `commons-jexl3` and the `darpan.script` code are gone.
- Migration `TENANT_SCRIPTS_TO_QUERIES` (sequence 150, after `AUTOMATION_FILTER_TABLE`; no dry run, idempotent) copies
  every tenant script to a query and every deployment to a schedule switched OFF (owner = the last or creating user;
  RUN_RESULT scripts off with a note), and deletes the old `scan_TenantScriptDeployments_5m` job. The `darpan.script`
  tables and their filters stay until it has run on prod and SM; a later release drops them.
- moqui-jexl's seed (security groups, the query email template) rides as `darpan-seed` (`MoquiJexlSeedData.xml`, with a
  drift guard), like moqui-rules'.
- Set `jexl_email_from` on prod and SM. moqui-jexl's scheduler checks every 60 seconds; with
  `scheduled_job_check_time=0` it does not start unless `jexl_deployment_check_seconds` is set.
- QUERY results may be up to 8,000,000 characters / 400,000 values (about 20,000 rows x 20 columns); results are never
  stored and attachments stay capped.

### moqui-rules 0.2.0

- moqui-rules is cloned and built in the image. Its entities are `rules.*` (moved from `moqui.rules.*`); prod never
  ran the old names, so the tables are created fresh.
- Facts are converted in bulk (`convert#Facts`), statically compiled, about 1.7-1.8x the old per-pair cost against a
  3x budget (it was about 24x). darpan's judge path builds facts statically and no longer throws per pair.
- Its `RuleSeedData.xml` is type `seed`, which the prod entrypoint does not load, so this release's upgrade pack
  carries its 27 rows verbatim (fact value, node and operator enums, condition and action types). Without them every
  fact is refused.

### Peak periods and sync pulse (DAR-BE-328)

- New tables: PeakPeriod, PeakPeriodRunTime, SyncPair, PeakPeriodSync, SyncPulseSample, SyncPulseOrder,
  SyncPulsePresentKey (created at boot). Each is tenant-filtered and in `DARPAN_APP`.
- Job `run_SyncPulse` is seeded running. The first pulse per pair after the deploy does one full read; later pulses
  read only unseen orders, and remember them across restarts.
- `run_DataRetention_daily` keeps sync pulse samples to 30 days and sweeps present keys. It is seeded paused: confirm
  it runs in prod.

### Judge and datasets

- Judge enums (`JudgeOutcomeKind`, `CheckDecisionStage` and their values) and the judge config tables are tenant
  filtered. New audit columns (`claimRuleSetId`, `claimOutcomeId`, `causeRuleSetId`, `causeRuleId`,
  `AuditCheck.pairFactTypeId`) are added at boot.
- Datasets (kept copies): tables and statuses ship; job `scan_DueDatasets_30m` ships paused. No kept copy is live.

### Other

- The first extract purge after the deploy deletes every tenant's interactive extracts and captured lookup answers
  older than `darpan.purge.extract.days` (7).
- Mail carries a Message-ID on the sending domain (DAR-BE-323).
- Users created before the deploy who still need a password: `user reset-password`, then `member welcome`
  (DAR-BE-325).
- The test suite gains a shared-Moqui pool (DAR-OPS-026); no runtime change.

## Upgrade data

- Load with the deploy: `data/upgrade-data.xml` (type `darpan-seed`) is loaded at boot by the prod entrypoint with
  every other `darpan-seed` file. Targeted load: `./gradlew loadDarpanUpgradeData`.
- 2,421 records: 27 moqui-rules seed rows, 29 tenant filters (`DARPAN_ACTIVE_COMPANY_SCOPE`) and their 29
  `DARPAN_APP` members, judge, dataset and sync pulse enums, 3 jobs, connector rows (DAR-BE-336 status fields), email
  copy (DAR-BE-325 password block), the regenerated mascot content, and the two migration rows (`AUTOMATION_FILTERS`
  switched off by hand, `AUTOMATION_FILTER_TABLE` and `TENANT_SCRIPTS_TO_QUERIES` added), moqui-jexl's seed rows, the
  3 `jexl.script.*` tenant filters, the query grants and the `DARPAN_QUERY_RESULT` email template.
- After the deploy, before the scheduler resumes: `keepLinesJson` is non-blank on OMS_GQL, OMS_RETURNS_GQL and
  NETSUITE_SUITEQL, and `SOURCE_SYSTEM_CONNECTOR.KEEP_LINES_JSON` and `DATASET.KEPT_FIELDS_JSON` are LONGTEXT.

## Deploy order

1. Optional safety, read-only: dump `RECONCILIATION_AUTOMATION_SOURCE_FILTER` (80 rows) before the deploy.
2. Backend image (all product refs at their 3.4.0-line tags) with the upgrade data.
3. Set `jexl_email_from`, then `run#PendingMigrations` straight away: `AUTOMATION_FILTER_TABLE` drops the retired
   table (dry run first is fine), then `TENANT_SCRIPTS_TO_QUERIES` copies scripts to queries (no dry run).
4. The two checks above, then confirm `run_DataRetention_daily` runs.
5. darpan-ui 4.4.0.
6. darpan CLI 0.2.4 (DAR-CLI-007 and 3b-2c's sync preview), only after the backend: the server refuses its new
   commands until then, and an older CLI's sync preview would report filters "lost on sync".

## Verification

- Contract and upgrade-data tripwires at the release commit, in a clean clone: ApiContractGeneratorTests,
  FacadeContractSnapshotTests, TenantOwnedEntityFilterContractTests (`PENDING_NEXT_PACK` emptied: 3.4.0 ships all 29
  rows), AuditCatalogContractTests, AutomationEntityContractTests, ConclusionEntityContractTests,
  RunFlowchartSchemaContractTests, ScheduleJobDefaultsSmokeTests, SourceFilterEntityContractTests,
  MoquiRulesSeedDataMirrorTests.
- Item-level proof is on each backlog item: DAR-BE-328 whole-branch review and 23 classes / 225 focused tests against
  darpan `ef1443de` + moqui-rules `869227b`; moqui-rules final review clean at `47a0322` (probe ratio 1.72 / 1.81
  under a 3.00 budget); DAR-BE-336 contract tests.
- Prod pre-deploy checks (read-only, 2026-11-23 10:31-10:35 IST): see Keep in Spark above.
- Owed: the UAT smoke on the deployed image (a run with a Spark side that has a tenant filter and a built-in line:
  passes, `.keep.json` written, kept count below records read, side file present on a Y connector), then the prod
  checks after the deploy and one filtered saved run, one scheduled compare and one evaluate automation per tenant.

## Deferred items

- DAR-BE-334 / DAR-BE-335 (scripts as user queries on moqui-jexl): branch `dar-be-334`, next release.
- Optional later: remove the orphan EntityFilter row `DARPAN_SCOPE_AUTOMATION_SRC_FILTER`.
- DAR-BE-334 follow-ups: an admin notice when a schedule switches off (OWNER_LOST); the run-result source re-parsing
  its file per page; narrowing DARPAN_USER's moqui-jexl schedule grant; dataset and audit ids in a read are not
  re-pointed on transfer (they fail closed). Drop the `darpan.script` tables after the migration ran on prod and SM.

## Rollback or fallback notes

- Rollback past 3.4.0 after `AUTOMATION_FILTER_TABLE` has run: an older image reads the recreated, empty automation
  filter table and its backfill row is disabled, so every automation would run UNFILTERED. Before such a rollback,
  re-enable `AUTOMATION_FILTERS` and run it with force on the old image (it re-seeds from each run's filters), or
  restore the dumped rows.
- Roll back the whole backend set together: darpan, darpan-hotwax, netsuite-darpan, shopify-darpan, moqui-rules
  (`deploy.sh prod --tag <previous image>`, the 3.3.2 image). Never one system repo alone: a 3.3.2 extractor with a
  3.4.0 core, or the reverse, filters twice or not at all.
- Queries: a rollback leaves the new `jexl.script.*` tables unused and the old script tables untouched, but the
  migration deleted the old scan job, so old deployments stay idle until a seed load re-creates it.
- The new tables and columns stay after a rollback (3.3.2 ignores them); the 3.4.0 seed rows are inert under 3.3.2.
- If only the sync pulse misbehaves during the sale: pause `run_SyncPulse` (`darpan-admin --target prod --yes job pause run_SyncPulse`)
  with no deploy; peak-period run times keep working.
