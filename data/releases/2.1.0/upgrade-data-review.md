# Upgrade Data Review For Darpan 2.1.0

## Scope

- Backend compare range: `v2.0.3..HEAD`
- Data directory reviewed: `data`
- Generic source data files are the source of truth for release upgrade data.
- This report lists candidate seed/config records that were added or modified in generic source data files between the compared refs.
- Do not author records directly in `upgrade-data.xml`; add or update the appropriate `runtime/component/darpan/data/*.xml` file and regenerate.

## Candidate records

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=UgtDarpanPermission|enumTypeId=UserGroupType`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="UgtDarpanPermission" description="Darpan permission groups" enumTypeId="UserGroupType"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.UserGroup|userGroupId=DARPAN_ADMIN|groupTypeEnumId=UgtDarpanPermission`
- Element: `moqui.security.UserGroup`

```xml
<moqui.security.UserGroup userGroupId="DARPAN_ADMIN" description="Can manage Darpan app-level settings and core configuration" groupTypeEnumId="UgtDarpanPermission"/>
```

### Modified in `data/SecuritySeedData.xml`

- Record: `moqui.security.UserGroup|userGroupId=DARPAN_SUPER_ADMIN|groupTypeEnumId=UgtDarpanPermission`
- Element: `moqui.security.UserGroup`

```xml
<moqui.security.UserGroup userGroupId="DARPAN_SUPER_ADMIN" description="Can view and edit every tenant and manage tenant and user setup" groupTypeEnumId="UgtDarpanPermission"/>
```

## Recommended operator review

- Confirm every candidate record truly needs to be loaded for the target environment.
- Keep final upgrade records reflected in the appropriate generic source data file, such as a type, security, mapping, job, or system-message seed file.
- Prefer keeping changes in the existing domain seed file unless the release needs a distinct generic setup bundle.
- State the final operator action in `release-notes.md` and `release-checklist.md`.
