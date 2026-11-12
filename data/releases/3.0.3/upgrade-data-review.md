# Upgrade Data Review For Darpan 3.0.3

## Scope

- Backend compare range: `v3.0.2..HEAD`
- Data directory reviewed: `data`
- Generic source data files are the source of truth for release upgrade data.
- This report lists candidate seed/config records that were added or modified in generic source data files between the compared refs.
- Do not author records directly in `upgrade-data.xml`; add or update the appropriate `runtime/component/darpan/data/*.xml` file and regenerate.

## Candidate records

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_GQL|fieldPath=$.records[*].itemQuantity`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_GQL" fieldPath="$.records[*].itemQuantity" label="Item quantity" fieldType="string" sequenceNum="13" description="DAR-BE-251: the order's units, its item rows' quantities summed (HotWax keeps one row per unit), read from the items by order id only when a rule reads it. Compared against Shopify itemQuantity (Item Sync: itemQuantity = itemQuantity); orderItemCount is a copy of Shopify's line count, not this."/>
```

### Added in `data/SourceSystemConnectorSeedDataFields.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=SHOPIFY|fieldPath=$.records[*].itemQuantity`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="SHOPIFY" fieldPath="$.records[*].itemQuantity" label="Item quantity" fieldType="String" sequenceNum="12" description="DAR-BE-251: units ordered (Order.subtotalLineItemsQuantity, the sum of line quantities as ordered, before refunds or removals). Compared against HotWax itemQuantity (Item Sync: itemQuantity = itemQuantity)."/>
```

## Recommended operator review

- Confirm every candidate record truly needs to be loaded for the target environment.
- Keep final upgrade records reflected in the appropriate generic source data file, such as a type, security, mapping, job, or system-message seed file.
- Prefer keeping changes in the existing domain seed file unless the release needs a distinct generic setup bundle.
- State the final operator action in `release-notes.md` and `release-checklist.md`.
