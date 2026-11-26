# Upgrade Data Review For Darpan 3.5.0

## Scope

- Backend compare range: `v3.4.17..HEAD`
- Data directory reviewed: `data`
- Generic source data files are the source of truth for release upgrade data.
- This report lists candidate seed/config records that were added or modified in generic source data files between the compared refs.
- Do not author records directly in `upgrade-data.xml`; add or update the appropriate `runtime/component/darpan/data/*.xml` file and regenerate.

## Candidate records

### Added in `data/ReconciliationJobSeedData.xml`

- Record: `moqui.service.job.ServiceJob|jobName=maintain_IdIndex_nightly`
- Element: `moqui.service.job.ServiceJob`

```xml
<moqui.service.job.ServiceJob jobName="maintain_IdIndex_nightly" expireLockTime="360" description="Index kept copies the ID index has not, then drop ids no read has seen for darpan.idindex.months." serviceName="idindex.IdIndexServices.maintain#IdIndex" defaultCronExpression="0 30 2 * * ?" defaultPaused="Y" minIntervalMinutes="60"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_RECORD_ID_INDEX_CURSOR|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_RECORD_ID_INDEX_CURSOR" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.idindex.RecordIdIndexCursor" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_RECORD_ID_INDEX|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_RECORD_ID_INDEX" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.idindex.RecordIdIndex" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

### Added in `data/SecuritySeedData.xml`

- Record: `moqui.security.EntityFilter|entityFilterId=DARPAN_SCOPE_TENANT_ID_INDEX_FIELD|entityFilterSetId=DARPAN_ACTIVE_COMPANY_SCOPE`
- Element: `moqui.security.EntityFilter`

```xml
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_TENANT_ID_INDEX_FIELD" entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE" entityName="darpan.idindex.TenantIdIndexField" filterMap="[companyUserGroupId: activeTenantUserGroupId ?: '__NO_ACTIVE_TENANT__']"/>
```

## Recommended operator review

- Confirm every candidate record truly needs to be loaded for the target environment.
- Keep final upgrade records reflected in the appropriate generic source data file, such as a type, security, mapping, job, or system-message seed file.
- Prefer keeping changes in the existing domain seed file unless the release needs a distinct generic setup bundle.
- State the final operator action in `release-notes.md` and `release-checklist.md`.

## Recommended operator review

- `maintain_IdIndex_nightly` arrives paused (`defaultPaused="Y"`, 02:30): resume it with `darpan-admin job resume` when the
  index should be maintained nightly. The three EntityFilters scope the ID index tables to the active tenant.

## Operator action

- After the boot: run `run#PendingMigrations` (dry run first) so `ACK_NOTE_NULLABLE` makes
  `AUDIT_FINDING_ACKNOWLEDGEMENT.NOTE` nullable (registered by 3.4.16, fixed for MySQL here). Then app `v4.4.15`.
- The ID index tables are created on boot. The old lookup tables (LookupRead/LookupLink) and their 3.4.13 filters stay,
  unused.
