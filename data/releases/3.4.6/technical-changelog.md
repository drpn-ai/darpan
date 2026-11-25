# Technical changelog: Darpan 3.4.6

## Versioning decision

- Patch on the 3.4 line: no facade method removed or field changed (contract stays 5), GraphQL unchanged.

## Source ranges

- darpan `v3.4.5..v3.4.6`, darpan-hotwax `v1.3.1..v1.3.2`, shopify-darpan `v0.11.0..v0.11.1`, darpan-ui
  `v4.4.6..v4.4.7`.

## Backend

### darpan

- DAR-BE-015 (`abf8e8fd`..`92e6a636`, 20 commits) — STRING settings with secret masking and the `errors.*` keys
  (`settings-catalog.json`); executions keep each side's read count (`file1ReadCount`/`file2ReadCount`, 0 never null),
  `noDataKind` and `triggerType`; a run with an empty or kept-nothing side records its checks as did not run; error
  kinds and a deduplicated Darpan error card (`RunErrorNotice`, own-transaction claim) posted to the Darpan error space;
  clients get one neutral `DARPAN_ERROR_NOTICE` line a day; the audit run update says a check did not run or partly
  ran, and once that Darpan is on it; ERROR log lines captured in memory by fingerprint (`ErrorLogCapture`) and flushed
  every 5 minutes (`ErrorLogEntry`, 30-day purge); daily `DARPAN_ERROR_DIGEST` email with the watchdog
  (`ErrorDigestAssembler`, `ErrorDigestSend`, sent only with a message id) via `send_ErrorDigest_hourly`.
- DAR-BE-358 (`da3efd5b`, `7a52b42c`) — after each audit run, `AuditRunUpdateSupport` emails
  `audit.unexplained.email.to` a CSV of every Unexplained finding; copy blocks `subject.unexplained`,
  `unexplained.intro`, `unexplained.body`, `unexplained.next`.
- DAR-BE-356 (`facd7683`) — an audit's re-check also reads records changed since its recent read (passes
  `changedSince` to the HotWax orders extract).
- DAR-BE-360 (`28aae10d`) — `AuditReportAssembler.recordFacts` gains `paymentStatus` (Shopify `displayFinancialStatus`,
  now apart from fulfillment; HotWax order payment preference statuses; HotWax return Refunded / Not refunded from
  `returnCredit`; NetSuite deposit statuses). Tests: `PaymentStatusRecordFactsTests`, `ShopifyReturnRecordFactsTests`.
- DAR-BE-359 (`957c85ff`) — a Shopify return's day falls back to `closedAt`.
- DAR-BE-357 (`60ac6965`) — R7 brain facts: a gift-card swipe given back reads the Shopify total; a voided
  authorization was never paid.
- DAR-BE-355 (`306fe1eb`) — a side Keep reads a bare key off each record, as the plan gate does.
- `e23bfa0e` — 3.4.5 pack docs impact after its tag.

### darpan-hotwax

- `18197d3` DAR-BE-356 — HotWax order extracts read the window's orders that changed since an instant (`changedSince`).

### shopify-darpan

- `0dd428d` DAR-BE-359 — the completed-returns read carries the order's date, financial and fulfillment status, total
  and refunded total.

## UI

- `1dfb47b` DAR-BE-360 — `auditReport.ts` basics row reads "<status> · <payment status>"; an item takes its order's.
- `c3ea6b9` DAR-UI-143 — `AuditReportPage.vue`: a quiet report refresh re-reads the open finding; a finding that
  disagrees with its row refreshes the report once; the finding read has its own abort.

## Data and configuration

- Upgrade data: 37 records (DAR-BE-015 templates, copy blocks, jobs; DAR-BE-358 copy blocks); new optional settings
  `errors.*` and `audit.unexplained.email.to`.

## Validation and rollout notes

- Focused tests and gates: `release-checklist.md`. Rollout: docker-config `v3.4.6` prod image via `deploy.sh`, then the
  app from a detached `v4.4.7` worktree.

## References

- Backlog: DAR-BE-015, DAR-BE-355, DAR-BE-356, DAR-BE-357, DAR-BE-358, DAR-BE-359, DAR-BE-360, DAR-UI-143.
