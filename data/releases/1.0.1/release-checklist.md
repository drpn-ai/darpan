# Release Checklist For Darpan 1.0.1

## Linear prework

- Issue-tracker prework: not required — the Linear-first rule was retired 2026-07-01;
  scope is tracked in this pack and in git history.
- Included work: `v1.0.0..v1.0.1` — archived-pack test-path fix (`b9f1c6c`), companion
  pin fix (`2025a84`), this release-prep commit.
- Open release issues: none.
- Deferred issues: listed in `release-notes.md`.

## Release notes

- User-facing release notes drafted: yes — `release-notes.md`.
- Operator-visible changes reviewed: yes (tag-tree prod build, companion pins).
- Release notes link or path: `release-notes.md`

## Technical changelog

- Technical changelog curated from compare ranges, not copied from raw commit log: yes.
- Compare URL captured: `https://github.com/drpn-ai/darpan/compare/v1.0.0...v1.0.1`
- Technical changelog link or path: `technical-changelog.md`

## Upgrade data

- Generic source data file updated for every release upgrade record: n/a — no upgrade
  records (no seed diff in range).
- Candidate diff reviewed: yes — empty; see `upgrade-data-review.md`.
- Final load path decided: no operator data-load action.
- Current upgrade data file link or path: `runtime/component/darpan/data/upgrade-data.xml` (empty candidate)
- Previous upgrade data archived under prior tag folder: yes —
  `data/releases/1.0.0/upgrade-data.xml`.

## Verification

- Release pack validated (`release_preflight.py validate --version 1.0.1`): passed.
- XML well-formedness (`component.xml`, `data/upgrade-data.xml`, pack mirror): passed.
- Backend compile check (`compileGroovy` via `run_backend_checks.sh`): passed.
- API contract gate (`checkApiContract`): passed (65 methods).
- Full backend test suite: green in CI on the release commit before the tag was placed.
- UI checks: n/a — UI not in this release.
- Not verified: live-deploy smoke (no deployed behavior changes in range).

## Approval

- Release owner: aditi.patel@hotwax.co (requested the tag 2026-07-03 to unblock the
  prod image build).
- Tag placement: `v1.0.1` on the CI-green release-prep commit on `main`.
