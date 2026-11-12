# Technical Changelog For Darpan 2.1.10

Release date: 2026-10-31

## Versioning decision

- Patch on the 2.1 line, cut from `v2.1.9` with one cherry-pick. Facade contract unchanged (`openapi.json` version
  line only); `component.xml` 2.1.9 -> 2.1.10. No connector or UI change.
- Per the hotfix rule, 2.1.10 folds into the 2.1 line's one public release.

## Source ranges

| Repo | Range | Commits |
| --- | --- | --- |
| darpan | `v2.1.9..ce94a10` | 1, cherry-picked from `main` `a2fae42` (plus the 2.1.10 version commit) |
| darpan-docker-config | `v2.1.9..` | 0 (plus the 2.1.10 pin commit) |

## Backend

### Fixed

- DAR-BE-168 (darpan `ce94a10`): `RunConclusionAuthoringSupport.save` read
  `boolean evaluate = (scope.scopeMode as String)?.equalsIgnoreCase("EVALUATE")`; on a scope with no `scopeMode` the
  safe-navigation left null in the declared boolean and `conditionsOf(ec, String, List, boolean)` had no match
  ("No signature of static method"). Now `"EVALUATE".equalsIgnoreCase(scope.scopeMode as String)`: no mode is a
  two-system compare. Test `RunConclusionAuthoringSmokeTests.aScopeWithNoModeIsATwoSystemRun`.

## UI

- No change.

## Data and configuration

- Upgrade data: none.
- `darpan-docker-config` `docker/prod/Dockerfile`: `DARPAN_REF=v2.1.10`; the other pins unchanged.

## Validation and rollout notes

- Focused tests; counts in `release-checklist.md`.
- Rollout: sm-darpan (Jenkins), then save the order sync reasons on `RS_PROD_ORDERS`.

## References

- https://github.com/drpn-ai/darpan/compare/v2.1.9...v2.1.10
- https://github.com/drpn-ai/darpan-docker-config/compare/v2.1.9...v2.1.10
