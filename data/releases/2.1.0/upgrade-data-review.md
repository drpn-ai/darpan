# Upgrade Data Review For Darpan 2.1.0

## Scope

- Backend compare range: `v2.0.0..HEAD`
- Data directory reviewed: `data`
- Generic source data files are the source of truth for release upgrade data.
- This report lists candidate seed/config records that were added or modified in generic source data files between the compared refs.
- Do not author records directly in `upgrade-data.xml`; add or update the appropriate `runtime/component/darpan/data/*.xml` file and regenerate.

## Decision

- **Loaded on every instance (`upgrade-data.xml`, 98 records):** the candidates from the seven
  `darpan-seed` files: `DarpanSystemSourceSeedData`, `AuditSeedData`, `SecuritySeedData`,
  `AuditDigestEmailSeedData`, `PasswordResetEmailSeedData`, `SourceSystemConnectorSeedData`,
  `SourceSystemConnectorFieldSeedData`, in that order (connectors before their fields).
- **Not upgrade data (229 records):** the candidates below from the 12 `darpan-tenant-config` files
  (`Gorjana*`, `RailsAuditData`, `TriSystemOrderRunsRuleSetData`). They load per tenant on the
  instance that hosts it, file by file, as `release-checklist.md` orders them.
- Every record added to the old `upgrade-data.xml` by hand since 2.0.0 (80) is in the rebuilt file.

## Candidate records

### Added in `data/AuditDigestEmailSeedData.xml`

- Record: `moqui.basic.email.EmailServer|emailServerId=SYSTEM`
- Element: `moqui.basic.email.EmailServer`

```xml
<moqui.basic.email.EmailServer emailServerId="SYSTEM"/>
```

### Added in `data/AuditDigestEmailSeedData.xml`

- Record: `moqui.basic.email.EmailTemplate|emailTemplateId=DARPAN_AUDIT_DIGEST|emailServerId=SYSTEM`
- Element: `moqui.basic.email.EmailTemplate`

```xml
<moqui.basic.email.EmailTemplate emailTemplateId="DARPAN_AUDIT_DIGEST" description="Darpan audit digest" emailServerId="SYSTEM" webappName="webroot" sendPartial="Y" subject="${digestSubject}" fromAddress="${System.getenv('DARPAN_MAIL_FROM') ?: 'support@drpn.ai'}" fromName="Darpan" bodyScreenLocation="component://darpan/screen/email/AuditDigest.xml"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.EnumerationType|enumTypeId=AuditActorType`
- Element: `moqui.basic.EnumerationType`

```xml
<moqui.basic.EnumerationType enumTypeId="AuditActorType" description="Audit event actor type"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.EnumerationType|enumTypeId=AuditCategory`
- Element: `moqui.basic.EnumerationType`

```xml
<moqui.basic.EnumerationType enumTypeId="AuditCategory" description="Audit statement category"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.EnumerationType|enumTypeId=AuditCheckStatus`
- Element: `moqui.basic.EnumerationType`

```xml
<moqui.basic.EnumerationType enumTypeId="AuditCheckStatus" description="Audit check status"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.EnumerationType|enumTypeId=AuditDigestKind`
- Element: `moqui.basic.EnumerationType`

```xml
<moqui.basic.EnumerationType enumTypeId="AuditDigestKind" description="Audit digest kind"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.EnumerationType|enumTypeId=AuditFindingEvent`
- Element: `moqui.basic.EnumerationType`

```xml
<moqui.basic.EnumerationType enumTypeId="AuditFindingEvent" description="Audit finding history event"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.EnumerationType|enumTypeId=AuditFindingState`
- Element: `moqui.basic.EnumerationType`

```xml
<moqui.basic.EnumerationType enumTypeId="AuditFindingState" description="Audit finding state"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.EnumerationType|enumTypeId=AuditPeriod`
- Element: `moqui.basic.EnumerationType`

```xml
<moqui.basic.EnumerationType enumTypeId="AuditPeriod" description="Audit reporting period"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.EnumerationType|enumTypeId=AuditSourceRead`
- Element: `moqui.basic.EnumerationType`

```xml
<moqui.basic.EnumerationType enumTypeId="AuditSourceRead" description="Audit source read status"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.EnumerationType|enumTypeId=AuditStatementWeight`
- Element: `moqui.basic.EnumerationType`

```xml
<moqui.basic.EnumerationType enumTypeId="AuditStatementWeight" description="Audit statement weight"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDAT_CHECK|enumTypeId=AuditActorType|enumCode=check`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDAT_CHECK" enumTypeId="AuditActorType" enumCode="check" description="Scheduled check" sequenceNum="1"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDAT_USER|enumTypeId=AuditActorType|enumCode=user`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDAT_USER" enumTypeId="AuditActorType" enumCode="user" description="Darpan user" sequenceNum="2"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDCS_NOT_RUN|enumTypeId=AuditCheckStatus|enumCode=notRun`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDCS_NOT_RUN" enumTypeId="AuditCheckStatus" enumCode="notRun" description="Not run" sequenceNum="3"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDCS_PARTIAL|enumTypeId=AuditCheckStatus|enumCode=partial`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDCS_PARTIAL" enumTypeId="AuditCheckStatus" enumCode="partial" description="Partial: a read was partial or failed, nothing closed" sequenceNum="2"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDCS_RAN|enumTypeId=AuditCheckStatus|enumCode=ran`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDCS_RAN" enumTypeId="AuditCheckStatus" enumCode="ran" description="Ran over complete reads" sequenceNum="1"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDC_EXCHANGES_WARRANTY|enumTypeId=AuditCategory|enumCode=exchangesAndWarranty`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDC_EXCHANGES_WARRANTY" enumTypeId="AuditCategory" enumCode="exchangesAndWarranty" description="Exchanges and warranty" sequenceNum="4"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDC_ORDERS|enumTypeId=AuditCategory|enumCode=orders`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDC_ORDERS" enumTypeId="AuditCategory" enumCode="orders" description="Orders" sequenceNum="1"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDC_REFUNDS_CREDITS|enumTypeId=AuditCategory|enumCode=refundsAndCredits`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDC_REFUNDS_CREDITS" enumTypeId="AuditCategory" enumCode="refundsAndCredits" description="Refunds and credits" sequenceNum="2"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDC_RETURNS|enumTypeId=AuditCategory|enumCode=returns`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDC_RETURNS" enumTypeId="AuditCategory" enumCode="returns" description="Returns" sequenceNum="3"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDDK_DAILY|enumTypeId=AuditDigestKind|enumCode=daily`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDDK_DAILY" enumTypeId="AuditDigestKind" enumCode="daily" description="Daily digest" sequenceNum="1"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDDK_WEEKLY|enumTypeId=AuditDigestKind|enumCode=weekly`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDDK_WEEKLY" enumTypeId="AuditDigestKind" enumCode="weekly" description="Weekly summary" sequenceNum="2"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDFE_FIXED|enumTypeId=AuditFindingEvent|enumCode=fixed`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDFE_FIXED" enumTypeId="AuditFindingEvent" enumCode="fixed" description="Fixed" sequenceNum="3"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDFE_FOUND|enumTypeId=AuditFindingEvent|enumCode=found`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDFE_FOUND" enumTypeId="AuditFindingEvent" enumCode="found" description="Found" sequenceNum="1"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDFE_REOPENED|enumTypeId=AuditFindingEvent|enumCode=reopened`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDFE_REOPENED" enumTypeId="AuditFindingEvent" enumCode="reopened" description="Reopened" sequenceNum="4"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDFE_STILL_OPEN|enumTypeId=AuditFindingEvent|enumCode=stillOpen`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDFE_STILL_OPEN" enumTypeId="AuditFindingEvent" enumCode="stillOpen" description="Still open" sequenceNum="2"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDFS_FIXED|enumTypeId=AuditFindingState|enumCode=fixed`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDFS_FIXED" enumTypeId="AuditFindingState" enumCode="fixed" description="Fixed" sequenceNum="3"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDFS_OPEN|enumTypeId=AuditFindingState|enumCode=open`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDFS_OPEN" enumTypeId="AuditFindingState" enumCode="open" description="Open" sequenceNum="1"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDFS_PENDING|enumTypeId=AuditFindingState|enumCode=pending`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDFS_PENDING" enumTypeId="AuditFindingState" enumCode="pending" description="Pending: inside the check's grace age" sequenceNum="2"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDP_DAY|enumTypeId=AuditPeriod|enumCode=day`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDP_DAY" enumTypeId="AuditPeriod" enumCode="day" description="Day" sequenceNum="1"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDP_MONTH|enumTypeId=AuditPeriod|enumCode=month`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDP_MONTH" enumTypeId="AuditPeriod" enumCode="month" description="Month" sequenceNum="2"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDP_QUARTER|enumTypeId=AuditPeriod|enumCode=quarter`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDP_QUARTER" enumTypeId="AuditPeriod" enumCode="quarter" description="Quarter" sequenceNum="3"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDSR_COMPLETE|enumTypeId=AuditSourceRead|enumCode=complete`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDSR_COMPLETE" enumTypeId="AuditSourceRead" enumCode="complete" description="Complete" sequenceNum="1"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDSR_FAILED|enumTypeId=AuditSourceRead|enumCode=failed`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDSR_FAILED" enumTypeId="AuditSourceRead" enumCode="failed" description="Failed" sequenceNum="3"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDSR_PARTIAL|enumTypeId=AuditSourceRead|enumCode=partial`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDSR_PARTIAL" enumTypeId="AuditSourceRead" enumCode="partial" description="Partial" sequenceNum="2"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDSW_DERIVED|enumTypeId=AuditStatementWeight|enumCode=derived`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDSW_DERIVED" enumTypeId="AuditStatementWeight" enumCode="derived" description="Derived: awaiting the tenant's confirmation" sequenceNum="2"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDSW_FIRM|enumTypeId=AuditStatementWeight|enumCode=firm`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDSW_FIRM" enumTypeId="AuditStatementWeight" enumCode="firm" description="Firm: stated by the tenant" sequenceNum="1"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.service.job.ServiceJob|jobName=send_AuditDigests_15m`
- Element: `moqui.service.job.ServiceJob`

```xml
<moqui.service.job.ServiceJob jobName="send_AuditDigests_15m" description="Send the audit run digests that are due." serviceName="audit.AuditDigestServices.send#DueAuditDigests" cronExpression="0 0/15 * * * ?" paused="N"/>
```

### Added in `data/DarpanSystemSourceSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=OMS_ORDER_ITEMS_GQL|enumTypeId=DarpanSystemSource|parentEnumId=OMS|enumCode=HOTWAX_GQL_ORDER_ITEMS`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="OMS_ORDER_ITEMS_GQL" enumTypeId="DarpanSystemSource" enumCode="HOTWAX_GQL_ORDER_ITEMS" description="HotWax order lines (NetSuite)" sequenceNum="13" parentEnumId="OMS"/>
```

### Modified in `data/GorjanaConclusionRulesData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_OMS_NS_LINE_SCOPE|conclusionEnumId=CONC_BILLED_NOT_COMPLETED|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_OMS_NS_LINE_SCOPE" sequenceNum="30" fixActionTemplate="Complete item {recordKey} in HotWax: NetSuite already billed it." conclusionEnumId="CONC_BILLED_NOT_COMPLETED" appliesToBucket="MISSING_FROM_FILE_1" companyUserGroupId="GORJANA"/>
```

### Modified in `data/GorjanaConclusionRulesData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_OMS_NS_LINE_SCOPE|conclusionEnumId=CONC_COMPLETED_CLOSED_IN_NS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_OMS_NS_LINE_SCOPE" sequenceNum="5" fixActionTemplate="NetSuite closed the line HotWax completed: reopen and bill it in NetSuite, or cancel item {recordKey} in HotWax if it never shipped." conclusionEnumId="CONC_COMPLETED_CLOSED_IN_NS" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaConclusionRulesData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_OMS_NS_LINE_SCOPE|conclusionEnumId=CONC_DUPLICATE_ORDER_NO_NS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_OMS_NS_LINE_SCOPE" sequenceNum="19" fixActionTemplate="Nothing to fix in NetSuite: HotWax marks this order as a duplicate, and a duplicate is not sent to NetSuite. Cancel it in HotWax so it stops reading as completed." conclusionEnumId="CONC_DUPLICATE_ORDER_NO_NS" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaConclusionRulesData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_OMS_NS_LINE_SCOPE|conclusionEnumId=CONC_FULFILLED_WITHIN_DAY|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_OMS_NS_LINE_SCOPE" sequenceNum="14" fixActionTemplate="Nothing to fix yet: NetSuite bills a fulfilled item within 24 hours of HotWax fulfilling it. If item {recordKey} is still not billed after that, Darpan raises it." conclusionEnumId="CONC_FULFILLED_WITHIN_DAY" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="GORJANA"/>
```

### Modified in `data/GorjanaConclusionRulesData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_OMS_NS_LINE_SCOPE|conclusionEnumId=CONC_NEVER_REACHED_NS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_OMS_NS_LINE_SCOPE" sequenceNum="20" fixActionTemplate="Re-run the order sync in HotWax for item {recordKey}: NetSuite has no sales order for it." conclusionEnumId="CONC_NEVER_REACHED_NS" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaConclusionRulesData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_OMS_NS_LINE_SCOPE|conclusionEnumId=CONC_NOT_SHIPPED_IN_NS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_OMS_NS_LINE_SCOPE" sequenceNum="16" fixActionTemplate="Send the fulfillment of item {recordKey} to NetSuite: HotWax fulfilled it over 24 hours ago and NetSuite still shows the line not shipped, so it cannot bill it." conclusionEnumId="CONC_NOT_SHIPPED_IN_NS" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="GORJANA"/>
```

### Modified in `data/GorjanaConclusionRulesData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_OMS_NS_LINE_SCOPE|conclusionEnumId=CONC_NS_BACKORDERED|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_OMS_NS_LINE_SCOPE" sequenceNum="10" fixActionTemplate="Fulfil the backordered line in NetSuite ({quantityBackordered} on backorder); it is billed once it ships." conclusionEnumId="CONC_NS_BACKORDERED" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaConclusionRulesData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_OMS_NS_LINE_SCOPE|conclusionEnumId=CONC_NS_NO_LINE_LOCATION|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_OMS_NS_LINE_SCOPE" sequenceNum="8" fixActionTemplate="Set the location on the NetSuite line of item {recordKey}; NetSuite can then ship and bill it." conclusionEnumId="CONC_NS_NO_LINE_LOCATION" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaConclusionRulesData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_OMS_NS_LINE_SCOPE|conclusionEnumId=CONC_ORDER_BACKORDER_HOLDS_BILLING|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_OMS_NS_LINE_SCOPE" sequenceNum="12" fixActionTemplate="Fulfil the backordered lines of {orderInNetsuite.tranId} in NetSuite." conclusionEnumId="CONC_ORDER_BACKORDER_HOLDS_BILLING" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaConclusionRulesData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_OMS_NS_LINE_SCOPE|conclusionEnumId=CONC_PARTIAL_PAYMENT_NO_NS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_OMS_NS_LINE_SCOPE" sequenceNum="18" fixActionTemplate="Nothing to fix in NetSuite: HotWax sends an order only once it is paid, and this one's payment was taken back (order total {orderGrandTotal}). It syncs once paid; cancel it in HotWax if the sale did not stand." conclusionEnumId="CONC_PARTIAL_PAYMENT_NO_NS" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="GORJANA"/>
```

### Modified in `data/GorjanaConclusionRulesData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_OMS_NS_LINE_SCOPE|conclusionEnumId=CONC_SHIPPED_NOT_BILLED|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_OMS_NS_LINE_SCOPE" sequenceNum="15" fixActionTemplate="Bill the shipped line in NetSuite: it shipped over 24 hours ago and its sales order is not invoiced." conclusionEnumId="CONC_SHIPPED_NOT_BILLED" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="GORJANA"/>
```

### Modified in `data/GorjanaConclusionRulesData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_BILLED_NOT_COMPLETED|enumTypeId=DarpanConclusion|enumCode=CONC_BILLED_NOT_COMPLETED`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_BILLED_NOT_COMPLETED" enumTypeId="DarpanConclusion" enumCode="CONC_BILLED_NOT_COMPLETED" description="Billed, not completed"/>
```

### Modified in `data/GorjanaConclusionRulesData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_COMPLETED_CLOSED_IN_NS|enumTypeId=DarpanConclusion|enumCode=CONC_COMPLETED_CLOSED_IN_NS`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_COMPLETED_CLOSED_IN_NS" enumTypeId="DarpanConclusion" enumCode="CONC_COMPLETED_CLOSED_IN_NS" description="Closed in NetSuite"/>
```

### Added in `data/GorjanaConclusionRulesData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_DUPLICATE_ORDER_NO_NS|enumTypeId=DarpanConclusion|enumCode=CONC_DUPLICATE_ORDER_NO_NS`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_DUPLICATE_ORDER_NO_NS" enumTypeId="DarpanConclusion" enumCode="CONC_DUPLICATE_ORDER_NO_NS" description="Duplicate order"/>
```

### Added in `data/GorjanaConclusionRulesData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_FULFILLED_WITHIN_DAY|enumTypeId=DarpanConclusion|enumCode=CONC_FULFILLED_WITHIN_DAY`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_FULFILLED_WITHIN_DAY" enumTypeId="DarpanConclusion" enumCode="CONC_FULFILLED_WITHIN_DAY" description="Billing within 24 hours"/>
```

### Modified in `data/GorjanaConclusionRulesData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_NEVER_REACHED_NS|enumTypeId=DarpanConclusion|enumCode=CONC_NEVER_REACHED_NS`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_NEVER_REACHED_NS" enumTypeId="DarpanConclusion" enumCode="CONC_NEVER_REACHED_NS" description="Not in NetSuite"/>
```

### Added in `data/GorjanaConclusionRulesData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_NOT_SHIPPED_IN_NS|enumTypeId=DarpanConclusion|enumCode=CONC_NOT_SHIPPED_IN_NS`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_NOT_SHIPPED_IN_NS" enumTypeId="DarpanConclusion" enumCode="CONC_NOT_SHIPPED_IN_NS" description="Not shipped after 24 hours"/>
```

### Modified in `data/GorjanaConclusionRulesData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_NS_BACKORDERED|enumTypeId=DarpanConclusion|enumCode=CONC_NS_BACKORDERED`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_NS_BACKORDERED" enumTypeId="DarpanConclusion" enumCode="CONC_NS_BACKORDERED" description="Backordered"/>
```

### Added in `data/GorjanaConclusionRulesData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_NS_NO_LINE_LOCATION|enumTypeId=DarpanConclusion|enumCode=CONC_NS_NO_LINE_LOCATION`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_NS_NO_LINE_LOCATION" enumTypeId="DarpanConclusion" enumCode="CONC_NS_NO_LINE_LOCATION" description="No location"/>
```

### Added in `data/GorjanaConclusionRulesData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_ORDER_BACKORDER_HOLDS_BILLING|enumTypeId=DarpanConclusion|relatedEnumId=CONC_NS_BACKORDERED|enumCode=CONC_ORDER_BACKORDER_HOLDS_BILLING`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_ORDER_BACKORDER_HOLDS_BILLING" enumTypeId="DarpanConclusion" enumCode="CONC_ORDER_BACKORDER_HOLDS_BILLING" description="Held by a backordered line" relatedEnumId="CONC_NS_BACKORDERED"/>
```

### Added in `data/GorjanaConclusionRulesData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_PARTIAL_PAYMENT_NO_NS|enumTypeId=DarpanConclusion|enumCode=CONC_PARTIAL_PAYMENT_NO_NS`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_PARTIAL_PAYMENT_NO_NS" enumTypeId="DarpanConclusion" enumCode="CONC_PARTIAL_PAYMENT_NO_NS" description="Partial payment"/>
```

### Modified in `data/GorjanaConclusionRulesData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_SHIPPED_NOT_BILLED|enumTypeId=DarpanConclusion|enumCode=CONC_SHIPPED_NOT_BILLED`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_SHIPPED_NOT_BILLED" enumTypeId="DarpanConclusion" enumCode="CONC_SHIPPED_NOT_BILLED" description="Shipped, not billed"/>
```

### Added in `data/GorjanaMonthCloseCatalogData.xml`

- Record: `darpan.audit.AuditStatement|statementId=GORJANA_R10|auditId=GORJANA_MONTH_CLOSE|companyUserGroupId=GORJANA|categoryEnumId=AUDC_RETURNS|weightEnumId=AUDSW_DERIVED`
- Element: `darpan.audit.AuditStatement`

```xml
<darpan.audit.AuditStatement statementId="GORJANA_R10" auditId="GORJANA_MONTH_CLOSE" companyUserGroupId="GORJANA" statementCode="R10" isActive="N" categoryEnumId="AUDC_RETURNS" weightEnumId="AUDSW_DERIVED" sequenceNum="10" statementText="A returned piece goes back into stock as Good when it is resold, and as QC when it is damaged."/>
```

### Added in `data/GorjanaMonthCloseCatalogData.xml`

- Record: `darpan.audit.AuditStatement|statementId=GORJANA_R11|auditId=GORJANA_MONTH_CLOSE|companyUserGroupId=GORJANA|categoryEnumId=AUDC_RETURNS|weightEnumId=AUDSW_DERIVED`
- Element: `darpan.audit.AuditStatement`

```xml
<darpan.audit.AuditStatement statementId="GORJANA_R11" auditId="GORJANA_MONTH_CLOSE" companyUserGroupId="GORJANA" statementCode="R11" isActive="N" categoryEnumId="AUDC_RETURNS" weightEnumId="AUDSW_DERIVED" sequenceNum="11" statementText="A warranty piece that arrives is booked, even when the claim is already closed."/>
```

### Added in `data/GorjanaMonthCloseCatalogData.xml`

- Record: `darpan.audit.AuditStatement|statementId=GORJANA_R12|auditId=GORJANA_MONTH_CLOSE|companyUserGroupId=GORJANA|categoryEnumId=AUDC_EXCHANGES_WARRANTY|weightEnumId=AUDSW_DERIVED`
- Element: `darpan.audit.AuditStatement`

```xml
<darpan.audit.AuditStatement statementId="GORJANA_R12" auditId="GORJANA_MONTH_CLOSE" companyUserGroupId="GORJANA" statementCode="R12" categoryEnumId="AUDC_EXCHANGES_WARRANTY" weightEnumId="AUDSW_DERIVED" sequenceNum="12" statementText="A warranty replacement is paid by that claim's own credit, at the claim's value. The credit and the replacement invoice close each other out."/>
```

### Added in `data/GorjanaMonthCloseCatalogData.xml`

- Record: `darpan.audit.AuditStatement|statementId=GORJANA_R13|auditId=GORJANA_MONTH_CLOSE|companyUserGroupId=GORJANA|categoryEnumId=AUDC_EXCHANGES_WARRANTY|weightEnumId=AUDSW_DERIVED`
- Element: `darpan.audit.AuditStatement`

```xml
<darpan.audit.AuditStatement statementId="GORJANA_R13" auditId="GORJANA_MONTH_CLOSE" companyUserGroupId="GORJANA" statementCode="R13" isActive="N" categoryEnumId="AUDC_EXCHANGES_WARRANTY" weightEnumId="AUDSW_DERIVED" sequenceNum="13" statementText="A return is never treated as replaced by the order it came from."/>
```

### Added in `data/GorjanaMonthCloseCatalogData.xml`

- Record: `darpan.audit.AuditStatement|statementId=GORJANA_R14|auditId=GORJANA_MONTH_CLOSE|companyUserGroupId=GORJANA|categoryEnumId=AUDC_EXCHANGES_WARRANTY|weightEnumId=AUDSW_DERIVED`
- Element: `darpan.audit.AuditStatement`

```xml
<darpan.audit.AuditStatement statementId="GORJANA_R14" auditId="GORJANA_MONTH_CLOSE" companyUserGroupId="GORJANA" statementCode="R14" isActive="N" categoryEnumId="AUDC_EXCHANGES_WARRANTY" weightEnumId="AUDSW_DERIVED" sequenceNum="14" statementText="Every exchange credit belongs to a customer. When the shopper gave none, it belongs to the store's own customer."/>
```

### Added in `data/GorjanaMonthCloseCatalogData.xml`

- Record: `darpan.audit.AuditStatement|statementId=GORJANA_R15|auditId=GORJANA_MONTH_CLOSE|companyUserGroupId=GORJANA|categoryEnumId=AUDC_EXCHANGES_WARRANTY|weightEnumId=AUDSW_DERIVED`
- Element: `darpan.audit.AuditStatement`

```xml
<darpan.audit.AuditStatement statementId="GORJANA_R15" auditId="GORJANA_MONTH_CLOSE" companyUserGroupId="GORJANA" statementCode="R15" isActive="N" categoryEnumId="AUDC_EXCHANGES_WARRANTY" weightEnumId="AUDSW_DERIVED" sequenceNum="15" statementText="An exchange piece handed over at a store is booked at that store."/>
```

### Added in `data/GorjanaMonthCloseCatalogData.xml`

- Record: `darpan.audit.AuditStatement|statementId=GORJANA_R16|auditId=GORJANA_MONTH_CLOSE|companyUserGroupId=GORJANA|categoryEnumId=AUDC_EXCHANGES_WARRANTY|weightEnumId=AUDSW_DERIVED`
- Element: `darpan.audit.AuditStatement`

```xml
<darpan.audit.AuditStatement statementId="GORJANA_R16" auditId="GORJANA_MONTH_CLOSE" companyUserGroupId="GORJANA" statementCode="R16" isActive="N" categoryEnumId="AUDC_EXCHANGES_WARRANTY" weightEnumId="AUDSW_DERIVED" sequenceNum="16" statementText="A cancelled exchange leaves nothing open in NetSuite."/>
```

### Added in `data/GorjanaMonthCloseCatalogData.xml`

- Record: `darpan.audit.AuditStatement|statementId=GORJANA_R17|auditId=GORJANA_MONTH_CLOSE|companyUserGroupId=GORJANA|categoryEnumId=AUDC_EXCHANGES_WARRANTY|weightEnumId=AUDSW_DERIVED`
- Element: `darpan.audit.AuditStatement`

```xml
<darpan.audit.AuditStatement statementId="GORJANA_R17" auditId="GORJANA_MONTH_CLOSE" companyUserGroupId="GORJANA" statementCode="R17" isActive="N" categoryEnumId="AUDC_EXCHANGES_WARRANTY" weightEnumId="AUDSW_DERIVED" sequenceNum="17" statementText="A store floor damage is booked in NetSuite, or reported with its reason."/>
```

### Added in `data/GorjanaMonthCloseCatalogData.xml`

- Record: `darpan.audit.AuditStatement|statementId=GORJANA_R18|auditId=GORJANA_MONTH_CLOSE|companyUserGroupId=GORJANA|categoryEnumId=AUDC_ORDERS|weightEnumId=AUDSW_DERIVED`
- Element: `darpan.audit.AuditStatement`

```xml
<darpan.audit.AuditStatement statementId="GORJANA_R18" auditId="GORJANA_MONTH_CLOSE" companyUserGroupId="GORJANA" statementCode="R18" isActive="N" categoryEnumId="AUDC_ORDERS" weightEnumId="AUDSW_DERIVED" sequenceNum="18" statementText="An order HotWax completed or cancelled does not stay open in NetSuite."/>
```

### Added in `data/GorjanaMonthCloseCatalogData.xml`

- Record: `darpan.audit.AuditStatement|statementId=GORJANA_R19|auditId=GORJANA_MONTH_CLOSE|companyUserGroupId=GORJANA|categoryEnumId=AUDC_ORDERS|weightEnumId=AUDSW_DERIVED`
- Element: `darpan.audit.AuditStatement`

```xml
<darpan.audit.AuditStatement statementId="GORJANA_R19" auditId="GORJANA_MONTH_CLOSE" companyUserGroupId="GORJANA" statementCode="R19" categoryEnumId="AUDC_ORDERS" weightEnumId="AUDSW_DERIVED" sequenceNum="19" statementText="Every order paid in Shopify has its payment in HotWax."/>
```

### Added in `data/GorjanaMonthCloseCatalogData.xml`

- Record: `darpan.audit.AuditStatement|statementId=GORJANA_R1|auditId=GORJANA_MONTH_CLOSE|companyUserGroupId=GORJANA|categoryEnumId=AUDC_ORDERS|weightEnumId=AUDSW_FIRM`
- Element: `darpan.audit.AuditStatement`

```xml
<darpan.audit.AuditStatement statementId="GORJANA_R1" auditId="GORJANA_MONTH_CLOSE" companyUserGroupId="GORJANA" statementCode="R1" categoryEnumId="AUDC_ORDERS" weightEnumId="AUDSW_FIRM" sequenceNum="1" statementText="Every order that is fulfilled, in store or online, is in NetSuite with its invoice. This includes digital gift cards."/>
```

### Added in `data/GorjanaMonthCloseCatalogData.xml`

- Record: `darpan.audit.AuditStatement|statementId=GORJANA_R20|auditId=GORJANA_MONTH_CLOSE|companyUserGroupId=GORJANA|categoryEnumId=AUDC_ORDERS|weightEnumId=AUDSW_DERIVED`
- Element: `darpan.audit.AuditStatement`

```xml
<darpan.audit.AuditStatement statementId="GORJANA_R20" auditId="GORJANA_MONTH_CLOSE" companyUserGroupId="GORJANA" statementCode="R20" categoryEnumId="AUDC_ORDERS" weightEnumId="AUDSW_DERIVED" sequenceNum="20" statementText="Every order HotWax completed is fulfilled in Shopify."/>
```

### Added in `data/GorjanaMonthCloseCatalogData.xml`

- Record: `darpan.audit.AuditStatement|statementId=GORJANA_R2|auditId=GORJANA_MONTH_CLOSE|companyUserGroupId=GORJANA|categoryEnumId=AUDC_ORDERS|weightEnumId=AUDSW_FIRM`
- Element: `darpan.audit.AuditStatement`

```xml
<darpan.audit.AuditStatement statementId="GORJANA_R2" auditId="GORJANA_MONTH_CLOSE" companyUserGroupId="GORJANA" statementCode="R2" isActive="N" categoryEnumId="AUDC_ORDERS" weightEnumId="AUDSW_FIRM" sequenceNum="2" statementText="Every invoice and credit memo carries the Shopify order id (the Etail id)."/>
```

### Added in `data/GorjanaMonthCloseCatalogData.xml`

- Record: `darpan.audit.AuditStatement|statementId=GORJANA_R3|auditId=GORJANA_MONTH_CLOSE|companyUserGroupId=GORJANA|categoryEnumId=AUDC_ORDERS|weightEnumId=AUDSW_DERIVED`
- Element: `darpan.audit.AuditStatement`

```xml
<darpan.audit.AuditStatement statementId="GORJANA_R3" auditId="GORJANA_MONTH_CLOSE" companyUserGroupId="GORJANA" statementCode="R3" isActive="N" categoryEnumId="AUDC_ORDERS" weightEnumId="AUDSW_DERIVED" sequenceNum="3" statementText="Every store sale carries its store in NetSuite."/>
```

### Added in `data/GorjanaMonthCloseCatalogData.xml`

- Record: `darpan.audit.AuditStatement|statementId=GORJANA_R4|auditId=GORJANA_MONTH_CLOSE|companyUserGroupId=GORJANA|categoryEnumId=AUDC_REFUNDS_CREDITS|weightEnumId=AUDSW_DERIVED`
- Element: `darpan.audit.AuditStatement`

```xml
<darpan.audit.AuditStatement statementId="GORJANA_R4" auditId="GORJANA_MONTH_CLOSE" companyUserGroupId="GORJANA" statementCode="R4" isActive="N" categoryEnumId="AUDC_REFUNDS_CREDITS" weightEnumId="AUDSW_DERIVED" sequenceNum="4" statementText="Every refund we give a customer is booked in NetSuite at the same amount, to the cent, with nothing left open."/>
```

### Added in `data/GorjanaMonthCloseCatalogData.xml`

- Record: `darpan.audit.AuditStatement|statementId=GORJANA_R5|auditId=GORJANA_MONTH_CLOSE|companyUserGroupId=GORJANA|categoryEnumId=AUDC_REFUNDS_CREDITS|weightEnumId=AUDSW_FIRM`
- Element: `darpan.audit.AuditStatement`

```xml
<darpan.audit.AuditStatement statementId="GORJANA_R5" auditId="GORJANA_MONTH_CLOSE" companyUserGroupId="GORJANA" statementCode="R5" isActive="N" categoryEnumId="AUDC_REFUNDS_CREDITS" weightEnumId="AUDSW_FIRM" sequenceNum="5" statementText="Store credit given in Shopify is in NetSuite at the same amount."/>
```

### Added in `data/GorjanaMonthCloseCatalogData.xml`

- Record: `darpan.audit.AuditStatement|statementId=GORJANA_R6|auditId=GORJANA_MONTH_CLOSE|companyUserGroupId=GORJANA|categoryEnumId=AUDC_REFUNDS_CREDITS|weightEnumId=AUDSW_DERIVED`
- Element: `darpan.audit.AuditStatement`

```xml
<darpan.audit.AuditStatement statementId="GORJANA_R6" auditId="GORJANA_MONTH_CLOSE" companyUserGroupId="GORJANA" statementCode="R6" isActive="N" categoryEnumId="AUDC_REFUNDS_CREDITS" weightEnumId="AUDSW_DERIVED" sequenceNum="6" statementText="A gift card refund is booked as a gift card, even when it comes weeks after the return."/>
```

### Added in `data/GorjanaMonthCloseCatalogData.xml`

- Record: `darpan.audit.AuditStatement|statementId=GORJANA_R7|auditId=GORJANA_MONTH_CLOSE|companyUserGroupId=GORJANA|categoryEnumId=AUDC_REFUNDS_CREDITS|weightEnumId=AUDSW_FIRM`
- Element: `darpan.audit.AuditStatement`

```xml
<darpan.audit.AuditStatement statementId="GORJANA_R7" auditId="GORJANA_MONTH_CLOSE" companyUserGroupId="GORJANA" statementCode="R7" isActive="N" categoryEnumId="AUDC_REFUNDS_CREDITS" weightEnumId="AUDSW_FIRM" sequenceNum="7" statementText="A Happiness Guarantee claim is booked to the Happiness Guarantee account, 42065."/>
```

### Added in `data/GorjanaMonthCloseCatalogData.xml`

- Record: `darpan.audit.AuditStatement|statementId=GORJANA_R8|auditId=GORJANA_MONTH_CLOSE|companyUserGroupId=GORJANA|categoryEnumId=AUDC_RETURNS|weightEnumId=AUDSW_DERIVED`
- Element: `darpan.audit.AuditStatement`

```xml
<darpan.audit.AuditStatement statementId="GORJANA_R8" auditId="GORJANA_MONTH_CLOSE" companyUserGroupId="GORJANA" statementCode="R8" categoryEnumId="AUDC_RETURNS" weightEnumId="AUDSW_DERIVED" sequenceNum="8" statementText="Every return reaches NetSuite."/>
```

### Added in `data/GorjanaMonthCloseCatalogData.xml`

- Record: `darpan.audit.AuditStatement|statementId=GORJANA_R9|auditId=GORJANA_MONTH_CLOSE|companyUserGroupId=GORJANA|categoryEnumId=AUDC_RETURNS|weightEnumId=AUDSW_FIRM`
- Element: `darpan.audit.AuditStatement`

```xml
<darpan.audit.AuditStatement statementId="GORJANA_R9" auditId="GORJANA_MONTH_CLOSE" companyUserGroupId="GORJANA" statementCode="R9" isActive="N" categoryEnumId="AUDC_RETURNS" weightEnumId="AUDSW_FIRM" sequenceNum="9" statementText="Every return carries the customer's full name and the address it came from."/>
```

### Added in `data/GorjanaMonthCloseCatalogData.xml`

- Record: `darpan.audit.Audit|auditId=GORJANA_MONTH_CLOSE|companyUserGroupId=GORJANA|periodEnumId=AUDP_MONTH`
- Element: `darpan.audit.Audit`

```xml
<darpan.audit.Audit auditId="GORJANA_MONTH_CLOSE" companyUserGroupId="GORJANA" auditCode="month-close" auditName="Month close" periodEnumId="AUDP_MONTH"/>
```

### Added in `data/GorjanaMonthCloseChecksData.xml`

- Record: `darpan.audit.AuditCheck|checkId=GORJANA_CHK_COMPLETED_BILLED|companyUserGroupId=GORJANA|savedRunId=GORJANA_OMS_NS_LINE_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="GORJANA_CHK_COMPLETED_BILLED" otherSideDateField="tranDate" companyUserGroupId="GORJANA" checkCode="completed-not-billed" label="HotWax completed items against NetSuite billed lines" savedRunId="GORJANA_OMS_NS_LINE_RS" file1RecordType="orderItem" file2RecordType="salesOrderLine" graceDays="0" matchedBy="OMS order id + item seq id (custbody_hc_order_id + custcol_hc_order_line_id)" fixOwner="HotWax Support" fixWhereEnumId="NETSUITE" headlineTemplate="Item {recordKey} is completed in HotWax and not billed in NetSuite" fixActionTemplate="Bill the NetSuite line for item {recordKey}, or sync the order if NetSuite does not have it." passesWhenTemplate="NetSuite has billed the line for item {recordKey}."/>
```

### Added in `data/GorjanaMonthCloseChecksData.xml`

- Record: `darpan.audit.AuditCheck|checkId=GORJANA_CHK_CREDIT_ETAIL|companyUserGroupId=GORJANA|savedRunId=GORJANA_CREDIT_ETAIL_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="GORJANA_CHK_CREDIT_ETAIL" companyUserGroupId="GORJANA" checkCode="credit-memos-without-etail-id" label="NetSuite credit memos with no Etail id" savedRunId="GORJANA_CREDIT_ETAIL_RS" file1RecordType="creditMemo" graceDays="1" fixOwner="Gorjana Accounting" fixWhereEnumId="NETSUITE" headlineTemplate="Credit memo {recordKey} has no Etail id" fixActionTemplate="Enter the Shopify order id in the Etail Order Id field of credit memo {recordKey}." passesWhenTemplate="Credit memo {recordKey} carries an Etail id."/>
```

### Added in `data/GorjanaMonthCloseChecksData.xml`

- Record: `darpan.audit.AuditCheck|checkId=GORJANA_CHK_INVOICE_ETAIL|companyUserGroupId=GORJANA|savedRunId=GORJANA_INVOICE_ETAIL_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="GORJANA_CHK_INVOICE_ETAIL" companyUserGroupId="GORJANA" checkCode="invoices-without-etail-id" label="NetSuite invoices with no Etail id" savedRunId="GORJANA_INVOICE_ETAIL_RS" file1RecordType="invoice" graceDays="1" fixOwner="Gorjana Accounting" fixWhereEnumId="NETSUITE" headlineTemplate="Invoice {recordKey} has no Etail id" fixActionTemplate="Enter the Shopify order id in the Etail Order Id field of invoice {recordKey}." passesWhenTemplate="Invoice {recordKey} carries an Etail id."/>
```

### Added in `data/GorjanaMonthCloseChecksData.xml`

- Record: `darpan.audit.AuditCheck|checkId=GORJANA_CHK_LINE_LOCATION|companyUserGroupId=GORJANA|savedRunId=GORJANA_OMS_NS_LINE_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="GORJANA_CHK_LINE_LOCATION" otherSideDateField="tranDate" companyUserGroupId="GORJANA" checkCode="lines-without-location" label="Completed items whose NetSuite line has no location" savedRunId="GORJANA_OMS_NS_LINE_RS" file1RecordType="orderItem" file2RecordType="salesOrderLine" graceDays="0" matchedBy="OMS order id + item seq id (custbody_hc_order_id + custcol_hc_order_line_id)" fixOwner="HotWax Support" fixWhereEnumId="NETSUITE" headlineTemplate="Item {recordKey} has no location on its NetSuite line" fixActionTemplate="Set the store on the NetSuite line for item {recordKey}, then ship and bill it." passesWhenTemplate="The NetSuite line for item {recordKey} has its store and is billed."/>
```

### Added in `data/GorjanaMonthCloseChecksData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=GORJANA_R1|checkId=GORJANA_CHK_COMPLETED_BILLED|companyUserGroupId=GORJANA`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="GORJANA_R1" checkId="GORJANA_CHK_COMPLETED_BILLED" companyUserGroupId="GORJANA" sequenceNum="1" diffTypes="MISSING_IN_FILE_2" conclusionCodes="CONC_COMPLETED_CLOSED_IN_NS,CONC_NS_NO_LINE_LOCATION,CONC_NS_BACKORDERED,CONC_ORDER_BACKORDER_HOLDS_BILLING,CONC_SHIPPED_NOT_BILLED,CONC_NOT_SHIPPED_IN_NS,CONC_NEVER_REACHED_NS,UNEXPLAINED"/>
```

### Added in `data/GorjanaMonthCloseChecksData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=GORJANA_R2|checkId=GORJANA_CHK_CREDIT_ETAIL|companyUserGroupId=GORJANA`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="GORJANA_R2" checkId="GORJANA_CHK_CREDIT_ETAIL" companyUserGroupId="GORJANA" sequenceNum="2" diffTypes="FINDING" conclusionCodes="CONC_NO_ETAIL_ID"/>
```

### Added in `data/GorjanaMonthCloseChecksData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=GORJANA_R2|checkId=GORJANA_CHK_INVOICE_ETAIL|companyUserGroupId=GORJANA`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="GORJANA_R2" checkId="GORJANA_CHK_INVOICE_ETAIL" companyUserGroupId="GORJANA" sequenceNum="1" diffTypes="FINDING" conclusionCodes="CONC_NO_ETAIL_ID"/>
```

### Added in `data/GorjanaMonthCloseChecksData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=GORJANA_R3|checkId=GORJANA_CHK_LINE_LOCATION|companyUserGroupId=GORJANA`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="GORJANA_R3" checkId="GORJANA_CHK_LINE_LOCATION" companyUserGroupId="GORJANA" sequenceNum="1" diffTypes="MISSING_IN_FILE_2" conclusionCodes="CONC_NS_NO_LINE_LOCATION"/>
```

### Added in `data/GorjanaMonthCloseChecksData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=GORJANA_CREDIT_ETAIL_SCOPE|ruleSetId=GORJANA_CREDIT_ETAIL_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="GORJANA_CREDIT_ETAIL_SCOPE" ruleSetId="GORJANA_CREDIT_ETAIL_RS" objectType="CREDIT_MEMO" scopeMode="EVALUATE" description="Every NetSuite credit memo carries the Shopify order id (custbody_celigo_etail_order_id). Reads every credit memo so a fixed one is seen and closes." companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseChecksData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=GORJANA_INVOICE_ETAIL_SCOPE|ruleSetId=GORJANA_INVOICE_ETAIL_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="GORJANA_INVOICE_ETAIL_SCOPE" ruleSetId="GORJANA_INVOICE_ETAIL_RS" objectType="INVOICE" scopeMode="EVALUATE" description="Every NetSuite invoice carries the Shopify order id (custbody_celigo_etail_order_id). Reads every invoice so a fixed one is seen and closes." companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=GORJANA_CREDIT_ETAIL_SCOPE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="GORJANA_CREDIT_ETAIL_SCOPE" fileSide="FILE_1" sequenceNum="1" fieldExpression="tranId" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=GORJANA_INVOICE_ETAIL_SCOPE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="GORJANA_INVOICE_ETAIL_SCOPE" fileSide="FILE_1" sequenceNum="1" fieldExpression="tranId" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=GORJANA_CREDIT_ETAIL_SCOPE|systemEnumId=NETSUITE_SUITEQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=NS_CREDIT_ETAIL|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="GORJANA_CREDIT_ETAIL_SCOPE" fileSide="FILE_1" systemEnumId="NETSUITE_SUITEQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="tranId" sourceConfigId="NS_CREDIT_ETAIL" sourceConfigType="NETSUITE_SUITEQL" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=GORJANA_INVOICE_ETAIL_SCOPE|systemEnumId=NETSUITE_SUITEQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=NS_INVOICE_ETAIL|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="GORJANA_INVOICE_ETAIL_SCOPE" fileSide="FILE_1" systemEnumId="NETSUITE_SUITEQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="tranId" sourceConfigId="NS_INVOICE_ETAIL" sourceConfigType="NETSUITE_SUITEQL" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionCondition|compareScopeId=GORJANA_CREDIT_ETAIL_SCOPE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionCondition`

```xml
<darpan.rule.RuleSetConclusionCondition compareScopeId="GORJANA_CREDIT_ETAIL_SCOPE" sequenceNum="10" conditionSeq="1" subject="FILE_1" presence="ANY" keyScope="FULL" fieldExpression="etailOrderId" operator="BLANK" checkLabel="The credit memo has no Etail id" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionCondition|compareScopeId=GORJANA_INVOICE_ETAIL_SCOPE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionCondition`

```xml
<darpan.rule.RuleSetConclusionCondition compareScopeId="GORJANA_INVOICE_ETAIL_SCOPE" sequenceNum="10" conditionSeq="1" subject="FILE_1" presence="ANY" keyScope="FULL" fieldExpression="etailOrderId" operator="BLANK" checkLabel="The invoice has no Etail id" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_CREDIT_ETAIL_SCOPE|conclusionEnumId=CONC_NO_ETAIL_ID|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_CREDIT_ETAIL_SCOPE" sequenceNum="10" conclusionEnumId="CONC_NO_ETAIL_ID" appliesToBucket="FINDING" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_INVOICE_ETAIL_SCOPE|conclusionEnumId=CONC_NO_ETAIL_ID|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_INVOICE_ETAIL_SCOPE" sequenceNum="10" conclusionEnumId="CONC_NO_ETAIL_ID" appliesToBucket="FINDING" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseChecksData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=GORJANA_CREDIT_ETAIL_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="GORJANA_CREDIT_ETAIL_RS" ruleSetName="Credit memos - Etail id" description="Every NetSuite credit memo carries the Shopify order id (custbody_celigo_etail_order_id). Reads every credit memo so a fixed one is seen and closes." version="1.0" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseChecksData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=GORJANA_INVOICE_ETAIL_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="GORJANA_INVOICE_ETAIL_RS" ruleSetName="Invoices - Etail id" description="Every NetSuite invoice carries the Shopify order id (custbody_celigo_etail_order_id). Reads every invoice so a fixed one is seen and closes." version="1.0" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseChecksData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_NO_ETAIL_ID|enumTypeId=DarpanConclusion|enumCode=CONC_NO_ETAIL_ID`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_NO_ETAIL_ID" enumTypeId="DarpanConclusion" enumCode="CONC_NO_ETAIL_ID" description="No Shopify order id (Etail id)"/>
```

### Added in `data/GorjanaMonthCloseOpenOrdersChecksData.xml`

- Record: `darpan.audit.AuditCheck|checkId=GORJANA_CHK_OPEN_NOT_IN_OMS|companyUserGroupId=GORJANA|savedRunId=GORJANA_OPEN_OMS_STATUS_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="GORJANA_CHK_OPEN_NOT_IN_OMS" companyUserGroupId="GORJANA" checkCode="open-in-netsuite-not-in-hotwax" label="NetSuite orders never shipped or billed whose HC Order Id HotWax does not have" savedRunId="GORJANA_OPEN_OMS_STATUS_RS" file1RecordType="salesOrder" graceDays="4" matchedBy="HC Order Id (custbody_hc_order_id) = HotWax order id" fixOwner="HotWax Support" fixWhereEnumId="NETSUITE" headlineTemplate="Order {recordKey} names a HotWax order HotWax does not have" fixActionTemplate="Find the HotWax order for {recordKey}, or close the NetSuite order if it never came from HotWax." passesWhenTemplate="HotWax has the order NetSuite order {recordKey} names, or NetSuite has moved it on."/>
```

### Added in `data/GorjanaMonthCloseOpenOrdersChecksData.xml`

- Record: `darpan.audit.AuditCheck|checkId=GORJANA_CHK_OPEN_OMS_DONE|companyUserGroupId=GORJANA|savedRunId=GORJANA_OPEN_OMS_STATUS_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="GORJANA_CHK_OPEN_OMS_DONE" companyUserGroupId="GORJANA" checkCode="open-in-netsuite-done-in-hotwax" label="NetSuite orders never shipped or billed that HotWax completed or cancelled" savedRunId="GORJANA_OPEN_OMS_STATUS_RS" file1RecordType="salesOrder" graceDays="4" matchedBy="HC Order Id (custbody_hc_order_id) = HotWax order id" fixOwner="HotWax Support" fixWhereEnumId="NETSUITE" headlineTemplate="Order {recordKey} is done in HotWax and still open in NetSuite" fixActionTemplate="Ship and bill order {recordKey} in NetSuite as HotWax completed it, or close it if HotWax cancelled it." passesWhenTemplate="NetSuite has shipped, billed or closed order {recordKey}."/>
```

### Added in `data/GorjanaMonthCloseOpenOrdersChecksData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=GORJANA_R18|checkId=GORJANA_CHK_OPEN_NOT_IN_OMS|companyUserGroupId=GORJANA`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="GORJANA_R18" checkId="GORJANA_CHK_OPEN_NOT_IN_OMS" companyUserGroupId="GORJANA" sequenceNum="2" diffTypes="FINDING" conclusionCodes="CONC_NS_ORDER_NOT_IN_OMS"/>
```

### Added in `data/GorjanaMonthCloseOpenOrdersChecksData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=GORJANA_R18|checkId=GORJANA_CHK_OPEN_OMS_DONE|companyUserGroupId=GORJANA`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="GORJANA_R18" checkId="GORJANA_CHK_OPEN_OMS_DONE" companyUserGroupId="GORJANA" sequenceNum="1" diffTypes="FINDING" conclusionCodes="CONC_OMS_COMPLETED_NS_OPEN,CONC_OMS_CANCELLED_NS_OPEN"/>
```

### Added in `data/GorjanaMonthCloseOpenOrdersChecksData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=GORJANA_OPEN_OMS_STATUS_SCOPE|ruleSetId=GORJANA_OPEN_OMS_STATUS_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="GORJANA_OPEN_OMS_STATUS_SCOPE" ruleSetId="GORJANA_OPEN_OMS_STATUS_RS" objectType="ORDER" scopeMode="EVALUATE" description="Sales orders NetSuite never shipped or billed, against HotWax: completed, cancelled while still open in NetSuite, or not in HotWax. Reads every HotWax order so a fixed one closes." companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseOpenOrdersChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSourceFilter|compareScopeId=GORJANA_OPEN_OMS_STATUS_SCOPE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSourceFilter`

```xml
<darpan.rule.RuleSetCompareSourceFilter compareScopeId="GORJANA_OPEN_OMS_STATUS_SCOPE" fileSide="FILE_1" sequenceNum="1" fieldExpression="orderOrigin" operator="INCLUDE_IN" filterValues="HOTWAX" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseOpenOrdersChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=GORJANA_OPEN_OMS_STATUS_SCOPE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="GORJANA_OPEN_OMS_STATUS_SCOPE" fileSide="FILE_1" sequenceNum="1" fieldExpression="tranId" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseOpenOrdersChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=GORJANA_OPEN_OMS_STATUS_SCOPE|systemEnumId=NETSUITE_SUITEQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=NS_SO_OPEN_OMS_STATUS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="GORJANA_OPEN_OMS_STATUS_SCOPE" fileSide="FILE_1" systemEnumId="NETSUITE_SUITEQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="tranId" sourceConfigId="NS_SO_OPEN_OMS_STATUS" sourceConfigType="NETSUITE_SUITEQL" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseOpenOrdersChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionCondition|compareScopeId=GORJANA_OPEN_OMS_STATUS_SCOPE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionCondition`

```xml
<darpan.rule.RuleSetConclusionCondition compareScopeId="GORJANA_OPEN_OMS_STATUS_SCOPE" sequenceNum="30" conditionSeq="3" subject="FILE_1" presence="ANY" keyScope="FULL" fieldExpression="omsStatusId" operator="IN" conditionValues="NOT_IN_OMS" checkLabel="HotWax has no order with its HC Order Id" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseOpenOrdersChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_OPEN_OMS_STATUS_SCOPE|conclusionEnumId=CONC_NS_ORDER_NOT_IN_OMS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_OPEN_OMS_STATUS_SCOPE" sequenceNum="30" conclusionEnumId="CONC_NS_ORDER_NOT_IN_OMS" appliesToBucket="FINDING" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseOpenOrdersChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_OPEN_OMS_STATUS_SCOPE|conclusionEnumId=CONC_OMS_CANCELLED_NS_OPEN|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_OPEN_OMS_STATUS_SCOPE" sequenceNum="20" conclusionEnumId="CONC_OMS_CANCELLED_NS_OPEN" appliesToBucket="FINDING" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseOpenOrdersChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_OPEN_OMS_STATUS_SCOPE|conclusionEnumId=CONC_OMS_COMPLETED_NS_OPEN|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_OPEN_OMS_STATUS_SCOPE" sequenceNum="10" conclusionEnumId="CONC_OMS_COMPLETED_NS_OPEN" appliesToBucket="FINDING" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseOpenOrdersChecksData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=GORJANA_OPEN_OMS_STATUS_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="GORJANA_OPEN_OMS_STATUS_RS" ruleSetName="Open in NetSuite - HotWax status" description="Sales orders NetSuite never shipped or billed, against HotWax: completed, cancelled while still open in NetSuite, or not in HotWax. Reads every HotWax order so a fixed one closes." version="1.0" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseOpenOrdersChecksData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_NS_ORDER_NOT_IN_OMS|enumTypeId=DarpanConclusion|enumCode=CONC_NS_ORDER_NOT_IN_OMS`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_NS_ORDER_NOT_IN_OMS" enumTypeId="DarpanConclusion" enumCode="CONC_NS_ORDER_NOT_IN_OMS" description="HotWax has no order with this HC Order Id"/>
```

### Added in `data/GorjanaMonthCloseOpenOrdersChecksData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_OMS_CANCELLED_NS_OPEN|enumTypeId=DarpanConclusion|enumCode=CONC_OMS_CANCELLED_NS_OPEN`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_OMS_CANCELLED_NS_OPEN" enumTypeId="DarpanConclusion" enumCode="CONC_OMS_CANCELLED_NS_OPEN" description="Cancelled in HotWax, still open in NetSuite"/>
```

### Added in `data/GorjanaMonthCloseOpenOrdersChecksData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_OMS_COMPLETED_NS_OPEN|enumTypeId=DarpanConclusion|enumCode=CONC_OMS_COMPLETED_NS_OPEN`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_OMS_COMPLETED_NS_OPEN" enumTypeId="DarpanConclusion" enumCode="CONC_OMS_COMPLETED_NS_OPEN" description="Completed in HotWax, never shipped or billed in NetSuite"/>
```

### Added in `data/GorjanaMonthClosePaymentsChecksData.xml`

- Record: `darpan.audit.AuditCheck|checkId=GORJANA_CHK_COMPLETED_NOT_FULFILLED|companyUserGroupId=GORJANA|savedRunId=GORJANA_PAID_HAS_OPP_RS|fixWhereEnumId=SHOPIFY`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="GORJANA_CHK_COMPLETED_NOT_FULFILLED" otherSideDateField="createdAt" companyUserGroupId="GORJANA" checkCode="completed-orders-not-fulfilled-in-shopify" label="HotWax orders completed that Shopify has not fulfilled" savedRunId="GORJANA_PAID_HAS_OPP_RS" file1RecordType="order" file2RecordType="order" graceDays="1" matchedBy="Shopify order id = HotWax externalId" fixOwner="HotWax Support" fixWhereEnumId="SHOPIFY" headlineTemplate="Shopify order {recordKey} is completed in HotWax and not fulfilled in Shopify" fixActionTemplate="Sync the HotWax fulfillment for order {recordKey} to Shopify, so Shopify shows it fulfilled." passesWhenTemplate="Shopify shows order {recordKey} fulfilled."/>
```

### Added in `data/GorjanaMonthClosePaymentsChecksData.xml`

- Record: `darpan.audit.AuditCheck|checkId=GORJANA_CHK_PAID_NO_OPP|companyUserGroupId=GORJANA|savedRunId=GORJANA_PAID_HAS_OPP_RS|fixWhereEnumId=OMS`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="GORJANA_CHK_PAID_NO_OPP" otherSideDateField="createdAt" companyUserGroupId="GORJANA" checkCode="paid-orders-without-a-payment-in-hotwax" label="Shopify orders paid whose HotWax order has no payment" savedRunId="GORJANA_PAID_HAS_OPP_RS" file1RecordType="order" file2RecordType="order" graceDays="1" matchedBy="Shopify order id = HotWax externalId" fixOwner="HotWax Support" fixWhereEnumId="OMS" headlineTemplate="Shopify order {recordKey} is paid and its HotWax order has no payment" fixActionTemplate="Sync the Shopify payment for order {recordKey} to HotWax, so the HotWax order carries its payment preference." passesWhenTemplate="The HotWax order for Shopify order {recordKey} carries a payment."/>
```

### Added in `data/GorjanaMonthClosePaymentsChecksData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=GORJANA_R19|checkId=GORJANA_CHK_PAID_NO_OPP|companyUserGroupId=GORJANA`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="GORJANA_R19" checkId="GORJANA_CHK_PAID_NO_OPP" companyUserGroupId="GORJANA" sequenceNum="1" diffTypes="FIELD_MISMATCH" ruleIds="GORJANA_PAID_HAS_OPP"/>
```

### Added in `data/GorjanaMonthClosePaymentsChecksData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=GORJANA_R20|checkId=GORJANA_CHK_COMPLETED_NOT_FULFILLED|companyUserGroupId=GORJANA`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="GORJANA_R20" checkId="GORJANA_CHK_COMPLETED_NOT_FULFILLED" companyUserGroupId="GORJANA" sequenceNum="1" diffTypes="FIELD_MISMATCH" ruleIds="GORJANA_COMPLETED_FULFILLED"/>
```

### Added in `data/GorjanaMonthClosePaymentsChecksData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=GORJANA_PAID_HAS_OPP_SCOPE|ruleSetId=GORJANA_PAID_HAS_OPP_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="GORJANA_PAID_HAS_OPP_SCOPE" ruleSetId="GORJANA_PAID_HAS_OPP_RS" objectType="ORDER" scopeMode="COMPARE" description="Every Shopify order against its HotWax order: a paid Shopify order whose HotWax order has no payment preference, or an order HotWax completed that Shopify has not fulfilled, is a finding." companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthClosePaymentsChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=GORJANA_PAID_HAS_OPP_SCOPE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="GORJANA_PAID_HAS_OPP_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="externalId" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthClosePaymentsChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=GORJANA_PAID_HAS_OPP_SCOPE|systemEnumId=OMS_GQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=gorjana_prod|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="GORJANA_PAID_HAS_OPP_SCOPE" fileSide="FILE_2" systemEnumId="OMS_GQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="externalId" sourceConfigId="gorjana_prod" sourceConfigType="HOTWAX_OMS_GQL" duplicateKeyHandling="FINDING" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthClosePaymentsChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=GORJANA_PAID_HAS_OPP_SCOPE|systemEnumId=SHOPIFY|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=gorjana_prod|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="GORJANA_PAID_HAS_OPP_SCOPE" fileSide="FILE_1" systemEnumId="SHOPIFY" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="legacyResourceId" sourceConfigId="gorjana_prod" sourceConfigType="SHOPIFY_AUTH" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthClosePaymentsChecksData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=GORJANA_PAID_HAS_OPP_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="GORJANA_PAID_HAS_OPP_RS" ruleSetName="Shopify orders against HotWax orders" description="Every Shopify order against its HotWax order: a paid Shopify order whose HotWax order has no payment preference, or an order HotWax completed that Shopify has not fulfilled, is a finding." version="1.0" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthClosePaymentsChecksData.xml`

- Record: `darpan.rule.Rule|ruleId=GORJANA_COMPLETED_FULFILLED|ruleSetId=GORJANA_PAID_HAS_OPP_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.Rule`

```xml
<darpan.rule.Rule ruleId="GORJANA_COMPLETED_FULFILLED" ruleSetId="GORJANA_PAID_HAS_OPP_RS" sequenceNum="20" ruleText="isFulfilled required by isCompleted" enabled="Y" ruleType="FIELD_COMPARISON" severity="WARN" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthClosePaymentsChecksData.xml`

- Record: `darpan.rule.Rule|ruleId=GORJANA_PAID_HAS_OPP|ruleSetId=GORJANA_PAID_HAS_OPP_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.Rule`

```xml
<darpan.rule.Rule ruleId="GORJANA_PAID_HAS_OPP" ruleSetId="GORJANA_PAID_HAS_OPP_RS" sequenceNum="10" ruleText="hasPaymentTransaction requires hasPaymentPreference" enabled="Y" ruleType="FIELD_COMPARISON" severity="WARN" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReceiptChecksData.xml`

- Record: `darpan.audit.AuditCheck|checkId=GORJANA_CHK_EXCHANGE_LOCATION|companyUserGroupId=GORJANA|savedRunId=GORJANA_EXCHANGE_LOCATION_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="GORJANA_CHK_EXCHANGE_LOCATION" companyUserGroupId="GORJANA" checkCode="exchange-pieces-booked-elsewhere" label="Exchange pieces booked at another NetSuite location than the facility that handed them over" savedRunId="GORJANA_EXCHANGE_LOCATION_RS" file1RecordType="orderItem" graceDays="1" matchedBy="OMS order id and item seq id (custbody_hc_order_id, custcol_hc_order_line_id)" fixOwner="HotWax Support" fixWhereEnumId="NETSUITE" headlineTemplate="Exchange item {recordKey}: handed over at facility {file1Value}, booked at NetSuite location {file2Value}" fixActionTemplate="Set the location of exchange item {recordKey}'s NetSuite line to {file1Value}, the facility that handed it over." passesWhenTemplate="The NetSuite line of exchange item {recordKey} is at the facility that handed it over."/>
```

### Added in `data/GorjanaMonthCloseReceiptChecksData.xml`

- Record: `darpan.audit.AuditCheck|checkId=GORJANA_CHK_WARRANTY_RECEIPT|companyUserGroupId=GORJANA|savedRunId=GORJANA_WARRANTY_RECEIPT_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="GORJANA_CHK_WARRANTY_RECEIPT" otherSideDateField="tranDate" companyUserGroupId="GORJANA" checkCode="warranty-arrivals-without-receipt" label="Warranty pieces the OMS received with no NetSuite item receipt" savedRunId="GORJANA_WARRANTY_RECEIPT_RS" file1RecordType="return" graceDays="2" matchedBy="return id (custbody_hc_return_id on the RMA; the receipt by link)" fixOwner="HotWax Support" fixWhereEnumId="NETSUITE" headlineTemplate="Warranty return {return} arrived in the OMS and has no NetSuite item receipt" fixActionTemplate="Receive return {return}'s piece in NetSuite (an item receipt on its RMA, at the damage location with QC), even if the claim is closed." passesWhenTemplate="Return {return}'s RMA carries an item receipt."/>
```

### Added in `data/GorjanaMonthCloseReceiptChecksData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=GORJANA_R11|checkId=GORJANA_CHK_WARRANTY_RECEIPT|companyUserGroupId=GORJANA`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="GORJANA_R11" checkId="GORJANA_CHK_WARRANTY_RECEIPT" companyUserGroupId="GORJANA" sequenceNum="1" diffTypes="MISSING_IN_FILE_2" conclusionCodes="CONC_ARRIVED_NOT_BOOKED"/>
```

### Added in `data/GorjanaMonthCloseReceiptChecksData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=GORJANA_R15|checkId=GORJANA_CHK_EXCHANGE_LOCATION|companyUserGroupId=GORJANA`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="GORJANA_R15" checkId="GORJANA_CHK_EXCHANGE_LOCATION" companyUserGroupId="GORJANA" sequenceNum="1" diffTypes="FIELD_MISMATCH" ruleIds="GORJANA_R15_LOCATION"/>
```

### Added in `data/GorjanaMonthCloseReceiptChecksData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=GORJANA_EXCHANGE_LOCATION_SCOPE|ruleSetId=GORJANA_EXCHANGE_LOCATION_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="GORJANA_EXCHANGE_LOCATION_SCOPE" ruleSetId="GORJANA_EXCHANGE_LOCATION_RS" objectType="ORDER_LINE_UNIT" scopeMode="COMPARE" description="Every exchange piece HotWax completed is booked in NetSuite at the facility that handed it over. Keyed (OMS order id, OMS order item seq id)." companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReceiptChecksData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=GORJANA_WARRANTY_RECEIPT_SCOPE|ruleSetId=GORJANA_WARRANTY_RECEIPT_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="GORJANA_WARRANTY_RECEIPT_SCOPE" ruleSetId="GORJANA_WARRANTY_RECEIPT_RS" objectType="RETURN" scopeMode="COMPARE" description="Every warranty piece the OMS received has an item receipt on its NetSuite RMA." companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReceiptChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSourceFilter|compareScopeId=GORJANA_EXCHANGE_LOCATION_SCOPE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSourceFilter`

```xml
<darpan.rule.RuleSetCompareSourceFilter compareScopeId="GORJANA_EXCHANGE_LOCATION_SCOPE" fileSide="FILE_2" sequenceNum="2" fieldExpression="itemType" operator="EXCLUDE_IN" filterValues="Discount" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReceiptChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=GORJANA_EXCHANGE_LOCATION_SCOPE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="GORJANA_EXCHANGE_LOCATION_SCOPE" fileSide="FILE_2" sequenceNum="2" fieldExpression="orderLineId" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReceiptChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=GORJANA_WARRANTY_RECEIPT_SCOPE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="GORJANA_WARRANTY_RECEIPT_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="returnId" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReceiptChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=GORJANA_EXCHANGE_LOCATION_SCOPE|systemEnumId=NETSUITE_SUITEQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=NS_SO_SHIPPED_LINES|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="GORJANA_EXCHANGE_LOCATION_SCOPE" fileSide="FILE_2" systemEnumId="NETSUITE_SUITEQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="orderId" sourceConfigId="NS_SO_SHIPPED_LINES" sourceConfigType="NETSUITE_SUITEQL" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReceiptChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=GORJANA_EXCHANGE_LOCATION_SCOPE|systemEnumId=OMS_ORDER_ITEMS_GQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=gorjana_prod|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="GORJANA_EXCHANGE_LOCATION_SCOPE" fileSide="FILE_1" systemEnumId="OMS_ORDER_ITEMS_GQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="omsOrderId" sourceConfigId="gorjana_prod" sourceConfigType="HOTWAX_OMS_GQL_ORDER_ITEMS" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReceiptChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=GORJANA_WARRANTY_RECEIPT_SCOPE|systemEnumId=NETSUITE_SUITEQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=NS_RMA_RECEIVED|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="GORJANA_WARRANTY_RECEIPT_SCOPE" fileSide="FILE_2" systemEnumId="NETSUITE_SUITEQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="returnId" sourceConfigId="NS_RMA_RECEIVED" sourceConfigType="NETSUITE_SUITEQL" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReceiptChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=GORJANA_WARRANTY_RECEIPT_SCOPE|systemEnumId=OMS_RETURNS_GQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=gorjana_prod|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="GORJANA_WARRANTY_RECEIPT_SCOPE" fileSide="FILE_1" systemEnumId="OMS_RETURNS_GQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="returnId" sourceConfigId="gorjana_prod" sourceConfigType="HOTWAX_OMS_GQL_RETURNS" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReceiptChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionCondition|compareScopeId=GORJANA_WARRANTY_RECEIPT_SCOPE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionCondition`

```xml
<darpan.rule.RuleSetConclusionCondition compareScopeId="GORJANA_WARRANTY_RECEIPT_SCOPE" sequenceNum="10" conditionSeq="2" subject="FILE_1" presence="ANY" keyScope="FULL" fieldExpression="items.receivedQuantity" operator="GT_ZERO" quantifier="ANY" checkLabel="The OMS received the piece" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReceiptChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_WARRANTY_RECEIPT_SCOPE|conclusionEnumId=CONC_ARRIVED_NOT_BOOKED|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_WARRANTY_RECEIPT_SCOPE" sequenceNum="10" conclusionEnumId="CONC_ARRIVED_NOT_BOOKED" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReceiptChecksData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=GORJANA_EXCHANGE_LOCATION_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="GORJANA_EXCHANGE_LOCATION_RS" ruleSetName="Exchange orders - HotWax facility vs NetSuite line location" description="Every exchange piece HotWax completed is booked in NetSuite at the facility that handed it over. Keyed (OMS order id, OMS order item seq id)." version="1.0" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReceiptChecksData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=GORJANA_WARRANTY_RECEIPT_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="GORJANA_WARRANTY_RECEIPT_RS" ruleSetName="Warranty arrivals - NetSuite item receipts" description="Every warranty piece the OMS received has an item receipt on its NetSuite RMA." version="1.0" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReceiptChecksData.xml`

- Record: `darpan.rule.Rule|ruleId=GORJANA_R15_LOCATION|ruleSetId=GORJANA_EXCHANGE_LOCATION_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.Rule`

```xml
<darpan.rule.Rule ruleId="GORJANA_R15_LOCATION" ruleSetId="GORJANA_EXCHANGE_LOCATION_RS" sequenceNum="10" ruleText="facilityId = lineLocation" enabled="Y" ruleType="FIELD_COMPARISON" severity="WARN" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReceiptChecksData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_ARRIVED_NOT_BOOKED|enumTypeId=DarpanConclusion|enumCode=CONC_ARRIVED_NOT_BOOKED`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_ARRIVED_NOT_BOOKED" enumTypeId="DarpanConclusion" enumCode="CONC_ARRIVED_NOT_BOOKED" description="Arrived in the OMS, no item receipt in NetSuite"/>
```

### Added in `data/GorjanaMonthCloseReplacementChecksData.xml`

- Record: `darpan.audit.AuditCheck|checkId=GORJANA_CHK_REPLACEMENT_CUSTOMER|companyUserGroupId=GORJANA|savedRunId=GORJANA_MEMO_REPLACEMENT_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="GORJANA_CHK_REPLACEMENT_CUSTOMER" companyUserGroupId="GORJANA" checkCode="exchange-credits-on-another-customer" label="Exchange credit memos whose replacement order is under another customer" savedRunId="GORJANA_MEMO_REPLACEMENT_RS" file1RecordType="creditMemo" file2RecordType="replacementOrder" graceDays="1" matchedBy="credit memo id (custbody_hc_exchange_credit_memo_id on the replacement order)" fixOwner="HotWax Support" fixWhereEnumId="NETSUITE" headlineTemplate="Credit memo id {creditMemo} and its replacement order are under different customers" fixActionTemplate="Move the credit memo with internal id {creditMemo} to its replacement order's customer (the store's own customer when the shopper gave none)." passesWhenTemplate="The credit memo with internal id {creditMemo} and its replacement order have the same customer."/>
```

### Added in `data/GorjanaMonthCloseReplacementChecksData.xml`

- Record: `darpan.audit.AuditCheck|checkId=GORJANA_CHK_REPLACEMENT_NOT_ORIGINAL|companyUserGroupId=GORJANA|savedRunId=GORJANA_MEMO_REPLACEMENT_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="GORJANA_CHK_REPLACEMENT_NOT_ORIGINAL" companyUserGroupId="GORJANA" checkCode="returns-replaced-by-their-own-order" label="Exchange credit memos whose replacement is an existing order (its own original, or a year older)" savedRunId="GORJANA_MEMO_REPLACEMENT_RS" file1RecordType="creditMemo" file2RecordType="replacementOrder" graceDays="1" matchedBy="credit memo id (custbody_hc_exchange_credit_memo_id on the replacement order)" fixOwner="HotWax Support" fixWhereEnumId="NETSUITE" headlineTemplate="Credit memo id {creditMemo} names an existing order as its replacement" fixActionTemplate="Unlink the credit memo with internal id {creditMemo} from the original order and apply it to the return's own replacement order." passesWhenTemplate="The replacement of the credit memo with internal id {creditMemo} is a new order, not the one the return came from."/>
```

### Added in `data/GorjanaMonthCloseReplacementChecksData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=GORJANA_R13|checkId=GORJANA_CHK_REPLACEMENT_NOT_ORIGINAL|companyUserGroupId=GORJANA`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="GORJANA_R13" checkId="GORJANA_CHK_REPLACEMENT_NOT_ORIGINAL" companyUserGroupId="GORJANA" sequenceNum="1" diffTypes="FIELD_MISMATCH" ruleIds="GORJANA_MR_NOT_OWN_ORIGINAL,GORJANA_MR_NOT_YEAR_OLDER"/>
```

### Added in `data/GorjanaMonthCloseReplacementChecksData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=GORJANA_R14|checkId=GORJANA_CHK_REPLACEMENT_CUSTOMER|companyUserGroupId=GORJANA`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="GORJANA_R14" checkId="GORJANA_CHK_REPLACEMENT_CUSTOMER" companyUserGroupId="GORJANA" sequenceNum="1" diffTypes="FIELD_MISMATCH" ruleIds="GORJANA_MR_SAME_CUSTOMER"/>
```

### Added in `data/GorjanaMonthCloseReplacementChecksData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=GORJANA_MEMO_REPLACEMENT_SCOPE|ruleSetId=GORJANA_MEMO_REPLACEMENT_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="GORJANA_MEMO_REPLACEMENT_SCOPE" ruleSetId="GORJANA_MEMO_REPLACEMENT_RS" objectType="CREDIT_MEMO" scopeMode="COMPARE" description="Every exchange or warranty credit memo against the replacement order that names it: one customer, and not the return's own original order." companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReplacementChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=GORJANA_MEMO_REPLACEMENT_SCOPE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="GORJANA_MEMO_REPLACEMENT_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="memoId" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReplacementChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=GORJANA_MEMO_REPLACEMENT_SCOPE|systemEnumId=NETSUITE_SUITEQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=NS_EXCHANGE_MEMO|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="GORJANA_MEMO_REPLACEMENT_SCOPE" fileSide="FILE_1" systemEnumId="NETSUITE_SUITEQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="memoId" sourceConfigId="NS_EXCHANGE_MEMO" sourceConfigType="NETSUITE_SUITEQL" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReplacementChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=GORJANA_MEMO_REPLACEMENT_SCOPE|systemEnumId=NETSUITE_SUITEQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=NS_REPLACEMENT_ORDER|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="GORJANA_MEMO_REPLACEMENT_SCOPE" fileSide="FILE_2" systemEnumId="NETSUITE_SUITEQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="memoId" sourceConfigId="NS_REPLACEMENT_ORDER" sourceConfigType="NETSUITE_SUITEQL" duplicateKeyHandling="FINDING" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReplacementChecksData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=GORJANA_MEMO_REPLACEMENT_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="GORJANA_MEMO_REPLACEMENT_RS" ruleSetName="Exchange credit memos - replacement orders" description="Every exchange or warranty credit memo against the replacement order that names it: one customer, and not the return's own original order." version="1.0" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReplacementChecksData.xml`

- Record: `darpan.rule.Rule|ruleId=GORJANA_MR_NOT_OWN_ORIGINAL|ruleSetId=GORJANA_MEMO_REPLACEMENT_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.Rule`

```xml
<darpan.rule.Rule ruleId="GORJANA_MR_NOT_OWN_ORIGINAL" ruleSetId="GORJANA_MEMO_REPLACEMENT_RS" sequenceNum="20" ruleText="orderId != orderId" enabled="Y" ruleType="FIELD_COMPARISON" severity="WARN" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReplacementChecksData.xml`

- Record: `darpan.rule.Rule|ruleId=GORJANA_MR_NOT_YEAR_OLDER|ruleSetId=GORJANA_MEMO_REPLACEMENT_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.Rule`

```xml
<darpan.rule.Rule ruleId="GORJANA_MR_NOT_YEAR_OLDER" ruleSetId="GORJANA_MEMO_REPLACEMENT_RS" sequenceNum="30" ruleText="yearBeforeDay &lt;= dayNumber" enabled="Y" ruleType="FIELD_COMPARISON" severity="WARN" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReplacementChecksData.xml`

- Record: `darpan.rule.Rule|ruleId=GORJANA_MR_SAME_CUSTOMER|ruleSetId=GORJANA_MEMO_REPLACEMENT_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.Rule`

```xml
<darpan.rule.Rule ruleId="GORJANA_MR_SAME_CUSTOMER" ruleSetId="GORJANA_MEMO_REPLACEMENT_RS" sequenceNum="10" ruleText="customerId = customerId" enabled="Y" ruleType="FIELD_COMPARISON" severity="WARN" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.audit.AuditCheck|checkId=GORJANA_CHK_CANCELLED_RETURN_OPEN|companyUserGroupId=GORJANA|savedRunId=GORJANA_CANCELLED_RETURN_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="GORJANA_CHK_CANCELLED_RETURN_OPEN" companyUserGroupId="GORJANA" checkCode="cancelled-returns-left-open" label="Returns the OMS cancelled whose NetSuite RMA is still open" savedRunId="GORJANA_CANCELLED_RETURN_RS" file1RecordType="return" graceDays="1" matchedBy="return id (custbody_hc_return_id on the RMA, without its HG- prefix)" fixOwner="HotWax Support" fixWhereEnumId="NETSUITE" headlineTemplate="Return {recordKey} is cancelled in the OMS; its RMA is {file2Value}" fixActionTemplate="Close the RMA of return {recordKey} in NetSuite, with anything open behind it: the OMS cancelled the return." passesWhenTemplate="The RMA of return {recordKey} is no longer open."/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.audit.AuditCheck|checkId=GORJANA_CHK_CREDIT_LEFT_OPEN|companyUserGroupId=GORJANA|savedRunId=GORJANA_CREDIT_OPEN_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="GORJANA_CHK_CREDIT_LEFT_OPEN" companyUserGroupId="GORJANA" checkCode="credit-memos-left-open" label="NetSuite credit memos left open" savedRunId="GORJANA_CREDIT_OPEN_RS" file1RecordType="creditMemo" graceDays="2" fixOwner="Gorjana Accounting" fixWhereEnumId="NETSUITE" headlineTemplate="Credit memo {recordKey} is still open" fixActionTemplate="Close credit memo {recordKey}: apply it to the refund or exchange it was made for. Darpan does not change NetSuite." passesWhenTemplate="Credit memo {recordKey} is no longer open."/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.audit.AuditCheck|checkId=GORJANA_CHK_HG_ACCOUNT|companyUserGroupId=GORJANA|savedRunId=GORJANA_HG_ACCOUNT_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="GORJANA_CHK_HG_ACCOUNT" companyUserGroupId="GORJANA" checkCode="hg-credits-not-on-42065" label="Happiness Guarantee credit memos that do not post to 42065" savedRunId="GORJANA_HG_ACCOUNT_RS" file1RecordType="creditMemo" graceDays="1" fixOwner="HotWax Support" fixWhereEnumId="NETSUITE" headlineTemplate="Credit memo {recordKey} is a Happiness Guarantee credit not on 42065" fixActionTemplate="Book credit memo {recordKey} to 42065 Happiness Guarantee, and check the sync's account mapping for its return channel." passesWhenTemplate="Credit memo {recordKey} posts to 42065 Happiness Guarantee."/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.audit.AuditCheck|checkId=GORJANA_CHK_RETURN_CREDIT_CUSTOMER|companyUserGroupId=GORJANA|savedRunId=GORJANA_RETURN_CREDIT_CUSTOMER_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="GORJANA_CHK_RETURN_CREDIT_CUSTOMER" companyUserGroupId="GORJANA" checkCode="return-credits-without-customer-name-or-address" label="NetSuite return credit memos with no customer name, return address or billing address" savedRunId="GORJANA_RETURN_CREDIT_CUSTOMER_RS" file1RecordType="creditMemo" graceDays="1" fixOwner="HotWax Support" fixWhereEnumId="NETSUITE" headlineTemplate="Credit memo {recordKey}: {conclusion}" fixActionTemplate="Set the customer's name, the shipping address and the billing address on credit memo {recordKey}, as the Shopify return shows them." passesWhenTemplate="Credit memo {recordKey} carries the customer's name, a shipping address and a billing address."/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.audit.AuditCheck|checkId=GORJANA_CHK_RETURN_IN_NETSUITE|companyUserGroupId=GORJANA|savedRunId=GORJANA_RETURN_IN_NETSUITE_RS|fixWhereEnumId=OMS`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="GORJANA_CHK_RETURN_IN_NETSUITE" companyUserGroupId="GORJANA" checkCode="returns-not-in-netsuite" label="Completed or received OMS returns with no NetSuite RMA" savedRunId="GORJANA_RETURN_IN_NETSUITE_RS" file1RecordType="return" graceDays="1" matchedBy="return id (custbody_hc_return_id on the RMA, without its HG- prefix)" fixOwner="HotWax Support" fixWhereEnumId="OMS" headlineTemplate="Return {recordKey}: {conclusion}" fixActionTemplate="Re-run the return sync for return {recordKey} and check its RMA exists in NetSuite. A completed return with no order is skipped by the sync until the code is fixed." passesWhenTemplate="NetSuite has an RMA for return {recordKey}."/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.audit.AuditCheck|checkId=GORJANA_CHK_RMA_CUSTOMER|companyUserGroupId=GORJANA|savedRunId=GORJANA_RMA_CUSTOMER_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="GORJANA_CHK_RMA_CUSTOMER" companyUserGroupId="GORJANA" checkCode="rmas-without-customer-name-or-address" label="NetSuite RMAs with no customer name, return address or billing address" savedRunId="GORJANA_RMA_CUSTOMER_RS" file1RecordType="rma" graceDays="1" fixOwner="HotWax Support" fixWhereEnumId="NETSUITE" headlineTemplate="RMA {recordKey}: {conclusion}" fixActionTemplate="Set the customer's name, the shipping address and the billing address on RMA {recordKey}, as the Shopify return shows them." passesWhenTemplate="RMA {recordKey} carries the customer's name, a shipping address and a billing address."/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=GORJANA_R16|checkId=GORJANA_CHK_CANCELLED_RETURN_OPEN|companyUserGroupId=GORJANA`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="GORJANA_R16" checkId="GORJANA_CHK_CANCELLED_RETURN_OPEN" companyUserGroupId="GORJANA" sequenceNum="1" diffTypes="FIELD_MISMATCH" ruleIds="GORJANA_R16_STATUS"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=GORJANA_R4|checkId=GORJANA_CHK_CREDIT_LEFT_OPEN|companyUserGroupId=GORJANA`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="GORJANA_R4" checkId="GORJANA_CHK_CREDIT_LEFT_OPEN" companyUserGroupId="GORJANA" sequenceNum="1" diffTypes="FINDING" conclusionCodes="CONC_MEMO_LEFT_OPEN"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=GORJANA_R7|checkId=GORJANA_CHK_HG_ACCOUNT|companyUserGroupId=GORJANA`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="GORJANA_R7" checkId="GORJANA_CHK_HG_ACCOUNT" companyUserGroupId="GORJANA" sequenceNum="1" diffTypes="FINDING" conclusionCodes="CONC_HG_NOT_ON_42065"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=GORJANA_R8|checkId=GORJANA_CHK_RETURN_IN_NETSUITE|companyUserGroupId=GORJANA`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="GORJANA_R8" checkId="GORJANA_CHK_RETURN_IN_NETSUITE" companyUserGroupId="GORJANA" sequenceNum="1" diffTypes="MISSING_IN_FILE_2" conclusionCodes="CONC_RETURN_NO_ORDER_NOT_SYNCED,CONC_RETURN_NOT_IN_NETSUITE"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=GORJANA_R9|checkId=GORJANA_CHK_RETURN_CREDIT_CUSTOMER|companyUserGroupId=GORJANA`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="GORJANA_R9" checkId="GORJANA_CHK_RETURN_CREDIT_CUSTOMER" companyUserGroupId="GORJANA" sequenceNum="2" diffTypes="FINDING" conclusionCodes="CONC_NO_CUSTOMER_NAME,CONC_NO_RETURN_ADDRESS,CONC_NO_BILLING_ADDRESS"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=GORJANA_R9|checkId=GORJANA_CHK_RMA_CUSTOMER|companyUserGroupId=GORJANA`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="GORJANA_R9" checkId="GORJANA_CHK_RMA_CUSTOMER" companyUserGroupId="GORJANA" sequenceNum="1" diffTypes="FINDING" conclusionCodes="CONC_NO_CUSTOMER_NAME,CONC_NO_RETURN_ADDRESS,CONC_NO_BILLING_ADDRESS"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=GORJANA_CANCELLED_RETURN_SCOPE|ruleSetId=GORJANA_CANCELLED_RETURN_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="GORJANA_CANCELLED_RETURN_SCOPE" ruleSetId="GORJANA_CANCELLED_RETURN_RS" objectType="RETURN" scopeMode="COMPARE" description="A return the OMS cancelled has no RMA left open in NetSuite." companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=GORJANA_CREDIT_OPEN_SCOPE|ruleSetId=GORJANA_CREDIT_OPEN_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="GORJANA_CREDIT_OPEN_SCOPE" ruleSetId="GORJANA_CREDIT_OPEN_RS" objectType="CREDIT_MEMO" scopeMode="EVALUATE" description="No NetSuite credit memo is left open. Reads every credit memo so an applied one is seen and closes." companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=GORJANA_HG_ACCOUNT_SCOPE|ruleSetId=GORJANA_HG_ACCOUNT_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="GORJANA_HG_ACCOUNT_SCOPE" ruleSetId="GORJANA_HG_ACCOUNT_RS" objectType="CREDIT_MEMO" scopeMode="EVALUATE" description="Every credit memo flagged Is HG posts to 42065 Happiness Guarantee. Reads every credit memo so a corrected one is seen and closes." companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=GORJANA_RETURN_CREDIT_CUSTOMER_SCOPE|ruleSetId=GORJANA_RETURN_CREDIT_CUSTOMER_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="GORJANA_RETURN_CREDIT_CUSTOMER_SCOPE" ruleSetId="GORJANA_RETURN_CREDIT_CUSTOMER_RS" objectType="CREDIT_MEMO" scopeMode="EVALUATE" description="Every NetSuite credit memo of a return carries the customer's name and the address it came from. Reads every credit memo so a fixed one is seen and closes." companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=GORJANA_RETURN_IN_NETSUITE_SCOPE|ruleSetId=GORJANA_RETURN_IN_NETSUITE_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="GORJANA_RETURN_IN_NETSUITE_SCOPE" ruleSetId="GORJANA_RETURN_IN_NETSUITE_RS" objectType="RETURN" scopeMode="COMPARE" description="Every completed or received OMS return has its RMA in NetSuite, by return id." companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=GORJANA_RMA_CUSTOMER_SCOPE|ruleSetId=GORJANA_RMA_CUSTOMER_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="GORJANA_RMA_CUSTOMER_SCOPE" ruleSetId="GORJANA_RMA_CUSTOMER_RS" objectType="RETURN_AUTHORIZATION" scopeMode="EVALUATE" description="Every NetSuite RMA of a return carries the customer's name and the address it came from. Reads every RMA so a fixed one is seen and closes." companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=GORJANA_CANCELLED_RETURN_SCOPE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="GORJANA_CANCELLED_RETURN_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="returnId" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=GORJANA_CREDIT_OPEN_SCOPE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="GORJANA_CREDIT_OPEN_SCOPE" fileSide="FILE_1" sequenceNum="1" fieldExpression="tranId" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=GORJANA_HG_ACCOUNT_SCOPE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="GORJANA_HG_ACCOUNT_SCOPE" fileSide="FILE_1" sequenceNum="1" fieldExpression="tranId" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=GORJANA_RETURN_CREDIT_CUSTOMER_SCOPE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="GORJANA_RETURN_CREDIT_CUSTOMER_SCOPE" fileSide="FILE_1" sequenceNum="1" fieldExpression="tranId" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=GORJANA_RETURN_IN_NETSUITE_SCOPE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="GORJANA_RETURN_IN_NETSUITE_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="returnId" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=GORJANA_RMA_CUSTOMER_SCOPE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="GORJANA_RMA_CUSTOMER_SCOPE" fileSide="FILE_1" sequenceNum="1" fieldExpression="tranId" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=GORJANA_CANCELLED_RETURN_SCOPE|systemEnumId=NETSUITE_SUITEQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=NS_OPEN_RMA|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="GORJANA_CANCELLED_RETURN_SCOPE" fileSide="FILE_2" systemEnumId="NETSUITE_SUITEQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="returnId" sourceConfigId="NS_OPEN_RMA" sourceConfigType="NETSUITE_SUITEQL" duplicateKeyHandling="FINDING" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=GORJANA_CANCELLED_RETURN_SCOPE|systemEnumId=OMS_RETURNS_GQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=gorjana_prod|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="GORJANA_CANCELLED_RETURN_SCOPE" fileSide="FILE_1" systemEnumId="OMS_RETURNS_GQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="returnId" sourceConfigId="gorjana_prod" sourceConfigType="HOTWAX_OMS_GQL_RETURNS" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=GORJANA_CREDIT_OPEN_SCOPE|systemEnumId=NETSUITE_SUITEQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=NS_CREDIT_MEMO_STATUS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="GORJANA_CREDIT_OPEN_SCOPE" fileSide="FILE_1" systemEnumId="NETSUITE_SUITEQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="tranId" sourceConfigId="NS_CREDIT_MEMO_STATUS" sourceConfigType="NETSUITE_SUITEQL" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=GORJANA_HG_ACCOUNT_SCOPE|systemEnumId=NETSUITE_SUITEQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=NS_CREDIT_MEMO_HG_ACCOUNT|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="GORJANA_HG_ACCOUNT_SCOPE" fileSide="FILE_1" systemEnumId="NETSUITE_SUITEQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="tranId" sourceConfigId="NS_CREDIT_MEMO_HG_ACCOUNT" sourceConfigType="NETSUITE_SUITEQL" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=GORJANA_RETURN_CREDIT_CUSTOMER_SCOPE|systemEnumId=NETSUITE_SUITEQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=NS_RETURN_CREDIT_CUSTOMER|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="GORJANA_RETURN_CREDIT_CUSTOMER_SCOPE" fileSide="FILE_1" systemEnumId="NETSUITE_SUITEQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="tranId" sourceConfigId="NS_RETURN_CREDIT_CUSTOMER" sourceConfigType="NETSUITE_SUITEQL" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=GORJANA_RETURN_IN_NETSUITE_SCOPE|systemEnumId=NETSUITE_SUITEQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=NS_RETURN_RMA|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="GORJANA_RETURN_IN_NETSUITE_SCOPE" fileSide="FILE_2" systemEnumId="NETSUITE_SUITEQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="returnId" sourceConfigId="NS_RETURN_RMA" sourceConfigType="NETSUITE_SUITEQL" duplicateKeyHandling="FINDING" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=GORJANA_RETURN_IN_NETSUITE_SCOPE|systemEnumId=OMS_RETURNS_GQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=gorjana_prod|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="GORJANA_RETURN_IN_NETSUITE_SCOPE" fileSide="FILE_1" systemEnumId="OMS_RETURNS_GQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="returnId" sourceConfigId="gorjana_prod" sourceConfigType="HOTWAX_OMS_GQL_RETURNS" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=GORJANA_RMA_CUSTOMER_SCOPE|systemEnumId=NETSUITE_SUITEQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=NS_RMA_CUSTOMER|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="GORJANA_RMA_CUSTOMER_SCOPE" fileSide="FILE_1" systemEnumId="NETSUITE_SUITEQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="tranId" sourceConfigId="NS_RMA_CUSTOMER" sourceConfigType="NETSUITE_SUITEQL" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionCondition|compareScopeId=GORJANA_CREDIT_OPEN_SCOPE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionCondition`

```xml
<darpan.rule.RuleSetConclusionCondition compareScopeId="GORJANA_CREDIT_OPEN_SCOPE" sequenceNum="10" conditionSeq="2" subject="FILE_1" presence="ANY" keyScope="FULL" fieldExpression="unapplied" operator="GT" conditionValues="5" checkLabel="More than $5 unapplied" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionCondition|compareScopeId=GORJANA_HG_ACCOUNT_SCOPE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionCondition`

```xml
<darpan.rule.RuleSetConclusionCondition compareScopeId="GORJANA_HG_ACCOUNT_SCOPE" sequenceNum="10" conditionSeq="3" subject="FILE_1" presence="ANY" keyScope="FULL" fieldExpression="amount" operator="GT_ZERO" checkLabel="The memo credits an amount" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionCondition|compareScopeId=GORJANA_RETURN_CREDIT_CUSTOMER_SCOPE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionCondition`

```xml
<darpan.rule.RuleSetConclusionCondition compareScopeId="GORJANA_RETURN_CREDIT_CUSTOMER_SCOPE" sequenceNum="30" conditionSeq="2" subject="FILE_1" presence="ANY" keyScope="FULL" fieldExpression="billAddressId" operator="BLANK" checkLabel="The credit memo has no billing address" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionCondition|compareScopeId=GORJANA_RETURN_IN_NETSUITE_SCOPE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionCondition`

```xml
<darpan.rule.RuleSetConclusionCondition compareScopeId="GORJANA_RETURN_IN_NETSUITE_SCOPE" sequenceNum="20" conditionSeq="2" subject="FILE_1" presence="ANY" keyScope="FULL" fieldExpression="fromPartyId" operator="NOT_IN" conditionValues="DISCONTINUED" checkLabel="The return is not from DISCONTINUED" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionCondition|compareScopeId=GORJANA_RMA_CUSTOMER_SCOPE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionCondition`

```xml
<darpan.rule.RuleSetConclusionCondition compareScopeId="GORJANA_RMA_CUSTOMER_SCOPE" sequenceNum="30" conditionSeq="2" subject="FILE_1" presence="ANY" keyScope="FULL" fieldExpression="billAddressId" operator="BLANK" checkLabel="The RMA has no billing address" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_CREDIT_OPEN_SCOPE|conclusionEnumId=CONC_MEMO_LEFT_OPEN|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_CREDIT_OPEN_SCOPE" sequenceNum="10" conclusionEnumId="CONC_MEMO_LEFT_OPEN" appliesToBucket="FINDING" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_HG_ACCOUNT_SCOPE|conclusionEnumId=CONC_HG_NOT_ON_42065|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_HG_ACCOUNT_SCOPE" sequenceNum="10" conclusionEnumId="CONC_HG_NOT_ON_42065" appliesToBucket="FINDING" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_RETURN_CREDIT_CUSTOMER_SCOPE|conclusionEnumId=CONC_NO_BILLING_ADDRESS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_RETURN_CREDIT_CUSTOMER_SCOPE" sequenceNum="30" conclusionEnumId="CONC_NO_BILLING_ADDRESS" appliesToBucket="FINDING" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_RETURN_CREDIT_CUSTOMER_SCOPE|conclusionEnumId=CONC_NO_CUSTOMER_NAME|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_RETURN_CREDIT_CUSTOMER_SCOPE" sequenceNum="10" conclusionEnumId="CONC_NO_CUSTOMER_NAME" appliesToBucket="FINDING" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_RETURN_CREDIT_CUSTOMER_SCOPE|conclusionEnumId=CONC_NO_RETURN_ADDRESS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_RETURN_CREDIT_CUSTOMER_SCOPE" sequenceNum="20" conclusionEnumId="CONC_NO_RETURN_ADDRESS" appliesToBucket="FINDING" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_RETURN_IN_NETSUITE_SCOPE|conclusionEnumId=CONC_RETURN_NOT_IN_NETSUITE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_RETURN_IN_NETSUITE_SCOPE" sequenceNum="20" conclusionEnumId="CONC_RETURN_NOT_IN_NETSUITE" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_RETURN_IN_NETSUITE_SCOPE|conclusionEnumId=CONC_RETURN_NO_ORDER_NOT_SYNCED|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_RETURN_IN_NETSUITE_SCOPE" sequenceNum="10" conclusionEnumId="CONC_RETURN_NO_ORDER_NOT_SYNCED" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_RMA_CUSTOMER_SCOPE|conclusionEnumId=CONC_NO_BILLING_ADDRESS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_RMA_CUSTOMER_SCOPE" sequenceNum="30" conclusionEnumId="CONC_NO_BILLING_ADDRESS" appliesToBucket="FINDING" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_RMA_CUSTOMER_SCOPE|conclusionEnumId=CONC_NO_CUSTOMER_NAME|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_RMA_CUSTOMER_SCOPE" sequenceNum="10" conclusionEnumId="CONC_NO_CUSTOMER_NAME" appliesToBucket="FINDING" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_RMA_CUSTOMER_SCOPE|conclusionEnumId=CONC_NO_RETURN_ADDRESS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_RMA_CUSTOMER_SCOPE" sequenceNum="20" conclusionEnumId="CONC_NO_RETURN_ADDRESS" appliesToBucket="FINDING" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=GORJANA_CANCELLED_RETURN_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="GORJANA_CANCELLED_RETURN_RS" ruleSetName="Cancelled OMS returns - open NetSuite RMAs" description="A return the OMS cancelled has no RMA left open in NetSuite." version="1.0" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=GORJANA_CREDIT_OPEN_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="GORJANA_CREDIT_OPEN_RS" ruleSetName="Credit memos - left open" description="No NetSuite credit memo is left open. Reads every credit memo so an applied one is seen and closes." version="1.0" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=GORJANA_HG_ACCOUNT_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="GORJANA_HG_ACCOUNT_RS" ruleSetName="Credit memos - Happiness Guarantee account" description="Every credit memo flagged Is HG posts to 42065 Happiness Guarantee. Reads every credit memo so a corrected one is seen and closes." version="1.0" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=GORJANA_RETURN_CREDIT_CUSTOMER_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="GORJANA_RETURN_CREDIT_CUSTOMER_RS" ruleSetName="Return credit memos - customer name and address" description="Every NetSuite credit memo of a return carries the customer's name and the address it came from. Reads every credit memo so a fixed one is seen and closes." version="1.0" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=GORJANA_RETURN_IN_NETSUITE_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="GORJANA_RETURN_IN_NETSUITE_RS" ruleSetName="OMS returns - NetSuite RMAs" description="Every completed or received OMS return has its RMA in NetSuite, by return id." version="1.0" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=GORJANA_RMA_CUSTOMER_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="GORJANA_RMA_CUSTOMER_RS" ruleSetName="RMAs - customer name and address" description="Every NetSuite RMA of a return carries the customer's name and the address it came from. Reads every RMA so a fixed one is seen and closes." version="1.0" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.Rule|ruleId=GORJANA_R16_STATUS|ruleSetId=GORJANA_CANCELLED_RETURN_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.Rule`

```xml
<darpan.rule.Rule ruleId="GORJANA_R16_STATUS" ruleSetId="GORJANA_CANCELLED_RETURN_RS" sequenceNum="10" ruleText="statusId = status" enabled="Y" ruleType="FIELD_COMPARISON" severity="WARN" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_HG_NOT_ON_42065|enumTypeId=DarpanConclusion|enumCode=CONC_HG_NOT_ON_42065`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_HG_NOT_ON_42065" enumTypeId="DarpanConclusion" enumCode="CONC_HG_NOT_ON_42065" description="Happiness Guarantee credit not on 42065"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_MEMO_LEFT_OPEN|enumTypeId=DarpanConclusion|enumCode=CONC_MEMO_LEFT_OPEN`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_MEMO_LEFT_OPEN" enumTypeId="DarpanConclusion" enumCode="CONC_MEMO_LEFT_OPEN" description="Credit memo left open"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_NO_BILLING_ADDRESS|enumTypeId=DarpanConclusion|enumCode=CONC_NO_BILLING_ADDRESS`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_NO_BILLING_ADDRESS" enumTypeId="DarpanConclusion" enumCode="CONC_NO_BILLING_ADDRESS" description="No billing address"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_NO_CUSTOMER_NAME|enumTypeId=DarpanConclusion|enumCode=CONC_NO_CUSTOMER_NAME`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_NO_CUSTOMER_NAME" enumTypeId="DarpanConclusion" enumCode="CONC_NO_CUSTOMER_NAME" description="No customer name"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_NO_RETURN_ADDRESS|enumTypeId=DarpanConclusion|enumCode=CONC_NO_RETURN_ADDRESS`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_NO_RETURN_ADDRESS" enumTypeId="DarpanConclusion" enumCode="CONC_NO_RETURN_ADDRESS" description="No address it came from"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_RETURN_NOT_IN_NETSUITE|enumTypeId=DarpanConclusion|enumCode=CONC_RETURN_NOT_IN_NETSUITE`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_RETURN_NOT_IN_NETSUITE" enumTypeId="DarpanConclusion" enumCode="CONC_RETURN_NOT_IN_NETSUITE" description="Return not in NetSuite"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_RETURN_NO_ORDER_NOT_SYNCED|enumTypeId=DarpanConclusion|enumCode=CONC_RETURN_NO_ORDER_NOT_SYNCED`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_RETURN_NO_ORDER_NOT_SYNCED" enumTypeId="DarpanConclusion" enumCode="CONC_RETURN_NO_ORDER_NOT_SYNCED" description="No order"/>
```

### Added in `data/GorjanaMonthCloseWarrantyClaimChecksData.xml`

- Record: `darpan.audit.AuditCheck|checkId=GORJANA_CHK_WARRANTY_INVOICE_OPEN|companyUserGroupId=GORJANA|savedRunId=GORJANA_WTY_INVOICE_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="GORJANA_CHK_WARRANTY_INVOICE_OPEN" companyUserGroupId="GORJANA" checkCode="warranty-replacement-invoices-not-paid" label="Warranty replacement invoices not paid by the claim's credit" savedRunId="GORJANA_WTY_INVOICE_RS" file1RecordType="invoice" graceDays="2" fixOwner="HotWax Support" fixWhereEnumId="NETSUITE" headlineTemplate="Warranty replacement invoice {recordKey} is still open" fixActionTemplate="Apply the warranty claim's credit memo to invoice {recordKey} so both close." passesWhenTemplate="Invoice {recordKey} is Paid In Full."/>
```

### Added in `data/GorjanaMonthCloseWarrantyClaimChecksData.xml`

- Record: `darpan.audit.AuditCheck|checkId=GORJANA_CHK_WARRANTY_MEMO_AMOUNT|companyUserGroupId=GORJANA|savedRunId=GORJANA_WARRANTY_CLAIM_RS|amountCurrencyUomId=USD|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="GORJANA_CHK_WARRANTY_MEMO_AMOUNT" companyUserGroupId="GORJANA" checkCode="warranty-memos-not-at-the-claimed-amount" label="Warranty credit memos not at the amount claimed" savedRunId="GORJANA_WARRANTY_CLAIM_RS" file1RecordType="warrantyClaim" graceDays="1" matchedBy="return id (custbody_hc_return_id on the RMA and the credit memo)" amountCurrencyUomId="USD" expectedSource="Warranty claim (RMA total)" actualSource="NetSuite credit memo total" fixOwner="HotWax Support" fixWhereEnumId="NETSUITE" headlineTemplate="Credit memo for return {recordKey} is {file2Value}; the claim is {file1Value}" fixActionTemplate="Correct the credit memo for return {recordKey} to the amount claimed, {expectedAmount}." passesWhenTemplate="The credit memo for return {recordKey} totals {expectedAmount}."/>
```

### Added in `data/GorjanaMonthCloseWarrantyClaimChecksData.xml`

- Record: `darpan.audit.AuditCheck|checkId=GORJANA_CHK_WARRANTY_NO_MEMO|companyUserGroupId=GORJANA|savedRunId=GORJANA_WARRANTY_CLAIM_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="GORJANA_CHK_WARRANTY_NO_MEMO" companyUserGroupId="GORJANA" checkCode="warranty-claims-without-a-credit-memo" label="Warranty claims with no credit memo" savedRunId="GORJANA_WARRANTY_CLAIM_RS" file1RecordType="warrantyClaim" graceDays="2" matchedBy="return id (custbody_hc_return_id on the RMA and the credit memo)" fixOwner="HotWax Support" fixWhereEnumId="NETSUITE" headlineTemplate="Warranty claim {warrantyClaim} has no credit memo" fixActionTemplate="Create the credit memo for warranty claim {warrantyClaim}, for the amount claimed, with return id {recordKey}." passesWhenTemplate="A credit memo with return id {recordKey} exists for warranty claim {warrantyClaim}."/>
```

### Added in `data/GorjanaMonthCloseWarrantyClaimChecksData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=GORJANA_R12|checkId=GORJANA_CHK_WARRANTY_INVOICE_OPEN|companyUserGroupId=GORJANA`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="GORJANA_R12" checkId="GORJANA_CHK_WARRANTY_INVOICE_OPEN" companyUserGroupId="GORJANA" sequenceNum="3" diffTypes="FINDING" conclusionCodes="CONC_WTY_INVOICE_NOT_PAID"/>
```

### Added in `data/GorjanaMonthCloseWarrantyClaimChecksData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=GORJANA_R12|checkId=GORJANA_CHK_WARRANTY_MEMO_AMOUNT|companyUserGroupId=GORJANA`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="GORJANA_R12" checkId="GORJANA_CHK_WARRANTY_MEMO_AMOUNT" companyUserGroupId="GORJANA" sequenceNum="2" diffTypes="FIELD_MISMATCH" ruleIds="GORJANA_WC_MEMO_AT_CLAIM"/>
```

### Added in `data/GorjanaMonthCloseWarrantyClaimChecksData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=GORJANA_R12|checkId=GORJANA_CHK_WARRANTY_NO_MEMO|companyUserGroupId=GORJANA`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="GORJANA_R12" checkId="GORJANA_CHK_WARRANTY_NO_MEMO" companyUserGroupId="GORJANA" sequenceNum="1" diffTypes="MISSING_IN_FILE_2"/>
```

### Added in `data/GorjanaMonthCloseWarrantyClaimChecksData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=GORJANA_WARRANTY_CLAIM_SCOPE|ruleSetId=GORJANA_WARRANTY_CLAIM_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="GORJANA_WARRANTY_CLAIM_SCOPE" ruleSetId="GORJANA_WARRANTY_CLAIM_RS" objectType="RETURN" scopeMode="COMPARE" description="Every warranty claim against the credit memo that carries its return id: there is one, for the amount claimed." companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseWarrantyClaimChecksData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=GORJANA_WTY_INVOICE_SCOPE|ruleSetId=GORJANA_WTY_INVOICE_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="GORJANA_WTY_INVOICE_SCOPE" ruleSetId="GORJANA_WTY_INVOICE_RS" objectType="INVOICE" scopeMode="EVALUATE" description="Every warranty replacement's invoice (reference WTY-) is Paid In Full. Reads every such invoice so a paid one is seen and closes." companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseWarrantyClaimChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=GORJANA_WARRANTY_CLAIM_SCOPE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="GORJANA_WARRANTY_CLAIM_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="returnId" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseWarrantyClaimChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=GORJANA_WTY_INVOICE_SCOPE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="GORJANA_WTY_INVOICE_SCOPE" fileSide="FILE_1" sequenceNum="1" fieldExpression="tranId" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseWarrantyClaimChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=GORJANA_WARRANTY_CLAIM_SCOPE|systemEnumId=NETSUITE_SUITEQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=NS_RETURN_MEMO|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="GORJANA_WARRANTY_CLAIM_SCOPE" fileSide="FILE_2" systemEnumId="NETSUITE_SUITEQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="returnId" sourceConfigId="NS_RETURN_MEMO" sourceConfigType="NETSUITE_SUITEQL" duplicateKeyHandling="FINDING" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseWarrantyClaimChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=GORJANA_WARRANTY_CLAIM_SCOPE|systemEnumId=NETSUITE_SUITEQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=NS_WARRANTY_CLAIM|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="GORJANA_WARRANTY_CLAIM_SCOPE" fileSide="FILE_1" systemEnumId="NETSUITE_SUITEQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="returnId" sourceConfigId="NS_WARRANTY_CLAIM" sourceConfigType="NETSUITE_SUITEQL" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseWarrantyClaimChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=GORJANA_WTY_INVOICE_SCOPE|systemEnumId=NETSUITE_SUITEQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=NS_INVOICE_REFERENCE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="GORJANA_WTY_INVOICE_SCOPE" fileSide="FILE_1" systemEnumId="NETSUITE_SUITEQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="tranId" sourceConfigId="NS_INVOICE_REFERENCE" sourceConfigType="NETSUITE_SUITEQL" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseWarrantyClaimChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionCondition|compareScopeId=GORJANA_WTY_INVOICE_SCOPE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionCondition`

```xml
<darpan.rule.RuleSetConclusionCondition compareScopeId="GORJANA_WTY_INVOICE_SCOPE" sequenceNum="10" conditionSeq="2" subject="FILE_1" presence="ANY" keyScope="FULL" fieldExpression="amountDue" operator="GT" conditionValues="5" checkLabel="More than $5 due" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseWarrantyClaimChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_WTY_INVOICE_SCOPE|conclusionEnumId=CONC_WTY_INVOICE_NOT_PAID|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_WTY_INVOICE_SCOPE" sequenceNum="10" conclusionEnumId="CONC_WTY_INVOICE_NOT_PAID" appliesToBucket="FINDING" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseWarrantyClaimChecksData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=GORJANA_WARRANTY_CLAIM_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="GORJANA_WARRANTY_CLAIM_RS" ruleSetName="Warranty claims - credit memos" description="Every warranty claim against the credit memo that carries its return id: there is one, for the amount claimed." version="1.0" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseWarrantyClaimChecksData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=GORJANA_WTY_INVOICE_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="GORJANA_WTY_INVOICE_RS" ruleSetName="Warranty replacement invoices - paid" description="Every warranty replacement's invoice (reference WTY-) is Paid In Full: the claim's credit and the replacement invoice close each other out. Reads every such invoice so a paid one is seen and closes." version="1.0" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseWarrantyClaimChecksData.xml`

- Record: `darpan.rule.Rule|ruleId=GORJANA_WC_MEMO_AT_CLAIM|ruleSetId=GORJANA_WARRANTY_CLAIM_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.Rule`

```xml
<darpan.rule.Rule ruleId="GORJANA_WC_MEMO_AT_CLAIM" ruleSetId="GORJANA_WARRANTY_CLAIM_RS" sequenceNum="10" ruleText="amount = amount" enabled="Y" ruleType="FIELD_COMPARISON" severity="WARN" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseWarrantyClaimChecksData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_WTY_INVOICE_NOT_PAID|enumTypeId=DarpanConclusion|enumCode=CONC_WTY_INVOICE_NOT_PAID`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_WTY_INVOICE_NOT_PAID" enumTypeId="DarpanConclusion" enumCode="CONC_WTY_INVOICE_NOT_PAID" description="Invoice unpaid"/>
```

### Added in `data/GorjanaReturnLifecycleConclusionData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_RTN_EXCHANGE_NOT_BACK|enumTypeId=DarpanConclusion|enumCode=CONC_RTN_EXCHANGE_NOT_BACK`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_RTN_EXCHANGE_NOT_BACK" enumTypeId="DarpanConclusion" enumCode="CONC_RTN_EXCHANGE_NOT_BACK" description="Exchange sent, goods not back"/>
```

### Added in `data/GorjanaReturnLifecycleConclusionData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_RTN_EXCHANGE_NO_ORDER|enumTypeId=DarpanConclusion|enumCode=CONC_RTN_EXCHANGE_NO_ORDER`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_RTN_EXCHANGE_NO_ORDER" enumTypeId="DarpanConclusion" enumCode="CONC_RTN_EXCHANGE_NO_ORDER" description="Exchange with no replacement order"/>
```

### Added in `data/GorjanaReturnLifecycleConclusionData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_RTN_NEVER_SHIPPED_BACK|enumTypeId=DarpanConclusion|enumCode=CONC_RTN_NEVER_SHIPPED_BACK`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_RTN_NEVER_SHIPPED_BACK" enumTypeId="DarpanConclusion" enumCode="CONC_RTN_NEVER_SHIPPED_BACK" description="Never shipped back"/>
```

### Added in `data/GorjanaReturnLifecycleConclusionData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_RTN_POS_NOT_RECEIVED|enumTypeId=DarpanConclusion|enumCode=CONC_RTN_POS_NOT_RECEIVED`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_RTN_POS_NOT_RECEIVED" enumTypeId="DarpanConclusion" enumCode="CONC_RTN_POS_NOT_RECEIVED" description="POS return not received in NetSuite"/>
```

### Added in `data/GorjanaReturnLifecycleConclusionData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_RTN_REFUND_NOT_CREDITED|enumTypeId=DarpanConclusion|enumCode=CONC_RTN_REFUND_NOT_CREDITED`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_RTN_REFUND_NOT_CREDITED" enumTypeId="DarpanConclusion" enumCode="CONC_RTN_REFUND_NOT_CREDITED" description="Refund owed, not credited"/>
```

### Added in `data/GorjanaReturnLifecycleConclusionData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_RTN_REFUND_NOT_PAID|enumTypeId=DarpanConclusion|enumCode=CONC_RTN_REFUND_NOT_PAID`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_RTN_REFUND_NOT_PAID" enumTypeId="DarpanConclusion" enumCode="CONC_RTN_REFUND_NOT_PAID" description="Refund credited, not paid"/>
```

### Added in `data/GorjanaReturnLifecycleConclusionData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_RTN_STORE_CREDIT_NOT_BOOKED|enumTypeId=DarpanConclusion|enumCode=CONC_RTN_STORE_CREDIT_NOT_BOOKED`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_RTN_STORE_CREDIT_NOT_BOOKED" enumTypeId="DarpanConclusion" enumCode="CONC_RTN_STORE_CREDIT_NOT_BOOKED" description="Store credit given, NetSuite stopped at the RMA"/>
```

### Added in `data/GorjanaReturnLifecycleConclusionData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_RTN_STORE_CREDIT_NOT_ISSUED|enumTypeId=DarpanConclusion|enumCode=CONC_RTN_STORE_CREDIT_NOT_ISSUED`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_RTN_STORE_CREDIT_NOT_ISSUED" enumTypeId="DarpanConclusion" enumCode="CONC_RTN_STORE_CREDIT_NOT_ISSUED" description="Store credit not invoiced"/>
```

### Added in `data/GorjanaReturnLifecycleConclusionData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_RTN_WARRANTY_IN_PROGRESS|enumTypeId=DarpanConclusion|enumCode=CONC_RTN_WARRANTY_IN_PROGRESS`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_RTN_WARRANTY_IN_PROGRESS" enumTypeId="DarpanConclusion" enumCode="CONC_RTN_WARRANTY_IN_PROGRESS" description="Warranty claim in progress"/>
```

### Added in `data/GorjanaReturnLifecycleConclusionData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_RTN_WARRANTY_NO_TXN|enumTypeId=DarpanConclusion|enumCode=CONC_RTN_WARRANTY_NO_TXN`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_RTN_WARRANTY_NO_TXN" enumTypeId="DarpanConclusion" enumCode="CONC_RTN_WARRANTY_NO_TXN" description="Warranty claim, nothing in NetSuite"/>
```

### Added in `data/PasswordResetEmailSeedData.xml`

- Record: `moqui.basic.EnumerationType|enumTypeId=DarpanResetCodePurpose`
- Element: `moqui.basic.EnumerationType`

```xml
<moqui.basic.EnumerationType description="Password Reset Code Purpose" enumTypeId="DarpanResetCodePurpose"/>
```

### Added in `data/PasswordResetEmailSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=DrpnRcPasswordReset|enumTypeId=DarpanResetCodePurpose`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration description="Password reset" enumId="DrpnRcPasswordReset" enumTypeId="DarpanResetCodePurpose"/>
```

### Added in `data/PasswordResetEmailSeedData.xml`

- Record: `moqui.basic.email.EmailServer|emailServerId=SYSTEM`
- Element: `moqui.basic.email.EmailServer`

```xml
<moqui.basic.email.EmailServer emailServerId="SYSTEM"/>
```

### Added in `data/PasswordResetEmailSeedData.xml`

- Record: `moqui.basic.email.EmailTemplate|emailTemplateId=DARPAN_PASSWORD_CHANGED|emailServerId=SYSTEM`
- Element: `moqui.basic.email.EmailTemplate`

```xml
<moqui.basic.email.EmailTemplate emailTemplateId="DARPAN_PASSWORD_CHANGED" description="Darpan password changed notice" emailServerId="SYSTEM" webappName="webroot" sendPartial="Y" subject="Your Darpan password was changed" fromAddress="${System.getenv('DARPAN_MAIL_FROM') ?: 'support@drpn.ai'}" fromName="Darpan" bodyScreenLocation="component://darpan/screen/email/PasswordChanged.xml"/>
```

### Added in `data/PasswordResetEmailSeedData.xml`

- Record: `moqui.basic.email.EmailTemplate|emailTemplateId=DARPAN_PASSWORD_RESET_CODE|emailServerId=SYSTEM`
- Element: `moqui.basic.email.EmailTemplate`

```xml
<moqui.basic.email.EmailTemplate emailTemplateId="DARPAN_PASSWORD_RESET_CODE" description="Darpan password reset code" emailServerId="SYSTEM" webappName="webroot" sendPartial="Y" subject="Your Darpan reset code" fromAddress="${System.getenv('DARPAN_MAIL_FROM') ?: 'support@drpn.ai'}" fromName="Darpan" bodyScreenLocation="component://darpan/screen/email/PasswordResetCode.xml"/>
```

### Added in `data/PasswordResetEmailSeedData.xml`

- Record: `moqui.basic.email.EmailTemplate|emailTemplateId=DARPAN_RECOVERY_EMAIL_SET|emailServerId=SYSTEM`
- Element: `moqui.basic.email.EmailTemplate`

```xml
<moqui.basic.email.EmailTemplate emailTemplateId="DARPAN_RECOVERY_EMAIL_SET" description="Darpan recovery email set notice" emailServerId="SYSTEM" webappName="webroot" sendPartial="Y" subject="Darpan recovery email set" fromAddress="${System.getenv('DARPAN_MAIL_FROM') ?: 'support@drpn.ai'}" fromName="Darpan" bodyScreenLocation="component://darpan/screen/email/RecoveryEmailSet.xml"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditCheck|checkId=RAILS_CHK_OPEN_NOT_IN_OMS|companyUserGroupId=RAILS|savedRunId=RAILS_OPEN_OMS_STATUS_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="RAILS_CHK_OPEN_NOT_IN_OMS" companyUserGroupId="RAILS" checkCode="open-in-netsuite-not-in-hotwax" label="NetSuite orders never shipped or billed whose HC Order Id HotWax does not have" savedRunId="RAILS_OPEN_OMS_STATUS_RS" file1RecordType="salesOrder" graceDays="4" matchedBy="HC Order Id (custbody_hc_order_id) = HotWax order id" fixOwner="HotWax Support" fixWhereEnumId="NETSUITE" headlineTemplate="Order {recordKey} names a HotWax order HotWax does not have" fixActionTemplate="Find the HotWax order for {recordKey}, or close the NetSuite order if it never came from HotWax." passesWhenTemplate="HotWax has the order NetSuite order {recordKey} names, or NetSuite has moved it on."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditCheck|checkId=RAILS_CHK_OPEN_OMS_DONE|companyUserGroupId=RAILS|savedRunId=RAILS_OPEN_OMS_STATUS_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="RAILS_CHK_OPEN_OMS_DONE" companyUserGroupId="RAILS" checkCode="open-in-netsuite-done-in-hotwax" label="NetSuite orders never shipped or billed that HotWax completed or cancelled" savedRunId="RAILS_OPEN_OMS_STATUS_RS" file1RecordType="salesOrder" graceDays="4" matchedBy="HC Order Id (custbody_hc_order_id) = HotWax order id" fixOwner="HotWax Support" fixWhereEnumId="NETSUITE" headlineTemplate="Order {recordKey} is done in HotWax and still open in NetSuite" fixActionTemplate="Ship and bill order {recordKey} in NetSuite as HotWax completed it, or close it if HotWax cancelled it." passesWhenTemplate="NetSuite has shipped, billed or closed order {recordKey}."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=RAILS_R1|checkId=RAILS_CHK_OPEN_NOT_IN_OMS|companyUserGroupId=RAILS`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="RAILS_R1" checkId="RAILS_CHK_OPEN_NOT_IN_OMS" companyUserGroupId="RAILS" sequenceNum="2" diffTypes="FINDING" conclusionCodes="CONC_NS_ORDER_NOT_IN_OMS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=RAILS_R1|checkId=RAILS_CHK_OPEN_OMS_DONE|companyUserGroupId=RAILS`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="RAILS_R1" checkId="RAILS_CHK_OPEN_OMS_DONE" companyUserGroupId="RAILS" sequenceNum="1" diffTypes="FINDING" conclusionCodes="CONC_OMS_COMPLETED_NS_OPEN,CONC_OMS_CANCELLED_NS_OPEN"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatement|statementId=RAILS_R1|auditId=RAILS_MONTH_CLOSE|companyUserGroupId=RAILS|categoryEnumId=AUDC_ORDERS|weightEnumId=AUDSW_FIRM`
- Element: `darpan.audit.AuditStatement`

```xml
<darpan.audit.AuditStatement statementId="RAILS_R1" auditId="RAILS_MONTH_CLOSE" companyUserGroupId="RAILS" statementCode="R1" categoryEnumId="AUDC_ORDERS" weightEnumId="AUDSW_FIRM" sequenceNum="1" statementText="An order HotWax completed or cancelled does not stay open in NetSuite."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.Audit|auditId=RAILS_MONTH_CLOSE|companyUserGroupId=RAILS|periodEnumId=AUDP_MONTH`
- Element: `darpan.audit.Audit`

```xml
<darpan.audit.Audit auditId="RAILS_MONTH_CLOSE" companyUserGroupId="RAILS" auditCode="month-close" auditName="Month close" periodEnumId="AUDP_MONTH"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=RAILS_OPEN_OMS_STATUS_SCOPE|ruleSetId=RAILS_OPEN_OMS_STATUS_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="RAILS_OPEN_OMS_STATUS_SCOPE" ruleSetId="RAILS_OPEN_OMS_STATUS_RS" objectType="ORDER" scopeMode="EVALUATE" description="Sales orders NetSuite never shipped or billed, against HotWax: completed, cancelled while still open in NetSuite, or not in HotWax. Reads every HotWax order so a fixed one closes." companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceFilter|compareScopeId=RAILS_OPEN_OMS_STATUS_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceFilter`

```xml
<darpan.rule.RuleSetCompareSourceFilter compareScopeId="RAILS_OPEN_OMS_STATUS_SCOPE" fileSide="FILE_1" sequenceNum="1" fieldExpression="orderOrigin" operator="INCLUDE_IN" filterValues="HOTWAX" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=RAILS_OPEN_OMS_STATUS_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="RAILS_OPEN_OMS_STATUS_SCOPE" fileSide="FILE_1" sequenceNum="1" fieldExpression="tranId" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_OPEN_OMS_STATUS_SCOPE|systemEnumId=NETSUITE_SUITEQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=RAILS_NS_SO_OPEN_OMS_STATUS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_OPEN_OMS_STATUS_SCOPE" fileSide="FILE_1" systemEnumId="NETSUITE_SUITEQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="tranId" sourceConfigId="RAILS_NS_SO_OPEN_OMS_STATUS" sourceConfigType="NETSUITE_SUITEQL" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionCondition|compareScopeId=RAILS_OPEN_OMS_STATUS_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionCondition`

```xml
<darpan.rule.RuleSetConclusionCondition compareScopeId="RAILS_OPEN_OMS_STATUS_SCOPE" sequenceNum="30" conditionSeq="3" subject="FILE_1" presence="ANY" keyScope="FULL" fieldExpression="omsStatusId" operator="IN" conditionValues="NOT_IN_OMS" checkLabel="HotWax has no order with its HC Order Id" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_OPEN_OMS_STATUS_SCOPE|conclusionEnumId=CONC_NS_ORDER_NOT_IN_OMS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_OPEN_OMS_STATUS_SCOPE" sequenceNum="30" conclusionEnumId="CONC_NS_ORDER_NOT_IN_OMS" appliesToBucket="FINDING" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_OPEN_OMS_STATUS_SCOPE|conclusionEnumId=CONC_OMS_CANCELLED_NS_OPEN|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_OPEN_OMS_STATUS_SCOPE" sequenceNum="20" conclusionEnumId="CONC_OMS_CANCELLED_NS_OPEN" appliesToBucket="FINDING" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_OPEN_OMS_STATUS_SCOPE|conclusionEnumId=CONC_OMS_COMPLETED_NS_OPEN|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_OPEN_OMS_STATUS_SCOPE" sequenceNum="10" conclusionEnumId="CONC_OMS_COMPLETED_NS_OPEN" appliesToBucket="FINDING" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=RAILS_OPEN_OMS_STATUS_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="RAILS_OPEN_OMS_STATUS_RS" ruleSetName="Open in NetSuite - HotWax status" description="Sales orders NetSuite never shipped or billed, against HotWax: completed, cancelled while still open in NetSuite, or not in HotWax. Reads every HotWax order so a fixed one closes." version="1.0" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_NS_ORDER_NOT_IN_OMS|enumTypeId=DarpanConclusion|enumCode=CONC_NS_ORDER_NOT_IN_OMS`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_NS_ORDER_NOT_IN_OMS" enumTypeId="DarpanConclusion" enumCode="CONC_NS_ORDER_NOT_IN_OMS" description="HotWax has no order with this HC Order Id"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_OMS_CANCELLED_NS_OPEN|enumTypeId=DarpanConclusion|enumCode=CONC_OMS_CANCELLED_NS_OPEN`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_OMS_CANCELLED_NS_OPEN" enumTypeId="DarpanConclusion" enumCode="CONC_OMS_CANCELLED_NS_OPEN" description="Cancelled in HotWax, still open in NetSuite"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_OMS_COMPLETED_NS_OPEN|enumTypeId=DarpanConclusion|enumCode=CONC_OMS_COMPLETED_NS_OPEN`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_OMS_COMPLETED_NS_OPEN" enumTypeId="DarpanConclusion" enumCode="CONC_OMS_COMPLETED_NS_OPEN" description="Completed in HotWax, never shipped or billed in NetSuite"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUD_AUDIT|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUD_AUDIT" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.audit.Audit" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUD_CHECK_RUN|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUD_CHECK_RUN" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.audit.AuditCheckRun" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUD_CHECK|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUD_CHECK" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.audit.AuditCheck" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUD_DIGEST|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUD_DIGEST" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.audit.AuditDigest" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUD_FINDING_CHECK|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUD_FINDING_CHECK" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.audit.AuditFindingCondition" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUD_FINDING_EVENT|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUD_FINDING_EVENT" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.audit.AuditFindingEvent" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUD_FINDING_RECORD|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUD_FINDING_RECORD" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.audit.AuditFindingRecord" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUD_FINDING|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUD_FINDING" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.audit.AuditFinding" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUD_SOURCE_READ|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUD_SOURCE_READ" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.audit.AuditSourceRead" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUD_STATEMENT_CHECK|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUD_STATEMENT_CHECK" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.audit.AuditStatementCheck" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUD_STATEMENT|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUD_STATEMENT" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.audit.AuditStatement" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUTOMATION_STREAK|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUTOMATION_STREAK" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.AutomationStreak" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Modified in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_GQL|fieldPath=$.records[*].hasPaymentPreference`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_GQL" fieldPath="$.records[*].hasPaymentPreference" label="Has payment" fieldType="string" sequenceNum="8" description="Y when the order carries at least one OrderPaymentPreference, else N. Compared against Shopify hasPaymentTransaction by the presence OPP rule (hasPaymentTransaction requires hasPaymentPreference, DAR-BE-126)."/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_GQL|fieldPath=$.records[*].isCompleted`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_GQL" fieldPath="$.records[*].isCompleted" label="Completed" fieldType="string" sequenceNum="10" description="DAR-BE-125: Y when statusId is ORDER_COMPLETED, else N. Compared against Shopify isFulfilled (isFulfilled required by isCompleted, DAR-BE-126)."/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_ORDER_ITEMS_GQL|fieldPath=$.records[*].facilityId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_ORDER_ITEMS_GQL" fieldPath="$.records[*].facilityId" label="Facility" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="11"/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_ORDER_ITEMS_GQL|fieldPath=$.records[*].omsOrderId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_ORDER_ITEMS_GQL" fieldPath="$.records[*].omsOrderId" label="OMS order ID" fieldType="string" isPrimaryIdCandidate="Y" sequenceNum="1"/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_ORDER_ITEMS_GQL|fieldPath=$.records[*].omsOrderName`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_ORDER_ITEMS_GQL" fieldPath="$.records[*].omsOrderName" label="Order name" fieldType="string" isPrimaryIdCandidate="Y" sequenceNum="3"/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_ORDER_ITEMS_GQL|fieldPath=$.records[*].omsOrderStatusId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_ORDER_ITEMS_GQL" fieldPath="$.records[*].omsOrderStatusId" label="Order status" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="12"/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_ORDER_ITEMS_GQL|fieldPath=$.records[*].orderItemSeqId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_ORDER_ITEMS_GQL" fieldPath="$.records[*].orderItemSeqId" label="Order item seq ID" fieldType="string" isPrimaryIdCandidate="Y" sequenceNum="2"/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_ORDER_ITEMS_GQL|fieldPath=$.records[*].productId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_ORDER_ITEMS_GQL" fieldPath="$.records[*].productId" label="Product" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="8"/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_ORDER_ITEMS_GQL|fieldPath=$.records[*].shopifyLineId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_ORDER_ITEMS_GQL" fieldPath="$.records[*].shopifyLineId" label="Shopify line ID" fieldType="string" isPrimaryIdCandidate="Y" sequenceNum="5"/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_ORDER_ITEMS_GQL|fieldPath=$.records[*].shopifyOrderId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_ORDER_ITEMS_GQL" fieldPath="$.records[*].shopifyOrderId" label="Shopify order ID" fieldType="string" isPrimaryIdCandidate="Y" sequenceNum="4"/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_ORDER_ITEMS_GQL|fieldPath=$.records[*].statusId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_ORDER_ITEMS_GQL" fieldPath="$.records[*].statusId" label="Item status" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="9"/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_ORDER_ITEMS_GQL|fieldPath=$.records[*].unitOrdinal`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_ORDER_ITEMS_GQL" fieldPath="$.records[*].unitOrdinal" label="Unit" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="7"/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_ORDER_ITEMS_GQL|fieldPath=$.records[*].unitState`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_ORDER_ITEMS_GQL" fieldPath="$.records[*].unitState" label="Unit state" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="10"/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_ORDER_ITEMS|fieldPath=$.records[*].facilityId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_ORDER_ITEMS" fieldPath="$.records[*].facilityId" label="Facility" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="11"/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_ORDER_ITEMS|fieldPath=$.records[*].netsuiteOrderId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_ORDER_ITEMS" fieldPath="$.records[*].netsuiteOrderId" label="NetSuite order ID" fieldType="string" isPrimaryIdCandidate="Y" sequenceNum="6"/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_ORDER_ITEMS|fieldPath=$.records[*].omsOrderId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_ORDER_ITEMS" fieldPath="$.records[*].omsOrderId" label="OMS order ID" fieldType="string" isPrimaryIdCandidate="Y" sequenceNum="1"/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_ORDER_ITEMS|fieldPath=$.records[*].omsOrderName`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_ORDER_ITEMS" fieldPath="$.records[*].omsOrderName" label="Order name" fieldType="string" isPrimaryIdCandidate="Y" sequenceNum="3"/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_ORDER_ITEMS|fieldPath=$.records[*].omsOrderStatusId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_ORDER_ITEMS" fieldPath="$.records[*].omsOrderStatusId" label="Order status" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="12"/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_ORDER_ITEMS|fieldPath=$.records[*].orderItemSeqId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_ORDER_ITEMS" fieldPath="$.records[*].orderItemSeqId" label="Order item seq ID" fieldType="string" isPrimaryIdCandidate="Y" sequenceNum="2"/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_ORDER_ITEMS|fieldPath=$.records[*].productId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_ORDER_ITEMS" fieldPath="$.records[*].productId" label="Product" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="8"/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_ORDER_ITEMS|fieldPath=$.records[*].salesChannelEnumId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_ORDER_ITEMS" fieldPath="$.records[*].salesChannelEnumId" label="Sales channel" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="13"/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_ORDER_ITEMS|fieldPath=$.records[*].shopifyLineId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_ORDER_ITEMS" fieldPath="$.records[*].shopifyLineId" label="Shopify line ID" fieldType="string" isPrimaryIdCandidate="Y" sequenceNum="5"/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_ORDER_ITEMS|fieldPath=$.records[*].shopifyOrderId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_ORDER_ITEMS" fieldPath="$.records[*].shopifyOrderId" label="Shopify order ID" fieldType="string" isPrimaryIdCandidate="Y" sequenceNum="4"/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_ORDER_ITEMS|fieldPath=$.records[*].statusId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_ORDER_ITEMS" fieldPath="$.records[*].statusId" label="Item status" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="9"/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_ORDER_ITEMS|fieldPath=$.records[*].unitOrdinal`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_ORDER_ITEMS" fieldPath="$.records[*].unitOrdinal" label="Unit" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="7"/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_ORDER_ITEMS|fieldPath=$.records[*].unitState`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_ORDER_ITEMS" fieldPath="$.records[*].unitState" label="Unit state" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="10"/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_RETURNS_GQL|fieldPath=$.records[*].daysBeforeEntry`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_RETURNS_GQL" fieldPath="$.records[*].daysBeforeEntry" label="Days made before OMS entry" fieldType="number" sequenceNum="13" description="DAR-BE-116 R8: whole days between the return's own date (returnDate) and when OMS recorded it (entryDate); large for an old return recorded late. GraphQL only. NOT in keepFieldsBase."/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_RETURNS_GQL|fieldPath=$.records[*].fromPartyId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_RETURNS_GQL" fieldPath="$.records[*].fromPartyId" label="From party" fieldType="string" sequenceNum="11" description="DAR-BE-094 R8: who the return is from (DISCONTINUED on gorjana's discontinued-stock returns). GraphQL only. NOT in keepFieldsBase: filters run before projection, so the pill filters without being compared."/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_RETURNS_GQL|fieldPath=$.records[*].orderNetsuiteId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_RETURNS_GQL" fieldPath="$.records[*].orderNetsuiteId" label="Order in NetSuite" fieldType="string" sequenceNum="12" description="DAR-BE-116 R8: the origin order's NETSUITE_ORDER_ID in HotWax, blank when that order never reached NetSuite. GraphQL only. NOT in keepFieldsBase: filters and conclusions read it, the compare does not."/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=SHOPIFY|fieldPath=$.records[*].isFulfilled`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="SHOPIFY" fieldPath="$.records[*].isFulfilled" label="Fulfilled" fieldType="String" sequenceNum="9" description="DAR-BE-125: Y when displayFulfillmentStatus is FULFILLED, else N (partial is N)."/>
```

### Modified in `data/SourceSystemConnectorSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnector|systemEnumId=NETSUITE`
- Element: `darpan.reconciliation.SourceSystemConnector`

```xml
<darpan.reconciliation.SourceSystemConnector systemEnumId="NETSUITE" expectedSourceConfigType="NETSUITE_AUTH" configParameterName="nsAuthConfigId" configEntityName="darpan.reconciliation.NsAuthConfig" healthCheckServiceName="facade.NetSuiteSettingsFacadeServices.probe#NsAuthConnection" systemAliases="NETSUITE,DAR_SYS_NETSUITE" preserveWindowInstants="N" enabled="Y"/>
```

### Modified in `data/SourceSystemConnectorSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnector|systemEnumId=NETSUITE_SUITEQL`
- Element: `darpan.reconciliation.SourceSystemConnector`

```xml
<darpan.reconciliation.SourceSystemConnector systemEnumId="NETSUITE_SUITEQL" extractServiceName="reconciliation.NetSuiteOrderExtractionServices.extract#NetSuiteOrders" dateFromParameterName="windowStart" dateToParameterName="windowEnd" expectedSourceConfigType="NETSUITE_SUITEQL" configParameterName="nsSuiteQlSourceQueryId" configEntityName="darpan.reconciliation.NsSuiteQlSourceQuery" endpointLabel="SuiteQL" sendUrlTemplate="https://{account}.suitetalk.api.netsuite.com/services/rest/query/v1/suiteql" remoteSendServiceName="reconciliation.NetSuiteOrderExtractionServices.extract#NetSuiteOrders" systemAliases="NETSUITE_SUITEQL,NETSUITE_ORDERS,NETSUITE_SALES_ORDERS,DAR_SYS_NETSUITE_SUITEQL" preserveWindowInstants="N" windowDateOnly="Y" filterParameterName="sourceFilters" evidenceFieldsJson='{"system":"NetSuite","state":"status","stateLabels":{"A":"Pending Approval","B":"Pending Fulfillment","C":"Cancelled","D":"Partially Fulfilled","E":"Pending Billing/Partially Fulfilled","F":"Pending Billing","G":"Billed","H":"Closed"},"facts":["orderLineId","lineState","lineLocation","quantityShipped","quantityBilled","quantityBackordered","tranId"]}' enabled="Y"/>
```

### Modified in `data/SourceSystemConnectorSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnector|systemEnumId=OMS_GQL|remoteId=HOTWAX_ORDERS_API`
- Element: `darpan.reconciliation.SourceSystemConnector`

```xml
<darpan.reconciliation.SourceSystemConnector systemEnumId="OMS_GQL" extractServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlOrders" dateFromParameterName="windowStart" dateToParameterName="windowEnd" expectedSourceConfigType="HOTWAX_OMS_GQL" configParameterName="omsRestSourceConfigId" configEntityName="darpan.hotwax.HotWaxOmsRestSourceConfig" remoteId="HOTWAX_ORDERS_API" endpointLabel="Orders and payments" sendUrlTemplate="{baseUrl}/rest/s1/graphql" remoteSendServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlOrders" systemAliases="OMS_GQL,HOTWAX_GQL,DAR_SYS_OMS_GQL" preserveWindowInstants="N" keepFieldsParameterName="keepRecordFields" keepFieldsBase="orderId,orderName,externalId,grandTotal,orderDate,statusId,currencyUomId,billToCustomer,shipGroups" filterParameterName="sourceFilters" pairLookupServiceName="reconciliation.HotWaxOmsExtractionServices.lookup#HotWaxOmsOrdersByExternalId" healthCheckServiceName="facade.HotWaxOmsFacadeServices.probe#HotWaxOmsConnection" enabled="Y"/>
```

### Added in `data/SourceSystemConnectorSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnector|systemEnumId=OMS_ORDER_ITEMS_GQL|remoteId=HOTWAX_ORDERS_API`
- Element: `darpan.reconciliation.SourceSystemConnector`

```xml
<darpan.reconciliation.SourceSystemConnector systemEnumId="OMS_ORDER_ITEMS_GQL" extractServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlOrderItems" dateFromParameterName="windowStart" dateToParameterName="windowEnd" expectedSourceConfigType="HOTWAX_OMS_GQL_ORDER_ITEMS" configParameterName="omsRestSourceConfigId" configEntityName="darpan.hotwax.HotWaxOmsRestSourceConfig" remoteId="HOTWAX_ORDERS_API" endpointLabel="Order lines for NetSuite" sendUrlTemplate="{baseUrl}/rest/s1/graphql" remoteSendServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlOrderItems" systemAliases="OMS_ORDER_ITEMS_GQL,HOTWAX_GQL_ORDER_ITEMS,DAR_SYS_OMS_ORDER_ITEMS_GQL" preserveWindowInstants="N" filterParameterName="sourceFilters" windowFieldName="orderDate" healthCheckServiceName="facade.HotWaxOmsFacadeServices.probe#HotWaxOmsConnection" evidenceFieldsJson='{"system":"HotWax","state":"statusId","stateLabels":{"ITEM_COMPLETED":"Completed","ITEM_APPROVED":"Approved","ITEM_CREATED":"Created","ITEM_CANCELLED":"Cancelled"},"facts":["facilityId","orderItemSeqId","omsOrderId"]}' enabled="Y"/>
```

### Modified in `data/SourceSystemConnectorSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnector|systemEnumId=OMS_ORDER_ITEMS|remoteId=HOTWAX_ORDERS_API`
- Element: `darpan.reconciliation.SourceSystemConnector`

```xml
<darpan.reconciliation.SourceSystemConnector systemEnumId="OMS_ORDER_ITEMS" extractServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrderItems" dateFromParameterName="windowStart" dateToParameterName="windowEnd" expectedSourceConfigType="HOTWAX_OMS_REST_ORDER_ITEMS" configParameterName="omsRestSourceConfigId" configEntityName="darpan.hotwax.HotWaxOmsRestSourceConfig" remoteId="HOTWAX_ORDERS_API" endpointLabel="Order lines for NetSuite (REST)" sendUrlTemplate="{baseUrl}/rest/s1/oms/orders" remoteSendServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrderItems" systemAliases="OMS_ORDER_ITEMS,HOTWAX_ORDER_ITEMS,DAR_SYS_OMS_ORDER_ITEMS" preserveWindowInstants="N" filterParameterName="sourceFilters" windowFieldName="orderDate" healthCheckServiceName="facade.HotWaxOmsFacadeServices.probe#HotWaxOmsConnection" evidenceFieldsJson='{"system":"HotWax","state":"statusId","stateLabels":{"ITEM_COMPLETED":"Completed","ITEM_APPROVED":"Approved","ITEM_CREATED":"Created","ITEM_CANCELLED":"Cancelled"},"facts":["facilityId","orderItemSeqId","omsOrderId"]}' enabled="Y"/>
```

### Modified in `data/SourceSystemConnectorSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnector|systemEnumId=OMS_ORDER_LINE_UNITS|remoteId=HOTWAX_ORDERS_API`
- Element: `darpan.reconciliation.SourceSystemConnector`

```xml
<darpan.reconciliation.SourceSystemConnector systemEnumId="OMS_ORDER_LINE_UNITS" extractServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrderLineUnits" dateFromParameterName="windowStart" dateToParameterName="windowEnd" expectedSourceConfigType="HOTWAX_OMS_REST_LINE_UNITS" configParameterName="omsRestSourceConfigId" configEntityName="darpan.hotwax.HotWaxOmsRestSourceConfig" remoteId="HOTWAX_ORDERS_API" endpointLabel="Order lines (REST)" sendUrlTemplate="{baseUrl}/rest/s1/oms/orders" remoteSendServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrderLineUnits" systemAliases="OMS_ORDER_LINE_UNITS,HOTWAX_ORDER_LINE_UNITS,DAR_SYS_OMS_LINE_UNITS" preserveWindowInstants="N" filterParameterName="sourceFilters" windowFieldName="orderDate" healthCheckServiceName="facade.HotWaxOmsFacadeServices.probe#HotWaxOmsConnection" evidenceFieldsJson='{"system":"HotWax","state":"statusId","stateLabels":{"ITEM_COMPLETED":"Completed","ITEM_APPROVED":"Approved","ITEM_CREATED":"Created","ITEM_CANCELLED":"Cancelled"},"facts":["facilityId","orderItemSeqId","omsOrderId"]}' enabled="Y"/>
```

### Modified in `data/SourceSystemConnectorSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnector|systemEnumId=OMS_RECON_ORDERS|remoteId=HOTWAX_ORDERS_API`
- Element: `darpan.reconciliation.SourceSystemConnector`

```xml
<darpan.reconciliation.SourceSystemConnector systemEnumId="OMS_RECON_ORDERS" extractServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsReconciliationOrders" dateFromParameterName="windowStart" dateToParameterName="windowEnd" expectedSourceConfigType="HOTWAX_OMS_REST_RECON" configParameterName="omsRestSourceConfigId" configEntityName="darpan.hotwax.HotWaxOmsRestSourceConfig" remoteId="HOTWAX_ORDERS_API" endpointLabel="Sales orders (REST)" sendUrlTemplate="{baseUrl}/rest/s1/oms/reconciliationOrders" remoteSendServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsReconciliationOrders" systemAliases="OMS_RECON_ORDERS,HOTWAX_RECON_ORDERS,DAR_SYS_OMS_RECON" preserveWindowInstants="N" keepFieldsParameterName="keepRecordFields" keepFieldsBase="orderId,orderName,externalId,grandTotal,orderDate,statusId" filterParameterName="sourceFilters" healthCheckServiceName="facade.HotWaxOmsFacadeServices.probe#HotWaxOmsConnection" enabled="Y"/>
```

### Modified in `data/SourceSystemConnectorSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnector|systemEnumId=OMS_RETURNS_GQL|remoteId=HOTWAX_ORDERS_API`
- Element: `darpan.reconciliation.SourceSystemConnector`

```xml
<darpan.reconciliation.SourceSystemConnector systemEnumId="OMS_RETURNS_GQL" extractServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlReturns" dateFromParameterName="windowStart" dateToParameterName="windowEnd" expectedSourceConfigType="HOTWAX_OMS_GQL_RETURNS" configParameterName="omsRestSourceConfigId" configEntityName="darpan.hotwax.HotWaxOmsRestSourceConfig" remoteId="HOTWAX_ORDERS_API" endpointLabel="Returns" sendUrlTemplate="{baseUrl}/rest/s1/graphql" remoteSendServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlReturns" systemAliases="OMS_RETURNS_GQL,HOTWAX_GQL_RETURNS,DAR_SYS_OMS_RETURNS_GQL" preserveWindowInstants="N" keepFieldsParameterName="keepRecordFields" keepFieldsBase="returnId,shopifyReturnId,externalId,orderExternalId,statusId,entryDate,returnTotal,currencyUomId,returnChannelEnumId" filterParameterName="sourceFilters" healthCheckServiceName="facade.HotWaxOmsFacadeServices.probe#HotWaxOmsConnection" orderStateLookupServiceName="reconciliation.HotWaxOmsExtractionServices.lookup#HotWaxOmsOrdersByExternalId" returnLinesLookupServiceName="reconciliation.HotWaxOmsExtractionServices.lookup#HotWaxOmsReturnLinesByOrderExternalId" lookupServiceName="reconciliation.HotWaxOmsExtractionServices.lookup#HotWaxOmsReturnsByExternalId" lookupIdsParameterName="externalIds" lookupMaxIds="10000" extractOptionsJson='[{"name":"includeReturnsWithoutShopifyRef","label":"Include returns with no Shopify reference","type":"boolean"},{"name":"onlyCancelledReturns","label":"Only cancelled returns","type":"boolean"}]' enabled="Y"/>
```

### Modified in `data/SourceSystemConnectorSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnector|systemEnumId=OMS_RETURNS|remoteId=HOTWAX_ORDERS_API`
- Element: `darpan.reconciliation.SourceSystemConnector`

```xml
<darpan.reconciliation.SourceSystemConnector systemEnumId="OMS_RETURNS" extractServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsReturns" dateFromParameterName="windowStart" dateToParameterName="windowEnd" expectedSourceConfigType="HOTWAX_OMS_REST_RETURNS" configParameterName="omsRestSourceConfigId" configEntityName="darpan.hotwax.HotWaxOmsRestSourceConfig" remoteId="HOTWAX_ORDERS_API" endpointLabel="Refunded returns (REST)" sendUrlTemplate="{baseUrl}/rest/s1/oms/reconciliationReturns" remoteSendServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsReturns" systemAliases="OMS_RETURNS,HOTWAX_RETURNS,DAR_SYS_OMS_RETURNS" preserveWindowInstants="N" keepFieldsParameterName="keepRecordFields" keepFieldsBase="returnId,shopifyReturnId,externalId,orderExternalId,statusId,entryDate,returnTotal,currencyUomId,returnChannelEnumId" filterParameterName="sourceFilters" healthCheckServiceName="facade.HotWaxOmsFacadeServices.probe#HotWaxOmsConnection" orderStateLookupServiceName="reconciliation.HotWaxOmsExtractionServices.lookup#HotWaxOmsOrdersByExternalId" returnLinesLookupServiceName="reconciliation.HotWaxOmsExtractionServices.lookup#HotWaxOmsReturnLinesByOrderExternalId" lookupServiceName="reconciliation.HotWaxOmsExtractionServices.lookup#HotWaxOmsReturnsByExternalId" lookupIdsParameterName="externalIds" lookupMaxIds="10000" enabled="Y"/>
```

### Modified in `data/SourceSystemConnectorSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnector|systemEnumId=OMS_TRANSFER_ORDERS|remoteId=HOTWAX_ORDERS_API`
- Element: `darpan.reconciliation.SourceSystemConnector`

```xml
<darpan.reconciliation.SourceSystemConnector systemEnumId="OMS_TRANSFER_ORDERS" extractServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsTransferOrders" dateFromParameterName="windowStart" dateToParameterName="windowEnd" expectedSourceConfigType="HOTWAX_OMS_REST_TRANSFER" configParameterName="omsRestSourceConfigId" configEntityName="darpan.hotwax.HotWaxOmsRestSourceConfig" remoteId="HOTWAX_ORDERS_API" endpointLabel="Transfer orders (REST)" sendUrlTemplate="{baseUrl}/rest/s1/oms/orders" remoteSendServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsTransferOrders" systemAliases="OMS_TRANSFER_ORDERS,HOTWAX_TRANSFER_ORDERS,DAR_SYS_OMS_TO" preserveWindowInstants="N" keepFieldsParameterName="keepRecordFields" keepFieldsBase="orderId,orderName,orderDate,statusId,originFacilityId,grandTotal,currencyUom" filterParameterName="sourceFilters" windowFieldName="orderDate" supportsStateExtract="Y" statusParameterName="orderStatusIds" healthCheckServiceName="facade.HotWaxOmsFacadeServices.probe#HotWaxOmsConnection" enabled="Y"/>
```

### Modified in `data/SourceSystemConnectorSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnector|systemEnumId=OMS|remoteId=HOTWAX_ORDERS_API`
- Element: `darpan.reconciliation.SourceSystemConnector`

```xml
<darpan.reconciliation.SourceSystemConnector systemEnumId="OMS" extractServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrders" dateFromParameterName="windowStart" dateToParameterName="windowEnd" expectedSourceConfigType="HOTWAX_OMS_REST" configParameterName="omsRestSourceConfigId" configEntityName="darpan.hotwax.HotWaxOmsRestSourceConfig" remoteId="HOTWAX_ORDERS_API" endpointLabel="Orders (REST)" sendUrlTemplate="{baseUrl}/rest/s1/oms/orders" remoteSendServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrders" systemAliases="OMS,HOTWAX,DAR_SYS_OMS" preserveWindowInstants="N" keepFieldsParameterName="keepRecordFields" keepFieldsBase="orderId,orderName,externalId,grandTotal,orderDate,statusId" filterParameterName="sourceFilters" pairLookupServiceName="reconciliation.HotWaxOmsExtractionServices.lookup#HotWaxOmsOrdersByExternalId" healthCheckServiceName="facade.HotWaxOmsFacadeServices.probe#HotWaxOmsConnection" enabled="Y"/>
```

### Modified in `data/TriSystemOrderRunsRuleSetData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=GORJANA_OMS_NS_LINE_SCOPE|ruleSetId=GORJANA_OMS_NS_LINE_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="GORJANA_OMS_NS_LINE_SCOPE" ruleSetId="GORJANA_OMS_NS_LINE_RS" objectType="ORDER_LINE_UNIT" scopeMode="COMPARE" description="Every item HotWax completed must be billed on its NetSuite sales order line, and every billed NetSuite line must be completed in HotWax. Keyed (OMS order id, OMS order item seq id)." conclusionLookupsJson='[{"name":"orderInNetsuite","subject":"FILE_2","idField":"internalId","systemEnumId":"NETSUITE_SUITEQL","sourceConfigId":"NS_SO_BACKORDER","keyField":"internalId"},{"name":"omsOrder","subject":"FILE_1","idField":"omsOrderId","systemEnumId":"OMS_GQL","sourceConfigId":"gorjana_prod","keyField":"orderId","parameters":{"keepRecordFields":["orderId","notes","statuses"]}}]' companyUserGroupId="GORJANA"/>
```

### Added in `data/TriSystemOrderRunsRuleSetData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=GORJANA_OMS_NS_CANCEL_SCOPE|systemEnumId=OMS_ORDER_ITEMS_GQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=gorjana_prod|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="GORJANA_OMS_NS_CANCEL_SCOPE" fileSide="FILE_1" systemEnumId="OMS_ORDER_ITEMS_GQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="omsOrderId" sourceConfigId="gorjana_prod" sourceConfigType="HOTWAX_OMS_GQL_ORDER_ITEMS" companyUserGroupId="GORJANA"/>
```

### Added in `data/TriSystemOrderRunsRuleSetData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=GORJANA_OMS_NS_LINE_SCOPE|systemEnumId=OMS_ORDER_ITEMS_GQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=gorjana_prod|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="GORJANA_OMS_NS_LINE_SCOPE" fileSide="FILE_1" systemEnumId="OMS_ORDER_ITEMS_GQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="omsOrderId" sourceConfigId="gorjana_prod" sourceConfigType="HOTWAX_OMS_GQL_ORDER_ITEMS" companyUserGroupId="GORJANA"/>
```

## Recommended operator review

- Confirm every candidate record truly needs to be loaded for the target environment.
- Keep final upgrade records reflected in the appropriate generic source data file, such as a type, security, mapping, job, or system-message seed file.
- Prefer keeping changes in the existing domain seed file unless the release needs a distinct generic setup bundle.
- State the final operator action in `release-notes.md` and `release-checklist.md`.
