# Technical Changelog For Darpan 2.1.12

Release date: 2026-11-02

## Versioning decision

- Hotfix on the 2.1 line, cut from `v2.1.11` with one cherry-pick. Facade contract: no field change (two descriptions
  reworded), not breaking; `component.xml` 2.1.11 -> 2.1.12. No connector or UI change.
- Per the hotfix rule, 2.1.12 folds into the 2.1 line's one public release.

## Source ranges

| Repo | Range | Commits |
| --- | --- | --- |
| darpan | `v2.1.11..` | 1, cherry-picked from `main` `69a95cb` (plus the 2.1.12 version commit) |
| darpan-docker-config | `v2.1.11..` | 0 (plus the 2.1.12 pin commit) |

## Backend

### Fixed

- DAR-BE-178: a play could not take days in the period just ended. `AuditSettingsSupport.playableFrom` was the current
  period's first day and `AuditAutomationSupport.pickedWindows` refused any day before it; the period extension that
  covered this (`AuditPeriodExtension`) was deleted in DAR-BE-128 because its button was never built.
  - `AuditReportAssembler.periodWindow` carries `recordable` (the period's own span, or the whole following period);
    `recordableFrom(day, periodCode)` is the first day of the period just ended.
  - `resolveCheckRun` falls back to a recordable period, so the ledger takes a played run of the month just ended.
  - `pickedWindows` splits picked days into one window per period again, each recorded under its own period; go-live
    (DAR-BE-145) applies per window.
  - Tests: `AuditAutomationSmokeTests.picksDaysAreSplitByPeriodAndKeepTheirOwnDates`,
    `nothingBeforeTheTenantsGoLiveIsRead`; `AuditReportAssemblerSmokeTests.theMonthJustEndedStillRecordsWhenPlayed`,
    `anEndedPeriodStopsAndKeepsWhatItHad` (refusal now two periods on); `AuditSettingsSmokeTests` (playableFrom, the
    ended-period refusal two months back).

## UI

- No change. The run page offers what `playableFrom` allows; darpan-ui `bf94d74` on `main` only rewords a comment.

## Data and configuration

- Upgrade data: none.
- `darpan-docker-config` `docker/prod/Dockerfile`: `DARPAN_REF=v2.1.12`; the other pins unchanged.

## Validation and rollout notes

- Focused tests; counts in `release-checklist.md`.
- Rollout: sm-darpan (Jenkins), then Previous Month on `/audits/order-sync-month/run`.

## References

- https://github.com/drpn-ai/darpan/compare/v2.1.11...v2.1.12
- https://github.com/drpn-ai/darpan-docker-config/compare/v2.1.11...v2.1.12
