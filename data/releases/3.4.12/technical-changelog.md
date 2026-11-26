# Technical Changelog For Darpan 3.4.12

## Versioning decision

- Patch on the 3.4 line: additive contract change only (three methods added, one made remote, an optional parameter and
  a response field), contract stays 5; no schema or data change.

## Source ranges

- Backend: `v3.4.11..v3.4.12` (https://github.com/drpn-ai/darpan/compare/v3.4.11...v3.4.12)
- UI: `v4.4.13..v4.4.14` (https://github.com/drpn-ai/darpan-ui/compare/v4.4.13...v4.4.14)

## Backend

### Added
- `facade.AgentChangeFacadeServices.propose#AuditChange`, `get#AuditChangeProposal`, `list#AuditChangeProposals`;
  `AuditChangePlanner.createForPerson`; `apply#AuditChange` takes `confirmedIds` (99a5bde7, e9c65725, 95e377f8).
- `facade.AgentReadFacadeServices.find#AuditFindingLookalikes` is `allow-remote` (e9c65725).
- `AuditReportAssembler.checkSystems`/`systemsNamed`: each report check carries `systems` (9b116057, a70ab6a6).
- `AuditReportAssembler.CARD_FIELDS`; `recordFacts` gains `hotwaxStatus` (omsItemStatusId) and `lineTotal`
  (lineAmount), reads `tranDate` as the order day, and `dayOf` reads NetSuite's M/d/yyyy (a70ab6a6).
- `ConclusionLookupSupport.answersFor`; `RunConclusionSupport` writes `lookedUp` on each row;
  `AuditAutomationSupport.lookedUpRecords` keeps it on the finding (ca8361f6).

### Changed
- `AuditReportAssembler.lineNumber`/`lineItemId`: a line is named by its HotWax item or the item its headline names,
  never the finding id (a70ab6a6).

### Tests
- `FindingCardRulesTests` (new, 6); `AuditReportAssemblerSmokeTests` 45 (systems from words then sources; golden
  report gains `systems`); `RunConclusionSupportTests` 39 (lookup answer rides on the row); `AuditAutomationSmokeTests`
  29; `RunConclusionStepTests` 27; `AuditChangeApplySmokeTests` 57; `CliMethodAllowlistTests` 8; `RpcMethodFenceTests` 23.

## UI (darpan-ui v4.4.14)
- DAR-UI-149: statement badges always show the count (735f201).
- DAR-UI-150/151, DAR-BE-371: per-system cards removed; `typeDetails(rows, kind)`; basics and lines use `check.systems`
  (5e038bf).
- DAR-BE-372: basics say **Missing**, take HotWax's carried status and **Line total**; `lineLabel` never the finding id;
  the seven rules in `docs/ui/page-surface-playbook.md` (4bbe6e8).

## Upgrade data
- None.
