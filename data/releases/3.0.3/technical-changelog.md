# Technical Changelog For Darpan 3.0.3

Release date: 2026-11-12

## Versioning decision

- Patch: a hotfix on the 3.0 line. Facade contract 4 unchanged (`generateApiContract` moved only the version line;
  243 methods, `methods.txt` identical); `component.xml` 3.0.2 -> 3.0.3.
- Under the hotfix rule, 3.0.3 folds into the 3.0 line's single public release.

## Source ranges

| Repo | Range | Commits |
| --- | --- | --- |
| darpan | `v3.0.2..v3.0.3` | 2 (`308f9653`, `e995c406`) + the 3.0.3 version commit |
| darpan-ui | `v4.0.2..v4.0.3` | 2 (`42c844b`, `86f3c0e`) + the 4.0.3 version commit |
| darpan-docker-config | `v3.0.2..v3.0.3` | the pin commit (`DARPAN_REF=v3.0.3`) |

## Backend

### Added

- `SourceSystemConnectorField` rows `OMS_GQL` / `SHOPIFY` `$.records[*].itemQuantity` "Item quantity", so the rules
  board offers the field that DAR-BE-251's Item Sync rule compares (`308f9653`).
- `AuditReportAssembler.itemOf`: each entry waiting under an earlier statement carries `item` (the record's SKUs, else
  its line ids) (`e995c406`).

### Changed

- `record#CheckFindings` (`service/audit/AuditLedgerServices.xml`): an open finding on the check's record key that sits
  under a statement this check no longer tests is re-homed to the check's current statement, history kept. Any further
  copy closes with "Moved to Rn with its check; reported there." (`e995c406`).

### Fixed

- RAILS R8/R10: the ship-group check moved R8 -> R10 (DAR-BE-253), and its 14 orders were open under both statements.
  The re-home above closes the R8 copies on the next run.

### Security

- None.

## UI

### Added

- `AuditWaitingFinding.item` (`auditTypes.ts`); `AuditColumns.vue` "Also on this order" rows render item, then cause,
  then statement (`86f3c0e`).

### Changed

- `runWindow.ts`: the third preset is Month to Date (the 1st of yesterday's month through yesterday; on the 1st, the
  whole month just ended), replacing Previous Month on the audit, lifecycle and Sync run starts (`42c844b`).

### Fixed

- None beyond the above.

### Security

- None.

## Data and configuration

- 2 seed rows, type `darpan-seed`. Prod loads them on boot (`DARPAN_LOAD_UPGRADE_DATA=Y`).
- No schema, job, remote, auth or runtime-config change. docker-config moves only `DARPAN_REF`.

## Validation and rollout notes

- Deploy the backend first, then the UI. 4.0.3 reads `item` only when present, so it also works against 3.0.2.
- Test harness note: when `AuditLedgerServicesSmokeTests` and `AuditReportAssemblerSmokeTests` run in one Gradle
  invocation, the second class's `@AfterAll` hits an NPE in Moqui's `ExecutionContextFactoryImpl.destroy`
  (`asi.curHitBin`, the artifact stats already torn down). The 26 tests themselves pass. Run alone, the class is 26/26
  and exits 0. This is pre-existing teardown behaviour and is not in the shipped code.
- Compare: https://github.com/drpn-ai/darpan/compare/v3.0.2...v3.0.3 ·
  https://github.com/drpn-ai/darpan-ui/compare/v4.0.2...v4.0.3 ·
  https://github.com/drpn-ai/darpan-docker-config/compare/v3.0.2...v3.0.3

## References

- Keep a Changelog: https://keepachangelog.com/en/1.1.0/
- Semantic Versioning: https://semver.org/
