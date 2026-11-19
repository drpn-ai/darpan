# Upgrade Data Review For Darpan 3.3.2

## Scope

- Backend compare range: `v3.3.1..HEAD`
- Data directory reviewed: `data`
- Generic source data files are the source of truth for release upgrade data.
- This report lists candidate seed/config records that were added or modified in generic source data files between the compared refs.
- Do not author records directly in `upgrade-data.xml`; add or update the appropriate `runtime/component/darpan/data/*.xml` file and regenerate.

## Candidate records

### Modified in `data/MigrationRegistrySeedData.xml`

- Record: `darpan.migration.DarpanMigration|migrationId=AUDIT_FINDINGS_NO_PENDING`
- Element: `darpan.migration.DarpanMigration`

```xml
<darpan.migration.DarpanMigration migrationId="AUDIT_FINDINGS_NO_PENDING" componentName="darpan" serviceName="audit.AuditLedgerServices.migrate#AuditFindingsNoPending" sequenceNum="110" supportsDryRun="Y" description="Open every audit finding still pending: a finding is only open or fixed, and a grace is an                 expected conclusion rule, not a state (DAR-BE-206). Every tenant; dates and history kept. Idempotent."/>
```

### Modified in `data/MigrationRegistrySeedData.xml`

- Record: `darpan.migration.DarpanMigration|migrationId=AUDIT_SCHEDULE_OWNED`
- Element: `darpan.migration.DarpanMigration`

```xml
<darpan.migration.DarpanMigration migrationId="AUDIT_SCHEDULE_OWNED" componentName="darpan" serviceName="audit.AuditLedgerServices.migrate#AuditScheduleOwned" sequenceNum="100" supportsDryRun="Y" description="Give each audit its own schedule (time, zone, on/off) from the automations its checks run                 through (DAR-BE-199): the time most running checks share. No automation is rewritten; one off the                 audit's schedule is named and put on it by saving the audit's settings. Idempotent."/>
```

### Modified in `data/MigrationRegistrySeedData.xml`

- Record: `darpan.migration.DarpanMigration|migrationId=AUTOMATION_FILTERS`
- Element: `darpan.migration.DarpanMigration`

```xml
<darpan.migration.DarpanMigration migrationId="AUTOMATION_FILTERS" componentName="darpan" serviceName="reconciliation.ReconciliationNotificationServices.migrate#AutomationExcludeFilters" sequenceNum="30" supportsDryRun="N" description="Copy exclusion filters from the rule set into each automation's own                 snapshot, across every tenant. Idempotent."/>
```

### Modified in `data/MigrationRegistrySeedData.xml`

- Record: `darpan.migration.DarpanMigration|migrationId=AUTOMATION_STREAKS`
- Element: `darpan.migration.DarpanMigration`

```xml
<darpan.migration.DarpanMigration migrationId="AUTOMATION_STREAKS" componentName="darpan" serviceName="reconciliation.ReconciliationNotificationServices.migrate#AutomationStreaks" sequenceNum="70" supportsDryRun="N" description="Fold every automation's run history into its stored clean-day streak, so the                 clean-run Chat line reads the day count from the first message (DAR-BE-144).                 Idempotent — a fold reads only slots past the stored cursor."/>
```

### Modified in `data/MigrationRegistrySeedData.xml`

- Record: `darpan.migration.DarpanMigration|migrationId=ENDPOINT_ACCESS`
- Element: `darpan.migration.DarpanMigration`

```xml
<darpan.migration.DarpanMigration migrationId="ENDPOINT_ACCESS" componentName="darpan" serviceName="facade.SourceEndpointFacadeServices.migrate#SourceConfigEndpointAccess" sequenceNum="50" supportsDryRun="N" description="Translate the legacy canReadOrders flag into explicit                 SourceConfigEndpointAccess rows. Idempotent by PK."/>
```

### Modified in `data/MigrationRegistrySeedData.xml`

- Record: `darpan.migration.DarpanMigration|migrationId=NS_AUTH_ACCOUNTS`
- Element: `darpan.migration.DarpanMigration`

```xml
<darpan.migration.DarpanMigration migrationId="NS_AUTH_ACCOUNTS" componentName="darpan" serviceName="facade.SettingsFacadeServices.migrate#NsAuthAccounts" sequenceNum="120" supportsDryRun="Y" description="Store on each NetSuite auth config the account its secret belongs to (token URL, else its oldest                 endpoint), so an endpoint on another account is refused and detaching or deleting endpoints cannot reset it                 (DAR-BE-258). Endpoints on other accounts are reported, not changed. Idempotent."/>
```

### Modified in `data/MigrationRegistrySeedData.xml`

- Record: `darpan.migration.DarpanMigration|migrationId=RETIRED_FIELDS`
- Element: `darpan.migration.DarpanMigration`

```xml
<darpan.migration.DarpanMigration migrationId="RETIRED_FIELDS" componentName="darpan" serviceName="facade.SourceEndpointFacadeServices.migrate#RetiredConnectorFields" sequenceNum="60" supportsDryRun="N" description="Delete SourceSystemConnectorField pill rows withdrawn from seed data. Seed                 loads are createOrUpdate and never delete, so withdrawal needs an explicit deletion.                 Idempotent — a second run deletes nothing."/>
```

### Modified in `data/MigrationRegistrySeedData.xml`

- Record: `darpan.migration.DarpanMigration|migrationId=RETIRED_JOB_PARAMETERS`
- Element: `darpan.migration.DarpanMigration`

```xml
<darpan.migration.DarpanMigration migrationId="RETIRED_JOB_PARAMETERS" componentName="darpan" serviceName="retention.RetentionMigrations.move#PurgeJobRetentionParameters" sequenceNum="130" supportsDryRun="Y" description="Move each nightly purge job's edited retentionDays parameter into its darpan.purge.* setting and delete                 the row, and delete the stuck-run reaper job's retired staleMinutes parameter (DAR-BE-306, DAR-BE-304). Seed loads                 never delete, and a live parameter row would keep overriding the setting. Idempotent."/>
```

### Modified in `data/MigrationRegistrySeedData.xml`

- Record: `darpan.migration.DarpanMigration|migrationId=RULE_TENANT_STAMPS`
- Element: `darpan.migration.DarpanMigration`

```xml
<darpan.migration.DarpanMigration migrationId="RULE_TENANT_STAMPS" componentName="darpan" serviceName="reconciliation.ReconciliationRuleEngineServices.sweep#RuleChildTenantStamps" sequenceNum="40" supportsDryRun="N" description="Backfill the denormalized companyUserGroupId stamp on rule-set child rows                 created before the stamp existed. Idempotent no-op on already-stamped rows."/>
```

### Modified in `data/MigrationRegistrySeedData.xml`

- Record: `darpan.migration.DarpanMigration|migrationId=STATEMENT_LINK_EXPECTED`
- Element: `darpan.migration.DarpanMigration`

```xml
<darpan.migration.DarpanMigration migrationId="STATEMENT_LINK_EXPECTED" componentName="darpan" serviceName="audit.AuditLedgerServices.migrate#StatementLinkExpected" sequenceNum="90" supportsDryRun="Y" description="Move each audit statement link's list of counted causes onto its run: the causes it left out                 become expected (rule outcome EXPECTED) and the list is cleared; Unexplained then counts on every                 statement (DAR-BE-196). Idempotent; a run whose links disagree is reported and left untouched."/>
```

### Modified in `data/MigrationRegistrySeedData.xml`

- Record: `darpan.migration.DarpanMigration|migrationId=TENANT_NOTIF_SETTINGS`
- Element: `darpan.migration.DarpanMigration`

```xml
<darpan.migration.DarpanMigration migrationId="TENANT_NOTIF_SETTINGS" componentName="darpan" serviceName="reconciliation.ReconciliationNotificationServices.migrate#TenantNotificationSettings" sequenceNum="10" supportsDryRun="N" description="Seed a default chat space for tenants that have notification settings but no                 space. Idempotent — tenants that already have any chat space are skipped."/>
```

### Modified in `data/MigrationRegistrySeedData.xml`

- Record: `darpan.migration.DarpanMigration|migrationId=UNDECRYPTABLE_HOOKS`
- Element: `darpan.migration.DarpanMigration`

```xml
<darpan.migration.DarpanMigration migrationId="UNDECRYPTABLE_HOOKS" componentName="darpan" serviceName="reconciliation.ReconciliationNotificationServices.clear#UndecryptableChatSpaceWebhooks" sequenceNum="20" supportsDryRun="N" description="Clear chat space webhooks that can no longer be decrypted after a crypt key                 change. Idempotent, and a no-op on an installation that never stored a webhook."/>
```

## Recommended operator review

- Confirm every candidate record truly needs to be loaded for the target environment.
- Keep final upgrade records reflected in the appropriate generic source data file, such as a type, security, mapping, job, or system-message seed file.
- Prefer keeping changes in the existing domain seed file unless the release needs a distinct generic setup bundle.
- State the final operator action in `release-notes.md` and `release-checklist.md`.
