# Release Checklist For Darpan 3.5.9

## Backlog prework

- Included backlog item IDs (`roadmap/BACKLOG.md`): DAR-BE-394, DAR-BE-396.
- Open release issues: 0
- Scope: Aditi 18:07 IST ("cut 3.5.9 and roll it tonight"). darpan `main` after `v3.5.8`: 4a9fc9ae, 252a39dd (DAR-BE-396),
  dd1b0c8f (DAR-BE-394), all from the Log errors fix session, pushed with green focused tests.
- Deferred items named and marked on the backlog: DAR-BE-385.

## Release notes

- `release-notes.md`; public notes: the 3.5 section of darpan-docs `releases/updates.mdx`.
- Operator-visible changes reviewed: yes (the error email's ignore list).

## Technical changelog

- Curated from `v3.5.8..v3.5.9`: `technical-changelog.md`. Compare: https://github.com/drpn-ai/darpan/compare/v3.5.8...v3.5.9

## Upgrade data

- 2 records (`EmailCopyBlock` `DARPAN_ERROR_DIGEST` `logs.ignored`, `logs.ignored.more`) from `data/OpsSeedData.xml`
  (`release_preflight.py generate --backend-previous-ref v3.5.8`; 3.5.8 is live).
- Candidate diff reviewed: only `OpsSeedData.xml` changed under `data/` since `v3.5.8`.
- Final load path: the image's boot upgrade-data load.
- Previous upgrade data archived: `data/releases/3.5.8/upgrade-data.xml` (unchanged, equals the `v3.5.8` root).

## Documentation

- Every shipped item has a docs decision in `docs-impact.md`: yes.
- `verify_release_docs.py --version 3.5.9 --offline --previous-tag v3.5.8` before the tag.
- Docs repos pushed before the tag. Post-deploy GraphQL re-check against prod owed.

## Verification

- After the bump (`--rerun`, isolated clone): contract guards (ApiContractGenerator, FacadeContractSnapshot, CliMethodAllowlist,
  DisableAuthzRatchet, AppContentRules, TenantOwnedEntityFilterContract) and every class the three commits touch
  (ErrorDigestAssembler/Support/ServicesSmoke, DarpanSettings, SettingAdminServicesSmoke, EmailTemplatesRender,
  AuditSettingsSmoke, AuditReadReport, AuditReportAssemblerSmoke, AuditRunUpdateSmoke). Not run: the full suites.
- Live: prod smoke after the roll (boot log, `/`, the copy blocks); GORJANA's next month-close window sends its update.
- `verify_release_pins.py --version 3.5.9` before the docker-config tag.

## Approval

- Release owner sign-off: Aditi, 2026-11-27 18:07 IST.
- Roll gated on `darpan-admin --target prod live` empty and clear of a pulse (:33).
