# Upgrade Data Review For Darpan 2.1.1

## Scope

- Backend compare range: `v2.1.0..HEAD`
- Data directory reviewed: `data`
- Generic source data files are the source of truth for release upgrade data.
- This report lists candidate seed/config records that were added or modified in generic source data files between the compared refs.
- Do not author records directly in `upgrade-data.xml`; add or update the appropriate `runtime/component/darpan/data/*.xml` file and regenerate.

## Decision

- Load target (`data/upgrade-data.xml`, mirrored here): **5 `darpan-seed` records**, rebuilt from the generator's
  candidates, not edited forward from 2.1.0's file.
  - 4 `moqui.security.EntityFilter` rows from `SecuritySeedData.xml`: `DARPAN_SCOPE_AUTOMATION_STREAK_BADGE`,
    `DARPAN_SCOPE_TENANT_STREAK_QUEST` (DAR-BE-108), `DARPAN_SCOPE_TENANT_STREAK_FACT` (DAR-BE-138),
    `DARPAN_SCOPE_TENANT_CONCLUSION` (DAR-BE-135; the entity shipped in 2.1.0 without one). Without them an existing
    database reads those four entities unscoped by tenant.
  - 1 modified `darpan.reconciliation.SourceSystemConnector` (`SHOPIFY_RETURN_REFS`) from
    `SourceSystemConnectorSeedData.xml`: adds `extractOptionsJson` declaring the `completedReturns` option (DAR-BE-137).
- Excluded by design: every candidate from `data/RailsAuditData.xml` (`darpan-tenant-config`, RAILS's audit R1-R6).
  It must not load on every instance; RAILS's prod config already arrived through the admin transfer (DAR-CLIENT-005).
- No record ordering hazard: the filters' set (`DARPAN_ACTIVE_COMPANY_SCOPE`) exists since 1.x, the connector row is
  an update of an existing row.
- `data/releases/2.1.0/upgrade-data.xml` is byte-identical to `v2.1.0`'s `data/upgrade-data.xml`, so the archive step
  was a no-op.

## Candidate records

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditCheck|checkId=RAILS_CHK_COMPLETED_BILLED|companyUserGroupId=RAILS|savedRunId=RAILS_OMS_NS_LINE_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="RAILS_CHK_COMPLETED_BILLED" otherSideDateField="tranDate" companyUserGroupId="RAILS" checkCode="completed-not-billed" label="HotWax completed items against NetSuite billed lines" savedRunId="RAILS_OMS_NS_LINE_RS" file1RecordType="orderItem" file2RecordType="salesOrderLine" graceDays="1" matchedBy="HotWax order id + item seq id (custbody_hc_order_id + custcol_hc_order_line_id)" fixOwner="HotWax Support" fixWhereEnumId="NETSUITE" headlineTemplate="Item {recordKey} is completed in HotWax and not billed in NetSuite" fixActionTemplate="Bill the NetSuite line for item {recordKey}, or sync the order if NetSuite does not have it." passesWhenTemplate="NetSuite has billed the line for item {recordKey}."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditCheck|checkId=RAILS_CHK_COMPLETED_NOT_FULFILLED_CANADA|companyUserGroupId=RAILS|savedRunId=RAILS_SHOP_OMS_CANADA_RS|fixWhereEnumId=SHOPIFY`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="RAILS_CHK_COMPLETED_NOT_FULFILLED_CANADA" otherSideDateField="createdAt" companyUserGroupId="RAILS" checkCode="completed-orders-not-fulfilled-in-shopify-canada" label="Rails Canada HotWax orders completed that Shopify has not fulfilled" savedRunId="RAILS_SHOP_OMS_CANADA_RS" file1RecordType="order" file2RecordType="order" graceDays="1" matchedBy="Shopify order id = HotWax externalId" fixOwner="HotWax Support" fixWhereEnumId="SHOPIFY" headlineTemplate="Shopify order {recordKey} is completed in HotWax and not fulfilled in Shopify" fixActionTemplate="Sync the HotWax fulfillment for order {recordKey} to Shopify, so Shopify shows it fulfilled." passesWhenTemplate="Shopify shows order {recordKey} fulfilled."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditCheck|checkId=RAILS_CHK_COMPLETED_NOT_FULFILLED_EU|companyUserGroupId=RAILS|savedRunId=RAILS_SHOP_OMS_EU_RS|fixWhereEnumId=SHOPIFY`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="RAILS_CHK_COMPLETED_NOT_FULFILLED_EU" otherSideDateField="createdAt" companyUserGroupId="RAILS" checkCode="completed-orders-not-fulfilled-in-shopify-eu" label="Rails EU HotWax orders completed that Shopify has not fulfilled" savedRunId="RAILS_SHOP_OMS_EU_RS" file1RecordType="order" file2RecordType="order" graceDays="1" matchedBy="Shopify order id = HotWax externalId" fixOwner="HotWax Support" fixWhereEnumId="SHOPIFY" headlineTemplate="Shopify order {recordKey} is completed in HotWax and not fulfilled in Shopify" fixActionTemplate="Sync the HotWax fulfillment for order {recordKey} to Shopify, so Shopify shows it fulfilled." passesWhenTemplate="Shopify shows order {recordKey} fulfilled."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditCheck|checkId=RAILS_CHK_COMPLETED_NOT_FULFILLED_OUTLET|companyUserGroupId=RAILS|savedRunId=RAILS_SHOP_OMS_OUTLET_RS|fixWhereEnumId=SHOPIFY`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="RAILS_CHK_COMPLETED_NOT_FULFILLED_OUTLET" otherSideDateField="createdAt" companyUserGroupId="RAILS" checkCode="completed-orders-not-fulfilled-in-shopify-outlet" label="Rails Outlet HotWax orders completed that Shopify has not fulfilled" savedRunId="RAILS_SHOP_OMS_OUTLET_RS" file1RecordType="order" file2RecordType="order" graceDays="1" matchedBy="Shopify order id = HotWax externalId" fixOwner="HotWax Support" fixWhereEnumId="SHOPIFY" headlineTemplate="Shopify order {recordKey} is completed in HotWax and not fulfilled in Shopify" fixActionTemplate="Sync the HotWax fulfillment for order {recordKey} to Shopify, so Shopify shows it fulfilled." passesWhenTemplate="Shopify shows order {recordKey} fulfilled."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditCheck|checkId=RAILS_CHK_COMPLETED_NOT_FULFILLED_UK|companyUserGroupId=RAILS|savedRunId=RAILS_SHOP_OMS_UK_RS|fixWhereEnumId=SHOPIFY`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="RAILS_CHK_COMPLETED_NOT_FULFILLED_UK" otherSideDateField="createdAt" companyUserGroupId="RAILS" checkCode="completed-orders-not-fulfilled-in-shopify-uk" label="Rails UK HotWax orders completed that Shopify has not fulfilled" savedRunId="RAILS_SHOP_OMS_UK_RS" file1RecordType="order" file2RecordType="order" graceDays="1" matchedBy="Shopify order id = HotWax externalId" fixOwner="HotWax Support" fixWhereEnumId="SHOPIFY" headlineTemplate="Shopify order {recordKey} is completed in HotWax and not fulfilled in Shopify" fixActionTemplate="Sync the HotWax fulfillment for order {recordKey} to Shopify, so Shopify shows it fulfilled." passesWhenTemplate="Shopify shows order {recordKey} fulfilled."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditCheck|checkId=RAILS_CHK_COMPLETED_NOT_FULFILLED_USA|companyUserGroupId=RAILS|savedRunId=RAILS_SHOP_OMS_USA_RS|fixWhereEnumId=SHOPIFY`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="RAILS_CHK_COMPLETED_NOT_FULFILLED_USA" otherSideDateField="createdAt" companyUserGroupId="RAILS" checkCode="completed-orders-not-fulfilled-in-shopify-usa" label="Rails US HotWax orders completed that Shopify has not fulfilled" savedRunId="RAILS_SHOP_OMS_USA_RS" file1RecordType="order" file2RecordType="order" graceDays="1" matchedBy="Shopify order id = HotWax externalId" fixOwner="HotWax Support" fixWhereEnumId="SHOPIFY" headlineTemplate="Shopify order {recordKey} is completed in HotWax and not fulfilled in Shopify" fixActionTemplate="Sync the HotWax fulfillment for order {recordKey} to Shopify, so Shopify shows it fulfilled." passesWhenTemplate="Shopify shows order {recordKey} fulfilled."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditCheck|checkId=RAILS_CHK_NOT_IN_OMS_CANADA|companyUserGroupId=RAILS|savedRunId=RAILS_SHOP_OMS_CANADA_RS|fixWhereEnumId=OMS`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="RAILS_CHK_NOT_IN_OMS_CANADA" otherSideDateField="createdAt" companyUserGroupId="RAILS" checkCode="shopify-orders-not-in-hotwax-canada" label="Rails Canada Shopify orders HotWax does not have" savedRunId="RAILS_SHOP_OMS_CANADA_RS" file1RecordType="order" file2RecordType="order" graceDays="1" matchedBy="Shopify order id = HotWax externalId" fixOwner="HotWax Support" fixWhereEnumId="OMS" headlineTemplate="Shopify order {recordKey} is not in HotWax" fixActionTemplate="Import Shopify order {recordKey} into HotWax." passesWhenTemplate="HotWax has Shopify order {recordKey}."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditCheck|checkId=RAILS_CHK_NOT_IN_OMS_EU|companyUserGroupId=RAILS|savedRunId=RAILS_SHOP_OMS_EU_RS|fixWhereEnumId=OMS`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="RAILS_CHK_NOT_IN_OMS_EU" otherSideDateField="createdAt" companyUserGroupId="RAILS" checkCode="shopify-orders-not-in-hotwax-eu" label="Rails EU Shopify orders HotWax does not have" savedRunId="RAILS_SHOP_OMS_EU_RS" file1RecordType="order" file2RecordType="order" graceDays="1" matchedBy="Shopify order id = HotWax externalId" fixOwner="HotWax Support" fixWhereEnumId="OMS" headlineTemplate="Shopify order {recordKey} is not in HotWax" fixActionTemplate="Import Shopify order {recordKey} into HotWax." passesWhenTemplate="HotWax has Shopify order {recordKey}."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditCheck|checkId=RAILS_CHK_NOT_IN_OMS_OUTLET|companyUserGroupId=RAILS|savedRunId=RAILS_SHOP_OMS_OUTLET_RS|fixWhereEnumId=OMS`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="RAILS_CHK_NOT_IN_OMS_OUTLET" otherSideDateField="createdAt" companyUserGroupId="RAILS" checkCode="shopify-orders-not-in-hotwax-outlet" label="Rails Outlet Shopify orders HotWax does not have" savedRunId="RAILS_SHOP_OMS_OUTLET_RS" file1RecordType="order" file2RecordType="order" graceDays="1" matchedBy="Shopify order id = HotWax externalId" fixOwner="HotWax Support" fixWhereEnumId="OMS" headlineTemplate="Shopify order {recordKey} is not in HotWax" fixActionTemplate="Import Shopify order {recordKey} into HotWax." passesWhenTemplate="HotWax has Shopify order {recordKey}."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditCheck|checkId=RAILS_CHK_NOT_IN_OMS_UK|companyUserGroupId=RAILS|savedRunId=RAILS_SHOP_OMS_UK_RS|fixWhereEnumId=OMS`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="RAILS_CHK_NOT_IN_OMS_UK" otherSideDateField="createdAt" companyUserGroupId="RAILS" checkCode="shopify-orders-not-in-hotwax-uk" label="Rails UK Shopify orders HotWax does not have" savedRunId="RAILS_SHOP_OMS_UK_RS" file1RecordType="order" file2RecordType="order" graceDays="1" matchedBy="Shopify order id = HotWax externalId" fixOwner="HotWax Support" fixWhereEnumId="OMS" headlineTemplate="Shopify order {recordKey} is not in HotWax" fixActionTemplate="Import Shopify order {recordKey} into HotWax." passesWhenTemplate="HotWax has Shopify order {recordKey}."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditCheck|checkId=RAILS_CHK_NOT_IN_OMS_USA|companyUserGroupId=RAILS|savedRunId=RAILS_SHOP_OMS_USA_RS|fixWhereEnumId=OMS`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="RAILS_CHK_NOT_IN_OMS_USA" otherSideDateField="createdAt" companyUserGroupId="RAILS" checkCode="shopify-orders-not-in-hotwax-usa" label="Rails US Shopify orders HotWax does not have" savedRunId="RAILS_SHOP_OMS_USA_RS" file1RecordType="order" file2RecordType="order" graceDays="1" matchedBy="Shopify order id = HotWax externalId" fixOwner="HotWax Support" fixWhereEnumId="OMS" headlineTemplate="Shopify order {recordKey} is not in HotWax" fixActionTemplate="Import Shopify order {recordKey} into HotWax." passesWhenTemplate="HotWax has Shopify order {recordKey}."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditCheck|checkId=RAILS_CHK_PAID_NO_OPP_CANADA|companyUserGroupId=RAILS|savedRunId=RAILS_SHOP_OMS_CANADA_RS|fixWhereEnumId=OMS`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="RAILS_CHK_PAID_NO_OPP_CANADA" otherSideDateField="createdAt" companyUserGroupId="RAILS" checkCode="paid-orders-without-a-payment-in-hotwax-canada" label="Rails Canada Shopify orders paid whose HotWax order has no payment" savedRunId="RAILS_SHOP_OMS_CANADA_RS" file1RecordType="order" file2RecordType="order" graceDays="1" matchedBy="Shopify order id = HotWax externalId" fixOwner="HotWax Support" fixWhereEnumId="OMS" headlineTemplate="Shopify order {recordKey} is paid and its HotWax order has no payment" fixActionTemplate="Sync the Shopify payment for order {recordKey} to HotWax, so the HotWax order carries its payment preference." passesWhenTemplate="The HotWax order for Shopify order {recordKey} carries a payment."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditCheck|checkId=RAILS_CHK_PAID_NO_OPP_EU|companyUserGroupId=RAILS|savedRunId=RAILS_SHOP_OMS_EU_RS|fixWhereEnumId=OMS`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="RAILS_CHK_PAID_NO_OPP_EU" otherSideDateField="createdAt" companyUserGroupId="RAILS" checkCode="paid-orders-without-a-payment-in-hotwax-eu" label="Rails EU Shopify orders paid whose HotWax order has no payment" savedRunId="RAILS_SHOP_OMS_EU_RS" file1RecordType="order" file2RecordType="order" graceDays="1" matchedBy="Shopify order id = HotWax externalId" fixOwner="HotWax Support" fixWhereEnumId="OMS" headlineTemplate="Shopify order {recordKey} is paid and its HotWax order has no payment" fixActionTemplate="Sync the Shopify payment for order {recordKey} to HotWax, so the HotWax order carries its payment preference." passesWhenTemplate="The HotWax order for Shopify order {recordKey} carries a payment."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditCheck|checkId=RAILS_CHK_PAID_NO_OPP_OUTLET|companyUserGroupId=RAILS|savedRunId=RAILS_SHOP_OMS_OUTLET_RS|fixWhereEnumId=OMS`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="RAILS_CHK_PAID_NO_OPP_OUTLET" otherSideDateField="createdAt" companyUserGroupId="RAILS" checkCode="paid-orders-without-a-payment-in-hotwax-outlet" label="Rails Outlet Shopify orders paid whose HotWax order has no payment" savedRunId="RAILS_SHOP_OMS_OUTLET_RS" file1RecordType="order" file2RecordType="order" graceDays="1" matchedBy="Shopify order id = HotWax externalId" fixOwner="HotWax Support" fixWhereEnumId="OMS" headlineTemplate="Shopify order {recordKey} is paid and its HotWax order has no payment" fixActionTemplate="Sync the Shopify payment for order {recordKey} to HotWax, so the HotWax order carries its payment preference." passesWhenTemplate="The HotWax order for Shopify order {recordKey} carries a payment."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditCheck|checkId=RAILS_CHK_PAID_NO_OPP_UK|companyUserGroupId=RAILS|savedRunId=RAILS_SHOP_OMS_UK_RS|fixWhereEnumId=OMS`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="RAILS_CHK_PAID_NO_OPP_UK" otherSideDateField="createdAt" companyUserGroupId="RAILS" checkCode="paid-orders-without-a-payment-in-hotwax-uk" label="Rails UK Shopify orders paid whose HotWax order has no payment" savedRunId="RAILS_SHOP_OMS_UK_RS" file1RecordType="order" file2RecordType="order" graceDays="1" matchedBy="Shopify order id = HotWax externalId" fixOwner="HotWax Support" fixWhereEnumId="OMS" headlineTemplate="Shopify order {recordKey} is paid and its HotWax order has no payment" fixActionTemplate="Sync the Shopify payment for order {recordKey} to HotWax, so the HotWax order carries its payment preference." passesWhenTemplate="The HotWax order for Shopify order {recordKey} carries a payment."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditCheck|checkId=RAILS_CHK_PAID_NO_OPP_USA|companyUserGroupId=RAILS|savedRunId=RAILS_SHOP_OMS_USA_RS|fixWhereEnumId=OMS`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="RAILS_CHK_PAID_NO_OPP_USA" otherSideDateField="createdAt" companyUserGroupId="RAILS" checkCode="paid-orders-without-a-payment-in-hotwax-usa" label="Rails US Shopify orders paid whose HotWax order has no payment" savedRunId="RAILS_SHOP_OMS_USA_RS" file1RecordType="order" file2RecordType="order" graceDays="1" matchedBy="Shopify order id = HotWax externalId" fixOwner="HotWax Support" fixWhereEnumId="OMS" headlineTemplate="Shopify order {recordKey} is paid and its HotWax order has no payment" fixActionTemplate="Sync the Shopify payment for order {recordKey} to HotWax, so the HotWax order carries its payment preference." passesWhenTemplate="The HotWax order for Shopify order {recordKey} carries a payment."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditCheck|checkId=RAILS_CHK_RETURN_NO_CM_CANADA|companyUserGroupId=RAILS|savedRunId=RAILS_RETURN_CM_CANADA_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="RAILS_CHK_RETURN_NO_CM_CANADA" companyUserGroupId="RAILS" checkCode="refunded-returns-without-credit-memo-canada" label="Rails Canada Shopify returns refunded with no NetSuite credit memo" savedRunId="RAILS_RETURN_CM_CANADA_RS" file1RecordType="return" file2RecordType="creditMemo" graceDays="1" matchedBy="Shopify order id = custbody_rails_ext_order_id on the credit memo" fixOwner="Rails Accounting" fixWhereEnumId="NETSUITE" headlineTemplate="Shopify order {recordKey} has a refunded return and no credit memo in NetSuite" fixActionTemplate="Book the refund of Shopify order {recordKey} in NetSuite: Loop writes a credit memo for an online return, HotWax for a store or admin return." passesWhenTemplate="NetSuite has a credit memo for Shopify order {recordKey}."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditCheck|checkId=RAILS_CHK_RETURN_NO_CM_EU|companyUserGroupId=RAILS|savedRunId=RAILS_RETURN_CM_EU_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="RAILS_CHK_RETURN_NO_CM_EU" companyUserGroupId="RAILS" checkCode="refunded-returns-without-credit-memo-eu" label="Rails EU Shopify returns refunded with no NetSuite credit memo" savedRunId="RAILS_RETURN_CM_EU_RS" file1RecordType="return" file2RecordType="creditMemo" graceDays="1" matchedBy="Shopify order id = custbody_rails_ext_order_id on the credit memo" fixOwner="Rails Accounting" fixWhereEnumId="NETSUITE" headlineTemplate="Shopify order {recordKey} has a refunded return and no credit memo in NetSuite" fixActionTemplate="Book the refund of Shopify order {recordKey} in NetSuite: Loop writes a credit memo for an online return, HotWax for a store or admin return." passesWhenTemplate="NetSuite has a credit memo for Shopify order {recordKey}."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditCheck|checkId=RAILS_CHK_RETURN_NO_CM_OUTLET|companyUserGroupId=RAILS|savedRunId=RAILS_RETURN_CM_OUTLET_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="RAILS_CHK_RETURN_NO_CM_OUTLET" companyUserGroupId="RAILS" checkCode="refunded-returns-without-credit-memo-outlet" label="Rails Outlet Shopify returns refunded with no NetSuite credit memo" savedRunId="RAILS_RETURN_CM_OUTLET_RS" file1RecordType="return" file2RecordType="creditMemo" graceDays="1" matchedBy="Shopify order id = custbody_rails_ext_order_id on the credit memo" fixOwner="Rails Accounting" fixWhereEnumId="NETSUITE" headlineTemplate="Shopify order {recordKey} has a refunded return and no credit memo in NetSuite" fixActionTemplate="Book the refund of Shopify order {recordKey} in NetSuite: Loop writes a credit memo for an online return, HotWax for a store or admin return." passesWhenTemplate="NetSuite has a credit memo for Shopify order {recordKey}."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditCheck|checkId=RAILS_CHK_RETURN_NO_CM_UK|companyUserGroupId=RAILS|savedRunId=RAILS_RETURN_CM_UK_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="RAILS_CHK_RETURN_NO_CM_UK" companyUserGroupId="RAILS" checkCode="refunded-returns-without-credit-memo-uk" label="Rails UK Shopify returns refunded with no NetSuite credit memo" savedRunId="RAILS_RETURN_CM_UK_RS" file1RecordType="return" file2RecordType="creditMemo" graceDays="1" matchedBy="Shopify order id = custbody_rails_ext_order_id on the credit memo" fixOwner="Rails Accounting" fixWhereEnumId="NETSUITE" headlineTemplate="Shopify order {recordKey} has a refunded return and no credit memo in NetSuite" fixActionTemplate="Book the refund of Shopify order {recordKey} in NetSuite: Loop writes a credit memo for an online return, HotWax for a store or admin return." passesWhenTemplate="NetSuite has a credit memo for Shopify order {recordKey}."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditCheck|checkId=RAILS_CHK_RETURN_NO_CM_USA|companyUserGroupId=RAILS|savedRunId=RAILS_RETURN_CM_USA_RS|fixWhereEnumId=NETSUITE`
- Element: `darpan.audit.AuditCheck`

```xml
<darpan.audit.AuditCheck checkId="RAILS_CHK_RETURN_NO_CM_USA" companyUserGroupId="RAILS" checkCode="refunded-returns-without-credit-memo-usa" label="Rails US Shopify returns refunded with no NetSuite credit memo" savedRunId="RAILS_RETURN_CM_USA_RS" file1RecordType="return" file2RecordType="creditMemo" graceDays="1" matchedBy="Shopify order id = custbody_rails_ext_order_id on the credit memo" fixOwner="Rails Accounting" fixWhereEnumId="NETSUITE" headlineTemplate="Shopify order {recordKey} has a refunded return and no credit memo in NetSuite" fixActionTemplate="Book the refund of Shopify order {recordKey} in NetSuite: Loop writes a credit memo for an online return, HotWax for a store or admin return." passesWhenTemplate="NetSuite has a credit memo for Shopify order {recordKey}."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=RAILS_R2|checkId=RAILS_CHK_NOT_IN_OMS_CANADA|companyUserGroupId=RAILS`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="RAILS_R2" checkId="RAILS_CHK_NOT_IN_OMS_CANADA" companyUserGroupId="RAILS" sequenceNum="2" diffTypes="MISSING_IN_FILE_2"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=RAILS_R2|checkId=RAILS_CHK_NOT_IN_OMS_EU|companyUserGroupId=RAILS`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="RAILS_R2" checkId="RAILS_CHK_NOT_IN_OMS_EU" companyUserGroupId="RAILS" sequenceNum="3" diffTypes="MISSING_IN_FILE_2"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=RAILS_R2|checkId=RAILS_CHK_NOT_IN_OMS_OUTLET|companyUserGroupId=RAILS`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="RAILS_R2" checkId="RAILS_CHK_NOT_IN_OMS_OUTLET" companyUserGroupId="RAILS" sequenceNum="5" diffTypes="MISSING_IN_FILE_2"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=RAILS_R2|checkId=RAILS_CHK_NOT_IN_OMS_UK|companyUserGroupId=RAILS`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="RAILS_R2" checkId="RAILS_CHK_NOT_IN_OMS_UK" companyUserGroupId="RAILS" sequenceNum="4" diffTypes="MISSING_IN_FILE_2"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=RAILS_R2|checkId=RAILS_CHK_NOT_IN_OMS_USA|companyUserGroupId=RAILS`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="RAILS_R2" checkId="RAILS_CHK_NOT_IN_OMS_USA" companyUserGroupId="RAILS" sequenceNum="1" diffTypes="MISSING_IN_FILE_2"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=RAILS_R3|checkId=RAILS_CHK_PAID_NO_OPP_CANADA|companyUserGroupId=RAILS`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="RAILS_R3" checkId="RAILS_CHK_PAID_NO_OPP_CANADA" companyUserGroupId="RAILS" sequenceNum="2" diffTypes="FIELD_MISMATCH" ruleIds="RAILS_CANADA_PAID_HAS_OPP"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=RAILS_R3|checkId=RAILS_CHK_PAID_NO_OPP_EU|companyUserGroupId=RAILS`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="RAILS_R3" checkId="RAILS_CHK_PAID_NO_OPP_EU" companyUserGroupId="RAILS" sequenceNum="3" diffTypes="FIELD_MISMATCH" ruleIds="RAILS_EU_PAID_HAS_OPP"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=RAILS_R3|checkId=RAILS_CHK_PAID_NO_OPP_OUTLET|companyUserGroupId=RAILS`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="RAILS_R3" checkId="RAILS_CHK_PAID_NO_OPP_OUTLET" companyUserGroupId="RAILS" sequenceNum="5" diffTypes="FIELD_MISMATCH" ruleIds="RAILS_OUTLET_PAID_HAS_OPP"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=RAILS_R3|checkId=RAILS_CHK_PAID_NO_OPP_UK|companyUserGroupId=RAILS`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="RAILS_R3" checkId="RAILS_CHK_PAID_NO_OPP_UK" companyUserGroupId="RAILS" sequenceNum="4" diffTypes="FIELD_MISMATCH" ruleIds="RAILS_UK_PAID_HAS_OPP"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=RAILS_R3|checkId=RAILS_CHK_PAID_NO_OPP_USA|companyUserGroupId=RAILS`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="RAILS_R3" checkId="RAILS_CHK_PAID_NO_OPP_USA" companyUserGroupId="RAILS" sequenceNum="1" diffTypes="FIELD_MISMATCH" ruleIds="RAILS_USA_PAID_HAS_OPP"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=RAILS_R4|checkId=RAILS_CHK_COMPLETED_BILLED|companyUserGroupId=RAILS`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="RAILS_R4" checkId="RAILS_CHK_COMPLETED_BILLED" companyUserGroupId="RAILS" sequenceNum="1" diffTypes="MISSING_IN_FILE_2" conclusionCodes="CONC_COMPLETED_CLOSED_IN_NS,CONC_NS_NO_LINE_LOCATION,CONC_NS_BACKORDERED,CONC_SHIPPED_NOT_BILLED,CONC_NOT_SHIPPED_IN_NS,CONC_NEVER_REACHED_NS,UNEXPLAINED"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=RAILS_R5|checkId=RAILS_CHK_COMPLETED_NOT_FULFILLED_CANADA|companyUserGroupId=RAILS`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="RAILS_R5" checkId="RAILS_CHK_COMPLETED_NOT_FULFILLED_CANADA" companyUserGroupId="RAILS" sequenceNum="2" diffTypes="FIELD_MISMATCH" ruleIds="RAILS_CANADA_COMPLETED_FULFILLED"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=RAILS_R5|checkId=RAILS_CHK_COMPLETED_NOT_FULFILLED_EU|companyUserGroupId=RAILS`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="RAILS_R5" checkId="RAILS_CHK_COMPLETED_NOT_FULFILLED_EU" companyUserGroupId="RAILS" sequenceNum="3" diffTypes="FIELD_MISMATCH" ruleIds="RAILS_EU_COMPLETED_FULFILLED"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=RAILS_R5|checkId=RAILS_CHK_COMPLETED_NOT_FULFILLED_OUTLET|companyUserGroupId=RAILS`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="RAILS_R5" checkId="RAILS_CHK_COMPLETED_NOT_FULFILLED_OUTLET" companyUserGroupId="RAILS" sequenceNum="5" diffTypes="FIELD_MISMATCH" ruleIds="RAILS_OUTLET_COMPLETED_FULFILLED"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=RAILS_R5|checkId=RAILS_CHK_COMPLETED_NOT_FULFILLED_UK|companyUserGroupId=RAILS`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="RAILS_R5" checkId="RAILS_CHK_COMPLETED_NOT_FULFILLED_UK" companyUserGroupId="RAILS" sequenceNum="4" diffTypes="FIELD_MISMATCH" ruleIds="RAILS_UK_COMPLETED_FULFILLED"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=RAILS_R5|checkId=RAILS_CHK_COMPLETED_NOT_FULFILLED_USA|companyUserGroupId=RAILS`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="RAILS_R5" checkId="RAILS_CHK_COMPLETED_NOT_FULFILLED_USA" companyUserGroupId="RAILS" sequenceNum="1" diffTypes="FIELD_MISMATCH" ruleIds="RAILS_USA_COMPLETED_FULFILLED"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=RAILS_R6|checkId=RAILS_CHK_RETURN_NO_CM_CANADA|companyUserGroupId=RAILS`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="RAILS_R6" checkId="RAILS_CHK_RETURN_NO_CM_CANADA" companyUserGroupId="RAILS" sequenceNum="2" diffTypes="MISSING_IN_FILE_2"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=RAILS_R6|checkId=RAILS_CHK_RETURN_NO_CM_EU|companyUserGroupId=RAILS`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="RAILS_R6" checkId="RAILS_CHK_RETURN_NO_CM_EU" companyUserGroupId="RAILS" sequenceNum="3" diffTypes="MISSING_IN_FILE_2"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=RAILS_R6|checkId=RAILS_CHK_RETURN_NO_CM_OUTLET|companyUserGroupId=RAILS`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="RAILS_R6" checkId="RAILS_CHK_RETURN_NO_CM_OUTLET" companyUserGroupId="RAILS" sequenceNum="5" diffTypes="MISSING_IN_FILE_2"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=RAILS_R6|checkId=RAILS_CHK_RETURN_NO_CM_UK|companyUserGroupId=RAILS`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="RAILS_R6" checkId="RAILS_CHK_RETURN_NO_CM_UK" companyUserGroupId="RAILS" sequenceNum="4" diffTypes="MISSING_IN_FILE_2"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatementCheck|statementId=RAILS_R6|checkId=RAILS_CHK_RETURN_NO_CM_USA|companyUserGroupId=RAILS`
- Element: `darpan.audit.AuditStatementCheck`

```xml
<darpan.audit.AuditStatementCheck statementId="RAILS_R6" checkId="RAILS_CHK_RETURN_NO_CM_USA" companyUserGroupId="RAILS" sequenceNum="1" diffTypes="MISSING_IN_FILE_2"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatement|statementId=RAILS_R2|auditId=RAILS_MONTH_CLOSE|companyUserGroupId=RAILS|categoryEnumId=AUDC_ORDERS|weightEnumId=AUDSW_FIRM`
- Element: `darpan.audit.AuditStatement`

```xml
<darpan.audit.AuditStatement statementId="RAILS_R2" auditId="RAILS_MONTH_CLOSE" companyUserGroupId="RAILS" statementCode="R2" categoryEnumId="AUDC_ORDERS" weightEnumId="AUDSW_FIRM" sequenceNum="2" statementText="Every Shopify order is in HotWax."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatement|statementId=RAILS_R3|auditId=RAILS_MONTH_CLOSE|companyUserGroupId=RAILS|categoryEnumId=AUDC_ORDERS|weightEnumId=AUDSW_FIRM`
- Element: `darpan.audit.AuditStatement`

```xml
<darpan.audit.AuditStatement statementId="RAILS_R3" auditId="RAILS_MONTH_CLOSE" companyUserGroupId="RAILS" statementCode="R3" categoryEnumId="AUDC_ORDERS" weightEnumId="AUDSW_FIRM" sequenceNum="3" statementText="Every order paid in Shopify has its payment in HotWax."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatement|statementId=RAILS_R4|auditId=RAILS_MONTH_CLOSE|companyUserGroupId=RAILS|categoryEnumId=AUDC_ORDERS|weightEnumId=AUDSW_FIRM`
- Element: `darpan.audit.AuditStatement`

```xml
<darpan.audit.AuditStatement statementId="RAILS_R4" auditId="RAILS_MONTH_CLOSE" companyUserGroupId="RAILS" statementCode="R4" categoryEnumId="AUDC_ORDERS" weightEnumId="AUDSW_FIRM" sequenceNum="4" statementText="Every item HotWax completes is billed in NetSuite."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatement|statementId=RAILS_R5|auditId=RAILS_MONTH_CLOSE|companyUserGroupId=RAILS|categoryEnumId=AUDC_ORDERS|weightEnumId=AUDSW_FIRM`
- Element: `darpan.audit.AuditStatement`

```xml
<darpan.audit.AuditStatement statementId="RAILS_R5" auditId="RAILS_MONTH_CLOSE" companyUserGroupId="RAILS" statementCode="R5" categoryEnumId="AUDC_ORDERS" weightEnumId="AUDSW_FIRM" sequenceNum="5" statementText="Every order HotWax completes is fulfilled in Shopify."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.audit.AuditStatement|statementId=RAILS_R6|auditId=RAILS_MONTH_CLOSE|companyUserGroupId=RAILS|categoryEnumId=AUDC_RETURNS|weightEnumId=AUDSW_FIRM`
- Element: `darpan.audit.AuditStatement`

```xml
<darpan.audit.AuditStatement statementId="RAILS_R6" auditId="RAILS_MONTH_CLOSE" companyUserGroupId="RAILS" statementCode="R6" categoryEnumId="AUDC_RETURNS" weightEnumId="AUDSW_FIRM" sequenceNum="6" statementText="Every return Shopify completes with a refund is in NetSuite."/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=RAILS_OMS_NS_LINE_SCOPE|ruleSetId=RAILS_OMS_NS_LINE_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="RAILS_OMS_NS_LINE_SCOPE" ruleSetId="RAILS_OMS_NS_LINE_RS" objectType="ORDER_LINE_UNIT" scopeMode="COMPARE" description="Every item HotWax completed must be billed on its NetSuite sales order line. Keyed (HotWax order id, item seq id)." companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=RAILS_RETURN_CM_CANADA_SCOPE|ruleSetId=RAILS_RETURN_CM_CANADA_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="RAILS_RETURN_CM_CANADA_SCOPE" ruleSetId="RAILS_RETURN_CM_CANADA_RS" objectType="RETURN" scopeMode="COMPARE" description="Every Rails Canada Shopify order with a return completed and refunded (on the return or, as Loop does, on the order) against its NetSuite credit memos." companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=RAILS_RETURN_CM_EU_SCOPE|ruleSetId=RAILS_RETURN_CM_EU_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="RAILS_RETURN_CM_EU_SCOPE" ruleSetId="RAILS_RETURN_CM_EU_RS" objectType="RETURN" scopeMode="COMPARE" description="Every Rails EU Shopify order with a return completed and refunded (on the return or, as Loop does, on the order) against its NetSuite credit memos." companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=RAILS_RETURN_CM_OUTLET_SCOPE|ruleSetId=RAILS_RETURN_CM_OUTLET_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="RAILS_RETURN_CM_OUTLET_SCOPE" ruleSetId="RAILS_RETURN_CM_OUTLET_RS" objectType="RETURN" scopeMode="COMPARE" description="Every Rails Outlet Shopify order with a return completed and refunded (on the return or, as Loop does, on the order) against its NetSuite credit memos." companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=RAILS_RETURN_CM_UK_SCOPE|ruleSetId=RAILS_RETURN_CM_UK_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="RAILS_RETURN_CM_UK_SCOPE" ruleSetId="RAILS_RETURN_CM_UK_RS" objectType="RETURN" scopeMode="COMPARE" description="Every Rails UK Shopify order with a return completed and refunded (on the return or, as Loop does, on the order) against its NetSuite credit memos." companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=RAILS_RETURN_CM_USA_SCOPE|ruleSetId=RAILS_RETURN_CM_USA_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="RAILS_RETURN_CM_USA_SCOPE" ruleSetId="RAILS_RETURN_CM_USA_RS" objectType="RETURN" scopeMode="COMPARE" description="Every Rails US Shopify order with a return completed and refunded (on the return or, as Loop does, on the order) against its NetSuite credit memos." companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=RAILS_SHOP_OMS_CANADA_SCOPE|ruleSetId=RAILS_SHOP_OMS_CANADA_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="RAILS_SHOP_OMS_CANADA_SCOPE" ruleSetId="RAILS_SHOP_OMS_CANADA_RS" objectType="ORDER" scopeMode="COMPARE" description="Every Rails Canada Shopify order against its HotWax order: missing from HotWax, paid with no HotWax payment, or completed in HotWax and not fulfilled in Shopify." companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=RAILS_SHOP_OMS_EU_SCOPE|ruleSetId=RAILS_SHOP_OMS_EU_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="RAILS_SHOP_OMS_EU_SCOPE" ruleSetId="RAILS_SHOP_OMS_EU_RS" objectType="ORDER" scopeMode="COMPARE" description="Every Rails EU Shopify order against its HotWax order: missing from HotWax, paid with no HotWax payment, or completed in HotWax and not fulfilled in Shopify." companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=RAILS_SHOP_OMS_OUTLET_SCOPE|ruleSetId=RAILS_SHOP_OMS_OUTLET_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="RAILS_SHOP_OMS_OUTLET_SCOPE" ruleSetId="RAILS_SHOP_OMS_OUTLET_RS" objectType="ORDER" scopeMode="COMPARE" description="Every Rails Outlet Shopify order against its HotWax order: missing from HotWax, paid with no HotWax payment, or completed in HotWax and not fulfilled in Shopify." companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=RAILS_SHOP_OMS_UK_SCOPE|ruleSetId=RAILS_SHOP_OMS_UK_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="RAILS_SHOP_OMS_UK_SCOPE" ruleSetId="RAILS_SHOP_OMS_UK_RS" objectType="ORDER" scopeMode="COMPARE" description="Every Rails UK Shopify order against its HotWax order: missing from HotWax, paid with no HotWax payment, or completed in HotWax and not fulfilled in Shopify." companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareScope|compareScopeId=RAILS_SHOP_OMS_USA_SCOPE|ruleSetId=RAILS_SHOP_OMS_USA_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareScope`

```xml
<darpan.rule.RuleSetCompareScope compareScopeId="RAILS_SHOP_OMS_USA_SCOPE" ruleSetId="RAILS_SHOP_OMS_USA_RS" objectType="ORDER" scopeMode="COMPARE" description="Every Rails US Shopify order against its HotWax order: missing from HotWax, paid with no HotWax payment, or completed in HotWax and not fulfilled in Shopify." companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceFilter|compareScopeId=RAILS_OMS_NS_LINE_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceFilter`

```xml
<darpan.rule.RuleSetCompareSourceFilter compareScopeId="RAILS_OMS_NS_LINE_SCOPE" fileSide="FILE_2" sequenceNum="3" fieldExpression="shopifyStore" operator="INCLUDE_IN" filterValues="1,2,3,4,5" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceFilter|compareScopeId=RAILS_RETURN_CM_CANADA_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceFilter`

```xml
<darpan.rule.RuleSetCompareSourceFilter compareScopeId="RAILS_RETURN_CM_CANADA_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="shopifyStore" operator="INCLUDE_IN" filterValues="5" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceFilter|compareScopeId=RAILS_RETURN_CM_EU_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceFilter`

```xml
<darpan.rule.RuleSetCompareSourceFilter compareScopeId="RAILS_RETURN_CM_EU_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="shopifyStore" operator="INCLUDE_IN" filterValues="2" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceFilter|compareScopeId=RAILS_RETURN_CM_OUTLET_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceFilter`

```xml
<darpan.rule.RuleSetCompareSourceFilter compareScopeId="RAILS_RETURN_CM_OUTLET_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="shopifyStore" operator="INCLUDE_IN" filterValues="4" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceFilter|compareScopeId=RAILS_RETURN_CM_UK_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceFilter`

```xml
<darpan.rule.RuleSetCompareSourceFilter compareScopeId="RAILS_RETURN_CM_UK_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="shopifyStore" operator="INCLUDE_IN" filterValues="3" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceFilter|compareScopeId=RAILS_RETURN_CM_USA_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceFilter`

```xml
<darpan.rule.RuleSetCompareSourceFilter compareScopeId="RAILS_RETURN_CM_USA_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="shopifyStore" operator="INCLUDE_IN" filterValues="1" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceFilter|compareScopeId=RAILS_SHOP_OMS_CANADA_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceFilter`

```xml
<darpan.rule.RuleSetCompareSourceFilter compareScopeId="RAILS_SHOP_OMS_CANADA_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="productStoreId" operator="INCLUDE_IN" filterValues="STORE_CA" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceFilter|compareScopeId=RAILS_SHOP_OMS_EU_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceFilter`

```xml
<darpan.rule.RuleSetCompareSourceFilter compareScopeId="RAILS_SHOP_OMS_EU_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="productStoreId" operator="INCLUDE_IN" filterValues="STORE_EU" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceFilter|compareScopeId=RAILS_SHOP_OMS_OUTLET_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceFilter`

```xml
<darpan.rule.RuleSetCompareSourceFilter compareScopeId="RAILS_SHOP_OMS_OUTLET_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="productStoreId" operator="INCLUDE_IN" filterValues="STORE_OUTLET" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceFilter|compareScopeId=RAILS_SHOP_OMS_UK_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceFilter`

```xml
<darpan.rule.RuleSetCompareSourceFilter compareScopeId="RAILS_SHOP_OMS_UK_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="productStoreId" operator="INCLUDE_IN" filterValues="STORE_UK" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceFilter|compareScopeId=RAILS_SHOP_OMS_USA_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceFilter`

```xml
<darpan.rule.RuleSetCompareSourceFilter compareScopeId="RAILS_SHOP_OMS_USA_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="productStoreId" operator="INCLUDE_IN" filterValues="STORE" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=RAILS_OMS_NS_LINE_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="RAILS_OMS_NS_LINE_SCOPE" fileSide="FILE_2" sequenceNum="2" fieldExpression="orderLineId" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=RAILS_RETURN_CM_CANADA_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="RAILS_RETURN_CM_CANADA_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="shopifyOrderId" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=RAILS_RETURN_CM_EU_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="RAILS_RETURN_CM_EU_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="shopifyOrderId" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=RAILS_RETURN_CM_OUTLET_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="RAILS_RETURN_CM_OUTLET_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="shopifyOrderId" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=RAILS_RETURN_CM_UK_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="RAILS_RETURN_CM_UK_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="shopifyOrderId" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=RAILS_RETURN_CM_USA_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="RAILS_RETURN_CM_USA_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="shopifyOrderId" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=RAILS_SHOP_OMS_CANADA_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="RAILS_SHOP_OMS_CANADA_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="externalId" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=RAILS_SHOP_OMS_EU_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="RAILS_SHOP_OMS_EU_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="externalId" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=RAILS_SHOP_OMS_OUTLET_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="RAILS_SHOP_OMS_OUTLET_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="externalId" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=RAILS_SHOP_OMS_UK_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="RAILS_SHOP_OMS_UK_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="externalId" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSourceKeyField|compareScopeId=RAILS_SHOP_OMS_USA_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSourceKeyField`

```xml
<darpan.rule.RuleSetCompareSourceKeyField compareScopeId="RAILS_SHOP_OMS_USA_SCOPE" fileSide="FILE_2" sequenceNum="1" fieldExpression="externalId" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_OMS_NS_LINE_SCOPE|systemEnumId=NETSUITE_SUITEQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=RAILS_NS_SO_LINES|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_OMS_NS_LINE_SCOPE" fileSide="FILE_2" systemEnumId="NETSUITE_SUITEQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="orderId" sourceConfigId="RAILS_NS_SO_LINES" sourceConfigType="NETSUITE_SUITEQL" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_OMS_NS_LINE_SCOPE|systemEnumId=OMS_ORDER_ITEMS_GQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=rails_prod|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_OMS_NS_LINE_SCOPE" fileSide="FILE_1" systemEnumId="OMS_ORDER_ITEMS_GQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="omsOrderId" sourceConfigId="rails_prod" sourceConfigType="HOTWAX_OMS_GQL_ORDER_ITEMS" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_RETURN_CM_CANADA_SCOPE|systemEnumId=NETSUITE_SUITEQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=RAILS_NS_CREDIT_MEMOS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_RETURN_CM_CANADA_SCOPE" fileSide="FILE_2" systemEnumId="NETSUITE_SUITEQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="shopifyOrderId" sourceConfigId="RAILS_NS_CREDIT_MEMOS" sourceConfigType="NETSUITE_SUITEQL" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_RETURN_CM_CANADA_SCOPE|systemEnumId=SHOPIFY_RETURN_REFS|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=100000|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_RETURN_CM_CANADA_SCOPE" fileSide="FILE_1" systemEnumId="SHOPIFY_RETURN_REFS" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="orderId" sourceConfigId="100000" sourceConfigType="SHOPIFY_RETURN_REFS_API" extractParametersJson='{"completedReturns":"orderRefunded"}' companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_RETURN_CM_EU_SCOPE|systemEnumId=NETSUITE_SUITEQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=RAILS_NS_CREDIT_MEMOS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_RETURN_CM_EU_SCOPE" fileSide="FILE_2" systemEnumId="NETSUITE_SUITEQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="shopifyOrderId" sourceConfigId="RAILS_NS_CREDIT_MEMOS" sourceConfigType="NETSUITE_SUITEQL" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_RETURN_CM_EU_SCOPE|systemEnumId=SHOPIFY_RETURN_REFS|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=100001|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_RETURN_CM_EU_SCOPE" fileSide="FILE_1" systemEnumId="SHOPIFY_RETURN_REFS" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="orderId" sourceConfigId="100001" sourceConfigType="SHOPIFY_RETURN_REFS_API" extractParametersJson='{"completedReturns":"orderRefunded"}' companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_RETURN_CM_OUTLET_SCOPE|systemEnumId=NETSUITE_SUITEQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=RAILS_NS_CREDIT_MEMOS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_RETURN_CM_OUTLET_SCOPE" fileSide="FILE_2" systemEnumId="NETSUITE_SUITEQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="shopifyOrderId" sourceConfigId="RAILS_NS_CREDIT_MEMOS" sourceConfigType="NETSUITE_SUITEQL" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_RETURN_CM_OUTLET_SCOPE|systemEnumId=SHOPIFY_RETURN_REFS|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=100002|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_RETURN_CM_OUTLET_SCOPE" fileSide="FILE_1" systemEnumId="SHOPIFY_RETURN_REFS" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="orderId" sourceConfigId="100002" sourceConfigType="SHOPIFY_RETURN_REFS_API" extractParametersJson='{"completedReturns":"orderRefunded"}' companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_RETURN_CM_UK_SCOPE|systemEnumId=NETSUITE_SUITEQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=RAILS_NS_CREDIT_MEMOS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_RETURN_CM_UK_SCOPE" fileSide="FILE_2" systemEnumId="NETSUITE_SUITEQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="shopifyOrderId" sourceConfigId="RAILS_NS_CREDIT_MEMOS" sourceConfigType="NETSUITE_SUITEQL" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_RETURN_CM_UK_SCOPE|systemEnumId=SHOPIFY_RETURN_REFS|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=100004|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_RETURN_CM_UK_SCOPE" fileSide="FILE_1" systemEnumId="SHOPIFY_RETURN_REFS" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="orderId" sourceConfigId="100004" sourceConfigType="SHOPIFY_RETURN_REFS_API" extractParametersJson='{"completedReturns":"orderRefunded"}' companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_RETURN_CM_USA_SCOPE|systemEnumId=NETSUITE_SUITEQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=RAILS_NS_CREDIT_MEMOS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_RETURN_CM_USA_SCOPE" fileSide="FILE_2" systemEnumId="NETSUITE_SUITEQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="shopifyOrderId" sourceConfigId="RAILS_NS_CREDIT_MEMOS" sourceConfigType="NETSUITE_SUITEQL" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_RETURN_CM_USA_SCOPE|systemEnumId=SHOPIFY_RETURN_REFS|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=100003|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_RETURN_CM_USA_SCOPE" fileSide="FILE_1" systemEnumId="SHOPIFY_RETURN_REFS" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="orderId" sourceConfigId="100003" sourceConfigType="SHOPIFY_RETURN_REFS_API" extractParametersJson='{"completedReturns":"orderRefunded"}' companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_SHOP_OMS_CANADA_SCOPE|systemEnumId=OMS_GQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=rails_prod|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_SHOP_OMS_CANADA_SCOPE" fileSide="FILE_2" systemEnumId="OMS_GQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="externalId" sourceConfigId="rails_prod" sourceConfigType="HOTWAX_OMS_GQL" duplicateKeyHandling="FINDING" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_SHOP_OMS_CANADA_SCOPE|systemEnumId=SHOPIFY|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=100000|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_SHOP_OMS_CANADA_SCOPE" fileSide="FILE_1" systemEnumId="SHOPIFY" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="legacyResourceId" sourceConfigId="100000" sourceConfigType="SHOPIFY_AUTH" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_SHOP_OMS_EU_SCOPE|systemEnumId=OMS_GQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=rails_prod|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_SHOP_OMS_EU_SCOPE" fileSide="FILE_2" systemEnumId="OMS_GQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="externalId" sourceConfigId="rails_prod" sourceConfigType="HOTWAX_OMS_GQL" duplicateKeyHandling="FINDING" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_SHOP_OMS_EU_SCOPE|systemEnumId=SHOPIFY|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=100001|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_SHOP_OMS_EU_SCOPE" fileSide="FILE_1" systemEnumId="SHOPIFY" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="legacyResourceId" sourceConfigId="100001" sourceConfigType="SHOPIFY_AUTH" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_SHOP_OMS_OUTLET_SCOPE|systemEnumId=OMS_GQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=rails_prod|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_SHOP_OMS_OUTLET_SCOPE" fileSide="FILE_2" systemEnumId="OMS_GQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="externalId" sourceConfigId="rails_prod" sourceConfigType="HOTWAX_OMS_GQL" duplicateKeyHandling="FINDING" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_SHOP_OMS_OUTLET_SCOPE|systemEnumId=SHOPIFY|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=100002|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_SHOP_OMS_OUTLET_SCOPE" fileSide="FILE_1" systemEnumId="SHOPIFY" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="legacyResourceId" sourceConfigId="100002" sourceConfigType="SHOPIFY_AUTH" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_SHOP_OMS_UK_SCOPE|systemEnumId=OMS_GQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=rails_prod|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_SHOP_OMS_UK_SCOPE" fileSide="FILE_2" systemEnumId="OMS_GQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="externalId" sourceConfigId="rails_prod" sourceConfigType="HOTWAX_OMS_GQL" duplicateKeyHandling="FINDING" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_SHOP_OMS_UK_SCOPE|systemEnumId=SHOPIFY|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=100004|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_SHOP_OMS_UK_SCOPE" fileSide="FILE_1" systemEnumId="SHOPIFY" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="legacyResourceId" sourceConfigId="100004" sourceConfigType="SHOPIFY_AUTH" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_SHOP_OMS_USA_SCOPE|systemEnumId=OMS_GQL|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=rails_prod|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_SHOP_OMS_USA_SCOPE" fileSide="FILE_2" systemEnumId="OMS_GQL" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="externalId" sourceConfigId="rails_prod" sourceConfigType="HOTWAX_OMS_GQL" duplicateKeyHandling="FINDING" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetCompareSource|compareScopeId=RAILS_SHOP_OMS_USA_SCOPE|systemEnumId=SHOPIFY|sourceTypeEnumId=AUT_SRC_API|fileTypeEnumId=DftJson|sourceConfigId=100003|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetCompareSource`

```xml
<darpan.rule.RuleSetCompareSource compareScopeId="RAILS_SHOP_OMS_USA_SCOPE" fileSide="FILE_1" systemEnumId="SHOPIFY" sourceTypeEnumId="AUT_SRC_API" fileTypeEnumId="DftJson" recordRootExpression="records" primaryIdExpression="legacyResourceId" sourceConfigId="100003" sourceConfigType="SHOPIFY_AUTH" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionCondition|compareScopeId=RAILS_OMS_NS_LINE_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionCondition`

```xml
<darpan.rule.RuleSetConclusionCondition compareScopeId="RAILS_OMS_NS_LINE_SCOPE" sequenceNum="30" conditionSeq="1" subject="FILE_2" presence="KEPT" keyScope="FULL" fieldExpression="lineState" operator="IN" conditionValues="BILLED" checkLabel="NetSuite billed the line" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionCondition|compareScopeId=RAILS_RETURN_CM_CANADA_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionCondition`

```xml
<darpan.rule.RuleSetConclusionCondition compareScopeId="RAILS_RETURN_CM_CANADA_SCOPE" sequenceNum="10" conditionSeq="1" subject="FILE_2" presence="ABSENT" keyScope="FULL" checkLabel="NetSuite has no credit memo for the order" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionCondition|compareScopeId=RAILS_RETURN_CM_EU_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionCondition`

```xml
<darpan.rule.RuleSetConclusionCondition compareScopeId="RAILS_RETURN_CM_EU_SCOPE" sequenceNum="10" conditionSeq="1" subject="FILE_2" presence="ABSENT" keyScope="FULL" checkLabel="NetSuite has no credit memo for the order" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionCondition|compareScopeId=RAILS_RETURN_CM_OUTLET_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionCondition`

```xml
<darpan.rule.RuleSetConclusionCondition compareScopeId="RAILS_RETURN_CM_OUTLET_SCOPE" sequenceNum="10" conditionSeq="1" subject="FILE_2" presence="ABSENT" keyScope="FULL" checkLabel="NetSuite has no credit memo for the order" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionCondition|compareScopeId=RAILS_RETURN_CM_UK_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionCondition`

```xml
<darpan.rule.RuleSetConclusionCondition compareScopeId="RAILS_RETURN_CM_UK_SCOPE" sequenceNum="10" conditionSeq="1" subject="FILE_2" presence="ABSENT" keyScope="FULL" checkLabel="NetSuite has no credit memo for the order" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionCondition|compareScopeId=RAILS_RETURN_CM_USA_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionCondition`

```xml
<darpan.rule.RuleSetConclusionCondition compareScopeId="RAILS_RETURN_CM_USA_SCOPE" sequenceNum="10" conditionSeq="1" subject="FILE_2" presence="ABSENT" keyScope="FULL" checkLabel="NetSuite has no credit memo for the order" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionCondition|compareScopeId=RAILS_SHOP_OMS_CANADA_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionCondition`

```xml
<darpan.rule.RuleSetConclusionCondition compareScopeId="RAILS_SHOP_OMS_CANADA_SCOPE" sequenceNum="10" conditionSeq="1" subject="FILE_2" presence="ABSENT" keyScope="FULL" checkLabel="HotWax has no order with this Shopify id" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionCondition|compareScopeId=RAILS_SHOP_OMS_EU_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionCondition`

```xml
<darpan.rule.RuleSetConclusionCondition compareScopeId="RAILS_SHOP_OMS_EU_SCOPE" sequenceNum="10" conditionSeq="1" subject="FILE_2" presence="ABSENT" keyScope="FULL" checkLabel="HotWax has no order with this Shopify id" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionCondition|compareScopeId=RAILS_SHOP_OMS_OUTLET_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionCondition`

```xml
<darpan.rule.RuleSetConclusionCondition compareScopeId="RAILS_SHOP_OMS_OUTLET_SCOPE" sequenceNum="10" conditionSeq="1" subject="FILE_2" presence="ABSENT" keyScope="FULL" checkLabel="HotWax has no order with this Shopify id" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionCondition|compareScopeId=RAILS_SHOP_OMS_UK_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionCondition`

```xml
<darpan.rule.RuleSetConclusionCondition compareScopeId="RAILS_SHOP_OMS_UK_SCOPE" sequenceNum="10" conditionSeq="1" subject="FILE_2" presence="ABSENT" keyScope="FULL" checkLabel="HotWax has no order with this Shopify id" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionCondition|compareScopeId=RAILS_SHOP_OMS_USA_SCOPE|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionCondition`

```xml
<darpan.rule.RuleSetConclusionCondition compareScopeId="RAILS_SHOP_OMS_USA_SCOPE" sequenceNum="10" conditionSeq="1" subject="FILE_2" presence="ABSENT" keyScope="FULL" checkLabel="HotWax has no order with this Shopify id" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_OMS_NS_LINE_SCOPE|conclusionEnumId=CONC_BILLED_NOT_COMPLETED|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_OMS_NS_LINE_SCOPE" sequenceNum="30" fixActionTemplate="Complete item {recordKey} in HotWax: NetSuite already billed it." conclusionEnumId="CONC_BILLED_NOT_COMPLETED" appliesToBucket="MISSING_FROM_FILE_1" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_OMS_NS_LINE_SCOPE|conclusionEnumId=CONC_COMPLETED_CLOSED_IN_NS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_OMS_NS_LINE_SCOPE" sequenceNum="5" fixActionTemplate="NetSuite closed the line HotWax completed: reopen and bill it in NetSuite, or cancel item {recordKey} in HotWax if it never shipped." conclusionEnumId="CONC_COMPLETED_CLOSED_IN_NS" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_OMS_NS_LINE_SCOPE|conclusionEnumId=CONC_NEVER_REACHED_NS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_OMS_NS_LINE_SCOPE" sequenceNum="20" fixActionTemplate="Re-run the order sync in HotWax for item {recordKey}: NetSuite has no sales order for it." conclusionEnumId="CONC_NEVER_REACHED_NS" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_OMS_NS_LINE_SCOPE|conclusionEnumId=CONC_NOT_SHIPPED_IN_NS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_OMS_NS_LINE_SCOPE" sequenceNum="16" fixActionTemplate="Send the fulfillment of item {recordKey} to NetSuite: HotWax fulfilled it and NetSuite still shows the line not shipped, so it cannot bill it." conclusionEnumId="CONC_NOT_SHIPPED_IN_NS" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_OMS_NS_LINE_SCOPE|conclusionEnumId=CONC_NS_BACKORDERED|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_OMS_NS_LINE_SCOPE" sequenceNum="10" fixActionTemplate="Fulfil the backordered line in NetSuite ({quantityBackordered} on backorder); it is billed once it ships." conclusionEnumId="CONC_NS_BACKORDERED" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_OMS_NS_LINE_SCOPE|conclusionEnumId=CONC_NS_NO_LINE_LOCATION|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_OMS_NS_LINE_SCOPE" sequenceNum="8" fixActionTemplate="Set the location on the NetSuite line of item {recordKey}; NetSuite can then ship and bill it." conclusionEnumId="CONC_NS_NO_LINE_LOCATION" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_OMS_NS_LINE_SCOPE|conclusionEnumId=CONC_PARTIAL_PAYMENT_NO_NS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_OMS_NS_LINE_SCOPE" sequenceNum="18" fixActionTemplate="Nothing to fix in NetSuite: HotWax sends an order only once it is paid, and this one's payment was taken back (order total {orderGrandTotal}). It syncs once paid; cancel it in HotWax if the sale did not stand." conclusionEnumId="CONC_PARTIAL_PAYMENT_NO_NS" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_OMS_NS_LINE_SCOPE|conclusionEnumId=CONC_SHIPPED_NOT_BILLED|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_OMS_NS_LINE_SCOPE" sequenceNum="15" fixActionTemplate="Bill the shipped line in NetSuite: it shipped and its sales order is not invoiced." conclusionEnumId="CONC_SHIPPED_NOT_BILLED" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_RETURN_CM_CANADA_SCOPE|conclusionEnumId=CONC_NO_CREDIT_MEMO_IN_NS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_RETURN_CM_CANADA_SCOPE" sequenceNum="10" fixActionTemplate="Book the refund of Shopify order {recordKey} in NetSuite: Loop writes a credit memo for an online return, HotWax for a store or admin return." conclusionEnumId="CONC_NO_CREDIT_MEMO_IN_NS" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_RETURN_CM_EU_SCOPE|conclusionEnumId=CONC_NO_CREDIT_MEMO_IN_NS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_RETURN_CM_EU_SCOPE" sequenceNum="10" fixActionTemplate="Book the refund of Shopify order {recordKey} in NetSuite: Loop writes a credit memo for an online return, HotWax for a store or admin return." conclusionEnumId="CONC_NO_CREDIT_MEMO_IN_NS" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_RETURN_CM_OUTLET_SCOPE|conclusionEnumId=CONC_NO_CREDIT_MEMO_IN_NS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_RETURN_CM_OUTLET_SCOPE" sequenceNum="10" fixActionTemplate="Book the refund of Shopify order {recordKey} in NetSuite: Loop writes a credit memo for an online return, HotWax for a store or admin return." conclusionEnumId="CONC_NO_CREDIT_MEMO_IN_NS" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_RETURN_CM_UK_SCOPE|conclusionEnumId=CONC_NO_CREDIT_MEMO_IN_NS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_RETURN_CM_UK_SCOPE" sequenceNum="10" fixActionTemplate="Book the refund of Shopify order {recordKey} in NetSuite: Loop writes a credit memo for an online return, HotWax for a store or admin return." conclusionEnumId="CONC_NO_CREDIT_MEMO_IN_NS" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_RETURN_CM_USA_SCOPE|conclusionEnumId=CONC_NO_CREDIT_MEMO_IN_NS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_RETURN_CM_USA_SCOPE" sequenceNum="10" fixActionTemplate="Book the refund of Shopify order {recordKey} in NetSuite: Loop writes a credit memo for an online return, HotWax for a store or admin return." conclusionEnumId="CONC_NO_CREDIT_MEMO_IN_NS" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_SHOP_OMS_CANADA_SCOPE|conclusionEnumId=CONC_SHOPIFY_ORDER_NOT_IN_OMS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_SHOP_OMS_CANADA_SCOPE" sequenceNum="10" fixActionTemplate="Import Shopify order {recordKey} into HotWax." conclusionEnumId="CONC_SHOPIFY_ORDER_NOT_IN_OMS" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_SHOP_OMS_EU_SCOPE|conclusionEnumId=CONC_SHOPIFY_ORDER_NOT_IN_OMS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_SHOP_OMS_EU_SCOPE" sequenceNum="10" fixActionTemplate="Import Shopify order {recordKey} into HotWax." conclusionEnumId="CONC_SHOPIFY_ORDER_NOT_IN_OMS" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_SHOP_OMS_OUTLET_SCOPE|conclusionEnumId=CONC_SHOPIFY_ORDER_NOT_IN_OMS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_SHOP_OMS_OUTLET_SCOPE" sequenceNum="10" fixActionTemplate="Import Shopify order {recordKey} into HotWax." conclusionEnumId="CONC_SHOPIFY_ORDER_NOT_IN_OMS" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_SHOP_OMS_UK_SCOPE|conclusionEnumId=CONC_SHOPIFY_ORDER_NOT_IN_OMS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_SHOP_OMS_UK_SCOPE" sequenceNum="10" fixActionTemplate="Import Shopify order {recordKey} into HotWax." conclusionEnumId="CONC_SHOPIFY_ORDER_NOT_IN_OMS" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSetConclusionRule|compareScopeId=RAILS_SHOP_OMS_USA_SCOPE|conclusionEnumId=CONC_SHOPIFY_ORDER_NOT_IN_OMS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSetConclusionRule`

```xml
<darpan.rule.RuleSetConclusionRule compareScopeId="RAILS_SHOP_OMS_USA_SCOPE" sequenceNum="10" fixActionTemplate="Import Shopify order {recordKey} into HotWax." conclusionEnumId="CONC_SHOPIFY_ORDER_NOT_IN_OMS" appliesToBucket="MISSING_FROM_FILE_2" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=RAILS_OMS_NS_LINE_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="RAILS_OMS_NS_LINE_RS" ruleSetName="Completed - HotWax vs NetSuite" description="Every item HotWax completed must be billed on its NetSuite sales order line. Keyed (HotWax order id, item seq id)." version="1.0" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=RAILS_RETURN_CM_CANADA_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="RAILS_RETURN_CM_CANADA_RS" ruleSetName="Shopify refunded returns against NetSuite credit memos - Canada" description="Every Rails Canada Shopify order with a return completed and refunded (on the return or, as Loop does, on the order) against its NetSuite credit memos." version="1.0" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=RAILS_RETURN_CM_EU_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="RAILS_RETURN_CM_EU_RS" ruleSetName="Shopify refunded returns against NetSuite credit memos - EU" description="Every Rails EU Shopify order with a return completed and refunded (on the return or, as Loop does, on the order) against its NetSuite credit memos." version="1.0" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=RAILS_RETURN_CM_OUTLET_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="RAILS_RETURN_CM_OUTLET_RS" ruleSetName="Shopify refunded returns against NetSuite credit memos - Outlet" description="Every Rails Outlet Shopify order with a return completed and refunded (on the return or, as Loop does, on the order) against its NetSuite credit memos." version="1.0" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=RAILS_RETURN_CM_UK_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="RAILS_RETURN_CM_UK_RS" ruleSetName="Shopify refunded returns against NetSuite credit memos - UK" description="Every Rails UK Shopify order with a return completed and refunded (on the return or, as Loop does, on the order) against its NetSuite credit memos." version="1.0" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=RAILS_RETURN_CM_USA_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="RAILS_RETURN_CM_USA_RS" ruleSetName="Shopify refunded returns against NetSuite credit memos - US" description="Every Rails US Shopify order with a return completed and refunded (on the return or, as Loop does, on the order) against its NetSuite credit memos." version="1.0" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=RAILS_SHOP_OMS_CANADA_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="RAILS_SHOP_OMS_CANADA_RS" ruleSetName="Shopify orders against HotWax - Canada" description="Every Rails Canada Shopify order against its HotWax order: missing from HotWax, paid with no HotWax payment, or completed in HotWax and not fulfilled in Shopify." version="1.0" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=RAILS_SHOP_OMS_EU_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="RAILS_SHOP_OMS_EU_RS" ruleSetName="Shopify orders against HotWax - EU" description="Every Rails EU Shopify order against its HotWax order: missing from HotWax, paid with no HotWax payment, or completed in HotWax and not fulfilled in Shopify." version="1.0" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=RAILS_SHOP_OMS_OUTLET_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="RAILS_SHOP_OMS_OUTLET_RS" ruleSetName="Shopify orders against HotWax - Outlet" description="Every Rails Outlet Shopify order against its HotWax order: missing from HotWax, paid with no HotWax payment, or completed in HotWax and not fulfilled in Shopify." version="1.0" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=RAILS_SHOP_OMS_UK_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="RAILS_SHOP_OMS_UK_RS" ruleSetName="Shopify orders against HotWax - UK" description="Every Rails UK Shopify order against its HotWax order: missing from HotWax, paid with no HotWax payment, or completed in HotWax and not fulfilled in Shopify." version="1.0" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.RuleSet|ruleSetId=RAILS_SHOP_OMS_USA_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.RuleSet`

```xml
<darpan.rule.RuleSet ruleSetId="RAILS_SHOP_OMS_USA_RS" ruleSetName="Shopify orders against HotWax - US" description="Every Rails US Shopify order against its HotWax order: missing from HotWax, paid with no HotWax payment, or completed in HotWax and not fulfilled in Shopify." version="1.0" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.Rule|ruleId=RAILS_CANADA_COMPLETED_FULFILLED|ruleSetId=RAILS_SHOP_OMS_CANADA_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.Rule`

```xml
<darpan.rule.Rule ruleId="RAILS_CANADA_COMPLETED_FULFILLED" ruleSetId="RAILS_SHOP_OMS_CANADA_RS" sequenceNum="20" ruleText="isFulfilled required by isCompleted" enabled="Y" ruleType="FIELD_COMPARISON" severity="WARN" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.Rule|ruleId=RAILS_CANADA_PAID_HAS_OPP|ruleSetId=RAILS_SHOP_OMS_CANADA_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.Rule`

```xml
<darpan.rule.Rule ruleId="RAILS_CANADA_PAID_HAS_OPP" ruleSetId="RAILS_SHOP_OMS_CANADA_RS" sequenceNum="10" ruleText="hasPaymentTransaction requires hasPaymentPreference" enabled="Y" ruleType="FIELD_COMPARISON" severity="WARN" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.Rule|ruleId=RAILS_EU_COMPLETED_FULFILLED|ruleSetId=RAILS_SHOP_OMS_EU_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.Rule`

```xml
<darpan.rule.Rule ruleId="RAILS_EU_COMPLETED_FULFILLED" ruleSetId="RAILS_SHOP_OMS_EU_RS" sequenceNum="20" ruleText="isFulfilled required by isCompleted" enabled="Y" ruleType="FIELD_COMPARISON" severity="WARN" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.Rule|ruleId=RAILS_EU_PAID_HAS_OPP|ruleSetId=RAILS_SHOP_OMS_EU_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.Rule`

```xml
<darpan.rule.Rule ruleId="RAILS_EU_PAID_HAS_OPP" ruleSetId="RAILS_SHOP_OMS_EU_RS" sequenceNum="10" ruleText="hasPaymentTransaction requires hasPaymentPreference" enabled="Y" ruleType="FIELD_COMPARISON" severity="WARN" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.Rule|ruleId=RAILS_OUTLET_COMPLETED_FULFILLED|ruleSetId=RAILS_SHOP_OMS_OUTLET_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.Rule`

```xml
<darpan.rule.Rule ruleId="RAILS_OUTLET_COMPLETED_FULFILLED" ruleSetId="RAILS_SHOP_OMS_OUTLET_RS" sequenceNum="20" ruleText="isFulfilled required by isCompleted" enabled="Y" ruleType="FIELD_COMPARISON" severity="WARN" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.Rule|ruleId=RAILS_OUTLET_PAID_HAS_OPP|ruleSetId=RAILS_SHOP_OMS_OUTLET_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.Rule`

```xml
<darpan.rule.Rule ruleId="RAILS_OUTLET_PAID_HAS_OPP" ruleSetId="RAILS_SHOP_OMS_OUTLET_RS" sequenceNum="10" ruleText="hasPaymentTransaction requires hasPaymentPreference" enabled="Y" ruleType="FIELD_COMPARISON" severity="WARN" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.Rule|ruleId=RAILS_UK_COMPLETED_FULFILLED|ruleSetId=RAILS_SHOP_OMS_UK_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.Rule`

```xml
<darpan.rule.Rule ruleId="RAILS_UK_COMPLETED_FULFILLED" ruleSetId="RAILS_SHOP_OMS_UK_RS" sequenceNum="20" ruleText="isFulfilled required by isCompleted" enabled="Y" ruleType="FIELD_COMPARISON" severity="WARN" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.Rule|ruleId=RAILS_UK_PAID_HAS_OPP|ruleSetId=RAILS_SHOP_OMS_UK_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.Rule`

```xml
<darpan.rule.Rule ruleId="RAILS_UK_PAID_HAS_OPP" ruleSetId="RAILS_SHOP_OMS_UK_RS" sequenceNum="10" ruleText="hasPaymentTransaction requires hasPaymentPreference" enabled="Y" ruleType="FIELD_COMPARISON" severity="WARN" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.Rule|ruleId=RAILS_USA_COMPLETED_FULFILLED|ruleSetId=RAILS_SHOP_OMS_USA_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.Rule`

```xml
<darpan.rule.Rule ruleId="RAILS_USA_COMPLETED_FULFILLED" ruleSetId="RAILS_SHOP_OMS_USA_RS" sequenceNum="20" ruleText="isFulfilled required by isCompleted" enabled="Y" ruleType="FIELD_COMPARISON" severity="WARN" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `darpan.rule.Rule|ruleId=RAILS_USA_PAID_HAS_OPP|ruleSetId=RAILS_SHOP_OMS_USA_RS|companyUserGroupId=RAILS`
- Element: `darpan.rule.Rule`

```xml
<darpan.rule.Rule ruleId="RAILS_USA_PAID_HAS_OPP" ruleSetId="RAILS_SHOP_OMS_USA_RS" sequenceNum="10" ruleText="hasPaymentTransaction requires hasPaymentPreference" enabled="Y" ruleType="FIELD_COMPARISON" severity="WARN" companyUserGroupId="RAILS"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_BILLED_NOT_COMPLETED|enumTypeId=DarpanConclusion|enumCode=CONC_BILLED_NOT_COMPLETED`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_BILLED_NOT_COMPLETED" enumTypeId="DarpanConclusion" enumCode="CONC_BILLED_NOT_COMPLETED" description="Billed, not completed"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_COMPLETED_CLOSED_IN_NS|enumTypeId=DarpanConclusion|enumCode=CONC_COMPLETED_CLOSED_IN_NS`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_COMPLETED_CLOSED_IN_NS" enumTypeId="DarpanConclusion" enumCode="CONC_COMPLETED_CLOSED_IN_NS" description="Closed in NetSuite"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_NEVER_REACHED_NS|enumTypeId=DarpanConclusion|enumCode=CONC_NEVER_REACHED_NS`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_NEVER_REACHED_NS" enumTypeId="DarpanConclusion" enumCode="CONC_NEVER_REACHED_NS" description="Not in NetSuite"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_NOT_SHIPPED_IN_NS|enumTypeId=DarpanConclusion|enumCode=CONC_NOT_SHIPPED_IN_NS`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_NOT_SHIPPED_IN_NS" enumTypeId="DarpanConclusion" enumCode="CONC_NOT_SHIPPED_IN_NS" description="Not shipped after 24 hours"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_NO_CREDIT_MEMO_IN_NS|enumTypeId=DarpanConclusion|enumCode=CONC_NO_CREDIT_MEMO_IN_NS`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_NO_CREDIT_MEMO_IN_NS" enumTypeId="DarpanConclusion" enumCode="CONC_NO_CREDIT_MEMO_IN_NS" description="No credit memo in NetSuite"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_NS_BACKORDERED|enumTypeId=DarpanConclusion|enumCode=CONC_NS_BACKORDERED`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_NS_BACKORDERED" enumTypeId="DarpanConclusion" enumCode="CONC_NS_BACKORDERED" description="Backordered"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_NS_NO_LINE_LOCATION|enumTypeId=DarpanConclusion|enumCode=CONC_NS_NO_LINE_LOCATION`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_NS_NO_LINE_LOCATION" enumTypeId="DarpanConclusion" enumCode="CONC_NS_NO_LINE_LOCATION" description="No location"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_PARTIAL_PAYMENT_NO_NS|enumTypeId=DarpanConclusion|enumCode=CONC_PARTIAL_PAYMENT_NO_NS`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_PARTIAL_PAYMENT_NO_NS" enumTypeId="DarpanConclusion" enumCode="CONC_PARTIAL_PAYMENT_NO_NS" description="Partial payment"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_SHIPPED_NOT_BILLED|enumTypeId=DarpanConclusion|enumCode=CONC_SHIPPED_NOT_BILLED`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_SHIPPED_NOT_BILLED" enumTypeId="DarpanConclusion" enumCode="CONC_SHIPPED_NOT_BILLED" description="Shipped, not billed"/>
```

### Added in `data/RailsAuditData.xml`

- Record: `moqui.basic.Enumeration|enumId=CONC_SHOPIFY_ORDER_NOT_IN_OMS|enumTypeId=DarpanConclusion|enumCode=CONC_SHOPIFY_ORDER_NOT_IN_OMS`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="CONC_SHOPIFY_ORDER_NOT_IN_OMS" enumTypeId="DarpanConclusion" enumCode="CONC_SHOPIFY_ORDER_NOT_IN_OMS" description="Not in HotWax"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUTOMATION_STREAK_BADGE|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUTOMATION_STREAK_BADGE" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.AutomationStreakBadge" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_TENANT_CONCLUSION|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_TENANT_CONCLUSION" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.rule.TenantConclusion" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_TENANT_STREAK_FACT|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_TENANT_STREAK_FACT" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.TenantStreakFact" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_TENANT_STREAK_QUEST|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_TENANT_STREAK_QUEST" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.reconciliation.TenantStreakQuest" filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Modified in `data/SourceSystemConnectorSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnector|systemEnumId=SHOPIFY_RETURN_REFS|remoteId=SHOPIFY_REMOTE`
- Element: `darpan.reconciliation.SourceSystemConnector`

```xml
<darpan.reconciliation.SourceSystemConnector systemEnumId="SHOPIFY_RETURN_REFS" extractServiceName="reconciliation.ShopifyOrderExtractionServices.extract#ShopifyOrderReturnRefs" dateFromParameterName="windowStart" dateToParameterName="windowEnd" expectedSourceConfigType="SHOPIFY_RETURN_REFS_API" configParameterName="shopifyAuthConfigId" configEntityName="darpan.shopify.ShopifyAuthConfig" remoteId="SHOPIFY_REMOTE" endpointLabel="Returns" systemAliases="SHOPIFY_RETURN_REFS,SHOPIFY_RETURNS,DAR_SYS_SHOPIFY_RETURN_REFS" preserveWindowInstants="N" filterParameterName="sourceFilters" keepFieldsBase="refundOrReturnId,refundOrReturnType,orderId,createdAt,orderCancelledAt,refundLineEverFulfilled,orderReturnStatus" lookupServiceName="reconciliation.ShopifyOrderExtractionServices.lookup#ShopifyRefundOrReturnIds" lookupIdsParameterName="refundOrReturnIds" lookupMaxIds="50000" enabled="Y" extractOptionsJson='[{"name":"completedReturns","label":"Completed returns, one row per order: refunded, exchanged or orderRefunded","type":"text"}]'/>
```

## Recommended operator review

- Confirm every candidate record truly needs to be loaded for the target environment.
- Keep final upgrade records reflected in the appropriate generic source data file, such as a type, security, mapping, job, or system-message seed file.
- Prefer keeping changes in the existing domain seed file unless the release needs a distinct generic setup bundle.
- State the final operator action in `release-notes.md` and `release-checklist.md`.
