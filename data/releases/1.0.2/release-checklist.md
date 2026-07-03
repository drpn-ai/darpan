# Release Checklist For Darpan 1.0.2

## Linear prework

- Issue-tracker prework: not required (Linear-first rule retired 2026-07-01).
- Included work: `8adb751` conf-expression fix + this release-prep commit
  (pins + version metadata + pack).
- Open release issues: none.
- Deferred issues: listed in `release-notes.md`.

## Release notes

- User-facing release notes drafted: yes — `release-notes.md`.
- Operator-visible changes reviewed: yes (exec'd data loads, image pins).
- Release notes link or path: `release-notes.md`

## Technical changelog

- Technical changelog curated from compare ranges, not copied from raw commit log: yes.
- Compare URL captured: `https://github.com/drpn-ai/darpan/compare/v1.0.1...v1.0.2`
- Technical changelog link or path: `technical-changelog.md`

## Upgrade data

- Generic source data file updated for every release upgrade record: n/a — no upgrade
  records in range.
- Candidate diff reviewed: yes — empty; see `upgrade-data-review.md`.
- Final load path decided: no data-load action for this tag.
- Current upgrade data file link or path: `runtime/component/darpan/data/upgrade-data.xml` (empty candidate)
- Previous upgrade data archived under prior tag folder: yes —
  `data/releases/1.0.1/upgrade-data.xml`.

## Verification

- Pack validation (`release_preflight.py validate --version 1.0.2`): passed.
- XML well-formedness (conf, component.xml, upgrade files): passed.
- `compileGroovy` + `checkApiContract`: passed (65 methods).
- Conf-fix behavior: reproduced the crash and verified both fallback branches with a
  Groovy 4.0.24 SystemBinding-equivalent harness.
- Full backend test suite: NOT awaited before tagging (release owner's explicit call
  for hotfix speed); CI runs on the pushed commit and is monitored after the fact.
- Live/deployed smoke: not run.

## Approval

- Release owner: aditi.patel@hotwax.co (requested hotfix + immediate tag 2026-07-03,
  explicitly waiving the pre-tag CI wait).
- Tag placement: `v1.0.2` on the release-prep commit on `main`.
