# Upgrade Data Review For Darpan 2.0.0

## Scope

- Backend compare range: `v1.1.1..e1efc7c`
- Data directory reviewed: `data`
- Generic source data files are the source of truth for release upgrade data.
- This report lists candidate seed/config records that were added or modified in generic source data files between the compared refs.
- Do not author records directly in `upgrade-data.xml`; add or update the appropriate `runtime/component/darpan/data/*.xml` file and regenerate.

## Review outcome

- Accepted production load records are mirrored in `data/upgrade-data.xml` and `data/releases/2.0.0/upgrade-data.xml`.
- Smoke-test RuleSet records from `data/ReconciliationCompareScopeFixtureData.xml` were excluded from the production load file.
- Duplicate source/file-type enumeration candidates were collapsed out of the production load file when the same persisted record was already covered by another generic source data diff or already existed in `v1.1.1`.
- The full candidate list below is retained for traceability against `v1.1.1`.

## Candidate records

### Added in `data/AutomationSeedData.xml`

- Record: `moqui.basic.EnumerationType|enumTypeId=AutomationExecStatus`
- Element: `moqui.basic.EnumerationType`

```xml
<moqui.basic.EnumerationType enumTypeId="AutomationExecStatus" description="Automation execution status"/>
```

### Added in `data/AutomationSeedData.xml`

- Record: `moqui.basic.EnumerationType|enumTypeId=AutomationInputMode`
- Element: `moqui.basic.EnumerationType`

```xml
<moqui.basic.EnumerationType enumTypeId="AutomationInputMode" description="Automation input mode"/>
```

### Added in `data/AutomationSeedData.xml`

- Record: `moqui.basic.EnumerationType|enumTypeId=AutomationRelWindow`
- Element: `moqui.basic.EnumerationType`

```xml
<moqui.basic.EnumerationType enumTypeId="AutomationRelWindow" description="Automation relative date window"/>
```

### Added in `data/AutomationSeedData.xml`

- Record: `moqui.basic.EnumerationType|enumTypeId=AutomationSourceType`
- Element: `moqui.basic.EnumerationType`

```xml
<moqui.basic.EnumerationType enumTypeId="AutomationSourceType" description="Automation source type"/>
```

### Added in `data/AutomationSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUT_IN_API_RANGE|enumTypeId=AutomationInputMode|enumCode=API_DATE_RANGE`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUT_IN_API_RANGE" enumTypeId="AutomationInputMode" enumCode="API_DATE_RANGE" description="API date-range extraction" sequenceNum="1"/>
```

### Added in `data/AutomationSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUT_IN_SFTP_FILES|enumTypeId=AutomationInputMode|enumCode=SFTP_FILES`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUT_IN_SFTP_FILES" enumTypeId="AutomationInputMode" enumCode="SFTP_FILES" description="SFTP file inputs" sequenceNum="2"/>
```

### Added in `data/AutomationSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUT_SRC_API|enumTypeId=AutomationSourceType|enumCode=API`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUT_SRC_API" enumTypeId="AutomationSourceType" enumCode="API" description="API source" sequenceNum="1"/>
```

### Added in `data/AutomationSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUT_SRC_SFTP|enumTypeId=AutomationSourceType|enumCode=SFTP`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUT_SRC_SFTP" enumTypeId="AutomationSourceType" enumCode="SFTP" description="SFTP source" sequenceNum="2"/>
```

### Added in `data/AutomationSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUT_STAT_FAILED|enumTypeId=AutomationExecStatus|enumCode=FAILED`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUT_STAT_FAILED" enumTypeId="AutomationExecStatus" enumCode="FAILED" description="Failed" sequenceNum="5"/>
```

### Added in `data/AutomationSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUT_STAT_NO_DATA|enumTypeId=AutomationExecStatus|enumCode=NO_DATA`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUT_STAT_NO_DATA" enumTypeId="AutomationExecStatus" enumCode="NO_DATA" description="No input data available" sequenceNum="4"/>
```

### Added in `data/AutomationSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUT_STAT_PENDING|enumTypeId=AutomationExecStatus|enumCode=PENDING`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUT_STAT_PENDING" enumTypeId="AutomationExecStatus" enumCode="PENDING" description="Pending execution" sequenceNum="1"/>
```

### Added in `data/AutomationSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUT_STAT_RUNNING|enumTypeId=AutomationExecStatus|enumCode=RUNNING`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUT_STAT_RUNNING" enumTypeId="AutomationExecStatus" enumCode="RUNNING" description="Running" sequenceNum="2"/>
```

### Added in `data/AutomationSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUT_STAT_SKIP_DUP|enumTypeId=AutomationExecStatus|enumCode=SKIPPED_DUPLICATE`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUT_STAT_SKIP_DUP" enumTypeId="AutomationExecStatus" enumCode="SKIPPED_DUPLICATE" description="Skipped duplicate" sequenceNum="6"/>
```

### Added in `data/AutomationSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUT_STAT_SUCCESS|enumTypeId=AutomationExecStatus|enumCode=SUCCEEDED`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUT_STAT_SUCCESS" enumTypeId="AutomationExecStatus" enumCode="SUCCEEDED" description="Succeeded" sequenceNum="3"/>
```

### Added in `data/AutomationSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUT_WIN_CUSTOM|enumTypeId=AutomationRelWindow|enumCode=CUSTOM_RANGE`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUT_WIN_CUSTOM" enumTypeId="AutomationRelWindow" enumCode="CUSTOM_RANGE" description="Custom date range" sequenceNum="7"/>
```

### Added in `data/AutomationSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUT_WIN_LAST_DAYS|enumTypeId=AutomationRelWindow|enumCode=LAST_N_DAYS`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUT_WIN_LAST_DAYS" enumTypeId="AutomationRelWindow" enumCode="LAST_N_DAYS" description="Last N days" sequenceNum="4"/>
```

### Added in `data/AutomationSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUT_WIN_LAST_MONTHS|enumTypeId=AutomationRelWindow|enumCode=LAST_N_MONTHS`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUT_WIN_LAST_MONTHS" enumTypeId="AutomationRelWindow" enumCode="LAST_N_MONTHS" description="Last N months" sequenceNum="6"/>
```

### Added in `data/AutomationSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUT_WIN_LAST_WEEKS|enumTypeId=AutomationRelWindow|enumCode=LAST_N_WEEKS`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUT_WIN_LAST_WEEKS" enumTypeId="AutomationRelWindow" enumCode="LAST_N_WEEKS" description="Last N weeks" sequenceNum="5"/>
```

### Added in `data/AutomationSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUT_WIN_PREV_DAY|enumTypeId=AutomationRelWindow|enumCode=PREVIOUS_DAY`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUT_WIN_PREV_DAY" enumTypeId="AutomationRelWindow" enumCode="PREVIOUS_DAY" description="Previous calendar day" sequenceNum="1"/>
```

### Added in `data/AutomationSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUT_WIN_PREV_MONTH|enumTypeId=AutomationRelWindow|enumCode=PREVIOUS_MONTH`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUT_WIN_PREV_MONTH" enumTypeId="AutomationRelWindow" enumCode="PREVIOUS_MONTH" description="Previous month" sequenceNum="3"/>
```

### Added in `data/AutomationSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUT_WIN_PREV_WEEK|enumTypeId=AutomationRelWindow|enumCode=PREVIOUS_WEEK`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUT_WIN_PREV_WEEK" enumTypeId="AutomationRelWindow" enumCode="PREVIOUS_WEEK" description="Previous calendar week" sequenceNum="2"/>
```

### Added in `data/DarpanSystemSourceSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=OMS|enumTypeId=DarpanSystemSource|enumCode=HOTWAX`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="OMS" enumTypeId="DarpanSystemSource" enumCode="HOTWAX" description="HotWax" sequenceNum="1"/>
```

### Added in `data/MappingSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=OMS|enumTypeId=DarpanSystemSource|enumCode=HOTWAX`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="OMS" enumTypeId="DarpanSystemSource" enumCode="HOTWAX" description="HotWax" sequenceNum="1"/>
```

### Added in `data/ReconciliationCompareScopeFixtureData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=DARPAN_TEST_ORDER_JSON_SCOPE|ruleSetId=DARPAN_TEST_COMPARE_RS`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="DARPAN_TEST_ORDER_JSON_SCOPE" ruleSetId="DARPAN_TEST_COMPARE_RS" objectType="ORDER" description="Smoke-test compare scope for nested JSON order IDs."/>
```

### Added in `data/ReconciliationCompareScopeFixtureData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=DARPAN_TEST_PRODUCT_BROKEN_JSON_SCOPE|ruleSetId=DARPAN_TEST_PRODUCT_BROKEN_RS`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="DARPAN_TEST_PRODUCT_BROKEN_JSON_SCOPE" ruleSetId="DARPAN_TEST_PRODUCT_BROKEN_RS" objectType="PRODUCT" description="Smoke-test compare scope for broken DRL preservation behavior."/>
```

### Added in `data/ReconciliationCompareScopeFixtureData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=DARPAN_TEST_PRODUCT_JSON_SCOPE|ruleSetId=DARPAN_TEST_PRODUCT_COMPARE_RS`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="DARPAN_TEST_PRODUCT_JSON_SCOPE" ruleSetId="DARPAN_TEST_PRODUCT_COMPARE_RS" objectType="PRODUCT" description="Smoke-test compare scope for product field mismatches."/>
```

### Added in `data/ReconciliationCompareScopeFixtureData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=DARPAN_TEST_ORDER_JSON_SCOPE|systemEnumId=OMS|fileTypeEnumId=DftJson`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="DARPAN_TEST_ORDER_JSON_SCOPE" fileSide="FILE_2" systemEnumId="OMS" fileTypeEnumId="DftJson" recordRootExpression="data.orders.edges" primaryIdExpression="node.id" idValueNormalizer="SHOPIFY_GID_TAIL"/>
```

### Added in `data/ReconciliationCompareScopeFixtureData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=DARPAN_TEST_ORDER_JSON_SCOPE|systemEnumId=SHOPIFY|fileTypeEnumId=DftJson`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="DARPAN_TEST_ORDER_JSON_SCOPE" fileSide="FILE_1" systemEnumId="SHOPIFY" fileTypeEnumId="DftJson" recordRootExpression="data.orders.edges" primaryIdExpression="node.id|SHOPIFY_GID_TAIL"/>
```

### Added in `data/ReconciliationCompareScopeFixtureData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=DARPAN_TEST_PRODUCT_BROKEN_JSON_SCOPE|systemEnumId=OMS|fileTypeEnumId=DftJson`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="DARPAN_TEST_PRODUCT_BROKEN_JSON_SCOPE" fileSide="FILE_2" systemEnumId="OMS" fileTypeEnumId="DftJson" recordRootExpression="data.products" primaryIdExpression="productId"/>
```

### Added in `data/ReconciliationCompareScopeFixtureData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=DARPAN_TEST_PRODUCT_BROKEN_JSON_SCOPE|systemEnumId=SHOPIFY|fileTypeEnumId=DftJson`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="DARPAN_TEST_PRODUCT_BROKEN_JSON_SCOPE" fileSide="FILE_1" systemEnumId="SHOPIFY" fileTypeEnumId="DftJson" recordRootExpression="data.products" primaryIdExpression="productId"/>
```

### Added in `data/ReconciliationCompareScopeFixtureData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=DARPAN_TEST_PRODUCT_JSON_SCOPE|systemEnumId=OMS|fileTypeEnumId=DftJson`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="DARPAN_TEST_PRODUCT_JSON_SCOPE" fileSide="FILE_2" systemEnumId="OMS" fileTypeEnumId="DftJson" recordRootExpression="data.products" primaryIdExpression="productId"/>
```

### Added in `data/ReconciliationCompareScopeFixtureData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=DARPAN_TEST_PRODUCT_JSON_SCOPE|systemEnumId=SHOPIFY|fileTypeEnumId=DftJson`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="DARPAN_TEST_PRODUCT_JSON_SCOPE" fileSide="FILE_1" systemEnumId="SHOPIFY" fileTypeEnumId="DftJson" recordRootExpression="data.products" primaryIdExpression="productId"/>
```

### Added in `data/ReconciliationCompareScopeFixtureData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=DARPAN_TEST_PRODUCT_BROKEN_RS`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="DARPAN_TEST_PRODUCT_BROKEN_RS" ruleSetName="Darpan Test Broken Product RuleSet" description="Smoke-test RuleSet for preserving base diffs when DRL compilation fails." version="1.0" explosionPath="data.products" primaryKeyPath="productId"/>
```

### Added in `data/ReconciliationCompareScopeFixtureData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=DARPAN_TEST_PRODUCT_COMPARE_RS`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="DARPAN_TEST_PRODUCT_COMPARE_RS" ruleSetName="Darpan Test Product Compare RuleSet" description="Smoke-test RuleSet for matched-pair field diff generation." version="1.0" explosionPath="data.products" primaryKeyPath="productId"/>
```

### Added in `data/ReconciliationCompareScopeFixtureData.xml`

- Record: `darpan.rule.Rule|ruleId=DARPAN_TEST_PRODUCT_BROKEN_RULE|ruleSetId=DARPAN_TEST_PRODUCT_BROKEN_RS`
- Element: `darpan.rule.Rule`

```xml
<darpan.rule.Rule ruleId="DARPAN_TEST_PRODUCT_BROKEN_RULE" ruleSetId="DARPAN_TEST_PRODUCT_BROKEN_RS" sequenceNum="10" ruleText="Broken DRL used to verify base-diff preservation" ruleLogic='rule "DARPAN_TEST_PRODUCT_BROKEN_RULE" when $m : Map( this["file1"] != null ) then BROKEN end' enabled="Y" ruleType="DRL" severity="ERROR"/>
```

### Added in `data/ReconciliationCompareScopeFixtureData.xml`

- Record: `darpan.rule.Rule|ruleId=DARPAN_TEST_PRODUCT_PRICE_MISMATCH|ruleSetId=DARPAN_TEST_PRODUCT_COMPARE_RS`
- Element: `darpan.rule.Rule`

```xml
<darpan.rule.Rule ruleId="DARPAN_TEST_PRODUCT_PRICE_MISMATCH" ruleSetId="DARPAN_TEST_PRODUCT_COMPARE_RS" sequenceNum="20" ruleText="Emit a field diff when price differs for the same product" ruleLogic='rule "DARPAN_TEST_PRODUCT_PRICE_MISMATCH" salience 100 when $m : Map( this["file1"] != null, this["file2"] != null ) eval(RuleDiffSupport.valuesDiffer(((Map) $m.get("file1")).get("price"), ((Map) $m.get("file2")).get("price"))) then RuleDiffSupport.addFieldMismatch($m, kcontext.getRule().getName(), "price", ((Map) $m.get("file1")).get("price"), ((Map) $m.get("file2")).get("price"), "WARN", "Price mismatch"); end' enabled="Y" ruleType="DRL" severity="WARN"/>
```

### Added in `data/ReconciliationCompareScopeFixtureData.xml`

- Record: `darpan.rule.Rule|ruleId=DARPAN_TEST_PRODUCT_SKU_MISMATCH|ruleSetId=DARPAN_TEST_PRODUCT_COMPARE_RS`
- Element: `darpan.rule.Rule`

```xml
<darpan.rule.Rule ruleId="DARPAN_TEST_PRODUCT_SKU_MISMATCH" ruleSetId="DARPAN_TEST_PRODUCT_COMPARE_RS" sequenceNum="10" ruleText="Emit a field diff when SKU differs for the same product" ruleLogic='rule "DARPAN_TEST_PRODUCT_SKU_MISMATCH" salience 200 when $m : Map( this["file1"] != null, this["file2"] != null ) eval(RuleDiffSupport.valuesDiffer(((Map) $m.get("file1")).get("sku"), ((Map) $m.get("file2")).get("sku"))) then RuleDiffSupport.addFieldMismatch($m, kcontext.getRule().getName(), "sku", ((Map) $m.get("file1")).get("sku"), ((Map) $m.get("file2")).get("sku"), "WARN", "SKU mismatch"); end' enabled="Y" ruleType="DRL" severity="WARN"/>
```

### Added in `data/ReconciliationCompareScopeFixtureData.xml`

- Record: `moqui.basic.EnumerationType|enumTypeId=DarpanFileType`
- Element: `moqui.basic.EnumerationType`

```xml
<moqui.basic.EnumerationType enumTypeId="DarpanFileType" description="File Types for Reconciliation"/>
```

### Added in `data/ReconciliationCompareScopeFixtureData.xml`

- Record: `moqui.basic.EnumerationType|enumTypeId=DarpanSystemSource`
- Element: `moqui.basic.EnumerationType`

```xml
<moqui.basic.EnumerationType enumTypeId="DarpanSystemSource" description="Darpan System Source"/>
```

### Added in `data/ReconciliationCompareScopeFixtureData.xml`

- Record: `moqui.basic.Enumeration|enumId=DftJson|enumTypeId=DarpanFileType|enumCode=JSON`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="DftJson" enumTypeId="DarpanFileType" enumCode="JSON" description="JSON" sequenceNum="2"/>
```

### Added in `data/ReconciliationCompareScopeFixtureData.xml`

- Record: `moqui.basic.Enumeration|enumId=OMS|enumTypeId=DarpanSystemSource|enumCode=HOTWAX`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="OMS" enumTypeId="DarpanSystemSource" enumCode="HOTWAX" description="HotWax" sequenceNum="1"/>
```

### Added in `data/ReconciliationCompareScopeFixtureData.xml`

- Record: `moqui.basic.Enumeration|enumId=SHOPIFY|enumTypeId=DarpanSystemSource|enumCode=SHOPIFY`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="SHOPIFY" enumTypeId="DarpanSystemSource" enumCode="SHOPIFY" description="Shopify" sequenceNum="2"/>
```

### Added in `data/ReconciliationJobSeedData.xml`

- Record: `moqui.service.job.ServiceJobParameter|jobName=scan_ReconciliationAutomations_5m|parameterName=limit`
- Element: `moqui.service.job.ServiceJobParameter`

```xml
<moqui.service.job.ServiceJobParameter jobName="scan_ReconciliationAutomations_5m" parameterName="limit" parameterValue="100"/>
```

### Added in `data/ReconciliationJobSeedData.xml`

- Record: `moqui.service.job.ServiceJob|jobName=scan_ReconciliationAutomations_5m`
- Element: `moqui.service.job.ServiceJob`

```xml
<moqui.service.job.ServiceJob jobName="scan_ReconciliationAutomations_5m" description="Scan due reconciliation automations and execute scheduled windows." serviceName="reconciliation.ReconciliationAutomationServices.scan#DueAutomations" cronExpression="0 0/5 * * * ?" paused="N"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.basic.EnumerationType|enumTypeId=DarpanSftpScope`
- Element: `moqui.basic.EnumerationType`

```xml
<moqui.basic.EnumerationType enumTypeId="DarpanSftpScope" description="Darpan SFTP server scope"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=DARPAN_SFTP_ADMIN|enumTypeId=DarpanSftpScope`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="DARPAN_SFTP_ADMIN" description="Admin/platform-owned SFTP server" enumTypeId="DarpanSftpScope"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=DARPAN_SFTP_TENANT_GROUP|enumTypeId=DarpanSftpScope`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="DARPAN_SFTP_TENANT_GROUP" description="SFTP server shared across explicitly assigned tenant groups" enumTypeId="DarpanSftpScope"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=DARPAN_SFTP_TENANT|enumTypeId=DarpanSftpScope`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="DARPAN_SFTP_TENANT" description="Tenant-owned SFTP server" enumTypeId="DarpanSftpScope"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=UgtDarpanCompany|enumTypeId=UserGroupType`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="UgtDarpanCompany" description="Darpan tenant groups" enumTypeId="UserGroupType"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=UgtDarpanPermission|enumTypeId=UserGroupType`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="UgtDarpanPermission" description="Darpan tenant permission groups" enumTypeId="UserGroupType"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=darpan.auth.activeTenantUserGroupId|enumTypeId=UserPreferenceKey`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="darpan.auth.activeTenantUserGroupId" description="Preferred active tenant user group id" enumTypeId="UserPreferenceKey"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=darpan.dashboard.pinnedMappingIds|enumTypeId=UserPreferenceKey`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="darpan.dashboard.pinnedMappingIds" description="Pinned dashboard reconciliation mappings" enumTypeId="UserPreferenceKey"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.ArtifactAuthzFilter|artifactAuthzId=DARPAN_FACADE_APP_ADMIN|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.ArtifactAuthzFilter`

```xml
<moqui.security.ArtifactAuthzFilter artifactAuthzId="DARPAN_FACADE_APP_ADMIN" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.ArtifactAuthzFilter|artifactAuthzId=DARPAN_FACADE_APP_USER|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.ArtifactAuthzFilter`

```xml
<moqui.security.ArtifactAuthzFilter artifactAuthzId="DARPAN_FACADE_APP_USER" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.ArtifactAuthz|artifactAuthzId=DARPAN_FACADE_APP_ADMIN|userGroupId=ADMIN|artifactGroupId=DARPAN_FACADE_APP|authzTypeEnumId=AUTHZT_ALWAYS|authzActionEnumId=AUTHZA_ALL`
- Element: `moqui.security.ArtifactAuthz`

```xml
<moqui.security.ArtifactAuthz artifactAuthzId="DARPAN_FACADE_APP_ADMIN" userGroupId="ADMIN" artifactGroupId="DARPAN_FACADE_APP" authzTypeEnumId="AUTHZT_ALWAYS" authzActionEnumId="AUTHZA_ALL"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.ArtifactAuthz|artifactAuthzId=DARPAN_FACADE_APP_USER|userGroupId=DARPAN_USER|artifactGroupId=DARPAN_FACADE_APP|authzTypeEnumId=AUTHZT_ALWAYS|authzActionEnumId=AUTHZA_ALL`
- Element: `moqui.security.ArtifactAuthz`

```xml
<moqui.security.ArtifactAuthz artifactAuthzId="DARPAN_FACADE_APP_USER" userGroupId="DARPAN_USER" artifactGroupId="DARPAN_FACADE_APP" authzTypeEnumId="AUTHZT_ALWAYS" authzActionEnumId="AUTHZA_ALL"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.ArtifactGroupMember|artifactGroupId=DARPAN_APP|artifactTypeEnumId=AT_ENTITY|artifactName=darpan.auth.TenantSetting`
- Element: `moqui.security.ArtifactGroupMember`

```xml
<moqui.security.ArtifactGroupMember artifactGroupId="DARPAN_APP" artifactName="darpan.auth.TenantSetting" artifactTypeEnumId="AT_ENTITY" inheritAuthz="Y"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.ArtifactGroupMember|artifactGroupId=DARPAN_APP|artifactTypeEnumId=AT_ENTITY|artifactName=darpan.reconciliation.ReconciliationAutomation`
- Element: `moqui.security.ArtifactGroupMember`

```xml
<moqui.security.ArtifactGroupMember artifactGroupId="DARPAN_APP" artifactName="darpan.reconciliation.ReconciliationAutomation" artifactTypeEnumId="AT_ENTITY" inheritAuthz="Y"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.ArtifactGroupMember|artifactGroupId=DARPAN_APP|artifactTypeEnumId=AT_ENTITY|artifactName=darpan.reconciliation.ReconciliationAutomationExecution`
- Element: `moqui.security.ArtifactGroupMember`

```xml
<moqui.security.ArtifactGroupMember artifactGroupId="DARPAN_APP" artifactName="darpan.reconciliation.ReconciliationAutomationExecution" artifactTypeEnumId="AT_ENTITY" inheritAuthz="Y"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.ArtifactGroupMember|artifactGroupId=DARPAN_APP|artifactTypeEnumId=AT_ENTITY|artifactName=darpan.reconciliation.ReconciliationAutomationSource`
- Element: `moqui.security.ArtifactGroupMember`

```xml
<moqui.security.ArtifactGroupMember artifactGroupId="DARPAN_APP" artifactName="darpan.reconciliation.ReconciliationAutomationSource" artifactTypeEnumId="AT_ENTITY" inheritAuthz="Y"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.ArtifactGroupMember|artifactGroupId=DARPAN_APP|artifactTypeEnumId=AT_ENTITY|artifactName=darpan.reconciliation.ReconciliationRunResult`
- Element: `moqui.security.ArtifactGroupMember`

```xml
<moqui.security.ArtifactGroupMember artifactGroupId="DARPAN_APP" artifactName="darpan.reconciliation.ReconciliationRunResult" artifactTypeEnumId="AT_ENTITY" inheritAuthz="Y"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.ArtifactGroupMember|artifactGroupId=DARPAN_APP|artifactTypeEnumId=AT_ENTITY|artifactName=darpan.reconciliation.TenantNotificationSetting`
- Element: `moqui.security.ArtifactGroupMember`

```xml
<moqui.security.ArtifactGroupMember artifactGroupId="DARPAN_APP" artifactName="darpan.reconciliation.TenantNotificationSetting" artifactTypeEnumId="AT_ENTITY" inheritAuthz="Y"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.ArtifactGroupMember|artifactGroupId=DARPAN_APP|artifactTypeEnumId=AT_ENTITY|artifactName=darpan.rule.RuleSet`
- Element: `moqui.security.ArtifactGroupMember`

```xml
<moqui.security.ArtifactGroupMember artifactGroupId="DARPAN_APP" artifactName="darpan.rule.RuleSet" artifactTypeEnumId="AT_ENTITY" inheritAuthz="Y"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.ArtifactGroupMember|artifactGroupId=DARPAN_APP|artifactTypeEnumId=AT_ENTITY|artifactName=darpan.rule.RuleSetCompareScope`
- Element: `moqui.security.ArtifactGroupMember`

```xml
<moqui.security.ArtifactGroupMember artifactGroupId="DARPAN_APP" artifactName="darpan.rule.RuleSetCompareScope" artifactTypeEnumId="AT_ENTITY" inheritAuthz="Y"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.ArtifactGroupMember|artifactGroupId=DARPAN_APP|artifactTypeEnumId=AT_ENTITY|artifactName=darpan.rule.RuleSetCompareSource`
- Element: `moqui.security.ArtifactGroupMember`

```xml
<moqui.security.ArtifactGroupMember artifactGroupId="DARPAN_APP" artifactName="darpan.rule.RuleSetCompareSource" artifactTypeEnumId="AT_ENTITY" inheritAuthz="Y"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.ArtifactGroupMember|artifactGroupId=DARPAN_FACADE_APP|artifactTypeEnumId=AT_SERVICE|artifactName=facade\..*`
- Element: `moqui.security.ArtifactGroupMember`

```xml
<moqui.security.ArtifactGroupMember artifactGroupId="DARPAN_FACADE_APP" artifactName="facade\..*" artifactTypeEnumId="AT_SERVICE" nameIsPattern="Y" inheritAuthz="Y"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.ArtifactGroup|artifactGroupId=DARPAN_FACADE_APP`
- Element: `moqui.security.ArtifactGroup`

```xml
<moqui.security.ArtifactGroup artifactGroupId="DARPAN_FACADE_APP" description="Darpan facade APIs for the PWA"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilterSet|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilterSet`

```xml
<moqui.security.EntityFilterSet entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" description="Filter Darpan tenant-owned rows by the active tenant in user context"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUTOMATION_EXEC|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUTOMATION_EXEC" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.ReconciliationAutomationExecution" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUTOMATION_SOURCE|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUTOMATION_SOURCE" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.ReconciliationAutomationSource" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUTOMATION|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUTOMATION" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.ReconciliationAutomation" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_JSON_SCHEMA|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_JSON_SCHEMA" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.JsonSchema" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_NS_AUTH|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_NS_AUTH" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.NsAuthConfig" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_NS_RESTLET|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_NS_RESTLET" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.NsRestletConfig" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_RECON_MAPPING|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_RECON_MAPPING" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.mapping.ReconciliationMapping" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_RULE_SET|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_RULE_SET" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.rule.RuleSet" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_RUN_RESULT|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_RUN_RESULT" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.ReconciliationRunResult" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_SFTP_SERVER|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_SFTP_SERVER" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.SftpServer" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_TENANT_NOTIF|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_TENANT_NOTIF" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.TenantNotificationSetting" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_TENANT_SETTING|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_TENANT_SETTING" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.auth.TenantSetting" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.UserGroup|userGroupId=DARPAN_COMPANY_EDITOR|groupTypeEnumId=UgtDarpanPermission`
- Element: `moqui.security.UserGroup`

```xml
<moqui.security.UserGroup userGroupId="DARPAN_COMPANY_EDITOR" description="Can create, update, run, and delete tenant-scoped Darpan data" groupTypeEnumId="UgtDarpanPermission"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.UserGroup|userGroupId=DARPAN_COMPANY_VIEW_ONLY|groupTypeEnumId=UgtDarpanPermission`
- Element: `moqui.security.UserGroup`

```xml
<moqui.security.UserGroup userGroupId="DARPAN_COMPANY_VIEW_ONLY" description="Can view tenant-scoped Darpan data but cannot mutate it" groupTypeEnumId="UgtDarpanPermission"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.UserGroup|userGroupId=DARPAN_SUPER_ADMIN|groupTypeEnumId=UgtDarpanPermission`
- Element: `moqui.security.UserGroup`

```xml
<moqui.security.UserGroup userGroupId="DARPAN_SUPER_ADMIN" description="Can view and edit every tenant and manage Darpan core configuration" groupTypeEnumId="UgtDarpanPermission"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.UserGroup|userGroupId=DARPAN_TENANT_ADMIN|groupTypeEnumId=UgtDarpanPermission`
- Element: `moqui.security.UserGroup`

```xml
<moqui.security.UserGroup userGroupId="DARPAN_TENANT_ADMIN" description="Can view, run, create, update, and delete data for associated tenants" groupTypeEnumId="UgtDarpanPermission"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.UserGroup|userGroupId=DARPAN_TENANT_USER|groupTypeEnumId=UgtDarpanPermission`
- Element: `moqui.security.UserGroup`

```xml
<moqui.security.UserGroup userGroupId="DARPAN_TENANT_USER" description="Can view tenant pages, upload files, and run reconciliation for associated tenants" groupTypeEnumId="UgtDarpanPermission"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.UserGroup|userGroupId=DARPAN_USER`
- Element: `moqui.security.UserGroup`

```xml
<moqui.security.UserGroup userGroupId="DARPAN_USER" description="Darpan application users"/>
```

### Added in `data/SystemMessageRemoteSeedData.xml`

- Record: `moqui.service.message.SystemMessageRemote|systemMessageRemoteId=HOTWAX_ORDERS_API`
- Element: `moqui.service.message.SystemMessageRemote`

```xml
<moqui.service.message.SystemMessageRemote systemMessageRemoteId="HOTWAX_ORDERS_API" description="Orders API" sendUrl="{baseUrl}/rest/s1/oms/orders" sendServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrders"/>
```

### Added in `data/SystemMessageRemoteSeedData.xml`

- Record: `moqui.service.message.SystemMessageRemote|systemMessageRemoteId=SHOPIFY_REMOTE`
- Element: `moqui.service.message.SystemMessageRemote`

```xml
<moqui.service.message.SystemMessageRemote systemMessageRemoteId="SHOPIFY_REMOTE" description="Admin GraphQL Orders" sendUrl="https://{shop}.myshopify.com/admin/api/{apiVersion}/graphql.json" sendServiceName="facade.ShopifyFacadeServices.execute#ShopifyGraphql"/>
```

## Recommended operator review

- Confirm every candidate record truly needs to be loaded for the target environment.
- Keep final upgrade records reflected in the appropriate generic source data file, such as a type, security, mapping, job, or system-message seed file.
- Prefer keeping changes in the existing domain seed file unless the release needs a distinct generic setup bundle.
- State the final operator action in `release-notes.md` and `release-checklist.md`.
