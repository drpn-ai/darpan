# Release Checklist For Darpan 3.4.3

## Backlog prework

- Included backlog item IDs (`roadmap/BACKLOG.md`): DAR-UI-138, DAR-BE-344, DAR-OPS-026
- Open release issues: 0
- Scope changes recorded on the backlog items: Aditi 2026-11-23 19:34-19:41 IST, "include all changes from today in
  the hotfix", "include the brain edits": everything on darpan `main` after `v3.4.2` (DAR-UI-138 pins, DAR-OPS-026),
  netsuite-darpan `faaaeac` (DAR-BE-344, the code half of the R4 store-backorder brain edit) and darpan-ui's DAR-UI-138
  commits. The brain edits' tenant halves (Malbon and Gorjana packs) are prod config loads after the deploy.
- Deferred items named and marked on the backlog: DAR-BE-344's Malbon pack (`darpan-config-malbon` 669bafe) and Gorjana
  pack (`darpan-config-gorjana` 2695215) loads; DAR-BE-340 (deadlock, ROLLBACK ONLY; fix shape is Aditi's call). The
  `run#AutomationNow` 60-second 500 on a committed run is noted, not filed. DAR-REL-007's one open line (next morning:
  Malbon R4 has no x-redo finding and no 'Backordered'; Malbon R10 35 -> ~10) is a read, not a release step.

## Release notes

- User-facing release notes drafted: yes, `release-notes.md`; public notes folded into the 3.4 section of darpan-docs
  `releases/updates.mdx` (`## v3.4.3`).
- Operator-visible changes reviewed: yes, none to config, jobs, migrations or settings; the image needs netsuite-darpan
  `v0.7.1`.
- Release notes link or path: `release-notes.md`

## Technical changelog

- Technical changelog curated from compare ranges, not copied from raw commit log: yes.
- Technical changelog link or path: `technical-changelog.md`

## Upgrade data

- Generic source data file updated for every release upgrade record: n/a, no seed file changed in `v3.4.2..v3.4.3`.
- Candidate diff reviewed: yes, empty.
- Final load path decided: none; the boot's `darpan-seed` load finds nothing new.
- Previous upgrade data archived under prior tag folder: yes, `data/releases/3.4.2/upgrade-data.xml`, byte-identical
  to `v3.4.2`'s `data/upgrade-data.xml`.

## Documentation

- Every shipped item has a docs decision in `docs-impact.md`: yes.
- `verify_release_docs.py` exit status (before tagging): 1 before the docs repos are pushed — 7/9 pass with
  `--sdl-url https://api.drpn.ai/rest/s1/graphql/sdl` (prod's 3.4.2 SDL; the schema is unchanged in the range); the
  two failures are "published": darpan-docs and darpan-developers each hold 1 commit not yet on `origin/main`. Re-run
  after pushing them; expected 9/9.
- Docs repos committed (Mintlify publishes from `main` once pushed): darpan-docs `1a92b72`, darpan-developers
  `2d4ab9a`; `mint broken-links` and `mint validate` pass in both (Node 20).
- Post-deploy GraphQL re-check against prod (`--sdl-url https://api.drpn.ai/rest/s1/graphql/sdl`) owed: yes.

## Verification

- Backend checks complete: JDK 21, `--rerun` — ApiContractGeneratorTests 8/8 and FacadeContractSnapshotTests 1/1, 0
  failures, read from the JUnit XML. The contract and snapshot already carried the new method (`cfe684cc`); only the
  version line moved. `docs/api-contract/methods.txt` lists 226 `facade.*` methods.
- Deploy pins: `verify_release_pins.py` after the docker-config commit — see the release report; it needs the
  `v3.4.3` / `v0.7.1` tags on the remotes to pass.
- UI checks complete: not run by the release cut (the DAR-UI-138 session runs its own specs); the release commit
  changes only `package.json` / `package-lock.json`.
- Live/deployed smoke coverage noted: after deploy, app.drpn.ai Dashboard -> Queries -> pin a query -> open it ->
  change its data in the Data popup -> run -> save; old `/settings/tenant/scripts` link redirects.
- Unverified items called out: full suites not run (focused classes only, by standing rule); no local stack run;
  `TenantQueryFacadeSmokeTests` and netsuite-darpan's `NsSuiteQlQueryTemplateTest` / `NsSuiteQlOrderSupportTest` were
  run by their implementing sessions, not re-run here.

## Approval

- Release owner sign-off: Aditi, "when you are done, deploy a hot fix" / "include all changes from today in the
  hotfix" / "include the brain edits" (2026-11-23 19:34-19:41 IST).
- Cut/tag blocked until this checklist is fully complete: yes.
