# Darpan 3.4.1

Release date: 2026-11-23

## Scope note

- **A hotfix on the 3.4 line** (Aditi, 2026-11-23 14:28 IST: "we kept the automation run separate for a reason... in
  case someone moves the source around the automation shouldnt be affected"; 14:35 "native, push and deploy as a
  hotfix"). DAR-BE-338 undoes DAR-BE-318 plan 3b-2c's filter half: an automation runs on its own copy of its run's
  filters again. Publicly it folds into the 3.4 notes (one `updates.mdx` section for the line).
- Facade contract stays at **5**: no method added or removed. `save#Automation` takes `sources[].excludeFilters` again
  (optional), and `get#Automation` sources return it.

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.4.0` | `v3.4.1` |
| `drpn-ai/darpan-docker-config` | `v3.4.0` | `v3.4.1` (only the darpan pin moves) |
| `darpan-ui` | `v4.4.1` | `v4.4.2` |
| `darpan-hotwax` | `v1.3.0` | `main` only, no tag: a test and a doc line, no runtime change; the image keeps `v1.3.0` |
| `drpn-ai/darpan-cli` | `v0.2.4` | `v0.2.5` |
| moqui-rules, moqui-jexl, shopify-darpan, netsuite-darpan, database-darpan, darpan-agent, moqui-gql | unchanged | 3.4.0 pins |

## User-visible changes

- An automation runs on its own copy of its saved run's filters, as it does its sources. A filter edit on the run
  reaches the automation's scheduled and audit runs only after **Sync**; the automation names the side whose filters
  differ, and Sync copies them. (3.4.0 had every automation read its run's filters live.)
- The agent's exclusion change syncs each automation of the run again, and is refused when one of them already differs
  from its run.
- A config bundle or version saved on 3.4.0 carries no automation filters; importing or restoring it gives each
  automation its run's filters. No restore or import is refused with `AUTOMATION_FILTERS_DIFFER` any more.

## Operator-visible changes

- New migration `AUTOMATION_FILTER_COPY` (sequence 160, dry run supported): on an instance where 3.4.0's
  `AUTOMATION_FILTER_TABLE` ran, it copies each automation side's filters back from its run's. It fills only sides with
  no rows, for the automation's own tenant; elsewhere it writes nothing. **Until it runs, those automations run
  unfiltered**: stop the scheduler across the deploy and this migration.
- `AUTOMATION_FILTER_TABLE` is retired: its service is gone and the upgrade data parks its row (`enabled="N"`), so an
  instance upgrading from before 3.4.0 keeps its automation filter table.

## Upgrade data

- `data/upgrade-data.xml`: the `AUTOMATION_FILTER_COPY` registry row, the `AUTOMATION_FILTER_TABLE` hand row
  (`enabled="N"`), the automation filter tenant filter (already present on instances that ran 3.4.0), and the mascot
  content refresh (one hint changed: Sync replaces the source setup and filters). The boot's `darpan-seed` load applies
  it; then run the pending migrations.

## Verification

- Focused backend classes from `cleanTest` (478 tests, 0 failures), darpan-hotwax parity 10/10, UI specs, type-check and
  lint, CLI 110/110 and 24/24; whole-change review with its fixes. See `release-checklist.md`.
- After deploy: `AUTOMATION_FILTER_COPY` dry run then run (rowsWritten >= 80), MALBON 100513 FILE_1 carries
  productId EXCLUDE_IN 142036, scheduler back on.

## Deferred items

- Key fields and record root still come from the run's compare scope (as before DAR-BE-318): isolating them is Aditi's
  call, not in this hotfix.
- A 3.4.0 config version of a run that already had several scopes, restored after its scopes changed, can take a
  sibling scope's filters (scope placement from 3.4.0).

## Rollback or fallback notes

- After `AUTOMATION_FILTER_COPY` has written rows, a 3.4.0 image fails automation save, sync and delete on the table's
  foreign key (3.4.0 does not clear the rows). Before rolling back to 3.4.0, delete the rows of
  `RECONCILIATION_AUTOMATION_SOURCE_FILTER`; 3.4.0 reads its run's filters anyway.
- Before the copy runs, rolling back needs nothing: the table is empty.
