# Technical changelog: Darpan 3.4.5

Compare ranges: darpan `v3.4.4..v3.4.5`, darpan-hotwax `v1.3.0..v1.3.1`.

## darpan

- `fa4fcd34` DAR-BE-353 — `AuditSettingsSupport.reasonsOf` reads the data and passes it to a Moqui-free `reasonsFrom`;
  `checksByStatement` carries each link's `linkDiffTypes` / `linkRuleIds`. A link's `diffTypes` map to buckets through
  `RunConclusionSupport.bucketOf` (made public, unchanged); a node's bucket is its own or its nearest ancestor's;
  FINDING nodes reach every check of an EVALUATE run; a blank `diffTypes` reaches every node. HIDE / EXPECTED are read
  only on reached nodes; `reasonClosesEverywhere` uses the narrowed list. Output shape unchanged. Tests:
  `AuditReasonsReachTests` (new, no Moqui) 9/9, `AuditSettingsSmokeTests` 20/20.
- `3f517082` DAR-BE-354 — `BrainFactAdapter.recordFieldsFor("SHOPIFY_ORDER")` keeps `createdAt`; the answered HotWax
  order is aged from it to `readAtMillis` with the shared `setAge` / `epochMillis`. Tests: `BrainFactAdapterTests`
  11/11 (2 h -> 2.00, 11 min -> 0.18; no/invalid date -> no age), `ShopifyInNetsuitePackTests` 6/6,
  `InFlightToNetsuitePackTests` 5/5.
- `cf3e450b` DAR-UI-141 — `MoquiConf.xml` cors-preflight `Access-Control-Allow-Headers` restates the framework list and
  adds `X-Darpan-Tenant`; `CliLoginSmokeTests` guards one header, the tenant header, and every framework header kept.
- `83fbeb84`, `d52d38f6`, `6c0056a6`, `b1d8fe38`, `cd703e78` — `AppContentSeedData.xml` regenerations for DAR-UI-140/141
  (the DAR-UI-141 lines reverted with the app change); `AppContentRulesTests` sample table.
- `5832eb5c` — 3.4.4 pack completed after its tag.

## darpan-hotwax

- `a2887a6` DAR-BE-345 option B — OMS_GQL connector `extractOptionsJson` offers `keepExchangeOrders` (boolean); its
  built-in exchange Keep line gains `appliesUnless {keepExchangeOrders: true}`; `extract#HotWaxOmsGqlOrders` takes the
  option so a re-read by key writes exchange orders flagged. Absent = default, so no existing side's read, Keep or kept-copy
  options key changes. Tests: `KeepExchangeOrdersOptionTieTests` 3/3 (new), `OmsExtractSupportTests` 18/18,
  `OmsOrdersKeepParityTests` 5/5, `ExcludedRecordsSidecarTieTests` 1/1, `OmsExtractScriptCharacterizationTests` 24/24,
  `OmsGqlOrdersDataDefinitionTests` 8/8.
- `75e8d75` DAR-BE-352 — the returns read by `orderExternalId` goes orders(externalId) -> returnItems(orderId) ->
  returns(returnId), keeping only records naming an asked key. 57/57.
- `201ba34` DAR-BE-338 follow-up — test and doc only.
