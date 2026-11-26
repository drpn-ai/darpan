# Technical Changelog For Darpan 3.4.14

## Versioning decision

- Patch: one behaviour fix, no method, schema or data change.

## Source ranges

- Backend: `v3.4.13..v3.4.14` (https://github.com/drpn-ai/darpan/compare/v3.4.13...v3.4.14)
- CLI: `drpn-ai/darpan-cli` `v0.4.0..v0.5.0`

## Backend

### Fixed
- `darpan.lookup.OrderLookup.openFindings` (a60b5604): open findings are found by their own `recordKey` or through
  `AuditFindingRecord` (recordId, internalId, or `id/line`), tenant-scoped, at most 50.

### Tests
- `LookupFacadeSmokeTests` "a finding keyed by another system's id is attached through the records it names" (red first).
