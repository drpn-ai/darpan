# Docs Impact For Darpan 3.4.6

Every shipped item needs a decision in each column: the page path you changed (a path under the docs repo), or
`none: <reason>`. A hotfix folds into its release line: one `updates.mdx` section for 3.4.

## Product docs (docs.drpn.ai)

Repo `darpan-docs`. UI labels grep-checked against darpan-ui `src`: **Notify me** (ReconciliationRunResultPage.vue),
**Not run**, **Partial**, **Fixed** and **None** (src/lib/auditReport.ts), **Retry from last read** (AuditColumns.vue).
The quoted chat line "*name* did not run this morning. Darpan has been alerted and is working on a fix." is the
`DARPAN_ERROR_NOTICE` / `client.notrun` copy block, and the not-run reasons are `AutomationExecutionSupport.noDataFinding`'s
words, both quoted as shipped. No client name appears in the pages.

| Item | Change | How-to | Reference | Troubleshooting | Setup / service details |
| --- | --- | --- | --- | --- | --- |
| DAR-BE-015 | An automation run that fails, dead-letters, fails its rules, or reads (or keeps) nothing on one side while the other reads records no longer posts its error to a tenant destination. The error goes to Darpan's own error space (`errors.chat.webhookUrl`) and daily error email (`errors.email.to`); the automation's chat space gets one neutral line a day for a scheduled run, notify-me subscribers get it on any run; audit checks on such a window record not run and the run update says so | guides/run-completion-alerts.mdx ("When an automation run does not run", the Note, the verdict table, a troubleshooting row); guides/audits.mdx (the update names checks that did not run or partly ran) | reference/reconciliation-runs.mdx (No data and Dead-lettered rows, the terminal-status paragraph) | troubleshooting/automations-and-alerts.mdx (dead-letter alert, "did not run this morning" row); troubleshooting/audits.mdx (a check is Not run when one side read or kept nothing, with the reasons) | none: the error space, email and settings are Darpan-only; they are on the developers self-host page |
| DAR-BE-358 | After each audit run with Unexplained findings, Darpan's own email with a CSV of them to `audit.unexplained.email.to`; unset sends none | none: a Darpan-only email, never a tenant's | none: same | none: same | none: the setting is on the developers self-host page |
| DAR-BE-356 (darpan-hotwax 1.3.2) | An audit's re-check also reads the HotWax orders whose items were approved, completed or cancelled since its recent read, when placed in the period, and reads them by key on the other sides | guides/audits.mdx ("How findings close") | none: no field, status or stage name a user sees changed | troubleshooting/audits.mdx (Partial with "Records changed since the last read were not re-read:") | none: no connection, endpoint or option change |
| DAR-BE-360 + app v4.4.7 (1dfb47b) | Every finding's order and refund records carry a payment status; the basics row reads "<status> · <payment status>" per system | guides/audits.mdx (Read the audit page: the basics) | none: no reference page lists a finding's record facts | none: no new message; a system whose read gave no payment status shows its status alone | none: no new read |
| DAR-BE-359 (shopify-darpan 0.11.1) | The completed-returns read carries the order's date, financial and fulfillment status, total and refunded total; a Shopify return's day is its `closedAt` when it has no other, so those findings' basics are filled | none: guides/audits.mdx already says the basics show each system's status, amount and day; this fills them for that check | none: same | none: no new message | none: the completed-returns read is an audit check's own read, not an endpoint a user picks in guides/setup-shopify.mdx |
| DAR-BE-357 | A part payment given back is concluded from the Shopify order's total when HotWax zeroed its own; a voided Shopify authorization with no settled HotWax payment counts as never paid | none: brain causes are not listed in the docs | none: same | none: no new message; such findings stop reading Unexplained | none: no new read |
| DAR-BE-355 | A side Keep runs on in Spark reads a bare key (no record root) off each record, as the plan gate does; such a run failed at the compare | none: the saved-run editor writes full key paths; nothing a user does changes | none: no user-facing term | none: the failure it removes had no documented message; "A side cannot be filtered" is unchanged | none: no connection change |
| DAR-UI-143 (app v4.4.7, c3ea6b9) | While a play runs, the finding row and its open detail agree (a quiet report refresh re-reads the open finding; a fresh finding that disagrees with its row refreshes the report once) | none: the guide already describes the board as it now behaves | none: same | none: the mismatch was a bug with no message | none: same |
| backend e23bfa0e | 3.4.5 pack completed after its tag | none: release tooling only | none: release tooling only | none: release tooling only | none: release tooling only |

## GraphQL reference (developers.drpn.ai)

- SDL compared against: none: schema unchanged (moqui-gql stays `v1.1.1`; no GraphQL file touched in darpan
  `v3.4.5..92e6a636`, darpan-hotwax `v1.3.1..18197d3` or shopify-darpan `v0.11.0..0dd428d`); `verify_release_docs.py`
  compares the prod SDL with the snapshot after deploy.
- SDL snapshot refreshed and pages regenerated: none: schema unchanged.
- GraphQL changelog entry: graphql/changelog.mdx `## Darpan 3.4.6`: no schema change; a Shopify record with no stored
  status now reads its fulfillment status alone in `FindingRecord.status` (DAR-BE-360 split the financial status out
  into `paymentStatus`, which the schema does not carry).
- Hand-written GraphQL pages: none: no query, field or example changed.

## Service catalog and public OpenAPI

- Service catalog: api-reference/service-catalog.mdx (`run#Audit`: the re-check reads `changedSince` records;
  `get#AuditFinding`: records carry `paymentStatus`, a Shopify record's `status` is its fulfillment status;
  `admin.SettingAdminServices` `get#Settings`: `STRING` type and masked `secret` values; `update#Setting`: the 2,000-character
  limit and the `errors.chat.webhookUrl` / `errors.email.to` checks). Every contract method documented, none phantom:
  `check_service_catalog.py` against the `92e6a636` methods list (identical to `v3.4.5`'s), 319 of 319.
- `api-reference/openapi.json` regenerated with `scripts/public_openapi.py` at version 3.4.6: yes (from the release
  worktree's contract, version 3.4.6, contract 5; only the version and description changed, 226 public methods).
- `api-reference/changelog.mdx`: a 3.4.6 entry under contract 5 (no method added or removed; `paymentStatus` added on
  finding records, Shopify `status` narrowed to fulfillment, the re-check's `changedSince` read and its Partial reason, a
  one-side-empty check `notRun`; operator-only STRING and secret settings). `x-darpan-contract-version` stays 5.

## Engineering and self-host docs

- Runtime baseline matches `build.gradle`: yes, unchanged from 3.4.5 (`build.gradle` not touched in the range; Spark
  4.2.1, Drools 10.2.0, JDK 21, Bitronix, JVM flags); no baseline page needed a change.
- Backend pages: backend/reconciliation-engine.mdx (`RECHECK` stage with `changedSince`; Keep reads a bare key off each
  record; new "Run errors" section: `RunErrorKinds`, `notifyRunError`, the `RunErrorNotice` dedupe, the client line,
  execution `file1ReadCount`/`file2ReadCount`/`noDataKind`/`triggerType`, `ErrorLogCapture`, the daily error email and
  watchdog, the Unexplained CSV); backend/component-layout.mdx (`OpsEntities.xml`, `service/ops/`);
  backend/operations-and-validation.mdx (jobs `send_ErrorDigest_hourly` and `flush_ErrorLog_5m`).
- Self-host and deploy pages: self-host/production.mdx (3.4.6 under "Upgrading to 3.4": image moves `darpan`,
  `darpan-hotwax` `v1.3.2` and `shopify-darpan` `v0.11.1`, app `v4.4.7`; 37 upgrade records; tables `RUN_ERROR_NOTICE`,
  `ERROR_LOG_ENTRY`, `ERROR_DIGEST_SEND` and four execution columns by add-missing, no migration; the six Errors settings
  `errors.chat.webhookUrl`, `errors.email.to`, `errors.digest.hourUtc`, `errors.watchdog.hours`, `errors.emptyStreak.days`,
  `audit.unexplained.email.to` with defaults; what changes on its own). The self-host troubleshooting page: none: no new
  failure mode an operator must fix (an unset setting sends nothing by design).
- Engineering process pages: none: no process change.

## Release notes and roadmap

- `releases/updates.mdx`: the 3.4 section is `## v3.4.6` (one section per line), with 3.4.6 and app `v4.4.7` named in
  the intro, its items under Runs and results (run errors to Darpan, bare-key Keep) and Audits (changed-orders re-check,
  one-side-empty Not run, payment given back and voided authorization, payment status in the basics, row and detail
  agree), and an Operators line (components, 37 records, tables and fields on boot, the three Darpan-only settings).
- `releases/roadmap.mdx` "Recently shipped": the 3.4 card names v3.4.6 and app v4.4.7. "Up next" unchanged.

Docs commits: darpan-docs `a237412`, darpan-developers `0b342bb` (local, not pushed).
