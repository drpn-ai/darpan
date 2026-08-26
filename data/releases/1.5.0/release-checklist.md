# Release Checklist For Darpan 1.5.0

## Linear prework

- Release tracking issue: not applicable — the Linear rule was retired 2026-07-01 and Linear is no
  longer the source of truth.
- Included issue IDs: not applicable as tickets. Work in this range carries internal requirement ids
  in commit subjects — DAR-BE-005 (cross-tenant config sharing) and DAR-BE-018 (returns
  reconciliation) — mapped in `technical-changelog.md`.
- Open release issues: 0
- Deferred items: recorded in the "Deliberately not in this release" and "Known gaps" sections of
  `release-notes.md`, not implied.

## Scope

- Scope derived from compare ranges, not from a ticket list: yes. `v1.4.0..HEAD` across five repos —
  136 + 20 + 28 + 7 backend commits and 58 app commits, 249 commits total, every one accounted for
  in `technical-changelog.md` (249 unique hashes, no commit in two buckets).
- Release numbering reconciled: yes. A `data/releases/1.6.0/` pack had been staged on 2026-08-21 in
  the expectation that Slack would follow sharing as a separate release. No `1.5.0` was ever cut, so
  both landed in one range; the 1.6.0 pack's three chat-provider records were verified absorbed into
  the 1.5.0 payload and the folder was removed. Leaving it would have advertised a 1.6.0 upgrade step
  that never existed.
- Unrelated work kept out: yes. `moqui-gql` is not tagged, pinned or announced, by decision.

## Release notes

- User-facing release notes drafted: yes — `release-notes.md`, curated from commit bodies rather than
  pasted from the log.
- Operator-visible changes reviewed: yes. Three required actions (upgrade-data load, then
  `run#PendingMigrations`, then the production image change), plus two behaviour changes on existing
  data that are called out explicitly rather than buried: blank CSV cells now read as empty values,
  and endpoints are disabled until enabled.
- Public docs updated: yes — `darpan-docs` `releases/updates.mdx` (the "Unreleased" section promoted
  to `v1.5.0`, with Upgrade and Rollback paragraphs added and the API paragraph corrected) and
  `releases/roadmap.mdx` (Recently shipped rotated to the two newest tags; returns reconciliation
  removed from "Up next" as shipped; remaining steps renumbered).
- Release notes link or path: `release-notes.md`

## Technical changelog

- Curated from compare ranges, not copied from the raw log: partly, and stated honestly. The prose
  preamble ("Structural changes worth reading before the lists") is hand-written; the per-repo lists
  are the full commit set, bucketed by a deterministic rule with a uniqueness assertion, because the
  conventional-commit prefix labels 59 of 128 backend commits `feat` and is not a useful split on its
  own.
- Compare URLs captured: yes — five, in `technical-changelog.md`.
- Technical changelog link or path: `technical-changelog.md`

## Upgrade data

- Generic source data file backs every release upgrade record: yes, and verified mechanically —
  54/54 records matched a record in `data/*.xml` attribute-for-attribute. Records were extracted
  verbatim from those files rather than retyped.
- Candidate diff reviewed: yes — `upgrade-data-review.md`, 51 added and 3 modified.
- Record ordering re-checked parent-before-child: yes, and by program rather than by eye. Three FK
  chains are asserted: `EnumerationType` before its `Enumeration` rows; a parent `Enumeration` before
  any child naming it in `parentEnumId`; and `Enumeration` before `SourceSystemConnector` before
  `SourceSystemConnectorField`. No child precedes its parent. The two external parents the field rows
  depend on (`OMS` and `SHOPIFY` connector rows) have existed since v1.0.
- Gap found and closed: the working-copy `upgrade-data.xml` was still the 1.4.0 payload plus
  accumulated drift, and was missing two whole seed files added since that tag —
  `MigrationRegistrySeedData.xml` (6 records) and `SourceSystemConnectorFieldSeedData.xml` (29
  records) — plus the `DATABASE` connector row, the chat-provider catalog and the shared-config
  catalog. Assembling from the generic files rather than editing the existing file is what surfaced
  them; a 5-record file had been staged where 54 were owed.
- Previous upgrade data archived under the prior tag folder: yes, and re-archiving was deliberately
  skipped. `data/releases/1.4.0/upgrade-data.xml` was verified byte-identical to
  `git show v1.4.0:data/upgrade-data.xml` with `cmp` before any change, so the archive was already
  correct; allowing a generator to archive the drifted working file would have overwritten a faithful
  archive with post-release content. Confirmed intact afterwards.
- Current file and release-pack mirror byte-identical: yes.
- Final load path: `./gradlew loadDarpanUpgradeData`, then
  `admin.MigrationAdminServices.run#PendingMigrations` once.
- Current upgrade data file path: `runtime/component/darpan/data/upgrade-data.xml`

## Verification

- Backend checks complete: yes, under JDK 21 with `--rerun-tasks`, each component in its **own**
  gradle invocation so that a failure in one could not abort the others.

  | Component | Classes | Tests | Failures | Errors | Skipped |
  | --- | --- | --- | --- | --- | --- |
  | `darpan` unitTest | 79 | 896 | 0 | 0 | 0 |
  | `darpan` smokeTest | 48 | 377 | 0 | 0 | 0 |
  | `darpan-hotwax` | 11 | 169 | 0 | 0 | 0 |
  | `shopify-darpan` | 15 | 135 | 0 | 0 | 0 |
  | `database-darpan` | 13 | 100 | 0 | 0 | 8 |
  | **Total** | **166** | **1677** | **0** | **0** | **8** |

- Green confirmed as real, not cached: yes. All tasks reported as *executed*, not `UP-TO-DATE`;
  counts were read from the JUnit XML in `build/test-results/`, not from the words "BUILD
  SUCCESSFUL"; no result file was 0-byte or unparseable; exit codes were captured directly rather
  than through a pipe, which would have returned the exit code of the last command in the pipeline.
- Test-count discrepancies chased rather than accepted: yes, two.
  1. `darpan`'s smoke pool announces 49 classes but produced 48 result files. The missing class is
     `LoginDeadlockRegressionTests`, which carries `@Tag("mysql")` and is excluded from both pools by
     design; it needs a real MySQL server and belongs to the separate `mysqlTest` task. **Its 4 tests
     did not run in this cut.**
  2. The retired `test` pool directory holds a **separate full run** (127 classes / 1277 tests,
     timestamped 18:38) made by another session, while this cut's `testAll` writes only `unitTest`
     and `smokeTest` (both 19:16). Counting every XML under `build/test-results/` would have
     double-counted the same classes and reported 293 classes / 2954 tests. The table above counts
     only the two pools `testAll` actually wrote. Provenance, not age, separates them — the stale
     directory was just 40 minutes old.
- `database-darpan`'s 8 skips identified, not waved through: yes — all are live-database integration
  tests (`PostgresIntegrationTests`, `MySqlIntegrationTests`, `Db2IntegrationTests`, and two
  Postgres-backed smoke cases) gated on a reachable server. End-to-end extraction and the read-only
  fence have no evidence from this cut.
- UI checks complete: yes — `npm run check` at version `2.6.0`, all four sub-checks executed:
  `eslint --max-warnings=0`, `stylelint --max-warnings=0` (the design-system gate),
  `vue-tsc --build --force` (forced, so not a cache hit), and `vitest run --coverage` at
  **113 files / 1175 tests / 0 failures**.
- API contract regenerated for the version bump: yes — `docs/api-contract/openapi.json`;
  `methods.txt` unchanged at 110 methods.
- API contract compatibility checked: yes, and it **failed first**. `check_contract_compat.py`
  against `v1.4.0` reported 20 additive methods plus one breaking change —
  `list#TenantChatSpaces` no longer returns `chatSpaces[].googleChatWebhookUrlMasked`, removed in
  `89d1522` when chat webhooks moved to clear text. The break was already merged to `main` and
  already present in the committed contract; `FacadeContractSnapshotTests` did not catch it because
  that snapshot was regenerated as an accepted change in `72e30a8`, and the compat script is a manual
  gate rather than a test. Resolved by consciously bumping `CONTRACT_VERSION` from 2 to 3 (with the
  reason recorded at the constant), regenerating, and documenting the removal and its replacements in
  the release notes and the public docs. Re-run reports the break as accepted.
- Rollback claim verified rather than assumed: yes. `v1.4.0`'s contract was inspected directly and
  returns *both* `googleChatWebhookUrlMasked` and `googleChatWebhookUrl`, so an integration on the
  latter survives a rollback; `webhookUrl` and `webhookConfigured` are new in `v1.5.0` and an
  integration on those does not. Both statements are in the release notes.
- Version metadata bumped: yes — `darpan` 1.5.0, `darpan-hotwax` 0.8.0, `shopify-darpan` 0.6.0
  (correcting a long-standing drift: it still read `0.2.1` at tags `v0.3.0`–`v0.5.0`),
  `database-darpan` 0.2.0, and `darpan-ui` 2.6.0.
- Lock file edited safely: yes — `package-lock.json` changed at its root `version` and
  `packages[""].version` only, via a JSON load/dump. The string `"2.5.0"` appears three times in that
  file; the third is the `cacheable` dependency, which a global replace would have silently rewritten
  and broken `npm ci` on a fresh clone after the tag was cut.
- Production image reviewed, not assumed: yes, and a gap was found. `docker/prod/Dockerfile` never
  cloned `database-darpan` — only the UAT Dockerfile did — while this release seeds an enabled
  `DATABASE` connector row naming a service that lives only in that component. Added at `v0.2.0`; the
  two images now carry identical component sets.

## Not verified

- No live reconciliation run against a real OMS, Shopify or database source.
- No browser pass over the release candidate; UI evidence is `npm run check` only.
- No image was built or deployed from these tags.
- Slack delivery never proven end to end — no observed message in a real workspace.
- `run#PendingMigrations` never run against a real upgraded database.
- The upgrade-data file was never loaded into any database; FK ordering is verified structurally, not
  by a successful load.
- `LoginDeadlockRegressionTests` (4 tests) and `database-darpan`'s 8 live-database integration tests
  did not execute.

## Known gaps carried forward

- `moqui-gql` remains pinned to `main` in the production image, so two builds of the same release tag
  can differ. Left as-is by decision; it should be tagged and pinned before the next cut.
- `netsuite-darpan`'s `component.xml` reads `0.1.0` at tag `v0.2.0`. Not corrected — it has no
  changes to publish in this range.

## Approval

- Prepared by: Claude Code, 2026-08-26, on behalf of aditi.patel@hotwax.co.
- Release owner sign-off: **pending**. Tags and branches are pushed; GitHub release pages, image
  build and deployment are deliberately not done and remain the release owner's call.
- Outstanding decisions recorded in this pack: `moqui-gql` left out of the release, contract version
  bumped to 3, and `database-darpan` added to the production image — each confirmed with the release
  owner during preparation.
