# Standalone Reconciliation Platform - Service Overview

## Goal

Move reconciliation out of OMS into a standalone Moqui platform while keeping integrations stable and data isolated per client.

## Core Services and Purpose

- Ingestion Service: accept POS and OMS extracts (SFTP/API) and normalize into canonical records
- Reconciliation Service: run rules against canonical records and produce match, missing, and mismatch outcomes
- Reporting Service: assemble outputs (CSV or queryable views) and publish to configured destinations
- Integration Service: manage outbound deliveries (SFTP push, API callbacks) and status tracking
- Configuration Service: store tenant-specific rules, schedules, and mapping configurations
- Audit and Monitoring Service: record runs, rule versions used, and operational metrics

## Production Tenant Settings Services

Production multi-user setup depends on these current facade and entity contracts:

| Service area | Current contract | Production setup note |
| --- | --- | --- |
| Auth/session | `facade.AuthFacadeServices.login#Session`, `get#SessionInfo`, and `save#ActiveTenant` return active-tenant metadata. | Verify `activeTenantUserGroupId`, `availableTenants`, `activeTenantPermissionGroupIds`, `canEditActiveTenantData`, and `isSuperAdmin` before configuring tenant-owned settings. |
| Tenant membership | `moqui.security.UserGroup`, `moqui.security.UserGroupMember`, and `darpan.auth.TenantUserPermissionGroupMember`. | Use `UgtDarpanCompany` for tenants, `DARPAN_COMPANY_EDITOR` for write access, and `DARPAN_COMPANY_VIEW_ONLY` for read-only access. |
| Tenant settings | `get#TenantSettings` and `save#TenantSettings`; data is stored in `darpan.auth.TenantSetting`. | Tenant-owned via `companyUserGroupId`; timezone is shared by users in the active tenant and replaces user-level timezone editing. |
| SFTP settings | `list#SftpServers` and `save#SftpServer`; data is stored in `darpan.reconciliation.SftpServer`. | Tenant-owned via `companyUserGroupId`; verify tenant A/B isolation and view-only save rejection. |
| Notification settings | `get#TenantNotificationSettings` and `save#TenantNotificationSettings`; data is stored in `darpan.reconciliation.TenantNotificationSetting`. | One tenant-owned Google Chat webhook per `companyUserGroupId`; facade responses only return configured status and a masked URL. Successful manual and automation runs use the active tenant setting for completion notifications. |
| NetSuite auth | `list#NsAuthConfigs` and `save#NsAuthConfig`; data is stored in `darpan.reconciliation.NsAuthConfig`. | Tenant-owned via `companyUserGroupId`; credentials must not be shared globally across tenants. |
| NetSuite endpoints | `list#NsRestletConfigs` and `save#NsRestletConfig`; data is stored in `darpan.reconciliation.NsRestletConfig`. | Tenant-owned via `companyUserGroupId`; endpoint save validates that the referenced auth config is visible in the active tenant. |
| Schemas | JSON schema facade services use `darpan.reconciliation.JsonSchema`. | Tenant-owned via `companyUserGroupId`; `list#JsonSchemas` should only return active-tenant schemas. |
| Saved runs and mappings | Reconciliation facade services use `darpan.mapping.ReconciliationMapping` and saved-run support. | Treat saved runs and mappings as tenant-level operational setup, not personal preferences. |
| Results | Reconciliation output support uses `darpan.reconciliation.ReconciliationRunResult` first, with generated-output metadata as a fallback for migration files. | Results are tenant evidence shared by users in the same active tenant; verify result list/detail/delete does not cross active tenants. |
| AI/LLM settings | `get#LlmSettings` and `save#LlmSettings`. | Super-admin only and admin-global in the current implementation. |
| Enum/global settings | `list#EnumOptions`. | Super-admin only; do not delegate to tenant editors. |

## Current Repo Footprint

- Configuration entities (Reconciliation, Run, RuleSet, etc): `runtime/component/darpan/entity/ReconciliationEntities.xml`
- Tenant permission entity: `runtime/component/darpan/entity/AuthEntities.xml`
- Tenant groups, permission groups, and active-tenant entity filters: `runtime/component/darpan/data/SecuritySeedData.xml`
- Auth/session facade: `runtime/component/darpan/service/facade/AuthFacadeServices.xml`
- Settings facade: `runtime/component/darpan/service/facade/SettingsFacadeServices.xml`
- Inventory retrieval services (NS Restlet + read-only DB): `runtime/component/darpan/service/reconciliation/ReconciliationInventoryServices.xml`
- Spark-based compare services: `runtime/component/darpan/service/reconciliation/ReconciliationCoreServices.xml`, `runtime/component/darpan/service/reconciliation/ReconciliationGenericServices.xml`
- Sample input data: `runtime/component/darpan/data/sample/omsData.json`, `runtime/component/darpan/data/sample/shopifyData.json`

## UI Facade Contracts (Wave 1)

- Wave 1 authenticated facade APIs for `darpan-ui` (Settings + JSON Schema):
  `runtime/component/darpan/docs/reconciliation/platform/facade-wave1-services.md`
- Production tenant settings setup and verification:
  `runtime/component/darpan/docs/reconciliation/platform/production-settings-surfaces.md`
- Tenant-scoped access and user-level preferences:
  `runtime/component/darpan/docs/reconciliation/platform/company-scoped-access-and-user-preferences.md`

## Platform Boundaries

- No direct reads from OMS or POS databases
- All heavy computation occurs inside the platform database and services
- Tenant isolation enforced at storage and rule execution levels
- Tenant-scoped settings are shared tenant assets; pins and saved UI preferences remain user-level.
- AI/LLM, enum/global, and app-wide operational config are not tenant-editor surfaces in the current implementation.
