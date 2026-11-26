# Upgrade Data Review For Darpan 3.5.3

## Scope

- Backend compare range: `v3.5.2..HEAD`
- Data directory reviewed: `data`
- Generic source data files are the source of truth for release upgrade data.
- This report lists candidate seed/config records that were added or modified in generic source data files between the compared refs.
- Do not author records directly in `upgrade-data.xml`; add or update the appropriate `runtime/component/darpan/data/*.xml` file and regenerate.

## Candidate records

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.EnumerationType|enumTypeId=AuditSharedRead`
- Element: `moqui.basic.EnumerationType`

```xml
<moqui.basic.EnumerationType enumTypeId="AuditSharedRead" description="Audit shared read status"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDSHR_DONE|enumTypeId=AuditSharedRead|enumCode=done`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDSHR_DONE" enumTypeId="AuditSharedRead" enumCode="done" description="Read"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDSHR_FAILED|enumTypeId=AuditSharedRead|enumCode=failed`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDSHR_FAILED" enumTypeId="AuditSharedRead" enumCode="failed" description="Failed"/>
```

### Added in `data/AuditSeedData.xml`

- Record: `moqui.basic.Enumeration|enumId=AUDSHR_READING|enumTypeId=AuditSharedRead|enumCode=reading`
- Element: `moqui.basic.Enumeration`

```xml
<moqui.basic.Enumeration enumId="AUDSHR_READING" enumTypeId="AuditSharedRead" enumCode="reading" description="Being read"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_AUD_SHARED_READ|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_AUD_SHARED_READ" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.audit.AuditSharedRead" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

## Recommended operator review

- Confirm every candidate record truly needs to be loaded for the target environment.
- Keep final upgrade records reflected in the appropriate generic source data file, such as a type, security, mapping, job, or system-message seed file.
- Prefer keeping changes in the existing domain seed file unless the release needs a distinct generic setup bundle.
- State the final operator action in `release-notes.md` and `release-checklist.md`.

## Recommended operator review

- The EnumerationType `AuditSharedRead` loads before its three statuses (FK order in the file). The table
  `darpan.audit.AuditSharedRead` and the `NsSuiteQlSourceQuery` columns `omsChangedStatusIds`, `liveLineStates` are created on
  boot (nullable).

## Operator action

- After the boot: run `run#PendingMigrations` (dry run first) for `ACK_NOTE_NULLABLE`; then app `v4.4.15`.
