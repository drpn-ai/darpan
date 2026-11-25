# Release Checklist For Darpan 3.4.9

## Backlog prework

- Included backlog item IDs (`roadmap/BACKLOG.md`): DAR-BE-361, DAR-UI-144, DAR-BE-369, DAR-UI-147
- Open release issues: 0
- Scope changes recorded on the backlog items: yes (Aditi 2026-11-25 19:00 IST "cut the release and deploy", then "Ship all": both features on darpan and darpan-ui `main`)
- Deferred items named and marked on the backlog: yes (DAR-BE-361 feedback webhook + email previews; a Re-check all on prod)

## Release notes

- User-facing release notes drafted: yes, `release-notes.md`; public notes fold into the 3.4 section of darpan-docs `releases/updates.mdx`
- Operator-visible changes reviewed: yes (settings, methods, upgrade data on boot, new tables on boot)

## Technical changelog

- Technical changelog curated from compare ranges, not copied from raw commit log: yes, `technical-changelog.md`
- Compare URLs captured: https://github.com/drpn-ai/darpan/compare/v3.4.8...v3.4.9 and https://github.com/drpn-ai/darpan-ui/compare/v4.4.10...v4.4.11

## Upgrade data

- Generic source data file updated for every release upgrade record: yes (AuditSeedData, EmailCopySeedData, FeedbackEmailSeedData, SecuritySeedData)
- Candidate diff reviewed: yes, `upgrade-data-review.md` (23 candidates) plus the audit vocabulary restated whole (43 rows, AuditCatalogContractTests)
- Final load path decided: loaded on boot as `darpan-seed` (63 records); templates ahead of their copy blocks, each enum type ahead of its members
- Generated with `release_preflight.py generate --backend-previous-ref v3.4.8` in the isolated clone at `origin/main`
- Previous upgrade data archived under prior tag folder: yes, `data/releases/3.4.8/upgrade-data.xml` equals the `v3.4.8` tag's

## Documentation

- Every shipped item has a docs decision in `docs-impact.md`: yes
- `verify_release_docs.py` exit status (before tagging): recorded in the DAR-BE-369 backlog note
- Docs repos pushed (Mintlify publishes from `main`): darpan-docs and darpan-developers, before the tag
- Post-deploy GraphQL re-check against prod (`--sdl-url https://api.drpn.ai/rest/s1/graphql/sdl`) owed: yes, until it passes after the deploy

## Verification

- Backend checks complete: yes, Gradle in the isolated clone, `--rerun`, fresh JUnit XML: 30 classes, 346 tests, 0 failures (every test class changed since `v3.4.8`, the upgrade-pack readers, the facade snapshot and contract generator, CLI allowlist, authz ratchet, tenant guards, static seam reset)
- UI checks complete: yes, AuditReportPage + contract/types/edit-icon guards + components/audit specs, type-check, eslint (counts in the DAR-UI-147 backlog note)
- Live/deployed smoke coverage noted: yes, a browser look on app.drpn.ai after the deploy (DAR-UI-147 prod-owed)
- Unverified items called out: yes, a Re-check all that closes findings, and a feedback post to chat (both prod-owed)
- Not run: the full suites (workspace rule: focused tests only)
- `verify_release_pins.py --version 3.4.9` before the docker-config tag

## Approval

- Release owner sign-off: Aditi, 2026-11-25 ("cut the release and deploy"; "Ship all (Recommended)")
