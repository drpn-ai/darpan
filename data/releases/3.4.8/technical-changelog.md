# Darpan 3.4.8 Technical Changelog

## Versioning decision

- Patch on the 3.4 line: no facade method removed or field changed (contract stays 5), GraphQL unchanged.

## Source ranges

- darpan `v3.4.7..v3.4.8`, darpan-hotwax `v1.3.3..v1.3.4`, darpan-docker-config `v3.4.7..v3.4.8`.

## Backend

### drpn-ai/darpan `v3.4.7..v3.4.8`

- `a788295d` DAR-BE-368: `SourceSystemConnectorSeedData.xml` SHOPIFY row declares `extractOptionsJson`
  `[{"name":"withLineItems","label":"Order lines","type":"boolean"}]`; `ShopifyLineItemsOptionTieTests`.
- `bba089c0` 3.4.7 pack completed after its tag.
- Release commit: component version 3.4.8, `openapi.json` version 3.4.8, upgrade data (1 record), this pack.

### drpn-ai/darpan-hotwax `v1.3.3..v1.3.4`

- `690acd8` DAR-BE-368: OMS_GQL connector declares `withOrderItems` ("Order items"); `extract#HotWaxOmsGqlOrders` takes it;
  `OmsExtractSupport.readFieldsWithOptions` adds `orderItems` to the read fields when on; `OrderItemsOptionTieTests`.
- Release commit: component version 1.3.4.

## UI

- None: the run editor offers a connector's declared options (DAR-UI-064); app stays `v4.4.10`.

### drpn-ai/darpan-docker-config `v3.4.7..v3.4.8`

- `docker/prod/Dockerfile`: `DARPAN_REF v3.4.8`, `DARPAN_HOTWAX_REF v1.3.4`.

## Data and configuration

- Upgrade data: 1 record (the SHOPIFY connector row); the OMS_GQL row loads from darpan-hotwax's seed on every boot.

## Validation and rollout notes

- Focused tests and gates: `release-checklist.md`. Rollout: docker-config `v3.4.8` prod image via the deployer; no app deploy.

## References

- Backlog: DAR-BE-368.
