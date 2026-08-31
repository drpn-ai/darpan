# Upgrade Data Review For Darpan 1.5.1

## Scope

Generated from the generic seed files under `runtime/component/darpan/data/` over `v1.5.0..v1.5.1`.
Two records. Both were confirmed byte-identical to their generic source declarations before the pack
was cut, so a default deploy's `darpan-seed` self-load and this file write the same values for the
same primary keys and cannot disagree about them.

This pack is a **delta**. Unlike `1.5.0`'s, it is not cumulative and cannot be loaded standalone onto
an environment that never ran `1.5.0`: it restates a `SourceSystemConnector` row that `1.5.0`
creates. prod, UAT and sm-darpan all completed `1.5.0`'s load and migrations on 2026-08-27.

## Load order

Two chains from `1.5.0`'s header no longer apply here — no `EnumerationType`, no `Enumeration`, no
self-referencing `parentEnumId` rows are in this pack. One remains:

1. `SourceSystemConnector` before `SourceSystemConnectorField`.

Moqui's entity-facade loader inserts in **document order** and does not topologically sort, so a child
ahead of its parent fails with a raw `*_ibfk_*` error pointing nowhere near this file. The
`Enumeration SHOPIFY_RETURN_REFS` this connector needs shipped in `1.5.0` (`upgrade-data.xml:151` at
that tag) and exists in every environment that ran it, so no Enumeration row is carried here.

`release_preflight.py` sorts alphabetically and would break the remaining chain if this file is
regenerated. Re-order by hand afterwards.

## Candidate records

### `data/SourceSystemConnectorSeedData.xml` — 1 record

`SourceSystemConnector SHOPIFY_RETURN_REFS`, restated. Two columns change:

- `filterParameterName` added, value `sourceFilters`. This connector now carries tenant exclusion
  rules; the rules board reads this column to decide whether to offer the exclusion control at all,
  and the getter applies the rules client-side because Shopify's search syntax has no knowledge of
  them. The canonical `SHOPIFY` orders row still declares none and still means what it said.
- `keepFieldsBase` widened by `returnWorkflowStatus`. Without it the field is projected away before
  any rule sees it.

Every other column is restated unchanged because `createOrUpdate` writes whole rows.

### `data/SourceSystemConnectorFieldSeedData.xml` — 1 record

`SourceSystemConnectorField SHOPIFY_RETURN_REFS / $.records[*].returnWorkflowStatus`, new. Label
"Return workflow status", `sequenceNum=5`.

## Modified records — why each is safe

The connector restatement is create-or-update by primary key, so it changes only the two named
columns on a row that already exists in every deployed environment. It cannot orphan a child: the
`SourceSystemConnectorField` rows that FK it are matched on `systemEnumId`, which is unchanged.

The new field row is additive and has no dependants.

Neither record is read at compare time; both are read when the rules board renders and when the
getter builds its projection. A run in flight during the load is unaffected.

## Deliberately absent

- **No `DarpanMigration` row for `RETIRED_FIELDS`.** It shipped in `1.5.0` and is already Applied
  everywhere. Adding it again would be a no-op at best; the actual requirement is a *forced re-run*,
  which is an operator action, not data. See below.
- **No deletion of the old `$.records[*].returnStatus` pill.** A seed load never deletes. The removal
  is owned by `SourceSystemConnectorFieldWriteSupport.RETIRED_FIELDS` and executed by the migration.
- **No `Enumeration` rows.** Parents already exist; see Load order.

## Recommended operator review

1. Load the pack: `./gradlew loadDarpanUpgradeData`.
2. Force the retirement sweep, once per environment:
   `admin.MigrationAdminServices.run#Migration migrationId=RETIRED_FIELDS force=true`
   (or the Migrations screen's per-migration run with force set). Idempotent — a second run deletes
   nothing, and on an environment that never saw the few-hour-old original it is a no-op. Running it
   everywhere is the recommended default; telling the two cases apart from outside the database is
   not worth the time.
3. Confirm the rules board for Shopify Return References offers exactly one status pill, labelled
   "Return workflow status". Two pills means step 2 did not run.
4. Re-add any exclusion rule previously saved against `$.records[*].returnStatus`. It kept its stored
   expression and matches nothing after the rename. Values: `REQUESTED,OPEN`.
5. Confirm `shopify-darpan` is at `v0.6.1` or later on the same deployment. Against `v0.6.0` the pill
   selects a field the extract does not contain and every rule on it silently matches nothing.

## Verification performed

- `xml.dom.minidom` parse of `data/upgrade-data.xml` — OK.
- Record count asserted at 2.
- Both records diffed against their generic source declarations — byte-identical.
- `data/releases/1.5.0/upgrade-data.xml` confirmed byte-identical to `git show v1.5.0:data/upgrade-data.xml`,
  so the previous pack was archived faithfully and this delta's baseline is the real one.

## Not verified

- No load was executed against any database, local or remote. Ordering is argued from the loader's
  documented document-order behaviour and from `1.5.0`'s header, not observed.
- The forced `RETIRED_FIELDS` re-run has not been exercised on an environment where the migration is
  already Applied.
