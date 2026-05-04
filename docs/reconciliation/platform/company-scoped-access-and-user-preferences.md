# Tenant-Scoped Access and User-Level Preferences

## Goal

Introduce tenant-scoped shared access for tenant-scoped Darpan data while preserving personal user-level preferences across devices and sessions.

## Overview

Darpan should separate two different concerns:

- Personal preferences that belong to an individual user
- Shared tenant-scoped data that belongs to the tenant the user is actively working in

Pins are personal and should follow the user everywhere they log in. Tenant-scoped records should be visible to all users in the same tenant and hidden from users outside that tenant. A user may belong to multiple tenants and switch the active tenant from the user menu without logging out.

## Phase 1 Scope

### In Scope

- Runs
- Schemas
- Results
- SFTP settings
- NetSuite settings

### Out of Scope

- LLM and other global AI settings
- enum/global admin settings
- an end-user "all tenants" data view for non-admin users
- broader global-vs-tenant policy beyond the surfaces listed above

## Current Repo State

The current repo now uses the active tenant as the working scope for the main tenant-scoped Darpan surfaces:

- dashboard pins are already persisted per user through `UserPreference`
- authenticated session info resolves an active tenant from Darpan tenant groups and returns `activeTenantUserGroupId`, `activeTenantLabel`, and `availableTenants`
- the PWA facade path now uses a dedicated `DARPAN_FACADE_APP` artifact group plus Moqui `ArtifactAuthzFilter` / `EntityFilterSet` records so tenant-owned entity reads are filtered automatically from `ec.user.context.activeTenantUserGroupId`
- `DARPAN_USER` is the coarse app-access group for non-admin PWA users; tenant groups continue to represent business scope, not general app permission
- tenant-specific permissions now resolve through explicit `user + tenant + permission-group` memberships in `darpan.auth.TenantUserPermissionGroupMember`, so the same user can be Tenant Admin in one tenant and Tenant User in another
- the user menu exposes tenant switching whenever the session has one or more Darpan tenants; super-admin users see all configured Darpan tenants
- schemas, mappings, generated outputs, SFTP settings, NetSuite auth configs, and NetSuite endpoint configs use `companyUserGroupId` for visibility and write ownership
- new tenant-scoped records are stamped with the active tenant and `createdByUserId`
- the component Moqui config syncs active-tenant data into `ec.user.context` on request/login so Moqui entity filters stay aligned with the current session
- local demo seed data gives `john.doe` `GORJANA` as the default active tenant, with tenant-specific permission rows used to resolve effective access

The persisted ownership field remains `companyUserGroupId` in this phase; `tenant` is the normalized domain term for the active working scope and permission model.

Remaining work is mostly migration and cleanup for older rows that still have blank tenant ownership.

## Core Principles

- Pins are always user-level
- Tenant-scoped records are always tenant-level
- The active tenant controls write ownership and the default working context
- A user can belong to multiple tenants
- Tenant identity and tenant-specific permission groups stay separate
- `ALL_USERS` must not be used as a tenant data-view scope
- Generic security groups such as `ADMIN` must not appear in the tenant switcher

## Tenant Model

Darpan should use `moqui.security.UserGroup` as the tenant model.

- Each tenant is represented by a `UserGroup`
- Users can belong to multiple tenant groups through `UserGroupMember`
- Tenant groups should be identified with a Darpan-specific `UserGroup.groupTypeEnumId`
- The active tenant should be stored as a user preference so it persists across sessions and devices

This avoids inventing a separate account model while still allowing explicit tenant selection.

## Tenant-Scoped Permissions

Tenant selection and tenant-specific permissions are related but different concerns.

- tenant membership still comes from Darpan tenant `UserGroup` records
- reusable permission groups such as `DARPAN_TENANT_ADMIN` and `DARPAN_TENANT_USER` stay independent from tenant groups
- `darpan.auth.TenantUserPermissionGroupMember` stores the exact `tenantUserGroupId + userId + permissionUserGroupId` membership
- the active tenant resolves the current user's permission groups by filtering those membership rows for the selected tenant

Current write-capability rule:

- super-admin users always retain write capability
- `DARPAN_TENANT_ADMIN` enables create/update/delete/run actions in the active tenant
- `DARPAN_TENANT_USER` allows tenant reads plus reconciliation upload/run without settings, schema, rules, saved-run, user, tenant, or platform mutation
- legacy `DARPAN_COMPANY_EDITOR` still maps to Tenant Admin behavior during migration, while legacy `DARPAN_COMPANY_VIEW_ONLY` remains pure read-only
- if no explicit permission assignment exists for the active tenant, the user should fail closed for tenant data capabilities

Production setup rule:

- create tenant groups as `moqui.security.UserGroup` rows with `groupTypeEnumId="UgtDarpanCompany"`
- add each selectable user through `moqui.security.UserGroupMember`
- assign Tenant Admin or Tenant User capability through `darpan.auth.TenantUserPermissionGroupMember`
- do not use `DARPAN_USER`, `ADMIN`, or `ALL_USERS` as tenant data scopes
- verify the session contract through `login#Session`, `get#SessionInfo`, or `save#ActiveTenant` before creating tenant-owned settings

For the production setup matrix and operational verification steps, see [Darpan Tenant Setup Tutorial](tenant-setup.md) and [Production Settings Surfaces](production-settings-surfaces.md).

## User-Level Preferences

Pins should remain user-scoped and should not depend on the active tenant.

This applies to:

- pinned runs
- pinned AI items
- any future pin-capable Darpan surfaces

Implementation rules:

- continue using `UserPreference`
- do not move pins onto tenant records
- do not clear or rewrite pins when the user switches tenant
- only render pinned items if the pinned target is visible in the current active tenant

## Tenant-Scoped Ownership and Visibility

Tenant-scoped records must be stamped with the active tenant when created and filtered by the active tenant when read.

Read-side filtering should default to Moqui configuration instead of per-facade query logic.

Implementation rules:

- use `ArtifactAuthzFilter` + `EntityFilterSet` + `EntityFilter` for tenant-owned entity reads whenever the entity has a direct `companyUserGroupId`
- populate `ec.user.context.activeTenantUserGroupId` from the authenticated session before tenant-owned entity queries run
- keep thin backend code only for active-tenant resolution, tenant-switch persistence, write stamping, and explicit validation/error handling
- do not use Moqui tenant IDs for this feature; Darpan remains a shared-app, shared-database deployment with security-scoped tenant access

Top-level records in scope should carry:

- `companyUserGroupId`
- `createdByUserId`

Phase 1 top-level records:

- `JsonSchema`
- `ReconciliationMapping`
- `ReconciliationRunResult`
- `SftpServer`
- `NsAuthConfig`
- `NsRestletConfig`

Child rows should inherit tenant scope from their parent rather than duplicating tenant ownership fields.

Visibility rules for normal users:

- list/get/update/delete only within the active tenant
- records from other tenants are not visible
- new records are created in the active tenant
- Tenant User active-tenant sessions can read tenant-scoped records and run reconciliation, but cannot create, update, or delete tenant configuration
- generated output result files are shared tenant evidence: `list#GeneratedOutputs`, `get#GeneratedOutput`, and `delete#GeneratedOutput` resolve tenant ownership from `ReconciliationRunResult.companyUserGroupId` first and only fall back to generated-output JSON metadata for migration files
- dashboard pins stay user-level; a pinned saved-run/result target should render only when the underlying run or generated output is visible in the active tenant

Admin behavior:

- retain operational admin access across the app while keeping tenant-owned data scoped by the active tenant
- keep the active tenant selector visible so super-admin users can choose from all configured Darpan tenants
- apply the same active-tenant read filtering to super-admin users as every other authenticated user
- stamp new tenant-scoped records with the active tenant even for super-admin
- keep global/admin-only Settings, including AI/LLM provider settings, enum/global settings, and app-wide operational config, outside tenant editor permissions

## Auth and Session Contract

The auth/session contract should shift from user-owned scope to active-tenant scope for tenant-scoped data.

`login#Session` and `get#SessionInfo` should return:

- `scopeType`
- `activeTenantUserGroupId`
- `activeTenantLabel`
- `availableTenants[]`
- `activeTenantPermissionGroupIds[]`
- `canEditActiveTenantData`
- `isSuperAdmin`

A new authenticated facade service should allow the frontend to switch active tenant and return refreshed session metadata.

The frontend should stop using `customerScopeId` as the source of data visibility for tenant-scoped surfaces.

## Runtime Storage and Results

Results must become tenant-scoped, not user-scoped.

Required changes:

- stop using per-user generated-output folders for tenant-scoped results
- write generated outputs to a tenant-scoped runtime location
- include `companyUserGroupId` in generated output metadata
- filter result list/get/delete operations by active tenant

This ensures users in the same tenant can see shared results while preserving tenant isolation.

## UI Behavior

The user popup should become the control point for active tenant switching.

Required behavior:

- show the current active tenant
- show all switchable tenant groups for the current user
- exclude generic security groups from the list
- switching tenant should refresh tenant-scoped surfaces immediately

Surfaces that should refresh on tenant switch:

- dashboard runs
- run history
- run results
- schema pages
- SFTP pages
- NetSuite pages

Global settings that are out of scope for Phase 1 should remain unchanged by tenant switching.

## Migration Strategy

Migration should be controlled and conservative.

Recommended sequence:

1. Add tenant ownership fields and session/tenant-switch contract
2. Backfill legacy tenant-scoped records to real tenant groups
3. Keep unmatched legacy records admin-only until assigned
4. Enforce active-tenant filtering after ownership is present
5. Move generated outputs to tenant-scoped storage after run ownership is stable

Do not introduce `ALL_USERS` as a fallback shared data scope.

## Acceptance Criteria

- A user with one tenant gets a valid default active tenant
- A user with multiple tenants can switch active tenant without logging out
- The active tenant persists across devices and sessions
- The same user can resolve different write permissions in different tenants
- Pins remain personal and persist across devices and sessions
- Users in the same tenant can see shared runs, schemas, results, SFTP settings, and NetSuite settings
- Users outside that tenant cannot access those records
- View-only users can read tenant-scoped records but cannot mutate them in the active tenant
- Super-admin users can review runs, schemas, SFTP settings, and NetSuite settings across all configured tenants while still choosing an active tenant
- New tenant-scoped records are stamped with the active tenant
- `ALL_USERS` never appears as a selectable tenant or data-view scope

## Assumptions

- Tenant groups are identified with a Darpan-specific `UserGroup.groupTypeEnumId`
- The last active tenant is stored in `UserPreference`
- Phase 1 tenant-scoped scope is limited to `RUN`, `SCHEMA`, `RESULTS`, `SFTP`, and `NETSUITE`
- Global settings outside that scope are intentionally deferred
