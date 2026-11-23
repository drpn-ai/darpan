# Release Checklist For Darpan 3.4.1

## Backlog prework

- Included backlog item IDs (`roadmap/BACKLOG.md`): DAR-BE-338
- Open release issues: 0
- Scope changes recorded on the backlog item: Aditi chose "own copy again" (14:40 IST) over pinning a run version; keys
  and record root stay the run's (open question for Aditi, as before DAR-BE-318).
- Deferred items named and marked on the backlog: a 3.4.0 config version of a run that already had several scopes,
  restored after its scopes changed, may take a sibling scope's filters (pre-existing placement from 3.4.0).

## Release notes

- User-facing release notes drafted: yes, `release-notes.md`; public notes folded into the 3.4 section of darpan-docs
  `releases/updates.mdx`.
- Operator-visible changes reviewed: yes, including the unfiltered window before `AUTOMATION_FILTER_COPY` and rollback.
- Release notes link or path: `release-notes.md`

## Technical changelog

- Technical changelog curated from compare ranges, not copied from raw commit log: yes.
- Technical changelog link or path: `technical-changelog.md`

## Upgrade data

- Generic source data file updated for every release upgrade record: yes, `data/MigrationRegistrySeedData.xml`,
  `data/SecuritySeedData.xml`, `data/AppContentSeedData.xml`; the `AUTOMATION_FILTER_TABLE` row is a hand row (its seed
  row was removed; seed loads never delete).
- Candidate diff reviewed: yes.
- Final load path decided: the boot's `darpan-seed` load, then `run#PendingMigrations`.
- Previous upgrade data archived under prior tag folder: yes, `data/releases/3.4.0/upgrade-data.xml`, byte-identical
  to `v3.4.0`'s.

## Documentation

- Every shipped item has a docs decision in `docs-impact.md`: yes.
- `verify_release_docs.py` exit status (before tagging): run in the cut (offline; schema unchanged).
- Post-deploy GraphQL re-check against prod (`--sdl-url https://api.drpn.ai/...`) owed: yes (schema unchanged).

## Verification

- Backend checks complete: yes, JDK 21, 2026-11-23, from `cleanTest`: 30 darpan classes, 478 tests, 0 failures
  (automation execution, Keep parity, sync pulse, automation facade and sync, audit settings, agent levers, every
  migration class, authz ratchet, config transfer, tenant config automations, entity filter contracts, facade contract
  snapshot, API contract generator) + darpan-hotwax HotWaxRollingCopyParityTests 10/10.
- UI checks complete: vitest (automation draft, workflow page, mascot popup) 59/59, type-check, lint, mascot seed.
- CLI: `node --test` 110/110, `test-darpan.sh` 24/24.
- Whole-change review (opus): 0 Critical; I1 fixed (record root), I2 and I3 in the deploy and rollback notes; M1 fixed;
  M3 test added; M4 no change (the automation row carries the same user FK).
- Live/deployed smoke coverage noted: after deploy, `AUTOMATION_FILTER_COPY` dry run then run (rowsWritten >= 80),
  spot-check MALBON 100513 FILE_1 carries productId EXCLUDE_IN 142036, then the scheduler back on.
- Unverified items called out: full suites not run (focused classes only); no browser check of the automation page.

## Approval

- Release owner sign-off: Aditi, "native, push and deploy as a hotfix" (2026-11-23 14:35 IST).
- Cut/tag blocked until this checklist is fully complete: yes, complete.
