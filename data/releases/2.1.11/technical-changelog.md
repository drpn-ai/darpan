# Technical Changelog For Darpan 2.1.11

Release date: 2026-10-31

## Versioning decision

- Patch on the 2.1 line, cut from `v2.1.10` with three cherry-picks. Facade contract: one added optional input
  (`save#RunConclusions.lookups`), not breaking; `component.xml` 2.1.10 -> 2.1.11. No connector or UI change.
- Per the hotfix rule, 2.1.11 folds into the 2.1 line's one public release.

## Source ranges

| Repo | Range | Commits |
| --- | --- | --- |
| darpan | `v2.1.10..` | 3, cherry-picked from `main` `923f6f8`, `2bd2c18`, `3838cec` (plus the 2.1.11 version commit) |
| darpan-docker-config | `v2.1.10..` | 0 (plus the 2.1.11 pin commit) |

## Backend

### Fixed

- DAR-BE-172: `AuditSettingsSupport.run` made the audit's automations in the request transaction and handed each to
  RunAdmission, whose thread started `execute#Automation` before that transaction committed ("Automation N not
  found", logged only). The automations are now made and read in their own transaction
  (`AutomationRuntimeSupport.runInNewTransaction`) before submission. Test
  `AuditSettingsSmokeTests.anAuditsFirstPlayRunsTheAutomationItMakes`.

### Added

- DAR-BE-174: `ConclusionLookupSupport.collect` files a `many` lookup's records as a list per key, so a dotted
  condition (`items.statusId`) fans out across them; `parse` refuses a non-boolean `many`. `RunConclusionStep.fetchLookup`
  uses it. `RunConclusionAuthoringSupport.get` returns `lookups`; `save` replaces them when given and validates each
  (parts present, one per name, the system's extract takes `lookupKeys`).
- Security review: a lookup's `parameters` reach the extract only as `keepRecordFields` (field names);
  `requireSafeParameters` refuses anything else on save.

## UI

- No change.

## Data and configuration

- Upgrade data: none.
- `darpan-docker-config` `docker/prod/Dockerfile`: `DARPAN_REF=v2.1.11`; the other pins unchanged.

## Validation and rollout notes

- Focused tests; counts in `release-checklist.md`.
- Rollout: sm-darpan (Jenkins), then the order sync reasons and lookup on `RS_PROD_ORDERS`.

## References

- https://github.com/drpn-ai/darpan/compare/v2.1.10...v2.1.11
- https://github.com/drpn-ai/darpan-docker-config/compare/v2.1.10...v2.1.11
