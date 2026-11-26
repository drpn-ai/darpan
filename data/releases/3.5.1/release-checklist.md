# Release Checklist For Darpan 3.5.1

## Backlog prework

- Included backlog item IDs (`roadmap/BACKLOG.md`): DAR-BE-377, DAR-BE-378.
- Open release issues: 0
- Scope: Aditi 2026-11-26 16:17 IST: hold the 3.5.0 roll, one roll straight to 3.5.1; 16:55: cut from darpan main 6489d83b =
  `v3.5.0` (95e58d97) + DAR-BE-377 (1c5be890) + DAR-BE-378 (6489d83b). DAR-BE-320 waits for 3.5.2. No other component moves.

## Release notes

- `release-notes.md`; public notes fold into the v3.5 section of darpan-docs `releases/updates.mdx` (now v3.5.1).

## Technical changelog

- `technical-changelog.md`.

## Upgrade data

- Generated with `release_preflight.py generate --backend-previous-ref v3.5.0 --previous-version 3.5.0` in an isolated
  backend clone; the root equalled the 3.5.0 archive before (`cmp`). 4 records.

## Documentation

- `docs-impact.md`; `check_service_catalog.py` (337/337).

## Verification

- Focused tests on the 3.5.1 tree (after the bump): darpan.pulse.*, darpan.peak.*, darpan.facade.pulse.*,
  darpan.facade.peak.*, SourceSystemConnectorSupportSmokeTests, ApiContractGeneratorTests, FacadeContractSnapshotTests,
  AppContentRulesTests, CliMethodAllowlistTests, AutomationEntityContractTests; DAR-BE-378's EmailTemplatesRenderTests,
  AuditRunUpdateSmokeTests, AuditDigestSmokeTests, ErrorChatSupportTests, AutomationExecutionSupportTests,
  AutomationFailureNotificationTests, SftpAutomationSupportTests, StuckRunReaperNotifyTests. Not run: the full suites (workspace rule:
  focused tests only).
- `verify_release_pins.py --version 3.5.1` before the docker-config tag; `verify_release_docs.py` offline before the tag,
  live after deploy. Roll gate: the cause-review "clear" + empty prod live.

## Approval

- Release owner sign-off: Aditi, 2026-11-26 16:17 IST (one roll, straight to 3.5.1).
