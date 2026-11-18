# Darpan 3.2.0

Release date: 2026-11-18

## Scope note

- **A minor release on the 3.x line**: everything on `main` in every repo since 3.1.0 (deployed 2026-11-18 10:41
  IST). Aditi, 2026-11-18: "prep a hotfix", then chose 3.2.0 (minor: two API methods, new tables, a daily job) and
  "everything on main".
- Headline: sign in with a passkey alone (DAR-BE-301); two-factor can be required for everyone, with reminder emails
  (DAR-BE-297); a Darpan admin works in every tenant (DAR-BE-296); the admin CLI signs in through the browser and keeps
  its session (DAR-BE-295); every Darpan email wears the app's look (DAR-BE-298).
- Facade contract stays at **4**: 2 methods added (`begin#PasskeyFirstSignIn`, `finish#PasskeyFirstSignIn`), all
  266 existing methods preserved (`check_contract_compat.py`); `start#CliLogin` gains an optional `scope`.
- Not in this release: the uncommitted CRM work in darpan and darpan-admin-ui (DAR-ADMIN-012, in progress).

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.1.0` | `v3.2.0` |
| `drpn-ai/darpan-ui` | `v4.1.0` | `v4.2.0` |
| `drpn-ai/darpan-hotwax` | `v1.1.0` | `v1.2.0` |
| `drpn-ai/darpan-agent` | `v0.2.0` | `v0.3.0` |
| `drpn-ai/darpan-docker-config` | `v3.1.0` | `v3.2.0` (pins move for darpan, darpan-hotwax, darpan-agent) |
| `drpn-ai/shopify-darpan`, `netsuite-darpan`, `database-darpan`, `moqui-gql` | unchanged | stay at `v0.10.0`, `v0.6.0`, `v0.4.0`, `v1.1.0` |
| `drpn-ai/moqui-framework` | `master` | `master` (no commits since 3.1.0) |
| `drpn-ai/darpan-admin-ui` | (no tag) | `main`: the darpan-admin CLI and admin app |

All tags are cut from `main`.

## User-visible changes

### Signing in

- **Sign in with a passkey alone.** The sign-in page has "Sign in with a passkey": the browser offers the saved
  passkey, Touch ID or Face ID confirms, and the person is in, with no password and no code. Typing a username first
  works for a security key. Password and code stay as the fallback (DAR-BE-301).
- **Two-factor can be required for everyone.** A super admin can set one date for every person with a tenant
  membership or a Darpan role. Until then sign-in prompts; from that date a person who has not set up an app or a
  passkey is taken into setup at sign-in, not locked out (DAR-BE-297).
- **Reminder emails** go to anyone a dated requirement covers who has not set up a method: on first notice, then 7, 3
  and 1 days before, on the day, and weekly after, at most one a day. A person whose only tenants are disabled is
  not covered by the everyone requirement (DAR-BE-297).
- **Approving a CLI sign-in** names an admin request ("darpan-admin on <computer>"), warns that it carries admin
  powers for 12 hours, and shows Approve only to a super admin. An unrecognised request can only be denied
  (DAR-BE-295).

### Roles

- **A Darpan admin works in every tenant** as tenant admin, with the CLI and AI, without being added to each tenant.
  A super admin adds only the admin app (DAR-BE-296). The settings page labels the role "Darpan admin".

### Email and screens

- **Every Darpan email wears the app's look** (the reset email's shape and theme), ends with one or two user guide
  links for its subject, and carries one short light line (DAR-BE-298).
- **A run result's rule picker shows the rule's name**, not its formula (DAR-BE-299).
- **The mascot wears the active tenant** as a bowtie (DAR-UI-119).

### Audits

- **HotWax item quantity counts exchange orders**: an exchange HotWax keeps on a separate EXC- order is counted with
  its original order, as Shopify counts it (DAR-BE-300).

## Operator-visible changes

- **darpan-admin keeps its session** in `~/.config/darpan-admin/<target>.json` (owner-only) and reuses it until the
  server ends it; `darpan-admin logout` ends it. After this deploy it signs in on prod and UAT by browser approval:
  the operator types the terminal's code in the Darpan app, a super admin with two-factor on approves with a fresh
  passkey or app code, and the session lasts 12 hours. Only that admin session may call `admin.*` from a CLI; a
  tenant `darpan` CLI session still may not (DAR-BE-295).
- **Two-factor policy role `EVERYONE`** (CLI alias `everyone`); dating it needs two super admins with two-factor on.
  Policy dates are on the UTC calendar: `2026-12-02` starts at 00:00 UTC (DAR-BE-297).
- **Reminder job** `send_TwoFactorReminders_daily` runs at 15:00 UTC on the scheduler node; it does nothing while no
  policy has a date (DAR-BE-297).
- **Passkey-only sign-in** is rate-limited per address and answers an unknown username the same as a known one; its
  challenges live in `PasskeySignInChallenge`, and a random key in `AuthSecret` (row `PASSKEY_DECOY`, made on first
  use) keeps unknown usernames indistinguishable (DAR-BE-301).
- **The email line** is written by Claude Haiku at send time through darpan-agent `write#EmailSmile` when an AI key is
  configured; otherwise the template's own line is used (DAR-BE-298).
- **The tenant user role's description** names what it can do since the permissions change (DAR-BE-292 seed text).

## Upgrade data

- `data/upgrade-data.xml`: 4 records rebuilt from the seed diff `v3.1.0..HEAD` (see `upgrade-data-review.md`): the
  `DARPAN_TENANT_USER` description, the `EVERYONE` two-factor policy row, the `DARPAN_2FA_REMINDER` email template and
  the `send_TwoFactorReminders_daily` job. No parent/child ordering between them.
- New tables (`PasskeySignInChallenge`, `AuthSecret`) and the `scopeId` field on `CliDeviceCode` and `CliSession` are
  created at boot (entity add-missing); nothing to backfill.
- Load path: first start of the new image with `DARPAN_LOAD_UPGRADE_DATA` unset or `Y`; otherwise
  `loadDarpanUpgradeData` once.

## Operator actions after deploy

1. Deploy the backend before darpan-ui 4.2.0 (the passkey button calls the new methods).
2. Smoke: sign in at app.drpn.ai with a passkey only; a darpan-admin browser approval on prod; a tenant CLI key
   refused on an `admin.*` call; a Darpan admin who is not a super admin sees every tenant and is refused by the admin
   app.
3. darpan-admin-ui: add `uat` and `prod` to `DEVICE_TARGETS` (`cli/targets.ts`), then `darpan-admin logout --target
   prod` to drop the stored web session.
4. Before dating `EVERYONE`: a second super admin enrolled; the person setting it has an authenticator app (the policy
   change asks for a fresh app code); prod's not-enrolled list read for password-only service accounts (move them to
   GraphQL API keys or enrol them); the reminder copy signed off; tenant users told.
5. Re-run `verify_release_docs.py --version 3.2.0 --sdl-url https://api.drpn.ai/rest/s1/graphql/sdl`.

## Verification

- Commands and results: `release-checklist.md`.

## Deferred items

- The tenant-admin role policy still counts admin memberships in disabled tenants (only the everyone path skips
  them).
- On the CLI approval page, a super admin without two-factor sees an unusable code field after the "Turn on
  two-factor" refusal (the message is right and nothing is spent).
- Approving each admin action in the browser (so a passkey-only admin can give a fresh proof from the CLI).

## Rollback or fallback notes

- Roll back with `deploy.sh prod --ref v3.1.0` and the 4.1.0 hosting release. The new tables, the policy row, the
  template and the job stay and are ignored by 3.1.0 (the job's service does not exist there; the scheduler logs it
  and moves on). Passkeys and enrolments are kept. An `EVERYONE` date set under 3.2.0 means nothing to 3.1.0.
- darpan-admin from `main` keeps working against 3.1.0 with password sign-in (the default for prod until step 3).
