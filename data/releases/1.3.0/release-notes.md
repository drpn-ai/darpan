# Darpan 1.3.0 Release Notes

Release date: `2026-08-05`

## Scope note

Release `1.3.0` makes source exclusion filters a first-class part of reconciliation. Operators can
now declare, on a rule set or a scheduled automation, which records a source system should leave out
of a run — excluded records are dropped as the extract is built, so they never reach the comparison.
The release also carries a visual consolidation of the UI onto its design system, and a live
elapsed clock on in-progress runs.

Deferred to a later release: exclusion filters are wired for the HotWax OMS orders getter only;
Shopify and NetSuite getters ignore them. Pair lookups (the missing-diff verify pass) deliberately
apply no exclusion rules. `netsuite-darpan` is unchanged and is not part of this release.

## Repo targets

| Repo | Remote | Branch | Tag |
| --- | --- | --- | --- |
| Backend component | `drpn-ai/darpan` | `main` | `v1.3.0` |
| HotWax connector | `drpn-ai/darpan-hotwax` | `main` | `v0.6.0` |
| UI | `drpn-ai/darpan-ui` | `main` | `v2.4.0` |

- Backend compare range: `v1.2.0..HEAD`
- HotWax connector compare range: `v0.5.0..HEAD`
- UI compare range: `v2.3.0..HEAD`
- `netsuite-darpan` stays at `v0.2.0`; it has no commits in this range.

## User-visible changes

**Source exclusion filters**

- A rule set can carry exclusion filters per source system. Records matching a filter are left out of
  the comparison, so expected mismatches stop showing up as differences run after run.
- Scheduled automations carry their own exclusion filters. A new automation seeds them once from its
  rule set, so a scheduled run starts out matching what an interactive run would produce.
- Excluded records are dropped during extraction, before they are written to the extract file and
  compared, so the stored extract and the comparison both shrink. The source is still queried in
  full; filtering happens on the records as they come back, not in the query sent to OMS.
- Built-in exclusions (non-sales orders, exchange orders) keep their priority over configured rules,
  so their counts stay stable and comparable across runs as tenants add filters.
- The rules board is now the final step of creating a run, with the exclusion editor reachable
  directly from it and an exclusions summary card on the rule set manager.

**Reading the board**

- A ghost rule shows the shape of a rule before you have written one, and hovering a term reveals
  what it means, so the board explains itself without standing instructions on screen.

**Runs in flight**

- An in-progress run ticks a live elapsed clock, so a long-running run is visibly alive.
- Source systems are named the same way everywhere — the human label rather than the internal code,
  with the connection shown beneath it on run summaries.

**Appearance**

- The app now draws its type, spacing, and surface treatments from one shared design system, so
  headers, chips, selects, and empty/error/offline states line up across pages.
- Only one font weight ships, which trims the web-font payload.

**Fixed**

- Extract progress now reports a count on both file sides while a stage is running, instead of
  stalling at its starting value until the stage finished.

## Operator-visible changes

Two actions are required, in this order, after deploying the backend:

1. **Load the upgrade data.** `./gradlew loadDarpanUpgradeData` applies an in-place update to the
   existing OMS connector row, adding `filterParameterName`. Until this runs, exclusion filters are
   inert: the rules board saves exclusions that never reach the OMS query, and the exclusion popover
   renders empty.
2. **Run the one-time backfill.** `reconciliation.ReconciliationNotificationServices.migrate#AutomationExcludeFilters`
   seeds exclusion-filter rows for automations that already existed before this release. It is
   idempotent and non-destructive — it seeds a side only when that side has zero filter rows — so it
   is safe to run more than once. Automations created after this release are seeded at creation time
   and do not need it.

Environment caveats:

- **An automation's exclusion filters are frozen at creation.** Editing a rule set's filters later
  does not reach automations that already exist, so a scheduled run can diverge from an interactive
  run of the same rule set. Exclusions are editable only on the rules board; the automation wizard
  never submits them. Re-create the automation if its filters must follow a changed rule set.
- The backfill sweeps across tenants and has no active tenant of its own; it stamps
  `companyUserGroupId` from each automation.
- The published API contract is unchanged in shape: 90 methods, contract version `2`. Integrations
  need no change. Exclusion filters ride on existing rule-set and automation methods as new fields.
- No schema migration is needed for the two new entity groups — Moqui creates the tables on startup.
- Deploy the backend before the UI. The UI's exclusion editor calls facade fields that only exist in
  `1.3.0`.

## Upgrade data

- Generic source data files must contain every release upgrade record before the release pack is generated.
- Add or update records in the appropriate `runtime/component/darpan/data/*.xml` file first; do not author release-only records directly in `upgrade-data.xml`.
- Current upgrade file: `runtime/component/darpan/data/upgrade-data.xml`
- Release-pack mirror: `upgrade-data.xml`
- Candidate review report: `upgrade-data-review.md`
- Operator load command: `./gradlew loadDarpanUpgradeData`
- Source of the record: `data/SourceSystemConnectorSeedData.xml` (OMS connector, `filterParameterName="sourceFilters"`).
- The previous current upgrade file was archived under `data/releases/1.2.0/upgrade-data.xml` and
  verified byte-identical to what the `v1.2.0` tag shipped.
- This release carries a single record with no parent dependency, so no hand-ordering was required.

## Verification

Ran and passed:

- Backend, under JDK 21, via `cleanTest test` so the suites genuinely re-executed:
  `darpan` 709 tests across 86 classes, `darpan-hotwax` 84 tests across 6 classes — 0 failures,
  0 errors, 0 skipped. `BUILD SUCCESSFUL in 10m 59s`.
  A plain `test` invocation was not sufficient: Gradle reported the darpan suite `UP-TO-DATE` and
  executed nothing, because a `component.xml` version bump is not a test input. That first run's
  `BUILD SUCCESSFUL` was a cache hit, not a passing suite.
- UI: `npm run check` — 873 tests across 101 files, 0 failures, with the stylelint design-system
  gate, ESLint, and `vue-tsc` type-check all executing.
- `ApiContractGeneratorTests.committedContractMatchesGeneratedContract` failed until the API contract
  was regenerated for the new version, which is the gate working as intended. Regenerated; the diff
  is one line and `methods.txt` is unchanged.

Not verified in this release:

- No live reconciliation run was executed against a real OMS tenant. Exclusion filters have not been
  exercised end-to-end against production-shaped data.
- No browser pass was run against the rules board help affordances or the exclusion editor; their
  proof is component tests only.
- The one-time backfill has not been run against a production dataset.
- No deployed-environment smoke test was performed for this tag.

## Deferred items

- Exclusion filters reach only the HotWax OMS orders getter. Shopify and NetSuite getters accept no
  filter parameter yet.
- Pair lookups in the missing-diff verify pass intentionally apply no exclusion rules; whether they
  should is an open question, not an oversight.
- Reconciliation observability still has no watchdog and no alerting.
- Exchange-order reconciliation phases 2 and 3 remain unstarted.
- Per-tenant `read_returns` and Shopify `apiVersion` remain global rather than per-tenant settings.

## Rollback or fallback notes

- Rolling the backend back to `v1.2.0` is safe. The two new entity groups are additive and unread by
  `1.2.0`; the `filterParameterName` column value on the OMS connector row is ignored by `1.2.0`
  code, so the loaded upgrade data does not need to be reverted.
- Roll the UI back to `v2.3.0` alongside any backend rollback. A `2.4.0` UI against a `1.2.0` backend
  will surface exclusion controls the backend cannot persist.
- Go/no-go: if the upgrade data has not been loaded, do not announce exclusion filters to operators —
  the controls appear in the UI but silently do nothing against OMS.
