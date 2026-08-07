# Upgrade Data Review For Darpan 1.4.0

## Scope

- Backend compare range: `v1.3.0..HEAD`
- Data directory reviewed: `data`
- Generic source data files are the source of truth for release upgrade data.
- This report lists candidate seed/config records that were added or modified in generic source data files between the compared refs.
- Do not author records directly in `upgrade-data.xml`; add or update the appropriate `runtime/component/darpan/data/*.xml` file and regenerate.

## Candidate records

### Added in `data/AutomationSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUT_WIN_STATE|enumTypeId=AutomationRelWindow|enumCode=STATE_BASED`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUT_WIN_STATE" enumTypeId="AutomationRelWindow" enumCode="STATE_BASED" description="State based (no date window)" sequenceNum="8"/>
```

### Added in `data/DarpanSystemSourceSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=OMS_TRANSFER_ORDERS|enumTypeId=DarpanSystemSource|enumCode=HOTWAX_TRANSFER_ORDERS`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="OMS_TRANSFER_ORDERS" enumTypeId="DarpanSystemSource" enumCode="HOTWAX_TRANSFER_ORDERS" description="HotWax Transfer Orders" sequenceNum="6"/>
```

### Added in `data/SourceSystemConnectorSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnector|systemEnumId=OMS_TRANSFER_ORDERS|remoteId=HOTWAX_ORDERS_API`
- Element: `darpan.reconciliation.SourceSystemConnector`

```xml
<darpan.reconciliation.SourceSystemConnector systemEnumId="OMS_TRANSFER_ORDERS" extractServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsTransferOrders" dateFromParameterName="windowStart" dateToParameterName="windowEnd" expectedSourceConfigType="HOTWAX_OMS_REST_TRANSFER" configParameterName="omsRestSourceConfigId" configEntityName="darpan.hotwax.HotWaxOmsRestSourceConfig" remoteId="HOTWAX_ORDERS_API" endpointLabel="Orders API (Transfer Orders)" sendUrlTemplate="{baseUrl}/rest/s1/oms/orders" remoteSendServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsTransferOrders" systemAliases="OMS_TRANSFER_ORDERS,HOTWAX_TRANSFER_ORDERS,DAR_SYS_OMS_TO" preserveWindowInstants="N" keepFieldsParameterName="keepRecordFields" keepFieldsBase="orderId,orderName,orderDate,statusId,originFacilityId,grandTotal,currencyUom" filterParameterName="sourceFilters" windowFieldName="orderDate" supportsStateExtract="Y" statusParameterName="orderStatusIds" healthCheckServiceName="facade.HotWaxOmsFacadeServices.probe#HotWaxOmsConnection" enabled="Y"/>
```

## Recommended operator review

- Confirm every candidate record truly needs to be loaded for the target environment.
- Keep final upgrade records reflected in the appropriate generic source data file, such as a type, security, mapping, job, or system-message seed file.
- Prefer keeping changes in the existing domain seed file unless the release needs a distinct generic setup bundle.
- State the final operator action in `release-notes.md` and `release-checklist.md`.
