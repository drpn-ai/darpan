# Technical Changelog For Darpan 3.0.0

Release date: 2026-11-12

## Versioning decision

- Major on every product line, chosen by Aditi 2026-11-12 ("per-line majors"): darpan 2.1.12 -> 3.0.0, darpan-ui
  3.1.4 -> 4.0.0, docker-config -> 3.0.0. Components by their own contract (service in/out diff against their last
  tag): darpan-hotwax 1.0.0 (six REST extract services removed, two `save#HotWaxOmsRestSourceConfig` params removed,
  migration `OMS_REST_RETIRED`) and moqui-gql 1.0.0 (public GraphQL drops `pending`, `pendingUntil`, `graceDays`) take
  a major and leave 0.x; shopify-darpan 0.9.0 and netsuite-darpan 0.5.0 are additive (+2 services each);
  database-darpan 0.3.0 has no contract change. darpan-agent's first tag is 0.1.0.
- Facade contract 3 -> 4 (`ApiContractGenerator.CONTRACT_VERSION`): `check_contract_compat.py` against `v2.1.12`
  reports six removals on `list#`/`save#HotWaxOmsRestSourceConfig` (`ordersPath`, `returnsPageSize`, request and
  response) and 66 additive methods. With the bump the gate passes ("contract version bumped 3 -> 4 (accepted)").
- Cut from `main` of every repo. `main` never carried the 2.1.6-2.1.12 version commits (the 2.1 line split at
  `v2.1.5`); `git cherry` shows only those release-metadata commits are missing, so no fix from the 2.1 line is lost.
  The 2.1.6-2.1.12 release packs are restored onto `main` from `v2.1.12`.

## Source ranges

| Repo | Range | Commits |
| --- | --- | --- |
| darpan | `v2.1.12..v3.0.0` (merge base `v2.1.5`) | 227 not on the 2.1 line |
| darpan-ui | `v3.1.4..v4.0.0` (merge base `v3.1.2`) | 61 not on the 3.1 line |
| darpan-hotwax | `v0.10.4..v1.0.0` | 29 |
| shopify-darpan | `v0.8.5..v0.9.0` | 7 (v0.8.5's version commit is off `main`) |
| netsuite-darpan | `v0.4.3..v0.5.0` | 11 |
| database-darpan | `v0.2.2..v0.3.0` | 5 |
| moqui-gql | `v0.1.1..v1.0.0` | 1 (DAR-BE-206) |
| darpan-agent | `..v0.1.0` | 40 (first tag) |
| darpan-docker-config | `v2.1.12..v3.0.0` | 24, plus the pin commit |

## Backend

### Breaking

- DAR-BE-052: the HotWax OMS source config drops `ordersPath` and `returnsPageSize`; OMS reads are GraphQL only.
- DAR-BE-206: audit findings are open or fixed; `pending`, `pendingUntil` and `graceDays` leave the services, the CLI
  and moqui-gql's audit types (migration `AUDIT_FINDINGS_NO_PENDING`).

### Added

- Embedded audit agent (darpan-agent v0.1.0 + darpan): turns, conversations, proposals (`plan#`/`apply#`/`reject#`/
  `revert#AuditChange`), brain change tickets, agent read scope; per-user AI access grants with spend limits
  (DAR-BE-159, DAR-BE-194, DAR-BE-200, DAR-BE-214..216, DAR-BE-220, DAR-BE-221, DAR-BE-224, DAR-BE-242).
- Brain: a declared question catalogue, stable vs run answers, per-run question records, parameter facts, the graph as
  JSON (DAR-BE-225..230).
- CLI: device login, sessions, per-user grants and access requests; read and act through the allowlist; MCP
  (DAR-CLI-003, DAR-CLI-004, DAR-BE-195, DAR-BE-209).
- Tenant scripts (JEXL) and scheduled deployments, memory-guarded (DAR-BE-164, DAR-BE-167, DAR-BE-183).
- Data-defined systems: REST JSON reads from a data definition; check/save/retire services; a stopped read records why
  (DAR-BE-176, DAR-BE-231, DAR-BE-234, DAR-BE-235).
- Tenant config versions, restore preview, transfer of scripts and deployments (DAR-BE-160, DAR-BE-236, DAR-BE-237).
- Knowledge base: three-tier reviewed entries, Lucene search (DAR-BE-161).
- Audit run update: Haiku-written chat message, email with a CSV per rule (DAR-BE-207).
- NetSuite <-> HotWax product reconciliation, NetSuite -> OMS fulfillment sync check (DAR-BE-157, DAR-BE-190).
- Run admission for concurrent audits (DAR-BE-143); live read counts on a running audit (DAR-BE-173).

### Changed

- Spark 3.5.1 -> 4.2.1 (Scala 2.13, ANTLR 4.13.1); Drools 8.44.0.Final -> 10.2.0 (DAR-BE-185, DAR-BE-184).
- An audit owns one schedule (DAR-BE-199, migration `AUDIT_SCHEDULE_OWNED`); "expected" is a property of the cause, not
  of a statement link (DAR-BE-196, migration `STATEMENT_LINK_EXPECTED`).
- Morning runs read month-to-date (DAR-BE-156); run notifications name each rule (DAR-BE-210); data labels 1-3 words
  (DAR-UI-101 backend labels); excused-and-done records get their own place (DAR-BE-238).

### Fixed

- DAR-BE-031, DAR-BE-155, DAR-BE-180, DAR-BE-208, DAR-BE-239, DAR-BE-241 (config-save race; Moqui fork shutdown).
- DAR-BE-243: Shopify's "Required access:" fails a run at once (`AutomationExecutionSupport` classifies it as not
  retryable); shopify-darpan's connection probe starts one empty bulk read after Orders reads, so a token Shopify refuses
  bulk operations fails the check. The facade change is a description only; the contract regenerates byte-identical.

### Security

- DAR-BE-080, DAR-BE-081, DAR-BE-082, DAR-BE-183, DAR-BE-189.

## UI

- 4.0.0: agent tab and drafts on the board (DAR-UI-104, DAR-UI-108, DAR-UI-109), finding panel record-first
  (DAR-UI-092), run health card (DAR-UI-096), order lines (DAR-UI-099), Slack peer provider and delivery mode
  (DAR-UI-023, DAR-UI-024), systems matched by server data (DAR-UI-106), excused-and-done place (DAR-UI-107), labels,
  mascot and cleanup passes (DAR-UI-101..103).
- Vue 3.5.30 -> 3.5.44 (lockfile; `package.json` floor `^3.5.44`): clears GHSA-g2v6-rqmx-r4w6 in
  `@vue/server-renderer`. Moved with it, all patch: `@vue/*` 3.5.44, `@babel/parser`/`types`/`helper-string-parser`
  7.29.14, `postcss` 8.5.32, `nanoid` 3.3.20, `source-map-js` 1.2.2. `npm audit --omit=dev --audit-level=high`: 0.
- Contract copy synced (`src/lib/api/contract/openapi.json`: 3.0.0 / contract 4); generated types unchanged.

## Data and configuration

- Upgrade data: 150 records, see `upgrade-data-review.md`. `release_preflight.py` now skips `darpan-fixture` and
  `darpan-test-fixture` files; before that fix its candidate for this range carried 104 test-fixture records (a fake
  audit and rule sets from `data/test/`).
- `darpan-docker-config` `docker/prod/Dockerfile`: `DARPAN_REF=v3.0.0`, `DARPAN_HOTWAX_REF=v1.0.0`,
  `SHOPIFY_DARPAN_REF=v0.9.0`, `NETSUITE_DARPAN_REF=v0.5.0`, `DATABASE_DARPAN_REF=v0.3.0`, `MOQUI_GQL_REF=v1.0.0`,
  `DARPAN_AGENT_REF=v0.1.0`. `MOQUI_FRAMEWORK_REF` stays `master` (the fork carries the DAR-BE-241 shutdown fix).
- Release checklist: moqui-gql and darpan-agent added to the release repo set.

## Validation and rollout notes

- Focused checks; counts in `release-checklist.md`.
- Rollout: set `DARPAN_APP_BASE_URL`, confirm SMTP; deploy backend and UI together; `loadDarpanUpgradeData`;
  `run#PendingMigrations` dry run (read `OMS_REST_RETIRED`'s list of sources with no GraphQL endpoint), then real.
- `OMS_REST_RETIRED` (darpan-hotwax `data/HotWaxMigrationSeedData.xml`, type `darpan-seed`) is not in darpan's
  `upgrade-data.xml`: the type load reads every component's `darpan-seed` files, so it registers on deploy.

## References

- https://github.com/drpn-ai/darpan/compare/v2.1.12...v3.0.0
- https://github.com/drpn-ai/darpan-ui/compare/v3.1.4...v4.0.0
- https://github.com/drpn-ai/darpan-docker-config/compare/v2.1.12...v3.0.0
- https://github.com/drpn-ai/darpan-hotwax/compare/v0.10.4...v1.0.0
- https://github.com/drpn-ai/shopify-darpan/compare/v0.8.5...v0.9.0
- https://github.com/drpn-ai/netsuite-darpan/compare/v0.4.3...v0.5.0
- https://github.com/drpn-ai/database-darpan/compare/v0.2.2...v0.3.0
- https://github.com/drpn-ai/moqui-gql/compare/v0.1.1...v1.0.0
