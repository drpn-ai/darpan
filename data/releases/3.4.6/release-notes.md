# Darpan 3.4.6

Release date: 2026-11-25

## Scope note

- **A backend and app hotfix on the 3.4 line** (Aditi, 2026-11-25 16:33 IST: "cut a hotfix and deploy backend and UI";
  16:36 IST: "cut now"). It carries everything on darpan `main` after `v3.4.5`, darpan-hotwax `main` after `v1.3.1`,
  shopify-darpan `main` after `v0.11.0`, and darpan-ui `main` after `v4.4.6`. Publicly it folds into the 3.4 notes (one
  `updates.mdx` section for the line).
- **Upgrade data**: 37 records (`data/upgrade-data.xml`; loaded on boot as `darpan-seed`): the run-error notice and
  daily error email templates with their copy, the audit run update's new did-not-run and Unexplained copy blocks, and
  two scheduled jobs. New entities and execution fields arrive by add-missing; no migration.
- Facade contract stays at **5**. GraphQL schema unchanged (moqui-gql stays `v1.1.1`).

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.4.5` | `v3.4.6` |
| `drpn-ai/darpan-hotwax` | `v1.3.1` | `v1.3.2` (DAR-BE-356) |
| `drpn-ai/shopify-darpan` | `v0.11.0` | `v0.11.1` (DAR-BE-359) |
| `drpn-ai/darpan-ui` | `v4.4.6` | `v4.4.7` (DAR-BE-360 UI half, DAR-UI-143) |
| `drpn-ai/darpan-docker-config` | `v3.4.5` | `v3.4.6` (DARPAN_REF, DARPAN_HOTWAX_REF, SHOPIFY_DARPAN_REF move) |
| darpan-cli, darpan-agent, moqui-gql, database-darpan, netsuite-darpan, moqui-rules, moqui-jexl | unchanged | unchanged |

The moqui-framework fork's `master` (the image builds `MOQUI_FRAMEWORK_REF=master`) has no commit after `bfad6aa`,
the commit 3.4.4 and 3.4.5 were built from.

## User-visible changes

- **Run failures go to Darpan, not to the client's success channel** (DAR-BE-015). A run that errors, or whose side
  read or kept nothing, records its checks as did not run. Darpan posts one deduplicated error card to its own error
  space (`errors.chat.webhookUrl`); the client's channel gets one neutral line a day ("did not run this morning; Darpan
  has been alerted"). Every morning Darpan emails its own staff (`errors.email.to`) a digest of run errors, checks with
  no successful scheduled run (the watchdog) and ERROR log lines grouped by fingerprint. Both are off until the
  settings are set. New STRING settings mask secret values.
- **An Unexplained CSV after each audit run** (DAR-BE-358): Darpan emails `audit.unexplained.email.to` a CSV of every
  Unexplained finding. Darpan only; off until set.
- **An audit's re-check reads orders that changed since its last read** (DAR-BE-356, darpan-hotwax `v1.3.2`). The HotWax
  read windowed on order date, so a "completed" check never saw an order completed days after it was placed.
- **Every audit finding's order and refund records say their payment status** (DAR-BE-360, app `v4.4.7`): the basics
  row reads "<status> · <payment status>" per system.
- **Return and exchange findings show the Shopify order's status, total and day** (DAR-BE-359, shopify-darpan
  `v0.11.1`); a Shopify return's day is its `closedAt` when it has no other.
- A gift-card payment given back reads the Shopify total, and a voided authorization counts as never paid (DAR-BE-357).
- A side's Keep reads a bare key off each record, as the plan gate does (DAR-BE-355).
- App: an audit finding's row and its open detail agree while a play runs (DAR-UI-143).

## Operator-visible changes

- New entities `darpan.ops.RunErrorNotice`, `ErrorLogEntry`, `ErrorDigestSend` and execution fields (`file1ReadCount`,
  `file2ReadCount`, `noDataKind`, `triggerType`) arrive by add-missing on boot; no migration.
- New jobs `send_ErrorDigest_hourly` (sends once a day at `errors.digest.hourUtc`) and `flush_ErrorLog_5m`.
- New settings (all optional, unset = off): `errors.email.to`, `errors.chat.webhookUrl`, `audit.unexplained.email.to`
  and the other `errors.*` keys in `settings-catalog.json`.

## Upgrade data

- 37 records in `data/upgrade-data.xml` (type `darpan-seed`, loaded on boot): see `release-checklist.md`.

## Verification

- See `release-checklist.md` (15 backend classes / 202 tests, UI 84 tests, type-check, lint, browser look).

## Deferred items

- None.

## Rollback or fallback notes

- Roll back by redeploying the `v3.4.5` image (`deploy.sh --ref v3.4.5`) and app `v4.4.6`. The added entities and
  columns are additive and unused by 3.4.5; the loaded copy blocks and jobs are inert under 3.4.5 (the jobs' services do
  not exist there: pause `send_ErrorDigest_hourly` and `flush_ErrorLog_5m` if rolled back).

## After deploy (prod-owed)

1. Set `audit.unexplained.email.to` (DAR-BE-358) and, when wanted, `errors.email.to` / `errors.chat.webhookUrl`
   (DAR-BE-015) on prod.
2. Next audit morning: Gorjana R1 / RAILS R4 / Malbon R4 RECHECK names changed keys (DAR-BE-356); Dolls Kill R7's four
   gift-card findings leave the report (DAR-BE-357); an R6/R7 finding shows Shopify status, total and day (DAR-BE-359);
   order and return findings show payment status (DAR-BE-360).
