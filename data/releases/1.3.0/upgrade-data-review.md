# Upgrade Data Review For Darpan 1.3.0

## Scope

- Backend compare range: `v1.2.0..HEAD`
- Data directory reviewed: `data`
- Generic source data files are the source of truth for release upgrade data.
- This report lists candidate seed/config records that were added or modified in generic source data files between the compared refs.
- Do not author records directly in `upgrade-data.xml`; add or update the appropriate `runtime/component/darpan/data/*.xml` file and regenerate.

## Candidate records

### Modified in `data/SourceSystemConnectorSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnector|systemEnumId=OMS|remoteId=HOTWAX_ORDERS_API`
- Element: `darpan.reconciliation.SourceSystemConnector`

```xml
<darpan.reconciliation.SourceSystemConnector systemEnumId="OMS" extractServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrders" dateFromParameterName="windowStart" dateToParameterName="windowEnd" expectedSourceConfigType="HOTWAX_OMS_REST" configParameterName="omsRestSourceConfigId" configEntityName="darpan.hotwax.HotWaxOmsRestSourceConfig" remoteId="HOTWAX_ORDERS_API" endpointLabel="Orders API" sendUrlTemplate="{baseUrl}/rest/s1/oms/orders" remoteSendServiceName="reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrders" systemAliases="OMS,HOTWAX,DAR_SYS_OMS" preserveWindowInstants="N" keepFieldsParameterName="keepRecordFields" keepFieldsBase="orderId,orderName,externalId,grandTotal,orderDate,statusId" filterParameterName="sourceFilters" pairLookupServiceName="reconciliation.HotWaxOmsExtractionServices.lookup#HotWaxOmsOrdersByExternalId" healthCheckServiceName="facade.HotWaxOmsFacadeServices.probe#HotWaxOmsConnection" enabled="Y"/>
```

## Recommended operator review

- Confirm every candidate record truly needs to be loaded for the target environment.
- Keep final upgrade records reflected in the appropriate generic source data file, such as a type, security, mapping, job, or system-message seed file.
- Prefer keeping changes in the existing domain seed file unless the release needs a distinct generic setup bundle.
- State the final operator action in `release-notes.md` and `release-checklist.md`.
