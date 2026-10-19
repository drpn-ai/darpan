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
| Tenant, user and membership management | `admin.TenantAdminServices`, `admin.UserAdminServices` and `admin.MembershipAdminServices` (the admin app); records are `moqui.security.UserGroup` (`UgtDarpanCompany`), `moqui.security.UserGroupMember` and `darpan.auth.TenantUserPermissionGroupMember`. | Super-admin only. Use `DARPAN_TENANT_ADMIN` for write access and `DARPAN_TENANT_USER` for view + run; `DARPAN_COMPANY_EDITOR` and `DARPAN_COMPANY_VIEW_ONLY` are legacy. Always give a user an email address. See [tenant-setup](tenant-setup.md). |
| Tenant settings | `get#TenantSettings` and `save#TenantSettings`; data is stored in `darpan.auth.TenantSetting`. | Tenant-owned via `companyUserGroupId`; timezone is shared by users in the active tenant and replaces user-level timezone editing. |
| SFTP settings | `list#SftpServers` and `save#SftpServer`; data is stored in `darpan.reconciliation.SftpServer`. | Tenant-owned via `companyUserGroupId`; verify tenant A/B isolation and view-only save rejection. |
| Chat-space notification registry | `list#TenantChatSpaces`, `save#TenantChatSpace`, and `delete#TenantChatSpace`; data is stored in `darpan.reconciliation.TenantChatSpace`. Slack workspaces connect through `facade.SlackFacadeServices` (`begin#SlackInstall`, `save#SlackBotToken`, `list#SlackChannels`, `disconnect#SlackWorkspace`). | Multiple named spaces per `companyUserGroupId`, each a Google Chat webhook or a Slack channel. Webhook URLs are stored in clear text and `list#TenantChatSpaces` returns them to members of the tenant. Write requires active-tenant write access; a space referenced by an automation or a live subscription must be deactivated, not deleted. Terminal-state runs (success, with-issues, failed, reaper-killed) notify the automation's linked space plus any per-run "notify me" subscriber snapshots. `TenantNotificationSetting` (one webhook per tenant) is retired — see [reconciliation-flow](../reconciliation-flow.md) §6. |
| Personal notification default | `get#UserNotificationDefault` and `save#UserNotificationDefault`; data is a per-tenant JSON map on `moqui.security.UserPreference`. | Personal preference, no tenant write access required. |
| Run notification subscription | `subscribe#RunNotification` and `unsubscribe#RunNotification` on `facade.ReconciliationFacadeServices`; data is stored in `darpan.reconciliation.ReconciliationRunNotifySubscription`. | Personal opt-in for one in-flight run, no tenant write access required; snapshots the caller's default chat space at subscribe time. |
| NetSuite auth | `list#NsAuthConfigs` and `save#NsAuthConfig`; data is stored in `darpan.reconciliation.NsAuthConfig`. | Owned by one tenant via `companyUserGroupId`; can be shared with named tenants through `facade.ConfigSharingFacadeServices` (`ConfigTenantAccess`), never globally. |
| NetSuite endpoints | `list#NsRestletConfigs` and `save#NsRestletConfig`; data is stored in `darpan.reconciliation.NsRestletConfig`. | Tenant-owned via `companyUserGroupId`; endpoint save validates that the referenced auth config is visible in the active tenant. |
| NetSuite SuiteQL queries | `facade.NetSuiteQueryFacadeServices` (`list/get/save#NsSuiteQlSourceQuery`); data is stored in `darpan.reconciliation.NsSuiteQlSourceQuery`. | Tenant-owned; the query runs with the referenced NetSuite auth config. |
| Shopify and HotWax OMS sources | `facade.ShopifyFacadeServices` (`*#ShopifyAuthConfig`) and `facade.HotWaxOmsFacadeServices` (`*#HotWaxOmsRestSourceConfig`), served by the `shopify-darpan` and `darpan-hotwax` components. | Owned by one tenant; shareable through `ConfigTenantAccess` like NetSuite auth. Endpoint access per config is `facade.SourceEndpointFacadeServices`. |
| Schemas | JSON schema facade services use `darpan.reconciliation.JsonSchema`. | Tenant-owned via `companyUserGroupId`; `list#JsonSchemas` should only return active-tenant schemas. |
| Saved runs and mappings | Reconciliation facade services use `darpan.mapping.ReconciliationMapping` and saved-run support. | Treat saved runs and mappings as tenant-level operational setup, not personal preferences. |
| Results | Reconciliation output support uses `darpan.reconciliation.ReconciliationRunResult` first, with generated-output metadata as a fallback for migration files. | Results are tenant evidence shared by users in the same active tenant; verify result list/detail/delete does not cross active tenants. |
| AI/LLM settings | `get#LlmSettings` and `save#LlmSettings`. | Darpan Admin only (`DARPAN_ADMIN` or Moqui `ADMIN`) and app-global. The settings are stored; no product feature reads them yet. |
| Enum/global settings | `list#EnumOptions`. | Darpan Admin only; do not delegate to tenant editors. |

## Current Repo Footprint

- Configuration entities (Reconciliation, Run, RuleSet, etc): `runtime/component/darpan/entity/ReconciliationEntities.xml`
- Tenant permission entity: `runtime/component/darpan/entity/AuthEntities.xml`
- Tenant groups, permission groups, and active-tenant entity filters: `runtime/component/darpan/data/SecuritySeedData.xml`
- Auth/session facade: `runtime/component/darpan/service/facade/AuthFacadeServices.xml`
- Settings facade: `runtime/component/darpan/service/facade/SettingsFacadeServices.xml`
- Spark-based compare services: `runtime/component/darpan/service/reconciliation/ReconciliationCoreServices.xml`, `runtime/component/darpan/service/reconciliation/ReconciliationGenericServices.xml`
- NetSuite inventory retrieval lives in `runtime/component/netsuite-darpan/service/reconciliation/NetSuiteInventoryServices.xml`; Darpan no longer carries inventory retrieval wrappers.

## UI Facade Contracts

- The full remote method list is generated: `runtime/component/darpan/docs/api-contract/methods.txt`
  (OpenAPI: `runtime/component/darpan/docs/api-contract/openapi.json`).
- Facade behaviour notes (Settings + JSON Schema):
  `runtime/component/darpan/docs/reconciliation/platform/facade-behaviour-notes.md`
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
