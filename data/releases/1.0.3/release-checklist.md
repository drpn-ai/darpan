# Release Checklist For Darpan 1.0.3

## Linear prework

- Issue-tracker prework: not required (Linear-first rule retired 2026-07-01).
- Included work: `5b059e9` NsRestletConfig row-size fix, `9df7029` SecuritySeedData
  ordering fix, `b7a894d` DB_LOAD runbook fix + this release-prep commit (pin +
  version metadata + pack).
- Open release issues: none.
- Deferred issues: listed in `release-notes.md`.

## Release notes

- User-facing release notes drafted: yes — `release-notes.md`.
- Operator-visible changes reviewed: yes (seed re-run command, DB_LOAD correction,
  DARPAN_REF pin).
- Release notes link or path: `release-notes.md`

## Technical changelog

- Technical changelog curated from compare ranges, not copied from raw commit log: yes.
- Compare URL captured: `https://github.com/drpn-ai/darpan/compare/v1.0.2...v1.0.3`
- Technical changelog link or path: `technical-changelog.md`

## Upgrade data

- Generic source data file updated for every release upgrade record: n/a — no upgrade
  records in range (seed reorder moves existing records; entity change is schema-only).
- Candidate diff reviewed: yes — empty; see `upgrade-data-review.md`.
- Final load path decided: no data-load action for healthy environments; environments
  where a pre-1.0.3 darpan-seed load rolled back re-run
  `types=darpan-seed-initial,darpan-seed` (documented in `release-notes.md`).
- Current upgrade data file link or path: `runtime/component/darpan/data/upgrade-data.xml` (empty candidate)
- Previous upgrade data archived under prior tag folder: yes —
  `data/releases/1.0.2/upgrade-data.xml`.

## Verification

- Pack validation (`release_preflight.py validate --version 1.0.3`): passed.
- XML well-formedness (`SecuritySeedData.xml`, `ReconciliationEntities.xml`,
  `component.xml`, upgrade files): passed.
- SecuritySeedData forward-reference order check (script): passed — every
  ArtifactAuthz `userGroupId` defined earlier in file or framework-owned.
- `AutomationEntityContractTests`: 7/7 passed (forced rerun).
- OpenAPI snapshot regenerated for version bump; method set unchanged.
- Full backend test suite: NOT awaited before tagging (release owner's standing
  hotfix-speed call, same as v1.0.2); CI monitored on the pushed tag.
- Live/deployed smoke: not run at authoring time; `sm-darpan-maarg` rebuild on this
  tag is the first consumer.

## Approval

- Release owner: aditi.patel@hotwax.co ("do all changes now", 2026-07-03 — fixes,
  tag, consolidated release page, docs refresh).
- Tag placement: `v1.0.3` on the release-prep commit on `main`.
