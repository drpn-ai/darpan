# Reconciliation Platform Security

## Goal

Protect client data end-to-end and prevent cross-client leakage while keeping the platform operable at scale.

## Core Principles

- Least privilege for services, users, and integrations
- Strong tenant isolation by default
- Encrypt data in transit and at rest
- Auditability for all access and changes

## Code Touchpoints (Current Repo)

- Remote system credentials and endpoints: `framework/entity/ServiceEntities.xml` (SystemMessageRemote; encrypted fields include `password` and `privateKey`)
- User groups and permissions: `framework/entity/SecurityEntities.xml`
- Darpan tenant groups, tenant permission groups, and entity filters: `runtime/component/darpan/data/SecuritySeedData.xml`
- Per-tenant permission membership: `runtime/component/darpan/entity/AuthEntities.xml` (`darpan.auth.TenantUserPermissionGroupMember`)
- Tenant-owned reconciliation settings and result metadata: `runtime/component/darpan/entity/ReconciliationEntities.xml`
- Active-tenant session contract: `runtime/component/darpan/service/facade/AuthFacadeServices.xml`
- Settings facade access gates: `runtime/component/darpan/service/facade/SettingsFacadeServices.xml`
- Target role, permission group, and membership contract: [Darpan Permissions Matrix](permissions-matrix.md)
- Tenant setup tutorial: [Darpan Tenant Setup Tutorial](tenant-setup.md)
- Tenant user setup tutorial: [Darpan Tenant User Setup Tutorial](tenant-user-setup.md)

## Data Isolation

- Tenant-scoped storage uses the active Darpan tenant user group as the working scope.
- Tenant-owned rows currently use `companyUserGroupId`; the term `tenant` is the normalized operational term.
- Entity filters in `DARPAN_ACTIVE_COMPANY_SCOPE` filter direct tenant-owned rows by `ec.user.context.activeTenantUserGroupId`.
- Separate SFTP and NetSuite credentials must be configured per tenant because `SftpServer`, `NsAuthConfig`, and `NsRestletConfig` are tenant-owned settings.
- Rule packages, saved mappings/runs, schemas, generated result metadata, and output visibility must remain isolated per active tenant.
- `ALL_USERS` must never be used as a fallback tenant data-view scope.

## Access Control

- Tenant identity and permission level are separate; the full role and membership contract lives in [Darpan Permissions Matrix](permissions-matrix.md).
- Tenant identity is represented by `moqui.security.UserGroup` rows with `groupTypeEnumId="UgtDarpanCompany"`.
- User tenant association is represented by `moqui.security.UserGroupMember` and controls the tenant switcher for non-super-admin users.
- Tenant-specific role assignment is represented by `darpan.auth.TenantUserPermissionGroupMember`.
- The target tenant roles are Super Admin, Tenant Admin, and Tenant User. Darpan Admin is a separate app-level settings capability.
- User login identity is represented by `moqui.security.UserAccount`; create or reset passwords only through approved user services, admin UI, or identity-provider flows.
- Super-admin checks accept Moqui `ADMIN` and `DARPAN_SUPER_ADMIN`. Darpan app-admin checks accept Moqui `ADMIN` and `DARPAN_ADMIN`. A `DARPAN_SUPER_ADMIN` or `DARPAN_ADMIN` user still needs `DARPAN_USER` base app membership unless the user also has Moqui `ADMIN` artifact access. Tenant permission seed data includes `DARPAN_TENANT_ADMIN` and `DARPAN_TENANT_USER`.
- Legacy `DARPAN_COMPANY_EDITOR` continues to grant Tenant Admin behavior, and legacy `DARPAN_COMPANY_VIEW_ONLY` remains view-only.
- Tenant User is not the same as a pure view-only role: this user can upload files and run reconciliation, but cannot mutate saved runs, schemas, rules, settings, users, tenants, or Darpan core configuration.
- Darpan-admin users can administer app-level settings and private Darpan core management screens. Super-admin users administer tenants and tenant-owned operational data through an active tenant unless a private admin screen intentionally aggregates across tenants.
- When the target environment exposes tenant-management facade services, super-admin tenant setup should use authenticated Auth facade services:
  `list#Tenants`, `save#Tenant`, `save#TenantUser`, and `save#TenantUserPermissionGroup`.
  These services manage only `moqui.security.UserGroup`, `moqui.security.UserGroupMember`,
  and `darpan.auth.TenantUserPermissionGroupMember`; Darpan does not maintain a separate company entity.
- If those services are not present in the target environment, use the generic-data setup path in [Darpan Tenant Setup Tutorial](tenant-setup.md).
- Short-lived credentials for services
- Environment-level separation (dev, staging, prod)

## Data Protection

- TLS for all ingestion endpoints (SFTP, API, GraphQL)
- Encryption at rest for databases and file storage
- Secrets stored in a managed vault, never in code

## Audit and Monitoring

- Log all data access and rule changes
- Alert on unusual access patterns or data volumes
- Regular access reviews and key rotation

## Operational Practices

- Minimize PII in reconciliation datasets
- Mask or tokenize sensitive fields when possible
- Retention policies aligned to client agreements
- Before handing a tenant to production users, verify active-tenant session metadata, SFTP isolation, NetSuite auth/endpoint isolation, schema/mapping/result isolation, and Tenant User mutation rejection.
- Verify AI/LLM settings, enum/global settings, and app-wide operational config are available only to Darpan-admin users.

## Production Verification

The operational verification checklist lives in [Production Settings Surfaces](production-settings-surfaces.md). At minimum, verify:

- `get#SessionInfo` returns the intended `activeTenantUserGroupId`, `availableTenants`, `activeTenantPermissionGroupIds`, `canViewActiveTenantData`, `canRunActiveTenantReconciliation`, `canEditActiveTenantData`, `canManageDarpanCore`, and `isSuperAdmin`.
- tenant A and tenant B cannot see each other's SFTP, NetSuite auth, NetSuite endpoint, schema, saved-run/mapping, or result records.
- a Tenant User active tenant can list/read tenant records and run reconciliation, but cannot save settings or create/update/delete tenant configuration.
- users without Darpan Admin access cannot call `get#LlmSettings`, `save#LlmSettings`, or `list#EnumOptions`.

## Next Steps

- Keep production tenant setup aligned with [Darpan Tenant Setup Tutorial](tenant-setup.md) and [Tenant-Scoped Access and User-Level Preferences](company-scoped-access-and-user-preferences.md)
- Choose the vault and logging stack
- Document security requirements for integrations
