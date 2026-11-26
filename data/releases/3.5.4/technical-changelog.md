# Technical Changelog For Darpan 3.5.4

## Versioning decision

- Patch on the 3.5 line: Groovy only (DAR-BE-382); contract 6 unchanged; no data.

## Source ranges

- Backend: `v3.5.3..v3.5.4` (https://github.com/drpn-ai/darpan/compare/v3.5.3...v3.5.4); prod moves from `v3.5.1`.
- Components: 3.5.3's (darpan-hotwax v1.4.1, netsuite-darpan v0.8.1).

## Backend

### Fixed
- 56b4c88f: a pulse that did not finish never posts to a client chat room; two in a row alert Darpan's room.
- eab82caf: each by-key NetSuite target batch reads its own folder (`batch-<n>`); a sample's message fits its 255 column.

### Tests
- Pulse session on eab82caf (fresh `--rerun`): 20 classes / 286 tests / 0 failures (darpan.pulse.*, darpan.peak.*,
  darpan.facade.pulse.*, darpan.facade.peak.*, ErrorChatSupportTests, RunAdmissionTests, SourceSystemConnectorSupportSmokeTests,
  ApiContractGeneratorTests, FacadeContractSnapshotTests).
- This cut (--rerun): ApiContractGeneratorTests, FacadeContractSnapshotTests, TenantOwnedEntityFilterContractTests,
  DisableAuthzRatchetTest.

## UI

- None in this tag; app `v4.4.15` (DAR-UI-152, DAR-BE-377 words, DAR-UI-153 Sync pulse page) after the migration.

## Data and configuration

- None of its own; 3.5.3's 5 records via the boot's darpan-seed load.

## Validation and rollout notes

- Roll on the cause-review session's "clear" + an empty prod live; then the migration dry run (Aditi), run, browser look on
  the acknowledge popover and GORJANA's Sync pulse page, app `v4.4.15`. Pulse watches GORJANA's next pulse.

## References

- Release notes: `release-notes.md`; checklist: `release-checklist.md`; 3.5.3's and 3.5.2's packs.
- Backlog: DAR-BE-382 (`roadmap/BACKLOG.md`).
