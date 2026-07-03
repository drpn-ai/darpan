# Darpan Tenancy Model

Status: current as of 2026-07-02 (MACH P3 — documents the pooled model and the staged
isolation backlog; nothing here is a commitment to build).

## Today: pooled multi-tenancy

All tenants share one deployment end to end:

- **One database.** Tenant rows are discriminated by `companyUserGroupId`
  (a `moqui.security.UserGroup`). Isolation is enforced in three layers:
  `TenantScopedFinder` (default-deny SQL predicate on every tenant read),
  `DARPAN_ACTIVE_COMPANY_SCOPE` EntityFilters (authz-enabled paths), and
  fail-closed system-write assertions (`assertSystemWriteTenant`) on cron/SFTP paths.
- **One Spark runtime.** Reconciliation batches for all tenants share the JVM-embedded
  SparkSession. A slow batch delays, but cannot read, another tenant's run
  (per-run artifact directories, tenant-stamped run results).
- **One crypt key.** Encrypted fields (`encrypt="true"`: partner credentials, webhook
  URLs, private keys) share `entity_ds_crypt_pass`. Rotation is dual-key with a bulk
  re-encryption sweep — see [crypt-key-rotation.md](crypt-key-rotation.md).
- **One rule-engine JVM.** Tenant rules are compiled from structured expressions
  (raw DRL fail-closed by default, DAR-289) and evaluated under a fire-count cap plus
  a wall-clock watchdog (`darpan.reconciliation.rule.maxEvalMillis`).

## Known pooled-model limits (accepted, monitored)

- **Noisy neighbor:** a large reconciliation run occupies shared Spark capacity. The
  watchdog and window-splitting bound the damage; there is no per-tenant quota.
- **Blast radius:** a JVM-level failure affects all tenants (single deployable —
  see ADR 0001 for why this stays a modular monolith).
- **Data residency:** all tenants live in one region/database; no residency pinning.

## Staged isolation backlog (build triggers, not plans)

| Stage | What | Build when |
| --- | --- | --- |
| 1 | Tenant tier flag (`TenantSetting`) gating batch size / schedule priority | a real noisy-neighbor incident, or a paying tier needs guarantees |
| 2 | Per-tenant crypt key (key-id column + keyring; rotation sweep already exists) | a customer contract requires key isolation |
| 3 | Dedicated worker pool / broker for heavy tenants | reconciliation compute outgrows vertical scaling (also the ADR 0001 extraction trigger) |
| 4 | Residency-pinned deployments (full stack per region) | a regulated customer requires it — this is a deployment shape, not a code change |

Anything proposing stages 2–4 should start from this doc plus ADR 0001 and name the
trigger it claims has fired.
