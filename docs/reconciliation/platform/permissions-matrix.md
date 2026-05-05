# Darpan Permissions Matrix

## Purpose

This document defines the Darpan permission contract for tenant data, tenant-wide administration, Darpan app administration, and user-visible page/action access.

It is the role matrix for DAR-220 and the implementation contract for the first permission foundation slices in DAR-221 and DAR-225.

## Role Contract

| Role | Scope | Tenant switching | Core access |
| --- | --- | --- | --- |
| Darpan Admin | App-wide. This is the Darpan app-level settings administrator. | Does not grant tenant switching by itself. Combine with Super Admin or tenant membership when tenant data access is also needed. | Can view and edit Darpan core/app-level settings such as AI/LLM provider settings and global enum/system options. |
| Super Admin | Tenant-wide administration across Darpan tenants. | Can access every configured Darpan tenant. Tenant-owned operational pages still use an active tenant unless a private admin screen intentionally aggregates across tenants. | Can view and edit all tenant data and tenant setup. Does not grant Darpan app-level settings unless the user also has Darpan Admin access or Moqui `ADMIN`. |
| Tenant Admin | Tenant-scoped. Applies only to tenants the user is associated with. | Can switch only between associated tenant `UserGroup` records returned by the session `availableTenants` contract. | Can view and edit tenant-owned data, tenant settings, saved runs, schemas, rules, and result records for the active tenant. Cannot access super-admin-only platform management. |
| Tenant User | Tenant-scoped. Applies only to tenants the user is associated with. | Can switch only between associated tenant `UserGroup` records returned by the session `availableTenants` contract. | Can view tenant-relevant pages, upload input files, run reconciliation, and view/download results. Cannot create, update, or delete saved runs, schemas, rules, tenant settings, user/tenant assignments, or platform settings. |

The user settings page must only present tenant switching options from `availableTenants`. For non-super-admin users, `availableTenants` is derived from tenant `UserGroupMember` associations. For super-admin users, it may include all configured Darpan tenant groups.

## Current and Target Group Vocabulary

The tenant access vocabulary is three roles: `DARPAN_SUPER_ADMIN`, `DARPAN_TENANT_ADMIN`, and `DARPAN_TENANT_USER`. Darpan app-level settings use the separate `DARPAN_ADMIN` capability so they can be granted independently from Super Admin.

Current implementation notes:

- Super-admin checks accept membership in Moqui `ADMIN` or `DARPAN_SUPER_ADMIN` through `TenantAccessSupport.isSuperAdmin(ec)`.
- Darpan app-admin checks accept membership in Moqui `ADMIN` or `DARPAN_ADMIN` through `TenantAccessSupport.isDarpanAdmin(ec)`.
- Permission seed data includes `DARPAN_ADMIN`, `DARPAN_TENANT_ADMIN`, and `DARPAN_TENANT_USER` under `UgtDarpanPermission`.
- `DARPAN_COMPANY_EDITOR` remains a legacy alias for Tenant Admin behavior while existing assignments are migrated.
- `DARPAN_COMPANY_VIEW_ONLY` remains a legacy view-only role. It is not equivalent to Tenant User because Tenant User can upload files and run reconciliation.
- `DARPAN_USER` remains the base authenticated Darpan app group. It is not a tenant role by itself, but a `DARPAN_SUPER_ADMIN` user still needs either Moqui `ADMIN` or `DARPAN_USER` facade access to use the PWA.
- `ALL_USERS` is allowed only for login/auth bootstrap surfaces that are safe for authenticated session establishment. It must not grant tenant data access.

## Moqui Security Model

| Concept | Moqui record | Purpose |
| --- | --- | --- |
| Login identity | `moqui.security.UserAccount` | The user identity used for authentication and session context. |
| Base app access | `moqui.security.UserGroup` plus `moqui.security.UserGroupMember` for `DARPAN_USER` | Allows an authenticated user to use Darpan facade/app surfaces. Does not grant tenant data rights by itself. |
| Darpan app-admin access | `UserGroupMember(userGroupId="ADMIN")` or `UserGroupMember(userGroupId="DARPAN_ADMIN")` | Grants Darpan app-level settings and core configuration access. |
| Super-admin access | `UserGroupMember(userGroupId="ADMIN")` or `UserGroupMember(userGroupId="DARPAN_SUPER_ADMIN")` | Grants tenant-wide Darpan administration and tenant/user management access. |
| Tenant identity | `moqui.security.UserGroup` with `groupTypeEnumId="UgtDarpanCompany"` | Represents a Darpan tenant such as a customer/brand/company. |
| Tenant association | `moqui.security.UserGroupMember` where `userGroupId` is a tenant group | Determines which tenants a non-super-admin user can select. |
| Tenant permission assignment | `darpan.auth.TenantUserPermissionGroupMember` | Joins one user, one tenant group, and one permission group. This keeps tenant identity separate from permission level. |
| Permission group type | `moqui.basic.Enumeration(enumId="UgtDarpanPermission")` | Groups reusable Darpan permission levels. |
| Artifact grants | `moqui.security.ArtifactGroup`, `ArtifactGroupMember`, and `ArtifactAuthz` | Grants page, service, action, or entity access. UI gating is advisory; backend services and artifact authorization are authoritative. |
| Tenant row filtering | `moqui.security.EntityFilterSet` and `EntityFilter` | Filters tenant-owned records by active tenant context where direct entity access is allowed. |

## Membership Matrix

| User type | Required membership records | Notes |
| --- | --- | --- |
| Darpan Admin | `UserGroupMember(userId, "ADMIN")`, or both `UserGroupMember(userId, "DARPAN_ADMIN")` and `UserGroupMember(userId, "DARPAN_USER")` for PWA facade access. | Grants `canManageDarpanCore=true`. Does not grant tenant data access by itself. |
| Super Admin | `UserGroupMember(userId, "ADMIN")`, or both `UserGroupMember(userId, "DARPAN_SUPER_ADMIN")` and `UserGroupMember(userId, "DARPAN_USER")` for PWA facade access. | Does not require per-tenant `TenantUserPermissionGroupMember` rows. Super Admin can see every tenant and can make tenant/user setup changes. |
| Tenant Admin | `UserGroupMember(userId, "DARPAN_USER")`, `UserGroupMember(userId, tenantUserGroupId)`, and `TenantUserPermissionGroupMember(tenantUserGroupId, userId, "DARPAN_TENANT_ADMIN")`. | Legacy `DARPAN_COMPANY_EDITOR` assignments continue to grant Tenant Admin behavior. |
| Tenant User | `UserGroupMember(userId, "DARPAN_USER")`, `UserGroupMember(userId, tenantUserGroupId)`, and `TenantUserPermissionGroupMember(tenantUserGroupId, userId, "DARPAN_TENANT_USER")`. | Distinct from legacy `DARPAN_COMPANY_VIEW_ONLY` because run/upload execution is allowed. |

## Capability Matrix

| Capability | Super Admin | Tenant Admin | Tenant User |
| --- | --- | --- | --- |
| Sign in and receive session info | Yes | Yes | Yes |
| Select tenant context | Any Darpan tenant | Associated tenants only | Associated tenants only |
| View tenant dashboard and tenant records | All tenants | Active associated tenant | Active associated tenant |
| View SFTP, NetSuite, saved runs, schemas, rules, run history, and results | Yes | Yes | Yes |
| Upload files for reconciliation | Yes | Yes | Yes |
| Execute reconciliation run | Yes | Yes | Yes |
| Download or review run results | Yes | Yes | Yes |
| Create/update/delete SFTP settings | Yes | Yes | No |
| Create/update/delete NetSuite auth or endpoint settings | Yes | Yes | No |
| Create/update/delete saved runs or run definitions | Yes | Yes | No |
| Create/update/delete schemas or schema inference output | Yes | Yes | No |
| Create/update/delete rules or rulesets | Yes | Yes | No |
| Delete run results or generated output metadata | Yes | Yes, where product allows tenant deletion | No |
| Edit tenant timezone | Yes | Yes | No |
| Edit user-owned preferences such as display name or pins | Own preferences and permitted admin reset flows | Own preferences | Own preferences |
| Manage tenants | Yes | No | No |
| Manage users | Yes | No | No |
| Assign tenant memberships and tenant permission groups | Yes | No | No |
| View/edit AI/LLM provider settings | No, unless also Darpan Admin | No | No |
| View/edit enum/global system settings | No, unless also Darpan Admin | No | No |
| Use debug, diagnostics, migration, or Darpan core management screens | No, unless also Darpan Admin | No | No |

## Page and Action Matrix

| Surface | Super Admin | Tenant Admin | Tenant User |
| --- | --- | --- | --- |
| Login and auth facade bootstrap | Allowed | Allowed | Allowed |
| Dashboard/home | View across selected tenant context. May use private admin views for aggregate platform management. | View active tenant. | View active tenant. |
| User settings and tenant switcher | View/edit own preferences. Tenant switcher includes all configured tenants. | View/edit own preferences. Tenant switcher includes associated tenants only. | View/edit own preferences. Tenant switcher includes associated tenants only. |
| Tenant timezone settings | View/edit active tenant timezone. | View/edit active tenant timezone. | View only. |
| SFTP settings | View/edit. Can also manage admin/platform SFTP where a screen is explicitly super-admin-only. | View/edit active tenant SFTP. | View only; may select a visible SFTP only for allowed run/upload workflows if backend permits it. |
| NetSuite auth and endpoint settings | View/edit. | View/edit active tenant NetSuite settings. | View redacted/list/detail status only. |
| Saved run editor and run definitions | View/edit/delete/run. | View/edit/delete/run active tenant definitions. | View definitions and run allowed definitions; no create/update/delete. |
| Reconciliation create/upload/diff flow | Upload/run and edit run setup. | Upload/run and edit run setup. | Upload/run only. No saved-run, schema, rule, or settings mutation. |
| Run history and result detail | View/download/delete where supported. | View/download/delete where tenant policy permits. | View/download only. |
| Schema library/editor | View/edit/create/delete. | View/edit/create/delete active tenant schemas. | View only. |
| Ruleset manager/editor | View/edit/create/delete. | View/edit/create/delete active tenant rules. | View only. |
| AI/LLM settings | No access unless also Darpan Admin. | No access. | No access. |
| Global enum/system settings | No access unless also Darpan Admin. | No access. | No access. |
| Tenant/user/permission management | View/edit. | No access. | No access. |
| Debug and operational diagnostics | No access unless also Darpan Admin. | No access unless a future tenant-safe diagnostic surface is explicitly created. | No access unless a future tenant-safe diagnostic surface is explicitly created. |

## Effective Access Resolution

1. Resolve whether the user is a Super Admin first. Super Admin can view and edit tenant data across Darpan tenants and can access tenant/user management.
1. Resolve whether the user is a Darpan Admin. Darpan Admin can access Darpan app-level settings and core management surfaces through `canManageDarpanCore`.
2. Resolve `availableTenants`.
   - Super Admin: all configured tenant `UserGroup` records with `groupTypeEnumId="UgtDarpanCompany"`.
   - Tenant Admin or Tenant User: only tenant groups where the user has `UserGroupMember`.
3. Resolve the active tenant from the saved active-tenant preference or the first available tenant.
4. Resolve the tenant permission assignment for the active tenant from `TenantUserPermissionGroupMember`.
5. If more than one tenant permission group applies in the same active tenant, use the most permissive effective role in this order: Super Admin, Tenant Admin, Tenant User.
6. Records with a null tenant owner are global/everyone-readable only when the service explicitly treats them that way. Null tenant ownership never grants write access.
7. The UI may hide or redirect unavailable actions, but backend service checks and Moqui artifact authorization remain the source of truth.

## Session Capability Contract

The UI should consume backend session/capability fields rather than recreating the access model page by page.

Minimum fields:

| Field | Meaning |
| --- | --- |
| `isSuperAdmin` | User has tenant-wide Darpan administration across configured tenants. |
| `availableTenants` | Tenant switcher options visible to the current user. |
| `activeTenantUserGroupId` | Current tenant scope for tenant-owned records and run output. |
| `activeTenantPermissionGroupIds` | Permission group IDs for the active tenant. |
| `canViewActiveTenantData` | Explicit read capability for tenant pages. |
| `canEditActiveTenantData` | Can create/update/delete tenant-owned configuration and saved data. |
| `canRunActiveTenantReconciliation` | Can upload files and execute reconciliation in the active tenant. This must be true for Tenant User even when `canEditActiveTenantData` is false. |
| `canManageDarpanCore` | Can access Darpan app-level settings and private core management screens. This is granted by Moqui `ADMIN` or `DARPAN_ADMIN`, not by `DARPAN_SUPER_ADMIN` alone. |

The UI derives `canViewTenantSettings`, `canRunActiveTenantReconciliation`, `canEditTenantSettings`, and `canManageGlobalSettings` from these backend session fields. Run/upload execution is intentionally separate from configuration edit so Tenant User can run reconciliation without being able to mutate schemas or saved runs.

## Implementation Follow-up Anchors

- DAR-221 should continue mapping the seeded groups to narrower backend service and artifact authorization checks where service-level write gates are still broad.
- DAR-222 should enforce tenant read/write visibility consistently, including null-tenant read behavior.
- DAR-223 should create safe services for user creation, tenant association, and tenant permission assignment.
- DAR-224 should build the Super Admin management UI for users, tenants, and permission assignments.
- DAR-225 should continue splitting remaining UI route/action gates into tenant view, tenant run/upload, tenant configuration edit, and global/core management capabilities.
- DAR-226 should validate the matrix with backend service tests, route/action tests, and audit/safety checks.
