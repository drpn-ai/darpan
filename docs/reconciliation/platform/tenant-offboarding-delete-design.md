# Tenant offboarding hard delete — design note (DAR-BE-266)

Status: DRAFT for Aditi's review. Nothing here is built. It deletes a client's data for good, so every choice
below marked **Decide** waits for her.

## Why

Deactivating a tenant (`admin.TenantAdminServices.deactivate#Tenant`) only sets `TenantSetting.disabled = Y` and drops
sessions. Every row and file stays. SOC 2 C1.2 and the data-classification policy
(wrapper `docs/security/data-classification.md`, section 4) need a way to destroy a departed client's data and keep
evidence that it was destroyed.

## Shape

Two admin services, the same dry-run / run split as migrations and retention, so an omitted flag can never choose the
destructive branch:

- `admin.TenantOffboardingAdminServices.preview#TenantDelete(tenantUserGroupId)` — counts rows per entity and files per
  data-manager folder that the delete would remove; writes nothing but an `AdminAuditLog` row (a preview of a destructive
  action is itself worth recording).
- `admin.TenantOffboardingAdminServices.delete#Tenant(tenantUserGroupId, confirmTenantId, previewHash)` — refused unless:
  - the tenant has been deactivated for at least N days (**Decide** N, e.g. 30, so a mistaken deactivation can be undone);
  - `confirmTenantId` repeats the tenant id (typed by the operator);
  - `previewHash` matches a preview taken in the last hour (the counts did not change underneath);
  - the caller is a super admin (fence + in-service gate).

## What it deletes

Driven by entity metadata, not a hand list, so a new tenant-owned entity is covered the day it is added:

1. Every entity with a `companyUserGroupId` field (today about 150 fields across `AuditEntities`, `ReconciliationEntities`,
   `RuleEntities`, `AgentEntities`, `TenantConfigEntities`, `CliAuthEntities`, `ScriptEntities`, `MappingEntities`,
   `JsonSchemaEntities`, `KnowledgeEntities`, `AuthEntities`), plus the same in installed system components
   (`netsuite-darpan`, `shopify-darpan`, `darpan-hotwax`, `database-darpan`, `darpan-agent`).
2. Children without a tenant stamp, found through their `type="one"` relationship to a stamped parent
   (e.g. `ReconciliationRunStep` rows whose stamp is null, `SlackPostedMessage`).
3. Order: children before parents, by walking foreign keys (the retention sweep's batching and own-transaction-per-batch
   pattern, `darpan.retention.RetentionSupport`, is the base).
4. Tenant membership: `UserGroupMember` rows for the tenant group, `TenantUserPermissionGroupMember`, the tenant's
   `UserPreference` active-tenant pins, CLI grants, AI access grants, and finally `TenantSetting` and the `UserGroup`.
5. Files: the tenant's data-manager folders (run inputs, extracts, results) under `runtime/datamanager`.
6. Encrypted connector credentials go with their rows (step 1); nothing is kept to decrypt later.

**Decide:**

- Users who belong only to this tenant: disable, or delete the `UserAccount` too? (Users can span tenants; a
  shared user must keep their account.)
- `AdminAuditLog` and `DataExportLog` rows about the tenant: keep (they are the evidence and are create-only), and say so
  in the client contract.
- AI spend ledger (`AgentTurn`): delete with the tenant, or keep a per-month cost total for billing?
- Backups: RDS snapshots still hold the data until they expire. State the backup retention in the deletion certificate.
- `darpan-config-<tenant>` GitHub repos and any `/transfer` local copies (data-classification section 5) are outside
  the database; list them as manual steps in the runbook.

## Evidence

- Before and after counts per entity and per folder, the preview hash, the operator, and the time, written as one
  `AdminAuditLog` row (target `Tenant`, id the tenant) plus a JSON certificate returned to the caller for the client file.
- A second preview after the delete must show zero for every entity; the service runs it itself and fails loudly if not.

## Not in scope here

Partial deletes (one table for one tenant on request) are the retention sweep's job with a tenant parameter; GDPR-style
per-person erasure inside a tenant's business data is not offered (that data is the client's, under the client's own
controls).
