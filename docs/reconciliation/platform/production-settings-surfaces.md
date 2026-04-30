# Production Settings Surfaces

## Purpose

This runbook identifies the Settings surfaces required for a production multi-user Darpan tenant setup and records the owner level, required permission level, owning files, and validation path for each surface.

This is documentation-only guidance. It is grounded in the current active-tenant model and current Settings UI and facade services. Do not use it as evidence that a missing UI affordance or migration has already been implemented.

## Evidence Map

- `docs/reconciliation/platform/company-scoped-access-and-user-preferences.md` defines Phase 1 tenant-scoped surfaces as runs, schemas, results, SFTP settings, and NetSuite settings. It explicitly keeps LLM settings, `HcReadDbConfig`, and enum/global admin settings out of Phase 1.
- `service/facade/AuthFacadeServices.xml` exposes the active tenant session contract: `activeTenantUserGroupId`, `availableTenants`, `activeTenantPermissionGroupIds`, `canEditActiveTenantData`, and `isSuperAdmin`.
- `service/facade/SettingsFacadeServices.xml` already makes `list#EnumOptions`, `get#LlmSettings`, `save#LlmSettings`, `list#HcReadDbConfigs`, and `save#HcReadDbConfig` super-admin only.
- `service/facade/SettingsFacadeServices.xml` already filters SFTP, NetSuite auth, and NetSuite endpoint lists by `activeTenantUserGroupId`, checks tenant record access on edits, and requires active-tenant write access for saves.
- `service/facade/JsonSchemaFacadeServices.xml` and `service/facade/ReconciliationFacadeServices.xml` already treat schemas, mappings, saved runs, and generated outputs as tenant-owned records using `companyUserGroupId`.
- `darpan-ui/src/App.vue` is the active tenant switch point. It keys route rendering by active tenant, refreshes command data on tenant change, and redirects to the hub after saving a new active tenant.
- `darpan-ui/src/pages/settings/*` owns Settings pages and workflows. Current settings pages do not consistently gate create/edit affordances from `canEditActiveTenantData` or `isSuperAdmin`; this is the main production readiness gap on the UI side.

## Production Tenant Setup Runbook

Use this sequence for each production tenant group.

1. Create or confirm the tenant `moqui.security.UserGroup`.
   - Use `groupTypeEnumId="UgtDarpanCompany"`.
   - Keep generic groups such as `ALL_USERS`, `ADMIN`, and `DARPAN_USER` out of the tenant list.
   - Current seed examples live in `data/SecuritySeedData.xml` as `GORJANA` and `KREWE`.
2. Add user membership to the tenant.
   - Use `moqui.security.UserGroupMember` for every user who should be able to select the tenant.
   - Super-admin users can see configured Darpan tenants, but tenant-owned data still follows the selected active tenant.
3. Add the user's tenant-specific permission row.
   - Use `darpan.auth.TenantUserPermissionGroupMember`.
   - Set `tenantUserGroupId` to the tenant group.
   - Set `permissionUserGroupId` to `DARPAN_COMPANY_EDITOR` for create/update/delete/run access.
   - Set `permissionUserGroupId` to `DARPAN_COMPANY_VIEW_ONLY` for read-only access.
   - The same user can be an editor in one tenant and view-only in another tenant.
4. Seed or save the user's active tenant preference where needed.
   - The active tenant preference key is `darpan.auth.activeTenantUserGroupId`.
   - Runtime switching should go through `facade.AuthFacadeServices.save#ActiveTenant`.
   - Session metadata should return `activeTenantUserGroupId`, `activeTenantLabel`, `availableTenants`, `activeTenantPermissionGroupIds`, `canEditActiveTenantData`, and `isSuperAdmin`.
5. Create tenant-owned settings while the intended tenant is active.
   - SFTP servers, NetSuite auth configs, NetSuite endpoints, schemas, mappings, saved runs, and result metadata must carry `companyUserGroupId`.
   - New records should also carry `createdByUserId` where the entity supports it.
   - Do not backfill blank `companyUserGroupId` rows to `ALL_USERS`; assign them to a real tenant or keep them admin-only until assigned.
6. Keep global/admin settings out of tenant setup.
   - LLM provider settings, enum/global settings, app-wide operational config, and `HcReadDbConfig` are not tenant-editor surfaces in the current implementation.
   - `HcReadDbConfig` has no tenant ownership field today; tenant-scoped promotion requires a separate data model and migration design.

## Surface Decisions

| Surface | Owner Level | Required Permissions | Production Decision |
| --- | --- | --- | --- |
| SFTP servers | Tenant-level | View-only can list/open. Editor can create/update/delete if delete exists. Super-admin follows active tenant and can edit. | Critical. Tenant-scoped integration config used by automation and reconciliation file movement. |
| NetSuite auth configs | Tenant-level | View-only can list/open redacted status. Editor can create/update. Super-admin follows active tenant and can edit. | Critical. Credentials must not be global because tenants can connect to different NetSuite accounts. |
| NetSuite endpoint configs | Tenant-level | View-only can list/open. Editor can create/update. Super-admin follows active tenant and can edit. | Critical. Endpoint URLs, RESTlet config, auth binding, and timeouts vary by tenant. |
| Saved runs and mappings | Tenant-level | View-only can list/open/run only where backend permits read-only run behavior. Editor can create/update/delete/run. Super-admin follows active tenant and can edit. | Critical. Saved reconciliation definitions are shared operational tenant assets, not personal user settings. |
| Schemas | Tenant-level | View-only can list/open. Editor can create/update/delete/infer/save. Super-admin follows active tenant and can edit. | Critical where schemas drive saved runs, mapping setup, and field selection. |
| Results and generated outputs | Tenant-level | View-only can list/open/download. Editor can create by running reconciliations and delete where supported. Super-admin follows active tenant and can edit. | Critical where results are shared evidence for tenant users. |
| AI/LLM provider setup | Admin/global | Super-admin only for view and edit. No tenant editor access. | Global/admin-only. Provider, model, base URL, timeout, and secrets affect the app runtime, not one tenant. |
| Enum/global system settings | Admin/global | Super-admin only for view and edit. | Global/admin-only. Enum choices and global operational settings should not be tenant-edited. |
| App-wide operational config | Admin/global | Super-admin only for view and edit. | Global/admin-only. Includes system-message remotes and runtime operational config that changes platform behavior. |
| `HcReadDbConfig` | Deferred/admin-global if retained | Super-admin only. | Excluded from tenant production scope unless production explicitly requires tenant-owned read DB sources. Current service already treats it as super-admin-only. |
| Pins and saved UI preferences | User-level | Authenticated user owns personal preference. | User-level. Pins stay in `UserPreference`; rendered targets must still be visible in the active tenant. |

## Settings Surface Matrix

| Surface | Tenant | User | Admin/Global | Deferred |
| --- | --- | --- | --- | --- |
| Tenant groups (`UgtDarpanCompany`) | Yes. Tenant identity is a `UserGroup`. | No. | Super-admins can administer groups. | No. |
| User tenant membership (`UserGroupMember`) | Yes. Controls selectable tenants. | Per user assignment. | Super-admin setup task. | No. |
| Tenant-specific permission (`TenantUserPermissionGroupMember`) | Yes. Bound to one tenant and one user. | Per user assignment. | Super-admin setup task. | No. |
| Active tenant preference (`darpan.auth.activeTenantUserGroupId`) | Selects current tenant scope. | Stored per user. | Super-admins also operate through an active tenant. | No. |
| SFTP servers | Yes. `SftpServer.companyUserGroupId`. | Created-by metadata only. | No, except super-admin acting in active tenant. | No. |
| NetSuite auth configs | Yes. `NsAuthConfig.companyUserGroupId`. | Created-by metadata only. | No, except super-admin acting in active tenant. | No. |
| NetSuite endpoint configs | Yes. `NsRestletConfig.companyUserGroupId`; auth binding must be visible in the active tenant. | Created-by metadata only. | No, except super-admin acting in active tenant. | No. |
| Saved runs and mappings | Yes. `ReconciliationMapping.companyUserGroupId` and related saved-run contract. | Created-by metadata only. | No, except super-admin acting in active tenant. | No. |
| Schemas | Yes. `JsonSchema.companyUserGroupId`. | Created-by metadata only. | No, except super-admin acting in active tenant. | No. |
| Results and generated outputs | Yes. `ReconciliationRunResult.companyUserGroupId` plus generated-output metadata/path filtering. | Created-by metadata only. | No, except super-admin acting in active tenant. | No. |
| Pins and saved UI preferences | Rendered target must be visible in active tenant. | Yes. Stored as `UserPreference`. | No. | No. |
| AI/LLM provider settings | No. | No. | Yes. `get#LlmSettings` and `save#LlmSettings` require super-admin. | No. |
| Enum/global settings | No. | No. | Yes. `list#EnumOptions` requires super-admin. | No. |
| App-wide operational config | No. | No. | Yes. Use only for platform-wide behavior. | No. |
| `HcReadDbConfig` | Not currently tenant-owned. | No. | Current list/save services require super-admin. | Yes, if production needs per-tenant read DB sources. |

## Operational Verification

Run these checks after production setup or after moving a tenant to a new environment.

### Tenant and Active-Tenant Contract

- Login as a tenant editor and call `facade.AuthFacadeServices.get#SessionInfo`.
- Confirm `availableTenants` contains only Darpan tenant groups for that user.
- Confirm `activeTenantUserGroupId` matches the tenant being verified.
- Confirm `activeTenantPermissionGroupIds` includes `DARPAN_COMPANY_EDITOR` and `canEditActiveTenantData` is true.
- Switch to a tenant where the same user is view-only through `save#ActiveTenant`.
- Confirm `activeTenantPermissionGroupIds` includes `DARPAN_COMPANY_VIEW_ONLY` and `canEditActiveTenantData` is false.

### SFTP Settings

- As tenant A editor, save an SFTP server through `facade.SettingsFacadeServices.save#SftpServer`.
- Confirm the stored `darpan.reconciliation.SftpServer.companyUserGroupId` is tenant A and credentials are stored only in encrypted fields (`password` or `privateKey`).
- Switch to tenant B and call `list#SftpServers`; the tenant A server must not appear.
- As a view-only user in the same tenant, call `list#SftpServers`; the server may appear with secret indicators only.
- As a view-only user, call `save#SftpServer`; the service must fail with the active-tenant view-only write error.
- If automation uses the server, run or dry-run the SFTP automation path and confirm it resolves only active-tenant server IDs.

### NetSuite Auth and Endpoint Settings

- As tenant A editor, save `NsAuthConfig` through `save#NsAuthConfig`.
- Save `NsRestletConfig` through `save#NsRestletConfig` using the tenant A auth config.
- Confirm both rows carry tenant A `companyUserGroupId`.
- Switch to tenant B and call `list#NsAuthConfigs` and `list#NsRestletConfigs`; tenant A rows must not appear.
- Attempt to save a tenant B endpoint that references tenant A auth; the backend must reject it because the auth config is not available in the active tenant.
- Confirm endpoint timeout, method, active flag, and headers JSON remain endpoint-level settings, while credentials stay on `NsAuthConfig`.

### Saved Runs, Mappings, Schemas, and Results

- Create or confirm schemas in tenant A and tenant B; `list#JsonSchemas` should return only the active tenant's schemas.
- Create or confirm saved mappings/runs in each tenant; `list#Mappings` and `list#SavedRuns` should not cross tenants.
- Run a reconciliation as tenant A editor and confirm `ReconciliationRunResult.companyUserGroupId` is tenant A.
- Switch to tenant B and confirm run history and result detail do not show tenant A generated outputs.
- Confirm view-only users can read visible saved records and results but cannot create, update, delete, or run where the service requires active-tenant write access.
- Confirm pinned records remain user-level preferences and disappear from rendering when the pinned target is not visible in the active tenant.

### AI/LLM, Enum, and Global Settings

- As a non-super-admin tenant user, call `get#LlmSettings`, `save#LlmSettings`, and `list#EnumOptions`; all must fail with the super-admin settings restriction.
- As a super-admin, call the same services and confirm they operate independently of tenant editor/view-only permission.
- Do not configure tenant-specific LLM providers in the current data model; the current LLM settings are platform-wide `SystemMessageRemote` records.

### `HcReadDbConfig` Deferral

- As a non-super-admin tenant user, call `list#HcReadDbConfigs` and `save#HcReadDbConfig`; both must fail with the super-admin settings restriction.
- As a super-admin, confirm `HcReadDbConfig` can be listed/saved only as admin-global configuration.
- Do not treat `HcReadDbConfig` as tenant-scoped until the entity has explicit tenant ownership, migration data, and active-tenant validation.
- Before using Read DB inventory flows in production, validate any runtime service that accepts `readDbConfigId` cannot let a tenant user bind an arbitrary global `HcReadDbConfig`.

## Task-Ready Checklist

### 1. Lock the permission contract in one shared UI policy

- [ ] Add or reuse a UI helper that exposes `canViewTenantSettings`, `canEditTenantSettings`, and `canManageGlobalSettings` from `useAuthState().sessionInfo`.
- [ ] Owner files:
  - `darpan-ui/src/lib/auth.ts`
  - `darpan-ui/src/lib/utils/tenantRecords.ts` or a new small permission helper if no existing helper fits
  - `darpan-ui/src/__tests__/App.spec.ts`
- [ ] Backend contract source:
  - `darpan-backend/runtime/component/darpan/service/facade/AuthFacadeServices.xml`
  - `darpan-backend/runtime/component/darpan/src/main/groovy/darpan/facade/common/TenantAccessSupport.groovy`
- [ ] Validation:
  - Unit test the helper for view-only tenant, editor tenant, and super-admin sessions.
  - Confirm tenant switching updates the effective helper state after `save#ActiveTenant`.

### 2. Gate tenant-level Settings UI affordances

- [ ] Hide or disable create/save controls for view-only tenant sessions on SFTP, NetSuite auth, NetSuite endpoint, runs, schemas, and result delete/run surfaces.
- [ ] Keep list/detail/read affordances available for view-only users.
- [ ] Owner files:
  - `darpan-ui/src/pages/settings/SftpServersPage.vue`
  - `darpan-ui/src/pages/settings/SftpServerWorkflowPage.vue`
  - `darpan-ui/src/pages/settings/NetSuiteSettingsPage.vue`
  - `darpan-ui/src/pages/settings/NetSuiteAuthWorkflowPage.vue`
  - `darpan-ui/src/pages/settings/NetSuiteEndpointWorkflowPage.vue`
  - `darpan-ui/src/pages/settings/RunsSettingsPage.vue`
  - `darpan-ui/src/pages/settings/RunsSettingsWorkflowPage.vue`
  - `darpan-ui/src/pages/jsonschema/JsonSchemaBrowsePage.vue`
  - `darpan-ui/src/pages/jsonschema/JsonSchemaWizardPage.vue`
  - `darpan-ui/src/pages/jsonschema/JsonSchemaEditorPage.vue`
  - `darpan-ui/src/pages/reconciliation/ReconciliationRunHistoryPage.vue`
  - `darpan-ui/src/pages/reconciliation/ReconciliationRunResultPage.vue`
- [ ] Backend contract source:
  - `darpan-backend/runtime/component/darpan/service/facade/SettingsFacadeServices.xml`
  - `darpan-backend/runtime/component/darpan/service/facade/JsonSchemaFacadeServices.xml`
  - `darpan-backend/runtime/component/darpan/service/facade/ReconciliationFacadeServices.xml`
- [ ] Validation:
  - UI tests for `DARPAN_COMPANY_VIEW_ONLY` must show saved records but no create/save/delete/run actions.
  - UI tests for `DARPAN_COMPANY_EDITOR` must show create/save actions.
  - Backend smoke calls must still reject write services for view-only active tenants.

### 3. Keep SFTP tenant scoped

- [ ] Keep list filtering by active tenant and write checks on save.
- [ ] Confirm create stamps `companyUserGroupId` and `createdByUserId`.
- [ ] Owner files:
  - `darpan-backend/runtime/component/darpan/service/facade/SettingsFacadeServices.xml`
  - `darpan-ui/src/pages/settings/SftpServersPage.vue`
  - `darpan-ui/src/pages/settings/SftpServerWorkflowPage.vue`
  - `darpan-ui/src/pages/settings/__tests__/SftpServersPage.spec.ts`
  - `darpan-ui/src/pages/settings/__tests__/SftpServerWorkflowPage.spec.ts`
- [ ] Validation:
  - List SFTP as editor in tenant A and tenant B; records must not cross tenants.
  - Save SFTP as view-only tenant; backend must return the active-tenant view-only error.
  - Switch tenant from `darpan-ui/src/App.vue`; SFTP list must refresh to the new tenant.

### 4. Keep NetSuite auth and endpoints tenant scoped

- [ ] Keep auth configs and endpoint configs scoped to the active tenant.
- [ ] Keep endpoint auth binding tenant-safe: endpoint save may only bind to an auth config visible in the active tenant.
- [ ] Owner files:
  - `darpan-backend/runtime/component/darpan/service/facade/SettingsFacadeServices.xml`
  - `darpan-ui/src/pages/settings/NetSuiteSettingsPage.vue`
  - `darpan-ui/src/pages/settings/NetSuiteAuthWorkflowPage.vue`
  - `darpan-ui/src/pages/settings/NetSuiteEndpointWorkflowPage.vue`
  - `darpan-ui/src/pages/settings/__tests__/ConnectionsSavedLists.spec.ts`
  - `darpan-ui/src/pages/settings/__tests__/NetSuiteAuthWorkflowPage.spec.ts`
  - `darpan-ui/src/pages/settings/__tests__/NetSuiteEndpointWorkflowPage.spec.ts`
- [ ] Validation:
  - List auth and endpoints as tenant A and tenant B; records must not cross tenants.
  - Save endpoint against another tenant's auth config; backend must reject with tenant access error.
  - Save auth or endpoint as view-only tenant; backend must reject with active-tenant view-only error.

### 5. Keep saved runs, mappings, schemas, and results tenant scoped

- [ ] Treat saved runs and mappings as tenant-level operational setup.
- [ ] Treat schemas as tenant-level data contracts where they are used by saved runs or result review.
- [ ] Treat generated outputs/results as tenant-level evidence.
- [ ] Keep pins user-level and filter pinned targets through active-tenant visibility.
- [ ] Owner files:
  - `darpan-backend/runtime/component/darpan/service/facade/ReconciliationFacadeServices.xml`
  - `darpan-backend/runtime/component/darpan/service/facade/JsonSchemaFacadeServices.xml`
  - `darpan-backend/runtime/component/darpan/src/main/groovy/darpan/facade/reconciliation/ReconciliationOutputSupport.groovy`
  - `darpan-backend/runtime/component/darpan/src/main/groovy/darpan/facade/reconciliation/listSavedRuns.groovy`
  - `darpan-backend/runtime/component/darpan/src/main/groovy/darpan/facade/reconciliation/listMappings.groovy`
  - `darpan-ui/src/pages/settings/RunsSettingsPage.vue`
  - `darpan-ui/src/pages/settings/RunsSettingsWorkflowPage.vue`
  - `darpan-ui/src/pages/jsonschema/*`
  - `darpan-ui/src/pages/reconciliation/ReconciliationRunHistoryPage.vue`
  - `darpan-ui/src/pages/reconciliation/ReconciliationRunResultPage.vue`
- [ ] Validation:
  - Tenant A and tenant B must see separate schema, saved-run, mapping, and result lists.
  - View-only tenant must read saved records and results but fail create/update/delete/run actions where backend requires write access.
  - Pinned records must not render when the target is hidden by the active tenant.

### 6. Keep global/admin-only Settings out of tenant editors

- [ ] Restrict AI/LLM settings navigation and workflow access to super-admin users.
- [ ] Keep enum/global system settings super-admin only.
- [ ] Keep app-wide operational config super-admin only.
- [ ] Owner files:
  - `darpan-backend/runtime/component/darpan/service/facade/SettingsFacadeServices.xml`
  - `darpan-ui/src/pages/settings/LlmSettingsPage.vue`
  - `darpan-ui/src/pages/settings/LlmSettingsWorkflowPage.vue`
  - `darpan-ui/src/pages/settings/__tests__/LlmSettingsPage.spec.ts`
  - `darpan-ui/src/pages/settings/__tests__/LlmSettingsWorkflowPage.spec.ts`
  - `darpan-ui/src/App.vue`
  - `darpan-ui/src/router/index.ts`
- [ ] Validation:
  - Non-super-admin users cannot open or save AI settings.
  - Direct API calls to `get#LlmSettings`, `save#LlmSettings`, and `list#EnumOptions` fail for non-super-admin users.
  - Super-admin users can view/edit global settings independent of active tenant.

### 7. Defer `HcReadDbConfig` unless production requires it

- [ ] Do not add `HcReadDbConfig` to tenant-level Settings by default.
- [ ] If production requires per-tenant read DB sources, first add explicit tenant ownership and migration design. Do not reuse the current global/admin-only service as tenant data.
- [ ] Owner files if deferred:
  - `darpan-backend/runtime/component/darpan/service/facade/SettingsFacadeServices.xml`
- [ ] Additional owner files if later promoted to tenant scope:
  - `darpan-backend/runtime/component/darpan/entity/*.xml`
  - `darpan-backend/runtime/component/darpan/data/*.xml`
  - any future `darpan-ui/src/pages/settings/HcReadDb*` page
- [ ] Validation:
  - Current state: non-super-admin direct API calls to `list#HcReadDbConfigs` and `save#HcReadDbConfig` fail.
  - Future tenant promotion: tenant A and tenant B must not see or bind each other's DB configs.

## Release Readiness Checks

- [ ] Backend compile: `./gradlew :runtime:component:darpan:compileGroovy`
- [ ] Backend focused tests for auth/session, settings facade, schema facade, reconciliation facade, and generated-output access.
- [ ] UI unit tests for Settings pages, App tenant switching, schema pages, run settings, and result pages.
- [ ] Manual browser smoke:
  - Login as an editor tenant user.
  - Save SFTP, NetSuite auth, NetSuite endpoint, schema, and saved run.
  - Switch to a view-only tenant in the user menu.
  - Confirm tenant-specific records refresh and write controls disappear or fail closed.
  - Login as super-admin and confirm global AI settings are available while tenant-owned data still follows the active tenant.
