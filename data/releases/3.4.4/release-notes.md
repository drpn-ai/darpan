# Darpan 3.4.4

Release date: 2026-11-24

## Scope note

- **A backend hotfix on the 3.4 line** (Aditi, 2026-11-24 18:11-18:13 IST: "deploy the backend", "cut a hotfix",
  "dont cut UI yet.. just backend"). It carries everything on darpan `main` after 3.4.3. The app stays at `v4.4.3`:
  the new progress fields (DAR-BE-350) and audit priorities (DAR-BE-348) are optional to the current app, which reads
  none of them. Publicly it folds into the 3.4 notes (one `updates.mdx` section for the line).
- **Upgrade data is required** (53 records, `data/upgrade-data.xml`; loaded on boot): the audit priority vocabulary
  (DAR-BE-348), a 6-hour lock expiry on every shipped ServiceJob (DAR-BE-340), and the audit run email's "Explain them
  to the agent" block (DAR-BE-252).
- Facade contract stays at **5**: every 3.4.3 method kept; fields and parameters added only. GraphQL schema gains the
  audit priority (moqui-gql `v1.1.1`).

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.4.3` | `v3.4.4` |
| `drpn-ai/moqui-gql` | `v1.1.0` | `v1.1.1` (audit priority in the schema, DAR-BE-348) |
| `drpn-ai/darpan-agent` | `v0.5.0` | `v0.5.1` (`send#AgentTurn` takes the statement, DAR-BE-252) |
| `drpn-ai/database-darpan` | `v0.4.0` | `v0.4.1` (PostgreSQL driver 42.7.15, DAR-OPS-015) |
| `drpn-ai/darpan-docker-config` | `v3.4.3` | `v3.4.4` (the four pins move) |
| darpan-ui, darpan-cli, darpan-hotwax (`201ba34` test + doc only), shopify-darpan, netsuite-darpan, moqui-rules, moqui-jexl | unchanged | 3.4.3 pins |

## User-visible changes

- **Regular runs look up every record their read could not confirm.** A daily automation or a Run now no longer skips
  the follow-up lookup when more than 5% of the records it checked are missing on one side; it looks every one up
  (a Shopify refund is found by its own id, even on an order past Shopify's 60-day read limit) and says, as a note,
  that the read left that share to look up. Only an audit's checks, which read the period so far, keep the 5% gate.
  (DAR-BE-351)
- **An audit report shows the last morning that was due.** Opened with no date before the audit's scheduled time, the
  page shows yesterday's morning instead of an empty today that read "0 of N checks ran / not complete" with a Retry
  for checks not yet due. (DAR-BE-347)
- **Audit priorities.** Statements carry a level (P0, P1, P2 or Known) and a cause may override it; every finding
  carries its level, the report counts findings per level, and findings lists and the export filter by level. The
  agent can set both. (DAR-BE-348)
- **The audit run email invites an explanation.** Under "No cause named yet" it now says "Know why? Explain them to the
  agent", linking to the statement's agent tab. (DAR-BE-252)
- **Conclusions can test a name prefix.** `STARTS_WITH_ANY` / `NOT_STARTS_WITH_ANY` (case ignored), e.g. an exchange
  order's lines (`EXC-...`) apart from the rest. (DAR-BE-345)
- **Returns runs no longer warn about rows the free grading clears.** The free returns grading (grace, before the
  window, cancelled-order refunds) runs before the paid lookup, so its 5% count is only what grading could not
  explain. (DAR-BE-346)

## Operator-visible changes

- **Audit progress says when each read last moved** (`get#AuditProgress`: `asOf`, per step `movedDate` and
  `expectedRecords` from the automation's last successful read, per run the peak period's read limits). The app shows
  them from its next release. A read step's page count is kept as `metricsJson.readCount` when it ends. (DAR-BE-350)
- **A dead run's job lock goes after 6 hours**, not 24 (`expireLockTime` 360 on all 11 shipped ServiceJobs), so a task
  killed mid-job no longer stalls the reaper and the scan for a day. (DAR-BE-340)
- **Patched dependencies** (DAR-OPS-015): log4j 2.25.5, Spark's transitive Netty 4.2.21, Jetty, ZooKeeper 3.9.7, Tink
  1.24, XmlSchema 2.3.3, PostgreSQL driver 42.7.15; OWASP suppressions carry their reasons.

## Upgrade data

`data/upgrade-data.xml` (53 records) loads on boot. Why each block is there:

- the audit vocabulary restated whole from `AuditSeedData.xml`, which gains `AuditPriority` (seed loads are
  create-or-update and the enum types are listed together, so they move together), plus the priority enums and the
  reason-priority filter (DAR-BE-348);
- the 11 shipped ServiceJobs with `expireLockTime="360"` (DAR-BE-340);
- the `DARPAN_AUDIT_RUN` email block `cause.explain` (DAR-BE-252; email copy is data, so the wording reaches a deployed
  instance only this way).

## Deferred

- The app half of DAR-BE-350 (darpan-ui `984d4d5`, `fb98014`) and DAR-BE-348's UI wait for the next app release.
- DAR-BE-340's pool fix (a connection stuck in ROLLBACK ONLY after a deadlock) is still Aditi's call.
