# Darpan 3.3.0

Release date: 2026-11-18 (prepared; the tag date is the cut date)

## Scope note

- A minor release on the 3.x line: everything on `main` in every repo since 3.2.1. Aditi, 2026-11-18: one 3.3.0
  carries DAR-BE-303, 304, 305, 306, 307 and 313, then DAR-BE-315 (added the same evening), plus the app work already on `main` (DAR-UI-120 to 129 and 131, and
  DAR-UI-123's backend methods).
- Headline: data that used to need a release is now edited in production, with no deploy and no restart. Email words
  and subjects (DAR-BE-303), tunables (DAR-BE-304), scheduled jobs (DAR-BE-305), retention and purge periods, per
  tenant (DAR-BE-306), mascot words (DAR-BE-313), and the agent's prompts, models, prices, texts and caps
  (DAR-BE-307). Every edit is made by a super admin in the admin app or the `darpan-admin` CLI, previewed where it
  matters, and written to the admin audit log. Every shipped default stays as it was until someone edits it.
- Facade contract stays at version 4: 33 methods added, none removed (`check_contract_compat.py` 3.2.1 -> 3.3.0). 29
  are `admin.*` methods for the admin app and CLI; 4 are `facade.*`: `get#AppContent` (DAR-BE-313) and
  `get#NotificationRoutes`, `set#NotificationRoute`, `send#ChatSpaceTest` (DAR-UI-123).
- darpan and darpan-agent ship together: the agent now reads core `DarpanSettings`, `AgentConfig` and
  `AdminAuditSupport`, and darpan's contract generator reads darpan-agent's admin services.

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.2.1` | `v3.3.0` |
| `drpn-ai/darpan-agent` | `v0.3.0` | `v0.4.0` (agent config tables, 33 settings, admin services; pairs with darpan 3.3.0) |
| `drpn-ai/darpan-ui` | `v4.2.0` | `v4.3.0` |
| `drpn-ai/darpan-docker-config` | `v3.2.1` | `v3.3.0` (`DARPAN_REF` v3.3.0 and `DARPAN_AGENT_REF` v0.4.0 move together) |
| `drpn-ai/darpan-hotwax` | `v1.2.0` | unchanged, no commits |
| `drpn-ai/shopify-darpan`, `netsuite-darpan`, `database-darpan`, `moqui-gql` | `v0.10.0`, `v0.6.0`, `v0.4.0`, `v1.1.0` | unchanged, no commits |
| `drpn-ai/darpan-admin-ui` | no tags | `main`: the admin app and CLI |
| `drpn-ai/darpan-docs`, `drpn-ai/darpan-developers` | no tags | `main` (Mintlify publishes on push) |

Every tag is cut from `main`; the exact commits are in `release-checklist.md`.

## User-visible changes

For tenant users:

- Sign-in asks for the username first, then the passkey (for a username this browser has signed in with a passkey) or
  the password. The username field offers saved passkeys in the browser's autofill (DAR-UI-120).
- User Settings starts with the profile, then settings in pairs; Tenant Settings is rows (Chat, Time, Audit, AI)
  (DAR-UI-121, DAR-UI-122).
- Notifications has one grid of what posts to which chat space, and one Add space flow; a space can be sent a test
  message (DAR-UI-123).
- Scripts is a list, and each script has a page of its own; Config history shows the current version, then earlier
  versions by day (DAR-UI-124, DAR-UI-125).
- Audit settings and the Ruleset manager share the settings-page header; filters and rules read as rows and
  conclusions open in popups. Edit buttons are icons everywhere (DAR-UI-126 to 129).
- The mascot can be dragged anywhere for the tab, and a reload sends her home (DAR-UI-131).
- The mascot's words come from the server, so a fix to a hint or a glossary entry no longer waits for a release. If
  the read fails, the app uses its built-in words (DAR-BE-313).
- The plain-text version of every Darpan email now carries every sentence and link (DAR-BE-315).
- Every email reads exactly as before until a super admin edits it (DAR-BE-303).

## Operator-visible changes

### Email copy is data (DAR-BE-303)

- All 12 Darpan emails read their words from `EmailCopyBlock` rows; the shipped default sits beside each edit and a
  reset restores it. Subjects are editable too, including the two-factor reminder and the audit run emails.
- The admin app has an Emails page with a live preview and reset; the CLI has `emails`, `email`, `email set`,
  `email subject` and `email reset`.
- An edit may use only the variables its block receives, carries no markup and no web or email address, and never
  stops a send: a block that cannot render uses its default. Layout and theme stay in code. A seed load keeps edits.
- The plain-text part of every email is built from the same copy blocks, so it carries every sentence and link the
  HTML part does, edits included (DAR-BE-315). This was the gate before `policy set everyone 2026-12-02`.

### Settings store (DAR-BE-304, DAR-BE-306, DAR-BE-307)

- Tunables are `DarpanSetting` rows, read as row, then `-D` property, then the shipped default, with no restart and a
  30 second snapshot that expires on commit. darpan carries 25 settings (run admission caps and heap hold, waiting
  limit, retries and backoff, the window ceiling, extract switches, six run-health thresholds including the stuck-run
  reaper, the two-factor reminder days and repeat, the four retention periods, the retention batch size and the two
  purge periods); darpan-agent carries 33 under the areas "Agent · Router", "Agent · Investigator", "Agent · Author",
  "Agent · Writers" and "Agent · Gateway".
- Admin app Settings page and CLI `settings`, `setting`, `setting set`, `setting reset`. Super admin only, audited.
  An edit that a later release puts out of range is shown and can be cleared.

### Scheduled jobs (DAR-BE-305)

- The Schedule page has a Jobs section and the CLI has `jobs` and `job ...`: edit a cron (Quartz syntax, must fire
  within 400 days and keep the job's minimum gap), pause, resume and reset. A cron change never fires off-cycle;
  resuming a job that deletes data needs a fresh preview. CLI writes with `--yes` need the exact job name.
- Job seeds carry defaults only, applied to jobs nobody edited, so a deploy never undoes a cron, pause or parameter
  edit, and a changed default still reaches unedited jobs. A seed load never resumes a job that deletes data.
- The 10 Darpan jobs are editable; framework jobs stay read-only.

### Retention and purge periods (DAR-BE-306)

- Retention periods are settings (an unset period keeps the data forever) with a per-tenant override (days, forever
  or inherit). They are edited only on the Schedule page Retention section or with `darpan-admin retention`. An
  invalid stored value keeps rows. Shortening a period while retention runs needs a fresh preview.
- The generated-output and extract purge days are settings (15 and 7 days, as before). The job editor refuses the old
  `retentionDays` parameter, and the purge output folder is not editable as a job parameter.
- Migration `RETIRED_JOB_PARAMETERS` moves each purge job's edited `retentionDays` parameter into its setting and
  deletes it, and deletes the stuck-run reaper job's retired `staleMinutes` parameter. Until it runs, a live purge
  `retentionDays` row still applies, at today's values.

### Mascot words (DAR-BE-313)

- Mascot hints, glossary entries and patterns are rows behind the app's built-in bundle (500 entries, each hint with
  a stable id). The app reads edits and admin entries once per tab. A hidden term stays silent.
- Admin app "Mascot words" page and CLI: create, edit, hide, reset and delete admin and retired entries, dry run
  first, audited with full text. Aliases are plain labels; patterns carry one or two `*`, and a pattern with no `*`
  is refused with "use an alias".
- darpan-ui's generator writes the seed; `npm run mascot:seed:check` catches a stale seed.

### Agent configuration (DAR-BE-307)

- The agent's system prompts (with version history), role models, model prices, reply strings, notices and tool
  descriptions are edited on the admin app Agent pages and with `darpan-admin agent config ...`. Shipped defaults stay
  in the jar and an edit is a row, so a reset deletes the row. Edits apply within 30 seconds; a turn runs on one
  snapshot of config and settings.
- An unpriced model refuses the turn instead of spending untracked, so a model upgrade needs its price entered first,
  with no release. A model change re-runs the provider check on the next turn. A failed config load keeps the last
  good config.
- The per-tenant daily dollar cap can be overridden per tenant, inside the global cap's range.
- The author model JVM properties are gone: every role's model comes from the role-model rows.
- Eight admin methods (`admin.AgentConfigAdminServices.*`), super admin only, admin app only in the CLI allowlist,
  no fresh two-factor code, audited. A prompt's audit row names its version and length, not its text.

## Upgrade data

- `data/upgrade-data.xml` (and this pack's copy) carries 1,948 records generated with `release_preflight.py
  generate` from `v3.2.1..HEAD` in a clean worktree: the mascot seed (500 entries, 54 routes, 1,289 anchors and the
  `CURRENT` seed mark), 91 email copy blocks, the `RETIRED_JOB_PARAMETERS` migration row, 10 default-only job rows,
  one job parameter default and the `apply#JobDefaults` call last. See `upgrade-data-review.md`.
- Every one of these records is also in a `darpan-seed` file that loads on every boot, so prod and UAT get them on
  the first start of the new image with `DARPAN_LOAD_UPGRADE_DATA` unset or `Y`. The upgrade file is for an
  environment that does not load `darpan-seed` on boot: there, run `./gradlew loadDarpanUpgradeData` once.
- New tables and columns are created at boot (entity add-missing): `EmailCopyBlock`, `DarpanSetting`,
  `TenantRetentionPeriod`, `AppContentEntry`, `AppContentSeed`, `AppRoute`, `AppRouteAnchor`, the agent's
  `AgentPrompt`, `AgentPromptVersion`, `AgentRoleConfig`, `AgentModelPrice`, `AgentTenantCap` and `AgentText`, and the
  new `ServiceJob` and `ServiceJobParameter` columns. Nothing to backfill.
- Never replay a pre-3.3 release pack: older packs state the live job columns that now hold defaults.

## Operator actions after deploy

1. Deploy order: backend (darpan with darpan-agent, one image) first, then darpan-ui, then darpan-admin-ui.
2. Boot checks: the boot log's SECA count rose by one (the email subject SECA); `AppContentSeed` `CURRENT` is
   `87a4819f84fdffa7` with 500 `AppContentEntry` rows.
3. `darpan-admin --target prod settings` lists 58 keys (25 darpan, 33 under "Agent · *" in the order Router,
   Investigator, Author, Writers, Gateway), all DEFAULT. Any PROPERTY source is a surprise to explain.
4. Run migration `RETIRED_JOB_PARAMETERS`: dry run, then real. On prod it deletes 2 to 3 rows, including the ignored
   `staleMinutes=120` parameter, and writes no setting.
5. The Schedule page lists the 10 Darpan jobs as editable and framework jobs read-only.
6. Time a prod retention dry run before resuming `run_DataRetention_daily` (it runs inside the update transaction).
   Then set retention periods on the Schedule page and resume the job after its preview.
7. UAT smoke for emails: Emails lists 12 and each previews; edit, save and reset a block; edit a subject, restart,
   and a signed-out password reset shows it; AdminAuditLog rows are written.
8. Agent smoke: `darpan-admin --target prod agent config` shows the defaults; edit then reset one prompt, one model (a
   priced one; the next turn's provider check passes), one reply, one tenant cap and one agent setting, and confirm
   the next turn uses each; AdminAuditLog shows each write.
9. Mascot browser smoke on app.drpn.ai: edit a seed body, hide a v-explain term (it stays silent), create an admin
   hint and an alias, hover and idle, reset and delete.
10. App browser checks on app.drpn.ai: passkey autofill signs in and a password-only user sees username, password,
    then code (DAR-UI-120); User Settings, Tenant Settings, Config history and Audit settings as a Darpan admin, a
    tenant admin and a non-admin (DAR-UI-121, 122, 125, 127); Notifications with a real Slack connection and a Google
    Chat test send (DAR-UI-123); the Ruleset manager (DAR-UI-128); drag the mascot in light and dark, a reload sends
    her home, touch drag at phone width (DAR-UI-131).
11. Before `policy set everyone 2026-12-02` (DAR-BE-297): send yourself a two-factor reminder and a password reset
    and read both parts (DAR-BE-315), then edit the two-factor reminder line on the Emails page and preview it.
12. Re-run `verify_release_docs.py --version 3.3.0 --sdl-url https://api.drpn.ai/rest/s1/graphql/sdl`.

## Verification

- Commands and results: `release-checklist.md`.

## Deferred items

- DAR-BE-303: the web and email address check still misses `_` beside a host, non-Latin domains and Unicode dots
  (admin-only, audited); subject length caps are enforced on save only; the chat `*bold*` and a leading blank line in
  the digest; the agent's email briefs still quote the email body text (AG-1).
- DAR-BE-304: auth and session lifetimes, and the Low and Medium tunables, are not settings yet.
- DAR-BE-305/306: the moqui-gql `purge_GqlQueryLog` and `GqlAnomalyScanJob` crons are still rewritten each deploy; the
  resume preview in the UI is wired to retention only.
- DAR-BE-307: the audit detail for a full tool-text edit sits near the `AdminAuditLog.detailText` limit.
- DAR-BE-313: the admin list reads every row and is not paged.
- Docs: the darpan-docs guides for the reworked settings pages (DAR-UI-121 to 128) still name the old labels; see
  `docs-impact.md`.

## Rollback or fallback notes

- Roll back with `deploy.sh prod --ref v3.2.1` and the previous hosting releases. The new tables and columns stay and
  3.2.1 ignores them. Edits made under 3.3.0 (copy, settings, job edits, retention, mascot, agent config) mean nothing
  to 3.2.1, which uses its code defaults.
- If the migration already deleted the purge jobs' `retentionDays` rows, 3.2.1 uses its code defaults (15 and 7
  days). 3.2.1's job seeds write their own cron and pause state again on its first boot, over any job edit made under
  3.3.0 (`run_DataRetention_daily` goes back to paused).
- darpan-admin-ui from `main` against 3.2.1 shows the new pages as errors; use the previous admin build.
