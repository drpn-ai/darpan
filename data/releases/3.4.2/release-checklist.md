# Release Checklist For Darpan 3.4.2

## Backlog prework

- Included backlog item IDs (`roadmap/BACKLOG.md`): DAR-BE-341, DAR-BE-339
- Open release issues: 0
- Scope changes recorded on the backlog items: DAR-BE-339 was waiting for "the next darpan release after 3.4.1"; it
  rides this hotfix with DAR-BE-341 (both on `main` after `v3.4.1`, nothing else in the range).
- Deferred items named and marked on the backlog: DAR-BE-340 (deadlock leaves a pooled connection in ROLLBACK ONLY;
  fix shape is Aditi's call). The `run#AutomationNow` 60-second 500 on a committed run is noted, not filed.

## Release notes

- User-facing release notes drafted: yes, `release-notes.md`; public notes folded into the 3.4 section of darpan-docs
  `releases/updates.mdx` (`## v3.4.2`).
- Operator-visible changes reviewed: yes, none to config, jobs, migrations or settings.
- Release notes link or path: `release-notes.md`

## Technical changelog

- Technical changelog curated from compare ranges, not copied from raw commit log: yes.
- Technical changelog link or path: `technical-changelog.md`

## Upgrade data

- Generic source data file updated for every release upgrade record: n/a, no seed file changed in `v3.4.1..v3.4.2`.
- Candidate diff reviewed: yes, empty.
- Final load path decided: none; the boot's `darpan-seed` load finds nothing new.
- Previous upgrade data archived under prior tag folder: yes, `data/releases/3.4.1/upgrade-data.xml`, byte-identical
  to `v3.4.1`'s `data/upgrade-data.xml`.

## Documentation

- Every shipped item has a docs decision in `docs-impact.md`: yes.
- `verify_release_docs.py` exit status (before tagging): 0 (9/9), with `--sdl-url https://api.drpn.ai/rest/s1/graphql/sdl`
  (prod's 3.4.1 SDL; the schema is unchanged in the range) instead of a local stack at the release commit.
- Docs repos pushed (Mintlify publishes from `main`): darpan-docs `84ff304`, darpan-developers `7bd950e`; `mint
  broken-links` and `mint validate` pass in both (Node 20).
- Post-deploy GraphQL re-check against prod (`--sdl-url https://api.drpn.ai/rest/s1/graphql/sdl`) owed: yes.

## Verification

- Backend checks complete: focused classes from `cleanTest`, JDK 21 — RequestedTenantSmokeTests,
  ScheduledAutomationTenantContextTests, ExchangePairVerificationSupportTests, ApiContractGeneratorTests,
  FacadeContractSnapshotTests: 45 tests, 0 failures (9 + 11 + 16 + 8 + 1), no zero-byte result files. The contract
  generator first failed on a stale local darpan-agent (v0.4.0+2, missing send#AgentQueryTurn that prod's v0.5.0 has);
  after fast-forwarding it to v0.5.0 it passed with the committed contract (version line only).
- UI checks complete: n/a, no UI change.
- Live/deployed smoke coverage noted: after deploy, Run now (CLI) of Malbon 100459, RAILS 100053, Dolls Kill
  100114/100115 for the 2026-11-23 15:30 IST slot; their record counts and differences in line with recent days.
- Unverified items called out: full suites not run (focused classes only, by standing rule); no local stack run.

## Approval

- Release owner sign-off: Aditi, "do it all" (2026-11-23 16:40 IST) after "yes, add the backlog item and fix it".
- Cut/tag blocked until this checklist is fully complete: yes.
