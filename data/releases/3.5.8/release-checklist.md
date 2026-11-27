# Release Checklist For Darpan 3.5.8

## Backlog prework

- Included backlog item IDs (`roadmap/BACKLOG.md`): DAR-BE-386, DAR-BE-388, DAR-BE-389, DAR-BE-390, DAR-BE-391, DAR-BE-393,
  DAR-BE-395, DAR-UI-159, DAR-UI-161, DAR-UI-163 (seeds).
- Open release issues: 0
- Scope: Aditi 16:47 IST (hotfix), 17:04 IST ("include anything that comes in"), 17:13 IST (one roll that waits for DAR-BE-395).
  darpan `main` after `v3.5.7`: 2bc28287 (DAR-BE-393), 12399442 (DAR-BE-391 no logo), d5ee310f (DAR-BE-395). 3.5.7 (eac29a95)
  was cut and never deployed; 3.5.8 is deployed in its place. Every active session was told at 16:48 and 17:05 IST.
- Deferred items named and marked on the backlog: DAR-BE-394, DAR-BE-385.

## Release notes

- `release-notes.md`; public notes: the 3.5 section of darpan-docs `releases/updates.mdx` (3.5.7 folded in).
- Operator-visible changes reviewed: yes.

## Technical changelog

- Curated from `v3.5.6..v3.5.8`: `technical-changelog.md`. Compare: https://github.com/drpn-ai/darpan/compare/v3.5.6...v3.5.8

## Upgrade data

- 2321 records diffed from `v3.5.6` (the version live), so 3.5.7's records load: 2320 from `data/AppContentSeedData.xml`,
  1 `EmailCopyBlock` from `data/OpsNoticeSeedData.xml` (`release_preflight.py generate --backend-previous-ref v3.5.6`).
- Candidate diff reviewed: only those two seed files changed under `data/` since `v3.5.6`.
- Final load path: the image's boot upgrade-data load.
- Previous upgrade data archived: `data/releases/3.5.7/upgrade-data.xml` (unchanged, equals the `v3.5.7` root).

## Documentation

- Every shipped item has a docs decision in `docs-impact.md`: yes.
- `verify_release_docs.py --version 3.5.8 --offline --previous-tag v3.5.6` before the tag.
- Docs repos pushed before the tag. Post-deploy GraphQL re-check against prod owed.

## Verification

- 3.5.7 cut: 354 focused tests, 0 failed. 3.5.8 (`--rerun`, isolated clone): 420 tests on 12399442 (guards + every class
  DAR-BE-391/393 touch), then on d5ee310f with the contract regenerated: ApiContractGeneratorTests, FacadeContractSnapshotTests,
  CliMethodAllowlistTests, DisableAuthzRatchetTest, AppContentRulesTests, TenantOwnedEntityFilterContractTests,
  AuditCheckAuthoringSmokeTests. Not run: the full suites.
- Live: pulse cards previewed in Aditi's test room; prod smoke after the roll (boot log, `/`, next pulse).
- `verify_release_pins.py --version 3.5.8` before the docker-config tag.

## Approval

- Release owner sign-off: Aditi, 2026-11-27 16:47 / 17:13 IST.
- Roll gated on `darpan-admin --target prod live` empty, clear of a pulse (:33), before 18:30 IST or after Rails EU ends.
