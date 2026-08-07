# Darpan 1.4.0 Release Notes

Release date: `2026-08-07`

## Scope note

Release `1.4.0` makes a running automation something an operator can actually watch, stop, and
diagnose. Pressing "Run now" no longer dies at the gateway's 60-second timeout, the run it starts is
visible from the moment it begins, its progress timeline advances stage by stage, Cancel genuinely
stops it, and a failure reports its full error text instead of a sentence cut off at 255 characters.
Most of this section is repair of behaviour that looked like it worked: runs that reported SUCCESS
after being cancelled, "Notify me" subscriptions that silently stopped firing, and a live view that
sat at seven Pending stages for a run's entire life.

The release also adds HotWax transfer orders as a reconcilable source system, and keeps
rule-referenced fields in the extract projection rather than switching projection off entirely
whenever a rule set has any rule.

Deferred to a later release: state-based (window-less) automation mode ships underneath but is
deliberately not offered in the UI — the per-source status list it depends on has no write path yet,
so offering it would let an operator save an automation that fails on every run. `shopify-darpan`,
`netsuite-darpan`, and `database-darpan` are unchanged and are not part of this release.

## Repo targets

| Repo | Remote | Branch | Tag |
| --- | --- | --- | --- |
| Backend component | `drpn-ai/darpan` | `main` | `v1.4.0` |
| HotWax connector | `drpn-ai/darpan-hotwax` | `main` | `v0.7.0` |
| UI | `drpn-ai/darpan-ui` | `main` | `v2.5.0` |

- Backend compare range: `v1.3.0..HEAD` (25 commits)
- HotWax connector compare range: `v0.6.0..HEAD` (10 commits)
- UI compare range: `v2.4.0..HEAD` (13 commits)
- `shopify-darpan` stays at `v0.5.0`, `netsuite-darpan` at `v0.2.0`, `database-darpan` at `v0.1.0`;
  none has commits in this range.

## User-visible changes

**Running an automation by hand**

- "Run now" survives past 60 seconds. It previously held the JSON-RPC request open until the gateway
  severed the connection, leaving no execution row behind at all — the button appeared to do
  nothing. The run is now detached from the request, so each write commits on its own and the run
  outlives the request that started it.
- "Run now" opens the live progress view of the run it just started, on both API-range and SFTP
  automations. Both previously had no run to redirect to until the run had already finished.
- The progress timeline advances through Resolve, Extract, Compare, and Write output as the run
  works. Automations previously wrote no step rows at all, so every stage rendered "Pending" for the
  run's whole life and a finished run was then labelled a "legacy run".
- **Cancel actually stops an automation run.** Nothing on the automation side read the flag the
  Cancel button sets: the run continued to completion and reported SUCCESS to an operator who
  believed they had stopped it. Cancel is now honoured at each phase boundary on both input modes,
  and a cancelled run is recorded CANCELLED rather than being misreported as a failure.
- A long-running automation is no longer falsely marked failed by the stuck-run sweep while it is
  still working.

**Seeing why a run failed**

- A failed run shows its full error text. The stored message is capped at 255 characters, which cut
  real errors off mid-sentence — routinely removing the half that names the fix. The untruncated
  text was already being stored; nothing exposed it until now.
- Error banners stay on screen until dismissed instead of disappearing after 10 seconds.

**Notifications**

- "Notify me" survives an automation retry. Because each attempt creates its own run row, a
  subscription taken during one attempt was left behind by the next, and an operator who asked to be
  told when the automation gave up was never told. Subscriptions now move forward with the retry
  chain, and every terminal outcome notifies its subscribers exactly once.
- A failing SFTP automation now sends a failure alert, matching what API-range automations already
  did. It previously closed silently.
- "Stop notifying me" reports an error when it removed nothing, instead of reporting success while
  the subscription went on firing from a newer attempt.
- A run that finishes clean is no longer headlined "WITH ISSUES". Both verification passes write one
  audit sentence on every run, including all-clear ones, and the notification header was counting
  those sentences as warnings.

**Transfer orders**

- HotWax transfer orders can be reconciled as their own source system, with its own extract service
  and connection settings. Record filtering is order-type aware, and the order type and status are
  filtered server-side rather than after fetching.
- Transfer orders match on `orderDate` rather than creation date. A transfer order's life spans
  create to receive, so a creation-date window can exclude a transfer that moved inside the window.

**Rules and exclusions**

- A rule set with rules no longer switches off extract projection wholesale. Fields referenced by
  rules are kept alongside the identity and display fields, so extracts stay trimmed.
- "Product store" is offered as an exclusion field for OMS sources.
- Typed text in the exclusion and primary-id inputs is committed when Save is clicked. Text typed
  but not confirmed with Enter was previously discarded silently.
- An interactive run of a saved run now queries the same date field its scheduled runs do. Only the
  scheduled path honoured a configured window field, so the two could silently disagree.
- Where a side cannot carry exclusions, the UI explains why instead of doing nothing.

**Elsewhere**

- Automation schedules render in the viewer's preferred timezone.
- Tenant settings no longer momentarily assert values while they are still loading.
- A keyboard event carrying no key no longer crashes the app shell.

## Operator-visible changes

**Before anything else — confirm 1.3.0's data actually loaded.** The upgrade file in this release is
release-scoped: it carries only the records new in `1.4.0` and does not re-carry the `1.3.0` record
(the OMS connector's `filterParameterName`). An environment that never loaded
`data/releases/1.3.0/upgrade-data.xml` must load that file first, or source exclusion filters remain
inert there even after `1.4.0` is deployed. The same applies to the `1.3.0` one-time backfill,
`reconciliation.ReconciliationNotificationServices.migrate#AutomationExcludeFilters`, which is
idempotent and safe to run again if its status is unknown.

Then, after deploying the backend:

1. **Load the upgrade data.** `./gradlew loadDarpanUpgradeData` adds three records: the
   `OMS_TRANSFER_ORDERS` system enum, its `SourceSystemConnector` row, and the `AUT_WIN_STATE`
   window-mode enum. All three are additive — nothing existing is modified, so loading changes no
   current reconciliation behaviour. Until it runs, transfer orders do not appear as a source-system
   option.

`1.4.0` adds no one-time migration service of its own.

Environment caveats:

- **State-based automation mode is not operator-reachable in this release.** The enum row, the
  window-mode dispatch, the per-source status list, and the record ceiling all ship, but
  `list#AutomationSourceOptions` filters the option out of the dropdown because the status-list
  write path does not exist yet. Do not announce it. The filter comes out when that surface ships.
- **No schema migration is needed.** Four fields are added to existing entities
  (`extractStatusIds` on the source config; `windowFieldName`, `supportsStateExtract`, and
  `statusParameterName` on `SourceSystemConnector`). Moqui adds the columns on startup. No new
  tables and no new entity groups.
- The published API contract is unchanged in shape: 90 methods, contract version `2`. Integrations
  need no change. `errorDetail` is a new output field on `get#ReconciliationRunStatus`, which is an
  additive change under the additive-only deprecation policy.
- A state-defined extract is capped at 50,000 raw records and fails with a clear error naming the
  limit rather than truncating. This is inert until state mode is reachable, but it is the safety
  property that makes state mode safe to enable later: a short extract would otherwise read exactly
  like records genuinely missing on one side.
- Deploy the backend before the UI. The UI reads `errorDetail` and the transfer-order system and
  config type, which only exist in `1.4.0`.

## Upgrade data

- Generic source data files must contain every release upgrade record before the release pack is generated.
- Add or update records in the appropriate `runtime/component/darpan/data/*.xml` file first; do not author release-only records directly in `upgrade-data.xml`.
- Current upgrade file: `runtime/component/darpan/data/upgrade-data.xml`
- Release-pack mirror: `upgrade-data.xml`
- Candidate review report: `upgrade-data-review.md`
- Operator load command: `./gradlew loadDarpanUpgradeData`
- Sources of the records: `data/AutomationSeedData.xml` (`AUT_WIN_STATE`),
  `data/DarpanSystemSourceSeedData.xml` (`OMS_TRANSFER_ORDERS` enum), and
  `data/SourceSystemConnectorSeedData.xml` (the transfer-order connector row).
- The previous current upgrade file was already archived under `data/releases/1.3.0/upgrade-data.xml`
  and was verified byte-identical to what the `v1.3.0` tag shipped before this pack was generated.
  Re-archiving was deliberately bypassed: the working copy had been appended to since `v1.3.0`, so
  archiving it again would have overwritten a correct two-record archive with a four-record file.
- This release carries a parent-child pair. The `OMS_TRANSFER_ORDERS` Enumeration is ordered before
  the `SourceSystemConnector` row whose `systemEnumId` references it. The generator's sort happens
  to agree with that dependency here; the ordering was re-checked by hand rather than assumed.

## Verification

Ran and passed:

- Backend, under JDK 21, via `--rerun-tasks` so the suites genuinely re-executed:
  `darpan` 778 tests across 88 classes, `darpan-hotwax` 109 tests across 6 classes — 0 failures,
  0 errors, 0 skipped. `BUILD SUCCESSFUL in 12m 11s`.
  A plain `test` invocation is not sufficient: a `component.xml` version bump is not a test input,
  so Gradle reports the suite `UP-TO-DATE` and executes nothing.
- UI: `npm run check` — 900 tests across 102 files, 0 failures, with the stylelint design-system
  gate, ESLint, and `vue-tsc` type-check all executing.
- `FacadeContractSnapshotTests` failed on `main` before this pack was prepared: `errorDetail` had
  been added to `get#ReconciliationRunStatus` and to `openapi.json`, but the committed facade
  snapshot was never regenerated. Regenerated; the diff is one line, in that one service. The
  regenerated artifact was diffed before being accepted, because regeneration would otherwise have
  silently blessed any other contract drift in the range.
- API contract regenerated for the version bump: `docs/api-contract/openapi.json`, one-line diff
  (`1.3.0` to `1.4.0`), `methods.txt` unchanged at 90 methods.
- `scripts/check_contract_compat.py` against `v1.3.0`: 90 base methods preserved, contract version
  `2` — additive, no unversioned breakage.

Not verified in this release:

- No live reconciliation run was executed against a real OMS tenant for this tag. Transfer-order
  extraction has not been exercised end-to-end against production-shaped data, and the
  `OMS_TRANSFER_ORDERS` connector has never resolved against a live source.
- No browser pass was run against this tag. The live-progress timeline, Cancel, and the full-error
  display were verified in the browser during development of their individual commits, but not
  re-checked as a set on the release candidate.
- The 50,000-record state-extract ceiling has not been exercised against a real dataset.
- No deployed-environment smoke test was performed for this tag.
- Whether the `1.3.0` upgrade data and one-time backfill have been loaded in production is not
  established here and must be confirmed before deploying.

## Deferred items

- State-based automation mode is not reachable: it needs a per-source status-list write path and the
  UI surface to configure it.
- Transfer-order reconciliation covers extraction and comparison; no transfer-order-specific
  verification pass or pair lookup exists.
- Exclusion filters still reach only the HotWax OMS getters. Shopify and NetSuite getters accept no
  filter parameter.
- The automation heartbeat is a phase-boundary heartbeat, not a progress heartbeat: a run whose
  single phase exceeds the stuck-run threshold on its own can still be falsely reaped.
- An automation's exclusion filters remain frozen at creation; editing a rule set's filters does not
  reach automations that already exist.
- Reconciliation observability still has no watchdog and no alerting.
- Exchange-order reconciliation phases 2 and 3 remain unstarted.
- Per-tenant `read_returns` and Shopify `apiVersion` remain global rather than per-tenant settings.

## Rollback or fallback notes

- Rolling the backend back to `v1.3.0` is safe. The three loaded records are additive and unread by
  `1.3.0`: the `AUT_WIN_STATE` enum is inert, and the transfer-order connector row is only reachable
  from configuration that `1.3.0` cannot create. The four new entity columns are ignored by `1.3.0`
  code, so the loaded upgrade data does not need to be reverted.
- Roll the UI back to `v2.4.0` alongside any backend rollback. A `2.5.0` UI against a `1.3.0` backend
  will request `errorDetail` and the transfer-order system type, neither of which that backend
  returns.
- Rolling back re-opens the "Run now" 60-second failure and the cancelled-run-reports-SUCCESS
  behaviour, both of which were reported from production. Prefer fixing forward.
- Go/no-go: if `1.3.0`'s upgrade data was never loaded, load it before or with this release. Loading
  only this file leaves exclusion filters inert while the UI presents them as working.
