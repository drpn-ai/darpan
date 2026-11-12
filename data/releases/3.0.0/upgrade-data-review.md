# Upgrade Data Review For Darpan 3.0.0

## Scope

- Backend compare range: `v2.1.12..HEAD`
- Data directory reviewed: `data`
- Generic source data files are the source of truth for release upgrade data.
- This report lists candidate seed/config records that were added or modified in generic source data files between the compared refs.
- Do not author records directly in `upgrade-data.xml`; add or update the appropriate `runtime/component/darpan/data/*.xml` file and regenerate.

## Candidate records

### Added in `data/AuditDigestEmailSeedData.xml`

- Record: `moqui.basic.email.EmailTemplate|emailTemplateId=DARPAN_AUDIT_RUN|emailServerId=SYSTEM`
- Element: `moqui.basic.email.EmailTemplate`

```xml
<moqui.basic.email.EmailTemplate emailTemplateId="DARPAN_AUDIT_RUN" description="Darpan audit run update" emailServerId="SYSTEM" webappName="webroot" sendPartial="Y" subject="${runSubject}" fromAddress="${System.getenv('DARPAN_MAIL_FROM') ?: 'support@drpn.ai'}" fromName="Darpan" bodyScreenLocation="component://darpan/screen/email/AuditRun.xml"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDDK_RUN|enumTypeId=AuditDigestKind|enumCode=run`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDDK_RUN" enumTypeId="AuditDigestKind" enumCode="run" description="After each audit run" sequenceNum="3"/>
```

### Added in `data/ConclusionCatalogSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_PARTIAL_PAYMENT_GIVEN_BACK|enumTypeId=DarpanConclusion|enumCode=CONC_PARTIAL_PAYMENT_GIVEN_BACK`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_PARTIAL_PAYMENT_GIVEN_BACK" enumTypeId="DarpanConclusion" enumCode="CONC_PARTIAL_PAYMENT_GIVEN_BACK" description="Partial payment given back"/>
```

### Added in `data/ConclusionCatalogSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_PRODUCT_NOT_IN_NETSUITE|enumTypeId=DarpanConclusion|enumCode=CONC_PRODUCT_NOT_IN_NETSUITE`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_PRODUCT_NOT_IN_NETSUITE" enumTypeId="DarpanConclusion" enumCode="CONC_PRODUCT_NOT_IN_NETSUITE" description="Product not in NetSuite"/>
```

### Added in `data/ConclusionCatalogSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_RETIRED_SKU|enumTypeId=DarpanConclusion|enumCode=CONC_RETIRED_SKU`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_RETIRED_SKU" enumTypeId="DarpanConclusion" enumCode="CONC_RETIRED_SKU" description="Sold on a retired SKU"/>
```

### Added in `data/ConclusionCatalogSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=FX_GAP|enumTypeId=DarpanConclusion|enumCode=FX_GAP`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="FX_GAP" enumTypeId="DarpanConclusion" enumCode="FX_GAP" description="Currency conversion leaves the payment short"/>
```

### Added in `data/ConclusionCatalogSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=HELD_IN_HC|enumTypeId=DarpanConclusion|enumCode=HELD_IN_HC`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="HELD_IN_HC" enumTypeId="DarpanConclusion" enumCode="HELD_IN_HC" description="Approved and paid, held in HotWax"/>
```

### Added in `data/ConclusionCatalogSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=LINE_REMOVED|enumTypeId=DarpanConclusion|enumCode=LINE_REMOVED`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="LINE_REMOVED" enumTypeId="DarpanConclusion" enumCode="LINE_REMOVED" description="Line removed in Shopify after the sale"/>
```

### Added in `data/ConclusionCatalogSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=LOST_NO_MEMO|enumTypeId=DarpanConclusion|enumCode=LOST_NO_MEMO`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="LOST_NO_MEMO" enumTypeId="DarpanConclusion" enumCode="LOST_NO_MEMO" description="Lost-warranty claim, no credit memo"/>
```

### Added in `data/ConclusionCatalogSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=NOT_IN_HC|enumTypeId=DarpanConclusion|enumCode=NOT_IN_HC`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="NOT_IN_HC" enumTypeId="DarpanConclusion" enumCode="NOT_IN_HC" description="Shopify order not in HotWax"/>
```

### Added in `data/ConclusionCatalogSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=NS_ID_NO_SO|enumTypeId=DarpanConclusion|enumCode=NS_ID_NO_SO`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="NS_ID_NO_SO" enumTypeId="DarpanConclusion" enumCode="NS_ID_NO_SO" description="HotWax has a NetSuite id, NetSuite no order"/>
```

### Added in `data/ConclusionCatalogSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=ORIG_OPEN|enumTypeId=DarpanConclusion|enumCode=ORIG_OPEN`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="ORIG_OPEN" enumTypeId="DarpanConclusion" enumCode="ORIG_OPEN" description="Waits on its original order"/>
```

### Added in `data/ConclusionCatalogSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=PAID_NO_TXN|enumTypeId=DarpanConclusion|enumCode=PAID_NO_TXN`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="PAID_NO_TXN" enumTypeId="DarpanConclusion" enumCode="PAID_NO_TXN" description="Marked paid in Shopify, nothing received"/>
```

### Added in `data/ConclusionCatalogSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=PAY_EXPIRED|enumTypeId=DarpanConclusion|enumCode=PAY_EXPIRED`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="PAY_EXPIRED" enumTypeId="DarpanConclusion" enumCode="PAY_EXPIRED" description="Payment failed, never paid"/>
```

### Added in `data/ConclusionCatalogSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=POS_UNPAID|enumTypeId=DarpanConclusion|enumCode=POS_UNPAID`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="POS_UNPAID" enumTypeId="DarpanConclusion" enumCode="POS_UNPAID" description="Store sale with no payment"/>
```

### Added in `data/ConclusionCatalogSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=REPL_UNPAID|enumTypeId=DarpanConclusion|enumCode=REPL_UNPAID`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="REPL_UNPAID" enumTypeId="DarpanConclusion" enumCode="REPL_UNPAID" description="Replacement not paid for"/>
```

### Added in `data/ConclusionCatalogSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=RET_NO_RMA|enumTypeId=DarpanConclusion|enumCode=RET_NO_RMA`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="RET_NO_RMA" enumTypeId="DarpanConclusion" enumCode="RET_NO_RMA" description="Return not in NetSuite"/>
```

### Added in `data/ConclusionCatalogSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=RET_OPEN|enumTypeId=DarpanConclusion|enumCode=RET_OPEN`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="RET_OPEN" enumTypeId="DarpanConclusion" enumCode="RET_OPEN" description="Waits on its own return"/>
```

### Added in `data/ConclusionCatalogSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=RMA_CLOSED|enumTypeId=DarpanConclusion|enumCode=RMA_CLOSED`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="RMA_CLOSED" enumTypeId="DarpanConclusion" enumCode="RMA_CLOSED" description="Return's RMA closed, no credit memo"/>
```

### Added in `data/ConclusionCatalogSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=RMA_NO_MEMO|enumTypeId=DarpanConclusion|enumCode=RMA_NO_MEMO`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="RMA_NO_MEMO" enumTypeId="DarpanConclusion" enumCode="RMA_NO_MEMO" description="Return has an RMA, no credit memo"/>
```

### Modified in `data/DarpanSystemSourceSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=OMS_GQL|enumTypeId=DarpanSystemSource|parentEnumId=OMS|enumCode=HOTWAX_GQL`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="OMS_GQL" enumTypeId="DarpanSystemSource" enumCode="HOTWAX_GQL" description="HotWax orders" sequenceNum="14" parentEnumId="OMS"/>
```

### Modified in `data/DarpanSystemSourceSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=OMS_ORDER_ITEMS_GQL|enumTypeId=DarpanSystemSource|parentEnumId=OMS|enumCode=HOTWAX_GQL_ORDER_ITEMS`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="OMS_ORDER_ITEMS_GQL" enumTypeId="DarpanSystemSource" enumCode="HOTWAX_GQL_ORDER_ITEMS" description="HotWax order lines" sequenceNum="13" parentEnumId="OMS"/>
```

### Added in `data/MigrationRegistrySeedData.xml`

- Record: `darpan.migration.DarpanMigration|migrationId=AUDIT_FINDINGS_NO_PENDING`
- Element: `darpan.migration.DarpanMigration`

```xml
<darpan.migration.DarpanMigration migrationId="AUDIT_FINDINGS_NO_PENDING" componentName="darpan" serviceName="audit.AuditLedgerServices.migrate#AuditFindingsNoPending" sequenceNum="110" supportsDryRun="Y" enabled="Y" description="Open every audit finding still pending: a finding is only open or fixed, and a grace is an                 expected conclusion rule, not a state (DAR-BE-206). Every tenant; dates and history kept. Idempotent."/>
```

### Added in `data/MigrationRegistrySeedData.xml`

- Record: `darpan.migration.DarpanMigration|migrationId=AUDIT_SCHEDULE_OWNED`
- Element: `darpan.migration.DarpanMigration`

```xml
<darpan.migration.DarpanMigration migrationId="AUDIT_SCHEDULE_OWNED" componentName="darpan" serviceName="audit.AuditLedgerServices.migrate#AuditScheduleOwned" sequenceNum="100" supportsDryRun="Y" enabled="Y" description="Give each audit its own schedule (time, zone, on/off) from the automations its checks run                 through (DAR-BE-199): the time most running checks share. No automation is rewritten; one off the                 audit's schedule is named and put on it by saving the audit's settings. Idempotent."/>
```

### Added in `data/MigrationRegistrySeedData.xml`

- Record: `darpan.migration.DarpanMigration|migrationId=STATEMENT_LINK_EXPECTED`
- Element: `darpan.migration.DarpanMigration`

```xml
<darpan.migration.DarpanMigration migrationId="STATEMENT_LINK_EXPECTED" componentName="darpan" serviceName="audit.AuditLedgerServices.migrate#StatementLinkExpected" sequenceNum="90" supportsDryRun="Y" enabled="Y" description="Move each audit statement link's list of counted causes onto its run: the causes it left out                 become expected (rule outcome EXPECTED) and the list is cleared; Unexplained then counts on every                 statement (DAR-BE-196). Idempotent; a run whose links disagree is reported and left untouched."/>
```

### Added in `data/ScriptJobSeedData.xml`

- Record: `moqui.service.job.ServiceJob|jobName=scan_TenantScriptDeployments_5m`
- Element: `moqui.service.job.ServiceJob`

```xml
<moqui.service.job.ServiceJob jobName="scan_TenantScriptDeployments_5m" description="Run tenant script deployments that are due and deliver their text." serviceName="script.TenantScriptServices.scan#DueScriptDeployments" cronExpression="0 0/5 * * * ?" paused="N"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AGENT_ACCESS_GRANT_EVENT|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AGENT_ACCESS_GRANT_EVENT" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.agent.AgentAccessGrantEvent" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AGENT_ACCESS_GRANT|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AGENT_ACCESS_GRANT" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.agent.AgentAccessGrant" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AGENT_CONVERSATION|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AGENT_CONVERSATION" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.agent.AgentConversation" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AGENT_MESSAGE|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AGENT_MESSAGE" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.agent.AgentMessage" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AGENT_QUERY_LOG|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AGENT_QUERY_LOG" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.agent.AgentQueryLog" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AGENT_TURN|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AGENT_TURN" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.agent.AgentTurn" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUDIT_CHANGE_PROPOSAL|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUDIT_CHANGE_PROPOSAL" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.agent.AuditChangeProposal" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_BRAIN_CHANGE_TICKET|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_BRAIN_CHANGE_TICKET" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.agent.BrainChangeTicket" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_CLI_ACCESS_GRANT_EVENT|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_CLI_ACCESS_GRANT_EVENT" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.auth.CliAccessGrantEvent" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_CLI_ACCESS_GRANT|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_CLI_ACCESS_GRANT" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.auth.CliAccessGrant" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_CLI_ACCESS_REQUEST|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_CLI_ACCESS_REQUEST" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.auth.CliAccessRequest" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_KNOWLEDGE_ENTRY|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_KNOWLEDGE_ENTRY" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.knowledge.KnowledgeEntry" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_TENANT_CONFIG_PENDING|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_TENANT_CONFIG_PENDING" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.config.TenantConfigPending" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_TENANT_CONFIG_VERSION|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_TENANT_CONFIG_VERSION" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.config.TenantConfigVersion" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_TENANT_SCRIPT_DEPLOY|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_TENANT_SCRIPT_DEPLOY" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.script.TenantScriptDeployment" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_TENANT_SCRIPT|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_TENANT_SCRIPT" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.script.TenantScript" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Modified in `data/SourceSystemConnectorSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnector|systemEnumId=NETSUITE_SUITEQL`
- Element: `darpan.reconciliation.SourceSystemConnector`

```xml
<darpan.reconciliation.SourceSystemConnector systemEnumId="NETSUITE_SUITEQL" extractServiceName="reconciliation.NetSuiteOrderExtractionServices.extract#NetSuiteOrders" dateFromParameterName="windowStart" dateToParameterName="windowEnd" expectedSourceConfigType="NETSUITE_SUITEQL" configParameterName="nsSuiteQlSourceQueryId" configEntityName="darpan.reconciliation.NsSuiteQlSourceQuery" endpointLabel="SuiteQL" sendUrlTemplate="https://{account}.suitetalk.api.netsuite.com/services/rest/query/v1/suiteql" remoteSendServiceName="reconciliation.NetSuiteOrderExtractionServices.extract#NetSuiteOrders" systemAliases="NETSUITE_SUITEQL,NETSUITE_ORDERS,NETSUITE_SALES_ORDERS,DAR_SYS_NETSUITE_SUITEQL" preserveWindowInstants="N" windowDateOnly="Y" filterParameterName="sourceFilters" evidenceFieldsJson='{"system":"NetSuite","state":"status","stateLabels":{"A":"Pending Approval","B":"Pending Fulfillment","C":"Cancelled","D":"Partially Fulfilled","E":"Pending Billing/Partially Fulfilled","F":"Pending Billing","G":"Billed","H":"Closed"},"facts":["orderLineId","lineState","lineLocation","quantityShipped","quantityBilled","quantityBackordered","tranId","sku"]}' enabled="Y"/>
```

### Modified in `data/SourceSystemConnectorSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnector|systemEnumId=OMS_GQL|remoteId=HOTWAX_ORDERS_API`
- Element: `darpan.reconciliation.SourceSystemConnector`

```xml
<darpan.reconciliation.SourceSystemConnector systemEnumId="OMS_GQL" extractServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlOrders" dateFromParameterName="windowStart" dateToParameterName="windowEnd" expectedSourceConfigType="HOTWAX_OMS_GQL" configParameterName="omsRestSourceConfigId" configEntityName="darpan.hotwax.HotWaxOmsRestSourceConfig" remoteId="HOTWAX_ORDERS_API" endpointLabel="Orders" sendUrlTemplate="{baseUrl}/rest/s1/graphql" remoteSendServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlOrders" systemAliases="OMS_GQL,HOTWAX_GQL,DAR_SYS_OMS_GQL" preserveWindowInstants="N" keepFieldsParameterName="keepRecordFields" keepFieldsBase="orderId,orderName,externalId,grandTotal,orderDate,statusId,currencyUomId,billToCustomer,shipGroups" filterParameterName="sourceFilters" pairLookupServiceName="reconciliation.HotWaxOmsExtractionServices.lookup#HotWaxOmsOrdersByExternalId" healthCheckServiceName="facade.HotWaxOmsFacadeServices.probe#HotWaxOmsConnection" enabled="Y"/>
```

### Modified in `data/SourceSystemConnectorSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnector|systemEnumId=OMS_ORDER_ITEMS_GQL|remoteId=HOTWAX_ORDERS_API`
- Element: `darpan.reconciliation.SourceSystemConnector`

```xml
<darpan.reconciliation.SourceSystemConnector systemEnumId="OMS_ORDER_ITEMS_GQL" extractServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlOrderItems" dateFromParameterName="windowStart" dateToParameterName="windowEnd" expectedSourceConfigType="HOTWAX_OMS_GQL_ORDER_ITEMS" configParameterName="omsRestSourceConfigId" configEntityName="darpan.hotwax.HotWaxOmsRestSourceConfig" remoteId="HOTWAX_ORDERS_API" endpointLabel="Order lines" sendUrlTemplate="{baseUrl}/rest/s1/graphql" remoteSendServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlOrderItems" systemAliases="OMS_ORDER_ITEMS_GQL,HOTWAX_GQL_ORDER_ITEMS,DAR_SYS_OMS_ORDER_ITEMS_GQL" preserveWindowInstants="N" filterParameterName="sourceFilters" windowFieldName="orderDate" healthCheckServiceName="facade.HotWaxOmsFacadeServices.probe#HotWaxOmsConnection" evidenceFieldsJson='{"system":"HotWax","state":"statusId","stateLabels":{"ITEM_COMPLETED":"Completed","ITEM_APPROVED":"Approved","ITEM_CREATED":"Created","ITEM_CANCELLED":"Cancelled"},"facts":["facilityId","orderItemSeqId","omsOrderId"]}' enabled="Y"/>
```

### Modified in `data/SourceSystemConnectorSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnector|systemEnumId=OMS_PRODUCTS_GQL|remoteId=HOTWAX_ORDERS_API`
- Element: `darpan.reconciliation.SourceSystemConnector`

```xml
<darpan.reconciliation.SourceSystemConnector systemEnumId="OMS_PRODUCTS_GQL" extractServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlProducts" dateFromParameterName="windowStart" dateToParameterName="windowEnd" expectedSourceConfigType="HOTWAX_OMS_GQL_PRODUCTS" configParameterName="omsRestSourceConfigId" configEntityName="darpan.hotwax.HotWaxOmsRestSourceConfig" remoteId="HOTWAX_ORDERS_API" endpointLabel="Products" sendUrlTemplate="{baseUrl}/rest/s1/graphql" remoteSendServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlProducts" systemAliases="OMS_PRODUCTS_GQL,HOTWAX_PRODUCTS" preserveWindowInstants="N" windowed="N" filterParameterName="sourceFilters" evidenceFieldsJson='{"system":"HotWax","facts":["internalName","productId"],"otherSideKeyField":"netsuiteProductId"}' healthCheckServiceName="facade.HotWaxOmsFacadeServices.probe#HotWaxOmsConnection" enabled="Y"/>
```

### Modified in `data/SourceSystemConnectorSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnector|systemEnumId=OMS_RETURNS_GQL|remoteId=HOTWAX_ORDERS_API`
- Element: `darpan.reconciliation.SourceSystemConnector`

```xml
<darpan.reconciliation.SourceSystemConnector systemEnumId="OMS_RETURNS_GQL" extractServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlReturns" dateFromParameterName="windowStart" dateToParameterName="windowEnd" expectedSourceConfigType="HOTWAX_OMS_GQL_RETURNS" configParameterName="omsRestSourceConfigId" configEntityName="darpan.hotwax.HotWaxOmsRestSourceConfig" remoteId="HOTWAX_ORDERS_API" endpointLabel="Returns" sendUrlTemplate="{baseUrl}/rest/s1/graphql" remoteSendServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlReturns" systemAliases="OMS_RETURNS_GQL,HOTWAX_GQL_RETURNS,DAR_SYS_OMS_RETURNS_GQL" preserveWindowInstants="N" keepFieldsParameterName="keepRecordFields" keepFieldsBase="returnId,shopifyReturnId,externalId,orderExternalId,statusId,entryDate,returnTotal,currencyUomId,returnChannelEnumId" filterParameterName="sourceFilters" healthCheckServiceName="facade.HotWaxOmsFacadeServices.probe#HotWaxOmsConnection" orderStateLookupServiceName="reconciliation.HotWaxOmsExtractionServices.lookup#HotWaxOmsOrdersByExternalId" returnLinesLookupServiceName="reconciliation.HotWaxOmsExtractionServices.lookup#HotWaxOmsReturnLinesByOrderExternalId" lookupServiceName="reconciliation.HotWaxOmsExtractionServices.lookup#HotWaxOmsReturnsByExternalId" lookupIdsParameterName="externalIds" lookupMaxIds="10000" extractOptionsJson='[{"name":"includeReturnsWithoutShopifyRef","label":"Include returns with no Shopify reference","type":"boolean"},{"name":"onlyCancelledReturns","label":"Only cancelled returns","type":"boolean"},{"name":"withReturnCredit","label":"Credit HotWax gave"}]' enabled="Y"/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_GQL|fieldPath=$.records[*].externalId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_GQL" fieldPath="$.records[*].externalId" label="Shopify order ID" fieldType="string" isPrimaryIdCandidate="Y" sequenceNum="3" description="Shopify reference stored on the OMS order. In keepFieldsBase."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_GQL|fieldPath=$.records[*].grandTotal`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_GQL" fieldPath="$.records[*].grandTotal" label="Order total" fieldType="string" sequenceNum="4" description="In keepFieldsBase. Comparison only — not key-like."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_GQL|fieldPath=$.records[*].hasPaymentPreference`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_GQL" fieldPath="$.records[*].hasPaymentPreference" label="Has payment" fieldType="string" sequenceNum="8" description="Y when the order carries at least one OrderPaymentPreference, else N. Compared against Shopify hasPaymentTransaction by the presence OPP rule (hasPaymentTransaction requires hasPaymentPreference, DAR-BE-126)."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_GQL|fieldPath=$.records[*].isCompleted`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_GQL" fieldPath="$.records[*].isCompleted" label="Completed" fieldType="string" sequenceNum="10" description="DAR-BE-125: Y when statusId is ORDER_COMPLETED, else N. Compared against Shopify isFulfilled (isFulfilled required by isCompleted, DAR-BE-126)."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_GQL|fieldPath=$.records[*].orderDate`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_GQL" fieldPath="$.records[*].orderDate" label="Order date" fieldType="string" sequenceNum="5" description="In keepFieldsBase. Epoch millis."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_GQL|fieldPath=$.records[*].orderId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_GQL" fieldPath="$.records[*].orderId" label="HotWax order ID" fieldType="string" isPrimaryIdCandidate="Y" sequenceNum="1" description="OMS order identity. In keepFieldsBase."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_GQL|fieldPath=$.records[*].orderName`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_GQL" fieldPath="$.records[*].orderName" label="Order name" fieldType="string" isPrimaryIdCandidate="Y" sequenceNum="2" description="Operator-facing order name. In keepFieldsBase."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_GQL|fieldPath=$.records[*].paymentMethodTypeIds`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_GQL" fieldPath="$.records[*].paymentMethodTypeIds" label="Payment methods" fieldType="string" sequenceNum="9" description="Comma-joined OPP paymentMethodTypeIds from the first two OPPs (detail, not a key)."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_GQL|fieldPath=$.records[*].paymentRefNums`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_GQL" fieldPath="$.records[*].paymentRefNums" label="Payment IDs" fieldType="string" sequenceNum="11" description="DAR-BE-153: comma-joined OPP manualRefNums (the Shopify transaction id each OPP names), read from up to five OPPs; null when all five slots came back full, so a longer list is never claimed short. Compared against Shopify paymentTransactionIds (paymentTransactionIds all in paymentRefNums)."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_GQL|fieldPath=$.records[*].productStoreId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_GQL" fieldPath="$.records[*].productStoreId" label="Store" fieldType="string" sequenceNum="7" description="NOT in keepFieldsBase; selected in the GraphQL document only when a filter or rule reads it."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_GQL|fieldPath=$.records[*].salesChannelEnumId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_GQL" fieldPath="$.records[*].salesChannelEnumId" label="Sales channel" fieldType="string" sequenceNum="12" description="DAR-BE-153: NOT in keepFieldsBase; selected only when a filter or rule reads it. On the Order type since the 2026-10-28 moqui-gql upgrade; Steve Madden's daily order run excludes POS_SALES_CHANNEL on it."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_GQL|fieldPath=$.records[*].statusId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_GQL" fieldPath="$.records[*].statusId" label="Order status" fieldType="string" sequenceNum="6" description="In keepFieldsBase."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_ORDER_ITEMS_GQL|fieldPath=$.records[*].facilityId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_ORDER_ITEMS_GQL" fieldPath="$.records[*].facilityId" label="Facility" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="11"/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_ORDER_ITEMS_GQL|fieldPath=$.records[*].omsOrderId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_ORDER_ITEMS_GQL" fieldPath="$.records[*].omsOrderId" label="HotWax order ID" fieldType="string" isPrimaryIdCandidate="Y" sequenceNum="1"/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_ORDER_ITEMS_GQL|fieldPath=$.records[*].omsOrderName`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_ORDER_ITEMS_GQL" fieldPath="$.records[*].omsOrderName" label="Order name" fieldType="string" isPrimaryIdCandidate="Y" sequenceNum="3"/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_ORDER_ITEMS_GQL|fieldPath=$.records[*].omsOrderStatusId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_ORDER_ITEMS_GQL" fieldPath="$.records[*].omsOrderStatusId" label="Order status" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="12"/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_ORDER_ITEMS_GQL|fieldPath=$.records[*].orderItemSeqId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_ORDER_ITEMS_GQL" fieldPath="$.records[*].orderItemSeqId" label="Line number" fieldType="string" isPrimaryIdCandidate="Y" sequenceNum="2"/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_ORDER_ITEMS_GQL|fieldPath=$.records[*].productId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_ORDER_ITEMS_GQL" fieldPath="$.records[*].productId" label="Product ID" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="8"/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_ORDER_ITEMS_GQL|fieldPath=$.records[*].shopifyLineId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_ORDER_ITEMS_GQL" fieldPath="$.records[*].shopifyLineId" label="Shopify line ID" fieldType="string" isPrimaryIdCandidate="Y" sequenceNum="5"/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_ORDER_ITEMS_GQL|fieldPath=$.records[*].shopifyOrderId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_ORDER_ITEMS_GQL" fieldPath="$.records[*].shopifyOrderId" label="Shopify order ID" fieldType="string" isPrimaryIdCandidate="Y" sequenceNum="4"/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_ORDER_ITEMS_GQL|fieldPath=$.records[*].statusId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_ORDER_ITEMS_GQL" fieldPath="$.records[*].statusId" label="Line status" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="9"/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_ORDER_ITEMS_GQL|fieldPath=$.records[*].unitOrdinal`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_ORDER_ITEMS_GQL" fieldPath="$.records[*].unitOrdinal" label="Unit number" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="7"/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_ORDER_ITEMS_GQL|fieldPath=$.records[*].unitState`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_ORDER_ITEMS_GQL" fieldPath="$.records[*].unitState" label="Unit state" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="10"/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_PRODUCTS_GQL|fieldPath=$.records[*].createdDate`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_PRODUCTS_GQL" fieldPath="$.records[*].createdDate" label="Created" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="6" description="When HotWax created the product."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_PRODUCTS_GQL|fieldPath=$.records[*].duplicateNetsuiteId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_PRODUCTS_GQL" fieldPath="$.records[*].duplicateNetsuiteId" label="Shared NetSuite ID" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="7" description="true when another sellable product carries the same NetSuite id."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_PRODUCTS_GQL|fieldPath=$.records[*].internalName`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_PRODUCTS_GQL" fieldPath="$.records[*].internalName" label="SKU" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="4" description="The product's internal name (its SKU)."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_PRODUCTS_GQL|fieldPath=$.records[*].netsuiteIdState`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_PRODUCTS_GQL" fieldPath="$.records[*].netsuiteIdState" label="NetSuite ID state" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="8" description="OWN: keyed on its NetSuite id; NONE: it has none (nsKey HOTWAX-ONLY:productId); SHARED: another product holds the id first (nsKey DUPLICATE:id:productId)."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_PRODUCTS_GQL|fieldPath=$.records[*].netsuiteProductId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_PRODUCTS_GQL" fieldPath="$.records[*].netsuiteProductId" label="NetSuite item ID" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="2" description="The NETSUITE_PRODUCT_ID identification, blank when the product has none."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_PRODUCTS_GQL|fieldPath=$.records[*].notSellableReason`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_PRODUCTS_GQL" fieldPath="$.records[*].notSellableReason" label="Unsellable reason" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="11" description="discontinued, virtual, or the product type (DIGITAL_GOOD, SERVICE ...) when sellable is N."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_PRODUCTS_GQL|fieldPath=$.records[*].nsKey`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_PRODUCTS_GQL" fieldPath="$.records[*].nsKey" label="Match key" fieldType="string" isPrimaryIdCandidate="Y" sequenceNum="1" description="The compare key: the product's NETSUITE_PRODUCT_ID (the NetSuite item internal id); HOTWAX-ONLY:productId when it has none, DUPLICATE:id:productId for every product after the first sharing one."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_PRODUCTS_GQL|fieldPath=$.records[*].productId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_PRODUCTS_GQL" fieldPath="$.records[*].productId" label="HotWax product ID" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="3" description="HotWax's own product id."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_PRODUCTS_GQL|fieldPath=$.records[*].productTypeId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_PRODUCTS_GQL" fieldPath="$.records[*].productTypeId" label="Product type" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="5" description="FINISHED_GOOD or MARKETING_PKG_PICK."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_PRODUCTS_GQL|fieldPath=$.records[*].sellable`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_PRODUCTS_GQL" fieldPath="$.records[*].sellable" label="Sellable" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="10" description="Y for a sellable product; N for a discontinued, virtual or non-selling product kept only because it carries a NetSuite id no sellable product holds."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_PRODUCTS_GQL|fieldPath=$.records[*].sharedWith`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_PRODUCTS_GQL" fieldPath="$.records[*].sharedWith" label="Shared with" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="9" description="The other sellable HotWax products carrying the same NetSuite id, comma-separated."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_RETURNS_GQL|fieldPath=$.records[*].currencyUomId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_RETURNS_GQL" fieldPath="$.records[*].currencyUomId" label="Currency" fieldType="string" sequenceNum="8" description="Server-side projected."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_RETURNS_GQL|fieldPath=$.records[*].daysBeforeEntry`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_RETURNS_GQL" fieldPath="$.records[*].daysBeforeEntry" label="Days before entry" fieldType="number" sequenceNum="13" description="DAR-BE-116 R8: whole days between the return's own date (returnDate) and when OMS recorded it (entryDate); large for an old return recorded late. GraphQL only. NOT in keepFieldsBase."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_RETURNS_GQL|fieldPath=$.records[*].entryDate`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_RETURNS_GQL" fieldPath="$.records[*].entryDate" label="Created" fieldType="string" sequenceNum="6" description="Epoch milliseconds. The extract windows on it."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_RETURNS_GQL|fieldPath=$.records[*].externalId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_RETURNS_GQL" fieldPath="$.records[*].externalId" label="Shopify refund ID" fieldType="string" isPrimaryIdCandidate="Y" sequenceNum="3" description="The Shopify refund id. BLANK on returns with no refund yet; the extract then fills it from shopifyReturnId. Kept as a candidate so rule sets keyed on it keep resolving."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_RETURNS_GQL|fieldPath=$.records[*].fromPartyId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_RETURNS_GQL" fieldPath="$.records[*].fromPartyId" label="Returned by" fieldType="string" sequenceNum="11" description="DAR-BE-094 R8: who the return is from (DISCONTINUED on gorjana's discontinued-stock returns). GraphQL only. NOT in keepFieldsBase: filters run before projection, so the pill filters without being compared."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_RETURNS_GQL|fieldPath=$.records[*].orderExternalId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_RETURNS_GQL" fieldPath="$.records[*].orderExternalId" label="Shopify order ID" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="4" description="Order spine — the Shopify order id of the return's order, resolved through OMS orders. Absent on a return with no items. Not offered as the ID (DAR-BE-071): one order can carry several returns, and duplicate keys collapse."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_RETURNS_GQL|fieldPath=$.records[*].orderNetsuiteId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_RETURNS_GQL" fieldPath="$.records[*].orderNetsuiteId" label="NetSuite order ID" fieldType="string" sequenceNum="12" description="DAR-BE-116 R8: the origin order's NETSUITE_ORDER_ID in HotWax, blank when that order never reached NetSuite. GraphQL only. NOT in keepFieldsBase: filters and conclusions read it, the compare does not."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_RETURNS_GQL|fieldPath=$.records[*].orderProductStoreId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_RETURNS_GQL" fieldPath="$.records[*].orderProductStoreId" label="Store" fieldType="string" sequenceNum="10" description="DAR-BE-068: the product store of the return's ORIGIN order (Return.productStoreId is unpopulated). NOT in keepFieldsBase: filters run before projection, so the pill filters without being compared. Absent on a return with no items, so an include rule drops those."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_RETURNS_GQL|fieldPath=$.records[*].returnChannelEnumId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_RETURNS_GQL" fieldPath="$.records[*].returnChannelEnumId" label="Return channel" fieldType="string" sequenceNum="9" description="Return channel. Exclusion rules on it run client-side, after the built-in exclusions."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_RETURNS_GQL|fieldPath=$.records[*].returnCredit`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_RETURNS_GQL" fieldPath="$.records[*].returnCredit" label="HotWax credit" fieldType="number" sequenceNum="14" description="DAR-BE-192: the credit HotWax gave the return, each payment its responses refund counted once (0 when none). Only on a side with the withReturnCredit option. A rule that reads it keeps it through projection."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_RETURNS_GQL|fieldPath=$.records[*].returnId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_RETURNS_GQL" fieldPath="$.records[*].returnId" label="HotWax return ID" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="1" description="OMS return identity. Not offered as the ID (DAR-BE-071): HotWax-only, so it never joins to Shopify."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_RETURNS_GQL|fieldPath=$.records[*].returnTotal`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_RETURNS_GQL" fieldPath="$.records[*].returnTotal" label="Return total" fieldType="string" sequenceNum="7" description="Sum of returnPrice x returnQuantity over the return's items."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_RETURNS_GQL|fieldPath=$.records[*].shopifyReturnId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_RETURNS_GQL" fieldPath="$.records[*].shopifyReturnId" label="Shopify return ID" fieldType="string" isPrimaryIdCandidate="Y" sequenceNum="2" description="PREFERRED join key. The Shopify return reference: the SHOPIFY_RTN_ID identification where present, else SHOPIFY_RETURN_ID. Populated on RETURN_REQUESTED returns that have no refund yet. Matches Shopify SHOPIFY_RETURN_REFS's refundOrReturnId."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_RETURNS_GQL|fieldPath=$.records[*].statusId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_RETURNS_GQL" fieldPath="$.records[*].statusId" label="Return status" fieldType="string" sequenceNum="5" description="The RETURN's status. Includes RETURN_REQUESTED; RETURN_CANCELLED is excluded by the extract."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=SHOPIFY_RETURN_REFS|fieldPath=$.records[*].createdAt`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="SHOPIFY_RETURN_REFS" fieldPath="$.records[*].createdAt" label="Created" fieldType="string" sequenceNum="4" description="RELABELLED (was 'Order created', then 'Event created'): the individual refund's or                 return's own creation date — a refund row carries the refund's createdAt, a return row                 carries the return's — never the order's creation date. Windowing filters on this field, not                 on any order-level date."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=SHOPIFY_RETURN_REFS|fieldPath=$.records[*].orderId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="SHOPIFY_RETURN_REFS" fieldPath="$.records[*].orderId" label="Order ID" fieldType="string" isPrimaryIdCandidate="Y" sequenceNum="3" description="bareId(legacyResourceId ?: id) — the bare GID tail, matching the numeric Shopify                 reference the OMS side stores. Unchanged by the per-refund/per-return reshape; still a                 grouping / secondary key, not the join key."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=SHOPIFY_RETURN_REFS|fieldPath=$.records[*].orderReturnStatus`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="SHOPIFY_RETURN_REFS" fieldPath="$.records[*].orderReturnStatus" label="Return status" fieldType="string" sequenceNum="5" description="ADDED 2026-09-01 (DAR-BE-026), replacing the withdrawn returnWorkflowStatus pill —                 see the retirement entry in SourceSystemConnectorFieldWriteSupport.RETIRED_FIELDS. This is                 the endpoint's ONLY return-status field. Holds Shopify's own Order.returnStatus, an                 OrderReturnStatus: IN_PROGRESS, INSPECTION_COMPLETE, NO_RETURN, RETURN_FAILED,                 RETURN_REQUESTED, RETURNED. Those are the values to type, and they are the ones Shopify                 itself shows: the admin renders IN_PROGRESS as 'Return in progress' and the order search                 spells it return_status:in_progress. The withdrawn field held a per-return ReturnStatus                 instead, whose in-progress value is spelled OPEN — a word on no surface an operator could                 see — so excluding IN_PROGRESS matched nothing and reported a zero count, which reads                 exactly like an undeployed feature. Present on EVERY row, refund and return alike, because                 it describes the whole ORDER: excluding IN_PROGRESS therefore drops an in-progress order's                 refund rows as well as its return, which is deliberate — the per-return field it replaced                 could never reach a refund row at all. Key always present, null when Shopify reports                 nothing, so a consumer can tell 'extract predates the field' from 'Shopify said nothing'.                 Exclude IN_PROGRESS to drop orders whose return has not finished yet."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=SHOPIFY_RETURN_REFS|fieldPath=$.records[*].refundOrReturnId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="SHOPIFY_RETURN_REFS" fieldPath="$.records[*].refundOrReturnId" label="Refund/return ID" fieldType="string" isPrimaryIdCandidate="Y" sequenceNum="1" description="RENAMED 2026-08-18 (was '$.records[*].eventId', labelled 'Event ID' then 'Refund or                 Return ID'): 'event' is this extractor's internal abstraction, not a Shopify term, and it                 surfaced verbatim on the rules board, the run summary and the diff columns. bareId-normalized                 identity — a REFUND id when refundOrReturnType is REFUND, a RETURN id when it is RETURN.                 Matches OMS externalId whichever kind it is: the single join key for returns reconciliation,                 with no precedence rule needed on either side."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=SHOPIFY_RETURN_REFS|fieldPath=$.records[*].refundOrReturnType`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="SHOPIFY_RETURN_REFS" fieldPath="$.records[*].refundOrReturnType" label="Record type" fieldType="string" sequenceNum="2" description="RENAMED 2026-08-18 (was '$.records[*].eventType'). REFUND or RETURN — identifies                 which kind of id refundOrReturnId carries on this row. Not a join key; useful for filtering                 or as a comparison field."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=SHOPIFY|fieldPath=$.records[*].cancelledAt`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="SHOPIFY" fieldPath="$.records[*].cancelledAt" label="Cancelled on" fieldType="DateTime" sequenceNum="7" description="Set on a cancelled order."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=SHOPIFY|fieldPath=$.records[*].displayFinancialStatus`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="SHOPIFY" fieldPath="$.records[*].displayFinancialStatus" label="Payment status" fieldType="String" sequenceNum="4" description="PAID, PENDING, PARTIALLY_REFUNDED, REFUNDED, ..."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=SHOPIFY|fieldPath=$.records[*].displayFulfillmentStatus`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="SHOPIFY" fieldPath="$.records[*].displayFulfillmentStatus" label="Fulfillment status" fieldType="String" sequenceNum="3" description="FULFILLED, UNFULFILLED, PARTIALLY_FULFILLED, RESTOCKED, ..."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=SHOPIFY|fieldPath=$.records[*].hasPaymentTransaction`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="SHOPIFY" fieldPath="$.records[*].hasPaymentTransaction" label="Has payment" fieldType="String" sequenceNum="8" description="DAR-BE-064: Y when any SUCCESS SALE, CAPTURE or AUTHORIZATION transaction exists, else N."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=SHOPIFY|fieldPath=$.records[*].id`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="SHOPIFY" fieldPath="$.records[*].id" label="Order ID" fieldType="ID" isPrimaryIdCandidate="Y" sequenceNum="1" description="Shopify order GID."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=SHOPIFY|fieldPath=$.records[*].isFulfilled`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="SHOPIFY" fieldPath="$.records[*].isFulfilled" label="Fulfilled" fieldType="String" sequenceNum="9" description="DAR-BE-125: Y when displayFulfillmentStatus is FULFILLED, else N (partial is N)."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=SHOPIFY|fieldPath=$.records[*].name`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="SHOPIFY" fieldPath="$.records[*].name" label="Order name" fieldType="String" isPrimaryIdCandidate="Y" sequenceNum="2" description="Shopify order name."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=SHOPIFY|fieldPath=$.records[*].paymentTransactionIds`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="SHOPIFY" fieldPath="$.records[*].paymentTransactionIds" label="Payment IDs" fieldType="String" sequenceNum="10" description="DAR-BE-153: comma-joined ids of the SUCCESS SALE and CAPTURE transactions, and of a SUCCESS AUTHORIZATION nothing captured; the ids HotWax keeps on its OPPs as manualRefNum."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=SHOPIFY|fieldPath=$.records[*].requiresShipping`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="SHOPIFY" fieldPath="$.records[*].requiresShipping" label="Needs shipping" fieldType="Boolean" sequenceNum="6" description="false on orders with nothing to ship (gorjana's digital gift cards, POS carry-outs)."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=SHOPIFY|fieldPath=$.records[*].sourceName`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="SHOPIFY" fieldPath="$.records[*].sourceName" label="Sales channel" fieldType="String" sequenceNum="5" description="web, pos, shopify_draft_order, or an app id."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=SHOPIFY|fieldPath=$.records[*].transactionIds`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="SHOPIFY" fieldPath="$.records[*].transactionIds" label="All transaction IDs" fieldType="String" sequenceNum="11" description="DAR-BE-153: comma-joined ids of every SUCCESS transaction on the order, any kind (authorization, capture, sale, void, refund). Each HotWax OPP manualRefNum must be among them (paymentRefNums all in transactionIds); extra Shopify ids are fine."/>
```

### Modified in `data/SystemMessageRemoteSeedData.xml`

- Record: `moqui.service.message.SystemMessageRemote|systemMessageRemoteId=HOTWAX_ORDERS_API`
- Element: `moqui.service.message.SystemMessageRemote`

```xml
<moqui.service.message.SystemMessageRemote systemMessageRemoteId="HOTWAX_ORDERS_API" description="Orders" sendUrl="{baseUrl}/rest/s1/graphql" sendServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlOrders"/>
```

### Modified in `data/SystemMessageRemoteSeedData.xml`

- Record: `moqui.service.message.SystemMessageRemote|systemMessageRemoteId=SHOPIFY_REMOTE`
- Element: `moqui.service.message.SystemMessageRemote`

```xml
<moqui.service.message.SystemMessageRemote systemMessageRemoteId="SHOPIFY_REMOTE" description="Orders" sendUrl="https://{shop}.myshopify.com/admin/api/{apiVersion}/graphql.json" sendServiceName="facade.ShopifyFacadeServices.execute#ShopifyGraphql"/>
```

### Added in `data/TenantConfigSeedData.xml`

- Record: `moqui.service.job.ServiceJob|jobName=snapshot_PendingTenantConfigs_1m`
- Element: `moqui.service.job.ServiceJob`

```xml
<moqui.service.job.ServiceJob jobName="snapshot_PendingTenantConfigs_1m" description="Keep a config version for each tenant whose audit config changed and has been quiet for a minute." serviceName="config.TenantConfigServices.snapshot#PendingTenantConfigs" cronExpression="0 * * * * ?" paused="N"/>
```

## Restated records (not in the range diff)

- 36 records unchanged since `v2.1.12` are restated, verbatim from their seed files, because the hand-kept 3.0.0 load
  target already carried them: the whole audit vocabulary from `data/AuditSeedData.xml` (types `AuditCategory`,
  `AuditStatementWeight`, `AuditFindingState`, `AuditCheckStatus`, `AuditSourceRead`, `AuditFindingEvent`,
  `AuditActorType`, `AuditPeriod`, `AuditDigestKind` and their members), `EmailServer SYSTEM` (parent of the run-update
  template), and the `OMS_PRODUCTS_GQL` system-source enum (parent of its connector row). The load is an upsert, so a
  restated row that already exists is unchanged.
- Where the hand-kept file and the seed disagreed (`SourceSystemConnector OMS_PRODUCTS_GQL` lacked
  `evidenceFieldsJson`), the seed's current record is used.

## Excluded

- `data/test/AuditMorningsFixtureData.xml` and `data/test/ConclusionParityCatalogueData.xml` (`darpan-test-fixture`,
  104 records): test fixtures never reach a deployed database. `release_preflight.py` now skips every `*fixture` type.

## Final order

- Parent before child by entity: enumeration types, enumerations, email server, email template, remotes, jobs, entity
  filters, migrations, connectors, connector fields.

## Recommended operator review

- Confirm every candidate record truly needs to be loaded for the target environment.
- Keep final upgrade records reflected in the appropriate generic source data file, such as a type, security, mapping, job, or system-message seed file.
- Prefer keeping changes in the existing domain seed file unless the release needs a distinct generic setup bundle.
- State the final operator action in `release-notes.md` and `release-checklist.md`.
