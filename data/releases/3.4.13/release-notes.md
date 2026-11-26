# Darpan 3.4.13

Release date: 2026-11-26

## Scope note

- **A hotfix on the 3.4 line, cut from all of darpan `main`** (Aditi, 2026-11-26 13:00 IST "All of main" for the 3.4.13
  hotfix, after the 3.4.12 browser look found R13's HotWax record never reached a finding). It carries the DAR-BE-372 and
  DAR-BE-375 fixes and DAR-CLI-008 part 3 (order lookup), which the DAR-CLI-008 session confirmed complete. App `v4.4.14`
  deploys after it. Publicly it folds into the 3.4 notes.
- **Upgrade data**: 2 records, loaded on boot (the tenant filters of the two lookup tables). No migration.
- Facade contract stays at **5**: six methods added (243 public). GraphQL schema unchanged.

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.4.12` | `v3.4.13` |
| `drpn-ai/darpan-hotwax` | `v1.3.4` | `v1.3.5` (tagged by the DAR-CLI-008 session) |
| `drpn-ai/shopify-darpan` | `v0.11.2` | `v0.11.3` (tagged by the DAR-CLI-008 session) |
| `drpn-ai/netsuite-darpan` | `v0.7.1` | `v0.7.2` (tagged by the DAR-CLI-008 session) |
| `drpn-ai/darpan-docker-config` | `v3.4.12` | `v3.4.13` (four pins move) |
| `drpn-ai/darpan-ui` | `v4.4.13` | `v4.4.14` (cut with 3.4.12, deployed after 3.4.13) |
| database-darpan, darpan-agent, moqui-rules and the framework pins | unchanged | unchanged |

## User-visible changes

- **A finding keeps the record its check looked up in another system** (DAR-BE-372): R13 (cancelled in Shopify, not in
  HotWax) keeps the HotWax order its rules read, so the card says HotWax's status. 3.4.12 wrote the lookup's answer onto
  the result row, but the audit read rows field by field and dropped it. A NetSuite line's finding shows its HotWax ship
  method.
- **Re-check all findings reports its progress again** (DAR-BE-375): since 3.4.10 the page's progress read refused the
  time it sent back (epoch milliseconds) and showed "No signature of method ... progress"; the re-check itself ran.
- **Follow an order through every connected system** (DAR-CLI-008 part 3), for CLI `0.5.0` (released after this deploy):
  `lookup#Order` finds an order id in Shopify, HotWax and NetSuite through read-only fresh queries on the tenant's own
  connections and follows it, with its open findings; customer details only when asked. The reads and links are data a
  tenant can override, add to or hide.

## Operator-visible changes

- Settings `darpan.lookup.maxReads` (30) and `darpan.lookup.timeoutSeconds` (60, at most 240).
- A tenant value shape runs against a 100 ms budget and the lookup's deadline; a read whose shape fails once is skipped for the rest of the lookup, and a save refuses a nested-quantifier shape.

## Upgrade data

- `data/upgrade-data.xml`: 2 `moqui.security.EntityFilter` records (`DARPAN_SCOPE_TENANT_LOOKUP_READ`,
  `DARPAN_SCOPE_TENANT_LOOKUP_LINK`) from `data/SecuritySeedData.xml`, loaded on boot. The previous is archived at
  `data/releases/3.4.12/upgrade-data.xml` (equals the `v3.4.12` tag's).

## Verification

- `release-checklist.md`.

## Deferred items

- DAR-CLI-009 (named query commands); DAR-BE-370 (a finding's Shopify status from the morning's other Shopify reads).
- The mascot hint gaps and stale mascot seed that darpan-ui's specs report (pre-existing since app 4.4.11).

## Rollback or fallback notes

- Roll back by redeploying `v3.4.12` (docker-config `v3.4.12`, image `prod-v3.4.12-202611260650-5254685`). The two lookup
  tables and filters stay and are unused; R13 findings recorded under 3.4.13 keep their HotWax record.
