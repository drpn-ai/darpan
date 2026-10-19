# Upgrade Data Review For Darpan 2.0.0

## Scope

Generated from the generic seed files under `runtime/component/darpan/data/` over
`v1.5.1..b473ecf`. 80 records, a **delta** on 1.5.1, not a cumulative file. An environment that has
not loaded `data/releases/1.5.1/upgrade-data.xml` must load it first: this file restates
`SHOPIFY_RETURN_REFS`, which that pack also restates, and the other order leaves 1.5.1's values in
place.

The file was rebuilt on 2026-10-03 and extended by three later commits (`506da4b`, `fb6a4a1`,
`f112c78`). Because extension is how an upgrade file drifts from its seed, the whole file was
re-checked for this cut rather than trusted:

- `release_preflight.py generate` was run against a scratch clone, so it could not overwrite the
  hand-ordered file, producing a 188-record candidate from the full seed diff.
- Every candidate record keyed on its identifying attributes was looked up in the current file,
  then compared as a whole element, child content included.
- Result: **80 present and identical, 0 extra, 0 differing.** The other 108 candidates all come
  from the four `type="darpan-tenant-config"` files, which are excluded by design (see below).

`data/releases/1.5.1/upgrade-data.xml` is byte-identical to `git show v1.5.1:data/upgrade-data.xml`,
so the baseline this delta sits on is the real tagged file.

## Load order

Moqui's entity-facade loader inserts in document order and does not sort topologically, so a child
ahead of its parent fails with a raw `*_ibfk_*` error. The file is hand-ordered parent-first:

1. `EnumerationType DarpanConclusion`, then its `UNEXPLAINED` enumeration.
2. `AUT_STAT_NOT_RUN` (its type `AutomationExecStatus` shipped long ago).
3. The ten `DarpanSystemSource` endpoint enums (parents `OMS` / `SHOPIFY` / `NETSUITE` exist).
4. The twelve `SourceSystemConnector` rows (remotes `HOTWAX_ORDERS_API` / `SHOPIFY_REMOTE` exist).
5. The forty-two `SourceSystemConnectorField` rows, which reference their connector by `systemEnumId`.
6. The thirteen DAR-BE-076 `EntityFilter` rows, which reference only `DARPAN_ACTIVE_COMPANY_SCOPE`.

`release_preflight.py` sorts alphabetically and would break this order if used to regenerate the
file. Re-order by hand if it is ever rebuilt.

## Candidate records

| Group | Records | Source file | Effect |
| --- | --- | --- | --- |
| Conclusion catalog root | 2 | `ConclusionCatalogSeedData.xml` | `DarpanConclusion` type and `UNEXPLAINED`; needed before any tenant's conclusion rules can load. The file is `type="seed"`, which the docker entrypoint's by-type load never picks up, so this pack is the only way it reaches a deployed instance. |
| Flowchart status | 1 | `AutomationSeedData.xml` | `AUT_STAT_NOT_RUN`. `ReconciliationRunResult.statusId` references it, so flowchart runs fail without it. |
| Endpoint enums | 10 | `DarpanSystemSourceSeedData.xml` | Six new endpoints, plus four restated for DAR-BE-069 display names only. |
| Connectors | 12 | `SourceSystemConnectorSeedData.xml` | New: `OMS_GQL`, `OMS_RETURNS_GQL`, `OMS_ORDER_ITEMS`, `OMS_ORDER_LINE_UNITS`, `SHOPIFY_ORDER_LINE_UNITS`, `NETSUITE_SUITEQL`. Restated: `SHOPIFY` (gains `sourceFilters` and its evidence map), `SHOPIFY_RETURN_REFS` (return-status field swap, `lookupMaxIds` 10000 to 50000), and four rows for display names. |
| Rules-board fields | 42 | `SourceSystemConnectorFieldSeedData.xml` | New fields for the new endpoints and for Shopify orders, 31 relabelled fields (DAR-BE-069), the returns key choices (DAR-BE-071), and the `orderReturnStatus` field. |
| Tenant filters | 13 | `SecuritySeedData.xml` | DAR-BE-076: 13 more entities under `DARPAN_ACTIVE_COMPANY_SCOPE`. |

Every record is also present, unchanged, in its generic seed file, so a default deploy's
`darpan-seed` self-load and this file write the same values to the same primary keys.

## Modified records — why each is safe

- **Restated display rows** (four enums, four connectors, 31 fields) differ from 1.5.1 only in
  `description`, `endpointLabel` or `label`. Saved rules store field paths, never labels, so runs
  and automations are untouched; past run results keep the names they were stamped with.
- **`SHOPIFY`** gains `filterParameterName="sourceFilters"`. This is the one restatement that
  changes behaviour: saved source-filter rows on a Shopify-orders source, ignored until now, start
  applying. Checked before the load (below).
- **`SHOPIFY_RETURN_REFS`** swaps `returnWorkflowStatus` for `orderReturnStatus` in
  `keepFieldsBase`. The superseded field row is not deleted by a load; the forced `RETIRED_FIELDS`
  run removes it.
- **Returns key choices** change `isPrimaryIdCandidate`, read only by the key card. Runs already
  keyed on a dropped choice keep running.
- **Tenant filters** add enforcement, not data. They hide rows with no tenant stamp from tenant
  reads, which is why the stamp counts below come first.

## Deliberately absent

- The four `darpan-tenant-config` files: `GorjanaConclusionRulesData` (with 14 conclusion
  enumerations), `ShopifyNsBilledRuleSetData` (with 14), `TriSystemOrderRunsRuleSetData` and
  `OrderLineUnitRuleSetData`, 108 records in all. They are one client's configuration; an upgrade
  pack loads on every instance, sm-darpan included. Load them on the instance and tenant they
  belong to, catalog first.
- Connector repos: none changes seed data in its range.

## Recommended operator review

Before the load, on prod, UAT and sm-darpan:

1. Run the 15 NULL-stamp counts in the `upgrade-data.xml` header (expect 0). Non-zero on the
   filter, key-field or scope table: run `sweep#RuleChildTenantStamps` and recount. Non-zero
   elsewhere: report before loading.
2. List source-filter rows saved against a `SHOPIFY` source; they start applying.
3. Confirm the image was built from `darpan-docker-config v2.0.0` (all five components at their
   2.0.0-set tags). Against older connectors, new fields select data the extract does not carry,
   and every rule on them matches nothing.

Load with `./gradlew loadDarpanUpgradeData`. After the load, once per environment:
`admin.MigrationAdminServices.run#Migration migrationId=RETIRED_FIELDS force=true`, then
`run#PendingMigrations`.
