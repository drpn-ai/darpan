# Upgrade Data Review For Darpan 3.4.13

## Scope

- Backend compare range: `v3.4.12..HEAD`
- Data directory reviewed: `data`
- Generic source data files are the source of truth for release upgrade data.
- This report lists candidate seed/config records that were added or modified in generic source data files between the compared refs.
- Do not author records directly in `upgrade-data.xml`; add or update the appropriate `runtime/component/darpan/data/*.xml` file and regenerate.

## Candidate records

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_TENANT_LOOKUP_LINK|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_TENANT_LOOKUP_LINK" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.lookup.TenantLookupLink" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_TENANT_LOOKUP_READ|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_TENANT_LOOKUP_READ" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.lookup.TenantLookupRead" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

## Recommended operator review

- Confirm every candidate record truly needs to be loaded for the target environment.
- Keep final upgrade records reflected in the appropriate generic source data file, such as a type, security, mapping, job, or system-message seed file.
- Prefer keeping changes in the existing domain seed file unless the release needs a distinct generic setup bundle.
- State the final operator action in `release-notes.md` and `release-checklist.md`.

## Operator action

- None by hand: the two records load on boot with `data/upgrade-data.xml`. The two lookup tables
  (`darpan.lookup.TenantLookupRead`, `TenantLookupLink`) are created on boot. The order lookup's product reads and links
  load on boot from the system components (`darpan-hotwax` `HotWaxLookupSeedData.xml`, `shopify-darpan`
  `ShopifyLookupSeedData.xml`, `netsuite-darpan` `NetSuiteLookupSeedData.xml`, type `darpan-seed`). No migration.
