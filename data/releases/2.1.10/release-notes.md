# Darpan 2.1.10

Release date: 2026-10-31 (tagged; not deployed)

## Scope note

- **A patch on the 2.1 line: DAR-BE-168.** On sm-darpan, saving a conclusion tree on Steve Madden's `RS_PROD_ORDERS`
  answered a 500: its compare scope predates `scopeMode`, and an empty mode reached the condition builder as a null
  boolean. It blocks the reasons behind the Steve Madden order sync audit (DAR-CLIENT-006).
- Cut from `v2.1.9` with one cherry-pick. Nothing else from `main` ships.

## Repo targets

| Repo | From | To | Content |
| --- | --- | --- | --- |
| `drpn-ai/darpan` | `v2.1.9` | `v2.1.10` | `a2fae42` (DAR-BE-168) |
| `drpn-ai/darpan-docker-config` | `v2.1.9` | `v2.1.10` (prod pin moves) | |
| `drpn-ai/darpan-ui`, `shopify-darpan`, `darpan-hotwax`, `netsuite-darpan`, `database-darpan`, `moqui-gql` | unchanged | not re-cut | |

## User-visible changes

- **Reasons save on older runs.** A run whose compare scope has no mode is treated as the two-system compare it is,
  so its conclusion tree saves.

## Operator-visible changes

- None.

## Upgrade data

- None. `data/upgrade-data.xml` holds no records; `loadDarpanUpgradeData` is a no-op for this release.
- The 2.1.9 load target stays archived, byte-identical to `v2.1.9`'s `data/upgrade-data.xml`, at
  `data/releases/2.1.9/upgrade-data.xml`.

## Verification

- Focused suites on the release line; counts in `release-checklist.md`.
- The new test (a scope with no mode) failed first with sm-darpan's exact error, then passed with the fix.

## Deferred items

- On sm-darpan after deploy: save the order sync reasons on `RS_PROD_ORDERS` and run the audit once.

## Rollback or fallback notes

- Roll back by rebuilding from docker-config `v2.1.9`. Conclusion trees then fail to save on scopes with no mode;
  setting the scope's `scopeMode` to `COMPARE` works around it.
