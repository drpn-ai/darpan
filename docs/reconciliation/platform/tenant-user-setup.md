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
| Email | `alex@example.com` | Always set it; password recovery depends on it. Must be unique. |
| Full name | `Alex Chen` | Stored as `UserAccount.userFullName`. |
| Initial password | one-time secret | Passed as `tempPassword` to the admin service; the user must change it at first sign-in. Do not commit or log it. |
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

3. Confirm the session you are using is super-admin capable by calling `facade.AuthFacadeServices.get#SessionInfo` and checking:

- `authenticated=true`
- `sessionInfo.isSuperAdmin=true`

Stop if the session is not super-admin capable: every `admin.*` service refuses other users.
`sessionInfo.canManageDarpanCore=true` is required for app-level settings, but it is not required
for tenant-user setup.

## Path A: Admin App or `admin.*` Services (standard)

Use the Darpan admin app (`darpan-admin-ui`), or call the `admin.*` JSON-RPC services it uses.
Each write records a `darpan.admin.AdminAuditLog` row.

### 1. Create the account

Call `admin.UserAdminServices.create#UserAccount`:

```json
{
  "username": "alex@example.com",
  "userFullName": "Alex Chen",
  "emailAddress": "alex@example.com",
  "tempPassword": "<one-time secret>"
}
```

- Always pass `emailAddress`. The service treats it as optional, but password recovery depends on it.
- The account is created through the framework's `create#UserAccount` with
  `requirePasswordChange=Y`, so the user must change the temporary password at first sign-in
  (`facade.AuthFacadeServices.change#ExpiredPassword`).
- The service also adds the `DARPAN_USER` membership.
- Credentials are shared out of band; there is no email invite.

### 2. Add the user to a tenant

Call `admin.MembershipAdminServices.add#TenantMember` with `userId`, `tenantUserGroupId` and
`permissionUserGroupId` (`DARPAN_TENANT_USER` or `DARPAN_TENANT_ADMIN`; the legacy
`DARPAN_COMPANY_EDITOR` and `DARPAN_COMPANY_VIEW_ONLY` are also accepted). It writes the tenant
`UserGroupMember` and the `TenantUserPermissionGroupMember` together. Repeat per tenant.

### 3. Later changes

| Change | Service |
| --- | --- |
| Name or email | `admin.UserAdminServices.update#UserAccount` |
| Reset a forgotten password | `admin.UserAdminServices.reset#Password` (sets a new temporary password, forces a change at next sign-in, and revokes the user's login keys) |
| Disable / re-enable sign-in | `disable#UserAccount` / `enable#UserAccount` |
| Change a tenant role | `admin.MembershipAdminServices.update#TenantMemberRole` |
| Remove from a tenant | `admin.MembershipAdminServices.remove#TenantMember` |

Do not route password changes through stock Moqui services such as
`org.moqui.impl.UserServices.update#Password` over `/rpc/json`: the JSON-RPC endpoint only
dispatches `facade.*`, `admin.*` and `gql.*`, and refuses everything else with HTTP 403. A user
changes their own password with `facade.AuthFacadeServices.change#OwnPassword`.

## Path B: Membership and Role Records (fallback)

Use a data load only when the admin app and `admin.*` services are not available, or when the
setup must be committed as configuration data. The account itself still comes from Path A;
a data load creates no login and writes no audit row.

Required records:

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

Do not put plaintext passwords in generic data. Create accounts and reset passwords through the admin app or `admin.UserAdminServices` (Path A).

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
| User cannot log in | Missing/disabled `UserAccount`, wrong password flow, or required password change not completed. | Create, enable or reset through the admin app or `admin.UserAdminServices`. |
| User logs in but sees no tenant switcher | Missing tenant `UserGroupMember` or tenant has the wrong `groupTypeEnumId`. | Add tenant membership and confirm the tenant is `UgtDarpanCompany`. |
| User sees tenant but cannot run reconciliation | Missing `DARPAN_TENANT_USER`/`DARPAN_TENANT_ADMIN`, or assigned legacy view-only. | Add `TenantUserPermissionGroupMember` with `DARPAN_TENANT_USER` or `DARPAN_TENANT_ADMIN`. |
| User can run but cannot edit settings | User is correctly assigned as Tenant User. | Use `DARPAN_TENANT_ADMIN` only if edit access is intended. |
| User has cross-tenant access | Assigned to the wrong tenant group or tenant-owned data has wrong `companyUserGroupId`. | Check memberships and row ownership. |

## Handoff Checklist

- [ ] `UserAccount` exists, is enabled, and has the intended login username and an email address.
- [ ] Initial password was not committed or logged.
- [ ] User has `DARPAN_USER`.
- [ ] User has tenant `UserGroupMember` for every intended tenant.
- [ ] User has `TenantUserPermissionGroupMember` for every intended tenant.
- [ ] Tenant User uses `DARPAN_TENANT_USER`, not legacy view-only.
- [ ] Default active tenant is set where needed.
- [ ] `get#SessionInfo` verifies tenant list, active tenant, permission groups, and capability flags.
