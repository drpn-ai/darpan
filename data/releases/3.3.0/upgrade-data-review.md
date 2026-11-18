# Upgrade Data Review For Darpan 3.3.0

## Scope

- Backend compare range: `v3.2.1..HEAD` (HEAD `6cc1a6ac`), data directory `data`.
- Generated with `release_preflight.py generate --version 3.3.0 --backend-previous-ref v3.2.1 --previous-version 3.2.1`
  in a clean `git worktree add --detach` of darpan HEAD, never the working copy: the working copy of
  `data/upgrade-data.xml` carries another session's uncommitted DAR-ADMIN-012 rows and an untracked seed file for
  them. The generated file holds none of them (a case-insensitive grep for that feature's name finds 0 lines).
- DAR-BE-315 (`dc80d9e9`) landed after this generation and changes no `data/` file, so the seed diff and this file
  are unchanged (validate passes against `v3.2.1..dc80d9e9`). The controller re-runs it on the final head.
- Generic source data files are the source of truth; no record here is hand-written.

## Candidate records

1,948 records, each copied verbatim from a `darpan-seed` file:

| Source file | Change | Records |
| --- | --- | --- |
| `data/AppContentSeedData.xml` (DAR-BE-313) | added | 500 `AppContentEntry`, 1 `AppContentSeed` (`CURRENT`, mark `87a4819f84fdffa7`), 54 `AppRoute`, 1,289 `AppRouteAnchor` |
| `data/AuditSeedData.xml` (DAR-BE-305) | modified | 1 `ServiceJob` (`send_AuditDigests_15m`, defaults only) |
| `data/EmailCopySeedData.xml` (DAR-BE-303) | added | 91 `EmailCopyBlock` (`defaultText` only) |
| `data/MigrationRegistrySeedData.xml` (DAR-BE-306) | added | 1 `DarpanMigration` (`RETIRED_JOB_PARAMETERS`) |
| `data/ReconciliationJobSeedData.xml` (DAR-BE-305/306) | modified | 6 `ServiceJob` (defaults only), 1 `ServiceJobParameter` (`scan_ReconciliationAutomations_5m` `limit`, `defaultValue` only) |
| `data/ScriptJobSeedData.xml`, `TenantConfigSeedData.xml`, `TwoFactorSeedData.xml` (DAR-BE-305) | modified | 1 `ServiceJob` each (defaults only) |
| `data/zzScheduleJobDefaultsApply.xml` (DAR-BE-305) | added | the `schedule.ScheduleServices.apply-JobDefaults` call |

Release tooling: the first generate under-counted silently (500 entries came out as 69, 1,289 anchors as 813),
because `make_identity` keyed `AppContentEntry` and `AppRouteAnchor` only on attributes ending in "id". Fixed in
darpan-workflows `release_preflight.py` (adds `contentKind`, `contentKey`, `routeName` to the key candidates) and
regenerated; the counts now equal the seed file's.

The records outside the mascot seed and the email copy, as loaded (the tail of the file):

```xml
    <!-- Added records from data/MigrationRegistrySeedData.xml -->
    <darpan.migration.DarpanMigration migrationId="RETIRED_JOB_PARAMETERS" componentName="darpan" serviceName="retention.RetentionMigrations.move#PurgeJobRetentionParameters" sequenceNum="130" supportsDryRun="Y" enabled="Y" description="Move each nightly purge job's edited retentionDays parameter into its darpan.purge.* setting and delete                 the row, and delete the stuck-run reaper job's retired staleMinutes parameter (DAR-BE-306, DAR-BE-304). Seed loads                 never delete, and a live parameter row would keep overriding the setting. Idempotent."/>
    <!-- Modified records from data/ReconciliationJobSeedData.xml -->
    <moqui.service.job.ServiceJob jobName="purge_ReconciliationExtractFiles_daily" description="Purge raw source extract files older than the extract retention setting (never result files)." serviceName="reconciliation.ReconciliationGenericServices.purge#ExtractFiles" defaultCronExpression="0 15 2 * * ?" defaultPaused="N" minIntervalMinutes="360"/>
    <moqui.service.job.ServiceJob jobName="purge_ReconciliationGeneratedFiles_daily" description="Purge generated reconciliation output files older than the generated-output retention setting." serviceName="reconciliation.ReconciliationGenericServices.purge#GeneratedOutputFiles" defaultCronExpression="0 0 2 * * ?" defaultPaused="N" minIntervalMinutes="360"/>
    <moqui.service.job.ServiceJob jobName="run_DataRetention_daily" description="Delete audit findings, run results, AI transcripts and agent query logs older than their configured retention periods." serviceName="retention.RetentionServices.run#Retention" defaultCronExpression="0 30 3 * * ?" defaultPaused="Y" minIntervalMinutes="360" resumePreviewService="retention.RetentionServices.dryRun#Retention"/>
    <moqui.service.job.ServiceJob jobName="scan_ReconciliationAutomations_5m" description="Scan due reconciliation automations and execute scheduled windows." serviceName="reconciliation.ReconciliationAutomationServices.scan#DueAutomations" defaultCronExpression="0 0/5 * * * ?" defaultPaused="N" minIntervalMinutes="1"/>
    <moqui.service.job.ServiceJob jobName="sweep_RuleChildTenantStamps_hourly" description="Backfill companyUserGroupId on Rule/RuleSetCompareScope/RuleSetCompareSource from the parent RuleSet." serviceName="reconciliation.ReconciliationRuleEngineServices.sweep#RuleChildTenantStamps" defaultCronExpression="0 15 * * * ?" defaultPaused="N" minIntervalMinutes="15"/>
    <moqui.service.job.ServiceJob jobName="sweep_StuckReconciliationRuns_10m" description="Reap reconciliation runs / automation executions wedged in RUNNING after crash." serviceName="reconciliation.ReconciliationAutomationServices.sweep#StuckReconciliationRuns" defaultCronExpression="0 0/10 * * * ?" defaultPaused="N" minIntervalMinutes="5"/>
    <!-- ORDERING NOTE (3.3.0, by hand after generate): the generator sorts ServiceJobParameter before ServiceJob; the
         parameter row follows its parent job here (FK relationship type="one"). Do not re-sort. -->
    <moqui.service.job.ServiceJobParameter jobName="scan_ReconciliationAutomations_5m" parameterName="limit" defaultValue="100"/>
    <!-- Modified records from data/ScriptJobSeedData.xml -->
    <moqui.service.job.ServiceJob jobName="scan_TenantScriptDeployments_5m" description="Run tenant script deployments that are due and deliver their text." serviceName="script.TenantScriptServices.scan#DueScriptDeployments" defaultCronExpression="0 0/5 * * * ?" defaultPaused="N" minIntervalMinutes="1"/>
    <!-- Modified records from data/TenantConfigSeedData.xml -->
    <moqui.service.job.ServiceJob jobName="snapshot_PendingTenantConfigs_1m" description="Keep a config version for each tenant whose audit config changed and has been quiet for a minute." serviceName="config.TenantConfigServices.snapshot#PendingTenantConfigs" defaultCronExpression="0 * * * * ?" defaultPaused="N" minIntervalMinutes="1"/>
    <!-- Modified records from data/TwoFactorSeedData.xml -->
    <moqui.service.job.ServiceJob jobName="send_TwoFactorReminders_daily" description="Remind everyone two-factor is required for, who has not set up a method, on the 7/3/1/0-days-before then weekly cadence." serviceName="auth.TwoFactorServices.send#TwoFactorReminders" defaultCronExpression="0 0 15 * * ?" defaultPaused="N" minIntervalMinutes="1440"/>
    <!-- Added records from data/zzScheduleJobDefaultsApply.xml -->
    <schedule.ScheduleServices.apply-JobDefaults/>
```

## Review findings

- Parent before child: the generator sorted the `ServiceJobParameter` row before its `ServiceJob`
  (`relationship type="one"`); it was moved below its parent by hand, with an ORDERING NOTE comment. No other record
  references a row added here: `AppContentEntities.xml` declares no relationships, and `EmailCopyBlock` is
  `one-nofk` to `EmailTemplate`, whose rows already exist.
- The apply call is the last element of the file, after every job row, so `loadDarpanUpgradeData` alone applies the
  defaults. On boot, `upgrade-data.xml` sorts before `zzScheduleJobDefaultsApply.xml`, so the apply runs again last
  (idempotent; `ScheduleJobDefaultsSmokeTests.applyFileLoadsLast` guards the name).
- No job row carries `cronExpression`, `paused` or `parameterValue`: a load never overwrites a prod edit, and never
  resumes a job that deletes data.
- Deletions are not upgrade data: the retired `retentionDays` and `staleMinutes` parameter rows are removed by the
  `RETIRED_JOB_PARAMETERS` migration, run by an operator after deploy.

## What the generated pack adds beyond the boot load

Nothing new for prod or UAT. Every record above is in a `darpan-seed` file, and every top-level `darpan-seed` file
loads on each boot with `DARPAN_LOAD_UPGRADE_DATA` unset or `Y`, so the first start of the 3.3.0 image loads all of
it, plus the new tables and columns (entity add-missing). The pack is the record of what changed, and the load path
for an environment that does not load `darpan-seed` on boot: there, run `./gradlew loadDarpanUpgradeData` once. Since
the file also loads on boot, those 1,948 rows are written twice per boot until the next release replaces it
(create-or-update, same values).

## Recommended operator review

- Load path: first start of the new image with `DARPAN_LOAD_UPGRADE_DATA` unset or `Y`; otherwise
  `./gradlew loadDarpanUpgradeData` once.
- After boot: `AppContentSeed` `CURRENT` = `87a4819f84fdffa7`, 500 `AppContentEntry` rows; the Schedule page lists the
  10 Darpan jobs with their shipped crons; then run `RETIRED_JOB_PARAMETERS` (dry run, then real).
- Previous current upgrade file archived: `data/releases/3.2.1/upgrade-data.xml`, byte-identical to `v3.2.1`'s
  `data/upgrade-data.xml` (unchanged by this cut).
- Never replay a pre-3.3 release pack: their job rows state `cronExpression` and `paused`.
