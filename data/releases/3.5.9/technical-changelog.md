# Technical Changelog For Darpan 3.5.9

## Versioning decision

- Patch on the 3.5 line: two defect fixes and an operator setting; no method, field or entity change (contract 6).

## Source ranges

- Backend: `v3.5.8..v3.5.9` (https://github.com/drpn-ai/darpan/compare/v3.5.8...v3.5.9).

## Backend

### Changed
- 4a9fc9ae, 252a39dd (DAR-BE-396): `ErrorDigestAssembler`/`ErrorDigestSupport`/`ErrorDigestMessage`: setting
  `errors.email.ignorePatterns` (settings catalog); a line is left out when its masked fingerprint matches a pattern
  (anchored); the email names how many it left out (`logs.ignored`, `logs.ignored.more`).

### Fixed
- dd1b0c8f (DAR-BE-394): Groovy 5.0.3 fails a bare static call from a closure when an inline-cast argument is null.
  `AuditSettingsSupport.steps` (readCountOf on a null metricsJson) broke `get#AuditProgress` and `AuditRunUpdateSupport.idle()`,
  so `send#AuditRunUpdate` failed "argument type mismatch" whenever another of the audit's automations was mid-run;
  `AuditReadReport.addCalls` (null calls) broke `get#AuditReadReport`. Qualified calls, no inline cast.

### Security
- 252a39dd: ignore patterns match only the masked fingerprint, anchored (no raw message matching).

### Data
- `OpsSeedData.xml` +2 `EmailCopyBlock` (`DARPAN_ERROR_DIGEST` `logs.ignored`, `logs.ignored.more`).

## UI

- None.

## Data and configuration

- Upgrade data: the two copy blocks. New setting `errors.email.ignorePatterns` (catalog default). No migration, no job change.

## Validation and rollout notes

- No schema or contract change. Rolls with the boot's upgrade-data load. Rollback to `v3.5.8` is safe.
- Focused tests after the bump: `release-checklist.md`.

## References

- Compare: https://github.com/drpn-ai/darpan/compare/v3.5.8...v3.5.9
- Backlog: DAR-BE-394, DAR-BE-396.
