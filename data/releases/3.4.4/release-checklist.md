# Release Checklist For Darpan 3.4.4

## Backlog prework

- Included backlog item IDs (`roadmap/BACKLOG.md`): DAR-BE-351, DAR-BE-347, DAR-BE-348, DAR-BE-350, DAR-BE-252,
  DAR-BE-345, DAR-BE-346, DAR-BE-340, DAR-OPS-015
- Open release issues: 0
- Scope changes recorded on the backlog items: Aditi 2026-11-24 18:11-18:13 IST, "deploy the backend", "cut a
  hotfix", "dont cut UI yet.. just backend": everything on darpan `main` after `v3.4.3`, with the component tags its
  commits need (moqui-gql `v1.1.1`, darpan-agent `v0.5.1`, database-darpan `v0.4.1`). No darpan-ui release.
- Deferred items named and marked on the backlog: DAR-BE-350's and DAR-BE-348's app halves (next darpan-ui release);
  DAR-BE-340's pool fix (Aditi's call); DAR-BE-348's Malbon priority levels (tenant data after the deploy).

## Release notes

- User-facing release notes drafted: yes, `release-notes.md`; public notes folded into the 3.4 section of darpan-docs
  `releases/updates.mdx` (`## v3.4.4`).
- Operator-visible changes reviewed: yes: upgrade data loads on boot (53 records); job lock expiry 6 h; progress
  fields; patched dependencies. The image needs moqui-gql `v1.1.1`, darpan-agent `v0.5.1`, database-darpan `v0.4.1`.
- Release notes link or path: `release-notes.md`

## Technical changelog

- Technical changelog curated from compare ranges, not copied from raw commit log: yes.
- Technical changelog link or path: `technical-changelog.md`

## Upgrade data

- Generic source data file updated for every release upgrade record: yes (`AuditSeedData.xml`, the 11 ServiceJob seed
  files, `EmailCopySeedData.xml`); `release_preflight.py validate` accepts the 35 audit vocabulary records as a
  deliberate restatement (present verbatim in `AuditSeedData.xml`).
- Candidate diff reviewed: yes. The generated file (18 records) and DAR-BE-348's hand-prepared `data/upgrade-data.xml`
  (41) were merged: 6 shared, 12 only generated (DAR-BE-340 ServiceJobs, DAR-BE-252 `cause.explain`), 35 only
  DAR-BE-348's (restated vocabulary); 53 records, parse-checked.
- Final load path decided: the boot's seed + upgrade load; `./gradlew loadDarpanUpgradeData` for a targeted load.
- Previous upgrade data archived under prior tag folder: yes, `data/releases/3.4.3/upgrade-data.xml`, byte-identical
  to `v3.4.3`'s `data/upgrade-data.xml`.

## Documentation

- Every shipped item has a docs decision in `docs-impact.md`: yes.
- `verify_release_docs.py` exit status (before tagging): see the release report.
- Post-deploy GraphQL re-check against prod (`--sdl-url https://api.drpn.ai/rest/s1/graphql/sdl`) owed: yes.

## Verification

- Contract: `check_contract_compat.py v3.4.3 -> v3.4.4` compatible, 319 methods, version 5; `generateApiContract` on
  `main` showed no drift before the version line moved.
- Backend checks: run on the release commit — see the release report.
- Deploy pins: `verify_release_pins.py` after the docker-config commit — see the release report.
- UI checks: not part of this release (no darpan-ui cut).
- Unverified items called out: full suites not run (focused classes only, by standing rule).

## Approval

- Release owner sign-off: Aditi, "cut a hotfix" / "dont cut UI yet.. just backend" (2026-11-24 18:13 IST).
- Cut/tag blocked until this checklist is fully complete: yes.
