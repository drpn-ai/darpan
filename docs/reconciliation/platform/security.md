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

## Data Isolation

- Tenant-scoped storage uses the active Darpan tenant user group as the working scope.
- Tenant-owned rows currently use `companyUserGroupId`; the term `tenant` is the normalized operational term.
- Entity filters in `DARPAN_ACTIVE_COMPANY_SCOPE` filter direct tenant-owned rows by `ec.user.context.activeTenantUserGroupId`.
- Separate SFTP and NetSuite credentials must be configured per tenant because `SftpServer`, `NsAuthConfig`, and `NsRestletConfig` are tenant-owned settings.
- Rule packages, saved mappings/runs, schemas, generated result metadata, and output visibility must remain isolated per active tenant.
- `ALL_USERS` must never be used as a fallback tenant data-view scope.

## Access Control

- Tenant identity and permission level are separate.
- Tenant identity is represented by `moqui.security.UserGroup` rows with `groupTypeEnumId="UgtDarpanCompany"`.
- User tenant membership is represented by `moqui.security.UserGroupMember`.
- Tenant-specific edit/view permissions are represented by `darpan.auth.TenantUserPermissionGroupMember`.
- `DARPAN_COMPANY_EDITOR` enables create, update, run, and delete actions in the active tenant.
- `DARPAN_COMPANY_VIEW_ONLY` allows reads but must fail closed for tenant write actions.
- Super-admin users can administer global settings, but tenant-owned data still follows the active tenant.
- Super-admin tenant setup uses authenticated Auth facade services:
  `list#Tenants`, `save#Tenant`, `save#TenantUser`, and `save#TenantUserPermissionGroup`.
  These services manage only `moqui.security.UserGroup`, `moqui.security.UserGroupMember`,
  and `darpan.auth.TenantUserPermissionGroupMember`; Darpan does not maintain a separate company entity.
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
- Before handing a tenant to production users, verify active-tenant session metadata, SFTP isolation, NetSuite auth/endpoint isolation, schema/mapping/result isolation, and view-only write rejection.
- Verify AI/LLM settings, enum/global settings, app-wide operational config, and `HcReadDbConfig` are available only to super-admin users.
- Treat `HcReadDbConfig` as admin-global/deferred until it has explicit tenant ownership and migration design.

## Production Verification

The operational verification checklist lives in [Production Settings Surfaces](production-settings-surfaces.md). At minimum, verify:

- `get#SessionInfo` returns the intended `activeTenantUserGroupId`, `availableTenants`, `activeTenantPermissionGroupIds`, `canEditActiveTenantData`, and `isSuperAdmin`.
- tenant A and tenant B cannot see each other's SFTP, NetSuite auth, NetSuite endpoint, schema, saved-run/mapping, or result records.
- a view-only active tenant can list/read tenant records but cannot save settings or run/delete tenant data where write access is required.
- non-super-admin users cannot call `get#LlmSettings`, `save#LlmSettings`, `list#EnumOptions`, `list#HcReadDbConfigs`, or `save#HcReadDbConfig`.

## Next Steps

- Keep production tenant setup aligned with [Tenant-Scoped Access and User-Level Preferences](company-scoped-access-and-user-preferences.md)
- Choose the vault and logging stack
- Document security requirements for integrations
