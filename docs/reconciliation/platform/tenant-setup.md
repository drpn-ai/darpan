# Darpan Tenant Setup Tutorial

## Purpose

This tutorial shows how to set up a new Darpan tenant and assign users to the three product roles:

- Super Admin
- Tenant Admin
- Tenant User

Darpan Admin is a separate app-level settings capability. Add it only for users who should manage Darpan-wide settings such as AI providers and global enum/system options.

Use this when onboarding a new customer, brand, or company tenant into a Darpan environment.

## Current Contract

Darpan tenant setup uses Moqui security records plus Darpan tenant permission records.

| Concept | Record | Purpose |
| --- | --- | --- |
| Tenant | `moqui.security.UserGroup` with `groupTypeEnumId="UgtDarpanCompany"` | The selectable tenant/company/brand. |
| Base app access | `moqui.security.UserGroupMember(userGroupId="DARPAN_USER")` | Lets non-admin users use Darpan facade/PWA surfaces. |
| Tenant association | `moqui.security.UserGroupMember(userGroupId=tenantUserGroupId)` | Lets the user select that tenant. |
| Tenant permission | `darpan.auth.TenantUserPermissionGroupMember` | Gives the user a role inside one tenant. |
| Active tenant | `facade.AuthFacadeServices.save#ActiveTenant` or `moqui.security.UserPreference` | Selects the tenant used for tenant-owned data. |

The canonical role and capability matrix is [Darpan Permissions Matrix](permissions-matrix.md).

## Role Setup

| Role | Required setup |
| --- | --- |
| Darpan Admin | `UserGroupMember(userId, "ADMIN")`, or both `UserGroupMember(userId, "DARPAN_ADMIN")` and `UserGroupMember(userId, "DARPAN_USER")` for PWA facade access. Grants app-level settings access, not tenant data access by itself. |
| Super Admin | `UserGroupMember(userId, "ADMIN")`, or both `UserGroupMember(userId, "DARPAN_SUPER_ADMIN")` and `UserGroupMember(userId, "DARPAN_USER")` for PWA facade access. Super Admin does not require per-tenant permission rows and does not grant Darpan app-level settings unless the user also has Darpan Admin or Moqui `ADMIN`. |
| Tenant Admin | `DARPAN_USER` membership, tenant group membership, and `TenantUserPermissionGroupMember(..., "DARPAN_TENANT_ADMIN")`. Legacy `DARPAN_COMPANY_EDITOR` still maps to Tenant Admin behavior during migration. |
| Tenant User | `DARPAN_USER` membership, tenant group membership, and `TenantUserPermissionGroupMember(..., "DARPAN_TENANT_USER")`. This user can view, upload files, and run reconciliation, but cannot mutate settings, schemas, rules, saved runs, users, tenants, or platform settings. |

Do not use `ALL_USERS`, `ADMIN`, or `DARPAN_USER` as tenant data scopes. Tenant data scope must be a real tenant `UserGroup` with `groupTypeEnumId="UgtDarpanCompany"`.

## Required Inputs

Collect these before setup:

| Input | Example | Notes |
| --- | --- | --- |
| Tenant ID | `ACME` | Use a stable `UserGroup.userGroupId`. Prefer uppercase IDs without spaces. |
| Tenant label | `Acme` | `admin.TenantAdminServices.create#Tenant` stores it as the tenant's display label. |
| Tenant timezone | `America/New_York` | Optional; defaults to UTC. |
| Users | `alex@example.com`, `sam@example.com` | Create missing accounts with `admin.UserAdminServices.create#UserAccount`, always with an email address. |
| Role per user | `DARPAN_TENANT_ADMIN` or `DARPAN_TENANT_USER` | Assign per user and per tenant. |
| Optional default active tenant | `ACME` | Set for users who should land in the new tenant immediately. |
| Optional tenant settings | timezone, SFTP, NetSuite, Google Chat spaces | Create only after the active tenant is set correctly. |

For creating the login account itself, see [Darpan Tenant User Setup Tutorial](tenant-user-setup.md).

## Path A: Admin App or `admin.*` Services (standard)

Tenant, user and membership setup goes through the operator surface: the Darpan admin app
(`darpan-admin-ui`), or the `admin.*` JSON-RPC services it calls. Both require a super-admin
session (`DARPAN_SUPER_ADMIN` or Moqui `ADMIN`); `admin.*` is refused for every other user.
Each admin write also records a `darpan.admin.AdminAuditLog` row.

### 1. Authenticate

Sign in to the admin app, or call `facade.AuthFacadeServices.login#Session` with a super-admin
username and password and send the returned `login_key` on later calls.

The JSON-RPC endpoint is usually:

```text
https://<darpan-host>/rpc/json
```

Local development is usually:

```text
http://localhost:8080/rpc/json
```

### 2. Verify Admin Scope

Call `facade.AuthFacadeServices.get#SessionInfo` (or `admin.ObserveAdminServices.get#AdminSessionInfo`)
and confirm:

- `authenticated` is `true`
- `sessionInfo.isSuperAdmin` is `true`

Stop if the session does not have super-admin capability. `sessionInfo.canManageDarpanCore=true` is additionally required only for Darpan app-level settings, not tenant setup.

### 3. Create the Tenant

Call `admin.TenantAdminServices.create#Tenant`:

```json
{ "tenantUserGroupId": "ACME", "label": "Acme", "timeZone": "America/New_York" }
```

It creates the tenant `UserGroup` (`groupTypeEnumId="UgtDarpanCompany"`) and its `TenantSetting`
row in one transaction. `update#Tenant`, `deactivate#Tenant` and `reactivate#Tenant` cover the
rest of the tenant lifecycle.

### 4. Create Users and Assign Them

For a user who has no account yet, call `admin.UserAdminServices.create#UserAccount` with
`username`, `userFullName`, `emailAddress` and `tempPassword`. Always pass `emailAddress`: the
service accepts it as optional, but password recovery depends on it. The account is created with
`DARPAN_USER` membership and must change the temporary password at first sign-in. See
[Darpan Tenant User Setup Tutorial](tenant-user-setup.md).

Then add the user to the tenant with `admin.MembershipAdminServices.add#TenantMember`:

```json
{ "userId": "<userId>", "tenantUserGroupId": "ACME", "permissionUserGroupId": "DARPAN_TENANT_ADMIN" }
```

It writes the tenant `UserGroupMember` and the `TenantUserPermissionGroupMember` together. Use
`DARPAN_TENANT_USER` for a Tenant User. `update#TenantMemberRole` changes the role and
`remove#TenantMember` ends both rows.

Granting Super Admin or Darpan Admin is not an `admin.*` operation; add those platform groups
through the data-load path (Path B).

### 5. Set Active Tenant

For the current authenticated user, call:

```text
facade.AuthFacadeServices.save#ActiveTenant
```

with:

```json
{ "activeTenantUserGroupId": "ACME" }
```

For other users, either have them select the tenant after login or seed `moqui.security.UserPreference`:

```xml
<moqui.security.UserPreference userId="alex@example.com"
        preferenceKey="darpan.auth.activeTenantUserGroupId"
        preferenceValue="ACME"/>
```

### 6. Verify

Login or use auth for each assigned user and call `get#SessionInfo`.

Expected Tenant Admin response:

- `availableTenants` includes `ACME`
- `activeTenantUserGroupId` can be set to `ACME`
- `activeTenantPermissionGroupIds` includes `DARPAN_TENANT_ADMIN`
- `canViewActiveTenantData=true`
- `canRunActiveTenantReconciliation=true`
- `canEditActiveTenantData=true`
- `canManageDarpanCore=false`

Expected Tenant User response:

- `availableTenants` includes `ACME`
- `activeTenantPermissionGroupIds` includes `DARPAN_TENANT_USER`
- `canViewActiveTenantData=true`
- `canRunActiveTenantReconciliation=true`
- `canEditActiveTenantData=false`
- `canManageDarpanCore=false`

Expected Super Admin response:

- `isSuperAdmin=true`
- `canManageDarpanCore=false` unless the same user also has `DARPAN_ADMIN` or Moqui `ADMIN`
- can select every configured Darpan tenant

## Path B: Generic Data Load Setup (fallback)

Use a data load only when the admin app and `admin.*` services are not available, when setup
must be committed as seed/configuration data, or to grant the platform groups (`DARPAN_ADMIN`,
`DARPAN_SUPER_ADMIN`). A data load writes no audit row and creates no login account; create
accounts with `admin.UserAdminServices.create#UserAccount` first.

### 1. Create a Data File

Create a small XML data file for the target environment. For one tenant and two users:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<entity-facade-xml type="darpan-tenant-setup">
    <moqui.security.UserGroup userGroupId="ACME"
            description="Acme"
            groupTypeEnumId="UgtDarpanCompany"/>

    <moqui.security.UserGroupMember userGroupId="DARPAN_USER"
            userId="alex@example.com"
            fromDate="2026-05-02 00:00:00"/>
    <moqui.security.UserGroupMember userGroupId="ACME"
            userId="alex@example.com"
            fromDate="2026-05-02 00:00:00"/>
    <darpan.auth.TenantUserPermissionGroupMember tenantUserGroupId="ACME"
            userId="alex@example.com"
            permissionUserGroupId="DARPAN_TENANT_ADMIN"
            fromDate="2026-05-02 00:00:00"/>
    <moqui.security.UserPreference userId="alex@example.com"
            preferenceKey="darpan.auth.activeTenantUserGroupId"
            preferenceValue="ACME"/>

    <moqui.security.UserGroupMember userGroupId="DARPAN_USER"
            userId="sam@example.com"
            fromDate="2026-05-02 00:00:00"/>
    <moqui.security.UserGroupMember userGroupId="ACME"
            userId="sam@example.com"
            fromDate="2026-05-02 00:00:00"/>
    <darpan.auth.TenantUserPermissionGroupMember tenantUserGroupId="ACME"
            userId="sam@example.com"
            permissionUserGroupId="DARPAN_TENANT_USER"
            fromDate="2026-05-02 00:00:00"/>
    <moqui.security.UserPreference userId="sam@example.com"
            preferenceKey="darpan.auth.activeTenantUserGroupId"
            preferenceValue="ACME"/>
</entity-facade-xml>
```

Use the current date/time for `fromDate`. Keep the same `fromDate` stable if rerunning an idempotent setup file.

### 2. Load the Data

Use the repo's normal data-load workflow for the target environment. In local development, use the Darpan backend Gradle/data-load path that is already used for seed and upgrade data.

Do not load production data into local or shared dev by accident. Confirm the database and environment before loading.

### 3. Verify Through Session Info

After loading, use the same verification from Path A. Session info is the source of truth for what the UI will see.

## Tenant-Owned Settings After Tenant Creation

Create tenant-owned settings only after the active tenant is correct.

| Setting | Service | Verification |
| --- | --- | --- |
| Tenant timezone | `facade.SettingsFacadeServices.save#TenantSettings` | Stored on `darpan.auth.TenantSetting.companyUserGroupId`. |
| SFTP | `facade.SettingsFacadeServices.save#SftpServer` | `SftpServer.companyUserGroupId` is the active tenant. |
| NetSuite auth | `facade.SettingsFacadeServices.save#NsAuthConfig` | `NsAuthConfig.companyUserGroupId` is the active tenant. |
| NetSuite endpoint | `facade.SettingsFacadeServices.save#NsRestletConfig` | Endpoint auth binding must be visible in the active tenant. |
| Chat spaces (Google Chat webhook or Slack channel) | `facade.SettingsFacadeServices.save#TenantChatSpace` (Slack needs the workspace connected first through `facade.SlackFacadeServices`) | `list#TenantChatSpaces` returns the named spaces for the active tenant. Google Chat webhook URLs are stored in clear text and returned to tenant members, so treat them as visible to anyone with tenant access. |

Do not create tenant-specific LLM provider settings in the current data model. LLM and enum/global settings are Darpan-admin-only app-level settings.

After a chat space exists, each user sets their own default through
`facade.SettingsFacadeServices.save#UserNotificationDefault` (personal preference, no tenant write
access needed), and automations pick their notification space from the registry when saved.

## Troubleshooting

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| Tenant does not appear in the switcher | Missing tenant `UserGroupMember`, wrong `groupTypeEnumId`, or no Darpan tenant groups are available. | Confirm the tenant has `groupTypeEnumId="UgtDarpanCompany"` and the user has tenant membership. |
| User can select tenant but cannot edit settings | Missing `TenantUserPermissionGroupMember` or assigned `DARPAN_TENANT_USER`. | Assign `DARPAN_TENANT_ADMIN` for edit access. |
| Tenant User cannot run reconciliation | Assigned legacy `DARPAN_COMPANY_VIEW_ONLY` instead of `DARPAN_TENANT_USER`. | Use `DARPAN_TENANT_USER`; it has run/upload access without edit access. |
| Super Admin cannot use the PWA | User has `DARPAN_SUPER_ADMIN` but lacks `DARPAN_USER` and Moqui `ADMIN`. | Add `DARPAN_USER`, or use Moqui `ADMIN` for full artifact access. |
| Super Admin cannot edit AI/global settings | Super Admin no longer implies Darpan Admin. | Add `DARPAN_ADMIN` or use Moqui `ADMIN` for app-level settings access. |
| Cross-tenant data appears | Tenant-owned rows are missing or have the wrong `companyUserGroupId`. | Backfill records to the correct tenant and verify entity filters/session context. |

## Handoff Checklist

- [ ] Tenant `UserGroup` exists with `groupTypeEnumId="UgtDarpanCompany"`.
- [ ] Any new login users were created through [Darpan Tenant User Setup Tutorial](tenant-user-setup.md), each with an email address.
- [ ] Every non-admin app user has `DARPAN_USER`.
- [ ] Every tenant user has tenant `UserGroupMember`.
- [ ] Every tenant user has the intended `TenantUserPermissionGroupMember`.
- [ ] Tenant User uses `DARPAN_TENANT_USER`, not legacy view-only.
- [ ] Super Admin has Moqui `ADMIN` or `DARPAN_SUPER_ADMIN` plus `DARPAN_USER`.
- [ ] Darpan Admin has Moqui `ADMIN` or `DARPAN_ADMIN` plus `DARPAN_USER`.
- [ ] `get#SessionInfo` verifies `availableTenants`, `activeTenantUserGroupId`, role flags, and run/edit/core capabilities.
- [ ] Tenant-owned settings are created only while the intended tenant is active.
