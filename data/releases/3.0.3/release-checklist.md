# Release Checklist For Darpan 3.0.3

## Scope

Hotfix on the 3.0 line, cut from `main` in darpan and darpan-ui (the only repos with commits since 3.0.2), plus
darpan-docker-config's `DARPAN_REF` pin. Aditi, 2026-11-12: "hotfix and deploy". Local `main` equalled `origin/main` in
every repo before the cut.

## Linear prework

Linear is retired; the backlog (`roadmap/BACKLOG.md`) is the source of truth.

- Release tracking issue: this pack (release prep is `neither`; it has no item of its own).
- Included issue IDs: DAR-BE-254, DAR-UI-112, DAR-BE-251 (seed rows).
- Open release issues: 0
- Scope changes documented in Linear: not applicable; recorded on each backlog item.
- Deferred issues moved to the next release: none; post-deploy checks stay on their items.

## Release notes

- User-facing release notes drafted: yes, `release-notes.md`.
- Operator-visible changes reviewed: yes (2 seed rows, loaded on boot; nothing else).
- Release notes link or path: `release-notes.md`

## Technical changelog

- Technical changelog curated from compare ranges, not copied from raw commit log: yes.
- Compare URLs captured: yes, `technical-changelog.md` Validation and rollout notes.
- Technical changelog link or path: `technical-changelog.md`

## Upgrade data

- Generic source data file updated for every release upgrade record: yes, `data/SourceSystemConnectorSeedDataFields.xml`
  (`308f9653`).
- Candidate diff reviewed: yes, 2 records, matching the seed commit exactly (`upgrade-data-review.md`).
- Final load path decided: boot load (`DARPAN_LOAD_UPGRADE_DATA=Y`, every `darpan-seed` file, upsert); no manual step.
- Current upgrade data file link or path: `runtime/component/darpan/data/upgrade-data.xml`
- Previous upgrade data archived under prior tag folder: yes; `data/releases/3.0.2/upgrade-data.xml` is byte-identical
  to `v3.0.2`'s `data/upgrade-data.xml`.

## Contract

- `docs/api-contract/openapi.json`: only the version line changes (3.0.2 -> 3.0.3); `methods.txt` unchanged (243).
  darpan-ui's copy updated to match.
- `facade-contract.snapshot.txt`: unchanged; `FacadeContractSnapshotTests` green.

## Verification

- Backend checks complete: yes (JDK 21, `--rerun`, fresh result XMLs 2026-11-12 22:10 IST): `AuditLedgerServicesSmokeTests`
  13/13, `AuditReportAssemblerSmokeTests` 26/26 (run alone; in the combined run, its `@AfterAll` Moqui teardown NPE'd
  after all 26 passed, see `technical-changelog.md`), `ApiContractGeneratorTests` 8/8, `FacadeContractSnapshotTests` 1/1.
- UI checks complete: yes; type-check exit 0, eslint exit 0, vitest on 6 specs 108/108.
- Live/deployed smoke coverage noted: yes; prod `/Login` 200 and the image tag after the deploy, plus the post-deploy
  checks in `release-notes.md`.
- Unverified items called out: yes (`release-notes.md` Verification).

## Approval

- Release owner sign-off: Aditi, "hotfix and deploy" (2026-11-12).
- Cut/tag blocked until this checklist is fully complete: yes.
