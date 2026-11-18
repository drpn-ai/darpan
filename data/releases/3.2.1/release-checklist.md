# Release Checklist For Darpan 3.2.1

## Backlog prework

- Included backlog item IDs (`roadmap/BACKLOG.md`): DAR-BE-302
- Open release issues: 0
- Scope changes recorded on the backlog items: Aditi made aditya.patel a Darpan admin (not super admin) on prod, so
  the two-super-admins guard blocked the everyone date; "yes" to the fix, then "push, tag and deploy 3.2.1".
- Deferred items named and marked on the backlog: `hasWayBackIn` read without the person lock; two CLI specs assert a
  non-zero exit instead of the exact code.

## Release notes

- User-facing release notes drafted: yes, `release-notes.md`; public notes folded into the 3.2 section of darpan-docs
  `releases/updates.mdx` (b0c72a4).
- Operator-visible changes reviewed: yes.
- Release notes link or path: `release-notes.md`

## Technical changelog

- Technical changelog curated from compare ranges, not copied from raw commit log: yes.
- Compare URLs captured: yes.
- Technical changelog link or path: `technical-changelog.md`

## Upgrade data

- Generic source data file updated for every release upgrade record: yes, none: no seed changed in `v3.2.0..HEAD`.
- Candidate diff reviewed: yes, empty.
- Final load path decided: nothing to load.
- Current upgrade data file link or path: `runtime/component/darpan/data/upgrade-data.xml` (no records)
- Previous upgrade data archived under prior tag folder: yes, `data/releases/3.2.0/upgrade-data.xml`, byte-identical
  to `v3.2.0`'s.

## Documentation

- Every shipped item has a docs decision in `docs-impact.md`: yes.
- `verify_release_docs.py` exit status (before tagging): run in the cut; "service catalog" reads the working-tree
  contract, which carries the CRM session's 28 uncommitted methods (release contract 268/268).
- Docs repos pushed: in the cut (darpan-docs b0c72a4, darpan-developers d946f25 + 5594690).
- Post-deploy GraphQL re-check against prod owed: yes (schema unchanged).

## Verification

- Backend checks complete: yes. JDK 21, `--rerun`, 2026-11-18 14:1x IST: ApiContractGeneratorTests 8/8,
  FacadeContractSnapshotTests 1/1, TwoFactorSignInSmokeTests 40/40, PasskeySignInSmokeTests 27/27,
  CliLoginSmokeTests 28/28, TwoFactorReminderSmokeTests 6/6. Opus review of DAR-BE-302: approved (no
  Critical/Important); it also built and tested the committed darpan-admin-ui tree on its own (type-check, eslint,
  88 specs).
- UI checks complete: darpan-admin-ui vitest SecurityPage + cli 136 pass, type-check clean; darpan-ui type-check
  clean, contractDrift pass.
- Deploy pins: `verify_release_pins.py` runs in the cut.
- Unverified items called out: full suites not run; the Security page not seen in a browser.

## Approval

- Release owner sign-off: Aditi, "push, tag and deploy 3.2.1" (2026-11-18 14:03 IST).
