# Technical Changelog For Darpan 3.1.0

This file is the engineer-facing companion to the user release notes. Keep it curated and diffable.

## Versioning decision

- **Minor.** 23 facade methods added and none removed or changed incompatibly: `check_contract_compat.py` reports 243
  base methods preserved, `x-darpan-contract-version` stays 4. New entities and fields are additive.
- Every component re-cuts at a minor version: each has commits of its own since its 3.0.3-era tag, and DAR-BE-262
  and DAR-BE-292 touch the connectors together.
- moqui-framework stays on `master` (prod builds it unpinned); its PBKDF2 hashing and password-only route closure
  (DAR-BE-264, 268, 276) ship with this image.

## Source ranges

| Repo | Range | Commits |
| --- | --- | --- |
| darpan | `v3.0.3..v3.1.0` | 76 |
| darpan-ui | `v4.0.3..v4.1.0` | 24 |
| shopify-darpan | `v0.9.1..v0.10.0` | 21 |
| darpan-docker-config | `v3.0.3..v3.1.0` | 8 |
| darpan-hotwax | `v1.0.1..v1.1.0` | 4 |
| darpan-agent | `v0.1.2..v0.2.0` | 3 |
| netsuite-darpan | `v0.5.1..v0.6.0` | 2 |
| database-darpan | `v0.3.0..v0.4.0` | 2 |
| moqui-gql | `v1.0.0..v1.1.0` | 2 |

Commit counts exclude each repo's release-metadata commit.

## Backend

### Added

- Two-factor sign-in (DAR-BE-264): `darpan.auth` `TwoFactorFactor` (encrypted TOTP secret), `TwoFactorRecoveryCode`
  (salted SHA-256), `SignInChallenge` (token hash), `TwoFactorRolePolicy`; `login#Session` answers a right password
  with `twoFactorRequired` / `twoFactorSetupRequired` (and `passkeysAvailable`); `verify#SignInCode`,
  `begin/confirm#TwoFactorSetup`, `create#RecoveryCodes`, `disable#TwoFactor`, `get#TwoFactorStatus`; CLI approval
  needs a fresh code; `update#TwoFactorPolicy` and `reset#TwoFactor` need the super admin's fresh proof; five notice
  emails.
- Passkeys and security keys (DAR-BE-270): webauthn4j-core 0.30.3; `begin/finish#PasskeyRegistration`,
  `begin#PasskeySignIn`, `begin#PasskeyProof`, `rename#Passkey`, `delete#Passkey`; a credential is stored as
  webauthn4j's attested credential data; relying party from `DARPAN_WEBAUTHN_RP_ID` / `DARPAN_WEBAUTHN_ORIGINS`, else
  `DARPAN_APP_BASE_URL`, else `webapp_allow_origins`.
- `AuthPrimitives`: one shared random token, SHA-256 hex, base64url, client-IP literal check and refusal floor.
- Admin: `add/remove#DarpanAdmin` and `darpanRoles` (DAR-ADMIN-011); `send#TenantWelcome` (DAR-BE-290);
  `dryRun#Retention` (DAR-BE-266); `rotate#EncryptedFields` (DAR-OPS-019); `get#TwoFactorPolicies`.
- Retention per table, off until a period is set; `run_DataRetention_daily` seeded paused (DAR-BE-266).
- `purge#ExtractFiles` and `purge_ReconciliationExtractFiles_daily`: raw extracts deleted after 7 days (DAR-BE-271).
- `AuditFindingRecord.sourceConfigId` (DAR-BE-284).
- `GraphQlDocumentCheck.fieldPaths`: every selected field as a path of real names, with arguments and variable
  defaults (DAR-BE-260).
- shopify-darpan: `ShopifyApp` (client secret encrypted), `begin#ShopifyInstall` with a single-use state, expiring
  offline tokens refreshed under a row lock, `list/save#ShopifyApp`; webhook receiver `POST /apps/shopify/webhook`
  (HMAC against the installing app's secret, store-domain match, per-topic body shape); privacy services
  `report#CustomerData`, `redact#Customer`, `redact#Shop` (DAR-BE-272, 273, 275).
- darpan-agent: `propose_audit_setup` names the category codes and weights, so the agent can add statements
  (DAR-BE-286).
- moqui-gql: GraphQL API key maximum lifetime `gql.apiKey.maxDays` (default 90), owner, audit rows (DAR-BE-267).

### Changed

- Sessions: web sign-in ends after 60 idle minutes, at most 24 hours after sign-in; every CLI sign-in lasts 7 days;
  a key issued before `UserAccount.sessionsEndedDate` is refused (DAR-BE-261, DAR-BE-264).
- Passwords: minimum 12 (Darpan conf override); the refused-login decoy spends the framework's PBKDF2 hash
  (DAR-BE-268).
- The six sign-in and setup steps (`verify#SignInCode`, `begin/confirm#TwoFactorSetup`, `begin#PasskeySignIn`,
  `begin/finish#PasskeyRegistration`) answer a refusal with a machine-readable `reason`; other methods still refuse
  with a JSON-RPC error.
- Tenant users get run access for audits, saved runs, comparisons, reconciliations and connection saves/deletes;
  only editors and tenant admins reach the agent (DAR-BE-292; darpan, hotwax, shopify, database, agent).
- `OutboundHttpClient` streams, keeps one shared guard, checks the connected address and keeps no cookies; HotWax
  and Shopify send through it (DAR-BE-262).
- The audit run update's report links and the digest link carry `tenantId` (DAR-BE-293).
- A finding's fix and passes-when text fill `{field}` from the finding's own records at the lowest rank (DAR-BE-294).
- HotWax ship-group facilities read returns `shipmentMethodTypeId`; netsuite-darpan puts an approved open line's
  own ship group, facility and ship method on the record (DAR-BE-294).
- Shopify extract and catalog drop order/customer email and shipping address; the transport refuses customer PII
  fields (DAR-BE-260).
- A check with no templates gets value headlines and key-named record cards (DAR-BE-291).
- An agent's new check id never collides with another audit's (DAR-BE-287).

### Fixed

- `close#ResolvedFindings` matches in memory instead of one IN with every explained key; a 107,431-parameter IN
  failed and rolled back the close (DAR-BE-294).
- Concurrent rule-set builds used one Drools release id and took each other's rules (DAR-BE-289).
- A NetSuite endpoint can no longer move to another NetSuite account; the account is kept on the auth config
  (`NS_AUTH_ACCOUNTS` migration) (DAR-BE-258). Stored Shopify/NetSuite secrets are dropped when the shop or token URL
  changes (DAR-BE-255). Archived-run error text and the returns join-key seed text (DAR-BE-257).
- Removing a factor no longer signs the person out; recovery codes made only when no factor was on before; a setup
  challenge is spent once a factor exists (DAR-BE-264/270 review fixes).

### Security

- Framework password-only ways in (HTTP Basic, `authUsername`/`authPassword` anywhere in a parsed body) sign out
  and answer 401 (DAR-BE-264, `PasswordOnlyRouteFence` DAR-BE-276).
- Password-less sign-ins start a new servlet session; `login#Session` fails closed.
- The per-IP password-reset limit fails closed; client IPs are kept only as IP literals (DAR-BE-028/264).
- Tenant isolation: entity filter fails closed with no active tenant; JSON schemas, automation executions and
  generated outputs resolve per tenant; an orphaned result file is not shown to another tenant; GraphQL store scope
  narrows the tenant pin (DAR-BE-269, 279, 280, 281, 282).
- Outbound HTTP checks the address actually connected (DAR-BE-262).
- Admin audit trail covers every admin mutation and download (DAR-BE-265).
- disableAuthz ratchet counts darpan-agent and moqui-gql (DAR-BE-263, DAR-BE-267); shopify-darpan Groovy baseline
  1 -> 2 (`ShopifyInstallSupport.withAuthzLifted`, DAR-BE-275).
- gitleaks secret scanning on every push in every component repo (DAR-BE-197).

## UI

### Added

- Two-factor sign-in: code and recovery-code steps, required first setup, grace prompt, User Settings section, CLI
  approval code; passkey sign-in and enrolment; one app-code-or-passkey proof (`useTwoFactorProof`) (DAR-BE-264,
  DAR-BE-270).

### Changed

- Tenant users see connection and audit run controls (`canManageConnections`) (DAR-BE-292).
- Two-factor screens switch on the backend's `reason`, never its wording; dates in the display time zone.
- Agent drafts: new statements numbered in approval order, a new statement's card shows its sentence (DAR-UI-117).
- New passwords need 12 characters (DAR-BE-268).
- CSP `connect-src` drops `https://*.hotwax.io` for uat and prod (DAR-OPS-019).

### Fixed

- Previous Week is seven days (DAR-UI-114); the dead repeated-key card is gone (DAR-UI-113); role labels and the
  stale Grace glossary entry (DAR-UI-115); Audit settings warns about an unusable chat space (DAR-BE-256).
- An open order's detail opens its open line (DAR-UI-118).
- Two-factor errors are announced by screen readers; a call in flight across a key swap is retried on the new key.
- CI green, install scripts off, Actions pinned by SHA; the AuditReportPage Resolve flake (DAR-UI-116).

## Data and configuration

- `data/upgrade-data.xml`: 90 records (see `upgrade-data-review.md`), hand-ordered parent before child.
- New env/config: `DARPAN_TOTP_ISSUER`, `DARPAN_WEBAUTHN_RP_ID`, `DARPAN_WEBAUTHN_ORIGINS`, `gql.apiKey.maxDays`,
  password min length 12, session idle/cap.
- docker-config: prod pins move to the 3.1.0 tags; non-root uid/gid 1000 (DAR-OPS-002); digest-pinned JDK stage
  (DAR-OPS-004); prod trusts only app.drpn.ai (DAR-OPS-019); EFS run-file path (DAR-OPS-020); `deploy.sh` checks `/`
  for 2xx/3xx (`/Login` is 404 now); SOC 2 infrastructure templates (DAR-OPS-018, already applied).

## Validation and rollout notes

- Commands and results: `release-checklist.md`.
- Deploy backend (all components) and app together. Rollback caveats (PBKDF2, tenant filter rows):
  `release-notes.md`.

## References

- https://github.com/drpn-ai/darpan/compare/v3.0.3...v3.1.0
- https://github.com/drpn-ai/darpan-ui/compare/v4.0.3...v4.1.0
- https://github.com/drpn-ai/shopify-darpan/compare/v0.9.1...v0.10.0
- https://github.com/drpn-ai/darpan-hotwax/compare/v1.0.1...v1.1.0
- https://github.com/drpn-ai/netsuite-darpan/compare/v0.5.1...v0.6.0
- https://github.com/drpn-ai/database-darpan/compare/v0.3.0...v0.4.0
- https://github.com/drpn-ai/darpan-agent/compare/v0.1.2...v0.2.0
- https://github.com/drpn-ai/moqui-gql/compare/v1.0.0...v1.1.0
- https://github.com/drpn-ai/darpan-docker-config/compare/v3.0.3...v3.1.0
