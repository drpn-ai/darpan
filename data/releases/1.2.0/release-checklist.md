# Release Checklist For Darpan 1.2.0

Prepared 2026-08-01. Status: prepared and verified locally, **not yet published**.

## Linear prework

- Linear was retired as the source of truth on 2026-07-01; no issue-tracker prework is required
  for this release (see `AGENTS.md`).
- Release tracking issue: not applicable — retired process.
- Open release issues: 0
- Deferred items are named explicitly in `release-notes.md` under "Scope note", not implied.

## Release notes

- User-facing release notes drafted: yes — `release-notes.md`
- Operator-visible changes reviewed: yes — data load, one-time notification migration, contract
  break, MySQL driver, revived automation schedules
- Release notes link or path: `release-notes.md`

## Technical changelog

- Technical changelog curated from compare ranges, not copied from raw commit log: yes —
  `technical-changelog.md`, grouped by subsystem with the versioning decision recorded
- Compare URLs captured: drafted in `technical-changelog.md`; they resolve only after tags are pushed
- Technical changelog link or path: `technical-changelog.md`

## Upgrade data

- Generic source data file updated for every release upgrade record: yes — all 12 records
  programmatically traced back to `data/SecuritySeedData.xml`, `data/AutomationSeedData.xml`, or
  `data/SourceSystemConnectorSeedData.xml`
- Candidate diff reviewed: yes — `upgrade-data-review.md`
- Final load path decided: operators run `./gradlew loadDarpanUpgradeData`, then invoke
  `reconciliation.ReconciliationNotificationServices.migrate#TenantNotificationSettings` once
  server-side
- Current upgrade data file link or path: `runtime/component/darpan/data/upgrade-data.xml`
- Previous upgrade data archived under prior tag folder: yes — `data/releases/1.1.0/upgrade-data.xml`,
  verified byte-identical to the file published at tag `v1.1.0`
- Ordering: the file is parent-before-child, not alphabetical, because `ArtifactGroupMember` and
  `ArtifactAuthz` carry a foreign key to `ArtifactGroup`. Do not re-sort it.

## Verification

- Backend checks complete: yes — `./gradlew :runtime:component:darpan:cleanTest :runtime:component:darpan:test`
  under JDK 21 (Temurin 21.0.11). BUILD SUCCESSFUL in 10m 9s.
  **84 suites, 640 tests, 0 failures, 0 errors, 0 skipped.** Confirmed the `test` task actually
  executed rather than resolving `UP-TO-DATE`.
- UI checks complete: yes — `npm run check` at `darpan-ui@2.3.0` (eslint `--max-warnings=0`,
  `vue-tsc --build --force`, `vitest run --coverage`). **98 test files, 814 tests, all passed.**
- API contract gate: `python3 scripts/check_contract_compat.py <v1.1.0> <HEAD>` exits 0 — breaking
  changes accepted against the `x-darpan-contract-version` `1 -> 2` bump.
- Upgrade data: XML well-formed (12 records); every record traced to a generic seed file.
- Live/deployed smoke coverage noted: **none run.** No UAT or production deploy was exercised as
  part of this cut.

### Unverified items, called out

- No live smoke against a real Shopify or OMS tenant. The exchange-reconciliation presence rework
  (`864dde4`) has unit coverage only.
- `migrate#TenantNotificationSettings` has smoke-test coverage but was not run against a real
  multi-tenant dataset.
- `loadDarpanUpgradeData` was not executed against a live database; FK ordering was corrected by
  inspection of the entity relationships, not by an actual load.
- MySQL 8.4 server compatibility was verified during the Connector/J upgrade, not re-verified here.
- No browser verification of the UI release build.

## Publish-time steps (not yet done)

Ordered — the deploy pins depend on the tags existing first.

1. Commit release metadata in `drpn-ai/darpan` and `drpn-ai/darpan-ui`, push both `main` branches.
2. Tag: `darpan v1.2.0`, `darpan-ui v2.3.0`, `darpan-hotwax v0.5.0` (at `e8a7514`),
   `shopify-darpan v0.5.0` (at `d499627`). The two connector repos are clean and need no commit.
3. Only then update `darpan-docker-config/docker/prod/Dockerfile`:
   `DARPAN_REF=v1.2.0`, `DARPAN_HOTWAX_REF=v0.5.0`, `SHOPIFY_DARPAN_REF=v0.5.0`.
   `NETSUITE_DARPAN_REF` stays `v0.2.0` (no commits since its tag).
4. Publish GitHub releases from `release-notes.md`.
5. Commit and deploy the `darpan-docs` changes (`releases/updates.mdx`, `releases/roadmap.mdx`).

## Approval

- Release owner sign-off: pending — prepared for review, nothing pushed or tagged.
- Cut/tag blocked until this checklist is fully complete: yes. Local verification is complete;
  publishing is deliberately not done.
