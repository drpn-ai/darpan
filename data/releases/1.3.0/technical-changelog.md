# Technical Changelog For Darpan 1.3.0

This file is the engineer-facing companion to the user release notes. Keep it curated and diffable.

## Versioning decision

`1.3.0` is a **minor** release.

The range adds two new entity groups, new service-contract fields on the reconciliation facade, and
new UI surfaces — all backward compatible with `1.2.0` clients, none of them a break. A patch number
was considered and rejected: it would have signalled bugfix-only to operators while shipping a
feature that carries a required seed-data load and a one-time backfill.

Companion versions: `darpan-hotwax` `0.6.0` (minor — the orders getter gains filter behavior),
`darpan-ui` `2.4.0` (minor — new exclusion surfaces). `netsuite-darpan` stays `0.2.0`, unchanged.

Note: `darpan-hotwax/component.xml` had been left at `0.2.0` through the `0.3.0`, `0.4.0`, and
`0.5.0` tags. This release corrects it to `0.6.0`, so the declared component version and the tag
agree again.

## Source ranges

- Backend: `v1.2.0..HEAD` (`drpn-ai/darpan`, 17 commits)
- HotWax connector: `v0.5.0..HEAD` (`drpn-ai/darpan-hotwax`, 6 commits)
- UI: `v2.3.0..HEAD` (`drpn-ai/darpan-ui`, 33 commits)

## Backend

### Added
- `f49dadd` Source exclusion filter entities for rule sets (`entity/RuleEntities.xml`, +46) and
  automations (`entity/ReconciliationEntities.xml`, +40).
- `5461a08` `SourceFilterSupport.groovy` (new, 176 lines) — connector-agnostic helper that turns
  stored filter rows into the argument a getter expects.
- `d1c9f29` Registry-driven `filterParameterName`, declared by the connector rather than hardcoded
  per source. The OMS connector declares `sourceFilters`.
- `6f3e19b` Interactive path passes rule set exclusion filters to the getter.
- `99a7b04` Scheduled path passes automation exclusion filters to the getter.
- `55e39f9` Rule set facade saves and returns source exclusion filters.
- `07c9329` Automation facade snapshots source exclusion filters at save time
  (`AutomationFacadeSupport.groovy`, +389).
- `b4af86d` `facade.ReconciliationFacadeServices.migrate#AutomationExcludeFilters` — one-time,
  idempotent, non-destructive backfill for automations that predate the feature. Seeds a side only
  when that side has zero filter rows; reuses the existing `seedFromRuleSet` reader.
- `src/test/resources/facade-contract.snapshot.txt` (+15) records the widened facade contract.

### Changed
- `04674ed` Stored exclusion expressions are reduced to record keys at dispatch rather than carried
  as full expressions.
- `docs/api-contract/openapi.json` regenerated for the version bump. `ApiContractGenerator` embeds
  the `component.xml` version in the spec, so `ApiContractGeneratorTests.committedContractMatchesGeneratedContract`
  fails by design until the contract is regenerated in the same commit as the bump. The diff is one
  line — `info.version` `1.2.0` → `1.3.0`. `methods.txt` is unchanged at 90 methods, and
  `x-darpan-contract-version` stays `2`: this release adds no remote methods and breaks none, so the
  exclusion fields ride on existing rule-set and automation methods.
- `ReconciliationSavedRunSupport.groovy` (+147) and `CompareIdExpressionSupport.groovy` (+28) thread
  the filter argument through the saved-run and compare-id paths.

### Fixed
- `0d4fbb1` Extract progress is reported as a count on both file sides, instead of holding its
  starting value until the stage completed. This is the only fix here to behavior that `1.2.0`
  shipped; the rest correct the unreleased exclusion-filter feature in place.
- `b803364` `create#RuleSetRun` no longer drops exclusion filters silently.
- `21694ec` Missing-operator default, and locale-safe uppercasing (avoids the Turkish-I class of bug).
- `d461e53` Backfill message ordering, validation error surfacing, and unconditional transaction
  isolation.
- `aaa212e` `FacadeSupport.enumLabel` names every source system, not only HotWax.

### Security
- `8f69c32` Closed cross-tenant and exposure gaps in the exclusion-filter backfill. The sweep's two
  reads route through `TenantScopedFinder.findGlobalUnscoped`.
- `DisableAuthzRatchetTest` baseline raised 11 → 12: the backfill needs one bare `disableAuthz`
  service-call write, because `TenantScopedFinder` has no create-side equivalent. The ratchet test
  exists to make exactly this kind of addition deliberate and reviewable rather than silent.

## UI

### Added
- `dccd938` Source exclusion filter draft types and payload conversion.
- `d885f4f` Exclusion mark and popover on the rules board.
- `489381e` Exclusions summary card on the rule set manager.
- `cf4f0bd` Rules board as the final create-run step.
- `c19a949` Ghost rule on arrival and term definitions on hover.
- `07ab77a` Live elapsed clock while a run is in progress.
- `5c527d9`, `20d2add` Spacing scale completed (`--space-00` plus two missing steps), in the app and
  the design system.

### Changed
- `fd767d0` Stylelint design-system gate added to `npm run check`.
- `638603c` Declared the nine tokens the app consumed but never defined.
- `fbd76a7`, `9db2196` App type moved onto design-system tokens; orphan sizes folded onto roles.
- `3066add`, `8138d65` One weight axis; only the 400 weight ships.
- `28ba4e8`, `b08bca9` Gate extended to font-size, letter-spacing, and margin; gap and padding are
  constrained by the scale itself.
- `d0f0bc5`, `b57be47`, `1a5f780`, `f683002`, `8ceea51`, `f7589f3` Chip, select internals,
  state-surface, header-row, action-row, and micro-label roles shared instead of reimplemented.
- `a22ddc1` Spacing folded onto the scale.
- `24f03df`, `2d8f0f3`, `86f5f63` System name and connection share one dot-separated row; board micro
  labels use the table-head role; rule popover aligned with its sibling exclusion editor.

### Fixed
- `f7624f9`, `4ba9e9d` Exclusion editor popup centring/width, and four rendering faults in the new
  help affordances.
- `f5aea65` Rule and exclusion popovers are mutually exclusive.
- `0c92c5c`, `0b37c95`, `c583725` Persisted exclusions hydrate; double-click opens exclusions;
  exclusions list ordering, structural and a11y guards.
- `48de843` Rules-board cancel discards correctly; automation-handoff draft leak closed.
- `e76436b`, `df3fe5f`, `620da78` System naming: no uppercasing on the rules board, human label
  preferred over the enum code, system named on the run summary with its config beneath.

### Security
- None.

## HotWax connector

- `d91e9c0` Orders getter applies configured exclusion rules as a third rejection branch in
  `filterComparableOrderRecords`, running *after* the two built-in exclusions so their counts never
  shift when a tenant adds a rule, and a record excluded for several reasons lands in exactly one
  bucket. Rules are parsed once in `extractOrdersInternal` before the fetch pool starts; per-page
  match counts ride back on the page bundle and are summed by the single-threaded page consumer.
  This is post-fetch filtering, not query pushdown — the OMS query is unchanged, and zero rules is
  byte-identical to the previous behavior.
- `8f2e0eb` Extract count heartbeats on the stage that is actually running.
- `1e88c90`, `2aad0d1`, `9fe1bfc` Docs: configurable record exclusion, the note that pair lookups
  apply no exclusion rules, and reconciliation data requirements for orders and returns.

## Data and configuration

- Generic source data files under `runtime/component/darpan/data/*.xml` are the source of truth for seeded data.
- Candidate upgrade records are generated from generic source data diffs into `upgrade-data-review.md` and the current load target `runtime/component/darpan/data/upgrade-data.xml`.
- One record changes: `darpan.reconciliation.SourceSystemConnector` for `systemEnumId=OMS` gains
  `filterParameterName="sourceFilters"`, sourced from `data/SourceSystemConnectorSeedData.xml`.
- No schema migration is required. Moqui creates tables for the two new entity groups on startup;
  only seeded rows need the explicit load.
- `data/upgrade-data.xml` had been hand-edited after the `v1.2.0` tag, leaving it a hybrid of 1.2.0
  records plus one 1.3.0 record. It was restored to its tagged content before regeneration, so the
  archived `data/releases/1.2.0/upgrade-data.xml` remains byte-identical to what `v1.2.0` shipped.

## Validation and rollout notes

- Backward compatibility: additive only. A `1.2.0` backend ignores the new column value; a `1.2.0`
  client does not request the new facade fields.
- Rollout order: backend, then upgrade-data load, then backfill, then UI. A `2.4.0` UI against a
  `1.2.0` backend exposes controls the backend cannot persist.
- Backend verification required `--rerun-tasks`. Gradle reported `:runtime:component:darpan:test`
  as `UP-TO-DATE` on a plain invocation, because a `component.xml` version bump is not a test input —
  the resulting `BUILD SUCCESSFUL` was a cache hit, not an executed suite.
- Compare ranges are listed under "Source ranges" above; compare URLs can be formed as
  `https://github.com/drpn-ai/<repo>/compare/<prev-tag>...<tag>`.

## References

- Keep a Changelog: https://keepachangelog.com/en/1.1.0/
- GitHub generated release notes: https://docs.github.com/en/repositories/releasing-projects-on-github/automatically-generated-release-notes
- Semantic Versioning: https://semver.org/
