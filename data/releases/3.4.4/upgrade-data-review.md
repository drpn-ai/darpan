# Upgrade Data Review For Darpan 3.4.4

## Scope

- Backend compare range: `v3.4.3..HEAD`
- Data directory reviewed: `data`
- Generic source data files are the source of truth for release upgrade data.
- This report lists candidate seed/config records that were added or modified in generic source data files between the compared refs.
- Do not author records directly in `upgrade-data.xml`; add or update the appropriate `runtime/component/darpan/data/*.xml` file and regenerate.

## Candidate records

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.EnumerationType|enumTypeId=AuditPriority`
- Element: `moqui.basic.EnumerationType`

```xml
<moqui.basic.EnumerationType enumTypeId="AuditPriority" description="Audit priority"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDP_KNOWN|enumTypeId=AuditPriority|enumCode=KNOWN`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDP_KNOWN" enumTypeId="AuditPriority" enumCode="KNOWN" description="Known: understood, tracked" sequenceNum="4"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDP_P0|enumTypeId=AuditPriority|enumCode=P0`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDP_P0" enumTypeId="AuditPriority" enumCode="P0" description="P0: fix now" sequenceNum="1"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDP_P1|enumTypeId=AuditPriority|enumCode=P1`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDP_P1" enumTypeId="AuditPriority" enumCode="P1" description="P1: fix soon" sequenceNum="2"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDP_P2|enumTypeId=AuditPriority|enumCode=P2`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDP_P2" enumTypeId="AuditPriority" enumCode="P2" description="P2: fix when convenient" sequenceNum="3"/>
```

### Modified in `data/AuditSeedData.xml`

- Record: `moqui.service.job.ServiceJob|jobName=send_AuditDigests_15m`
- Element: `moqui.service.job.ServiceJob`

```xml
<moqui.service.job.ServiceJob jobName="send_AuditDigests_15m" expireLockTime="360" description="Send the audit run digests that are due." serviceName="audit.AuditDigestServices.send#DueAuditDigests" defaultCronExpression="0 0/15 * * * ?" defaultPaused="N" minIntervalMinutes="5"/>
```

### Added in `data/EmailCopySeedData.xml`

- Record: `darpan.email.EmailCopyBlock|emailTemplateId=DARPAN_AUDIT_RUN|blockId=cause.explain`
- Element: `darpan.email.EmailCopyBlock`

```xml
<darpan.email.EmailCopyBlock emailTemplateId="DARPAN_AUDIT_RUN" blockId="cause.explain" defaultText="Know why? Explain them to the agent"/>
```

### Modified in `data/ReconciliationJobSeedData.xml`

- Record: `moqui.service.job.ServiceJob|jobName=purge_ReconciliationExtractFiles_daily`
- Element: `moqui.service.job.ServiceJob`

```xml
<moqui.service.job.ServiceJob jobName="purge_ReconciliationExtractFiles_daily" expireLockTime="360" description="Purge raw source extract files older than the extract retention setting (never result files)." serviceName="reconciliation.ReconciliationGenericServices.purge#ExtractFiles" defaultCronExpression="0 15 2 * * ?" defaultPaused="N" minIntervalMinutes="360"/>
```

### Modified in `data/ReconciliationJobSeedData.xml`

- Record: `moqui.service.job.ServiceJob|jobName=purge_ReconciliationGeneratedFiles_daily`
- Element: `moqui.service.job.ServiceJob`

```xml
<moqui.service.job.ServiceJob jobName="purge_ReconciliationGeneratedFiles_daily" expireLockTime="360" description="Purge generated reconciliation output files older than the generated-output retention setting." serviceName="reconciliation.ReconciliationGenericServices.purge#GeneratedOutputFiles" defaultCronExpression="0 0 2 * * ?" defaultPaused="N" minIntervalMinutes="360"/>
```

### Modified in `data/ReconciliationJobSeedData.xml`

- Record: `moqui.service.job.ServiceJob|jobName=run_DataRetention_daily`
- Element: `moqui.service.job.ServiceJob`

```xml
<moqui.service.job.ServiceJob jobName="run_DataRetention_daily" expireLockTime="360" description="Delete audit findings, run results, AI transcripts, agent query logs and sync pulse samples older than their configured retention periods." serviceName="retention.RetentionServices.run#Retention" defaultCronExpression="0 30 3 * * ?" defaultPaused="Y" minIntervalMinutes="360" resumePreviewService="retention.RetentionServices.dryRun#Retention"/>
```

### Modified in `data/ReconciliationJobSeedData.xml`

- Record: `moqui.service.job.ServiceJob|jobName=run_SyncPulse`
- Element: `moqui.service.job.ServiceJob`

```xml
<moqui.service.job.ServiceJob jobName="run_SyncPulse" expireLockTime="360" description="Sample every due sync pair: orders waiting on the other system and orders past their allowance." serviceName="pulse.SyncPulseServices.run#DuePulses" defaultCronExpression="30 2/5 * * * ?" defaultPaused="N" minIntervalMinutes="1"/>
```

### Modified in `data/ReconciliationJobSeedData.xml`

- Record: `moqui.service.job.ServiceJob|jobName=scan_DueDatasets_30m`
- Element: `moqui.service.job.ServiceJob`

```xml
<moqui.service.job.ServiceJob jobName="scan_DueDatasets_30m" expireLockTime="360" description="Start the due refreshes of the kept copy of datasets, each as its dataset's tenant." serviceName="dataset.DatasetServices.scan#DueDatasets" defaultCronExpression="0 0/30 * * * ?" defaultPaused="Y" minIntervalMinutes="15"/>
```

### Modified in `data/ReconciliationJobSeedData.xml`

- Record: `moqui.service.job.ServiceJob|jobName=scan_ReconciliationAutomations_5m`
- Element: `moqui.service.job.ServiceJob`

```xml
<moqui.service.job.ServiceJob jobName="scan_ReconciliationAutomations_5m" expireLockTime="360" description="Scan due reconciliation automations and execute scheduled windows." serviceName="reconciliation.ReconciliationAutomationServices.scan#DueAutomations" defaultCronExpression="0 0/5 * * * ?" defaultPaused="N" minIntervalMinutes="1"/>
```

### Modified in `data/ReconciliationJobSeedData.xml`

- Record: `moqui.service.job.ServiceJob|jobName=sweep_RuleChildTenantStamps_hourly`
- Element: `moqui.service.job.ServiceJob`

```xml
<moqui.service.job.ServiceJob jobName="sweep_RuleChildTenantStamps_hourly" expireLockTime="360" description="Backfill companyUserGroupId on Rule/RuleSetCompareScope/RuleSetCompareSource from the parent RuleSet." serviceName="reconciliation.ReconciliationRuleEngineServices.sweep#RuleChildTenantStamps" defaultCronExpression="0 15 * * * ?" defaultPaused="N" minIntervalMinutes="15"/>
```

### Modified in `data/ReconciliationJobSeedData.xml`

- Record: `moqui.service.job.ServiceJob|jobName=sweep_StuckReconciliationRuns_10m`
- Element: `moqui.service.job.ServiceJob`

```xml
<moqui.service.job.ServiceJob jobName="sweep_StuckReconciliationRuns_10m" expireLockTime="360" description="Reap reconciliation runs / automation executions wedged in RUNNING after crash." serviceName="reconciliation.ReconciliationAutomationServices.sweep#StuckReconciliationRuns" defaultCronExpression="0 0/10 * * * ?" defaultPaused="N" minIntervalMinutes="5"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUD_REASON_PRIORITY|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUD_REASON_PRIORITY" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.audit.AuditReasonPriority" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/TenantConfigSeedData.xml`

- Record: `moqui.service.job.ServiceJob|jobName=snapshot_PendingTenantConfigs_1m`
- Element: `moqui.service.job.ServiceJob`

```xml
<moqui.service.job.ServiceJob jobName="snapshot_PendingTenantConfigs_1m" expireLockTime="360" description="Keep a config version for each tenant whose audit config changed and has been quiet for a minute." serviceName="config.TenantConfigServices.snapshot#PendingTenantConfigs" defaultCronExpression="0 * * * * ?" defaultPaused="N" minIntervalMinutes="1"/>
```

### Modified in `data/TwoFactorSeedData.xml`

- Record: `moqui.service.job.ServiceJob|jobName=send_TwoFactorReminders_daily`
- Element: `moqui.service.job.ServiceJob`

```xml
<moqui.service.job.ServiceJob jobName="send_TwoFactorReminders_daily" expireLockTime="360" description="Remind everyone two-factor is required for, who has not set up a method, on the 7/3/1/0-days-before then weekly cadence." serviceName="auth.TwoFactorServices.send#TwoFactorReminders" defaultCronExpression="0 0 15 * * ?" defaultPaused="N" minIntervalMinutes="1440"/>
```

## Recommended operator review

- Confirm every candidate record truly needs to be loaded for the target environment.
- Keep final upgrade records reflected in the appropriate generic source data file, such as a type, security, mapping, job, or system-message seed file.
- Prefer keeping changes in the existing domain seed file unless the release needs a distinct generic setup bundle.
- State the final operator action in `release-notes.md` and `release-checklist.md`.
