# Technical Changelog For Darpan 3.4.0

This file is the engineer-facing companion to the user release notes. Keep it curated and diffable.

## Versioning decision

- Minor by number (3.3.2 -> 3.4.0), breaking by contract: the facade contract moves to version 5 (8 TenantScript
  methods removed, `save#Automation` sources lose `excludeFilters`, `add#TenantMember` renames `welcomeQueued`).
  Precedent tied contract bumps to Darpan majors (contract 4 with 3.0.0); Aditi kept 3.4.0 (2026-11-23 12:20 IST)
  because the next major is reserved for DAR-REL-006. Every product repo is re-cut: the system repos change behaviour
  with core (Keep in Spark), and darpan now depends on moqui-jexl.

## Source ranges

- darpan: https://github.com/drpn-ai/darpan/compare/v3.3.2...v3.4.0
- moqui-rules: first tag, v0.2.0 (`47a0322`)
- moqui-jexl: https://github.com/drpn-ai/moqui-jexl/compare/v0.2.0...v0.2.2
- darpan-hotwax: https://github.com/drpn-ai/darpan-hotwax/compare/v1.2.0...v1.3.0
- netsuite-darpan: https://github.com/drpn-ai/netsuite-darpan/compare/v0.6.0...v0.7.0
- shopify-darpan: https://github.com/drpn-ai/shopify-darpan/compare/v0.10.0...v0.11.0
- darpan-agent: https://github.com/drpn-ai/darpan-agent/compare/v0.4.0...v0.5.0
- darpan-ui: https://github.com/drpn-ai/darpan-ui/compare/v4.3.2...v4.4.0
- darpan-docker-config: https://github.com/drpn-ai/darpan-docker-config/compare/v3.3.2...v3.4.0

## Backend (darpan), by item

- DAR-BE-318 (105 commits): plan 2, the judge on moqui-rules (pair fact types, `DecisionOutcome`/`CheckDecision`
  config, judge replay); plan 3a, datasets (kept copies, `Dataset`/`DatasetRead`, `scan_DueDatasets_30m` paused);
  plan 3b-1/3b-2a/3b-2b, Keep in Spark (`KeepPlanSupport.effectiveSideKeep` is the one decision for interactive,
  automation, retry, replay and evaluate paths; extractors no longer filter; connector `keepLinesJson`; a side that
  cannot be filtered fails; set-aside reported per side); the fact-conversion speed work (`buildFact` statically
  compiled, no exception per pair); plan 3b-2c, one filter store (automations read the compare scope's filters,
  `retire#AutomationSourceFilterTable` migration, `AUTOMATION_FILTERS_DIFFER` on old imports, agent lever writes the
  run's filter only); moqui-rules' seed rows carried as `darpan-seed` (`MoquiRulesSeedData.xml`, with a
  drift guard).
- DAR-BE-328 (48 commits): `darpan.peak.*` and `darpan.pulse.*` entities, `PeakPeriodFacadeServices` (8 methods),
  `SyncPulseFacadeServices` (5 methods), `run_SyncPulse`, present keys persisted, chunked and swept by retention,
  pulse run link by read window, `periodThruDate`.
- DAR-OPS-026 (36 commits): shared-Moqui test pool (`@SharedMoqui`, `smokeSharedTest`), quiet gate, seam resets.
- DAR-BE-320: lookup share gate, audit read report (`get#AuditReadReport`), reads line in run updates.
- DAR-BE-334 (queries on moqui-jexl): `darpan.query` read catalog and proposals, `send#AgentQueryTurn`, the
  `TENANT_SCRIPTS_TO_QUERIES` migration, `MoquiJexlSeedData.xml` with a drift guard, QUERY result caps raised,
  `darpan.script` deleted (its tables kept until the migration runs), contract version 5.
- DAR-BE-337: hidden repeated-key rows leave the duplicate difference count; the repeated-key warning withdraws when
  every repeat is hidden.
- DAR-BE-336: `evidenceFieldsJson` for OMS_GQL, OMS_RETURNS_GQL and SHOPIFY_RETURN_REFS.
- DAR-BE-192, DAR-BE-323, DAR-BE-325, DAR-BE-327, DAR-BE-332, DAR-CLI-007, DAR-UI-132 (backend half), DAR-BE-301
  (test), DAR-CLIENT-006; DAR-BE-319/321/322 already shipped in 3.3.1/3.3.2 and are on `main` for the first time
  under a tag.

## System repos

- darpan-hotwax 1.3.0: DAR-BE-318 (built-in drops as Keep lines, extractor no longer filters), DAR-BE-328, DAR-BE-329
  (R10 shipments), DAR-BE-142.
- netsuite-darpan 0.7.0: DAR-BE-318 (SuiteQL Keep line), DAR-BE-328, DAR-BE-329.
- shopify-darpan 0.11.0: DAR-BE-318, DAR-BE-320, DAR-BE-326.
- darpan-agent 0.5.0: DAR-BE-320 (audit read review, read-economy tickets), DAR-BE-334 (query-author agent).
- moqui-jexl 0.2.2: sources learn their script and scope, ISO dates, owner fields, the deployment scheduler follows
  Moqui's scheduler switch.
- moqui-rules 0.2.0: entities renamed to `rules.*`, bulk `convert#Facts`, statically compiled conversion.

## UI (darpan-ui 4.4.0)

- DAR-BE-328 (21 commits): peak periods in Tenant Settings, sync pair page and pulse charts, period cue.
- DAR-UI-135 (15 commits): run conclusions edit page.
- DAR-BE-334: the scripts page becomes Queries (ask, read in words, run, schedule by email).
- DAR-UI-134 and DAR-UI-136 (motion rule, success pulse, icon crossfade, tokens guard), DAR-BE-327 (LT operator).

## Data and configuration

- `data/upgrade-data.xml` generated with `release_preflight.py generate` from `v3.3.2..HEAD` in a clean clone:
  2,421 records (review: `upgrade-data-review.md`). New seed files `data/MoquiRulesSeedData.xml` and `data/MoquiJexlSeedData.xml` (the
  components' type-seed rows as `darpan-seed`).
- Connector Keep lines (`keepLinesJson`) are seeded by darpan-hotwax, netsuite-darpan and shopify-darpan in their
  own `darpan-seed` files, loaded at boot with the pack.
- New tables are created at boot; `KEEP_LINES_JSON` and `KEPT_FIELDS_JSON` must come out LONGTEXT on MySQL.
- Jobs: `run_SyncPulse` running; `scan_DueDatasets_30m` paused; `run_DataRetention_daily` must run.

## Validation and rollout notes

- Tripwires at the release commit and item-level proof: `release-notes.md` "Verification".
- Rollout: UAT first (image with moqui-rules v0.2.0, classloader smoke), then prod on Aditi's go: backend with the
  upgrade data, the two post-deploy checks before the scheduler resumes, then darpan-ui 4.4.0, then CLI 0.2.4.
- Rollback: the whole backend set to the 3.3.2 image (`release-notes.md` "Rollback or fallback notes").

## References

- Release notes: `release-notes.md`; checklist: `release-checklist.md`; docs decisions: `docs-impact.md`.
- Prod pre-deploy checks: `docs/superpowers/plans/2026-11-21-dar-be-318-03b2b-followups.md` items 6-11.
- Backlog: DAR-REL-007 (next-release checklist), DAR-BE-318, DAR-BE-328.
