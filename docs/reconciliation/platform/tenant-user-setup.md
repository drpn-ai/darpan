# Darpan Tenant User Setup Tutorial

## Purpose

This tutorial shows how to create a Darpan tenant user and assign that user to one or more tenants as either Tenant Admin or Tenant User.

Use this when onboarding a person into an existing Darpan tenant.

## Current Contract

A Darpan tenant user is not just a login account. The complete setup has five parts:

| Step | Record or service | Purpose |
| --- | --- | --- |
| Login identity | `moqui.security.UserAccount` | Creates the user identity and password. |
| Base app access | `moqui.security.UserGroupMember(userGroupId="DARPAN_USER")` | Lets the user use Darpan facade/PWA surfaces. |
| Tenant association | `moqui.security.UserGroupMember(userGroupId=tenantUserGroupId)` | Makes the tenant selectable for the user. |
| Tenant role | `darpan.auth.TenantUserPermissionGroupMember` | Gives the user Tenant Admin or Tenant User access inside that tenant. |
| Active tenant preference | `facade.AuthFacadeServices.save#ActiveTenant` or `moqui.security.UserPreference` | Controls the user's default working tenant. |

The target role vocabulary is:

| Role | Permission group | Capability |
| --- | --- | --- |
| Tenant Admin | `DARPAN_TENANT_ADMIN` | View, upload/run, create, update, and delete tenant-owned configuration and saved data. |
| Tenant User | `DARPAN_TENANT_USER` | View tenant data, upload files, run reconciliation, and download/review results. Cannot mutate settings, schemas, rules, saved runs, users, tenants, or platform settings. |

Legacy `DARPAN_COMPANY_EDITOR` still maps to Tenant Admin behavior during migration. Legacy `DARPAN_COMPANY_VIEW_ONLY` is pure read-only and is not equivalent to Tenant User.

## Required Inputs

Collect these before setup:

| Input | Example | Notes |
| --- | --- | --- |
| User ID | `alex@example.com` | Stable Moqui `UserAccount.userId`. Email address is a good default when allowed by the environment. |
| Username | `alex@example.com` | Login username. Must be unique. |
| Email | `alex@example.com` | Must be unique if provided. |
| Full name | `Alex Chen` | Stored as `UserAccount.userFullName`. |
| Initial password | one-time secret | Use only through approved user creation services or admin UI. Do not commit plaintext passwords. |
| Tenant ID | `ACME` | Existing Darpan tenant `UserGroup` with `groupTypeEnumId="UgtDarpanCompany"`. |
| Tenant role | `DARPAN_TENANT_ADMIN` or `DARPAN_TENANT_USER` | Assign per user and per tenant. |
| Default active tenant | `ACME` | Optional but useful for the first login. |

## Preflight

1. Confirm the tenant exists:

```xml
<moqui.security.UserGroup userGroupId="ACME"
        groupTypeEnumId="UgtDarpanCompany"/>
```

2. Confirm the role groups exist:

```text
DARPAN_TENANT_ADMIN
DARPAN_TENANT_USER
DARPAN_USER
```

3. Confirm the auth you are using is super-admin capable by calling `facade.AuthFacadeServices.get#SessionInfo` and checking:

- `authenticated=true`
- `sessionInfo.isSuperAdmin=true`

Stop if the auth is not super-admin capable. `sessionInfo.canManageDarpanCore=true` is required for app-level settings, but it is not required for tenant-user setup.

## Path A: Admin UI or Internal User Service

Use this path when creating a real login user with a password.

The standard Moqui service for creating a user account is:

```text
org.moqui.impl.UserServices.create#UserAccount
```

It accepts `username`, `newPassword`, `newPasswordVerify`, `userFullName`, `emailAddress`, `requirePasswordChange`, and related `UserAccount` fields. In this checkout the service is `allow-remote="false"`, so it is not a Darpan PWA JSON-RPC service by default. It is used by the Moqui Tools user screen:

```text
/apps/system/Security/UserAccount
```

Recommended production flow:

1. Create the `UserAccount` through the approved admin UI or an environment-approved backend operation that calls `org.moqui.impl.UserServices.create#UserAccount`.
2. Set `requirePasswordChange="Y"` unless the onboarding process already handles password rotation.
3. Do not commit or log the initial password.
4. Continue with the membership and role assignment records below.

## Path B: Authenticated Darpan Facade Setup

Use this path only when the target environment exposes Darpan user-management facade services. Verify availability before attempting mutation.

Expected services, when available:

- `facade.AuthFacadeServices.save#TenantUserAccount` or equivalent user-provisioning service
- `facade.AuthFacadeServices.save#TenantUser`
- `facade.AuthFacadeServices.save#TenantUserPermissionGroup`
- `facade.AuthFacadeServices.save#ActiveTenant`
- `facade.AuthFacadeServices.get#SessionInfo`

The exact user-provisioning method name may differ by environment. Do not assume a method exists just because tenant-assignment services exist.

Minimum intended effects:

```xml
<moqui.security.UserGroupMember userGroupId="DARPAN_USER"
        userId="alex@example.com"
        fromDate="2026-05-02 00:00:00"/>

<moqui.security.UserGroupMember userGroupId="ACME"
        userId="alex@example.com"
        fromDate="2026-05-02 00:00:00"/>

<darpan.auth.TenantUserPermissionGroupMember tenantUserGroupId="ACME"
        userId="alex@example.com"
        permissionUserGroupId="DARPAN_TENANT_USER"
        fromDate="2026-05-02 00:00:00"/>
```

For Tenant Admin, use `DARPAN_TENANT_ADMIN`.

## Path C: Generic Data for Membership and Role Assignment

Use this path when the `UserAccount` already exists or when setup must be committed/loaded as configuration data.

For an existing user:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<entity-facade-xml type="darpan-tenant-user-setup">
    <moqui.security.UserGroupMember userGroupId="DARPAN_USER"
            userId="alex@example.com"
            fromDate="2026-05-02 00:00:00"/>
    <moqui.security.UserGroupMember userGroupId="ACME"
            userId="alex@example.com"
            fromDate="2026-05-02 00:00:00"/>
    <darpan.auth.TenantUserPermissionGroupMember tenantUserGroupId="ACME"
            userId="alex@example.com"
            permissionUserGroupId="DARPAN_TENANT_USER"
            fromDate="2026-05-02 00:00:00"/>
    <moqui.security.UserPreference userId="alex@example.com"
            preferenceKey="darpan.auth.activeTenantUserGroupId"
            preferenceValue="ACME"/>
</entity-facade-xml>
```

Do not put plaintext passwords in generic data. For production users, create or reset the password through `org.moqui.impl.UserServices.create#UserAccount`, `update#Password`, an approved admin UI, or the environment's identity provider flow.

## Multiple Tenants

Repeat tenant membership and role assignment once per tenant:

```xml
<moqui.security.UserGroupMember userGroupId="KREWE"
        userId="alex@example.com"
        fromDate="2026-05-02 00:00:00"/>
<darpan.auth.TenantUserPermissionGroupMember tenantUserGroupId="KREWE"
        userId="alex@example.com"
        permissionUserGroupId="DARPAN_TENANT_ADMIN"
        fromDate="2026-05-02 00:00:00"/>
```

The same user can be Tenant Admin in one tenant and Tenant User in another. Effective access is resolved from the active tenant.

## Verification

Login as the created user, or use a valid auth token for that user, and call:

```text
facade.AuthFacadeServices.get#SessionInfo
```

For Tenant User, verify:

- `availableTenants` includes the tenant.
- `activeTenantUserGroupId` can be set to the tenant.
- `activeTenantPermissionGroupIds` includes `DARPAN_TENANT_USER`.
- `canViewActiveTenantData=true`.
- `canRunActiveTenantReconciliation=true`.
- `canEditActiveTenantData=false`.
- `canManageDarpanCore=false`.

For Tenant Admin, verify:

- `activeTenantPermissionGroupIds` includes `DARPAN_TENANT_ADMIN`.
- `canViewActiveTenantData=true`.
- `canRunActiveTenantReconciliation=true`.
- `canEditActiveTenantData=true`.
- `canManageDarpanCore=false`.

Also verify the negative path:

- Tenant User can open tenant records and run reconciliation.
- Tenant User cannot save SFTP, NetSuite, schemas, rules, saved runs, tenant settings, user assignments, or platform settings.
- A user without tenant `UserGroupMember` does not see the tenant in `availableTenants`.

## Troubleshooting

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| User cannot log in | Missing/disabled `UserAccount`, wrong password flow, or required password change not completed. | Create/reset through the approved user service or admin UI. |
| User logs in but sees no tenant switcher | Missing tenant `UserGroupMember` or tenant has the wrong `groupTypeEnumId`. | Add tenant membership and confirm the tenant is `UgtDarpanCompany`. |
| User sees tenant but cannot run reconciliation | Missing `DARPAN_TENANT_USER`/`DARPAN_TENANT_ADMIN`, or assigned legacy view-only. | Add `TenantUserPermissionGroupMember` with `DARPAN_TENANT_USER` or `DARPAN_TENANT_ADMIN`. |
| User can run but cannot edit settings | User is correctly assigned as Tenant User. | Use `DARPAN_TENANT_ADMIN` only if edit access is intended. |
| User has cross-tenant access | Assigned to the wrong tenant group or tenant-owned data has wrong `companyUserGroupId`. | Check memberships and row ownership. |

## Handoff Checklist

- [ ] `UserAccount` exists, is enabled, and has the intended login username/email.
- [ ] Initial password was not committed or logged.
- [ ] User has `DARPAN_USER`.
- [ ] User has tenant `UserGroupMember` for every intended tenant.
- [ ] User has `TenantUserPermissionGroupMember` for every intended tenant.
- [ ] Tenant User uses `DARPAN_TENANT_USER`, not legacy view-only.
- [ ] Default active tenant is set where needed.
- [ ] `get#SessionInfo` verifies tenant list, active tenant, permission groups, and capability flags.
