# Release Checklist For Darpan 1.1.1

## Linear prework

- Release tracking issue: `DAR-146`
- Included issue IDs: `DAR-84`
- Open release issues: 0
- Scope changes documented in Linear: `DAR-146`
- Deferred issues moved to the next release: `DAR-115`, deployed-environment smoke validation after tag publish

## Release notes

- User-facing release notes drafted: yes
- Operator-visible changes reviewed: yes
- Release notes link or path: `release-notes.md`

## Technical changelog

- Technical changelog curated from compare ranges, not copied from raw commit log: yes
- Compare URLs captured: yes
- Technical changelog link or path: `technical-changelog.md`

## Upgrade data

- Candidate diff reviewed: yes
- Final load path decided: no release-scoped data load required; `upgrade-data.xml` is intentionally empty for `1.1.1`
- Upgrade data file link or path: `upgrade-data.xml`

## Verification

- Backend checks complete: yes
- UI checks complete: yes
- Live/deployed smoke coverage noted: yes, deferred to post-tag validation in `LIVE_TESTING.md`
- Unverified items called out: yes

## Approval

- Release owner sign-off: user-requested cut tracked in `DAR-146`
- Cut/tag blocked until this checklist is fully complete: checklist complete, tagging unblocked
