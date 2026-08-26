# Technical Changelog For Darpan 1.5.0

Engineer-facing. Buckets are hand-assigned against commit bodies, not the conventional-commit prefix
— the prefix says `feat` for 59 of 128 backend commits, which is not a useful split.

## Versioning decision

- `darpan` **1.4.0 → 1.5.0**. Minor, not patch: 128 commits adding whole capabilities (databases as a
  source, Slack as a notification provider, cross-tenant config sharing, a migration registry).
- `darpan-hotwax` **0.7.0 → 0.8.0**, `shopify-darpan` **0.2.1 → 0.6.0**, `database-darpan`
  **0.1.0 → 0.2.0**, `darpan-ui` **2.5.0 → 2.6.0**.
- `shopify-darpan`'s jump is a drift correction, not four releases at once. Its `component.xml` still
  read `0.2.1` at tags `v0.3.0`, `v0.4.0` and `v0.5.0` — the tag moved and the declared version did
  not. Setting it to `0.6.0` makes the two agree again. `netsuite-darpan` carries the same drift
  (`0.1.0` at tag `v0.2.0`) and is left alone: it has no commits to publish, and re-tagging a
  component to fix metadata alone is not worth an operator's attention.
- `netsuite-darpan` stays **v0.2.0** — no commits in range.
- `moqui-gql` is deliberately **not versioned** in this release. It has no tag and stays pinned to
  `main` in the production image.
- **Published API contract version 2 → 3.** Not a component version: this is
  `ApiContractGenerator.CONTRACT_VERSION`, bumped because `list#TenantChatSpaces` dropped the
  response field `chatSpaces[].googleChatWebhookUrlMasked` in `89d1522`. The additive-only policy
  makes a removed out-field a breaking change; 20 added methods on their own would not have moved it.

## Source ranges

- `https://github.com/drpn-ai/darpan/compare/v1.4.0...v1.5.0` — 136 commits
- `https://github.com/drpn-ai/darpan-hotwax/compare/v0.7.0...v0.8.0` — 20 commits
- `https://github.com/drpn-ai/shopify-darpan/compare/v0.5.0...v0.6.0` — 28 commits
- `https://github.com/drpn-ai/database-darpan/compare/v0.1.0...v0.2.0` — 7 commits
- `https://github.com/drpn-ai/darpan-ui/compare/v2.5.0...v2.6.0` — 58 commits

Total 249 commits, each appearing exactly once below. Counts are taken at the release-prep commit's
parent; the release-prep commit in each repo is not listed in its own changelog.

## Backend

### `drpn-ai/darpan` — 136 commits

#### Added

- `3e23a0d` feat(automation): verify every scheduled run by default
- `9ba0be6` feat(automation): canary verification per automation, and prove it runs
- `a724a06` feat(automation): let scheduled runs verify their differences, default off
- `5dce3db` feat(notification): run-notification voice and automation extract-window params
- `4f70157` feat(automation): add sync#Automation to re-derive a snapshot from its run
- `d1cdb97` feat(automation): report snapshot drift on get#Automation
- `2f8d19a` feat(automation): build a sync payload that preserves operator settings
- `70e4ee3` feat(automation): merge derived run config over the stored sources
- `196c251` feat(automation): derive a saved run's authoritative config
- `32f95eb` feat(recon): suppress cancelled-item refunds from the missing-in-OMS count
- `7e18e1e` feat(recon): stamp the API window on the run row at beginRun
- `86cc3cc` feat(recon): suppress superseded return drafts from the missing-in-OMS count
- `5c73702` data(recon): offer shopifyReturnId and wire the OMS returns lookup
- `da58efb` data(release): drop the pre-seeded sharing backfill from 1.5.0
- `9860288` feat(recon): suppress cancellation refunds from the row's own field
- `7f6a0ae` feat(admin): remote migration surface for operator and agent use
- `581881d` feat(migration): targeted run, park/unpark, failure detail and attempt history
- `e1ed01f` feat(notification): render verdict-led run-completion message
- `e4732ab` feat(notification): add clean-run streak lookback
- `e4994f6` feat(notification): add tenant-timezone time-of-day flavour line
- `945caa3` feat(notification): add clean-run copy corpus and injectable line picker
- `ea787d8` feat(notification): classify run results into verdict buckets
- `98e7c15` feat(notification): name the tenant in run-result deep links
- `e701fdf` feat(migration): super-admin Migrations screen with status, dry run and run pending
- `7ba957b` feat(migration): status listing joining registry, ledger and prerequisite state
- `6eb39dd` feat(migration): block a migration whose prerequisites have not succeeded
- `7e2ce04` feat(migration): supervisor walks the registry and skips already-applied migrations
- `32efa0b` feat(migration): ledger writer in a forced-new transaction so failures survive rollback
- `a7623f7` feat(migration): registry, prereq and ledger entities with seed for six existing migrations
- `36024ee` feat(facade): report schema flatness on list#JsonSchemas
- `50cc5fe` feat(facade): infer#JsonSchemaFromCsvText for CSV header schemas
- `d1cccd0` feat(jsonschema): CSV header parser and flat schema builder
- `087b1e1` feat(recon): compare two instances of the same system
- `db32fbc` feat(recon): legible error when an extractor's component is not installed
- `0824dab` feat(recon): register DATABASE source connector
- `4d4d998` feat(recon): per-connector cap on verification point lookups
- `c16daf4` feat(recon): verify missing-in-Shopify returns by point lookup
- `c0a83b3` feat(recon): dispatch the cancelled-order lookup from the returns verify stage
- `8c6a49c` feat(recon): suppress cancellation refunds from missing-in-OMS
- `6820886` feat(recon): add orderStateLookupServiceName to the connector registry
- `cc92a95` feat(recon): name the system on the System card; rename the returns join key
- `434e2c0` feat(reconciliation): re-point SHOPIFY_RETURN_REFS pills to the per-event shape
- `4d73413` feat(migration): retire pill rows withdrawn from seed data
- `942f448` feat(reconciliation): emit one source option per enabled endpoint, from the registry
- `6d3c012` feat(data): migrate rules-board pills into the connector registry
- `606d846` feat(entity): add SourceSystemConnectorField for registry-driven pills
- `4309645` feat(diagnostics): surface per-endpoint state through testSourceConnection
- `aabbb2a` feat(saved-run): gate both run guards on the run's own endpoint
- `f930159` feat(automation): auto-resolve configs per endpoint, not per canReadOrders
- `b30de36` feat(migration): translate legacy canReadOrders into explicit endpoint decisions
- `8581b30` feat(facade): generic list/store services for source endpoint access
- `083f897` feat(reconciliation): registry-driven per-endpoint enablement predicate
- `a309b65` feat(entity): add SourceConfigEndpointAccess for per-endpoint enablement
- `89d1522` feat(notification): store and return the Google Chat webhook URL in clear text
- `a11e3a5` DAR-BE-018: register SHOPIFY_RETURN_REFS connector, enum, and gate constant
- `08e36c6` DAR-BE-018: wire return presence check into saved-run diff as a VERIFY stage
- `45caa39` DAR-BE-018: fix return-presence audit-note caveat + remove dead reverse-match guard
- `ee1e9da` feat(DAR-BE-018): offer the returnChannelEnumId exclusion pill on OMS_RETURNS
- `0caa99e` DAR-BE-018: return presence rule — refund spine, return backup, order-scoped, grace/pending
- `eae6a80` DAR-BE-018: register OMS_RETURNS connector + upgrade data
- `0d00bde` feat(DAR-BE-018): register the OMS_RECON_ORDERS connector
- `33759e9` feat(DAR-BE-005): block deletion of a config that still has active grants
- `25bb1d0` feat(DAR-BE-005): widen source-config reference validators to owner-or-shared
- `4bd5541` feat(DAR-BE-005): surface shared configs in darpan settings lists and source pickers
- `59fea31` feat(DAR-BE-005): add two-sided tenant-admin gate for config sharing grants
- `4d1c7cf` feat(DAR-BE-005): add SharedConfigAccessSupport owner-or-shared resolver
- `bed96ce` feat(DAR-BE-005): add isTenantAdmin/requireTenantAdmin for an arbitrary tenant
- `5000d2b` feat(DAR-BE-005): add ConfigTenantAccess peer-group entity and shared-config type enum
- `ae0d8d8` feat(auth): let an account locked out by PWDCHG change its own password

#### Changed

- `690a9b7` chore(release): bump darpan to 1.5.0 and regenerate the API contract
- `f36f7c5` refactor(recon): lift the verification pass into a callable seam
- `6285a03` build(test): focused test selection and parallel-suite stability
- `745f42c` Merge feature/run-notification-voice: verdict-led run-completion notifications
- `f67add1` chore(contract): regenerate for admin migration services
- `dda8508` Merge main into feature/run-notification-voice
- `f8f77b0` refactor(migration): route registry and ledger reads through TenantScopedFinder
- `45c8268` refactor(reconciliation): reduce ReturnPresenceVerificationSupport to a grace/window filter
- `aa8797b` ci: clone the framework fork's master instead of the superseded tag
- `1f394a7` refactor(rules): read rules-board pills from the connector registry
- `7a94306` chore(DAR-BE-005): add 1.5.0 upgrade-data for shared-config enums and the SM/BJ backfill
- `9cf3781` chore(DAR-BE-005): regenerate api-contract and facade snapshot for ConfigSharingFacadeServices
- `5e0f285` ci(auth): run the login-deadlock regression suite against the fixed framework

#### Fixed

- `c996bd0` fix(recon): define formatMetadataTimestamp, and use it on the two remaining writers
- `8651a60` fix(recon): ship the authoritative run instant with diff metadata
- `7f15e27` fix(notify): name the system in run alerts, not the endpoint that fed it
- `55f0121` fix(slack): form-encode Web API calls so conversations.list stops silently ignoring them
- `855e304` fix(security): pin tenant-scoped reads that Moqui silently unpinned, and add Slack support
- `d8e0f52` fix(automation): refuse to re-point an existing automation at another run
- `72e30a8` fix(contract): regenerate the facade snapshot
- `e86b64a` fix(admin): validate tenant timezone before writing
- `40408fc` fix(notify): compose run copy as a verdict plus one closer
- `48a4b67` fix(recon): report blank CSV cells as empty instead of absent
- `8941821` fix(notification): surface value mismatches in run-completion alerts
- `460053b` fix(recon): resolve every system label by description, not just OMS
- `1076a34` fix(saved-run): label a run's endpoint from the connector, not the shared remote
- `140107e` fix(reconciliation): relabel returns pills so the join pairing is obvious
- `34399d0` fix(contract): regenerate facade snapshot for migrate#RetiredConnectorFields
- `0b2b31c` fix(data): drop unevidenced orderName pill from SHOPIFY_RETURN_REFS
- `2065217` fix(contract): regenerate facade contract snapshot for the source-endpoint services
- `3a685c0` fix(contract): regenerate API contract for the source-endpoint services
- `7dbeb8e` fix(facade): tenant-scope endpoint list read, move store logic to a Support class, and cover the owner-only write gate
- `716b8b9` fix(test): load DarpanSystemSource seed in the facade smoke test; refresh snapshot
- `b700595` fix(data): correct the stored SHOPIFY system description for deployed envs
- `b93fe7a` fix(reconciliation): make the source picker ask for system then endpoint
- `b363667` fix(DAR-BE-018): normalize orderExternalId GID, gate reverse pass on windowStart (Important #2/#3)
- `dc87d96` fix(DAR-BE-018): fix return-presence artifact contract, grace clocks, and ID normalization (C2, C3, I1, I5, I6, M2)
- `ba741ce` fix(reconciliation): route NsRestletConfig reads through shared-config gates (DAR-BE-005 B4/B5)
- `6617250` fix(security): close describeSharing existence oracle (DAR-BE-005 B6)
- `1119ede` fix(DAR-BE-005 B1): single owner-or-shared decision for explicit vs active tenant
- `a70e809` fix(DAR-BE-005): widen save#NsAuthConfig/NsRestletConfig to owner-or-shared
- `e813b00` fix(DAR-BE-005): collapse source-config denial messages to close existence oracle
- `d272fd7` fix(DAR-BE-005): close cross-tenant existence oracle, sync active-grant definition
- `6bcf2e1` fix(DAR-BE-005): anchor contract tests to ConfigTenantAccess entity block only
- `f3e7d45` fix(DAR-BE-005): restore field descriptions and use tolerant regex in contract test
- `1206104` fix(automation): DAR-BE-002 submit scheduled runs instead of joining them
- `7286430` fix(build): cap mysqlTest maxParallelForks to prevent worker-pool crash
- `f306fe3` fix(auth): hold refused logins to a constant time
- `f602fbb` fix(auth): stop login from revealing whether an account exists

#### Tests and docs

- `e42f0da` test(notification): delegate payload fakes to the real renderer
- `86c2d0b` test(automation): expect the tenant in the API run-result link
- `3edea19` test(automation): expect the tenant in the SFTP run-result link
- `2b591b0` test(jsonschema): pin CSV header parser to Spark's reader dialect
- `dd5b460` docs(returns): point the OMS externalId pill at refundOrReturnId
- `b5ee083` test(registry): prove a new endpoint needs data only
- `408b62b` test(registry): ratchet pill subset and top-level-scalar invariants
- `5114288` test(reconciliation): drop ShopifyConnectionProbe from the legacy canReadOrders ratchet
- `45285dc` test: fix round 1 — correct AutomationFacadeSupport allowlist citation
- `6dfb7c0` test: ratchet legacy canReadOrders readers; cover access-row cleanup
- `e979076` test(reconciliation): cover registry-disabled connectors staying unreachable
- `12fad53` test(DAR-BE-005): pin the read-side half of Task 8 Finding A statically
- `2c3b620` docs(contract): regenerate API contract after hotwax full-field fix
- `ea20375` docs(contract): regenerate API contract for Task 7 isShared out-parameters
- `0b4658c` docs(DAR-BE-005): clarify tenant-existence contract for isTenantAdmin
- `37cc209` test(auth): cover unarmed login and post-login flag self-heal
- `25663d8` test(auth): reproduce UserLoginKey self-deadlock on armed accounts (RED)
- `915810f` test(auth): MySQL-backed Moqui harness for InnoDB lock behavior

### `drpn-ai/darpan-hotwax` — 20 commits

- `460f5fe` chore(release): bump darpan-hotwax to 0.8.0
- `047de18` fix(recon): survive a transient OMS gateway 504 in returns extraction
- `b3dd4e1` fix(recon): look returns up by shopifyReturnId as well as externalId
- `d268743` feat(recon): borrow the returns join key, and point-look-up OMS by external id
- `8dd5268` docs: fix round 1 — record the out-of-band-delete orphan invariant
- `7afdd1f` fix(oms): delete endpoint access rows with the config
- `b579131` fix(oms): name the endpoint at every extraction and lookup call site
- `2142aa6` feat(oms): gate requireUsableOmsConfig on per-endpoint enablement
- `fac4e7f` fix(DAR-BE-018): trust hasMore as returns extraction's sole termination signal (C4, M1)
- `23405e6` DAR-BE-018: document returns extraction contract and count semantics
- `44b45ef` fix(DAR-BE-005 B1): allow shared HotWax OMS configs to actually run
- `0cc1b15` DAR-BE-018: add extract#HotWaxOmsReturns service and automation edge
- `9ee28c2` DAR-BE-018: returns channel exclusion + count hygiene (design §5, §9.5)
- `2243f62` fix(DAR-BE-018): abort+delete partial returns files on mid-window failure
- `680cbb4` feat(DAR-BE-018): OMS reconciliationReturns extraction (window, pagination, nested items)
- `34bf8ec` feat(DAR-BE-018): adopt the OMS reconciliationOrders endpoint
- `73eadbf` feat(DAR-BE-005): owner cannot delete an OMS source config while a grant is active
- `55cfe1a` test(DAR-BE-005): prove the delete oracle collapse against a genuine stranger
- `f9347d5` fix(DAR-BE-005): declare all OMS config out-parameter fields, not a subset
- `406d00d` feat(DAR-BE-005): OMS config list/save honour cross-tenant sharing; delete stays owner-only

### `drpn-ai/shopify-darpan` — 28 commits

- `eb77d2f` feat(recon): mark refunds whose lines never shipped
- `176742b` feat(recon): carry order cancelledAt on every return-refs event row
- `620e8c0` feat(recon): point-lookup Shopify refund/return ids via nodes(ids:)
- `2a12991` docs(returns): update extract#ShopifyOrderReturnRefs description for the rename
- `64de089` refactor(returns): rename eventId/eventType to refundOrReturnId/refundOrReturnType
- `ddc92f5` fix(shopify): correct Return.refunds selection path, malformed on live runs
- `9ec9ec7` feat(shopify): implement refunded-return narrowing via Return.refunds
- `96aaa68` docs(shopify): investigate and reject status as a refunded-return gate
- `fb334e7` feat(shopify): reshape return-refs extractor to one record per EVENT
- `3fa66e1` fix(shopify): remove order-level return pairing, returnId now honest null
- `96a459d` feat(shopify): reshape return-refs extractor to one record per refund
- `d0dff63` fix(shopify): declare the probe's endpoints out-parameter so it survives dispatch
- `db87e49` feat(shopify): probe reports per-endpoint state and names a missing token scope
- `f99a81b` docs: fix round 1 — record the out-of-band-delete orphan invariant
- `25ca9a6` fix(shopify): delete endpoint access rows with the config
- `bf05f72` fix(shopify): restore config gating by naming the endpoint at every call site
- `6471ed9` feat(shopify): gate per endpoint, independently for orders and return-refs
- `94ba330` fix(DAR-BE-018): widen fetch/emit lookback and emit stable list-shaped refunds/returns
- `4657fef` fix(DAR-BE-018): window returns reconciliation on event createdAt, not order date
- `744a9fe` DAR-BE-018: add extract#ShopifyOrderReturnRefs service and edge script
- `0c47a73` DAR-BE-018: Shopify per-order refund/return id extraction via cursor pagination
- `d1b4e99` DAR-BE-018: add SHOPIFY_ORDER_RETURN_REFS cursor source (bulk deliberately unsupported)
- `a4a6102` fix(settings): route requireUsableAuthConfig through findGlobalUnscoped (DAR-BE-005 B3)
- `8ceac89` fix(DAR-BE-005 B1): allow shared Shopify auth configs to actually run
- `c12b3aa` fix(DAR-BE-005): close live cross-tenant credential hijack in ShopifyAuthConfigSupport.findAuthConfig
- `78450b9` feat(DAR-BE-005): owner cannot delete a Shopify auth config while a grant is active
- `315b41c` test(DAR-BE-005): prove the get/delete oracle collapse against a genuine stranger
- `f49428c` feat(DAR-BE-005): Shopify auth config list/get/save honour sharing; delete stays owner-only

### `drpn-ai/database-darpan` — 7 commits

- `faf6b7a` fix(facade): allow angle brackets in sqlSelect parameters
- `59283ac` fix(security): centralise the private-host policy; unbreak gated integration tests
- `affa58f` feat(facade): expose preview#DatabaseQuery; pin tenant fences with regressions
- `776e32d` feat(facade): tenant-scoped CRUD for database source queries
- `802e14e` feat(facade): tenant-scoped CRUD for database source configs
- `bdcd8e8` feat(diagnostics): probe#DatabaseConnection for the registry health-check slot
- `22de9df` feat(security): reject database hosts resolving to non-routable space

## UI

### `drpn-ai/darpan-ui` — 58 commits

- `7cdb433` fix(run-result): show the run time from a real instant, not a zone-less string
- `999dbc9` fix(auth): let the tenant's timezone win, as the backend already decided it does
- `9d8fe44` fix(shell): shorten every notice-pill sentence and stop reserving its lane empty
- `0261dfb` fix(shell): announce a deep-link tenant switch on the pill, not a second banner
- `323110a` fix(settings): skip the chat-product card when the entry point already answered it
- `029c079` fix(settings): give every Slack screen a route to the channel it exists to notify
- `ce6f17e` feat(shell): command palette and stylesheet updates
- `149ae78` feat(settings): connect Slack and pick a channel, alongside Google Chat
- `e1b50a3` feat(shell): run /switch-tenant, and reserve a lane so notices never cover content
- `1b00fb2` feat(shell): slash-command mode for Ask Darpan, first command /switch-tenant
- `96d746d` fix(recon): pair Status beside Chat Space on the automation edit form
- `d887bd3` fix(recon): name which systems each VERIFY pass rechecked
- `0ba51d0` fix(recon): name the system on run-result source chips, not the extract endpoint
- `5935eff` fix(recon): hide API-only endpoints from the file-upload branch
- `3423e55` feat(shell): announce a deep-link tenant switch
- `9bd8295` feat(router): switch tenant for tenant-scoped deep links
- `70efc29` feat(auth): track the tenant a deep link switched into
- `1581aa6` feat(auth): distinguish a refused tenant switch from a failed one
- `4bf2272` fix(api): recover a restored session instead of stranding pages on an auth error
- `79765f7` feat(settings): drop the shared marker from config tiles
- `a22b848` feat(recon): switch automations active from the dashboard and canonicalize system labels
- `41a7991` fix(api): land the inferFromCsvText facade and isFlatFieldList type its callers need
- `5d33699` fix(recon): show resolved system label on run-result source files, not the enum token
- `7db7362` test(reconciliation): prove the rules board populates from CSV column lists
- `bc9a410` feat(reconciliation): CSV sides can pick a column list instead of typing
- `c60fc60` feat(jsonschema): infer a schema from a CSV sample's header row
- `f1a85a3` feat(reconciliation): rank likely primary-key columns by name
- `118f425` feat(api): sync CSV header inference contract and schema flatness types
- `0d62cc7` fix(recon): schedule automations in the tenant timezone, not UTC
- `aa1fcae` fix(recon): order create-flow cards under what they depend on
- `e9c5c37` fix(recon): resolve API source options by endpoint, not by shared remote id
- `de88791` fix(recon): carry systemParentLabel through the run edit workflow
- `345a635` fix(recon): System card names the system, not the endpoint
- `1d101c7` test(reconciliation): pin endpoint labels on the ruleset manager's schema cards
- `5ad969b` fix(reconciliation): skip redundant API-endpoint step when only one option exists
- `f92554c` refactor(reconciliation): drop UI-side source-config-type guessing
- `acd5741` fix(settings): stop applyRecord racing the endpoint panel's seed
- `966501d` test(reconciliation): cover the enabled-subset filter on the ruleset-manager auth popup
- `a8ab835` feat(settings): read endpoint lists from the registry on read-only surfaces
- `98d59e7` fix(settings): gate endpoint-access writes on a confirmed panel read
- `b04b64f` feat(settings): drive Available Endpoints from the registry on both config forms
- `59cb1b1` fix(settings): close config-switch clobber window in EndpointAccessPanel
- `d7f479f` feat(settings): registry-driven Available Endpoints panel
- `c7026f9` feat(api): client for source-config endpoint listing and enablement
- `8010613` fix(reconciliation): a paused automation has no next run
- `9097060` fix(reconciliation): ask which run to automate; own the time control
- `5fe44dc` fix(reconciliation): name API source configs by description, not id
- `0a4f094` fix(reconciliation): one rules board, one stage
- `7d71bf4` feat(settings): show the Google Chat webhook URL in clear text
- `bcd4261` feat(settings): seat "Shared with" inside the config edit form
- `b7a3a27` fix(reconciliation): two-step source picker — system, then endpoint
- `eee3e84` fix(ui): Enter advances inside marked forms; name the browser timezone
- `e67b001` fix(DAR-BE-005): close the affects-N-tenants save-gate race, de-dup the sharing fetch
- `68990e5` fix(DAR-BE-005): un-commit an unrelated in-flight style.css change
- `a6fe31f` feat(DAR-BE-005): add Shared with panel, shared tile marker, and edit warning
- `b8adedd` feat(DAR-BE-005): add config-sharing facade client and canManageConfigSharing
- `dfdccbd` fix(login): state the password rules and check them as the user types
- `c17b355` feat(login): offer the password change inline instead of a dead end

## Data and configuration

- **54 upgrade records**, assembled from the generic seed files and hand-ordered for foreign-key
  safety. Full record listing and rationale in `upgrade-data-review.md`.
- **Two seed files are new** in this range and had never reached an upgrade payload:
  `MigrationRegistrySeedData.xml` (6 `DarpanMigration` rows) and
  `SourceSystemConnectorFieldSeedData.xml` (29 `SourceSystemConnectorField` rows).
- **Three records are restatements**, not additions: `Enumeration OMS` and `Enumeration SHOPIFY`
  correct stale stored descriptions, and `Enumeration OMS_TRANSFER_ORDERS` gains `parentEnumId`.
- **New entities**: `ConfigTenantAccess`, `SourceConfigEndpointAccess`, `SourceSystemConnectorField`,
  `DarpanMigration`, `DarpanMigrationPrereq`, plus the Slack install and chat-provider columns on
  `TenantChatSpace`. All created on startup; no schema migration step.
- **Production image composition changed**: `docker/prod/Dockerfile` now clones `database-darpan`
  (pinned `v0.2.0`), which it never did before. Without it the enabled `DATABASE` connector row this
  release seeds would name an extract service the image does not contain.

## Validation and rollout notes

- Backend: 166 test classes, 1677 tests, 0 failures, 0 errors, 8 skipped, under JDK 21 with
  `--rerun-tasks`, one gradle invocation per component.
- UI: `npm run check` — 113 files, 1175 tests, 0 failures, with ESLint, the stylelint design-system
  gate and a forced `vue-tsc` type-check all executing.
- The 8 skips are `database-darpan`'s live-database integration tests (Postgres, MySQL, Db2), gated
  on a reachable server. `LoginDeadlockRegressionTests` (4 tests, `@Tag("mysql")`) also did not run.
- `scripts/check_contract_compat.py` failed before the contract-version bump and passes after it,
  reporting the removal as consciously accepted.
- Rollout order: load upgrade data, run `run#PendingMigrations`, deploy backend, then deploy the app.
- Nothing in this release was exercised against a live source system or a deployed environment.

## References

- `release-notes.md` — user- and operator-facing notes
- `release-checklist.md` — verification evidence and what was not verified
- `upgrade-data-review.md` — per-record upgrade data review
- `upgrade-data.xml` — the load target, byte-identical to `data/upgrade-data.xml`
