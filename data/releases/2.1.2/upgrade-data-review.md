# Upgrade Data Review For Darpan 2.1.2

## Scope

- Backend compare range: `v2.1.1..HEAD`
- Data directory reviewed: `data`
- Generic source data files are the source of truth for release upgrade data.
- This report lists candidate seed/config records that were added or modified in generic source data files between the compared refs.
- Do not author records directly in `upgrade-data.xml`; add or update the appropriate `runtime/component/darpan/data/*.xml` file and regenerate.

## Decision

- Load target (`data/upgrade-data.xml`, mirrored here): **3 `darpan-seed` records**, rebuilt from the generator's
  candidates, not edited forward from 2.1.1's file.
  - 1 `darpan.migration.DarpanMigration` (`AUTOMATION_STREAKS`, seq 70) from `MigrationRegistrySeedData.xml`
    (DAR-BE-144): `run#PendingMigrations` then runs `migrate#AutomationStreaks` once on each instance.
  - 2 `darpan.reconciliation.SourceSystemConnectorField` rows from `SourceSystemConnectorFieldSeedData.xml`
    (DAR-BE-153): `SHOPIFY` `paymentTransactionIds`, `OMS_GQL` `paymentRefNums`. They label the fields on the rules
    board; the extractors emit the fields without them.
- Excluded by design (`darpan-tenant-config`, loaded per tenant): 10 candidates from `GorjanaMonthCloseCatalogData.xml`
  and `GorjanaMonthCloseReturnsChecksData.xml` (gorjana R21, DAR-BE-149) and 108 from `RailsAuditData.xml` (RAILS R7,
  DAR-CLIENT-005). 121 candidates in all.
- No record ordering hazard: the migration registry and both connectors (`SHOPIFY`, `OMS_GQL`) exist since 2.0/2.1.
- `data/releases/2.1.1/upgrade-data.xml` is byte-identical to `v2.1.1`'s `data/upgrade-data.xml`, so the archive step
  was a no-op.

## Candidate records

### Added in `data/GorjanaMonthCloseCatalogData.xml`

- Record: `darpan.audit.AuditStatement|statementId=GORJANA_R21|auditId=GORJANA_MONTH_CLOSE|companyUserGroupId=GORJANA|categoryEnumId=AUDC_REFUNDS_CREDITS|weightEnumId=AUDSW_DERIVED`
- Element: `darpan.audit.AuditStatement`

```xml
<darpan.audit.AuditStatement statementId="GORJANA_R21" auditId="GORJANA_MONTH_CLOSE" companyUserGroupId="GORJANA" statementCode="R21" categoryEnumId="AUDC_REFUNDS_CREDITS" weightEnumId="AUDSW_DERIVED" sequenceNum="21" statementText="No Happiness Guarantee credit memo is left open by tax counted twice on its POS Tax Variance line."/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.audit.AuditCheck|checkId=GORJANA_CHK_HG_TAX_VARIANCE|companyUserGroupId=GORJANA|savedRunId=GORJANA_HG_TAX_VARIANCE_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="GORJANA_CHK_HG_TAX_VARIANCE" companyUserGroupId="GORJANA" checkCode="hg-credits-open-by-pos-tax-variance" label="Happiness Guarantee credit memos left open by their POS Tax Variance line" savedRunId="GORJANA_HG_TAX_VARIANCE_RS" file1RecordType="creditMemo" graceDays="1" fixOwner="HotWax Support" fixWhereEnumId="NETSUITE" headlineTemplate="Credit memo {recordKey} is left open by tax counted twice" fixActionTemplate="Lower the POS Tax Variance line on credit memo {recordKey} by the amount left open." passesWhenTemplate="Credit memo {recordKey} is no longer left open by its POS Tax Variance line."/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=GORJANA_R21|checkId=GORJANA_CHK_HG_TAX_VARIANCE|companyUserGroupId=GORJANA`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="GORJANA_R21" checkId="GORJANA_CHK_HG_TAX_VARIANCE" companyUserGroupId="GORJANA" sequenceNum="1" diffTypes="FINDING" conclusionCodes="CONC_HG_TAX_COUNTED_TWICE"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=GORJANA_HG_TAX_VARIANCE_SCOPE|ruleSetId=GORJANA_HG_TAX_VARIANCE_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="GORJANA_HG_TAX_VARIANCE_SCOPE" ruleSetId="GORJANA_HG_TAX_VARIANCE_RS" objectType="CREDIT_MEMO" scopeMode="EVALUATE" description="No Happiness Guarantee credit memo is left open by its POS Tax Variance line. Reads every Happiness Guarantee credit memo so a corrected one is seen and closes." companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=GORJANA_HG_TAX_VARIANCE_SCOPE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="GORJANA_HG_TAX_VARIANCE_SCOPE" fileSide="FILE_1" sequenceNum="1" fieldExpression="tranId" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=GORJANA_HG_TAX_VARIANCE_SCOPE|systemEnumId=NETSUITE_SUITEQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=NS_HG_TAX_VARIANCE_MEMO|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="GORJANA_HG_TAX_VARIANCE_SCOPE" fileSide="FILE_1" systemEnumId="NETSUITE_SUITEQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="tranId" sourceConfigId="NS_HG_TAX_VARIANCE_MEMO" sourceConfigType="NETSUITE_SUITEQL" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionCondition|compareScopeId=GORJANA_HG_TAX_VARIANCE_SCOPE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionCondition`

```xml
<darpan.rule.RuleSetConclusionCondition compareScopeId="GORJANA_HG_TAX_VARIANCE_SCOPE" sequenceNum="10" conditionSeq="3" subject="FILE_1" presence="ANY" keyScope="FULL" fieldExpression="lineAfterFix" operator="GT" conditionValues="-0.01" checkLabel="The POS Tax Variance line covers the amount open" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_HG_TAX_VARIANCE_SCOPE|conclusionEnumId=CONC_HG_TAX_COUNTED_TWICE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_HG_TAX_VARIANCE_SCOPE" sequenceNum="10" conclusionEnumId="CONC_HG_TAX_COUNTED_TWICE" appliesToBucket="FINDING" fixActionTemplate="Lower the POS Tax Variance line on credit memo {recordKey} by the {unapplied} open, to {lineAfterFix}. The invoices are right; the OMS counted the store credit's tax twice." companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=GORJANA_HG_TAX_VARIANCE_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="GORJANA_HG_TAX_VARIANCE_RS" ruleSetName="Credit memos - Happiness Guarantee POS Tax Variance" description="No Happiness Guarantee credit memo is left open by its POS Tax Variance line. Reads every Happiness Guarantee credit memo so a corrected one is seen and closes." version="1.0" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_HG_TAX_COUNTED_TWICE|enumTypeId=DarpanConclusion|enumCode=CONC_HG_TAX_COUNTED_TWICE`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_HG_TAX_COUNTED_TWICE" enumTypeId="DarpanConclusion" enumCode="CONC_HG_TAX_COUNTED_TWICE" description="Tax counted twice"/>
```

### Added in `data/MigrationRegistrySeedData.xml`

- Record: `darpan.migration.DarpanMigration|migrationId=AUTOMATION_STREAKS`
- Element: `darpan.migration.DarpanMigration`

```xml
<darpan.migration.DarpanMigration migrationId="AUTOMATION_STREAKS" componentName="darpan" serviceName="reconciliation.ReconciliationNotificationServices.migrate#AutomationStreaks" sequenceNum="70" supportsDryRun="N" enabled="Y" description="Fold every automation's run history into its stored clean-day streak, so the                 clean-run Chat line reads the day count from the first message (DAR-BE-144).                 Idempotent — a fold reads only slots past the stored cursor."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditCheck|checkId=RAILS_CHK_LOOP_EXC_NOT_IN_NS_CANADA|companyUserGroupId=RAILS|savedRunId=RAILS_EXC_LOOP_CANADA_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="RAILS_CHK_LOOP_EXC_NOT_IN_NS_CANADA" companyUserGroupId="RAILS" checkCode="loop-exchanges-not-in-netsuite-canada" label="Rails Canada Loop exchange orders NetSuite does not have" savedRunId="RAILS_EXC_LOOP_CANADA_RS" file1RecordType="order" file2RecordType="salesOrder" graceDays="1" matchedBy="Shopify order id = custbody_rails_ext_order_id on the exchange sales order" fixOwner="HotWax Support" fixWhereEnumId="NETSUITE" headlineTemplate="Loop exchange order {recordKey} is not in NetSuite" fixActionTemplate="Sync Loop exchange order {recordKey} from HotWax to NetSuite." passesWhenTemplate="NetSuite has a sales order for Loop exchange order {recordKey}."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditCheck|checkId=RAILS_CHK_LOOP_EXC_NOT_IN_NS_EU|companyUserGroupId=RAILS|savedRunId=RAILS_EXC_LOOP_EU_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="RAILS_CHK_LOOP_EXC_NOT_IN_NS_EU" companyUserGroupId="RAILS" checkCode="loop-exchanges-not-in-netsuite-eu" label="Rails EU Loop exchange orders NetSuite does not have" savedRunId="RAILS_EXC_LOOP_EU_RS" file1RecordType="order" file2RecordType="salesOrder" graceDays="1" matchedBy="Shopify order id = custbody_rails_ext_order_id on the exchange sales order" fixOwner="HotWax Support" fixWhereEnumId="NETSUITE" headlineTemplate="Loop exchange order {recordKey} is not in NetSuite" fixActionTemplate="Sync Loop exchange order {recordKey} from HotWax to NetSuite." passesWhenTemplate="NetSuite has a sales order for Loop exchange order {recordKey}."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditCheck|checkId=RAILS_CHK_LOOP_EXC_NOT_IN_NS_OUTLET|companyUserGroupId=RAILS|savedRunId=RAILS_EXC_LOOP_OUTLET_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="RAILS_CHK_LOOP_EXC_NOT_IN_NS_OUTLET" companyUserGroupId="RAILS" checkCode="loop-exchanges-not-in-netsuite-outlet" label="Rails Outlet Loop exchange orders NetSuite does not have" savedRunId="RAILS_EXC_LOOP_OUTLET_RS" file1RecordType="order" file2RecordType="salesOrder" graceDays="1" matchedBy="Shopify order id = custbody_rails_ext_order_id on the exchange sales order" fixOwner="HotWax Support" fixWhereEnumId="NETSUITE" headlineTemplate="Loop exchange order {recordKey} is not in NetSuite" fixActionTemplate="Sync Loop exchange order {recordKey} from HotWax to NetSuite." passesWhenTemplate="NetSuite has a sales order for Loop exchange order {recordKey}."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditCheck|checkId=RAILS_CHK_LOOP_EXC_NOT_IN_NS_UK|companyUserGroupId=RAILS|savedRunId=RAILS_EXC_LOOP_UK_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="RAILS_CHK_LOOP_EXC_NOT_IN_NS_UK" companyUserGroupId="RAILS" checkCode="loop-exchanges-not-in-netsuite-uk" label="Rails UK Loop exchange orders NetSuite does not have" savedRunId="RAILS_EXC_LOOP_UK_RS" file1RecordType="order" file2RecordType="salesOrder" graceDays="1" matchedBy="Shopify order id = custbody_rails_ext_order_id on the exchange sales order" fixOwner="HotWax Support" fixWhereEnumId="NETSUITE" headlineTemplate="Loop exchange order {recordKey} is not in NetSuite" fixActionTemplate="Sync Loop exchange order {recordKey} from HotWax to NetSuite." passesWhenTemplate="NetSuite has a sales order for Loop exchange order {recordKey}."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditCheck|checkId=RAILS_CHK_LOOP_EXC_NOT_IN_NS_USA|companyUserGroupId=RAILS|savedRunId=RAILS_EXC_LOOP_USA_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="RAILS_CHK_LOOP_EXC_NOT_IN_NS_USA" companyUserGroupId="RAILS" checkCode="loop-exchanges-not-in-netsuite-usa" label="Rails US Loop exchange orders NetSuite does not have" savedRunId="RAILS_EXC_LOOP_USA_RS" file1RecordType="order" file2RecordType="salesOrder" graceDays="1" matchedBy="Shopify order id = custbody_rails_ext_order_id on the exchange sales order" fixOwner="HotWax Support" fixWhereEnumId="NETSUITE" headlineTemplate="Loop exchange order {recordKey} is not in NetSuite" fixActionTemplate="Sync Loop exchange order {recordKey} from HotWax to NetSuite." passesWhenTemplate="NetSuite has a sales order for Loop exchange order {recordKey}."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditCheck|checkId=RAILS_CHK_POS_EXC_NOT_IN_NS_CANADA|companyUserGroupId=RAILS|savedRunId=RAILS_EXC_POS_CANADA_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="RAILS_CHK_POS_EXC_NOT_IN_NS_CANADA" companyUserGroupId="RAILS" checkCode="store-exchanges-not-in-netsuite-canada" label="Rails Canada store exchanges NetSuite does not have" savedRunId="RAILS_EXC_POS_CANADA_RS" file1RecordType="return" file2RecordType="salesOrder" graceDays="1" matchedBy="Shopify order id (the original order) = custbody_rails_ext_order_id on the exchange sales order" fixOwner="HotWax Support" fixWhereEnumId="NETSUITE" headlineTemplate="The exchange on Shopify order {recordKey} is not in NetSuite" fixActionTemplate="Sync the HotWax exchange for Shopify order {recordKey} to NetSuite." passesWhenTemplate="NetSuite has the exchange sales order for Shopify order {recordKey}."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditCheck|checkId=RAILS_CHK_POS_EXC_NOT_IN_NS_EU|companyUserGroupId=RAILS|savedRunId=RAILS_EXC_POS_EU_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="RAILS_CHK_POS_EXC_NOT_IN_NS_EU" companyUserGroupId="RAILS" checkCode="store-exchanges-not-in-netsuite-eu" label="Rails EU store exchanges NetSuite does not have" savedRunId="RAILS_EXC_POS_EU_RS" file1RecordType="return" file2RecordType="salesOrder" graceDays="1" matchedBy="Shopify order id (the original order) = custbody_rails_ext_order_id on the exchange sales order" fixOwner="HotWax Support" fixWhereEnumId="NETSUITE" headlineTemplate="The exchange on Shopify order {recordKey} is not in NetSuite" fixActionTemplate="Sync the HotWax exchange for Shopify order {recordKey} to NetSuite." passesWhenTemplate="NetSuite has the exchange sales order for Shopify order {recordKey}."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditCheck|checkId=RAILS_CHK_POS_EXC_NOT_IN_NS_OUTLET|companyUserGroupId=RAILS|savedRunId=RAILS_EXC_POS_OUTLET_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="RAILS_CHK_POS_EXC_NOT_IN_NS_OUTLET" companyUserGroupId="RAILS" checkCode="store-exchanges-not-in-netsuite-outlet" label="Rails Outlet store exchanges NetSuite does not have" savedRunId="RAILS_EXC_POS_OUTLET_RS" file1RecordType="return" file2RecordType="salesOrder" graceDays="1" matchedBy="Shopify order id (the original order) = custbody_rails_ext_order_id on the exchange sales order" fixOwner="HotWax Support" fixWhereEnumId="NETSUITE" headlineTemplate="The exchange on Shopify order {recordKey} is not in NetSuite" fixActionTemplate="Sync the HotWax exchange for Shopify order {recordKey} to NetSuite." passesWhenTemplate="NetSuite has the exchange sales order for Shopify order {recordKey}."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditCheck|checkId=RAILS_CHK_POS_EXC_NOT_IN_NS_UK|companyUserGroupId=RAILS|savedRunId=RAILS_EXC_POS_UK_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="RAILS_CHK_POS_EXC_NOT_IN_NS_UK" companyUserGroupId="RAILS" checkCode="store-exchanges-not-in-netsuite-uk" label="Rails UK store exchanges NetSuite does not have" savedRunId="RAILS_EXC_POS_UK_RS" file1RecordType="return" file2RecordType="salesOrder" graceDays="1" matchedBy="Shopify order id (the original order) = custbody_rails_ext_order_id on the exchange sales order" fixOwner="HotWax Support" fixWhereEnumId="NETSUITE" headlineTemplate="The exchange on Shopify order {recordKey} is not in NetSuite" fixActionTemplate="Sync the HotWax exchange for Shopify order {recordKey} to NetSuite." passesWhenTemplate="NetSuite has the exchange sales order for Shopify order {recordKey}."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditCheck|checkId=RAILS_CHK_POS_EXC_NOT_IN_NS_USA|companyUserGroupId=RAILS|savedRunId=RAILS_EXC_POS_USA_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="RAILS_CHK_POS_EXC_NOT_IN_NS_USA" companyUserGroupId="RAILS" checkCode="store-exchanges-not-in-netsuite-usa" label="Rails US store exchanges NetSuite does not have" savedRunId="RAILS_EXC_POS_USA_RS" file1RecordType="return" file2RecordType="salesOrder" graceDays="1" matchedBy="Shopify order id (the original order) = custbody_rails_ext_order_id on the exchange sales order" fixOwner="HotWax Support" fixWhereEnumId="NETSUITE" headlineTemplate="The exchange on Shopify order {recordKey} is not in NetSuite" fixActionTemplate="Sync the HotWax exchange for Shopify order {recordKey} to NetSuite." passesWhenTemplate="NetSuite has the exchange sales order for Shopify order {recordKey}."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=RAILS_R7|checkId=RAILS_CHK_LOOP_EXC_NOT_IN_NS_CANADA|companyUserGroupId=RAILS`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="RAILS_R7" checkId="RAILS_CHK_LOOP_EXC_NOT_IN_NS_CANADA" companyUserGroupId="RAILS" sequenceNum="3" diffTypes="MISSING_IN_FILE_2"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=RAILS_R7|checkId=RAILS_CHK_LOOP_EXC_NOT_IN_NS_EU|companyUserGroupId=RAILS`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="RAILS_R7" checkId="RAILS_CHK_LOOP_EXC_NOT_IN_NS_EU" companyUserGroupId="RAILS" sequenceNum="5" diffTypes="MISSING_IN_FILE_2"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=RAILS_R7|checkId=RAILS_CHK_LOOP_EXC_NOT_IN_NS_OUTLET|companyUserGroupId=RAILS`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="RAILS_R7" checkId="RAILS_CHK_LOOP_EXC_NOT_IN_NS_OUTLET" companyUserGroupId="RAILS" sequenceNum="9" diffTypes="MISSING_IN_FILE_2"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=RAILS_R7|checkId=RAILS_CHK_LOOP_EXC_NOT_IN_NS_UK|companyUserGroupId=RAILS`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="RAILS_R7" checkId="RAILS_CHK_LOOP_EXC_NOT_IN_NS_UK" companyUserGroupId="RAILS" sequenceNum="7" diffTypes="MISSING_IN_FILE_2"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=RAILS_R7|checkId=RAILS_CHK_LOOP_EXC_NOT_IN_NS_USA|companyUserGroupId=RAILS`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="RAILS_R7" checkId="RAILS_CHK_LOOP_EXC_NOT_IN_NS_USA" companyUserGroupId="RAILS" sequenceNum="1" diffTypes="MISSING_IN_FILE_2"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=RAILS_R7|checkId=RAILS_CHK_POS_EXC_NOT_IN_NS_CANADA|companyUserGroupId=RAILS`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="RAILS_R7" checkId="RAILS_CHK_POS_EXC_NOT_IN_NS_CANADA" companyUserGroupId="RAILS" sequenceNum="4" diffTypes="MISSING_IN_FILE_2"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=RAILS_R7|checkId=RAILS_CHK_POS_EXC_NOT_IN_NS_EU|companyUserGroupId=RAILS`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="RAILS_R7" checkId="RAILS_CHK_POS_EXC_NOT_IN_NS_EU" companyUserGroupId="RAILS" sequenceNum="6" diffTypes="MISSING_IN_FILE_2"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=RAILS_R7|checkId=RAILS_CHK_POS_EXC_NOT_IN_NS_OUTLET|companyUserGroupId=RAILS`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="RAILS_R7" checkId="RAILS_CHK_POS_EXC_NOT_IN_NS_OUTLET" companyUserGroupId="RAILS" sequenceNum="10" diffTypes="MISSING_IN_FILE_2"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=RAILS_R7|checkId=RAILS_CHK_POS_EXC_NOT_IN_NS_UK|companyUserGroupId=RAILS`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="RAILS_R7" checkId="RAILS_CHK_POS_EXC_NOT_IN_NS_UK" companyUserGroupId="RAILS" sequenceNum="8" diffTypes="MISSING_IN_FILE_2"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=RAILS_R7|checkId=RAILS_CHK_POS_EXC_NOT_IN_NS_USA|companyUserGroupId=RAILS`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="RAILS_R7" checkId="RAILS_CHK_POS_EXC_NOT_IN_NS_USA" companyUserGroupId="RAILS" sequenceNum="2" diffTypes="MISSING_IN_FILE_2"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatement|statementId=RAILS_R7|auditId=RAILS_MONTH_CLOSE|companyUserGroupId=RAILS|categoryEnumId=AUDC_EXCHANGES_WARRANTY|weightEnumId=AUDSW_FIRM`
- Element: `darpan.audit.AuditStatement`

```xml
<darpan.audit.AuditStatement statementId="RAILS_R7" auditId="RAILS_MONTH_CLOSE" companyUserGroupId="RAILS" statementCode="R7" categoryEnumId="AUDC_EXCHANGES_WARRANTY" weightEnumId="AUDSW_FIRM" sequenceNum="7" statementText="Every exchange Shopify makes is in NetSuite."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=RAILS_EXC_LOOP_CANADA_SCOPE|ruleSetId=RAILS_EXC_LOOP_CANADA_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="RAILS_EXC_LOOP_CANADA_SCOPE" ruleSetId="RAILS_EXC_LOOP_CANADA_RS" objectType="ORDER" scopeMode="COMPARE" description="Every Rails Canada Loop exchange order in Shopify (named EXC-) against the store's NetSuite exchange sales orders." companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=RAILS_EXC_LOOP_EU_SCOPE|ruleSetId=RAILS_EXC_LOOP_EU_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="RAILS_EXC_LOOP_EU_SCOPE" ruleSetId="RAILS_EXC_LOOP_EU_RS" objectType="ORDER" scopeMode="COMPARE" description="Every Rails EU Loop exchange order in Shopify (named EXC-) against the store's NetSuite exchange sales orders." companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=RAILS_EXC_LOOP_OUTLET_SCOPE|ruleSetId=RAILS_EXC_LOOP_OUTLET_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="RAILS_EXC_LOOP_OUTLET_SCOPE" ruleSetId="RAILS_EXC_LOOP_OUTLET_RS" objectType="ORDER" scopeMode="COMPARE" description="Every Rails Outlet Loop exchange order in Shopify (named EXC-) against the store's NetSuite exchange sales orders." companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=RAILS_EXC_LOOP_UK_SCOPE|ruleSetId=RAILS_EXC_LOOP_UK_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="RAILS_EXC_LOOP_UK_SCOPE" ruleSetId="RAILS_EXC_LOOP_UK_RS" objectType="ORDER" scopeMode="COMPARE" description="Every Rails UK Loop exchange order in Shopify (named EXC-) against the store's NetSuite exchange sales orders." companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=RAILS_EXC_LOOP_USA_SCOPE|ruleSetId=RAILS_EXC_LOOP_USA_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="RAILS_EXC_LOOP_USA_SCOPE" ruleSetId="RAILS_EXC_LOOP_USA_RS" objectType="ORDER" scopeMode="COMPARE" description="Every Rails US Loop exchange order in Shopify (named EXC-) against the store's NetSuite exchange sales orders." companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=RAILS_EXC_POS_CANADA_SCOPE|ruleSetId=RAILS_EXC_POS_CANADA_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="RAILS_EXC_POS_CANADA_SCOPE" ruleSetId="RAILS_EXC_POS_CANADA_RS" objectType="RETURN" scopeMode="COMPARE" description="Every Rails Canada Shopify order with a completed return that has exchange items (a HotWax store exchange) against the store's NetSuite exchange sales orders." companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=RAILS_EXC_POS_EU_SCOPE|ruleSetId=RAILS_EXC_POS_EU_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="RAILS_EXC_POS_EU_SCOPE" ruleSetId="RAILS_EXC_POS_EU_RS" objectType="RETURN" scopeMode="COMPARE" description="Every Rails EU Shopify order with a completed return that has exchange items (a HotWax store exchange) against the store's NetSuite exchange sales orders." companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=RAILS_EXC_POS_OUTLET_SCOPE|ruleSetId=RAILS_EXC_POS_OUTLET_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="RAILS_EXC_POS_OUTLET_SCOPE" ruleSetId="RAILS_EXC_POS_OUTLET_RS" objectType="RETURN" scopeMode="COMPARE" description="Every Rails Outlet Shopify order with a completed return that has exchange items (a HotWax store exchange) against the store's NetSuite exchange sales orders." companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=RAILS_EXC_POS_UK_SCOPE|ruleSetId=RAILS_EXC_POS_UK_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="RAILS_EXC_POS_UK_SCOPE" ruleSetId="RAILS_EXC_POS_UK_RS" objectType="RETURN" scopeMode="COMPARE" description="Every Rails UK Shopify order with a completed return that has exchange items (a HotWax store exchange) against the store's NetSuite exchange sales orders." companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=RAILS_EXC_POS_USA_SCOPE|ruleSetId=RAILS_EXC_POS_USA_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="RAILS_EXC_POS_USA_SCOPE" ruleSetId="RAILS_EXC_POS_USA_RS" objectType="RETURN" scopeMode="COMPARE" description="Every Rails US Shopify order with a completed return that has exchange items (a HotWax store exchange) against the store's NetSuite exchange sales orders." companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceFilter|compareScopeId=RAILS_EXC_LOOP_CANADA_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceFilter`

```xml
<darpan.rule.RuleSetCompareSourceFilter compareScopeId="RAILS_EXC_LOOP_CANADA_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="shopifyStore" operator="INCLUDE_IN" filterValues="5" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceFilter|compareScopeId=RAILS_EXC_LOOP_EU_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceFilter`

```xml
<darpan.rule.RuleSetCompareSourceFilter compareScopeId="RAILS_EXC_LOOP_EU_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="shopifyStore" operator="INCLUDE_IN" filterValues="2" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceFilter|compareScopeId=RAILS_EXC_LOOP_OUTLET_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceFilter`

```xml
<darpan.rule.RuleSetCompareSourceFilter compareScopeId="RAILS_EXC_LOOP_OUTLET_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="shopifyStore" operator="INCLUDE_IN" filterValues="4" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceFilter|compareScopeId=RAILS_EXC_LOOP_UK_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceFilter`

```xml
<darpan.rule.RuleSetCompareSourceFilter compareScopeId="RAILS_EXC_LOOP_UK_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="shopifyStore" operator="INCLUDE_IN" filterValues="3" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceFilter|compareScopeId=RAILS_EXC_LOOP_USA_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceFilter`

```xml
<darpan.rule.RuleSetCompareSourceFilter compareScopeId="RAILS_EXC_LOOP_USA_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="shopifyStore" operator="INCLUDE_IN" filterValues="1" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceFilter|compareScopeId=RAILS_EXC_POS_CANADA_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceFilter`

```xml
<darpan.rule.RuleSetCompareSourceFilter compareScopeId="RAILS_EXC_POS_CANADA_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="shopifyStore" operator="INCLUDE_IN" filterValues="5" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceFilter|compareScopeId=RAILS_EXC_POS_EU_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceFilter`

```xml
<darpan.rule.RuleSetCompareSourceFilter compareScopeId="RAILS_EXC_POS_EU_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="shopifyStore" operator="INCLUDE_IN" filterValues="2" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceFilter|compareScopeId=RAILS_EXC_POS_OUTLET_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceFilter`

```xml
<darpan.rule.RuleSetCompareSourceFilter compareScopeId="RAILS_EXC_POS_OUTLET_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="shopifyStore" operator="INCLUDE_IN" filterValues="4" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceFilter|compareScopeId=RAILS_EXC_POS_UK_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceFilter`

```xml
<darpan.rule.RuleSetCompareSourceFilter compareScopeId="RAILS_EXC_POS_UK_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="shopifyStore" operator="INCLUDE_IN" filterValues="3" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceFilter|compareScopeId=RAILS_EXC_POS_USA_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceFilter`

```xml
<darpan.rule.RuleSetCompareSourceFilter compareScopeId="RAILS_EXC_POS_USA_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="shopifyStore" operator="INCLUDE_IN" filterValues="1" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=RAILS_EXC_LOOP_CANADA_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="RAILS_EXC_LOOP_CANADA_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="shopifyOrderId" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=RAILS_EXC_LOOP_EU_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="RAILS_EXC_LOOP_EU_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="shopifyOrderId" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=RAILS_EXC_LOOP_OUTLET_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="RAILS_EXC_LOOP_OUTLET_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="shopifyOrderId" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=RAILS_EXC_LOOP_UK_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="RAILS_EXC_LOOP_UK_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="shopifyOrderId" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=RAILS_EXC_LOOP_USA_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="RAILS_EXC_LOOP_USA_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="shopifyOrderId" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=RAILS_EXC_POS_CANADA_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="RAILS_EXC_POS_CANADA_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="shopifyOrderId" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=RAILS_EXC_POS_EU_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="RAILS_EXC_POS_EU_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="shopifyOrderId" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=RAILS_EXC_POS_OUTLET_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="RAILS_EXC_POS_OUTLET_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="shopifyOrderId" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=RAILS_EXC_POS_UK_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="RAILS_EXC_POS_UK_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="shopifyOrderId" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=RAILS_EXC_POS_USA_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="RAILS_EXC_POS_USA_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="shopifyOrderId" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_EXC_LOOP_CANADA_SCOPE|systemEnumId=NETSUITE_SUITEQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=RAILS_NS_EXCHANGE_ORDERS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_EXC_LOOP_CANADA_SCOPE" fileSide="FILE_2" systemEnumId="NETSUITE_SUITEQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="shopifyOrderId" sourceConfigId="RAILS_NS_EXCHANGE_ORDERS" sourceConfigType="NETSUITE_SUITEQL" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_EXC_LOOP_CANADA_SCOPE|systemEnumId=SHOPIFY|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=100000|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_EXC_LOOP_CANADA_SCOPE" fileSide="FILE_1" systemEnumId="SHOPIFY" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="legacyResourceId" sourceConfigId="100000" sourceConfigType="SHOPIFY_AUTH" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_EXC_LOOP_EU_SCOPE|systemEnumId=NETSUITE_SUITEQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=RAILS_NS_EXCHANGE_ORDERS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_EXC_LOOP_EU_SCOPE" fileSide="FILE_2" systemEnumId="NETSUITE_SUITEQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="shopifyOrderId" sourceConfigId="RAILS_NS_EXCHANGE_ORDERS" sourceConfigType="NETSUITE_SUITEQL" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_EXC_LOOP_EU_SCOPE|systemEnumId=SHOPIFY|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=100001|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_EXC_LOOP_EU_SCOPE" fileSide="FILE_1" systemEnumId="SHOPIFY" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="legacyResourceId" sourceConfigId="100001" sourceConfigType="SHOPIFY_AUTH" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_EXC_LOOP_OUTLET_SCOPE|systemEnumId=NETSUITE_SUITEQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=RAILS_NS_EXCHANGE_ORDERS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_EXC_LOOP_OUTLET_SCOPE" fileSide="FILE_2" systemEnumId="NETSUITE_SUITEQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="shopifyOrderId" sourceConfigId="RAILS_NS_EXCHANGE_ORDERS" sourceConfigType="NETSUITE_SUITEQL" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_EXC_LOOP_OUTLET_SCOPE|systemEnumId=SHOPIFY|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=100002|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_EXC_LOOP_OUTLET_SCOPE" fileSide="FILE_1" systemEnumId="SHOPIFY" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="legacyResourceId" sourceConfigId="100002" sourceConfigType="SHOPIFY_AUTH" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_EXC_LOOP_UK_SCOPE|systemEnumId=NETSUITE_SUITEQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=RAILS_NS_EXCHANGE_ORDERS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_EXC_LOOP_UK_SCOPE" fileSide="FILE_2" systemEnumId="NETSUITE_SUITEQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="shopifyOrderId" sourceConfigId="RAILS_NS_EXCHANGE_ORDERS" sourceConfigType="NETSUITE_SUITEQL" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_EXC_LOOP_UK_SCOPE|systemEnumId=SHOPIFY|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=100004|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_EXC_LOOP_UK_SCOPE" fileSide="FILE_1" systemEnumId="SHOPIFY" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="legacyResourceId" sourceConfigId="100004" sourceConfigType="SHOPIFY_AUTH" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_EXC_LOOP_USA_SCOPE|systemEnumId=NETSUITE_SUITEQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=RAILS_NS_EXCHANGE_ORDERS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_EXC_LOOP_USA_SCOPE" fileSide="FILE_2" systemEnumId="NETSUITE_SUITEQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="shopifyOrderId" sourceConfigId="RAILS_NS_EXCHANGE_ORDERS" sourceConfigType="NETSUITE_SUITEQL" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_EXC_LOOP_USA_SCOPE|systemEnumId=SHOPIFY|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=100003|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_EXC_LOOP_USA_SCOPE" fileSide="FILE_1" systemEnumId="SHOPIFY" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="legacyResourceId" sourceConfigId="100003" sourceConfigType="SHOPIFY_AUTH" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_EXC_POS_CANADA_SCOPE|systemEnumId=NETSUITE_SUITEQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=RAILS_NS_EXCHANGE_ORDERS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_EXC_POS_CANADA_SCOPE" fileSide="FILE_2" systemEnumId="NETSUITE_SUITEQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="shopifyOrderId" sourceConfigId="RAILS_NS_EXCHANGE_ORDERS" sourceConfigType="NETSUITE_SUITEQL" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_EXC_POS_CANADA_SCOPE|systemEnumId=SHOPIFY_RETURN_REFS|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=100000|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_EXC_POS_CANADA_SCOPE" fileSide="FILE_1" systemEnumId="SHOPIFY_RETURN_REFS" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="orderId" sourceConfigId="100000" sourceConfigType="SHOPIFY_RETURN_REFS_API" extractParametersJson='{"completedReturns":"exchanged"}' companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_EXC_POS_EU_SCOPE|systemEnumId=NETSUITE_SUITEQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=RAILS_NS_EXCHANGE_ORDERS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_EXC_POS_EU_SCOPE" fileSide="FILE_2" systemEnumId="NETSUITE_SUITEQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="shopifyOrderId" sourceConfigId="RAILS_NS_EXCHANGE_ORDERS" sourceConfigType="NETSUITE_SUITEQL" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_EXC_POS_EU_SCOPE|systemEnumId=SHOPIFY_RETURN_REFS|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=100001|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_EXC_POS_EU_SCOPE" fileSide="FILE_1" systemEnumId="SHOPIFY_RETURN_REFS" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="orderId" sourceConfigId="100001" sourceConfigType="SHOPIFY_RETURN_REFS_API" extractParametersJson='{"completedReturns":"exchanged"}' companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_EXC_POS_OUTLET_SCOPE|systemEnumId=NETSUITE_SUITEQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=RAILS_NS_EXCHANGE_ORDERS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_EXC_POS_OUTLET_SCOPE" fileSide="FILE_2" systemEnumId="NETSUITE_SUITEQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="shopifyOrderId" sourceConfigId="RAILS_NS_EXCHANGE_ORDERS" sourceConfigType="NETSUITE_SUITEQL" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_EXC_POS_OUTLET_SCOPE|systemEnumId=SHOPIFY_RETURN_REFS|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=100002|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_EXC_POS_OUTLET_SCOPE" fileSide="FILE_1" systemEnumId="SHOPIFY_RETURN_REFS" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="orderId" sourceConfigId="100002" sourceConfigType="SHOPIFY_RETURN_REFS_API" extractParametersJson='{"completedReturns":"exchanged"}' companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_EXC_POS_UK_SCOPE|systemEnumId=NETSUITE_SUITEQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=RAILS_NS_EXCHANGE_ORDERS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_EXC_POS_UK_SCOPE" fileSide="FILE_2" systemEnumId="NETSUITE_SUITEQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="shopifyOrderId" sourceConfigId="RAILS_NS_EXCHANGE_ORDERS" sourceConfigType="NETSUITE_SUITEQL" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_EXC_POS_UK_SCOPE|systemEnumId=SHOPIFY_RETURN_REFS|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=100004|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_EXC_POS_UK_SCOPE" fileSide="FILE_1" systemEnumId="SHOPIFY_RETURN_REFS" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="orderId" sourceConfigId="100004" sourceConfigType="SHOPIFY_RETURN_REFS_API" extractParametersJson='{"completedReturns":"exchanged"}' companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_EXC_POS_USA_SCOPE|systemEnumId=NETSUITE_SUITEQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=RAILS_NS_EXCHANGE_ORDERS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_EXC_POS_USA_SCOPE" fileSide="FILE_2" systemEnumId="NETSUITE_SUITEQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="shopifyOrderId" sourceConfigId="RAILS_NS_EXCHANGE_ORDERS" sourceConfigType="NETSUITE_SUITEQL" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_EXC_POS_USA_SCOPE|systemEnumId=SHOPIFY_RETURN_REFS|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=100003|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_EXC_POS_USA_SCOPE" fileSide="FILE_1" systemEnumId="SHOPIFY_RETURN_REFS" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="orderId" sourceConfigId="100003" sourceConfigType="SHOPIFY_RETURN_REFS_API" extractParametersJson='{"completedReturns":"exchanged"}' companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionCondition|compareScopeId=RAILS_EXC_LOOP_CANADA_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionCondition`

```xml
<darpan.rule.RuleSetConclusionCondition compareScopeId="RAILS_EXC_LOOP_CANADA_SCOPE" sequenceNum="10" conditionSeq="1" subject="FILE_2" presence="ABSENT" keyScope="FULL" checkLabel="NetSuite has no sales order for the exchange" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionCondition|compareScopeId=RAILS_EXC_LOOP_EU_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionCondition`

```xml
<darpan.rule.RuleSetConclusionCondition compareScopeId="RAILS_EXC_LOOP_EU_SCOPE" sequenceNum="10" conditionSeq="1" subject="FILE_2" presence="ABSENT" keyScope="FULL" checkLabel="NetSuite has no sales order for the exchange" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionCondition|compareScopeId=RAILS_EXC_LOOP_OUTLET_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionCondition`

```xml
<darpan.rule.RuleSetConclusionCondition compareScopeId="RAILS_EXC_LOOP_OUTLET_SCOPE" sequenceNum="10" conditionSeq="1" subject="FILE_2" presence="ABSENT" keyScope="FULL" checkLabel="NetSuite has no sales order for the exchange" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionCondition|compareScopeId=RAILS_EXC_LOOP_UK_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionCondition`

```xml
<darpan.rule.RuleSetConclusionCondition compareScopeId="RAILS_EXC_LOOP_UK_SCOPE" sequenceNum="10" conditionSeq="1" subject="FILE_2" presence="ABSENT" keyScope="FULL" checkLabel="NetSuite has no sales order for the exchange" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionCondition|compareScopeId=RAILS_EXC_LOOP_USA_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionCondition`

```xml
<darpan.rule.RuleSetConclusionCondition compareScopeId="RAILS_EXC_LOOP_USA_SCOPE" sequenceNum="10" conditionSeq="1" subject="FILE_2" presence="ABSENT" keyScope="FULL" checkLabel="NetSuite has no sales order for the exchange" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionCondition|compareScopeId=RAILS_EXC_POS_CANADA_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionCondition`

```xml
<darpan.rule.RuleSetConclusionCondition compareScopeId="RAILS_EXC_POS_CANADA_SCOPE" sequenceNum="20" conditionSeq="1" subject="FILE_2" presence="ABSENT" keyScope="FULL" checkLabel="NetSuite has no sales order for the exchange" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionCondition|compareScopeId=RAILS_EXC_POS_EU_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionCondition`

```xml
<darpan.rule.RuleSetConclusionCondition compareScopeId="RAILS_EXC_POS_EU_SCOPE" sequenceNum="20" conditionSeq="1" subject="FILE_2" presence="ABSENT" keyScope="FULL" checkLabel="NetSuite has no sales order for the exchange" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionCondition|compareScopeId=RAILS_EXC_POS_OUTLET_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionCondition`

```xml
<darpan.rule.RuleSetConclusionCondition compareScopeId="RAILS_EXC_POS_OUTLET_SCOPE" sequenceNum="20" conditionSeq="1" subject="FILE_2" presence="ABSENT" keyScope="FULL" checkLabel="NetSuite has no sales order for the exchange" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionCondition|compareScopeId=RAILS_EXC_POS_UK_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionCondition`

```xml
<darpan.rule.RuleSetConclusionCondition compareScopeId="RAILS_EXC_POS_UK_SCOPE" sequenceNum="20" conditionSeq="1" subject="FILE_2" presence="ABSENT" keyScope="FULL" checkLabel="NetSuite has no sales order for the exchange" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionCondition|compareScopeId=RAILS_EXC_POS_USA_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionCondition`

```xml
<darpan.rule.RuleSetConclusionCondition compareScopeId="RAILS_EXC_POS_USA_SCOPE" sequenceNum="20" conditionSeq="1" subject="FILE_2" presence="ABSENT" keyScope="FULL" checkLabel="NetSuite has no sales order for the exchange" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_EXC_LOOP_CANADA_SCOPE|conclusionEnumId=CONC_EXCHANGE_NOT_IN_NS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_EXC_LOOP_CANADA_SCOPE" sequenceNum="10" fixActionTemplate="Sync Loop exchange order {recordKey} from HotWax to NetSuite." conclusionEnumId="CONC_EXCHANGE_NOT_IN_NS" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_EXC_LOOP_EU_SCOPE|conclusionEnumId=CONC_EXCHANGE_NOT_IN_NS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_EXC_LOOP_EU_SCOPE" sequenceNum="10" fixActionTemplate="Sync Loop exchange order {recordKey} from HotWax to NetSuite." conclusionEnumId="CONC_EXCHANGE_NOT_IN_NS" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_EXC_LOOP_OUTLET_SCOPE|conclusionEnumId=CONC_EXCHANGE_NOT_IN_NS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_EXC_LOOP_OUTLET_SCOPE" sequenceNum="10" fixActionTemplate="Sync Loop exchange order {recordKey} from HotWax to NetSuite." conclusionEnumId="CONC_EXCHANGE_NOT_IN_NS" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_EXC_LOOP_UK_SCOPE|conclusionEnumId=CONC_EXCHANGE_NOT_IN_NS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_EXC_LOOP_UK_SCOPE" sequenceNum="10" fixActionTemplate="Sync Loop exchange order {recordKey} from HotWax to NetSuite." conclusionEnumId="CONC_EXCHANGE_NOT_IN_NS" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_EXC_LOOP_USA_SCOPE|conclusionEnumId=CONC_EXCHANGE_NOT_IN_NS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_EXC_LOOP_USA_SCOPE" sequenceNum="10" fixActionTemplate="Sync Loop exchange order {recordKey} from HotWax to NetSuite." conclusionEnumId="CONC_EXCHANGE_NOT_IN_NS" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_EXC_POS_CANADA_SCOPE|conclusionEnumId=CONC_EXCHANGE_NOT_IN_NS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_EXC_POS_CANADA_SCOPE" sequenceNum="20" fixActionTemplate="Sync the HotWax exchange for Shopify order {recordKey} to NetSuite." conclusionEnumId="CONC_EXCHANGE_NOT_IN_NS" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_EXC_POS_CANADA_SCOPE|conclusionEnumId=CONC_EXCHANGE_ORIGINAL_UNFULFILLED|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_EXC_POS_CANADA_SCOPE" sequenceNum="10" fixActionTemplate="Ship the original order {recordKey}: its exchange reaches NetSuite only once the original is fulfilled." conclusionEnumId="CONC_EXCHANGE_ORIGINAL_UNFULFILLED" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_EXC_POS_EU_SCOPE|conclusionEnumId=CONC_EXCHANGE_NOT_IN_NS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_EXC_POS_EU_SCOPE" sequenceNum="20" fixActionTemplate="Sync the HotWax exchange for Shopify order {recordKey} to NetSuite." conclusionEnumId="CONC_EXCHANGE_NOT_IN_NS" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_EXC_POS_EU_SCOPE|conclusionEnumId=CONC_EXCHANGE_ORIGINAL_UNFULFILLED|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_EXC_POS_EU_SCOPE" sequenceNum="10" fixActionTemplate="Ship the original order {recordKey}: its exchange reaches NetSuite only once the original is fulfilled." conclusionEnumId="CONC_EXCHANGE_ORIGINAL_UNFULFILLED" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_EXC_POS_OUTLET_SCOPE|conclusionEnumId=CONC_EXCHANGE_NOT_IN_NS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_EXC_POS_OUTLET_SCOPE" sequenceNum="20" fixActionTemplate="Sync the HotWax exchange for Shopify order {recordKey} to NetSuite." conclusionEnumId="CONC_EXCHANGE_NOT_IN_NS" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_EXC_POS_OUTLET_SCOPE|conclusionEnumId=CONC_EXCHANGE_ORIGINAL_UNFULFILLED|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_EXC_POS_OUTLET_SCOPE" sequenceNum="10" fixActionTemplate="Ship the original order {recordKey}: its exchange reaches NetSuite only once the original is fulfilled." conclusionEnumId="CONC_EXCHANGE_ORIGINAL_UNFULFILLED" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_EXC_POS_UK_SCOPE|conclusionEnumId=CONC_EXCHANGE_NOT_IN_NS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_EXC_POS_UK_SCOPE" sequenceNum="20" fixActionTemplate="Sync the HotWax exchange for Shopify order {recordKey} to NetSuite." conclusionEnumId="CONC_EXCHANGE_NOT_IN_NS" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_EXC_POS_UK_SCOPE|conclusionEnumId=CONC_EXCHANGE_ORIGINAL_UNFULFILLED|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_EXC_POS_UK_SCOPE" sequenceNum="10" fixActionTemplate="Ship the original order {recordKey}: its exchange reaches NetSuite only once the original is fulfilled." conclusionEnumId="CONC_EXCHANGE_ORIGINAL_UNFULFILLED" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_EXC_POS_USA_SCOPE|conclusionEnumId=CONC_EXCHANGE_NOT_IN_NS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_EXC_POS_USA_SCOPE" sequenceNum="20" fixActionTemplate="Sync the HotWax exchange for Shopify order {recordKey} to NetSuite." conclusionEnumId="CONC_EXCHANGE_NOT_IN_NS" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_EXC_POS_USA_SCOPE|conclusionEnumId=CONC_EXCHANGE_ORIGINAL_UNFULFILLED|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_EXC_POS_USA_SCOPE" sequenceNum="10" fixActionTemplate="Ship the original order {recordKey}: its exchange reaches NetSuite only once the original is fulfilled." conclusionEnumId="CONC_EXCHANGE_ORIGINAL_UNFULFILLED" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=RAILS_EXC_LOOP_CANADA_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="RAILS_EXC_LOOP_CANADA_RS" ruleSetName="Shopify Loop exchanges against NetSuite - Canada" description="Every Rails Canada Loop exchange order in Shopify (named EXC-) against the store's NetSuite exchange sales orders." version="1.0" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=RAILS_EXC_LOOP_EU_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="RAILS_EXC_LOOP_EU_RS" ruleSetName="Shopify Loop exchanges against NetSuite - EU" description="Every Rails EU Loop exchange order in Shopify (named EXC-) against the store's NetSuite exchange sales orders." version="1.0" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=RAILS_EXC_LOOP_OUTLET_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="RAILS_EXC_LOOP_OUTLET_RS" ruleSetName="Shopify Loop exchanges against NetSuite - Outlet" description="Every Rails Outlet Loop exchange order in Shopify (named EXC-) against the store's NetSuite exchange sales orders." version="1.0" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=RAILS_EXC_LOOP_UK_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="RAILS_EXC_LOOP_UK_RS" ruleSetName="Shopify Loop exchanges against NetSuite - UK" description="Every Rails UK Loop exchange order in Shopify (named EXC-) against the store's NetSuite exchange sales orders." version="1.0" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=RAILS_EXC_LOOP_USA_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="RAILS_EXC_LOOP_USA_RS" ruleSetName="Shopify Loop exchanges against NetSuite - US" description="Every Rails US Loop exchange order in Shopify (named EXC-) against the store's NetSuite exchange sales orders." version="1.0" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=RAILS_EXC_POS_CANADA_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="RAILS_EXC_POS_CANADA_RS" ruleSetName="Shopify store exchanges against NetSuite - Canada" description="Every Rails Canada Shopify order with a completed return that has exchange items (a HotWax store exchange) against the store's NetSuite exchange sales orders." version="1.0" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=RAILS_EXC_POS_EU_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="RAILS_EXC_POS_EU_RS" ruleSetName="Shopify store exchanges against NetSuite - EU" description="Every Rails EU Shopify order with a completed return that has exchange items (a HotWax store exchange) against the store's NetSuite exchange sales orders." version="1.0" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=RAILS_EXC_POS_OUTLET_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="RAILS_EXC_POS_OUTLET_RS" ruleSetName="Shopify store exchanges against NetSuite - Outlet" description="Every Rails Outlet Shopify order with a completed return that has exchange items (a HotWax store exchange) against the store's NetSuite exchange sales orders." version="1.0" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=RAILS_EXC_POS_UK_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="RAILS_EXC_POS_UK_RS" ruleSetName="Shopify store exchanges against NetSuite - UK" description="Every Rails UK Shopify order with a completed return that has exchange items (a HotWax store exchange) against the store's NetSuite exchange sales orders." version="1.0" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=RAILS_EXC_POS_USA_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="RAILS_EXC_POS_USA_RS" ruleSetName="Shopify store exchanges against NetSuite - US" description="Every Rails US Shopify order with a completed return that has exchange items (a HotWax store exchange) against the store's NetSuite exchange sales orders." version="1.0" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_EXCHANGE_NOT_IN_NS|enumTypeId=DarpanConclusion|enumCode=CONC_EXCHANGE_NOT_IN_NS`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_EXCHANGE_NOT_IN_NS" enumTypeId="DarpanConclusion" enumCode="CONC_EXCHANGE_NOT_IN_NS" description="Exchange not in NetSuite"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_EXCHANGE_ORIGINAL_UNFULFILLED|enumTypeId=DarpanConclusion|enumCode=CONC_EXCHANGE_ORIGINAL_UNFULFILLED`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_EXCHANGE_ORIGINAL_UNFULFILLED" enumTypeId="DarpanConclusion" enumCode="CONC_EXCHANGE_ORIGINAL_UNFULFILLED" description="Original order not fulfilled"/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_GQL|fieldPath=$.records[*].paymentRefNums`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_GQL" fieldPath="$.records[*].paymentRefNums" label="Payment transaction ids" fieldType="string" sequenceNum="11" description="DAR-BE-153: comma-joined OPP manualRefNums (the Shopify transaction id each OPP names), read from up to six OPPs; null when all six slots came back full, so a longer list is never claimed short. Compared against Shopify paymentTransactionIds (paymentTransactionIds all in paymentRefNums)."/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=SHOPIFY|fieldPath=$.records[*].paymentTransactionIds`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="SHOPIFY" fieldPath="$.records[*].paymentTransactionIds" label="Payment transaction ids" fieldType="String" sequenceNum="10" description="DAR-BE-153: comma-joined ids of the SUCCESS SALE and CAPTURE transactions, and of a SUCCESS AUTHORIZATION nothing captured; the ids HotWax keeps on its OPPs as manualRefNum."/>
```

## Recommended operator review

- Confirm every candidate record truly needs to be loaded for the target environment.
- Keep final upgrade records reflected in the appropriate generic source data file, such as a type, security, mapping, job, or system-message seed file.
- Prefer keeping changes in the existing domain seed file unless the release needs a distinct generic setup bundle.
- State the final operator action in `release-notes.md` and `release-checklist.md`.
