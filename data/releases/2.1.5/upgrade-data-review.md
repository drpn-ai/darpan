# Upgrade Data Review For Darpan 2.1.5

## Scope

- Backend compare range: `v2.1.4..HEAD`
- Data directory reviewed: `data`
- Generic source data files are the source of truth for release upgrade data.
- This report lists candidate seed/config records that were added or modified in generic source data files between the compared refs.
- Do not author records directly in `upgrade-data.xml`; add or update the appropriate `runtime/component/darpan/data/*.xml` file and regenerate.

## Decision

- Load target (`data/upgrade-data.xml`, mirrored here): **14 `darpan-seed` records**, rebuilt from the generator's
  candidates, not edited forward from 2.1.4's file.
  - 1 `moqui.basic.Enumeration` `OMS_PRODUCTS_GQL` (`DarpanSystemSourceSeedData.xml`, DAR-BE-157).
  - 1 `darpan.reconciliation.SourceSystemConnector` `OMS_PRODUCTS_GQL` (`SourceSystemConnectorSeedData.xml`, DAR-BE-157).
  - 12 `darpan.reconciliation.SourceSystemConnectorField` rows (`SourceSystemConnectorFieldSeedData.xml`): 11
    `OMS_PRODUCTS_GQL` (DAR-BE-157) and `SHOPIFY` `transactionIds` (DAR-BE-153).
- Ordering: parent before child (Enumeration, then connector, then its fields), not the generator's alphabetical order;
  an ORDERING NOTE in the file says so.
- Excluded by design (`darpan-tenant-config`, loaded per tenant): every GORJANA candidate below, from
  `GorjanaConclusionRulesData.xml`, `GorjanaMonthCloseCatalogData.xml`, `GorjanaMonthCloseReturnsChecksData.xml`,
  `GorjanaProductPresenceChecksData.xml` and `TriSystemOrderRunsRuleSetData.xml` (gorjana R22, DAR-BE-157; brain
  pilot, DAR-BE-151).
- `data/releases/2.1.4/upgrade-data.xml` is byte-identical to `v2.1.4`'s `data/upgrade-data.xml`, so the archive step
  was a no-op.

## Candidate records

### Added in `data/DarpanSystemSourceSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=OMS_PRODUCTS_GQL|enumTypeId=DarpanSystemSource|parentEnumId=OMS|enumCode=HOTWAX_GQL_PRODUCTS`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="OMS_PRODUCTS_GQL" enumTypeId="DarpanSystemSource" enumCode="HOTWAX_GQL_PRODUCTS" description="HotWax products" sequenceNum="16" parentEnumId="OMS"/>
```

### Added in `data/GorjanaConclusionRulesData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_OMS_NS_LINE_SCOPE|conclusionEnumId=CONC_PARTIAL_PAYMENT_GIVEN_BACK|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_OMS_NS_LINE_SCOPE" sequenceNum="2" engine="BRAIN" conclusionEnumId="CONC_PARTIAL_PAYMENT_GIVEN_BACK" appliesToBucket="MISSING_FROM_FILE_2" fixActionTemplate="Nothing to fix in NetSuite: part of the order was paid and given back by its return, so the sale never stood. HotWax does not send an unpaid order." companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaConclusionRulesData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_PARTIAL_PAYMENT_GIVEN_BACK|enumTypeId=DarpanConclusion|enumCode=CONC_PARTIAL_PAYMENT_GIVEN_BACK`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_PARTIAL_PAYMENT_GIVEN_BACK" enumTypeId="DarpanConclusion" enumCode="CONC_PARTIAL_PAYMENT_GIVEN_BACK" description="Partial payment given back"/>
```

### Added in `data/GorjanaMonthCloseCatalogData.xml`

- Record: `darpan.audit.AuditStatement|statementId=GORJANA_R22|auditId=GORJANA_MONTH_CLOSE|companyUserGroupId=GORJANA|categoryEnumId=AUDC_ORDERS|weightEnumId=AUDSW_DERIVED`
- Element: `darpan.audit.AuditStatement`

```xml
<darpan.audit.AuditStatement statementId="GORJANA_R22" auditId="GORJANA_MONTH_CLOSE" companyUserGroupId="GORJANA" statementCode="R22" categoryEnumId="AUDC_ORDERS" weightEnumId="AUDSW_DERIVED" sequenceNum="22" statementText="Every active product is in both NetSuite and HotWax."/>
```

### Modified in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=GORJANA_RETURN_IN_NETSUITE_SCOPE|ruleSetId=GORJANA_RETURN_IN_NETSUITE_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="GORJANA_RETURN_IN_NETSUITE_SCOPE" ruleSetId="GORJANA_RETURN_IN_NETSUITE_RS" objectType="RETURN" scopeMode="COMPARE" conclusionBrain="RETURN" description="Every completed or received OMS return has its RMA in NetSuite, by return id." companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaMonthCloseReturnsChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_RETURN_IN_NETSUITE_SCOPE|conclusionEnumId=CONC_PARTIAL_PAYMENT_GIVEN_BACK|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_RETURN_IN_NETSUITE_SCOPE" sequenceNum="5" engine="BRAIN" conclusionEnumId="CONC_PARTIAL_PAYMENT_GIVEN_BACK" appliesToBucket="MISSING_FROM_FILE_2" fixActionTemplate="Nothing to fix in NetSuite: part of the order was paid and given back by its return, so the sale never stood. HotWax does not send an unpaid order." companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaProductPresenceChecksData.xml`

- Record: `darpan.audit.AuditCheck|checkId=GORJANA_CHK_PRODUCT_PRESENCE|companyUserGroupId=GORJANA|savedRunId=GORJANA_PRODUCT_PRESENCE_RS|fixWhereEnumId=OMS`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="GORJANA_CHK_PRODUCT_PRESENCE" companyUserGroupId="GORJANA" checkCode="products-not-in-both" label="NetSuite active items against HotWax sellable products" savedRunId="GORJANA_PRODUCT_PRESENCE_RS" file1RecordType="item" file2RecordType="product" graceDays="0" matchedBy="NetSuite item internal id = HotWax NETSUITE_PRODUCT_ID" fixOwner="HotWax Support" fixWhereEnumId="OMS" headlineTemplate="Product {recordKey} is not in both NetSuite and HotWax" fixActionTemplate="Sync NetSuite item {recordKey} to HotWax, or give the HotWax product its NetSuite id." passesWhenTemplate="NetSuite item {recordKey} and its HotWax product both exist."/>
```

### Added in `data/GorjanaProductPresenceChecksData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=GORJANA_R22|checkId=GORJANA_CHK_PRODUCT_PRESENCE|companyUserGroupId=GORJANA`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="GORJANA_R22" checkId="GORJANA_CHK_PRODUCT_PRESENCE" companyUserGroupId="GORJANA" sequenceNum="1" diffTypes="MISSING_IN_FILE_1,MISSING_IN_FILE_2" conclusionCodes="CONC_PRODUCT_NOT_IN_HOTWAX,CONC_PRODUCT_NOT_SELLABLE_IN_HOTWAX,CONC_PRODUCT_NO_NS_ID,CONC_PRODUCT_NS_ID_SHARED,CONC_PRODUCT_NS_ITEM_GONE,UNEXPLAINED"/>
```

### Added in `data/GorjanaProductPresenceChecksData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=GORJANA_PRODUCT_PRESENCE_SCOPE|ruleSetId=GORJANA_PRODUCT_PRESENCE_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="GORJANA_PRODUCT_PRESENCE_SCOPE" ruleSetId="GORJANA_PRODUCT_PRESENCE_RS" objectType="PRODUCT" scopeMode="COMPARE" description="Every active NetSuite item (stock, kit, non-inventory) is a sellable HotWax product carrying its NetSuite id, and every sellable HotWax product's NetSuite id names one. Whole catalogue, no window." companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaProductPresenceChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSourceFilter|compareScopeId=GORJANA_PRODUCT_PRESENCE_SCOPE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSourceFilter`

```xml
<darpan.rule.RuleSetCompareSourceFilter compareScopeId="GORJANA_PRODUCT_PRESENCE_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="sellable" operator="EXCLUDE_IN" filterValues="N" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaProductPresenceChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=GORJANA_PRODUCT_PRESENCE_SCOPE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="GORJANA_PRODUCT_PRESENCE_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="nsKey" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaProductPresenceChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=GORJANA_PRODUCT_PRESENCE_SCOPE|systemEnumId=NETSUITE_SUITEQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=NS_ACTIVE_ITEMS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="GORJANA_PRODUCT_PRESENCE_SCOPE" fileSide="FILE_1" systemEnumId="NETSUITE_SUITEQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="id" sourceConfigId="NS_ACTIVE_ITEMS" sourceConfigType="NETSUITE_SUITEQL" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaProductPresenceChecksData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=GORJANA_PRODUCT_PRESENCE_SCOPE|systemEnumId=OMS_PRODUCTS_GQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=gorjana_prod|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="GORJANA_PRODUCT_PRESENCE_SCOPE" fileSide="FILE_2" systemEnumId="OMS_PRODUCTS_GQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="nsKey" sourceConfigId="gorjana_prod" sourceConfigType="HOTWAX_OMS_GQL_PRODUCTS" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaProductPresenceChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionCondition|compareScopeId=GORJANA_PRODUCT_PRESENCE_SCOPE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionCondition`

```xml
<darpan.rule.RuleSetConclusionCondition compareScopeId="GORJANA_PRODUCT_PRESENCE_SCOPE" sequenceNum="20" conditionSeq="2" subject="FILE_1" presence="ANY" keyScope="FULL" fieldExpression="sku" operator="NOT_IN" conditionValues="-" checkLabel="NetSuite SKU" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaProductPresenceChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_PRODUCT_PRESENCE_SCOPE|conclusionEnumId=CONC_PRODUCT_NOT_IN_HOTWAX|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_PRODUCT_PRESENCE_SCOPE" sequenceNum="20" conclusionEnumId="CONC_PRODUCT_NOT_IN_HOTWAX" appliesToBucket="MISSING_FROM_FILE_2" fixActionTemplate="Sync NetSuite item {recordKey} ({sku}) to HotWax, then re-import anything HotWax refused for it (transfer orders)." companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaProductPresenceChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_PRODUCT_PRESENCE_SCOPE|conclusionEnumId=CONC_PRODUCT_NOT_SELLABLE_IN_HOTWAX|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_PRODUCT_PRESENCE_SCOPE" sequenceNum="17" conclusionEnumId="CONC_PRODUCT_NOT_SELLABLE_IN_HOTWAX" appliesToBucket="MISSING_FROM_FILE_2" fixActionTemplate="NetSuite item {recordKey} ({sku}) has stock or moved in 90 days, but HotWax product {productId} is {notSellableReason}: make it sellable in HotWax, or inactive in NetSuite." companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaProductPresenceChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_PRODUCT_PRESENCE_SCOPE|conclusionEnumId=CONC_PRODUCT_NO_NS_ID|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_PRODUCT_PRESENCE_SCOPE" sequenceNum="10" conclusionEnumId="CONC_PRODUCT_NO_NS_ID" appliesToBucket="MISSING_FROM_FILE_1" fixActionTemplate="Give HotWax product {productId} ({internalName}) its NetSuite id, or retire it in HotWax." companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaProductPresenceChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_PRODUCT_PRESENCE_SCOPE|conclusionEnumId=CONC_PRODUCT_NS_ID_SHARED|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_PRODUCT_PRESENCE_SCOPE" sequenceNum="12" conclusionEnumId="CONC_PRODUCT_NS_ID_SHARED" appliesToBucket="MISSING_FROM_FILE_1" fixActionTemplate="NetSuite id {netsuiteProductId} is on HotWax products {productId} ({internalName}) and {sharedWith}: keep it on the product that is that NetSuite item and remove it from the others." companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaProductPresenceChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_PRODUCT_PRESENCE_SCOPE|conclusionEnumId=CONC_PRODUCT_NS_ITEM_GONE|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_PRODUCT_PRESENCE_SCOPE" sequenceNum="15" conclusionEnumId="CONC_PRODUCT_NS_ITEM_GONE" appliesToBucket="MISSING_FROM_FILE_1" fixActionTemplate="NetSuite has no active item {recordKey}: reactivate it in NetSuite, or retire HotWax product {productId} ({internalName})." companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaProductPresenceChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_PRODUCT_PRESENCE_SCOPE|conclusionEnumId=CONC_PRODUCT_NS_ONLY_NON_INVENTORY|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_PRODUCT_PRESENCE_SCOPE" sequenceNum="16" conclusionEnumId="CONC_PRODUCT_NS_ONLY_NON_INVENTORY" appliesToBucket="MISSING_FROM_FILE_2" fixActionTemplate="Nothing to fix: {sku} is a NetSuite non-inventory item (an accounting line or a charge) that HotWax does not sell. Sync it to HotWax only if stores sell it." companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaProductPresenceChecksData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=GORJANA_PRODUCT_PRESENCE_SCOPE|conclusionEnumId=CONC_PRODUCT_QUIET_IN_NS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="GORJANA_PRODUCT_PRESENCE_SCOPE" sequenceNum="5" conclusionEnumId="CONC_PRODUCT_QUIET_IN_NS" appliesToBucket="MISSING_FROM_FILE_1" fixActionTemplate="Nothing to fix: NetSuite item {recordKey} is active but has no stock and no movement in 90 days, so the check does not require HotWax product {internalName}. Make it inactive in NetSuite if it is retired." companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaProductPresenceChecksData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=GORJANA_PRODUCT_PRESENCE_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="GORJANA_PRODUCT_PRESENCE_RS" ruleSetName="Products - NetSuite vs HotWax" description="Every active NetSuite item (stock, kit, non-inventory) is a sellable HotWax product carrying its NetSuite id, and every sellable HotWax product's NetSuite id names one. Whole catalogue, no window." version="1.0" companyUserGroupId="GORJANA"/>
```

### Added in `data/GorjanaProductPresenceChecksData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_PRODUCT_NOT_IN_HOTWAX|enumTypeId=DarpanConclusion|enumCode=CONC_PRODUCT_NOT_IN_HOTWAX`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_PRODUCT_NOT_IN_HOTWAX" enumTypeId="DarpanConclusion" enumCode="CONC_PRODUCT_NOT_IN_HOTWAX" description="Not synced to HotWax"/>
```

### Added in `data/GorjanaProductPresenceChecksData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_PRODUCT_NOT_SELLABLE_IN_HOTWAX|enumTypeId=DarpanConclusion|enumCode=CONC_PRODUCT_NOT_SELLABLE_IN_HOTWAX`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_PRODUCT_NOT_SELLABLE_IN_HOTWAX" enumTypeId="DarpanConclusion" enumCode="CONC_PRODUCT_NOT_SELLABLE_IN_HOTWAX" description="In HotWax, not sellable"/>
```

### Added in `data/GorjanaProductPresenceChecksData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_PRODUCT_NO_NS_ID|enumTypeId=DarpanConclusion|enumCode=CONC_PRODUCT_NO_NS_ID`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_PRODUCT_NO_NS_ID" enumTypeId="DarpanConclusion" enumCode="CONC_PRODUCT_NO_NS_ID" description="No NetSuite id in HotWax"/>
```

### Added in `data/GorjanaProductPresenceChecksData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_PRODUCT_NS_ID_SHARED|enumTypeId=DarpanConclusion|enumCode=CONC_PRODUCT_NS_ID_SHARED`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_PRODUCT_NS_ID_SHARED" enumTypeId="DarpanConclusion" enumCode="CONC_PRODUCT_NS_ID_SHARED" description="One NetSuite id on several HotWax products"/>
```

### Added in `data/GorjanaProductPresenceChecksData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_PRODUCT_NS_ITEM_GONE|enumTypeId=DarpanConclusion|enumCode=CONC_PRODUCT_NS_ITEM_GONE`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_PRODUCT_NS_ITEM_GONE" enumTypeId="DarpanConclusion" enumCode="CONC_PRODUCT_NS_ITEM_GONE" description="NetSuite item inactive or gone"/>
```

### Added in `data/GorjanaProductPresenceChecksData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_PRODUCT_NS_ONLY_NON_INVENTORY|enumTypeId=DarpanConclusion|enumCode=CONC_PRODUCT_NS_ONLY_NON_INVENTORY`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_PRODUCT_NS_ONLY_NON_INVENTORY" enumTypeId="DarpanConclusion" enumCode="CONC_PRODUCT_NS_ONLY_NON_INVENTORY" description="Non-inventory item, NetSuite only"/>
```

### Added in `data/GorjanaProductPresenceChecksData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_PRODUCT_QUIET_IN_NS|enumTypeId=DarpanConclusion|enumCode=CONC_PRODUCT_QUIET_IN_NS`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_PRODUCT_QUIET_IN_NS" enumTypeId="DarpanConclusion" enumCode="CONC_PRODUCT_QUIET_IN_NS" description="Quiet in NetSuite"/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_PRODUCTS_GQL|fieldPath=$.records[*].createdDate`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_PRODUCTS_GQL" fieldPath="$.records[*].createdDate" label="Created" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="6" description="When HotWax created the product."/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_PRODUCTS_GQL|fieldPath=$.records[*].duplicateNetsuiteId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_PRODUCTS_GQL" fieldPath="$.records[*].duplicateNetsuiteId" label="Shares its NetSuite ID" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="7" description="true when another sellable product carries the same NetSuite id."/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_PRODUCTS_GQL|fieldPath=$.records[*].internalName`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_PRODUCTS_GQL" fieldPath="$.records[*].internalName" label="SKU" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="4" description="The product's internal name (its SKU)."/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_PRODUCTS_GQL|fieldPath=$.records[*].netsuiteIdState`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_PRODUCTS_GQL" fieldPath="$.records[*].netsuiteIdState" label="NetSuite ID state" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="8" description="OWN: keyed on its NetSuite id; NONE: it has none (nsKey HOTWAX-ONLY:productId); SHARED: another product holds the id first (nsKey DUPLICATE:id:productId)."/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_PRODUCTS_GQL|fieldPath=$.records[*].netsuiteProductId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_PRODUCTS_GQL" fieldPath="$.records[*].netsuiteProductId" label="NetSuite item ID" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="2" description="The NETSUITE_PRODUCT_ID identification, blank when the product has none."/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_PRODUCTS_GQL|fieldPath=$.records[*].notSellableReason`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_PRODUCTS_GQL" fieldPath="$.records[*].notSellableReason" label="Not sellable because" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="11" description="discontinued, virtual, or the product type (DIGITAL_GOOD, SERVICE ...) when sellable is N."/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_PRODUCTS_GQL|fieldPath=$.records[*].nsKey`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_PRODUCTS_GQL" fieldPath="$.records[*].nsKey" label="NetSuite item ID (key)" fieldType="string" isPrimaryIdCandidate="Y" sequenceNum="1" description="The compare key: the product's NETSUITE_PRODUCT_ID (the NetSuite item internal id); HOTWAX-ONLY:productId when it has none, DUPLICATE:id:productId for every product after the first sharing one."/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_PRODUCTS_GQL|fieldPath=$.records[*].productId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_PRODUCTS_GQL" fieldPath="$.records[*].productId" label="HotWax product ID" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="3" description="HotWax's own product id."/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_PRODUCTS_GQL|fieldPath=$.records[*].productTypeId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_PRODUCTS_GQL" fieldPath="$.records[*].productTypeId" label="Product type" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="5" description="FINISHED_GOOD or MARKETING_PKG_PICK."/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_PRODUCTS_GQL|fieldPath=$.records[*].sellable`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_PRODUCTS_GQL" fieldPath="$.records[*].sellable" label="Sellable" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="10" description="Y for a sellable product; N for a discontinued, virtual or non-selling product kept only because it carries a NetSuite id no sellable product holds."/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_PRODUCTS_GQL|fieldPath=$.records[*].sharedWith`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_PRODUCTS_GQL" fieldPath="$.records[*].sharedWith" label="Shares its NetSuite ID with" fieldType="string" isPrimaryIdCandidate="N" sequenceNum="9" description="The other sellable HotWax products carrying the same NetSuite id, comma-separated."/>
```

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=SHOPIFY|fieldPath=$.records[*].transactionIds`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="SHOPIFY" fieldPath="$.records[*].transactionIds" label="Transaction ids" fieldType="String" sequenceNum="11" description="DAR-BE-153: comma-joined ids of every SUCCESS transaction on the order, any kind (authorization, capture, sale, void, refund). Each HotWax OPP manualRefNum must be among them (paymentRefNums all in transactionIds); extra Shopify ids are fine."/>
```

### Added in `data/SourceSystemConnectorSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnector|systemEnumId=OMS_PRODUCTS_GQL|remoteId=HOTWAX_ORDERS_API`
- Element: `darpan.reconciliation.SourceSystemConnector`

```xml
<darpan.reconciliation.SourceSystemConnector systemEnumId="OMS_PRODUCTS_GQL" extractServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlProducts" dateFromParameterName="windowStart" dateToParameterName="windowEnd" expectedSourceConfigType="HOTWAX_OMS_GQL_PRODUCTS" configParameterName="omsRestSourceConfigId" configEntityName="darpan.hotwax.HotWaxOmsRestSourceConfig" remoteId="HOTWAX_ORDERS_API" endpointLabel="Products" sendUrlTemplate="{baseUrl}/rest/s1/graphql" remoteSendServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsGqlProducts" systemAliases="OMS_PRODUCTS_GQL,HOTWAX_PRODUCTS" preserveWindowInstants="N" windowed="N" filterParameterName="sourceFilters" healthCheckServiceName="facade.HotWaxOmsFacadeServices.probe#HotWaxOmsConnection" enabled="Y"/>
```

### Modified in `data/TriSystemOrderRunsRuleSetData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=GORJANA_OMS_NS_LINE_SCOPE|ruleSetId=GORJANA_OMS_NS_LINE_RS|companyUserGroupId=GORJANA`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="GORJANA_OMS_NS_LINE_SCOPE" ruleSetId="GORJANA_OMS_NS_LINE_RS" objectType="ORDER_LINE_UNIT" scopeMode="COMPARE" conclusionBrain="ORDER_LINE" description="Every item HotWax completed must be billed on its NetSuite sales order line, and every billed NetSuite line must be completed in HotWax. Keyed (OMS order id, OMS order item seq id)." conclusionLookupsJson='[{"name":"orderInNetsuite","subject":"FILE_2","idField":"internalId","systemEnumId":"NETSUITE_SUITEQL","sourceConfigId":"NS_SO_BACKORDER","keyField":"internalId"},{"name":"omsOrder","subject":"FILE_1","idField":"omsOrderId","systemEnumId":"OMS_GQL","sourceConfigId":"gorjana_prod","keyField":"orderId","parameters":{"keepRecordFields":["orderId","notes","statuses"]}}]' companyUserGroupId="GORJANA"/>
```

## Recommended operator review

- Confirm every candidate record truly needs to be loaded for the target environment.
- Keep final upgrade records reflected in the appropriate generic source data file, such as a type, security, mapping, job, or system-message seed file.
- Prefer keeping changes in the existing domain seed file unless the release needs a distinct generic setup bundle.
- State the final operator action in `release-notes.md` and `release-checklist.md`.
