# Upgrade Data Review For Darpan 1.1.0

## Review verdict (2026-07-27)

All 6 candidate records below are approved for the 1.1.0 load target: 2 enums
(`AUT_SRC_DB`, `DATABASE` — database source type), 2 security records
(`RuleSetCompareSourceKeyField` artifact-group membership + tenant entity filter —
composite keys), and 2 modified `SourceSystemConnector` rows (OMS keep-fields
projection; Shopify `lookupServiceName` for the verification pass). Each record
traces to its generic source seed file; the `data/test/` fixture changes in the
compare range are correctly excluded. The Shopify connector row requires
`shopify-darpan v0.4.0` and the OMS row requires `darpan-hotwax v0.4.0` at runtime —
both tagged with this release.

## Scope

- Backend compare range: `v1.0.3..main`
- Data directory reviewed: `data`
- Generic source data files are the source of truth for release upgrade data.
- This report lists candidate seed/config records that were added or modified in generic source data files between the compared refs.
- Do not author records directly in `upgrade-data.xml`; add or update the appropriate `runtime/component/darpan/data/*.xml` file and regenerate.

## Candidate records

### Added in `data/AutomationSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUT_SRC_DB|enumTypeId=AutomationSourceType|enumCode=DB`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUT_SRC_DB" enumTypeId="AutomationSourceType" enumCode="DB" description="Database source" sequenceNum="3"/>
```

### Added in `data/DarpanSystemSourceSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=DATABASE|enumTypeId=DarpanSystemSource|enumCode=DATABASE`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="DATABASE" enumTypeId="DarpanSystemSource" enumCode="DATABASE" description="Database" sequenceNum="5"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.ArtifactGroupMember|artifactGroupId=DARPAN_APP|artifactTypeEnumId=AT_ENTITY|artifactName=darpan.rule.RuleSetCompareSourceKeyField`
- Element: `moqui.security.ArtifactGroupMember`

```xml
<moqui.security.ArtifactGroupMember artifactGroupId="DARPAN_APP" artifactName="darpan.rule.RuleSetCompareSourceKeyField" artifactTypeEnumId="AT_ENTITY" inheritAuthz="Y"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_RULE_SOURCE_KEY_FIELD|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_RULE_SOURCE_KEY_FIELD" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.rule.RuleSetCompareSourceKeyField" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Modified in `data/SourceSystemConnectorSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnector|systemEnumId=OMS|remoteId=HOTWAX_ORDERS_API`
- Element: `darpan.reconciliation.SourceSystemConnector`

```xml
<darpan.reconciliation.SourceSystemConnector systemEnumId="OMS" extractServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrders" dateFromParameterName="windowStart" dateToParameterName="windowEnd" expectedSourceConfigType="HOTWAX_OMS_REST" configParameterName="omsRestSourceConfigId" configEntityName="darpan.hotwax.HotWaxOmsRestSourceConfig" remoteId="HOTWAX_ORDERS_API" endpointLabel="Orders API" sendUrlTemplate="{baseUrl}/rest/s1/oms/orders" remoteSendServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrders" systemAliases="OMS,HOTWAX,DAR_SYS_OMS" preserveWindowInstants="N" keepFieldsParameterName="keepRecordFields" keepFieldsBase="orderId,orderName,externalId,grandTotal,orderDate,statusId" enabled="Y"/>
```

### Modified in `data/SourceSystemConnectorSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnector|systemEnumId=SHOPIFY|remoteId=SHOPIFY_REMOTE`
- Element: `darpan.reconciliation.SourceSystemConnector`

```xml
<darpan.reconciliation.SourceSystemConnector systemEnumId="SHOPIFY" extractServiceName="reconciliation.ShopifyOrderExtractionServices.extract#ShopifyOrders" dateFromParameterName="windowStart" dateToParameterName="windowEnd" expectedSourceConfigType="SHOPIFY_AUTH" configParameterName="shopifyAuthConfigId" configEntityName="darpan.shopify.ShopifyAuthConfig" remoteId="SHOPIFY_REMOTE" endpointLabel="Admin GraphQL Orders" sendUrlTemplate="https://{shop}.myshopify.com/admin/api/{apiVersion}/graphql.json" remoteSendServiceName="facade.ShopifyFacadeServices.execute#ShopifyGraphql" systemAliases="SHOPIFY,DAR_SYS_SHOPIFY" preserveWindowInstants="Y" lookupServiceName="reconciliation.ShopifyOrderExtractionServices.lookup#ShopifyOrderIds" enabled="Y"/>
```

## Recommended operator review

- Confirm every candidate record truly needs to be loaded for the target environment.
- Keep final upgrade records reflected in the appropriate generic source data file, such as a type, security, mapping, job, or system-message seed file.
- Prefer keeping changes in the existing domain seed file unless the release needs a distinct generic setup bundle.
- State the final operator action in `release-notes.md` and `release-checklist.md`.
