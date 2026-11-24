# Technical Changelog For Darpan 3.4.4

## Versioning decision

Patch (3.4.3 -> 3.4.4): a hotfix on the 3.4 line; the facade contract stays at 5 (fields and parameters added only).

## Source ranges

- `drpn-ai/darpan` `v3.4.3..v3.4.4` (24 commits + 2 release commits); moqui-gql `v1.1.0..v1.1.1`; darpan-agent
  `v0.5.0..v0.5.1`; database-darpan `v0.4.0..v0.4.1`; darpan-docker-config `v3.4.3..v3.4.4`.

## Backend

- **DAR-BE-351** `8e54d480`: `MissingDiffVerificationSupport.verifyMissingDiffs` takes `auditRun`; only an audit window
  skips an over-share side (`OVER_SHARE`). Any other run looks up every candidate and adds the note "the read left N of
  M records missing in <side>, above the N% a read should leave; all were looked up" (`sideOutcomes.overShare`).
  `AutomationExecutionSupport.verifyMissingDiffsIfEnabled` passes `auditRun` from `window.auditReportDate`;
  `RunVerificationSupport` forwards it; `runSavedRunDiff` never sets it. Gate: `RunPathParityGateTest`.
- **DAR-BE-347** `230ad6ee`: `AuditReportGraph.morningOnShow` / `defaultMorning`. A blank report date is yesterday while
  today's scheduled time (in the audit's schedule zone) is ahead, unless the audit is off schedule, a check of it ran
  today, or a requested period had not started yesterday. Used by `get#AuditReport`, `get#AuditCheckExtracts` and the
  GraphQL report; `retry#AuditCheck` keeps today.
- **DAR-BE-350** `5ba31246`, `70627906`: `AuditSettingsSupport.progress` adds `asOf`, per step `movedDate`
  (`heartbeatDate`) and `expectedRecords` (the same stage's `metricsJson.readCount` on the automation's last successful
  execution), per running run `limits` (`PeakReadPolicy`). `RunObservability.endStep` keeps a heartbeated read's last
  page count as `metricsJson.readCount` before `recordCount` takes the connector's count.
- **DAR-BE-348** `7f12a68a` .. `088f8818` (10 commits): `AuditPriority` enums, `AuditStatement` priority,
  `AuditReasonPriority` override (config versions, transfer, import), `AuditPriority` resolution per finding unit,
  report/finding `priority` + per-level counts, findings lists and export filter by priority, settings read/save, agent
  levers; contract snapshot + openapi.
- **DAR-BE-252** `a2ef643b`, `04b2312e`: `AuditRunMessage` adds the "Explain them to the agent" invite (`cause.explain`
  in `EmailCopySeedData.xml`); `send#AgentTurn`'s `statement` parameter in the contract (darpan-agent `899a146`).
- **DAR-BE-345** `2c2b4bf7`: `ConclusionConditionSupport` `STARTS_WITH_ANY` / `NOT_STARTS_WITH_ANY`.
- **DAR-BE-346** `2c7278e6`: the returns pass runs before the missing-diff pass on both run paths;
  `RunObservability.STAGE_SEQUENCE` VERIFY_RETURNS 6 / VERIFY_MISSING 7 / VERIFY_EXCHANGE 8.
- **DAR-BE-340** `ce12a4a5`, `4f306cfe`: `expireLockTime="360"` on the 11 shipped ServiceJobs
  (`ServiceJobLockExpirySeedTests`); a MySQL regression test for a deadlocked pooled connection.
- **DAR-OPS-015** `3b7094bb`, `a8048149`, `a69f5dd1`, `d85d7678`: CI places moqui-jexl; dependency patches (log4j BOM
  2.25.5, Netty, Jetty, ZooKeeper, Tink, XmlSchema, PostgreSQL driver); `.github/owasp-suppressions.xml`.

### Other repos

- `moqui-gql` `v1.1.0..v1.1.1`: `868477b`, `3f5d299`, `d5bedc9` (audit priority in `DarpanSchema.gql.xml`).
- `darpan-agent` `v0.5.0..v0.5.1`: `899a146` (`send#AgentTurn` takes the statement the panel is open on).
- `database-darpan` `v0.4.0..v0.4.1`: `7567af7` (PostgreSQL 42.7.15), `ff76273` (unused compileOnly Connector/J 8.3.0
  dropped).
- `darpan-docker-config` `v3.4.3..v3.4.4`: prod pins for darpan, moqui-gql, darpan-agent, database-darpan.

## UI

None: no darpan-ui release; the app stays at `v4.4.3`. DAR-BE-350's and DAR-BE-348's app halves ship later.

## Data and configuration

`data/upgrade-data.xml`: 53 records (DAR-BE-348's audit vocabulary and priority enums + reason-priority filter;
DAR-BE-340's 11 ServiceJobs with `expireLockTime` 360; DAR-BE-252's `cause.explain` email block). Loaded on boot.

## Validation and rollout notes


`check_contract_compat.py v3.4.3 -> v3.4.4`: compatible, 319 base methods preserved, version 5.

Release tests and pins: see `release-notes.md` › Verification. Rollout: the prod deployer at `--ref v3.4.4`.

## References

- `release-notes.md`, `release-checklist.md`, `docs-impact.md`, `upgrade-data-review.md` in this folder.
- Backlog: DAR-BE-351, DAR-BE-347, DAR-BE-348, DAR-BE-350, DAR-BE-252, DAR-BE-345, DAR-BE-346, DAR-BE-340, DAR-OPS-015.
