# Technical Changelog For Darpan 2.1.3

Release date: 2026-10-28

## Versioning decision

- Patch on the 2.1 line (Aditi, 2026-10-28: "Hotfix 2.1.3"). The facade contract is unchanged, so
  `x-darpan-contract-version` stays; `component.xml` 2.1.2 -> 2.1.3, darpan-hotwax 0.10.2 -> 0.10.3. Components with
  no commits since their 2.1.2-set tag keep it.
- Per the hotfix rule, 2.1.3 folds into the 2.1 line's one public release.

## Source ranges

| Repo | Range | Commits |
| --- | --- | --- |
| darpan | `v2.1.2..3daa790` | 3 |
| darpan-hotwax | `v0.10.2..ec09b77` | 2 |
| darpan-docker-config | `v2.1.2..267f8ab` | 0 (plus the 2.1.3 pin commit) |

## Backend

### Added

- DAR-BE-153 (darpan-hotwax `ec09b77`, darpan `3daa790`): `salesChannelEnumId` joins `OmsGqlQueries.HEADER_FIELDS`,
  so `orderGrainPagePlan` selects it when a filter or rule reads it instead of refusing the read; `OMS_GQL` connector
  field row `$.records[*].salesChannelEnumId` (label "Sales channel", sequence 12). The order-items (unit grain)
  read still refuses it: the shared orders read does not select it. Tests: a POS exclusion is selected and drops
  the POS order; the two refusal tests now use `netsuiteOrderId`; a wiring ratchet keeps the pill and the field set
  together.

### Changed

- DAR-BE-153 (darpan-hotwax `32e1b5c`): a read that needs only the transaction ids asks
  `paymentPreferences(first: 5) { manualRefNum }`; any other payment field keeps the full row. Slot cost is
  `1 + fields` (`slotCost()`); a row without a method no longer claims `paymentMethodTypeIds` "".
- DAR-BE-155 (darpan `bb64b03`, `c7f155e`): `reconcile#RuleSetCompareScope` returns `ruleDifferenceFields`
  (`RuleDiffSupport.fieldTally`); the saved-run and API-range automation notify maps carry it and
  `build#RunCompletedPayload` declares it. `RunNotificationVoice` states the differing field and, when both values are
  flags, codes or numbers, the values; 24 cause-guessing lines removed; Chat syntax stripped from rule labels.

## UI

- No change; darpan-ui `v3.1.2` stays. The "Sales channel" pill reaches the rules board from the connector field row.

## Data and configuration

- `data/upgrade-data.xml`: 1 `darpan-seed` record (`OMS_GQL` `salesChannelEnumId`), from
  `SourceSystemConnectorFieldSeedData.xml`.
- `darpan-docker-config` `docker/prod/Dockerfile`: `DARPAN_REF=v2.1.3`, `DARPAN_HOTWAX_REF=v0.10.3`; the other pins
  stay.

## Validation and rollout notes

- Focused tests only; counts in `release-checklist.md`.
- Rollout: sm-darpan first (Jenkins, `loadDarpanUpgradeData`), then the Steve Madden run change and a test run before
  syncing its automation.

## References

- https://github.com/drpn-ai/darpan/compare/v2.1.2...v2.1.3
- https://github.com/drpn-ai/darpan-hotwax/compare/v0.10.2...v0.10.3
- https://github.com/drpn-ai/darpan-docker-config/compare/v2.1.2...v2.1.3
