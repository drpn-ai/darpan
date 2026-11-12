# Darpan 3.0.3

Release date: 2026-11-12

## Scope note

- **A hotfix on the 3.0 line** carrying DAR-BE-254 (audit findings follow a check that moved to another statement;
  "Also on this order" names the item), DAR-UI-112 (Month to Date run window) and DAR-BE-251's rules-board fields.
  Aditi, 2026-11-12: "hotfix and deploy".
- Only darpan and darpan-ui have commits since 3.0.2; every other component stays on its 3.0.2 tag.
- Facade contract unchanged (4); `component.xml` 3.0.2 -> 3.0.3.
- No deferrals: everything on `main` in both repos ships.

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.0.2` | `v3.0.3` |
| `drpn-ai/darpan-ui` | `v4.0.2` | `v4.0.3` |
| `drpn-ai/darpan-docker-config` | `v3.0.2` | `v3.0.3` (only `DARPAN_REF` moves) |
| `drpn-ai/darpan-hotwax`, `shopify-darpan`, `netsuite-darpan`, `darpan-agent`, `database-darpan`, `moqui-gql` | `v1.0.1`, `v0.9.1`, `v0.5.1`, `v0.1.2`, `v0.3.0`, `v1.0.0` | not re-cut (no commits) |

All tags are cut from `main`.

## User-visible changes

### Audits

- **A check moved to another statement takes its open findings with it.** When an audit's check moves from one
  statement to another, its open findings move too, with their history, instead of staying open under the old
  statement and opening again under the new one. A further copy left behind closes as "Moved to Rn with its check;
  reported there." (DAR-BE-254). On RAILS, the ship-group check moved from R8 to R10 and its 14 orders were showing
  under both. The R8 copies close on the first run after this release.
- **"Also on this order" names the item first**, either the SKU or the line, then the cause, then the statement
  (DAR-BE-254).

### Runs

- **The third run-window card is Month to Date.** It covers the 1st of yesterday's month through yesterday; on the 1st
  of a month it covers the whole month just ended. It replaces Previous Month on the audit, lifecycle and Sync run
  starts (DAR-UI-112).

### Reconciliation

- **The rules board lists Item quantity on both sides** (Shopify and HotWax GraphQL), the fields that the daily order
  runs' Item Sync rule compares (DAR-BE-251).

## Operator-visible changes

- No schema change, no new job, no new configuration.
- Two `SourceSystemConnectorField` rows (seed); they load on boot (see Upgrade data).

## Upgrade data

- `data/upgrade-data.xml` holds 2 `darpan-seed` records, both from `data/SourceSystemConnectorSeedDataFields.xml`
  (`v3.0.2..HEAD`): `OMS_GQL` and `SHOPIFY` `$.records[*].itemQuantity`, "Item quantity".
- The 3.0.2 load target is archived, byte-identical to `v3.0.2`'s, at `data/releases/3.0.2/upgrade-data.xml`.
- **No manual load.** Prod boots with `DARPAN_LOAD_UPGRADE_DATA=Y` and upserts every `darpan-seed` file, so the two
  rows land when the new task starts.

## Verification

- Backend (JDK 21, `--rerun`, fresh result XMLs, 2026-11-12 22:10 IST): `AuditLedgerServicesSmokeTests` 13/13,
  `AuditReportAssemblerSmokeTests` 26/26, `ApiContractGeneratorTests` 8/8, `FacadeContractSnapshotTests` 1/1.
- UI: type-check exit 0; eslint on the changed sources exit 0; vitest on the `runWindow`, `AuditRunStartPage`,
  `ReconciliationDiffPage`, `RunFlowchartStartPage`, `AuditReportPage` and `registryContract` specs, 108/108.
- Not run: the full suites (the focused-tests rule).
- Unverified until after the deploy: the R8 copies closing on the next RAILS run; the item showing in "Also on this
  order"; the Month to Date card on app.drpn.ai.

## Deferred items

- None in scope. Open items stay on their backlog items: DAR-BE-252 (guard against an all-Unexplained flood) and the
  R5 causes.

## Rollback or fallback notes

- Roll back with `deploy.sh prod --ref v3.0.2` and the 4.0.2 hosting release. The two seed rows are harmless to 3.0.2.
- A finding that `record#CheckFindings` re-homed stays under its new statement after a rollback. 3.0.2 reads it as an
  ordinary open finding.
