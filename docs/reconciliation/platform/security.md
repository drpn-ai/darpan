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
- Credential sharing between tenants: `runtime/component/darpan/service/facade/ConfigSharingFacadeServices.xml` (`darpan.auth.ConfigTenantAccess`)
- Operator (super-admin) surface: `runtime/component/darpan/service/admin/` (`admin.*`, fenced by the `DARPAN_ADMIN_API` artifact group)
- JSON-RPC namespace fence: `runtime/component/darpan/src/main/groovy/darpan/facade/common/RpcMethodFence.groovy` (see [API Access Policy](api-access-policy.md))
- Tenant-scoped reads in Groovy: `runtime/component/darpan/src/main/groovy/darpan/facade/common/TenantScopedFinder.groovy`
- Target role, permission group, and membership contract: [Darpan Permissions Matrix](permissions-matrix.md)
- Tenant setup tutorial: [Darpan Tenant Setup Tutorial](tenant-setup.md)
- Tenant user setup tutorial: [Darpan Tenant User Setup Tutorial](tenant-user-setup.md)

## Data Isolation

- Tenant-scoped storage uses the active Darpan tenant user group as the working scope.
- Tenant-owned rows currently use `companyUserGroupId`; the term `tenant` is the normalized operational term.
- Entity filters in `DARPAN_ACTIVE_COMPANY_SCOPE` filter direct tenant-owned rows by `ec.user.context.activeTenantUserGroupId`.
- Source credentials are owned by one tenant and can be shared with others explicitly:
  - HotWax OMS, Shopify, NetSuite auth and NetSuite RESTlet configs are shared through
    `facade.ConfigSharingFacadeServices.grant#ConfigTenantAccess` (`ConfigTenantAccess` rows,
    `SCFG_*` config types). The caller must be a tenant admin of the target tenant and of a tenant
    that already uses the config. Sharing widens access to the same encrypted credentials; it is
    not a copy. Revoking sets `thruDate` and keeps the row as history.
  - SFTP servers can be shared with a tenant group through `SftpServerTenantAccess`.
  - A config with no grant is visible only to its owning tenant.
- Rule packages, saved mappings/runs, schemas, generated result metadata, and output visibility must remain isolated per active tenant.
- `ALL_USERS` must never be used as a fallback tenant data-view scope.

## Access Control

- Tenant identity and permission level are separate; the full role and membership contract lives in [Darpan Permissions Matrix](permissions-matrix.md).
- Tenant identity is represented by `moqui.security.UserGroup` rows with `groupTypeEnumId="UgtDarpanCompany"`.
- User tenant association is represented by `moqui.security.UserGroupMember` and controls the tenant switcher for non-super-admin users.
- Tenant-specific role assignment is represented by `darpan.auth.TenantUserPermissionGroupMember`.
- The target tenant roles are Super Admin, Tenant Admin, and Tenant User. Darpan Admin is a separate app-level settings capability.
- User login identity is represented by `moqui.security.UserAccount`; create accounts and reset passwords through the admin app or `admin.UserAdminServices` (always with an email address). Stock Moqui user services such as `update#Password` are not reachable over `/rpc/json`: the endpoint dispatches only `facade.*`, `admin.*` and `gql.*` and answers anything else with HTTP 403. Users change their own password with `facade.AuthFacadeServices.change#OwnPassword`.
- Super-admin checks accept Moqui `ADMIN` and `DARPAN_SUPER_ADMIN`. Darpan app-admin checks accept Moqui `ADMIN` and `DARPAN_ADMIN`. A `DARPAN_SUPER_ADMIN` or `DARPAN_ADMIN` user still needs `DARPAN_USER` base app membership unless the user also has Moqui `ADMIN` artifact access. Tenant permission seed data includes `DARPAN_TENANT_ADMIN` and `DARPAN_TENANT_USER`.
- Legacy `DARPAN_COMPANY_EDITOR` continues to grant Tenant Admin behavior, and legacy `DARPAN_COMPANY_VIEW_ONLY` remains view-only.
- Tenant User is not the same as a pure view-only role: this user can upload files and run reconciliation, but cannot mutate saved runs, schemas, rules, settings, users, tenants, or Darpan core configuration.
- Darpan-admin users can administer app-level settings and private Darpan core management screens. Super-admin users administer tenants and tenant-owned operational data through an active tenant unless a private admin screen intentionally aggregates across tenants.
- Tenant and user management is the operator surface `admin.*` (used by the Darpan admin app):
  `admin.TenantAdminServices.create#Tenant`, `admin.UserAdminServices.create#UserAccount`,
  `admin.MembershipAdminServices.add#TenantMember` and their update/disable/remove siblings. Only
  `DARPAN_SUPER_ADMIN` and Moqui `ADMIN` can call them, each call re-checks super-admin in code,
  and each write records a `darpan.admin.AdminAuditLog` row. A data load is the fallback only; see
  [Darpan Tenant Setup Tutorial](tenant-setup.md). Darpan does not maintain a separate company
  entity: a tenant is a `UserGroup` plus its `TenantSetting`.
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
- Before handing a tenant to production users, verify active-tenant session metadata, SFTP isolation, source-credential isolation (and any intended sharing grants), schema/mapping/result isolation, and Tenant User mutation rejection.
- Verify AI/LLM settings, enum/global settings, and app-wide operational config are available only to Darpan-admin users.

## Production Verification

The operational verification checklist lives in [Production Settings Surfaces](production-settings-surfaces.md). At minimum, verify:

- `get#SessionInfo` returns the intended `activeTenantUserGroupId`, `availableTenants`, `activeTenantPermissionGroupIds`, `canViewActiveTenantData`, `canRunActiveTenantReconciliation`, `canEditActiveTenantData`, `canManageDarpanCore`, and `isSuperAdmin`.
- tenant A and tenant B cannot see each other's SFTP, NetSuite, Shopify, HotWax OMS, schema, saved-run/mapping, or result records unless a sharing grant says so.
- a Tenant User active tenant can list/read tenant records and run reconciliation, but cannot save settings or create/update/delete tenant configuration.
- users without Darpan Admin access cannot call `get#LlmSettings`, `save#LlmSettings`, or `list#EnumOptions`.

## Next Steps

- Keep production tenant setup aligned with [Darpan Tenant Setup Tutorial](tenant-setup.md) and [Tenant-Scoped Access and User-Level Preferences](company-scoped-access-and-user-preferences.md)
- Choose the vault and logging stack
- Document security requirements for integrations
