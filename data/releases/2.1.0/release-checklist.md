# Release Checklist For Darpan 2.1.0

## Scope

Minor release from prod's `v2.0.0` (deployed 2026-10-19). All content is on `main` and pushed in
every repo; the cut bumped version metadata and moved the prod pins (below), then tagged.

| Repo | From | To | Content head |
| --- | --- | --- | --- |
| `drpn-ai/darpan` | `v2.0.0` | `v2.1.0` | `99e2620` |
| `drpn-ai/darpan-ui` | `v3.0.0` | `v3.1.0` | `2520855`, plus `626b8b1` (another session's DAR-BE-028 contract mirror, committed locally and not pushed) |
| `drpn-ai/darpan-hotwax` | `v0.9.0` | `v0.10.0` | `3445bb1` |
| `drpn-ai/netsuite-darpan` | `v0.3.0` | `v0.4.0` | `dfdfca2` |
| `drpn-ai/shopify-darpan` | `v0.7.0` | `v0.8.0` | `e8b462a` |
| `drpn-ai/database-darpan` | `v0.2.1` | not re-cut | — |
| `drpn-ai/moqui-gql` | untagged | `v0.1.0` at `1e0776c` (recommended) | `1e0776c` |
| `drpn-ai/darpan-docker-config` | `v2.0.0` | `v2.1.0` | pins only |
| `drpn-ai/darpan-docs` | — | `releases/updates.mdx`, `releases/roadmap.mdx`, and push `f8b25eb` after the API is live | — |

## Linear prework

Linear is retired; the backlog (`roadmap/BACKLOG.md`) is the source of truth.

- Release tracking: this pack (release prep is `neither`; no item of its own).
- In-scope items (named in the release set's commits): DAR-BE-028, 056, 085, 086, 088-095, 097-103,
  107, 108, 111-131, 133, 134; DAR-UI-050, 052, 056-058, 060-066, 070, 076-081; DAR-DOCS-003.
- Every in-scope item's code is committed and pushed; what remains on each is a deploy, data load,
  app step or live check, listed below.
- Deferred, named: moqui-gql `v0.1.0` tag and pin (DAR-BE-101) if not taken at this cut; darpan-cli
  release (DAR-CLI-001, 002); the live checks under Not verified.
- Open release issues: 0

## Cut (2026-10-26)

| Repo | Tag | Release commit |
| --- | --- | --- |
| `drpn-ai/darpan` | `v2.1.0` | the release-pack commit on `a90cbf4` (component 2.1.0, contract version 2.1.0) |
| `drpn-ai/darpan-ui` | `v3.1.0` | `5243440` (package 3.1.0, contract copy; carries `626b8b1`) |
| `drpn-ai/darpan-hotwax` | `v0.10.0` | `3eb266f` |
| `drpn-ai/netsuite-darpan` | `v0.4.0` | `3071af8` |
| `drpn-ai/shopify-darpan` | `v0.8.0` | `c56c3d5` |
| `drpn-ai/moqui-gql` | `v0.1.0` | `d7c753f` (notes updated for the four commits after `907fd15`) |
| `drpn-ai/darpan-docker-config` | `v2.1.0` | `e488e3b` (prod pins) |
| `drpn-ai/database-darpan` | stays `v0.2.1` | — |

- `generateApiContract` after the bump: diff is the version string only; `ApiContractGeneratorTests`
  7/7 and `FacadeContractSnapshotTests` 1/1 re-run (`--rerun`, Gradle exit 0).
- darpan-ui: `package.json` and the two root keys of `package-lock.json` edited as JSON (diff: those
  three lines); `sync-contract` + `generate-api-types`: diff is the contract version string only
  (`626b8b1` already mirrored darpan's 2.1.0 contract); `npx vitest run src/lib/api` 11 files / 106
  tests, `type-check` and `lint` exit 0.
- `verify_release_pins.py --version 2.1.0 --offline`: exit 0 before tagging; online after the push.
- GitHub release pages and the darpan-docs publish are not part of this cut.

## Deploy and data, in order

On each instance, from an image built from the 2.1.0 tags.

1. **Start** the new image: Moqui's add-missing creates the 11 `darpan.audit.*` tables,
   `AutomationStreak`, `PasswordResetCode` and the new columns.
2. **System upgrade data**, every instance (prod, UAT, sm-darpan):
   `./gradlew loadDarpanUpgradeData` (loads every `darpan-seed` file; this release adds 98 records,
   listed in `data/upgrade-data.xml`). Then `run#PendingMigrations` as on every deploy.
3. **SYSTEM EmailServer**: confirm it has an `smtpHost` (password reset and digests send through it).
4. **Tenant config, prod only** (the instance hosting GORJANA and RAILS), one file at a time, in
   this order, so no older tenant file is reloaded over edits made in the app:
   ```
   DARPAN_UPGRADE_DATA_TYPES=darpan-tenant-config \
   DARPAN_UPGRADE_DATA_LOCATION=component://darpan/data/<file> ./gradlew loadDarpanUpgradeData
   ```
   1. `TriSystemOrderRunsRuleSetData.xml` (modified: gorjana's order-lines sources move from REST
      `OMS_ORDER_ITEMS` to `OMS_ORDER_ITEMS_GQL`, and the run's conclusion lookups)
   2. `GorjanaMonthCloseCatalogData.xml` (the audit, its statements)
   3. `GorjanaMonthCloseChecksData.xml`, `GorjanaMonthCloseOpenOrdersChecksData.xml`,
      `GorjanaMonthClosePaymentsChecksData.xml`, `GorjanaMonthCloseReceiptChecksData.xml`,
      `GorjanaMonthCloseReplacementChecksData.xml`, `GorjanaMonthCloseReturnsChecksData.xml`,
      `GorjanaMonthCloseWarrantyClaimChecksData.xml`
   4. `GorjanaConclusionRulesData.xml` (modified: it overwrites the same rule rows if they were
      edited in the app since 2.0.0; check the app's conclusions for gorjana's order-lines run first)
   5. `GorjanaReturnLifecycleConclusionData.xml`
   6. `RailsAuditData.xml`
5. **NetSuite queries, prod, as the tenant they belong to** (`client-config`, outside the image):
   - gorjana: `tools/netsuite-chain-config/upload-gorjana-checks.py --base-url <prod> --queries
     tools/netsuite-chain-config/gorjana-month-close-queries.xml --queries
     tools/netsuite-chain-config/gorjana-tri-system-queries.xml --apply` (dry run first; the
     active tenant must read gorjana). It saves 17 queries and refuses `NS_SO_OPEN_OMS_STATUS` by name.
   - `NS_SO_OPEN_OMS_STATUS` (gorjana) and `RAILS_NS_SO_OPEN_OMS_STATUS` (RAILS, from
     `rails-audit-queries.xml`) carry `omsStatusConfigId` / `omsStatusKeyField`, which the save
     service does not take: load those two rows as data (`DARPAN_UPGRADE_DATA_TYPES=client-config`
     with the file copied onto the instance) or set them in the app.
   - `gorjana-r12-closed-claim.xml` (one conclusion, its condition and enum): data load as above, or
     add the conclusion in R12's run settings.
6. **Audits, in the app** (gorjana, then RAILS): any existing gorjana automation whose source is REST
   `OMS_ORDER_ITEMS` moves to `OMS_ORDER_ITEMS_GQL` (automations keep their own source rows; DAR-BE-116);
   tenant time zone America/Los_Angeles for gorjana;
   play the audit once over a short window (it creates the paused automations); schedule them 06:00
   in the tenant's zone and enable them; set digest rows and chat spaces.
7. **Smoke**: a 3-day audit window first (extract steps count up; every check ends ran), then the
   month; the audit page on gorjana (an R1 order, an R8 exchange-held return, an R20 pair, Resolve on
   an open finding); a password reset by email; one scheduled gorjana run (DAR-BE-133); a NetSuite
   auth profile's diagnose.
8. **Docs**: push `darpan-docs` `f8b25eb` and the 2.1.0 release pages once the API is live.

## Release notes

- `release-notes.md` (scope, targets, user and operator changes, upgrade data, API, rollback,
  deferred). Public pages (`darpan-docs/releases/updates.mdx`, `roadmap.mdx`) are written at the cut.

## Technical changelog

- `technical-changelog.md`: versioning per repo, ranges, Added / Fixed / Security / Removed per repo,
  schema and job changes, rollout order, compare URLs.

## Upgrade data

- Generic source data file updated for every release upgrade record: yes; the file is rebuilt from
  the seven `darpan-seed` files changed in `v2.0.0..HEAD`, not edited forward.
- Candidate diff reviewed: the generator's 327 candidates split into 98 `darpan-seed` records (the
  load target) and 229 records from the 12 `darpan-tenant-config` files, excluded by design (step 4).
  All 80 records added to the file by hand since 2.0.0 are in the rebuilt file; the other 70 records
  the old file held are 2.0.0's own, already loaded.
- Order: system-source enums, audit enums and filters, email, then connectors before their fields
  (the generator's alphabetical order loaded `SourceSystemConnectorField` before
  `SourceSystemConnector`, the FK hazard of earlier cuts).
- Final load path decided: `./gradlew loadDarpanUpgradeData`, every instance.
- `data/releases/2.0.0/upgrade-data.xml` byte-identical to `v2.0.0`'s `data/upgrade-data.xml`;
  `data/releases/2.1.0/upgrade-data.xml` byte-identical to the load target.

## Verification

Run 2026-10-26 on `main` heads, JDK 21.0.11, focused tests only (standing instruction):

- Contract: `scripts/check_contract_compat.py v2.0.0..HEAD`: compatible, 140 preserved, 26 added,
  version 3. `ApiContractGeneratorTests` 7/7, `FacadeContractSnapshotTests` 1/1 (before the version
  bump; both re-run at the cut).
- darpan: `darpan.audit.*` 157/157; `AutomationExecutionSupportTests` 94/94 (incl. DAR-BE-130's and
  DAR-BE-134's tests and a cancel raised inside an extract ending CANCELLED);
  `AutomationExecutionDetachTests`, `AutomationFailureNotificationTests`,
  `AutomationExecutionServiceSmokeTests`, `RunFlowchartAutomationSmokeTests` green.
- darpan-hotwax: `darpan.hotwax.oms.*` 390/390. netsuite-darpan: `NsSuiteQlPageRetryTest` 7/7.
  shopify-darpan: `ShopifyGraphqlTransportTests`, `ShopifyBulkOperationClientTests` 18/18.
- database-darpan: compiles (main and test) against darpan `99e2620`, Gradle exit 0.
- Live, local stack against gorjana's live HotWax, NetSuite and Shopify: full-read Oct 1-25 month
  close, all 5 automations succeeded, 7/7 checks ran over complete reads, 39.3 min, 0 THROTTLED;
  a 3-day run with extract progress counting up.
- `release_preflight.py validate --version 2.1.0`: see Approval.

## Not verified

- Full suites (darpan `unitTest`/`smokeTest`, connector suites, `npm run check`); UI checks not run
  in this prep.
- No load of `upgrade-data.xml` or the tenant files on a database at 2.0.0; ordering is argued and
  record-checked, not observed.
- No browser check of the 2.1.0 app; no prod or UAT run.
- `verify_release_pins.py` online (after tags).

## Approval

- Release owner sign-off: Aditi, "Finish and cut the tag" (2026-10-26). Release pages, docs publish
  and every deploy remain hers.
- Cut/tag gate: the steps under Cut done; `verify_release_pins.py` exit 0 offline, online after push.
