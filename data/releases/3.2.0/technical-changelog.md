# Technical Changelog For Darpan 3.2.0

This file is the engineer-facing companion to the user release notes. Keep it curated and diffable.

## Versioning decision

- **Minor.** 2 facade methods added (`begin#PasskeyFirstSignIn`, `finish#PasskeyFirstSignIn`) and an optional
  in-parameter (`start#CliLogin.scope`); `check_contract_compat.py` reports all 266 base methods preserved and
  `x-darpan-contract-version` stays 4. New entities and fields are additive. Aditi asked for a hotfix and chose 3.2.0
  once the additions were named.
- darpan-hotwax and darpan-agent re-cut at a minor version (each has a commit of its own); shopify-darpan,
  netsuite-darpan, database-darpan and moqui-gql have none and keep their tags; moqui-framework `master` is unchanged.

## Source ranges

| Repo | Range | Commits |
| --- | --- | --- |
| darpan | `v3.1.0..v3.2.0` | 25 |
| darpan-ui | `v4.1.0..v4.2.0` | 10 |
| darpan-hotwax | `v1.1.0..v1.2.0` | 1 |
| darpan-agent | `v0.2.0..v0.3.0` | 1 |
| darpan-docker-config | `v3.1.0..v3.2.0` | 0 |
| darpan-admin-ui | `main` (no tags) | 10 since 3.1.0 for this work |

Commit counts exclude each repo's release-metadata commit.

## Backend

### Added

- Passkey-only sign-in (DAR-BE-301): anonymous `begin#PasskeyFirstSignIn {username?}` / `finish#PasskeyFirstSignIn
  {challengeToken, passkeyAssertion}`; entity `PasskeySignInChallenge` (token hash, WebAuthn challenge, username hint,
  300 s, single use, index on `expiresDate`); 10 begins a minute per address; a refusal floor held after short write
  transactions; an unknown or passkey-less username gets one decoy descriptor (HMAC of the username under a random
  key in `AuthSecret` row `PASSKEY_DECOY`, created race-safely on first use); the owner comes from the verified
  credential; a forced or expired password answers `PASSWORD_CHANGE_REQUIRED`; success is the same new-session
  sign-in as `verify#SignInCode` plus TwoFactorEvent `PASSKEY_FIRST_SIGN_IN`.
- Admin-scope CLI sign-in (DAR-BE-295): `CliDeviceCode.scopeId` / `CliSession.scopeId` (TENANT | ADMIN);
  `start#CliLogin.scope`; `get#CliLoginRequest.scope`; only a super admin with two-factor on approves an ADMIN request
  (fresh passkey or app proof); `CliAccess.ADMIN_SIGN_IN_HOURS = 12`; `RpcMethodFence` lets an ADMIN-scope session
  call `admin.*` (the `DARPAN_ADMIN_API` artifact authz still decides); `CliAuthSupport.isAdminCliSession`.
- Two-factor policy role `EVERYONE` (DAR-BE-297): `TwoFactorPolicy.coveredPeople` / per-request `coveredAmong`
  (in-force tenant membership in an enabled tenant, or a Darpan-wide role); the two-enrolled-super-admins guard
  covers it.
- Reminder job (DAR-BE-297): `auth.TwoFactorServices.send#TwoFactorReminders` (not remote) via ServiceJob
  `send_TwoFactorReminders_daily` (`0 0 15 * * ?`); `TwoFactorPolicy.reminderDue` (first sight, 7/3/1/0 days, weekly
  after, once a day, UTC calendar); template `DARPAN_2FA_REMINDER` (`screen/email/TwoFactorReminder.xml`); event
  `REMINDER_SENT`; early return when no policy has a date.
- `EmailFrame` / `EmailSmile` (DAR-BE-298): every notice email on the app's theme with one or two guide links and one
  line written by darpan-agent `agent.AgentWriterServices.write#EmailSmile` when defined, checked (one line, <= 80
  chars, no digits, no address), else the template's line.

### Changed

- Darpan admin role (DAR-BE-296): `TenantAccessSupport.DARPAN_ADMIN_GROUP_IDS` = ADMIN, DARPAN_SUPER_ADMIN,
  DARPAN_ADMIN decides tenant access, tenant-admin rights, the CLI without a grant and AI
  (`AgentAccess.isDarpanAdminUser`); `SUPER_ADMIN_GROUP_IDS` keeps the admin app, `admin.*` and system-wide jobs.
  Active-tenant permission groups for a Darpan admin: `[DARPAN_ADMIN, DARPAN_TENANT_ADMIN, DARPAN_TENANT_USER]`.
- A temporary lockout (disabled with a disabled time) never lifts the two-factor requirement; only permanently
  disabled accounts leave the policy list (DAR-BE-297).
- An expired admin CLI session answers "This darpan-admin session has expired. Run the command again to sign in."
- `get#GeneratedOutputDifferences` names each rule by its name; a named rule's picker entry is its name alone
  (DAR-BE-299).
- darpan-hotwax: item quantity counts the original order's EXC- exchange orders through the reverse EXCHANGE
  association (DAR-BE-300).
- `DARPAN_TENANT_USER` description (DAR-BE-292 seed text).

## UI

### Added

- "Sign in with a passkey" on the sign-in page (DAR-BE-301): username optional; cancel, refusal and
  `PASSWORD_CHANGE_REQUIRED` handling; success through the same sign-in handler as `verify#SignInCode`.

### Changed

- CLI approval page (DAR-BE-295): "darpan-admin on <host>" and the 12-hour warning before Approve; an unrecognised
  scope can only be denied; Approve hidden for anyone who is not a super admin; the typed code is never read from the
  link.
- Settings role label: Super admin, then Darpan admin, then tenant roles (DAR-BE-296).
- Rule picker shows the rule's name (DAR-BE-299); the mascot's tenant bowtie (DAR-UI-119).

## darpan-admin-ui (CLI and admin app, `main`)

- darpan-admin keeps its session in `~/.config/darpan-admin/<target>.json` (0600), retries every call once after a
  stale session with one shared sign-in, never resends a spent code, opens only https approval links, `logout`
  (DAR-BE-295); browser approval on `DEVICE_TARGETS` (none until this deploy).
- `security two-factor policy set|clear everyone`; Security page "Everyone" row; the board shows the policy's stored
  (UTC) day (DAR-BE-297).

## Data and configuration

- `data/upgrade-data.xml`: 4 records (see `upgrade-data-review.md`).
- New entities: `PasskeySignInChallenge`, `AuthSecret`; new fields `CliDeviceCode.scopeId`, `CliSession.scopeId`.
- docker-config: `DARPAN_REF` v3.2.0, `DARPAN_HOTWAX_REF` v1.2.0, `DARPAN_AGENT_REF` v0.3.0.

## Validation and rollout notes

- Commands and results: `release-checklist.md`.
- Deploy the backend before darpan-ui; rollback notes in `release-notes.md`.

## References

- https://github.com/drpn-ai/darpan/compare/v3.1.0...v3.2.0
- https://github.com/drpn-ai/darpan-ui/compare/v4.1.0...v4.2.0
- https://github.com/drpn-ai/darpan-hotwax/compare/v1.1.0...v1.2.0
- https://github.com/drpn-ai/darpan-agent/compare/v0.2.0...v0.3.0
- https://github.com/drpn-ai/darpan-docker-config/compare/v3.1.0...v3.2.0
