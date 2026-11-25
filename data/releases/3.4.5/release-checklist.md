# Release Checklist For Darpan 3.4.5

## Backlog prework

- Included backlog item IDs (`roadmap/BACKLOG.md`): DAR-BE-353, DAR-BE-354, DAR-BE-345 (option B), DAR-BE-352,
  DAR-UI-141 (backend half), DAR-UI-140 (mascot seed)
- Open release issues: 0. The next-release todo (DAR-REL-007) has no unticked line.
- Scope: Aditi 2026-11-25 12:17 IST, "push anything else pending.. then cut a hotfix and deploy": everything on darpan
  `main` after `v3.4.4` and darpan-hotwax `main` after `v1.3.0`. No darpan-ui release (the mascot glossary text fix is
  data served by the backend; darpan-ui only holds its source).
- Deferred and named on the backlog: per-rule scoping of RULE causes (DAR-BE-353); grace for Shopify orders HotWax has
  not received (DAR-BE-354); the tenant config steps after deploy (release notes, "After deploy").

## Release notes

- User-facing release notes: `release-notes.md`; public notes fold into the 3.4 section of darpan-docs
  `releases/updates.mdx` (`## v3.4.5`).
- Operator-visible: upgrade data loads on boot (2,325 records); the image needs darpan-hotwax `v1.3.1`.

## Technical changelog

- Curated from the compare ranges: `technical-changelog.md`.

## Upgrade data

- Generated with `release_preflight.py generate --backend-previous-ref v3.4.4` in a throwaway worktree of darpan
  `origin/main` (gotcha 3b), then: the app content seed regenerated once more after a fix (below) and taken whole
  (2,323 records with seed mark `1a16ffaf3499f68a`: content loads whole with its mark, as in 3.4.0/3.4.1), plus the two
  darpan-hotwax `v1.3.1` records (OMS_GQL connector, isExchangeOrder field), verbatim. 2,325 records, parse-checked.
- `upgrade-data-review.md` is the generator's review of the first draft (seed mark `2e40644a6795429f`); the only
  content difference since is the one glossary text below.
- Fix found by the release tests: `AppContentRulesTests.acceptsTheShippedWords` failed on main since `cd703e78`
  (glossary `queryDataName` body held `input.<name>`, and bodies may not contain `<` or `>`). Fixed at the source
  (darpan-ui `src/lib/mascotGlossary.ts`) and the seed regenerated with `npm run mascot:seed`; `mascot:seed:check` passes.
- Previous upgrade data archived: `data/releases/3.4.4/upgrade-data.xml`, byte-identical to `v3.4.4`'s
  `data/upgrade-data.xml`.

## Validation

- Focused tests, Gradle via the shared lock, `--rerun`, JUnit XML counted (no zero-byte files):
  ApiContractGeneratorTests 8/8, FacadeContractSnapshotTests 1/1, ConclusionEntityContractTests 11/11,
  AppContentRulesTests 11/11 (after the fix), CliLoginSmokeTests 29/29, AuditReasonsReachTests 9/9,
  BrainFactAdapterTests 11/11. darpan-hotwax suites ran green in the DAR-BE-345 lane on the released commit's code
  (KeepExchangeOrdersOptionTieTests 3/3, OmsExtractSupportTests 18/18, OmsOrdersKeepParityTests 5/5,
  OmsExtractScriptCharacterizationTests 24/24, OmsGqlOrdersDataDefinitionTests 8/8, ExcludedRecordsSidecarTieTests 1/1).
- Not run: the full suites (workspace rule: focused tests only).
- `openapi.json` version set to 3.4.5 by hand (the only line the generator changes for a version bump);
  ApiContractGeneratorTests confirms it.
- `verify_release_pins.py`: run after the docker-config pin move, before its tag.
- `verify_release_docs.py`: docs written in parallel with the deploy (as on 3.4.4); run against the prod SDL after
  deploy. GraphQL schema unchanged (moqui-gql stays `v1.1.1`).
