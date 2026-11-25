# Upgrade Data Review For Darpan 3.4.8

## Scope

- Backend compare range: `v3.4.7..HEAD`
- Data directory reviewed: `data`
- Generic source data files are the source of truth for release upgrade data.
- This report lists candidate seed/config records that were added or modified in generic source data files between the compared refs.
- Do not author records directly in `upgrade-data.xml`; add or update the appropriate `runtime/component/darpan/data/*.xml` file and regenerate.

## Candidate records

### Modified in `data/SourceSystemConnectorSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnector|systemEnumId=SHOPIFY|remoteId=SHOPIFY_REMOTE`
- Element: `darpan.reconciliation.SourceSystemConnector`

```xml
<darpan.reconciliation.SourceSystemConnector systemEnumId="SHOPIFY" extractServiceName="reconciliation.ShopifyOrderExtractionServices.extract#ShopifyOrders" dateFromParameterName="windowStart" dateToParameterName="windowEnd" expectedSourceConfigType="SHOPIFY_AUTH" configParameterName="shopifyAuthConfigId" configEntityName="darpan.shopify.ShopifyAuthConfig" remoteId="SHOPIFY_REMOTE" endpointLabel="Orders" sendUrlTemplate="https://{shop}.myshopify.com/admin/api/{apiVersion}/graphql.json" remoteSendServiceName="facade.ShopifyFacadeServices.execute#ShopifyGraphql" systemAliases="SHOPIFY,DAR_SYS_SHOPIFY" preserveWindowInstants="Y" lookupServiceName="reconciliation.ShopifyOrderExtractionServices.lookup#ShopifyOrderIds" exchangeStateLookupServiceName="reconciliation.ShopifyOrderExtractionServices.lookup#ShopifyOrderExchangeState" exchangeSweepServiceName="reconciliation.ShopifyOrderExtractionServices.lookup#ShopifyExchangeSweep" healthCheckServiceName="facade.ShopifyFacadeServices.probe#ShopifyAuthConnection" filterParameterName="sourceFilters" evidenceFieldsJson='{"system":"Shopify","state":"displayFulfillmentStatus","stateLabels":{"FULFILLED":"Fulfilled","UNFULFILLED":"Unfulfilled","PARTIALLY_FULFILLED":"Partially fulfilled","RESTOCKED":"Restocked","ON_HOLD":"On hold","IN_PROGRESS":"In progress","SCHEDULED":"Scheduled","PENDING_FULFILLMENT":"Pending fulfillment","OPEN":"Open"},"facts":["name","displayFinancialStatus","sourceName"]}' extractOptionsJson='[{"name":"withLineItems","label":"Order lines","type":"boolean"}]' enabled="Y"/>
```

## Recommended operator review

- Confirm every candidate record truly needs to be loaded for the target environment.
- Keep final upgrade records reflected in the appropriate generic source data file, such as a type, security, mapping, job, or system-message seed file.
- Prefer keeping changes in the existing domain seed file unless the release needs a distinct generic setup bundle.
- State the final operator action in `release-notes.md` and `release-checklist.md`.
