# Darpan 3.1.0

Release date: 2026-11-17

## Scope note

- **A minor release on the 3.x line**: everything on `main` in every repo since 3.0.3 (2026-11-12). Aditi,
  2026-11-17: "prep a minor release"; scope "everything on main".
- Headline: two-factor sign-in with authenticator apps, passkeys and security keys (DAR-BE-264, DAR-BE-270); shorter
  sessions and 12-character passwords (DAR-BE-261, DAR-BE-268); tenant users run audits and manage connections
  (DAR-BE-292); a welcome email when someone is granted a tenant (DAR-BE-290); tighter tenant isolation and outbound
  HTTP; Shopify customer data read at Level 1; raw extracts deleted after 7 days; audit fixes (DAR-BE-289, 291, 293,
  294, DAR-UI-113 to 118).
- Facade contract stays at **4**: 23 methods added, all 243 existing methods preserved (`check_contract_compat.py`).
  Every component re-cuts at a minor version because each has commits of its own.
- Not in this release: the uncommitted CRM work in darpan (DAR-ADMIN-012, in progress); it ships when it is done.

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.0.3` | `v3.1.0` |
| `drpn-ai/darpan-ui` | `v4.0.3` | `v4.1.0` |
| `drpn-ai/darpan-hotwax` | `v1.0.1` | `v1.1.0` |
| `drpn-ai/shopify-darpan` | `v0.9.1` | `v0.10.0` |
| `drpn-ai/netsuite-darpan` | `v0.5.1` | `v0.6.0` |
| `drpn-ai/database-darpan` | `v0.3.0` | `v0.4.0` |
| `drpn-ai/darpan-agent` | `v0.1.2` | `v0.2.0` |
| `drpn-ai/moqui-gql` | `v1.0.0` | `v1.1.0` |
| `drpn-ai/darpan-docker-config` | `v3.0.3` | `v3.1.0` (every component pin moves) |
| `drpn-ai/moqui-framework` | `master` | `master` (prod builds it unpinned; carries DAR-BE-264/268/276) |

All tags are cut from `main`.

## User-visible changes

### Signing in

- **Two-factor sign-in.** A person can turn on an authenticator app (codes from Google Authenticator, 1Password and
  the like) in User Settings, with one-time recovery codes as the fallback. Once it is on, the right password asks
  for a code. Roles the policy requires set it up at their next sign-in, after a grace period an admin sets. A CLI
  sign-in is approved with a code too. Turning the app on emails the account (DAR-BE-264).
- **Passkeys and security keys.** Touch ID, Windows Hello, a phone passkey or a YubiKey can be the second factor, or
  the only one; a recovery code still works when the device is not at hand. Passkeys are added, renamed and removed
  in User Settings, and each addition emails the account (DAR-BE-270).
- **Sessions end sooner.** A web sign-in ends after 60 idle minutes and at most 24 hours after sign-in. Every CLI
  sign-in lasts 7 days, admins included (DAR-BE-261).
- **Passwords need 12 characters** when they are set or changed (DAR-BE-268).

### Tenants and roles

- **Tenant users run and connect.** A tenant user can play an audit, resolve a finding, run a saved run, a one-off
  comparison or a reconciliation, and save or delete NetSuite, Shopify, HotWax, SFTP and database connections. Only
  editors and tenant admins talk to the agent (DAR-BE-292).
- **A welcome email** from support@drpn.ai goes to a person when they are granted a tenant, and an admin can send it
  again (DAR-BE-290).

### Audits

- **Previous Week is seven days**, ending yesterday (DAR-UI-114).
- **Run setup no longer asks "What happens when a key repeats"**: every repeated key is a finding (DAR-UI-113).
- **Audit settings warns** when the chosen chat space can no longer be used (DAR-BE-256, app part).
- **An open order's detail opens its open line**, not a line fixed earlier the same day (DAR-UI-118).
- **The run update's report links name their tenant**, so a link opened while signed into another tenant lands on
  the right report (DAR-BE-293).
- **A finding's fix text names the record**, filling names such as the order name from the finding's own records
  (DAR-BE-294).
- **gorjana R10**: an open NetSuite line is judged by its own HotWax ship group, and lines NetSuite no longer holds
  open close as expected. This takes the gorjana config bundle as well; see Operator actions (DAR-BE-294).
- **A run that explains a very large number of records closes its fixed findings** (DAR-BE-294).
- **Runs compiling at the same time keep their own rules**: each rule set builds under its own release id
  (DAR-BE-289).
- **Checks the agent drafts read like configured ones**: mismatch headlines name each system's value and record
  cards name the key (DAR-BE-291).
- **The agent drafts new statements**, numbered in approval order on the board; a new check never takes a check id
  another audit uses (DAR-BE-286, DAR-BE-287, DAR-UI-117).

### Connections and data

- **Shopify customer data is read at Level 1**: order email and shipping address fields are gone from the extract
  and the field catalog, and a query that asks for customer email, phone, name or address is refused (DAR-BE-260).
- **Raw source extracts are deleted 7 days after their run**. Result files and findings stay (DAR-BE-271).
- **A NetSuite endpoint stays on its auth config's NetSuite account**, and a Shopify or NetSuite connection asks for
  its secret again when its shop URL or token URL changes (DAR-BE-255, DAR-BE-258).
- **Returns join-key text corrected**: the HotWax returns seed names `externalId` as the join key for refunded
  returns, and the archived-run error points to a control that exists (DAR-BE-257).

## Operator-visible changes

- **Every password-only way into the framework is closed**: HTTP Basic and `authUsername`/`authPassword` anywhere
  in a request answer 401, whatever the route (DAR-BE-264, DAR-BE-276). API clients use a session or an API key.
- **Password hashing is PBKDF2**, rehashed at sign-in (DAR-BE-268, moqui-framework `master`). This is one-way: a
  password rehashed after the deploy cannot be checked by a 3.0.3 image's framework (see Rollback).
- **Two-factor policy per role** (ADMIN, DARPAN_SUPER_ADMIN, DARPAN_ADMIN, DARPAN_TENANT_ADMIN), all optional at
  deploy; `admin.SecurityAdminServices.update#TwoFactorPolicy` (fresh proof required) makes a role required from a
  date. `admin.UserAdminServices.reset#TwoFactor` is the break-glass reset (DAR-BE-264).
- **Passkey relying party** defaults to the host of `DARPAN_APP_BASE_URL` (prod: app.drpn.ai). Steve Madden sets
  `DARPAN_WEBAUTHN_RP_ID` and `DARPAN_WEBAUTHN_ORIGINS` (DAR-BE-270). `DARPAN_TOTP_ISSUER` names the app entry.
- **Darpan admins in the admin app** (`add#DarpanAdmin`, `remove#DarpanAdmin`, `darpanRoles` on the user list and
  detail; DAR-ADMIN-011).
- **Admin audit trail** covers every admin mutation and every download; the app cannot change it (DAR-BE-265).
- **Retention** per table for findings, run results, AI transcripts and agent query logs, off until a period is set;
  `run_DataRetention_daily` is seeded paused; `dryRun#Retention` previews (DAR-BE-266).
- **Field-encryption key rotation**: `admin.SecurityAdminServices.rotate#EncryptedFields`, runbook
  `crypt-key-rotation.md` (DAR-OPS-019).
- **GraphQL API keys** get a maximum lifetime (`gql.apiKey.maxDays`, default 90; a blank expiry gets the maximum), an
  owner and audit rows (DAR-BE-267, moqui-gql v1.1.0).
- **Outbound HTTP** from every connector (data-defined sources, HotWax, Shopify) goes through one client that checks
  the address it connects to, follows no redirects and keeps no cookies (DAR-BE-262).
- **Tenant isolation**: the tenant entity filter fails closed with no active tenant (66 `EntityFilter` rows), JSON
  schemas, automation executions and generated outputs resolve per tenant, and a GraphQL caller profile's store scope
  narrows the tenant pin (DAR-BE-269, DAR-BE-279 to 282).
- **Finding records name the connection that read them** (`AuditFindingRecord.sourceConfigId`), so a Shopify shop
  erase removes exactly that store's records (DAR-BE-284).
- **Shopify app install and privacy webhooks, backend only**: `begin#ShopifyInstall`, `list/save#ShopifyApp`, the
  HMAC-checked receiver `POST /apps/shopify/webhook` and the privacy services (DAR-BE-272, 273, 275). No app UI yet;
  nothing is installed until a Darpan Shopify app is registered.
- **Prod image**: runs as non-root uid/gid 1000 (DAR-OPS-002); digest-pinned JDK stage, no git-credential build
  args (DAR-OPS-004); trusts only app.drpn.ai as an origin (DAR-OPS-019); run files on EFS at
  `/moqui-deploy/runtime/datamanager` (DAR-OPS-020, already applied by the SOC 2 change set).
- **`deploy.sh` health check** reads `/` for 2xx/3xx, because `/Login` now answers 404. Deploy with docker-config
  `main` at or after `da56a54`, or the post-deploy check fails a healthy task.
- New dependency: webauthn4j-core 0.30.3 with jackson-dataformat-cbor 2.21.2 (Apache-2.0).

## Upgrade data

- `data/upgrade-data.xml` holds 90 `darpan-seed` records from the generic seed files (`v3.0.3..HEAD`):
  - `MigrationRegistrySeedData.xml`: the `NS_AUTH_ACCOUNTS` migration (DAR-BE-258).
  - `ReconciliationJobSeedData.xml`: `purge_ReconciliationExtractFiles_daily` (02:15, 7 days) and
    `run_DataRetention_daily` (03:30, paused) (DAR-BE-266, DAR-BE-271).
  - `SecuritySeedData.xml`: 66 `EntityFilter` rows, now failing closed with no active tenant, among them the new
    Shopify install-state filter (DAR-BE-275, DAR-BE-282).
  - `SourceSystemConnectorSeedDataFields.xml`: two `OMS_RETURNS_GQL` join-key descriptions (DAR-BE-257).
  - `TenantWelcomeEmailSeedData.xml`: the `DARPAN_TENANT_WELCOME` email template (DAR-BE-290).
  - `TwoFactorSeedData.xml`: four optional role policies, two enumeration types with six values, and five
    two-factor email templates (DAR-BE-264, DAR-BE-270).
- Hand-ordered parent before child (`ServiceJob` before its `ServiceJobParameter`); keep the ORDERING NOTE.
- The 3.0.3 load target is archived, byte-identical to `v3.0.3`'s, at `data/releases/3.0.3/upgrade-data.xml`.
- Load: the first start of the new image with `DARPAN_LOAD_UPGRADE_DATA` unset or `Y` loads every `darpan-seed` file;
  where the pipeline sets it to `N`, run `./gradlew :runtime:component:darpan:loadDarpanUpgradeData` once.
- New tables (two-factor, passkeys, Shopify apps and install state, retention) are created on boot from the entity
  definitions.

## Operator actions after deploy

1. Announce before deploying: web sign-ins older than 24 hours and CLI keys older than 7 days end; people sign in
   again (`darpan login` for the CLI).
2. Deploy backend and app together, with docker-config `main` at or after `da56a54`.
3. Run the `NS_AUTH_ACCOUNTS` migration (`darpan-admin --target prod migrations run`), dry run first.
4. Every super admin enrols a second factor; then make admin roles required (`update#TwoFactorPolicy`); tenant
   admins after their grace period.
5. Before any Shopify app subscribes to webhooks: a body-size cap and rate limit on `POST /apps/shopify/webhook`
   at the ALB/WAF, and the app secret in Secrets Manager (DAR-BE-273).
6. gorjana R10/R11: preview and apply `packs/changes/2026-11-17-r10-r11-line-facility.json` through /transfer, then
   compare and sync the R10 and R11 automations (DAR-BE-294).
7. Send the Malbon welcome to the six members granted on 2026-11-14 (DAR-BE-290).
8. Read-backs: a tenant user and a view-only member in the browser (DAR-BE-292); new findings carry `sourceConfigId`
   (DAR-BE-284); the first nightly extract purge (DAR-BE-271).

## Verification

- See `release-checklist.md` for the commands, counts and exit statuses.

## Deferred items

- DAR-BE-256: the backend's per-destination deliverable field (the app part ships).
- DAR-BE-275: the first install against a Darpan development store, and an app UI for installs.
- DAR-BE-266: retention periods are not set, so the job stays paused (decision 7).
- DAR-BE-265: revoking UPDATE/DELETE on the audit tables from the app's database user, and a locked copy.
- DAR-ADMIN-012: CRM, in progress and uncommitted.

## Rollback or fallback notes

- Roll back with `deploy.sh prod --ref v3.0.3` and the 4.0.3 hosting release, knowing:
  - **Passwords**: the 3.0.3 image carries the older framework, which cannot verify a password rehashed to PBKDF2
    after this deploy. Those people reset their password after a rollback.
  - **Tenant filters**: the 66 `EntityFilter` rows now fail closed with no active tenant, which 3.0.3's code was not
    written for. Reload `v3.0.3`'s `data/SecuritySeedData.xml` with the rollback so its filters match its code.
  - The new tables and the other seed rows stay and are ignored by 3.0.3.
  - Two-factor enrolments and passkeys are kept and simply not asked for by 3.0.3.
