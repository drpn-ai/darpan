# Release Checklist For Darpan 2.0.0

## Scope

Major release. Every repo in the release set is re-cut; all content commits were already on `main`
and pushed in each repo before the cut began.

| Repo | From | To | Content commits | Tagged commit |
| --- | --- | --- | --- | --- |
| `drpn-ai/darpan` | `v1.5.1` | `v2.0.0` | 89 | release commit on `b473ecf` |
| `drpn-ai/darpan-ui` | `v2.6.1` | `v3.0.0` | 108 | release commit on `46539b4` |
| `drpn-ai/darpan-hotwax` | `v0.8.0` | `v0.9.0` | 26 | release commit on `a9aac48` |
| `drpn-ai/shopify-darpan` | `v0.6.1` | `v0.7.0` | 19 | release commit on `822b155` |
| `drpn-ai/netsuite-darpan` | `v0.2.0` | `v0.3.0` | 14 | release commit on `586bffe` |
| `drpn-ai/database-darpan` | `v0.2.0` | `v0.2.1` | 2 | release commit on `7908b9e` |
| `drpn-ai/darpan-docker-config` | `v1.5.1` | `v2.0.0` | 1 | release commit on `92c496c` |
| `drpn-ai/darpan-docs` | — | — | release pages and technical docs | release commit on `e4d10ab` |

`darpan-admin-ui` is not in the release set (no release tags; it talks only to `admin.*`, which the
fence keeps open).

## Linear prework

Linear is retired; the backlog (`roadmap/BACKLOG.md`) is the source of truth.

- Release tracking: this pack. Release prep is classed `neither`, so no backlog item of its own.
- In-scope items (named in commit messages across the release set): DAR-BE-011, 024, 026, 027,
  029, 030, 032, 036, 037, 040, 042, 043, 044, 045, 046, 049, 050, 053, 054, 057, 058, 059, 060,
  061, 062, 063, 064, 065, 067, 068, 069, 070, 071, 072, 073, 074, 075, 076, 077, 078, 079;
  DAR-UI-017, 019, 020, 021, 022, 025, 026, 027, 028, 029, 030, 031, 032, 033, 035, 036, 037, 038,
  039, 040, 041, 042, 043, 044, 047, 048, 049, 050, 051, 053, 054, 055; DAR-DOCS-002; DAR-OPS-001.
- Every in-scope item's code is committed and pushed. Ten are still `active` in the backlog. For
  each, what remains is a deploy step, a live check or a later slice, not code this release lacks:
  DAR-BE-040 (re-point gorjana returns to the GraphQL endpoint after deploy), DAR-BE-045 (month
  re-run, blocked on DAR-BE-056), DAR-BE-058 (gorjana check upload after deploy), DAR-BE-073
  (field-reach UI, month timing run), DAR-UI-048 (browser check, live acceptance), DAR-DOCS-002
  (two deferred pages). DAR-BE-060, DAR-BE-064, DAR-UI-044 and DAR-UI-050 carry next actions the
  commits have already done; their status text is stale and should be refreshed.
- Deferred, named: DAR-BE-056 (SuiteQL 120 s timeout), DAR-BE-059 (`delete#*` still answer over
  RPC), the live and browser checks under Not verified.
- Open release issues: 0

## Release notes

- `release-notes.md` in this folder: scope, repo targets, user and operator changes, upgrade data,
  API, rollback, deferred items.
- Public: `darpan-docs/releases/updates.mdx` (v2.0.0 section, version key now names app `v3.x`),
  `darpan-docs/releases/roadmap.mdx` (recently shipped rotated to v2.0.0 and v1.5.1; the four
  shipped items removed from Up next).

## Technical changelog

- `technical-changelog.md` in this folder: versioning decision per repo, source ranges, breaking
  changes, per-repo Added / Changed / Fixed / Security / Removed with every commit accounted for,
  cross-component pairing, rollout order.
- Public API: `darpan-docs/api-reference/changelog.mdx` (Added in 2.0.0),
  `service-catalog.mdx` (all 140 contract methods documented; the drift check passes),
  `errors.mdx` (HTTP 403 from the method fence), `openapi.json` (public 2.0.0 copy, 115 methods).

## Release metadata changed

- `darpan/component.xml` 1.5.1 → 2.0.0; `docs/api-contract/openapi.json` regenerated, diff is the
  version string only.
- `darpan-hotwax/component.xml` 0.8.0 → 0.9.0; `shopify-darpan` 0.6.1 → 0.7.0; `netsuite-darpan`
  0.1.0 → 0.3.0 (its `v0.2.0` tag carried a stale `0.1.0`); `database-darpan` 0.2.0 → 0.2.1.
- `darpan-ui/package.json` and the two root-package keys of `package-lock.json` 2.6.1 → 3.0.0 (edited
  as JSON; no dependency entry touched). `src/lib/api/contract/{methods.txt,openapi.json}` re-synced
  from the backend and `src/lib/api/generated/schema.d.ts` regenerated.
- `darpan-docker-config/docker/prod/Dockerfile`: `DARPAN_REF=v2.0.0`, `DARPAN_HOTWAX_REF=v0.9.0`,
  `SHOPIFY_DARPAN_REF=v0.7.0`, `NETSUITE_DARPAN_REF=v0.3.0`, `DATABASE_DARPAN_REF=v0.2.1`, with the
  pairing reason in the comments. `docker/Dockerfile` tracks `main` by design and is unchanged.
  `README.md` pin table updated to match.
- `plugins/darpan-workflows/scripts/verify_release_pins.py` (wrapper): check 4 now also requires
  darpan ≥ 2.0.0 to pin hotwax ≥ 0.9.0, Shopify ≥ 0.7.0 and NetSuite ≥ 0.3.0.

## Upgrade data

- `data/upgrade-data.xml`: 80-record delta, re-checked against the full seed diff from a
  scratch-clone `release_preflight.py generate` (188 candidates: 80 present and identical, 0
  extra, 0 differing; 108 tenant-config records excluded by design). Header updated for 2.0.0, and
  the pre-load NULL-stamp counts widened from 5 tables to 15.
- `data/releases/2.0.0/upgrade-data.xml` byte-identical to the load target.
- `data/releases/1.5.1/upgrade-data.xml` confirmed byte-identical to the `v1.5.1` tag's file; untouched.
- `xml.dom.minidom` parse OK; 80 records by entity: 1 EnumerationType, 12 Enumeration,
  12 SourceSystemConnector, 42 SourceSystemConnectorField, 13 EntityFilter.

## Verification

Run 2026-10-19 from the wrapper root, JDK 21.0.11, with the local dev stack up (it was running at
the start and was left running).

Focused tests only, per standing instruction; the full suites were not run.

**Backend:** `./gradlew :runtime:component:darpan:test --rerun` over the eight classes that read
the version, the contract or `upgrade-data.xml`: `ApiContractGeneratorTests`,
`FacadeContractSnapshotTests`, `RuleEngineSupportCompileGateTests`,
`RunFlowchartSchemaContractTests`, `AutomationEntityContractTests`,
`SourceFilterEntityContractTests`, `TenantOwnedEntityFilterContractTests`,
`SavedRunsFacadeSmokeTests`. Task executed (not UP-TO-DATE), exit 0, **8 classes, 90 tests,
0 failures**, counted from result XMLs newer than the run. The first run failed
`SourceFilterEntityContractTests.neitherTableShipsSeedOrUpgradeRows`, which had been red on `main`
since `f112c78`. The test was fixed (see the technical changelog); the re-run above is after the fix
and after the final header edit.

**Contract:** `scripts/check_contract_compat.py` v1.5.1 → HEAD: compatible, 110 base methods
preserved, 30 added, version 3. `generateApiContract`: 140 methods.

**App:** `npx vitest run src/lib/api/__tests__/contractDrift.spec.ts` failed on `main` before the
re-sync (11 lifecycle methods missing from the contract copy) and passes after it.
`npx vitest run src/lib/api`: 10 files, 89 tests, 0 failures. `npm run type-check`: exit 0 after the
types were regenerated.

**Docs:** `scripts/check_service_catalog.py` against the 2.0.0 `methods.txt`: OK, 140 of 140
documented. The public OpenAPI filter was proven by reproducing the existing 1.5.1 public copy
byte-for-byte from the `v1.5.1` contract before it was applied to 2.0.0.

**Pins:** `verify_release_pins.py --offline`: exit 0 on the tree. Negative check: a fake root
pinning `DARPAN_HOTWAX_REF=v0.8.0` against darpan 2.0.0 fails with the new pairing message. The
online run cannot pass until the tags exist on the remotes; it must be run after the tags are pushed.

**Release pack:** `release_preflight.py validate --version 2.0.0 --backend-previous-ref v1.5.1
--backend-current-ref HEAD --ui-previous-ref v2.6.1 --ui-current-ref HEAD`: passed, exit 0
(sections, no placeholders, open issues 0, every upgrade record backed by the seed diff, pack
file identical to the load target).

## Not verified

- **Full suites not run** (darpan `unitTest`/`smokeTest`, the four connector suites, `npm run check`).
  Connector repos changed only `component.xml` in this cut; no connector test was run.
- **No live run, no browser check, no upgrade-data load** on any database. Ordering is argued and
  record-checked, not observed.
- **`verify_release_pins.py` online**: owed after the tags are pushed.
- **The forced `RETIRED_FIELDS` run** has not been exercised where the migration is already applied.
- **Mintlify build**: run by the docs pass (see its report); not part of this pack's evidence.

## Approval

Prepared and verified as above; release metadata committed locally in each repo, **not tagged, not
pushed, not published**. Tag pushes, GitHub release pages, the docs publish and every deploy wait
for Aditi.
