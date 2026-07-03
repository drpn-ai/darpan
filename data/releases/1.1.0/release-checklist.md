# Release Checklist For Darpan 1.1.0

## Linear prework

- Release tracking issue: `DAR-139`
- Included issue IDs: `DAR-114`
- Open release issues: 0
- Scope changes documented in Linear: `DAR-139`
- Deferred issues moved to the next release: `DAR-115`, `DAR-117`, `DAR-134`

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
- Final load path decided: `upgrade-data.xml` for targeted existing-environment `seed` loads; `./gradlew loadDarpanSetup` for fresh/full setup
- Upgrade data file link or path: `upgrade-data.xml`

## Verification

- Backend checks complete: yes
- UI checks complete: yes
- Live/deployed smoke coverage noted: yes, deferred to post-cut validation in `LIVE_TESTING.md`
- Unverified items called out: yes

## Approval

- Release owner sign-off: user-requested cut tracked in `DAR-139`
- Cut/tag blocked until this checklist is fully complete: checklist complete, tagging unblocked
