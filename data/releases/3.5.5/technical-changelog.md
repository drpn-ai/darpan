# Technical Changelog For Darpan 3.5.5

## Versioning decision

- Patch on the 3.5 line: Groovy only (DAR-BE-383); contract 6 unchanged; no data.

## Source ranges

- Backend: `v3.5.4..v3.5.5` (https://github.com/drpn-ai/darpan/compare/v3.5.4...v3.5.5).

## Backend

### Fixed
- 1f57a936: `IdIndexer.toJsonLines` writes a temp file of its own (`File.createTempFile("idindex-", ".jsonl")`), never
  `<read>.records.jsonl` beside the read (the run's `JsonRecordLines.SUFFIX` file), and deletes it when the conversion fails.
  Both callers (`IdIndexRunHook.afterLiveRead`'s async `index#ExtractFile`, `IdIndexDatasetHook.afterRead`) delete it in a
  `finally`.

### Tests
- Pulse session on 1f57a936 (fresh `--rerun`): 20 classes / 191 tests / 0 failures (darpan.idindex.* incl. the new
  IdIndexerSparkTests clash test, red before the fix; darpan.pulse.*; DatasetRefreshTests; ApiContractGeneratorTests;
  FacadeContractSnapshotTests).
- This cut (`--rerun`): IdIndexerSparkTests, ApiContractGeneratorTests, FacadeContractSnapshotTests,
  TenantOwnedEntityFilterContractTests, DisableAuthzRatchetTest.

## UI

- None.

## Data and configuration

- None.

## Validation and rollout notes

- Prebuilt image; roll on the cause-review session's "clear" (scan paused, runs drained) + prod live empty apart from the 4
  known stale run results. Then the coordinator re-runs the GORJANA test pulse and Pulse checks the samples.

## References

- Release notes: `release-notes.md`; checklist: `release-checklist.md`.
- Backlog: DAR-BE-383 (`roadmap/BACKLOG.md`).
