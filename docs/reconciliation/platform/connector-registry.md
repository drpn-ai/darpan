# Source Connector Registry

API-backed compare sources dispatch through the `darpan.reconciliation.SourceSystemConnector`
registry instead of per-system code branches. One row per source endpoint names its extract
service, the config type it expects, its window parameters, and the optional lookup, exchange,
order-state and health-check services that verification and diagnostics call.

- Entity: `runtime/component/darpan/entity/ReconciliationEntities.xml` (`SourceSystemConnector`,
  plus `SourceSystemConnectorField` for rules-board field pills and `SourceConfigEndpointAccess`
  for which endpoints a config may use)
- Seed rows: `runtime/component/darpan/data/SourceSystemConnectorSeedData.xml` and
  `runtime/component/darpan/data/SourceSystemConnectorFieldSeedData.xml`
- Resolver: `runtime/component/darpan/src/main/groovy/darpan/reconciliation/automation/SourceSystemConnectorSupport.groovy`

## Seed a new connector

1. Add the extract service in the owning component. Its name must match the dispatch fence:
   `reconciliation.<Name>ExtractionServices.extract#<Noun>` (or `facade.<Name>FacadeServices.execute#<Noun>`).
   Lookup slots accept only `lookup#*`, and the health-check slot only `probe#*`.
2. Add one enabled `SourceSystemConnector` row to the seed file. Registering the row is what
   allow-lists its extract service; no core code change is needed.
3. Keep `extractServiceName` and `expectedSourceConfigType` unique across enabled rows. The
   resolvers take the first enabled match, so two rows sharing either value resolve ambiguously.
4. Add the row to the release's upgrade data so existing environments receive it.

## Add a field to the connector

A new registry column needs three edits, or it silently reads as null:

1. the field on `SourceSystemConnector` in `entity/ReconciliationEntities.xml`;
2. the value on the seed rows in `data/SourceSystemConnectorSeedData.xml` (and upgrade data);
3. the key in `SourceSystemConnectorSupport.toConnectorMap`, which whitelists what callers see.

The original knowledge-transfer plan for the registry is archived at
[archive/reconciliation/platform/connector-registry-kt.md](../../archive/reconciliation/platform/connector-registry-kt.md).
