# Upgrade Data Review For Darpan 2.0.0

## Scope

- Data source of truth: `data/SecuritySeedData.xml`
- Current load target: `data/upgrade-data.xml`
- Release copy: `data/releases/2.0.0/upgrade-data.xml`
- Review status: production-safe additive seed records only.

## Recommended operator review

- The reusable Darpan tenant setup is defined in generic data, not only in release-local upgrade data.
- No default tenant fixture such as `GORJANA` or `KREWE` is seeded for production.
- No demo user membership, tenant permission assignment, or active-tenant preference is seeded for `EX_JOHN_DOE`.
- The remaining `ALL_USERS` seed record is limited to `DARPAN_AUTH_API` for auth facade access; it does not grant tenant data access.
- Smoke-test RuleSet compare-scope fixtures are excluded from this production upgrade payload.

## Candidate records

### Darpan App Entity Access

```xml
<moqui.security.ArtifactGroupMember artifactGroupId="DARPAN_APP"
        artifactName="darpan.rule.RuleSet" artifactTypeEnumId="AT_ENTITY" inheritAuthz="Y"/>
<moqui.security.ArtifactGroupMember artifactGroupId="DARPAN_APP"
        artifactName="darpan.reconciliation.ReconciliationRunResult" artifactTypeEnumId="AT_ENTITY" inheritAuthz="Y"/>
```

### Active Tenant Entity Filters

```xml
<moqui.security.EntityFilterSet entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE"
        description="Filter Darpan tenant-owned rows by the active tenant in user context"/>
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_RULE_SET"
        entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE"
        entityName="darpan.rule.RuleSet"
        filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
<moqui.security.EntityFilter entityFilterId="DARPAN_SCOPE_RUN_RESULT"
        entityFilterSetId="DARPAN_ACTIVE_COMPANY_SCOPE"
        entityName="darpan.reconciliation.ReconciliationRunResult"
        filterMap="[companyUserGroupId: activeTenantUserGroupId]"/>
```

### Tenant Permission Groups

```xml
<moqui.basic.Enumeration enumId="UgtDarpanPermission"
        description="Darpan tenant permission groups"
        enumTypeId="UserGroupType"/>
<moqui.security.UserGroup userGroupId="DARPAN_COMPANY_EDITOR"
        description="Can create, update, run, and delete tenant-scoped Darpan data"
        groupTypeEnumId="UgtDarpanPermission"/>
<moqui.security.UserGroup userGroupId="DARPAN_COMPANY_VIEW_ONLY"
        description="Can view tenant-scoped Darpan data but cannot mutate it"
        groupTypeEnumId="UgtDarpanPermission"/>
```

### Tenant Type And User Preference Keys

```xml
<moqui.basic.Enumeration enumId="UgtDarpanCompany"
        description="Darpan tenant groups"
        enumTypeId="UserGroupType"/>
<moqui.basic.Enumeration enumId="darpan.auth.activeTenantUserGroupId"
        description="Preferred active tenant user group id"
        enumTypeId="UserPreferenceKey"/>
<moqui.basic.Enumeration enumId="darpan.dashboard.pinnedMappingIds"
        description="Pinned dashboard reconciliation mappings"
        enumTypeId="UserPreferenceKey"/>
```
