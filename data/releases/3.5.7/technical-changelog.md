# Technical Changelog For Darpan 3.5.7

## Versioning decision

- Patch on the 3.5 line: chat message builders, one parse fix, seed data and one copy block (no method, field or entity
  change; contract 6).

## Source ranges

- Backend: `v3.5.6..v3.5.7` (https://github.com/drpn-ai/darpan/compare/v3.5.6...v3.5.7).

## Backend

### Changed
- 0fd5e2cf (DAR-BE-389) + 3ec2b11b (DAR-BE-388): `SyncPulseSupport` period posts: `clientLines` (pairs a pulse can read
  and checked in the period, one line each, "(N pairs)"), a card alone with `fallbackText`; nothing to say posts nothing but
  stamps; an end is skipped for a room another covering period speaks to and drops "Normal settings are back." when a
  period covers the day; each room hears only its pairs.
- 32971a89 (DAR-BE-390): `AutomationExecutionSupport` NO_DATA close: both sides read 0 (an evaluate: its first side), not a
  verify, calls `notifyRunLine` with the automation's `chatSpaceId` (claims the run result once, purges subscriptions);
  `noDataNotice` decides quiet / one side empty / kept nothing. SFTP NO_DATA (file not found) unchanged.
- ffeb1373, 5bbd3552, 0b3af7d9 (DAR-BE-391): `AuditRunMessage.card` returns `fallbackText` not `text`
  (`AuditRunUpdateSupport` gives Slack `fallbackText`); `SyncPulseSupport.pastCard`/`clearCard`/`pulseMessage`/`pulseUrl`/
  `withPulseLink`; `postMessage` gives Slack `slackText` and strips it for Chat; `postChat` deleted. Header image
  `https://docs.drpn.ai/logo/darpan-mark.png`.

### Fixed
- a4f04982 (DAR-BE-386): `parseInstant` accepts a `+HHMM` offset (the database extractor writes `+0000`).
- Tests: AuditRunFactsTests / AuditRunMessageTests asserted the did-not-run line in the client card (red on main since
  DAR-BE-378); now assert the card never carries it.

### Security
- None.

### Data
- `AppContentSeedData.xml` regenerated (467e228b DAR-UI-159/161, ce46a519 DAR-UI-163); `OpsNoticeSeedData.xml` +1
  `EmailCopyBlock` (`client.nothing`).

## UI

- None (no app change; the mascot seed's new anchors wait for the next app release).

## Data and configuration

- Upgrade data: the mascot seed (2320 rows) and `EmailCopyBlock` `DARPAN_ERROR_NOTICE`/`client.nothing` (1). No migration,
  no setting, no job change.

## Validation and rollout notes

- No schema or contract change. Rolls with the boot's upgrade-data load. Rollback to `v3.5.6` is safe (see release notes).
- Focused tests after the bump: `release-checklist.md` (354 tests, 0 failed).

## References

- Compare: https://github.com/drpn-ai/darpan/compare/v3.5.6...v3.5.7
- Backlog: DAR-BE-386, DAR-BE-388, DAR-BE-389, DAR-BE-390, DAR-BE-391, DAR-UI-159/161/163; deferred DAR-BE-394.
