# Upgrade Data Review For Darpan 1.5.0

## Scope

- Backend compare range: `v1.4.0..HEAD`
- Data directory reviewed: `data`
- Generic source data files are the source of truth for release upgrade data.
- Every record below was extracted verbatim from a generic seed file and verified
  attribute-for-attribute against it; none was authored directly into `upgrade-data.xml`.
- Total records: **54** — 51 added, 3 modified (restatements of existing rows).

## Load order

Document order is the load order. Moqui's entity-facade loader does not topologically sort, so
three chains are hand-verified and re-checked programmatically:

1. `EnumerationType` before every `Enumeration` of that type.
2. A parent `Enumeration` before any `Enumeration` naming it in `parentEnumId` (a self-reference).
3. `Enumeration` before `SourceSystemConnector` before `SourceSystemConnectorField`.

`release_preflight.py` sorts alphabetically and breaks all three. If this file is ever regenerated,
re-order by hand and re-run the ordering check.

## Candidate records

### `data/SecuritySeedData.xml` — 9 record(s)

- 9 added

- **Added** `moqui.basic.EnumerationType` — `DarpanChatProvider`
- **Added** `moqui.basic.EnumerationType` — `DarpanSharedConfigType`
- **Added** `moqui.basic.Enumeration` — `CHAT_PROV_GOOGLE`
- **Added** `moqui.basic.Enumeration` — `CHAT_PROV_SLACK`
- **Added** `moqui.basic.Enumeration` — `SCFG_HOTWAX_OMS`
- **Added** `moqui.basic.Enumeration` — `SCFG_SHOPIFY_AUTH`
- **Added** `moqui.basic.Enumeration` — `SCFG_NS_AUTH`
- **Added** `moqui.basic.Enumeration` — `SCFG_NS_RESTLET`
- **Added** `moqui.security.ArtifactGroupMember` — `facade.AuthFacadeServices.change#ExpiredPassword`

### `data/ReconciliationCompareScopeFixtureData.xml` — 2 record(s)

- 2 modified

- **Modified** `moqui.basic.Enumeration` — `OMS`
- **Modified** `moqui.basic.Enumeration` — `SHOPIFY`

### `data/DarpanSystemSourceSeedData.xml` — 4 record(s)

- 1 modified, 3 added

- **Modified** `moqui.basic.Enumeration` — `OMS_TRANSFER_ORDERS`
- **Added** `moqui.basic.Enumeration` — `OMS_RECON_ORDERS`
- **Added** `moqui.basic.Enumeration` — `OMS_RETURNS`
- **Added** `moqui.basic.Enumeration` — `SHOPIFY_RETURN_REFS`

### `data/SourceSystemConnectorSeedData.xml` — 4 record(s)

- 4 added

- **Added** `darpan.reconciliation.SourceSystemConnector` — `DATABASE`
- **Added** `darpan.reconciliation.SourceSystemConnector` — `OMS_RECON_ORDERS`
- **Added** `darpan.reconciliation.SourceSystemConnector` — `OMS_RETURNS`
- **Added** `darpan.reconciliation.SourceSystemConnector` — `SHOPIFY_RETURN_REFS`

### `data/SourceSystemConnectorFieldSeedData.xml` — 29 record(s)

- 29 added

- **Added** `darpan.reconciliation.SourceSystemConnectorField` — `OMS|$.records[*].orderId`
- **Added** `darpan.reconciliation.SourceSystemConnectorField` — `OMS|$.records[*].orderName`
- **Added** `darpan.reconciliation.SourceSystemConnectorField` — `OMS|$.records[*].externalId`
- **Added** `darpan.reconciliation.SourceSystemConnectorField` — `OMS|$.records[*].grandTotal`
- **Added** `darpan.reconciliation.SourceSystemConnectorField` — `OMS|$.records[*].orderDate`
- **Added** `darpan.reconciliation.SourceSystemConnectorField` — `OMS|$.records[*].statusId`
- **Added** `darpan.reconciliation.SourceSystemConnectorField` — `OMS|$.records[*].salesChannelEnumId`
- **Added** `darpan.reconciliation.SourceSystemConnectorField` — `OMS|$.records[*].productStoreId`
- **Added** `darpan.reconciliation.SourceSystemConnectorField` — `OMS_RECON_ORDERS|$.records[*].orderId`
- **Added** `darpan.reconciliation.SourceSystemConnectorField` — `OMS_RECON_ORDERS|$.records[*].orderName`
- **Added** `darpan.reconciliation.SourceSystemConnectorField` — `OMS_RECON_ORDERS|$.records[*].externalId`
- **Added** `darpan.reconciliation.SourceSystemConnectorField` — `OMS_RECON_ORDERS|$.records[*].grandTotal`
- **Added** `darpan.reconciliation.SourceSystemConnectorField` — `OMS_RECON_ORDERS|$.records[*].orderDate`
- **Added** `darpan.reconciliation.SourceSystemConnectorField` — `OMS_RECON_ORDERS|$.records[*].statusId`
- **Added** `darpan.reconciliation.SourceSystemConnectorField` — `OMS_RETURNS|$.records[*].returnId`
- **Added** `darpan.reconciliation.SourceSystemConnectorField` — `OMS_RETURNS|$.records[*].shopifyReturnId`
- **Added** `darpan.reconciliation.SourceSystemConnectorField` — `OMS_RETURNS|$.records[*].externalId`
- **Added** `darpan.reconciliation.SourceSystemConnectorField` — `OMS_RETURNS|$.records[*].orderExternalId`
- **Added** `darpan.reconciliation.SourceSystemConnectorField` — `OMS_RETURNS|$.records[*].statusId`
- **Added** `darpan.reconciliation.SourceSystemConnectorField` — `OMS_RETURNS|$.records[*].entryDate`
- **Added** `darpan.reconciliation.SourceSystemConnectorField` — `OMS_RETURNS|$.records[*].returnTotal`
- **Added** `darpan.reconciliation.SourceSystemConnectorField` — `OMS_RETURNS|$.records[*].currencyUomId`
- **Added** `darpan.reconciliation.SourceSystemConnectorField` — `OMS_RETURNS|$.records[*].returnChannelEnumId`
- **Added** `darpan.reconciliation.SourceSystemConnectorField` — `SHOPIFY|$.records[*].id`
- **Added** `darpan.reconciliation.SourceSystemConnectorField` — `SHOPIFY|$.records[*].name`
- **Added** `darpan.reconciliation.SourceSystemConnectorField` — `SHOPIFY_RETURN_REFS|$.records[*].refundOrReturnId`
- **Added** `darpan.reconciliation.SourceSystemConnectorField` — `SHOPIFY_RETURN_REFS|$.records[*].refundOrReturnType`
- **Added** `darpan.reconciliation.SourceSystemConnectorField` — `SHOPIFY_RETURN_REFS|$.records[*].orderId`
- **Added** `darpan.reconciliation.SourceSystemConnectorField` — `SHOPIFY_RETURN_REFS|$.records[*].createdAt`

### `data/MigrationRegistrySeedData.xml` — 6 record(s)

- 6 added

- **Added** `darpan.migration.DarpanMigration` — `TENANT_NOTIF_SETTINGS`
- **Added** `darpan.migration.DarpanMigration` — `UNDECRYPTABLE_HOOKS`
- **Added** `darpan.migration.DarpanMigration` — `AUTOMATION_FILTERS`
- **Added** `darpan.migration.DarpanMigration` — `RULE_TENANT_STAMPS`
- **Added** `darpan.migration.DarpanMigration` — `ENDPOINT_ACCESS`
- **Added** `darpan.migration.DarpanMigration` — `RETIRED_FIELDS`

## Modified records — why each is safe

All three are create-or-update by primary key, so they rewrite only the named column on a row that
already exists in every deployed environment.

- `Enumeration OMS` and `Enumeration SHOPIFY` — description corrected to "HotWax" and "Shopify".
  Deployed databases hold the shouty `SHOPIFY`, which is what an operator reads wherever a system is
  named. The seed file has been correct for some time; only stored rows are stale. Future runs only:
  a run's file labels are stamped into its output document at execution time, so historical runs keep
  the name they were stamped with (decision, 2026-08-13).
- `Enumeration OMS_TRANSFER_ORDERS` — gains `parentEnumId="OMS"`, grouping it under HotWax in the
  two-step source picker. Shipped in 1.4.0 without the grouping.

## Deliberately absent

- **No cross-tenant sharing grant.** An earlier 1.5.0 draft pre-seeded a `ConfigTenantAccess` row
  between two named tenants. Dropped: it would bake production tenant and config ids into a release
  artifact, and a row naming the wrong tenant would silently widen access. Sharing is granted through
  the product, behind a two-sided tenant-admin gate.
- **No chat-webhook backfill.** Copying each space's legacy `googleChatWebhookUrl` into the
  provider-agnostic `webhookUrl` column is `migrate#ChatSpaceWebhookUrls`, now registered in the
  migration registry. The values are per-tenant secrets. It is optional for this release —
  `resolveWebhookUrl` falls back to the legacy column.
- **No `DarpanMigrationPrereq` rows.** The entity exists and the supervisor honours it; no migration
  in this release declares a prerequisite. When one does, its prereq rows must follow every
  `DarpanMigration` row.

## Recommended operator review

- Load with `./gradlew loadDarpanUpgradeData` (loads by type `darpan-seed`, no fixed location, so one
  run covers base seed plus every release payload idempotently).
- Then run `admin.MigrationAdminServices.run#PendingMigrations` once.
- Prerequisite: 1.4.0's upgrade data must be loaded first — this file is release-scoped.

## Verification performed

- XML well-formedness: parsed clean.
- Record count: 54, matching the sum of the per-file sections above.
- FK ordering: checked programmatically — no child precedes its parent on any of the three chains.
- Seed backing: 54/54 records matched a generic seed file attribute-for-attribute.
- External dependencies: the `OMS` and `SHOPIFY` `SourceSystemConnector` rows that the field rows
  reference are pre-existing in deployed environments (present since v1.0); every other parent is
  created earlier in this same file.
- **Not** verified by an actual load into a database.
