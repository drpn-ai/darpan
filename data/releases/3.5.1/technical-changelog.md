# Technical Changelog For Darpan 3.5.1

## Versioning decision

- Patch: contract stays 6; one optional entity field pair, no facade field change.

## Source ranges

- Backend: `v3.5.0..v3.5.1` (1c5be890 DAR-BE-377, 6489d83b DAR-BE-378) (https://github.com/drpn-ai/darpan/compare/v3.5.0...v3.5.1)
- System components: unchanged (3.5.0's pins). UI: none in this tag.

## Backend

### Added
- DAR-BE-377 (1c5be890): `SourceSystemConnector.pulseCreatedField` / `pulseNameField` (entity, seed for `OMS_GQL` and
  `SHOPIFY`, `SourceSystemConnectorSupport.toConnectorMap`); `SyncPulseSupport.pulseFieldsOf` / `canPulse`.

### Changed
- `SyncPulseFacadeSupport.validate` takes `upstreamCanPulse` (boolean) instead of a system id; `E_HOTWAX` becomes
  `E_UPSTREAM` "A pulse needs HotWax or Shopify orders as the upstream side." (save#SyncPair and save#PeakPeriodSync).
- `SyncPulseSupport.pulseableAutomation`, `PeakPeriodFacadeSupport.upstreamCanPulse` (was `upstreamIsHotwax`) ask
  `canPulse`; `SyncPulseReader.upstreamRecords` dates and names records by the upstream connector's fields.
- Mascot rows `syncPulse`, `syncPairUpstream` reworded (darpan-ui 8287162 carries the same words in the bundle).

- DAR-BE-378 (6489d83b): ErrorChatSupport, AuditRunMessage, AuditRunUpdateSupport, AuditDigestSupport,
  AutomationExecutionSupport. Errors and warnings go to the Darpan team's alerts room, never a client chat room. The audit update's chat card and the digest's chat message no longer say a check did not run (the email still does). An automation's own chat space is no longer posted a run error (Notify me still is). And the update after a run goes out once a morning, after every due check has run, not again as late checks finish.

### Removed
- `SyncPulseSupport.HOTWAX_ORDERS`.

### Tests
- New: a Shopify-shaped record (`createdAt`/`name`) read with its connector's fields and with HotWax's (every record skipped);
  the pulse fields resolve through `toConnectorMap`; a Shopify-upstream pair pulses end to end and flags its late order;
  save#SyncPair accepts a Shopify pulse and refuses NetSuite.
- On the 3.5.1 tree: see `release-checklist.md`.

## UI

- None in this tag; app `v4.4.15` (3.5.0's pairing) is unchanged. The two mascot rows reach the app as data; darpan-ui
  8287162 carries the same words in the bundle for the next app release.

## Data and configuration

- 4 upgrade records; two columns added on boot.

## Validation and rollout notes

- Rolled as part of the 3.5.0 roll (one restart), on the cause-review session's "clear" and an empty prod live view.
- After the deploy: a Shopify → HotWax pair on GORJANA (check 100102, keys Shopify `id` ↔ HotWax `externalId`) gets an
  hourly pulse; its first samples are read against Shopify and HotWax (DAR-BE-377 prod-owed).

## References

- Release notes: `release-notes.md`; checklist: `release-checklist.md`; upgrade review: `upgrade-data-review.md`.
- Backlog: DAR-BE-377 (`roadmap/BACKLOG.md`).
