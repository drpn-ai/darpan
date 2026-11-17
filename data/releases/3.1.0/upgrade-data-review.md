# Upgrade Data Review For Darpan 3.1.0

## Scope

- Backend compare range: `v3.0.3..HEAD`
- Data directory reviewed: `data`
- Generic source data files are the source of truth for release upgrade data.
- This report lists candidate seed/config records that were added or modified in generic source data files between the compared refs.
- Do not author records directly in `upgrade-data.xml`; add or update the appropriate `runtime/component/darpan/data/*.xml` file and regenerate.

## Candidate records

### Added in `data/MigrationRegistrySeedData.xml`

- Record: `darpan.migration.DarpanMigration|migrationId=NS_AUTH_ACCOUNTS`
- Element: `darpan.migration.DarpanMigration`

```xml
<darpan.migration.DarpanMigration migrationId="NS_AUTH_ACCOUNTS" componentName="darpan" serviceName="facade.SettingsFacadeServices.migrate#NsAuthAccounts" sequenceNum="120" supportsDryRun="Y" enabled="Y" description="Store on each NetSuite auth config the account its secret belongs to (token URL, else its oldest                 endpoint), so an endpoint on another account is refused and detaching or deleting endpoints cannot reset it                 (DAR-BE-258). Endpoints on other accounts are reported, not changed. Idempotent."/>
```

### Added in `data/ReconciliationJobSeedData.xml`

- Record: `moqui.service.job.ServiceJobParameter|jobName=purge_ReconciliationExtractFiles_daily|parameterName=retentionDays`
- Element: `moqui.service.job.ServiceJobParameter`

```xml
<moqui.service.job.ServiceJobParameter jobName="purge_ReconciliationExtractFiles_daily" parameterName="retentionDays" parameterValue="7"/>
```

### Added in `data/ReconciliationJobSeedData.xml`

- Record: `moqui.service.job.ServiceJob|jobName=purge_ReconciliationExtractFiles_daily`
- Element: `moqui.service.job.ServiceJob`

```xml
<moqui.service.job.ServiceJob jobName="purge_ReconciliationExtractFiles_daily" description="Purge raw source extract files older than 7 days (never result files)." serviceName="reconciliation.ReconciliationGenericServices.purge#ExtractFiles" cronExpression="0 15 2 * * ?" paused="N"/>
```

### Added in `data/ReconciliationJobSeedData.xml`

- Record: `moqui.service.job.ServiceJob|jobName=run_DataRetention_daily`
- Element: `moqui.service.job.ServiceJob`

```xml
<moqui.service.job.ServiceJob jobName="run_DataRetention_daily" description="Delete audit findings, run results, AI transcripts and agent query logs older than their configured retention periods." serviceName="retention.RetentionServices.run#Retention" cronExpression="0 30 3 * * ?" paused="Y"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AGENT_ACCESS_GRANT_EVENT|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AGENT_ACCESS_GRANT_EVENT" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.agent.AgentAccessGrantEvent" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AGENT_ACCESS_GRANT|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AGENT_ACCESS_GRANT" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.agent.AgentAccessGrant" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AGENT_CONVERSATION|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AGENT_CONVERSATION" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.agent.AgentConversation" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AGENT_MESSAGE|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AGENT_MESSAGE" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.agent.AgentMessage" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AGENT_QUERY_LOG|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AGENT_QUERY_LOG" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.agent.AgentQueryLog" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AGENT_TURN|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AGENT_TURN" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.agent.AgentTurn" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUDIT_CHANGE_PROPOSAL|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUDIT_CHANGE_PROPOSAL" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.agent.AuditChangeProposal" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUD_AUDIT|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUD_AUDIT" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.audit.Audit" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUD_CHECK_RUN|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUD_CHECK_RUN" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.audit.AuditCheckRun" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUD_CHECK|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUD_CHECK" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.audit.AuditCheck" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUD_DIGEST|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUD_DIGEST" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.audit.AuditDigest" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUD_FINDING_CHECK|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUD_FINDING_CHECK" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.audit.AuditFindingCondition" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUD_FINDING_EVENT|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUD_FINDING_EVENT" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.audit.AuditFindingEvent" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUD_FINDING_RECORD|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUD_FINDING_RECORD" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.audit.AuditFindingRecord" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUD_FINDING|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUD_FINDING" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.audit.AuditFinding" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUD_SOURCE_READ|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUD_SOURCE_READ" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.audit.AuditSourceRead" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUD_STATEMENT_CHECK|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUD_STATEMENT_CHECK" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.audit.AuditStatementCheck" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUD_STATEMENT|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUD_STATEMENT" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.audit.AuditStatement" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUTOMATION_EXEC|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUTOMATION_EXEC" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.ReconciliationAutomationExecution" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUTOMATION_SOURCE|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUTOMATION_SOURCE" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.ReconciliationAutomationSource" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUTOMATION_SRC_FILTER|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUTOMATION_SRC_FILTER" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.ReconciliationAutomationSourceFilter" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUTOMATION_STREAK_BADGE|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUTOMATION_STREAK_BADGE" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.AutomationStreakBadge" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUTOMATION_STREAK|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUTOMATION_STREAK" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.AutomationStreak" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUTOMATION|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUTOMATION" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.ReconciliationAutomation" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_BRAIN_CHANGE_TICKET|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_BRAIN_CHANGE_TICKET" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.agent.BrainChangeTicket" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_CLI_ACCESS_GRANT_EVENT|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_CLI_ACCESS_GRANT_EVENT" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.auth.CliAccessGrantEvent" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_CLI_ACCESS_GRANT|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_CLI_ACCESS_GRANT" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.auth.CliAccessGrant" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_CLI_ACCESS_REQUEST|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_CLI_ACCESS_REQUEST" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.auth.CliAccessRequest" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_CONCLUSION_CONDITION|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_CONCLUSION_CONDITION" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.rule.RuleSetConclusionCondition" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_CONCLUSION_RULE|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_CONCLUSION_RULE" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.rule.RuleSetConclusionRule" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_DATA_EXPORT_LOG|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_DATA_EXPORT_LOG" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.admin.DataExportLog" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_DB_SOURCE_CONFIG|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_DB_SOURCE_CONFIG" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.database.DatabaseSourceConfig" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_DB_SOURCE_QUERY|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_DB_SOURCE_QUERY" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.database.DatabaseSourceQuery" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_ENDPOINT_ACCESS|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_ENDPOINT_ACCESS" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.SourceConfigEndpointAccess" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_HW_OMS_REST|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_HW_OMS_REST" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.hotwax.HotWaxOmsRestSourceConfig" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_JSON_SCHEMA|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_JSON_SCHEMA" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.JsonSchema" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_KNOWLEDGE_ENTRY|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_KNOWLEDGE_ENTRY" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.knowledge.KnowledgeEntry" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_NS_AUTH|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_NS_AUTH" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.NsAuthConfig" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_NS_RESTLET|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_NS_RESTLET" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.NsRestletConfig" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_NS_SUITEQL_QUERY|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_NS_SUITEQL_QUERY" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.NsSuiteQlSourceQuery" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_RECONCILIATION|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_RECONCILIATION" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.Reconciliation" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_RECON_MAPPING|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_RECON_MAPPING" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.mapping.ReconciliationMapping" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_RECON_RUN|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_RECON_RUN" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.ReconciliationRun" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_RULE_SCOPE|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_RULE_SCOPE" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.rule.RuleSetCompareScope" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_RULE_SET|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_RULE_SET" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.rule.RuleSet" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_RULE_SOURCE_FILTER|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_RULE_SOURCE_FILTER" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.rule.RuleSetCompareSourceFilter" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_RULE_SOURCE_KEY_FIELD|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_RULE_SOURCE_KEY_FIELD" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.rule.RuleSetCompareSourceKeyField" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_RULE_SOURCE|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_RULE_SOURCE" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.rule.RuleSetCompareSource" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_RULE|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_RULE" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.rule.Rule" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_RUN_RESULT|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_RUN_RESULT" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.ReconciliationRunResult" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_RUN_STEP|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_RUN_STEP" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.ReconciliationRunStep" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_SFTP_SERVER|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_SFTP_SERVER" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.SftpServer" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_SHOPIFY_AUTH|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_SHOPIFY_AUTH" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.shopify.ShopifyAuthConfig" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_SHOPIFY_INSTALL_STATE|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_SHOPIFY_INSTALL_STATE" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.shopify.ShopifyInstallState" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_SLACK_INSTALL|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_SLACK_INSTALL" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.SlackWorkspaceInstall" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_SLACK_OAUTH_STATE|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_SLACK_OAUTH_STATE" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.SlackOAuthState" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_TENANT_CHAT_SPACE|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_TENANT_CHAT_SPACE" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.TenantChatSpace" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_TENANT_CONCLUSION|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_TENANT_CONCLUSION" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.rule.TenantConclusion" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_TENANT_CONFIG_PENDING|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_TENANT_CONFIG_PENDING" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.config.TenantConfigPending" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_TENANT_CONFIG_VERSION|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_TENANT_CONFIG_VERSION" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.config.TenantConfigVersion" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_TENANT_NOTIF|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_TENANT_NOTIF" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.TenantNotificationSetting" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_TENANT_SCRIPT_DEPLOY|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_TENANT_SCRIPT_DEPLOY" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.script.TenantScriptDeployment" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_TENANT_SCRIPT|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_TENANT_SCRIPT" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.script.TenantScript" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_TENANT_SETTING|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_TENANT_SETTING" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.auth.TenantSetting" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_TENANT_STREAK_FACT|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_TENANT_STREAK_FACT" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.TenantStreakFact" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_TENANT_STREAK_QUEST|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_TENANT_STREAK_QUEST" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.TenantStreakQuest" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Modified in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_RETURNS_GQL|fieldPath=$.records[*].externalId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_RETURNS_GQL" fieldPath="$.records[*].externalId" label="Shopify refund ID" fieldType="string" isPrimaryIdCandidate="Y" sequenceNum="3" description="The join key. The Shopify refund id, matching Shopify SHOPIFY_RETURN_REFS's refundOrReturnId. BLANK on returns with no refund yet; the extract then fills it from shopifyReturnId, the return id Shopify reports until a refund exists."/>
```

### Modified in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_RETURNS_GQL|fieldPath=$.records[*].shopifyReturnId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_RETURNS_GQL" fieldPath="$.records[*].shopifyReturnId" label="Shopify return ID" fieldType="string" isPrimaryIdCandidate="Y" sequenceNum="2" description="The Shopify return reference: the SHOPIFY_RTN_ID identification where present, else SHOPIFY_RETURN_ID. Not the join key: once a return is refunded Shopify SHOPIFY_RETURN_REFS reports the refund id, not this, so keying on it misses refunded returns. Fills externalId while a return has no refund yet."/>
```

### Added in `data/TenantWelcomeEmailSeedData.xml`

- Record: `moqui.basic.email.EmailTemplate|emailTemplateId=DARPAN_TENANT_WELCOME|emailServerId=SYSTEM`
- Element: `moqui.basic.email.EmailTemplate`

```xml
<moqui.basic.email.EmailTemplate emailTemplateId="DARPAN_TENANT_WELCOME" description="Darpan tenant welcome" emailServerId="SYSTEM" webappName="webroot" sendPartial="Y" subject="You're in: ${tenantLabel} on Darpan" fromAddress="${System.getenv('DARPAN_MAIL_FROM') ?: 'support@drpn.ai'}" fromName="Darpan" bodyScreenLocation="component://darpan/screen/email/TenantWelcome.xml"/>
```

### Added in `data/TwoFactorSeedData.xml`

- Record: `darpan.auth.TwoFactorRolePolicy|permissionGroupId=ADMIN`
- Element: `darpan.auth.TwoFactorRolePolicy`

```xml
<darpan.auth.TwoFactorRolePolicy permissionGroupId="ADMIN"/>
```

### Added in `data/TwoFactorSeedData.xml`

- Record: `darpan.auth.TwoFactorRolePolicy|permissionGroupId=DARPAN_ADMIN`
- Element: `darpan.auth.TwoFactorRolePolicy`

```xml
<darpan.auth.TwoFactorRolePolicy permissionGroupId="DARPAN_ADMIN"/>
```

### Added in `data/TwoFactorSeedData.xml`

- Record: `darpan.auth.TwoFactorRolePolicy|permissionGroupId=DARPAN_SUPER_ADMIN`
- Element: `darpan.auth.TwoFactorRolePolicy`

```xml
<darpan.auth.TwoFactorRolePolicy permissionGroupId="DARPAN_SUPER_ADMIN"/>
```

### Added in `data/TwoFactorSeedData.xml`

- Record: `darpan.auth.TwoFactorRolePolicy|permissionGroupId=DARPAN_TENANT_ADMIN`
- Element: `darpan.auth.TwoFactorRolePolicy`

```xml
<darpan.auth.TwoFactorRolePolicy permissionGroupId="DARPAN_TENANT_ADMIN"/>
```

### Added in `data/TwoFactorSeedData.xml`

- Record: `moqui.basic.EnumerationType|enumTypeId=DarpanScPurpose`
- Element: `moqui.basic.EnumerationType`

```xml
<moqui.basic.EnumerationType description="Sign-In Challenge Purpose" enumTypeId="DarpanScPurpose"/>
```

### Added in `data/TwoFactorSeedData.xml`

- Record: `moqui.basic.EnumerationType|enumTypeId=DarpanTfFactorType`
- Element: `moqui.basic.EnumerationType`

```xml
<moqui.basic.EnumerationType description="Two-Factor Factor Type" enumTypeId="DarpanTfFactorType"/>
```

### Added in `data/TwoFactorSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=DrpnScCode|enumTypeId=DarpanScPurpose`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration description="Enter a code" enumId="DrpnScCode" enumTypeId="DarpanScPurpose"/>
```

### Added in `data/TwoFactorSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=DrpnScPkAdd|enumTypeId=DarpanScPurpose`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration description="Passkey registration" enumId="DrpnScPkAdd" enumTypeId="DarpanScPurpose"/>
```

### Added in `data/TwoFactorSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=DrpnScProof|enumTypeId=DarpanScPurpose`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration description="Passkey proof for an action" enumId="DrpnScProof" enumTypeId="DarpanScPurpose"/>
```

### Added in `data/TwoFactorSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=DrpnScSetup|enumTypeId=DarpanScPurpose`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration description="Set up two-factor first" enumId="DrpnScSetup" enumTypeId="DarpanScPurpose"/>
```

### Added in `data/TwoFactorSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=DrpnTfPkey|enumTypeId=DarpanTfFactorType`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration description="Passkey or security key (WebAuthn)" enumId="DrpnTfPkey" enumTypeId="DarpanTfFactorType"/>
```

### Added in `data/TwoFactorSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=DrpnTfTotp|enumTypeId=DarpanTfFactorType`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration description="Authenticator app (TOTP)" enumId="DrpnTfTotp" enumTypeId="DarpanTfFactorType"/>
```

### Added in `data/TwoFactorSeedData.xml`

- Record: `moqui.basic.email.EmailTemplate|emailTemplateId=DARPAN_2FA_APP_ADDED|emailServerId=SYSTEM`
- Element: `moqui.basic.email.EmailTemplate`

```xml
<moqui.basic.email.EmailTemplate emailTemplateId="DARPAN_2FA_APP_ADDED" description="Darpan authenticator app set up notice" emailServerId="SYSTEM" webappName="webroot" sendPartial="Y" subject="An authenticator app was set up for your Darpan account" fromAddress="${System.getenv('DARPAN_MAIL_FROM') ?: 'support@drpn.ai'}" fromName="Darpan" bodyScreenLocation="component://darpan/screen/email/TwoFactorAppAdded.xml"/>
```

### Added in `data/TwoFactorSeedData.xml`

- Record: `moqui.basic.email.EmailTemplate|emailTemplateId=DARPAN_2FA_CODES_LOCKED|emailServerId=SYSTEM`
- Element: `moqui.basic.email.EmailTemplate`

```xml
<moqui.basic.email.EmailTemplate emailTemplateId="DARPAN_2FA_CODES_LOCKED" description="Darpan two-factor codes locked notice" emailServerId="SYSTEM" webappName="webroot" sendPartial="Y" subject="Someone has your Darpan password" fromAddress="${System.getenv('DARPAN_MAIL_FROM') ?: 'support@drpn.ai'}" fromName="Darpan" bodyScreenLocation="component://darpan/screen/email/TwoFactorCodesLocked.xml"/>
```

### Added in `data/TwoFactorSeedData.xml`

- Record: `moqui.basic.email.EmailTemplate|emailTemplateId=DARPAN_2FA_PASSKEY_ADDED|emailServerId=SYSTEM`
- Element: `moqui.basic.email.EmailTemplate`

```xml
<moqui.basic.email.EmailTemplate emailTemplateId="DARPAN_2FA_PASSKEY_ADDED" description="Darpan passkey added notice" emailServerId="SYSTEM" webappName="webroot" sendPartial="Y" subject="A passkey was added to your Darpan account" fromAddress="${System.getenv('DARPAN_MAIL_FROM') ?: 'support@drpn.ai'}" fromName="Darpan" bodyScreenLocation="component://darpan/screen/email/TwoFactorPasskeyAdded.xml"/>
```

### Added in `data/TwoFactorSeedData.xml`

- Record: `moqui.basic.email.EmailTemplate|emailTemplateId=DARPAN_2FA_RECOVERY_USED|emailServerId=SYSTEM`
- Element: `moqui.basic.email.EmailTemplate`

```xml
<moqui.basic.email.EmailTemplate emailTemplateId="DARPAN_2FA_RECOVERY_USED" description="Darpan recovery code used notice" emailServerId="SYSTEM" webappName="webroot" sendPartial="Y" subject="A Darpan recovery code was used" fromAddress="${System.getenv('DARPAN_MAIL_FROM') ?: 'support@drpn.ai'}" fromName="Darpan" bodyScreenLocation="component://darpan/screen/email/TwoFactorRecoveryUsed.xml"/>
```

### Added in `data/TwoFactorSeedData.xml`

- Record: `moqui.basic.email.EmailTemplate|emailTemplateId=DARPAN_2FA_RESET|emailServerId=SYSTEM`
- Element: `moqui.basic.email.EmailTemplate`

```xml
<moqui.basic.email.EmailTemplate emailTemplateId="DARPAN_2FA_RESET" description="Darpan two-factor reset notice" emailServerId="SYSTEM" webappName="webroot" sendPartial="Y" subject="Your Darpan two-factor was reset" fromAddress="${System.getenv('DARPAN_MAIL_FROM') ?: 'support@drpn.ai'}" fromName="Darpan" bodyScreenLocation="component://darpan/screen/email/TwoFactorReset.xml"/>
```

## Recommended operator review

- Confirm every candidate record truly needs to be loaded for the target environment.
- Keep final upgrade records reflected in the appropriate generic source data file, such as a type, security, mapping, job, or system-message seed file.
- Prefer keeping changes in the existing domain seed file unless the release needs a distinct generic setup bundle.
- State the final operator action in `release-notes.md` and `release-checklist.md`.
