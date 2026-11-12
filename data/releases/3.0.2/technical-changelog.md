# Technical Changelog For Darpan 3.0.2

Release date: 2026-11-12

## Versioning decision

- Hotfix on the 3.0 line, cut from `main`. Facade contract 4 unchanged (`generateApiContract` moved only the version
  line; 243 methods, `methods.txt` identical); `component.xml` 3.0.0 -> 3.0.2.
- 3.0.1 skipped for darpan: darpan-docker-config `v3.0.1` (2026-11-12 14:20 IST) was an agent-only hotfix that kept
  `DARPAN_REF=v3.0.0`. `verify_release_pins.py` requires docker-config to carry `v<darpan version>`, so darpan 3.0.1
  would have matched a tag that pins 3.0.0.
- Components patch-bump on their own commits; database-darpan and moqui-gql have none and are not re-cut.
- Per the hotfix rule, 3.0.2 folds into the 3.0 line's one public release.

## Source ranges

| Repo | Range | Commits |
| --- | --- | --- |
| darpan | `v3.0.0..v3.0.2` | 8 (2 release-pack/test follow-ups to 3.0.0, 6 product) + the 3.0.2 version commit |
| darpan-ui | `v4.0.1..v4.0.2` | 2 + the 4.0.2 version commit |
| darpan-hotwax | `v1.0.0..v1.0.1` | 2 + version |
| shopify-darpan | `v0.9.0..v0.9.1` | 1 + version |
| netsuite-darpan | `v0.5.0..v0.5.1` | 1 + version |
| darpan-agent | `v0.1.1..v0.1.2` | 1 + version |
| darpan-docker-config | `v3.0.1..v3.0.2` | 1 (`ef08069`, AgentEnabled parameter) + the pin commit |

## Backend

### Added

- DAR-BE-245 (`2a0b7644`): `NsSuiteQlSourceQuery.omsShipGroupsField`; the NetSuite order read counts HotWax's
  completed ship groups against NetSuite's fulfillments per location (`omsShipGroupCheck`, `nsFulfillmentCount`,
  `omsShipGroupCount`, `omsShipGroupDetail`). darpan-hotwax `d43e0a6`: the item lookup returns `itemShipGroupByKey`,
  and `lookup#HotWaxOmsOrderStatuses withShipGroupFacilities` names each ship group's facility. netsuite-darpan
  `eb361e3`: `LINKED_LOCATIONS(ItemShip)` as `location^number`; facilities are asked only for orders with 2+ groups.
- DAR-BE-251: `itemQuantity` on HotWax OMS GraphQL orders (darpan-hotwax `6c230e1`, a derived field read only when a
  run's rule reads it; the DRAFT `OMS_ORDERS_GQL_V1` definition gets the same steps) and on Shopify orders
  (shopify-darpan `0624393`, `subtotalLineItemsQuantity`, no line-item children).
- DAR-BE-207 (darpan-agent `882d3b7`): `write#AuditRunWords`, one claude-haiku-5-5 call per run update (structured
  output, checked against the fact sheet; refusal, bad JSON or any exception = no words). Gate: deployment switch and
  the named tenant's `agentEnabled=Y`.

### Changed

- DAR-BE-250 (`85ed44c3`): earliest broken step wins in `AuditReportAssembler`, the by-order list and the run email;
  statements carry `step`; `OrderWorlds` joins findings by the typed order ids they hold.
- DAR-BE-248 (`a45f1ea8`): `RunNotificationVoice` never prints a rule's formula; unnamed rules pool into "Field
  mismatches: N".
- DAR-BE-246 (`cef3001f`): `AgentAccess.access` answers LIVE for a super-admin group member before any grant is read;
  `create#AgentAccessGrant` refuses a super admin.
- DAR-BE-207 (`857f0890`, `c4e98b25`): the run email uses darpan-ui's light-theme tokens; an empty writer answer is no
  words.

### Test only

- `15c8e86e`: `TenantOwnedEntityFilterContractTests.PENDING_NEXT_PACK` emptied (3.0.0 shipped those filters).
- `4361f754`: 3.0.0 release pack folds the 3.0 hotfixes.

## UI

- DAR-BE-250 (`fe2d5e9`): the audit board lists a finding's later-step findings under "Also on this order"; a
  statement's counts say how many sit under an earlier step.
- DAR-UI-111 (`451be0a`): the audit settings' "After each run" section picks the chat space (`runEmail.chatSpaceId`).
- Release: `package.json` / `package-lock.json` 4.0.2 (root keys only); contract copy at darpan 3.0.2.

## Data and configuration

- Upgrade data: none. Entity: one nullable column (added on startup).
- `darpan-docker-config` `docker/prod/Dockerfile`: `DARPAN_REF=v3.0.2`, `DARPAN_HOTWAX_REF=v1.0.1`,
  `SHOPIFY_DARPAN_REF=v0.9.1`, `NETSUITE_DARPAN_REF=v0.5.1`, `DARPAN_AGENT_REF=v0.1.2`; `DATABASE_DARPAN_REF=v0.3.0`
  and `MOQUI_GQL_REF=v1.0.0` unchanged.

## Validation and rollout notes

- Focused tests; counts in `release-checklist.md`.
- Rollout: AWS prod via `darpan-docker-config/aws/deploy.sh` (backend) and the darpan-ui Firebase deploy, together.

## References

- https://github.com/drpn-ai/darpan/compare/v3.0.0...v3.0.2
- https://github.com/drpn-ai/darpan-ui/compare/v4.0.1...v4.0.2
- https://github.com/drpn-ai/darpan-hotwax/compare/v1.0.0...v1.0.1
- https://github.com/drpn-ai/shopify-darpan/compare/v0.9.0...v0.9.1
- https://github.com/drpn-ai/netsuite-darpan/compare/v0.5.0...v0.5.1
- https://github.com/drpn-ai/darpan-agent/compare/v0.1.1...v0.1.2
- https://github.com/drpn-ai/darpan-docker-config/compare/v3.0.1...v3.0.2
