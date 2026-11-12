# Darpan 2.1.12

Release date: 2026-11-02 (tagged; not deployed)

## Scope note

- **A hotfix on the 2.1 line for sm-darpan: DAR-BE-178.** On Nov 2, the Steve Madden order sync audit
  (`order-sync-month`, DAR-CLIENT-006) could not play October: a play could only take days in the current month, so
  after a month ended its days were neither offered nor accepted ("2026-10 has ended; pick days in 2026-11").
- Cut from `v2.1.11` with one cherry-pick. Nothing else from `main` ships.

## Repo targets

| Repo | From | To | Content |
| --- | --- | --- | --- |
| `drpn-ai/darpan` | `v2.1.11` | `v2.1.12` | `69a95cb` (DAR-BE-178) |
| `drpn-ai/darpan-docker-config` | `v2.1.11` | `v2.1.12` (prod pin moves) | |
| `drpn-ai/darpan-ui`, `shopify-darpan`, `darpan-hotwax`, `netsuite-darpan`, `database-darpan`, `moqui-gql` | unchanged | not re-cut | |

## User-visible changes

- **An audit can play the month that just ended.** On the run page, Previous Month is offered and runs; its findings
  are recorded in that month's report. Days that cross two months run once per month, each under its own. A month
  ended before the previous one still cannot be played.

## Operator-visible changes

- `get#AuditSettings.playableFrom` is the first day of the period just ended (the tenant's go-live when later), not the
  current period's first day.
- `run#Audit` accepts days in the period just ended. The audit ledger (`record#CheckRun`, `record#CheckFindings`,
  `close#ResolvedFindings`) accepts that period too. Morning runs are unchanged: they owe the current period only.

## Upgrade data

- None. `data/upgrade-data.xml` holds no records; `loadDarpanUpgradeData` is a no-op for this release.
- The 2.1.11 load target stays archived, byte-identical to `v2.1.11`'s `data/upgrade-data.xml`, at
  `data/releases/2.1.11/upgrade-data.xml`.

## Verification

- Focused suites on the release line; counts in `release-checklist.md`.
- On `main`, dropping the ledger fallback turned `theMonthJustEndedStillRecordsWhenPlayed` red; restored, green.

## Deferred items

- On sm-darpan after deploy: `/audits/order-sync-month/run` offers Previous Month (Oct 1-31); play it and confirm the
  October report shows the run.
- Phase 2 of DAR-BE-178 (an ended month continuing on the morning schedule until a chosen date) is not built.

## Rollback or fallback notes

- Roll back by rebuilding from docker-config `v2.1.11`. October then cannot be played again; findings a played October
  already recorded stay in the October report.
