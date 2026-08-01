# Upgrade Data Review For Darpan 1.2.0

## Scope

- Backend compare range: `v1.1.0..HEAD`
- Data directory reviewed: `data`
- Generic source data files are the source of truth for release upgrade data.
- This report lists candidate seed/config records that were added or modified in generic source data files between the compared refs.
- Do not author records directly in `upgrade-data.xml`; add or update the appropriate `runtime/component/darpan/data/*.xml` file and regenerate.

## Candidate records

### Added in `data/AutomationSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUT_STAT_CANCELLED|enumTypeId=AutomationExecStatus|enumCode=CANCELLED`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUT_STAT_CANCELLED" enumTypeId="AutomationExecStatus" enumCode="CANCELLED" description="Cancelled by an operator" sequenceNum="8"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=darpan.notification.defaultChatSpaceId|enumTypeId=UserPreferenceKey`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="darpan.notification.defaultChatSpaceId" description="Default Google Chat space per tenant (JSON map companyUserGroupId to chatSpaceId)" enumTypeId="UserPreferenceKey"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.ArtifactAuthz|artifactAuthzId=DARPAN_ADMIN_API_ADMIN|userGroupId=ADMIN|artifactGroupId=DARPAN_ADMIN_API|authzTypeEnumId=AUTHZT_ALWAYS|authzActionEnumId=AUTHZA_ALL`
- Element: `moqui.security.ArtifactAuthz`

```xml
<moqui.security.ArtifactAuthz artifactAuthzId="DARPAN_ADMIN_API_ADMIN" userGroupId="ADMIN" artifactGroupId="DARPAN_ADMIN_API" authzTypeEnumId="AUTHZT_ALWAYS" authzActionEnumId="AUTHZA_ALL"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.ArtifactAuthz|artifactAuthzId=DARPAN_ADMIN_API_SUPER_ADMIN|userGroupId=DARPAN_SUPER_ADMIN|artifactGroupId=DARPAN_ADMIN_API|authzTypeEnumId=AUTHZT_ALWAYS|authzActionEnumId=AUTHZA_ALL`
- Element: `moqui.security.ArtifactAuthz`

```xml
<moqui.security.ArtifactAuthz artifactAuthzId="DARPAN_ADMIN_API_SUPER_ADMIN" userGroupId="DARPAN_SUPER_ADMIN" artifactGroupId="DARPAN_ADMIN_API" authzTypeEnumId="AUTHZT_ALWAYS" authzActionEnumId="AUTHZA_ALL"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.ArtifactGroupMember|artifactGroupId=DARPAN_ADMIN_API|artifactTypeEnumId=AT_SERVICE|artifactName=admin\..*`
- Element: `moqui.security.ArtifactGroupMember`

```xml
<moqui.security.ArtifactGroupMember artifactGroupId="DARPAN_ADMIN_API" artifactName="admin\..*" artifactTypeEnumId="AT_SERVICE" nameIsPattern="Y" inheritAuthz="Y"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.ArtifactGroupMember|artifactGroupId=DARPAN_APP|artifactTypeEnumId=AT_ENTITY|artifactName=darpan.reconciliation.ReconciliationRunNotifySubscription`
- Element: `moqui.security.ArtifactGroupMember`

```xml
<moqui.security.ArtifactGroupMember artifactGroupId="DARPAN_APP" artifactName="darpan.reconciliation.ReconciliationRunNotifySubscription" artifactTypeEnumId="AT_ENTITY" inheritAuthz="Y"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.ArtifactGroupMember|artifactGroupId=DARPAN_APP|artifactTypeEnumId=AT_ENTITY|artifactName=darpan.reconciliation.TenantChatSpace`
- Element: `moqui.security.ArtifactGroupMember`

```xml
<moqui.security.ArtifactGroupMember artifactGroupId="DARPAN_APP" artifactName="darpan.reconciliation.TenantChatSpace" artifactTypeEnumId="AT_ENTITY" inheritAuthz="Y"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.ArtifactGroup|artifactGroupId=DARPAN_ADMIN_API`
- Element: `moqui.security.ArtifactGroup`

```xml
<moqui.security.ArtifactGroup artifactGroupId="DARPAN_ADMIN_API" description="Darpan platform-admin APIs (admin.* services) — super-admin only, cross-tenant."/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_TENANT_CHAT_SPACE|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_TENANT_CHAT_SPACE" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.TenantChatSpace" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.UserGroupPermission|userGroupId=DARPAN_SUPER_ADMIN|userPermissionId=ADMIN_PASSWORD`
- Element: `moqui.security.UserGroupPermission`

```xml
<moqui.security.UserGroupPermission userGroupId="DARPAN_SUPER_ADMIN" userPermissionId="ADMIN_PASSWORD" fromDate="2026-07-28 00:00:00.000"/>
```

### Modified in `data/SourceSystemConnectorSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnector|systemEnumId=OMS|remoteId=HOTWAX_ORDERS_API`
- Element: `darpan.reconciliation.SourceSystemConnector`

```xml
<darpan.reconciliation.SourceSystemConnector systemEnumId="OMS" extractServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrders" dateFromParameterName="windowStart" dateToParameterName="windowEnd" expectedSourceConfigType="HOTWAX_OMS_REST" configParameterName="omsRestSourceConfigId" configEntityName="darpan.hotwax.HotWaxOmsRestSourceConfig" remoteId="HOTWAX_ORDERS_API" endpointLabel="Orders API" sendUrlTemplate="{baseUrl}/rest/s1/oms/orders" remoteSendServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrders" systemAliases="OMS,HOTWAX,DAR_SYS_OMS" preserveWindowInstants="N" keepFieldsParameterName="keepRecordFields" keepFieldsBase="orderId,orderName,externalId,grandTotal,orderDate,statusId" pairLookupServiceName="reconciliation.HotWaxOmsExtractionServices.lookup#HotWaxOmsOrdersByExternalId" healthCheckServiceName="facade.HotWaxOmsFacadeServices.probe#HotWaxOmsConnection" enabled="Y"/>
```

### Modified in `data/SourceSystemConnectorSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnector|systemEnumId=SHOPIFY|remoteId=SHOPIFY_REMOTE`
- Element: `darpan.reconciliation.SourceSystemConnector`

```xml
<darpan.reconciliation.SourceSystemConnector systemEnumId="SHOPIFY" extractServiceName="reconciliation.ShopifyOrderExtractionServices.extract#ShopifyOrders" dateFromParameterName="windowStart" dateToParameterName="windowEnd" expectedSourceConfigType="SHOPIFY_AUTH" configParameterName="shopifyAuthConfigId" configEntityName="darpan.shopify.ShopifyAuthConfig" remoteId="SHOPIFY_REMOTE" endpointLabel="Admin GraphQL Orders" sendUrlTemplate="https://{shop}.myshopify.com/admin/api/{apiVersion}/graphql.json" remoteSendServiceName="facade.ShopifyFacadeServices.execute#ShopifyGraphql" systemAliases="SHOPIFY,DAR_SYS_SHOPIFY" preserveWindowInstants="Y" lookupServiceName="reconciliation.ShopifyOrderExtractionServices.lookup#ShopifyOrderIds" exchangeStateLookupServiceName="reconciliation.ShopifyOrderExtractionServices.lookup#ShopifyOrderExchangeState" exchangeSweepServiceName="reconciliation.ShopifyOrderExtractionServices.lookup#ShopifyExchangeSweep" healthCheckServiceName="facade.ShopifyFacadeServices.probe#ShopifyAuthConnection" enabled="Y"/>
```

## Recommended operator review

- Confirm every candidate record truly needs to be loaded for the target environment.
- Keep final upgrade records reflected in the appropriate generic source data file, such as a type, security, mapping, job, or system-message seed file.
- Prefer keeping changes in the existing domain seed file unless the release needs a distinct generic setup bundle.
- State the final operator action in `release-notes.md` and `release-checklist.md`.
