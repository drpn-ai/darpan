# Reconciliation Platform Security

## Goal

Protect client data end-to-end and prevent cross-client leakage while keeping the platform operable at scale.

## Core Principles

- Least privilege for services, users, and integrations
- Strong tenant isolation by default
- Encrypt data in transit and at rest
- Auditability for all access and changes

## Code Touchpoints (Current Repo)

- Remote system credentials and endpoints: `framework/entity/ServiceEntities.xml` (SystemMessageRemote; encrypted fields include `password` and `privateKey`)
- User groups and permissions: `framework/entity/SecurityEntities.xml`
- Darpan tenant groups, tenant permission groups, and entity filters: `runtime/component/darpan/data/SecuritySeedData.xml`
- Per-tenant permission membership: `runtime/component/darpan/entity/AuthEntities.xml` (`darpan.auth.TenantUserPermissionGroupMember`)
- Tenant-owned reconciliation settings and result metadata: `runtime/component/darpan/entity/ReconciliationEntities.xml`
- Active-tenant session contract: `runtime/component/darpan/service/facade/AuthFacadeServices.xml`
- Settings facade access gates: `runtime/component/darpan/service/facade/SettingsFacadeServices.xml`
- Credential sharing between tenants: `runtime/component/darpan/service/facade/ConfigSharingFacadeServices.xml` (`darpan.auth.ConfigTenantAccess`)
- Operator (super-admin) surface: `runtime/component/darpan/service/admin/` (`admin.*`, fenced by the `DARPAN_ADMIN_API` artifact group)
- JSON-RPC namespace fence: `runtime/component/darpan/src/main/groovy/darpan/facade/common/RpcMethodFence.groovy` (see [API Access Policy](api-access-policy.md))
- Tenant-scoped reads in Groovy: `runtime/component/darpan/src/main/groovy/darpan/facade/common/TenantScopedFinder.groovy`
- Target role, permission group, and membership contract: [Darpan Permissions Matrix](permissions-matrix.md)
- Tenant setup tutorial: [Darpan Tenant Setup Tutorial](tenant-setup.md)
- Tenant user setup tutorial: [Darpan Tenant User Setup Tutorial](tenant-user-setup.md)

## Data Isolation

- Tenant-scoped storage uses the active Darpan tenant user group as the working scope.
- Tenant-owned rows currently use `companyUserGroupId`; the term `tenant` is the normalized operational term.
- Entity filters in `DARPAN_ACTIVE_COMPANY_SCOPE` filter direct tenant-owned rows by `ec.user.context.activeTenantUserGroupId`.
- Source credentials are owned by one tenant and can be shared with others explicitly:
  - HotWax OMS, Shopify, NetSuite auth and NetSuite RESTlet configs are shared through
    `facade.ConfigSharingFacadeServices.grant#ConfigTenantAccess` (`ConfigTenantAccess` rows,
    `SCFG_*` config types). The caller must be a tenant admin of the target tenant and of a tenant
    that already uses the config. Sharing widens access to the same encrypted credentials; it is
    not a copy. Revoking sets `thruDate` and keeps the row as history.
  - SFTP servers can be shared with a tenant group through `SftpServerTenantAccess`.
  - A config with no grant is visible only to its owning tenant.
- Rule packages, saved mappings/runs, schemas, generated result metadata, and output visibility must remain isolated per active tenant.
- `ALL_USERS` must never be used as a fallback tenant data-view scope.

## Access Control

- Tenant identity and permission level are separate; the full role and membership contract lives in [Darpan Permissions Matrix](permissions-matrix.md).
- Tenant identity is represented by `moqui.security.UserGroup` rows with `groupTypeEnumId="UgtDarpanCompany"`.
- User tenant association is represented by `moqui.security.UserGroupMember` and controls the tenant switcher for non-super-admin users.
- Tenant-specific role assignment is represented by `darpan.auth.TenantUserPermissionGroupMember`.
- The target tenant roles are Super Admin, Tenant Admin, and Tenant User. Darpan Admin is a separate app-level settings capability.
- User login identity is represented by `moqui.security.UserAccount`; create accounts and reset passwords through the admin app or `admin.UserAdminServices` (always with an email address). Stock Moqui user services such as `update#Password` are not reachable over `/rpc/json`: the endpoint dispatches only `facade.*`, `admin.*` and `gql.*` and answers anything else with HTTP 403. Users change their own password with `facade.AuthFacadeServices.change#OwnPassword`.
- Super-admin checks accept Moqui `ADMIN` and `DARPAN_SUPER_ADMIN`. Darpan app-admin checks accept Moqui `ADMIN` and `DARPAN_ADMIN`. A `DARPAN_SUPER_ADMIN` or `DARPAN_ADMIN` user still needs `DARPAN_USER` base app membership unless the user also has Moqui `ADMIN` artifact access. Tenant permission seed data includes `DARPAN_TENANT_ADMIN` and `DARPAN_TENANT_USER`.
- Legacy `DARPAN_COMPANY_EDITOR` continues to grant Tenant Admin behavior, and legacy `DARPAN_COMPANY_VIEW_ONLY` remains view-only.
- Tenant User is not the same as a pure view-only role: this user can upload files and run reconciliation, but cannot mutate saved runs, schemas, rules, settings, users, tenants, or Darpan core configuration.
- Darpan-admin users can administer app-level settings and private Darpan core management screens. Super-admin users administer tenants and tenant-owned operational data through an active tenant unless a private admin screen intentionally aggregates across tenants.
- Tenant and user management is the operator surface `admin.*` (used by the Darpan admin app):
  `admin.TenantAdminServices.create#Tenant`, `admin.UserAdminServices.create#UserAccount`,
  `admin.MembershipAdminServices.add#TenantMember` and their update/disable/remove siblings. Only
  `DARPAN_SUPER_ADMIN` and Moqui `ADMIN` can call them, each call re-checks super-admin in code,
  and each write records a `darpan.admin.AdminAuditLog` row. A data load is the fallback only; see
  [Darpan Tenant Setup Tutorial](tenant-setup.md). Darpan does not maintain a separate company
  entity: a tenant is a `UserGroup` plus its `TenantSetting`.
- Short-lived credentials for services
- Environment-level separation (dev, staging, prod)

### Two-factor sign-in (DAR-BE-264)

- Authenticator-app codes (TOTP: 6 digits, 30 seconds, SHA-1) for every way a person gets a session: `login#Session`
  answers a right password from someone with two-factor on with a challenge, not a session (`twoFactorRequired`,
  `challengeToken`), and `verify#SignInCode` finishes it with a code or a one-time recovery code. Factors, recovery codes,
  challenges, the per-role policy and the evidence trail live in `darpan.auth` (`entity/TwoFactorEntities.xml`); Darpan
  never writes the framework's `moqui.security.UserAuthcFactor` and no `UserGroup` has `requireAuthcFactor=Y`.
- Who must use it is data: `darpan.auth.TwoFactorRolePolicy` per role (`ADMIN`, `DARPAN_SUPER_ADMIN`, `DARPAN_ADMIN`,
  `DARPAN_TENANT_ADMIN` in any tenant). Every role ships optional; `admin.SecurityAdminServices.update#TwoFactorPolicy`
  sets a date (a future date is the grace period, during which sign-in prompts) and writes an `AdminAuditLog` row.
  Requiring it for `ADMIN` or `DARPAN_SUPER_ADMIN` is refused until at least two super admins have it on.
- Limits: 5 wrong codes end a challenge; every wrong code is a failed login (the framework lockout: 3, then 5 minutes);
  20 wrong codes in 24 hours lock codes until a password reset or an admin reset, and the person is emailed. A code is
  accepted once (replay check). A password reset never ends or changes two-factor; it clears the code lock.
- A fresh code is asked for when approving a CLI sign-in (`approve#CliLogin`), a `/transfer` export or secret handoff
  (`twoFactorCode`), new recovery codes, turning it off, and an admin reset (the acting admin's own code).
- A login key issued before the person's factor was confirmed stops working (web and CLI); a CLI key of someone required
  and not enrolled is refused. Confirming a factor ends every other key and hands the confirming tab a fresh one.
- The framework's password-only ways in are refused for everyone (`PasswordOnlyRouteFence`, webroot before-request):
  HTTP Basic and `authUsername`/`authPassword` answer 401 (signed out again first), `/Login`, `/SecondFactor` and
  `/ChangePassword` answer 404. Not covered: a WebSocket handshake with Basic (no before-request hook; Darpan serves no
  WebSocket its clients use).
- Instance label in the authenticator app: set `DARPAN_TOTP_ISSUER` (e.g. `Darpan UAT`, `Darpan SM`); default `Darpan`.
- Reset: `admin.UserAdminServices.reset#TwoFactor` (super admins only, never one's own, a reason and the admin's own
  code) ends the factor and recovery codes and every session and CLI key, and writes `AdminAuditLog`, a
  `TwoFactorEvent` naming the admin, and an email to the person.

#### Passkeys and security keys (DAR-BE-270)

- WebAuthn (webauthn4j-core 0.30.3, Apache-2.0) beside the app: a person may hold the app, any number of passkeys (up to
  10), or both; any one answers the sign-in challenge (`begin#PasskeySignIn`, then `verify#SignInCode` with
  `passkeyAssertion`) and every fresh-proof action above (`begin#PasskeyProof`, then the action's passkey assertion
  parameter). `userVerification` required, attestation `none`, resident keys preferred. A passkey is a
  `TwoFactorFactor` row (`DrpnTfPkey`) plus its `PasskeyCredential` (public key and credential id, not encrypted: not
  secrets). A signature counter that does not move forward is refused (`PASSKEY_COUNTER_REGRESSED`). A refused passkey
  counts against the challenge only, not as a failed login. Adding one emails the person.
- Relying party per instance: `DARPAN_WEBAUTHN_RP_ID` (the UI's host, never the API's) and `DARPAN_WEBAUTHN_ORIGINS`
  (comma list). Unset, both come from `DARPAN_APP_BASE_URL` and `webapp_allow_origins` (https on the rpId or under it;
  http only for localhost); nothing usable means passkeys are unavailable there and the app keeps working.
- The last factor of a person whose role requires two-factor cannot be removed; `disable#TwoFactor` with `appOnly`
  removes just the app while a passkey remains. Admin reset and the break-glass below end passkeys too (every
  `TWO_FACTOR_FACTOR` row of the person). The `darpan-admin` CLI has no browser, so it stays on codes.

#### Break-glass: no super admin can pass two-factor

Keep at least two super admins enrolled (the policy switch refuses fewer), so one lost phone is a `reset#TwoFactor` by
the other. Only when no enrolled super admin can sign in, Aditi ends her own factor directly in the database, from an
ECS Exec session on the backend task (or the database console), and writes the same evidence the service would:

```sql
UPDATE TWO_FACTOR_FACTOR SET STATUS_ID = 'ENDED', ENDED_DATE = CURRENT_TIMESTAMP, ENDED_REASON = 'ADMIN_RESET'
  WHERE USER_ID = '<userId>' AND STATUS_ID IN ('ACTIVE', 'PENDING');
DELETE FROM TWO_FACTOR_RECOVERY_CODE WHERE USER_ID = '<userId>';
DELETE FROM USER_LOGIN_KEY WHERE USER_ID = '<userId>';
INSERT INTO TWO_FACTOR_EVENT (EVENT_ID, USER_ID, EVENT_TYPE, BY_USER_ID, EVENT_DATE)
  VALUES ('BREAKGLASS-<yyyymmdd>', '<userId>', 'ADMIN_RESET', '<userId>', CURRENT_TIMESTAMP);
INSERT INTO ADMIN_AUDIT_LOG (ADMIN_AUDIT_LOG_ID, ADMIN_USER_ID, SERVICE_NAME, TARGET_TYPE, TARGET_ID, DETAIL_TEXT, AUDIT_TIMESTAMP)
  VALUES ('BREAKGLASS-<yyyymmdd>', '<userId>', 'break-glass (runbook)', 'User', '<userId>',
          'Two-factor reset: break-glass, <reason>', CURRENT_TIMESTAMP);
```

The next sign-in then asks for setup (the role is required), and the first thing after it is to reset the other super
admins' lost factors through the admin app and record the incident.

## Data Protection

- TLS for all ingestion endpoints (SFTP, API, GraphQL)
- Encryption at rest for databases and file storage
- Secrets stored in a managed vault, never in code

## Audit and Monitoring

- Log all data access and rule changes
- Alert on unusual access patterns or data volumes
- Regular access reviews and key rotation

### Admin audit trail (DAR-BE-265)

- `darpan.admin.AdminAuditLog` gets one row per platform-admin mutation: every `admin.*` mutation (tenants, users,
  memberships, Darpan admins, knowledge, brain tickets, config transfer, AI access grants, CLI grants and requests,
  migration runs and parking), shared-config grants, super-admin data-source definitions, and a person-run output purge or
  crypt-key rotation. Reads, previews and dry runs write none, except the crypt-key rotation's dry run
  (`admin.SecurityAdminServices.rotate#EncryptedFields`, DAR-OPS-019), which decrypts every stored credential to count
  them and so is on the trail too (counts only). GraphQL API key mint and revoke live in `moqui-gql`
  (DAR-BE-267).
- `darpan.admin.DataExportLog` gets one row per file served for download (`get#GeneratedOutput`, and the super-admin
  legacy screen download): user, active tenant, file, format, size, time. Never the contents. Client-side exports the
  browser builds from already-loaded data are not seen by the server.
- Both entities are `create-only`: Moqui's entity facade refuses update and delete on them, including `updateAll`,
  `deleteAll` and the `update#`/`delete#`/`store#` entity-auto services. Raw SQL is the only way past that, so the
  database user the app connects as must not hold UPDATE or DELETE on the two tables. Ops step, run once per database
  by the database owner (MySQL; table names as Moqui creates them):

  ```sql
  -- The app user keeps INSERT and SELECT; it loses UPDATE and DELETE on the audit tables only.
  -- MySQL has no column-free table-level REVOKE once the grant is schema-wide, so grant per table:
  -- 1. If the app user holds ALL ON <schema>.*, replace it with per-table grants first (or use a role), then:
  REVOKE UPDATE, DELETE ON <schema>.ADMIN_AUDIT_LOG FROM '<app_user>'@'%';
  REVOKE UPDATE, DELETE ON <schema>.DATA_EXPORT_LOG FROM '<app_user>'@'%';
  SHOW GRANTS FOR '<app_user>'@'%';  -- evidence for the audit
  ```

  MySQL cannot revoke a privilege on one table that was granted schema-wide (`ON <schema>.*`); in that case move the app
  user to a role with explicit per-table grants, or add a `BEFORE UPDATE` / `BEFORE DELETE` trigger on each table that
  signals an error (`SIGNAL SQLSTATE '45000'`). Choose one with the database owner; record the `SHOW GRANTS` or
  `SHOW TRIGGERS` output as evidence.

### Data retention (DAR-BE-266)

- `retention.RetentionServices.run#Retention` deletes, tenant by tenant, in batches of their own transaction
  (`darpan.retention.batchSize`, default 500), the rows older than each table's period:

  | Table key | What goes | Period property (days) |
  |---|---|---|
  | `AUDIT_FINDING` | `AuditFinding` last seen before the cutoff, with its records, conditions and events | `darpan.retention.auditFinding.days` |
  | `RUN_RESULT` | finished `ReconciliationRunResult` created before the cutoff, with its steps, Slack message handles and notify subscriptions; an automation execution keeps its row and counts and forgets the run result id | `darpan.retention.runResult.days` |
  | `AGENT_MESSAGE` | the transcript (`AgentMessage`) of a conversation started before the cutoff with no model run since; the conversation and `AgentTurn` (spend ledger) stay | `darpan.retention.agentMessage.days` |
  | `AGENT_QUERY_LOG` | `AgentQueryLog` created before the cutoff | `darpan.retention.agentQueryLog.days` |

- Unset, blank, not a whole number, or below 1 keeps the table forever. Nothing is deleted until a period is set
  (DAR-OPS-017 decision 7). Rows with no tenant stamp are never touched.
- The job `run_DataRetention_daily` (03:30) is seeded paused. Each real run with any period set writes one
  `AdminAuditLog` row with the counts per table (target `Retention`).
- `admin.RetentionAdminServices.dryRun#Retention` (super admin) counts what a run would delete now, per table and tenant,
  and takes optional preview periods (`periodDays: {AUDIT_FINDING: 395}`) before any is set.
- Not covered here: generated output files (the existing 15-day `purge#GeneratedOutputFiles` job), run input and extract
  files on disk, `GqlQueryLog` (moqui-gql's own `gql.queryLog.retainDays`), and tenant offboarding
  ([design note](tenant-offboarding-delete-design.md), not built).

## Operational Practices

- Minimize PII in reconciliation datasets
- Mask or tokenize sensitive fields when possible
- Retention policies aligned to client agreements
- Before handing a tenant to production users, verify active-tenant session metadata, SFTP isolation, source-credential isolation (and any intended sharing grants), schema/mapping/result isolation, and Tenant User mutation rejection.
- Verify AI/LLM settings, enum/global settings, and app-wide operational config are available only to Darpan-admin users.

## Production Verification

The operational verification checklist lives in [Production Settings Surfaces](production-settings-surfaces.md). At minimum, verify:

- `get#SessionInfo` returns the intended `activeTenantUserGroupId`, `availableTenants`, `activeTenantPermissionGroupIds`, `canViewActiveTenantData`, `canRunActiveTenantReconciliation`, `canEditActiveTenantData`, `canManageDarpanCore`, and `isSuperAdmin`.
- tenant A and tenant B cannot see each other's SFTP, NetSuite, Shopify, HotWax OMS, schema, saved-run/mapping, or result records unless a sharing grant says so.
- a Tenant User active tenant can list/read tenant records and run reconciliation, but cannot save settings or create/update/delete tenant configuration.
- users without Darpan Admin access cannot call `get#LlmSettings`, `save#LlmSettings`, or `list#EnumOptions`.

## Next Steps

- Keep production tenant setup aligned with [Darpan Tenant Setup Tutorial](tenant-setup.md) and [Tenant-Scoped Access and User-Level Preferences](company-scoped-access-and-user-preferences.md)
- Choose the vault and logging stack
- Document security requirements for integrations
