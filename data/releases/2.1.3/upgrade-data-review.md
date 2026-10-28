# Upgrade Data Review For Darpan 2.1.3

## Scope

- Backend compare range: `v2.1.2..HEAD`
- Data directory reviewed: `data`
- Generic source data files are the source of truth for release upgrade data.
- This report lists candidate seed/config records that were added or modified in generic source data files between the compared refs.
- Do not author records directly in `upgrade-data.xml`; add or update the appropriate `runtime/component/darpan/data/*.xml` file and regenerate.

## Candidate records

### Added in `data/SourceSystemConnectorFieldSeedData.xml`

- Record: `darpan.reconciliation.SourceSystemConnectorField|systemEnumId=OMS_GQL|fieldPath=$.records[*].salesChannelEnumId`
- Element: `darpan.reconciliation.SourceSystemConnectorField`

```xml
<darpan.reconciliation.SourceSystemConnectorField systemEnumId="OMS_GQL" fieldPath="$.records[*].salesChannelEnumId" label="Sales channel" fieldType="string" sequenceNum="12" description="DAR-BE-153: NOT in keepFieldsBase; selected only when a filter or rule reads it. On the Order type since the 2026-10-28 moqui-gql upgrade; Steve Madden's daily order run excludes POS_SALES_CHANNEL on it."/>
```

## Recommended operator review

- Confirm every candidate record truly needs to be loaded for the target environment.
- Keep final upgrade records reflected in the appropriate generic source data file, such as a type, security, mapping, job, or system-message seed file.
- Prefer keeping changes in the existing domain seed file unless the release needs a distinct generic setup bundle.
- State the final operator action in `release-notes.md` and `release-checklist.md`.
