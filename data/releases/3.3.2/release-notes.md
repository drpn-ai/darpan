# Darpan 3.3.2

Release date: 2026-11-19

## Scope note

- **A hotfix on the 3.3 line** (Aditi, 2026-11-19: "update the email time to 7pm IST for SM and Prod", then "cut a
  hotfix and deploy"): `v3.3.1` plus three fixes already on `main`: DAR-BE-319, DAR-BE-321, DAR-BE-322. `main`'s
  DAR-UI-132 and DAR-BE-320 (entity, contract and seed changes) stay out, for 3.4. Publicly it folds into the 3.3 notes
  (one `updates.mdx` section and one GitHub release page for the line).
- Facade contract stays at **4**: no method added or removed, no parameter changed.

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.3.1` | `v3.3.2` (tag off `main`: `v3.3.1` + the three fixes; on `main` as 2469bf5c, 69388bb2, 29cde809) |
| `drpn-ai/darpan-docker-config` | `v3.3.1` | `v3.3.2` (only the darpan pin moves); `sm-v3.3.2` for Steve Madden's Jenkins |
| darpan-ui, darpan-hotwax, darpan-agent, shopify-darpan, netsuite-darpan, database-darpan, moqui-gql | unchanged | 3.3.1 pins |

## User-visible changes

- The admin app's Schedule page lists the jobs in production again, and a job's time, parameters and pause state can be
  changed there and from the admin CLI (`job`, `jobs`, `job set`). In 3.3.0 and 3.3.1 every one of these failed on
  production databases with "Error going to absolute row number 1".
- A once-a-day job's time can be changed on a server whose clock keeps daylight saving. Before, the check counted the
  23-hour spring-forward day as a gap shorter than a day and refused every new time.
- A migration disabled in production stays disabled after a deploy.

## Operator-visible changes

- Two-factor reminders (`send_TwoFactorReminders_daily`) can now be moved to 7pm IST: `0 30 13 * * ?` on a UTC server
  (prod); on a server in a daylight-saving zone (sm-darpan) the time is in that zone, so it moves by an hour against IST
  twice a year.

## Upgrade data

- `data/upgrade-data.xml` lists the 12 migration registry rows, changed only by no longer stating `enabled="Y"`. No load
  is needed: loading them leaves each row's enabled flag as it is, and the boot seed carries the same rows.

## Operator actions after deploy

1. Smoke: the backend healthy; boot data loaded without skipped files.
2. Schedule: `darpan-admin --target <t> jobs` lists every job with its last run.
3. Prod: TENANT_NOTIF_SETTINGS, UNDECRYPTABLE_HOOKS, AUTOMATION_FILTERS, RULE_TENANT_STAMPS and ENDPOINT_ACCESS still
   show DISABLED (`darpan-admin --target prod migrations`).
4. Move `send_TwoFactorReminders_daily` to 7pm IST on prod and sm-darpan (`job set ... --cron`).

## Verification

- Commands and results: `release-checklist.md`.

## Deferred items

- None.

## Rollback or fallback notes

- Roll back with the 3.3.1 image: the Schedule list and job edits fail again in production, a daily job cannot be
  re-timed on a daylight-saving clock, and a deploy re-enables disabled migrations (they show pending; migrations never
  run on their own). A job time already saved stays saved.
