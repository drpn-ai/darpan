# Docs Impact For Darpan 3.4.4

Every shipped item needs a decision in each column: the page path you changed (a path under the docs repo), or
`none: <reason>`. A hotfix folds into its release line: one `updates.mdx` section for 3.4.

## Product docs (docs.drpn.ai)

Repo `darpan-docs`.

| Item | Change | How-to | Reference | Troubleshooting | Setup / service details |
| --- | --- | --- | --- | --- | --- |
| DAR-BE-351 | A regular run (manual, Run now, scheduled) looks up every record its read could not confirm, even above the 5% share, and adds a "Verification pass: the read left N of M …; all were looked up" note; only an audit's checks keep the gate | guides/review-results.mdx, guides/returns-and-exchanges.mdx | reference/reconciliation-runs.mdx | troubleshooting/reconciliation-runs.mdx | none: no connector, credential or lookup endpoint changed |
| DAR-BE-347 | The audit page (and report) with no date opens on the last morning that was due: yesterday's until the audit's scheduled time, then today's | guides/audits.mdx | none: no status, field or term changed; the report shape is the same | troubleshooting/audits.mdx | none: no connection change |
| DAR-BE-348 | Statements and findings carry a priority (P0/P1/P2/KNOWN), a cause can override it, the report counts per level, and findings filter by level | guides/audits.mdx | none: the app (v4.4.3) shows no level yet; the GraphQL reference documents the fields | none: no in-app message; the one refusal (a level on a reason that closes on every check) is API-only | none: no connection change |
| DAR-BE-252 | The audit run email shows "Know why? Explain them to the agent" under "No cause named yet", linking to the statement with the Agent tab | guides/audits.mdx | none: no term or status changed | none: no new failure mode | none: email copy is data, carried by the upgrade data |
| DAR-BE-345 | Conclusions can test `STARTS_WITH_ANY` / `NOT_STARTS_WITH_ANY` (case ignored) | guides/review-results.mdx | none: no reference page lists conclusion operators; the how-to's test table is the list | none: the conclusions page in app v4.4.3 does not offer the test, so its authoring refusal cannot be reached there | none: no connection change |
| DAR-BE-346 | Free returns grading runs before the paid lookup, so returns runs stop warning about rows grading clears; stage order VERIFY_RETURNS 6 / VERIFY_MISSING 7 / VERIFY_EXCHANGE 8 | guides/run-reconciliation.mdx, guides/returns-and-exchanges.mdx | reference/reconciliation-runs.mdx | none: the warning text is unchanged and already covered in troubleshooting/reconciliation-runs.mdx; it no longer fires for rows grading clears | none: no connection change |
| DAR-BE-350 | `get#AuditProgress` adds `asOf`, per-step `movedDate`/`expectedRecords`, and per-run peak `limits`; `metricsJson.readCount` kept | none: app v4.4.3 reads none of the new fields | none: no field a user sees in the app | none: no new message | none: no connection change |
| DAR-BE-340 | `expireLockTime` 360 on all 11 shipped ServiceJobs (a dead run's lock goes after 6 h, not 24) | none: operator-only | none: operator-only | none: operator-only; the developers backend and self-host pages carry it | none: no connection change |
| DAR-OPS-015 | Dependency patches (log4j 2.25.5, Netty, Jetty, ZooKeeper, Tink, XmlSchema, PostgreSQL driver 42.7.15) | none: no behaviour change | none: no behaviour change | none: no behaviour change | none: the database connection is unchanged; the driver version is on the developers runtime baseline |

## GraphQL reference (developers.drpn.ai)

- SDL compared against: the 3.4.3 snapshot in `graphql-reference/schema.graphql`; the 3.4.4 SDL was taken from a local
  stack at the release content (moqui-gql `v1.1.1`). Additive only: `PriorityCounts` and `FindingPriority` types;
  `byPriority` on `AuditSummary`, `CategoryCounts` and `FindingCounts`; `Statement.priority`; `Finding.priority`;
  `priority`, `prioritySource`, `priorityReason` and `priorityLine` on `FindingSummary` and `FindingListItem`;
  `findings` accepts a `priority` key.
- SDL snapshot refreshed and pages regenerated: yes, 57 pages; new graphql/objects/priority-counts.mdx and
  graphql/objects/finding-priority.mdx (in docs.json); `--check` passes.
- GraphQL changelog entry: graphql/changelog.mdx `## Darpan 3.4.4`.
- Hand-written GraphQL pages: graphql/tasks/list-open-findings.mdx, graphql/tasks/check-the-morning-audit.mdx,
  graphql/tasks.mdx.
- Deferred: moqui-gql's `auditReport(date:)` description still says "blank is today" (the changelog states the real
  behaviour); fix in the next moqui-gql release.

## Service catalog and public OpenAPI

- Service catalog: api-reference/service-catalog.mdx (`get#AuditSettings`/`save#AuditSettings`, `get#AuditReport`,
  `get#AuditFindingsByOrder`, `get#AuditCheckExtracts`, `get#AuditProgress`, `send#AgentTurn`).
- `api-reference/openapi.json` regenerated with `scripts/public_openapi.py` at version 3.4.4: yes.
- `api-reference/changelog.mdx`: a 3.4.4 entry under contract 5 (no method added or removed; parameters and fields
  added only).

## Engineering and self-host docs

- Runtime baseline matches `build.gradle`: yes (Spark 4.2.1, Drools 10.2.0 unchanged); the PostgreSQL driver row in
  backend/operations-and-validation.mdx moves to 42.7.15.
- Backend pages: backend/reconciliation-engine.mdx, backend/operations-and-validation.mdx.
- Self-host and deploy pages: self-host/production.mdx (3.4.4 under "Upgrading to 3.4").
- Engineering process pages: none: no process change.

## Release notes and roadmap

- `releases/updates.mdx`: the 3.4 section is `## v3.4.4` (one section per line).
- `releases/roadmap.mdx` "Recently shipped": the 3.4 card names v3.4.4 and app v4.4.3.

Docs commits: darpan-docs `2467145`, darpan-developers `7dee511`.
