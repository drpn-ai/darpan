package darpan.facade.reconciliation

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.regex.Pattern

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.normalizeInt

/**
 * Return presence GRACE FILTER (DAR-BE-018; 2026-08-17/18 returns-refund-grain-alignment plan, Task 3).
 *
 * SHAPE HISTORY — read before restoring anything this class used to do. Before that plan, Shopify's
 * return-refs extract emitted one record per ORDER (refundIds[]/returnIds[] id-set lists) while OMS
 * reconciliationReturns emitted one record per RETURN, keyed by a Shopify refund OR return id. The two
 * sides sat at different GRAINS, so an ordinary compare_id join between them was meaningless. This
 * class used to bridge that gap itself: it read BOTH full extracts, built a per-order id index
 * (byOrder), matched each OMS return against its own order's refund ids (primary) or return ids
 * (fallback), tracked matchedCount / ordersMatchedForward, and APPENDED its own missing-in-Shopify /
 * missing-in-OMS diff rows on top of whatever the generic ruleset compare produced (which, at
 * mismatched grains, was largely noise).
 *
 * Task 1 of that plan (REVISION 2026-08-18) reshaped the Shopify extractor to one record per EVENT —
 * {@code {refundOrReturnId, refundOrReturnType, orderId, createdAt}} — where refundOrReturnId is a refund id OR a return id (see
 * ShopifyReturnRefsSupport's class doc). OMS externalId now matches refundOrReturnId directly, so BOTH sides sit
 * at the same grain and the ordinary ruleset join (CompareDatasetSupport, keyed on
 * refundOrReturnId <-> externalId) finds real missing-in-OMS / missing-in-Shopify rows correctly and at the
 * right grain, on its own. Task 3 (this class, this revision) therefore RETIRES the id-matching
 * machinery entirely as pure redundancy — the byOrder index, the refund-then-return fallback,
 * matchedCount, ordersMatchedForward — all of it existed only to decide "is this OMS return present in
 * Shopify", which the generic compare now does correctly. That machinery was ALSO already broken by
 * Task 1 before this class was touched: it read refundIds/returnIds/refunds/returns, fields Task 1
 * removed from the Shopify record entirely.
 *
 * PER-ORDER FORWARD-MATCH SUPPRESSION — RETIRED, not narrowed (the judgement call this task exists to
 * make). The old class doc named this a known phase-1 imprecision: an order with more than one return
 * could mask a second missing refund, because the suppression worked per ORDER rather than per EVENT.
 * Fixing it properly, the old doc said, needed either the design's §8 typed-field hedge or "a Shopify
 * Return->Refund link". Task 1 built exactly that link (Return.refunds, consumed by
 * ShopifyReturnRefsSupport's refunded-return narrowing) — but it used the link to stop EMITTING a
 * redundant RETURN row for an already-refunded return, not to re-associate a refund back to its return
 * for matching purposes. The suppression's entire reason to exist was to stop ONE OMS return being
 * double-reported against TWO Shopify ids it might be keyed by (refund id primary, return id fallback)
 * inside a single order-scoped byOrder lookup. With the byOrder lookup gone and refundOrReturnId a single flat
 * join key with no precedence rule (an OMS externalId now matches exactly one Shopify refundOrReturnId, full
 * stop), there is no second id for the same OMS return to be checked against, no per-order grouping to
 * suppress within, and nothing left for this suppression to protect. It is retired as dead weight, not
 * kept — the thing it used to guard against (double-counting one return under two ids) cannot happen
 * once there is only one id.
 *
 * WHAT THIS CLASS DOES NOW: three behaviours a plain present/absent join cannot replicate — two because
 * it has no notion of "how new", and one because it has no notion of WHY a counterpart is absent:
 *
 *   GRACE: a return/refund event that the generic compare reported missing on one side, but whose OWN
 *   createdAt (the Shopify event's createdAt, or the OMS return's entryDate) is younger than
 *   graceHours, is PENDING rather than missing — OMS lags Shopify by roughly 38 minutes (RQ-23), so a
 *   one-sided event inside that lag window is expected, not a defect. Graded against the record's OWN
 *   date, never the order's — a refund on a months-old order can still be minutes old.
 *
 *   WINDOW-START GATE: a missing-in-OMS row (Shopify present, OMS absent) whose event predates the
 *   run's reporting windowStartMillis is never reported at all, pending or otherwise. It is only
 *   present in the extract because ShopifyReturnRefsSupport's own lookback widens its fetch/emit floor
 *   backward from windowStart (see that class's LOOKBACK doc) so a refund the OMS side hasn't caught up
 *   to yet is still visible for forward matching. Reporting a pre-window event missing-in-OMS would
 *   just relocate the false-missing that lookback exists to close, from the OMS side onto the Shopify
 *   side. One-directional by construction: a pre-window OMS return has no equivalent lookback-driven
 *   artifact to correct for, so missing-in-Shopify rows are graded on grace alone.
 *
 *   CANCELLATION-REFUND SUPPRESSION (2026-08-18): a missing-in-OMS row for a Shopify REFUND whose
 *   originating HotWax order is ORDER_CANCELLED is not a gap — OMS books a cancellation, not a return,
 *   so the counterpart can never exist and no amount of waiting will produce one. Unlike the two rules
 *   above, this one cannot be decided from the row itself: the row carries the refund, not the order's
 *   status, so it originally needed a live lookup (injected as cancelledOrderLookup — this class never
 *   dispatches a service itself).
 *
 *   FIELD-FIRST since 2026-08-20: the Shopify return-refs extract now selects Order.cancelledAt — a
 *   scalar on a node it already fetches, so it costs nothing — and stamps it on every event row as
 *   `orderCancelledAt`. A row carrying that KEY is decided inline and never triggers a lookup; only a
 *   row from an older extract (key ABSENT, as distinct from present-and-null) falls back to it. Note
 *   the evidence changed with it: the field reports SHOPIFY's cancellation, and treating that as
 *   implying OMS cancellation is a deliberate product assumption taken 2026-08-20, not a measured
 *   equivalence. The OMS-measured rates it stands on are 16.5% cancelled on the missing-in-OMS side
 *   vs 0.9% on matched controls; if the field's own rates diverge sharply from those, the assumption
 *   is what to re-examine first.
 *
 *   Four constraints, each load-bearing and each pinned by a test:
 *     (1) DIRECTION — missing-in-OMS only. The same rule applied to missing-in-Shopify explained 1 of
 *         579 rows in the live probe, indistinguishable from control.
 *     (2) REFUND ONLY — a RETURN row is never suppressed, even on a cancelled order. Live data showed
 *         21 REFUND / 0 RETURN on cancelled orders, so a qualifying RETURN means an assumption broke
 *         and must stay visible rather than be explained away.
 *     (3) ANY-RECORD-CANCELLED — an order group with mixed statuses suppresses if ANY record is
 *         cancelled; real groups routinely carry several records.
 *     (4) FAIL CLOSED — a capped, failed, not-ok or throwing lookup suppresses nothing and never fails
 *         the run, so a degraded OMS over-reports rather than silently under-reports. The field path
 *         has no failure mode to fail closed over: a blank/absent value simply suppresses nothing.
 *
 * MECHANISM: this is now a POST-COMPARE FILTER over the diff document the generic ruleset compare
 * already wrote — the same streaming read/rewrite MissingDiffVerificationSupport uses (candidate scan,
 * then a sibling-temp-file rewrite, atomic replace, summary counts adjusted, an audit note appended),
 * but the removal criterion is each row's OWN embedded date against grace/window rather than a live
 * source-of-record lookup. A "missing" ruleset diff row's data column already carries the full record
 * from the side that IS present (CompareDatasetSupport.buildJsonDataDf's struct(col("*")), surfaced
 * through convertMissingDiffToRuleSetDiffDataset) — that embedded record supplies the date this filter
 * grades, with no need to re-read either source extract file directly. There is therefore no more
 * "pure function over two lists" half of this class the way there used to be — the pure unit now is
 * grading one already-written row, not joining two full record sets.
 */
class ReturnPresenceVerificationSupport {

    static final int DEFAULT_GRACE_HOURS = 3
    static final String DEFAULT_OMS_SIDE_LABEL = "OMS"
    static final String DEFAULT_SHOPIFY_SIDE_LABEL = "Shopify"
    // Mirrors ExchangePairVerificationSupport.AUDIT_NOTE_PREFIX / MissingDiffVerificationSupport
    // .AUDIT_NOTE_PREFIX: the opening phrase of the always-emitted audit sentence, consumed by
    // TenantNotificationSupport.partitionAuditNotes so a returns run's own "show your work" note is
    // classified as a note, not folded into "warnings" (which would misreport an all-clear run as WITH
    // ISSUES). Unchanged across this revision even though everything the sentence describes changed.
    static final String AUDIT_NOTE_PREFIX = "Return presence check: "
    /** Matches lookup#HotWaxOmsOrdersByExternalId's PAIR_LOOKUP_MAX_IDS. Keep the two in sync. */
    static final int ORDER_LOOKUP_CHUNK_SIZE = 100
    /** Mirrors MissingDiffVerificationSupport.DEFAULT_MAX_LOOKUP_IDS: past this, the sync is broken
     *  rather than skewed and point-checking it only hammers the source API. */
    static final int DEFAULT_MAX_ORDER_LOOKUPS = 1000
    static final String CANCELLED_ORDER_STATUS_ID = "ORDER_CANCELLED"
    /** Order-level cancellation marker stamped on every Shopify event row by ShopifyReturnRefsSupport. */
    static final String ORDER_CANCELLED_AT_FIELD = "orderCancelledAt"
    static final String EVENT_TYPE_REFUND = "REFUND"

    private static final String DIFFERENCES_HEADER = "\"differences\":["
    private static final String SUMMARY_PREFIX = "\"summary\":"
    private static final String PROCESSING_WARNINGS_PREFIX = "\"processingWarnings\":"
    // All-digits form: what a JSON epoch-millis integer looks like once ValueSupport.normalize has
    // already turned it into a String (OMS entryDate is serialized this way — see the retired C3 fix
    // this class used to carry, and SourceSystemConnectorFieldSeedData's OMS_RETURNS entryDate pill).
    private static final Pattern ALL_DIGITS_PATTERN = Pattern.compile(/^\d+$/)

    /**
     * args:
     *   diffFile          : File — ruleset diff document (writeDiffDatasetOutput format), required
     *   omsSideLabel      : String — the file1Label/file2Label value the OMS-returns connector resolved to
     *   shopifySideLabel  : String — the file1Label/file2Label value the Shopify-return-refs connector resolved to
     *   file1Label        : String, file2Label : String — as written into the document's metadata,
     *                        needed only to attribute removals back to onlyInFile1Count/onlyInFile2Count
     *   graceHours        : optional, default DEFAULT_GRACE_HOURS
     *   nowMillis         : optional, default now
     *   windowStartMillis : optional — the run's REPORTING window start (see class doc's WINDOW-START
     *                        GATE); a caller that omits it degrades to grace-only behaviour
     *   cancelledOrderLookup : optional Closure<Map> — List<String> orderIds ->
     *                        [ok: Boolean, ordersByExternalId: Map<String, ?>, errors: List<String>],
     *                        matching lookup#HotWaxOmsOrdersByExternalId. Each value is an order GROUP
     *                        (one order Map or a List of them, each with a statusId). Absent disables
     *                        cancellation-refund suppression entirely
     *   maxOrderLookups   : optional cap on candidate orders, default DEFAULT_MAX_ORDER_LOOKUPS
     *
     * returns [performed, rewritten, pendingCount, preWindowSuppressedCount, malformedCount,
     *          removedCount, removedMissingInFile1, removedMissingInFile2, cancelledRefundSuppressedCount,
     *          warnings, auditNote]
     */
    static Map<String, Object> verifyReturnPresenceForRun(Map<String, Object> args) {
        File diffFile = (File) args?.diffFile
        String omsSideLabel = normalize(args?.omsSideLabel) ?: DEFAULT_OMS_SIDE_LABEL
        String shopifySideLabel = normalize(args?.shopifySideLabel) ?: DEFAULT_SHOPIFY_SIDE_LABEL
        String file1Label = normalize(args?.file1Label)
        String file2Label = normalize(args?.file2Label)
        int graceHours = normalizeInt(args?.graceHours, DEFAULT_GRACE_HOURS)
        long nowMillis = (args?.nowMillis instanceof Number)
                ? ((Number) args.nowMillis).longValue()
                : System.currentTimeMillis()
        long graceFloor = nowMillis - graceHours * 3600_000L
        Long windowStartMillis = (args?.windowStartMillis instanceof Number)
                ? ((Number) args.windowStartMillis).longValue()
                : null
        Closure cancelledOrderLookup = (args?.cancelledOrderLookup instanceof Closure) ? (Closure) args.cancelledOrderLookup : null
        int maxOrderLookups = normalizeInt(args?.maxOrderLookups, DEFAULT_MAX_ORDER_LOOKUPS)

        String omsToken = DiffDetailClassifier.normalizeToken(omsSideLabel)
        String shopifyToken = DiffDetailClassifier.normalizeToken(shopifySideLabel)
        String file1Token = DiffDetailClassifier.normalizeToken(file1Label)
        String file2Token = DiffDetailClassifier.normalizeToken(file2Label)

        List<String> warnings = []
        Map<String, Object> inert = [performed: false, rewritten: false, pendingCount: 0,
                preWindowSuppressedCount: 0, malformedCount: 0, removedCount: 0,
                removedMissingInFile1: 0, removedMissingInFile2: 0, cancelledRefundSuppressedCount: 0,
                warnings: warnings, auditNote: null] as Map<String, Object>
        if (diffFile == null || !diffFile.isFile()) {
            warnings.add("Return presence check skipped: diff file was not available.")
            return inert
        }

        // Pass 1 — stream the already-written diff document one row at a time (diff files reach GB
        // scale; see MissingDiffVerificationSupport's own OOM-avoidance doc) and grade every candidate
        // missing row (one belonging to the OMS side or the Shopify side of THIS run) against grace and
        // the window-start gate, using that row's own embedded record data — never a fresh join.
        JsonSlurper slurper = new JsonSlurper()
        Map<String, Set<String>> removeIdsByToken = [:]
        // orderId -> row ids of the missing-in-OMS REFUND rows grading LEFT in place. Populated in
        // Pass 1, resolved in bulk in Pass 1.5 (CANCELLATION-REFUND SUPPRESSION, see class doc).
        Map<String, Set<String>> candidateRowIdsByOrderId = [:]
        int pendingCount = 0
        int preWindowCount = 0
        int cancelledByFieldCount = 0
        int malformedCount = 0
        boolean sawDifferencesHeader = false

        diffFile.withReader("UTF-8") { Reader reader ->
            BufferedReader lines = new BufferedReader(reader)
            String line
            boolean inRows = false
            while ((line = lines.readLine()) != null) {
                if (!inRows) {
                    if (line.startsWith(DIFFERENCES_HEADER)) {
                        sawDifferencesHeader = true
                        inRows = !line.startsWith(DIFFERENCES_HEADER + "]")
                    }
                    continue
                }
                String rowJson = stripRowLine(line)
                if (rowJson == null) break
                if (!rowJson.contains("\"missingIn\"")) continue
                Map row = parseRowQuietly(slurper, rowJson)
                if (row == null) continue

                String missingToken = DiffDetailClassifier.normalizeToken(row.get("missingIn"))
                boolean missingInOms = missingToken && missingToken == omsToken
                boolean missingInShopify = !missingInOms && missingToken && missingToken == shopifyToken
                if (!missingInOms && !missingInShopify) continue

                // The row's data column carries the record from the side that IS present — the
                // Shopify event (createdAt) when missing-in-OMS, the OMS return (entryDate) when
                // missing-in-Shopify. See the class doc's MECHANISM section.
                Map data = parseEmbeddedData(slurper, row.get("data"))
                Long ownCreatedMillis = data == null ? null
                        : parseMillisOrNull(missingInOms ? data.get("createdAt") : data.get("entryDate"))
                if (ownCreatedMillis == null) {
                    // Cannot grade without a date. Conservative posture (mirrors the rest of this
                    // pipeline's M2/lookback bias): never silently suppress a row we cannot prove is
                    // young — leave it exactly as the generic compare reported it, but say so.
                    malformedCount++
                    continue
                }

                boolean remove = false
                boolean pending = false
                boolean preWindow = false
                if (missingInOms && windowStartMillis != null && ownCreatedMillis < windowStartMillis) {
                    // WINDOW-START GATE: only ever present because of the extractor's lookback; never
                    // genuinely missing-in-OMS. Not counted as pending — it is not "recent", it is
                    // out-of-scope for this run's reporting window entirely.
                    remove = true
                    preWindow = true
                } else if (ownCreatedMillis > graceFloor) {
                    remove = true
                    pending = true
                }
                // CANCELLATION-REFUND SUPPRESSION — FIELD FIRST (2026-08-20). The Shopify return-refs
                // extract now stamps the order's own cancelledAt onto every event row, so for any row
                // carrying that key the answer is already in the row: decide inline, with no lookup,
                // no chunking, no cap and no fail-closed path to get wrong.
                //
                // Only a row from an extract PREDATING the field (key ABSENT, not null) falls through
                // to the Pass 1.5 point lookup — which is exactly why the extract always writes the
                // key, null included. Saved runs replay stored artifacts, so old files keep working.
                //
                // Candidates for that fallback are collected, never looked up inline: the lookup is
                // an HTTP call that must be batched, and this is the streaming pass that must not
                // block on network I/O. Independent of the grading decision above — a row already
                // removed as pending/pre-window needs no second reason to go.
                boolean cancelledByField = false
                if (missingInOms && !remove &&
                        EVENT_TYPE_REFUND.equalsIgnoreCase(normalize(data.get("refundOrReturnType")))) {
                    if (data.containsKey(ORDER_CANCELLED_AT_FIELD)) {
                        if (normalize(data.get(ORDER_CANCELLED_AT_FIELD))) {
                            remove = true
                            cancelledByField = true
                        }
                    } else if (cancelledOrderLookup != null) {
                        String orderId = normalize(data.get("orderId"))
                        String candidateRowId = rowIdOf(row)
                        if (orderId && candidateRowId) {
                            candidateRowIdsByOrderId.computeIfAbsent(orderId) { new LinkedHashSet<String>() }.add(candidateRowId)
                        }
                    }
                }
                if (!remove) continue

                String rowId = rowIdOf(row)
                if (!rowId) continue
                removeIdsByToken.computeIfAbsent(missingToken) { new LinkedHashSet<String>() }.add(rowId)
                if (pending) pendingCount++
                else if (preWindow) preWindowCount++
                else if (cancelledByField) cancelledByFieldCount++
            }
        }
        if (!sawDifferencesHeader) {
            warnings.add("Return presence check skipped: diff document has no differences section.")
            return inert
        }
        // M2 posture (carried over from the retired matching code): surface malformed counts as an
        // ordinary (actionable) warning, deliberately NOT folded into the AUDIT_NOTE_PREFIX sentence,
        // so partitionAuditNotes keeps treating this as real signal rather than "show your work" noise.
        if (malformedCount > 0) {
            warnings.add("Return presence check could not grade ${malformedCount} reported-missing return/refund row(s) against the grace window (no parseable data.createdAt/data.entryDate); left as reported.".toString())
        }

        // Pass 1.5 — CANCELLATION-REFUND SUPPRESSION. Resolve each candidate order's OMS status in
        // chunks and fold the cancelled ones into the SAME removal set Pass 2 already consumes, so the
        // rewrite, the summary arithmetic and the audit-note plumbing are reused unchanged. Fails
        // closed at every step: a capped, failed, not-ok or throwing lookup suppresses nothing and
        // never fails the run.
        int cancelledRefundCount = cancelledByFieldCount
        if (cancelledOrderLookup != null && !candidateRowIdsByOrderId.isEmpty()) {
            List<String> orderIds = new ArrayList<>(candidateRowIdsByOrderId.keySet())
            if (orderIds.size() > maxOrderLookups) {
                warnings.add("Cancellation-refund check skipped: ${orderIds.size()} candidate order(s) exceeds the ${maxOrderLookups}-lookup cap; no rows were suppressed.".toString())
            } else {
                orderIds.collate(ORDER_LOOKUP_CHUNK_SIZE).each { List<String> chunk ->
                    Map chunkResult
                    try {
                        chunkResult = (Map) cancelledOrderLookup.call(chunk)
                    } catch (Throwable t) {
                        // `return` skips THIS chunk only — one failed chunk must not discard a
                        // succeeding one, since each chunk is an independent batch of orders.
                        warnings.add("Cancellation-refund lookup failed for ${chunk.size()} order(s): ${normalize(t.message) ?: t.class.simpleName}".toString())
                        return
                    }
                    if (chunkResult?.ok != true) {
                        warnings.add("Cancellation-refund lookup did not complete for ${chunk.size()} order(s): ${(chunkResult?.errors ?: []).join('; ')}".toString())
                        return
                    }
                    ((Map) (chunkResult.ordersByExternalId ?: [:])).each { Object orderIdKey, Object group ->
                        if (!containsCancelledOrder(group)) return
                        Set<String> rowIds = candidateRowIdsByOrderId.get(normalize(orderIdKey))
                        if (!rowIds) return
                        removeIdsByToken.computeIfAbsent(omsToken) { new LinkedHashSet<String>() }.addAll(rowIds)
                        cancelledRefundCount += rowIds.size()
                    }
                }
            }
        }

        int removedCount = (removeIdsByToken.values()*.size().sum() ?: 0) as int
        String auditNote = buildAuditNote(pendingCount, preWindowCount, graceHours, cancelledRefundCount)
        if (removedCount == 0) {
            return [performed: true, rewritten: false, pendingCount: 0, preWindowSuppressedCount: 0,
                    malformedCount: malformedCount, removedCount: 0, removedMissingInFile1: 0, removedMissingInFile2: 0,
                    cancelledRefundSuppressedCount: 0,
                    warnings: warnings, auditNote: auditNote] as Map<String, Object>
        }

        int removedMissingInOms = removeIdsByToken.get(omsToken)?.size() ?: 0
        int removedMissingInShopify = removeIdsByToken.get(shopifyToken)?.size() ?: 0
        // onlyInFile1Count counts records present only in file1 = missing in file2, and vice versa —
        // same mapping MissingDiffVerificationSupport's own adjustSummary relies on.
        int removedMissingInFile1 = (omsToken && omsToken == file1Token ? removedMissingInOms : 0) +
                (shopifyToken && shopifyToken == file1Token ? removedMissingInShopify : 0)
        int removedMissingInFile2 = (omsToken && omsToken == file2Token ? removedMissingInOms : 0) +
                (shopifyToken && shopifyToken == file2Token ? removedMissingInShopify : 0)

        // Pass 2 — stream-rewrite to a sibling temp file, then atomically replace the original.
        File tempFile = new File(diffFile.getParentFile(), diffFile.getName() + ".returns-verify-tmp")
        diffFile.withReader("UTF-8") { Reader reader ->
            BufferedReader lines = new BufferedReader(reader)
            tempFile.withWriter("UTF-8") { Writer writer ->
                String line
                boolean inRows = false
                boolean documentClosed = false
                boolean firstRowWritten = false
                while ((line = lines.readLine()) != null) {
                    if (documentClosed) continue
                    if (!inRows) {
                        if (line.startsWith(SUMMARY_PREFIX)) {
                            writer << SUMMARY_PREFIX + JsonOutput.toJson(adjustSummary(slurper, line,
                                    removedCount, removedMissingInFile1, removedMissingInFile2)) + ",\n"
                        } else if (line.startsWith(DIFFERENCES_HEADER)) {
                            inRows = !line.startsWith(DIFFERENCES_HEADER + "]")
                            if (inRows) writer << DIFFERENCES_HEADER
                            else writer << line << "\n"
                        } else if (line.startsWith(PROCESSING_WARNINGS_PREFIX)) {
                            writer << PROCESSING_WARNINGS_PREFIX + JsonOutput.toJson(
                                    appendedWarnings(slurper, line, auditNote)) + ",\n"
                        } else {
                            writer << line << "\n"
                        }
                        continue
                    }
                    String rowJson = stripRowLine(line)
                    if (rowJson == null) {
                        writer << "]\n}"
                        documentClosed = true
                        continue
                    }
                    boolean lastRow = line.endsWith("]")
                    boolean removeRow = false
                    if (rowJson.contains("\"missingIn\"")) {
                        Map row = parseRowQuietly(slurper, rowJson)
                        String missingToken = row == null ? null : DiffDetailClassifier.normalizeToken(row.get("missingIn"))
                        String rowId = row == null ? null : rowIdOf(row)
                        removeRow = rowId != null && removeIdsByToken.get(missingToken)?.contains(rowId)
                    }
                    if (!removeRow) {
                        if (firstRowWritten) writer << ","
                        writer << "\n" << rowJson
                        firstRowWritten = true
                    }
                    if (lastRow) {
                        writer << "]\n}"
                        documentClosed = true
                        inRows = false
                    }
                }
            }
        }
        replaceFile(tempFile, diffFile)

        return [performed: true, rewritten: true, pendingCount: pendingCount, preWindowSuppressedCount: preWindowCount,
                malformedCount: malformedCount, removedCount: removedCount,
                removedMissingInFile1: removedMissingInFile1, removedMissingInFile2: removedMissingInFile2,
                cancelledRefundSuppressedCount: cancelledRefundCount,
                warnings: warnings, auditNote: auditNote] as Map<String, Object>
    }

    /** A row line ends with "," (more rows follow) or "]" (last row); the closing "}" line ends the region. */
    private static String stripRowLine(String line) {
        String trimmed = line.trim()
        if (trimmed == "}" || trimmed == "]" || trimmed.isEmpty()) return null
        if (trimmed.endsWith(",") || trimmed.endsWith("]")) return trimmed.substring(0, trimmed.length() - 1)
        return trimmed
    }

    private static Map parseRowQuietly(JsonSlurper slurper, String rowJson) {
        try {
            Object parsed = slurper.parseText(rowJson)
            return parsed instanceof Map ? (Map) parsed : null
        } catch (Exception ignored) {
            return null
        }
    }

    /**
     * The row's data column is itself a JSON-encoded STRING (CompareDatasetSupport's
     * convertMissingDiffToRuleSetDiffDataset carries it through as `to_json(struct(col("*")))`), so
     * reading it needs a second parse beyond the row's own. Defensively accepts an already-decoded Map
     * too, in case a future writer stops double-encoding it.
     */
    private static Map parseEmbeddedData(JsonSlurper slurper, Object rawData) {
        if (rawData instanceof Map) return (Map) rawData
        String text = normalize(rawData)
        if (!text) return null
        try {
            Object parsed = slurper.parseText(text)
            return parsed instanceof Map ? (Map) parsed : null
        } catch (Exception ignored) {
            return null
        }
    }

    /** Ruleset diff rows carry the record id in primaryId; generic diff rows in id. */
    private static String rowIdOf(Map row) {
        String primaryId = row.get("primaryId")?.toString()?.trim()
        if (primaryId) return primaryId
        String id = row.get("id")?.toString()?.trim()
        return id ?: null
    }

    /**
     * Accepts a raw Number, an all-digits String (an epoch-millis integer once ValueSupport.normalize
     * has stringified it — OMS entryDate is serialized this way), or an ISO-8601 String (Shopify
     * createdAt). Returns null rather than a sentinel on failure — a null date must never look
     * artificially "old" or "young"; see the malformedCount handling at the call site.
     */
    private static Long parseMillisOrNull(Object rawTimestamp) {
        if (rawTimestamp instanceof Number) return ((Number) rawTimestamp).longValue()
        String value = normalize(rawTimestamp)
        if (!value) return null
        if (ALL_DIGITS_PATTERN.matcher(value).matches()) {
            try {
                return Long.parseLong(value)
            } catch (NumberFormatException ignored) {
                return null
            }
        }
        try {
            return Instant.parse(value).toEpochMilli()
        } catch (Exception ignored) {
            return null
        }
    }

    /**
     * True when ANY record in an OMS order group is cancelled. Groups routinely carry several records
     * (split shipments); the live probe saw 1,093 status values across 579 orders. Accepts a bare Map
     * as well as a List, since the lookup contract only promises "an order group".
     */
    private static boolean containsCancelledOrder(Object group) {
        List records = (group instanceof List) ? (List) group : (group == null ? [] : [group])
        return records.any { Object record ->
            record instanceof Map && CANCELLED_ORDER_STATUS_ID.equalsIgnoreCase(normalize(((Map) record).get("statusId")))
        }
    }

    private static Map adjustSummary(JsonSlurper slurper, String summaryLine, int removedCount,
                                     int removedMissingInFile1, int removedMissingInFile2) {
        Object fragment = headerFragment(slurper, summaryLine, SUMMARY_PREFIX)
        Map summary = fragment instanceof Map ? (Map) fragment : [:]
        decrement(summary, "totalDifferences", removedCount)
        decrement(summary, "onlyInFile1Count", removedMissingInFile2)
        decrement(summary, "onlyInFile2Count", removedMissingInFile1)
        decrement(summary, "missingObjectDifferenceCount", removedCount)
        return summary
    }

    private static List appendedWarnings(JsonSlurper slurper, String warningsLine, String auditNote) {
        Object fragment = headerFragment(slurper, warningsLine, PROCESSING_WARNINGS_PREFIX)
        List warningsList = fragment instanceof List ? new ArrayList((List) fragment) : []
        if (auditNote) warningsList.add(auditNote)
        return warningsList
    }

    private static Object headerFragment(JsonSlurper slurper, String line, String prefix) {
        String fragment = line.substring(prefix.length()).trim()
        if (fragment.endsWith(",")) fragment = fragment.substring(0, fragment.length() - 1)
        try {
            return slurper.parseText(fragment)
        } catch (Exception ignored) {
            return null
        }
    }

    private static void decrement(Map summary, String key, int by) {
        Object value = summary.get(key)
        if (value instanceof Number && by > 0) summary.put(key, Math.max(0L, ((Number) value).longValue() - by))
    }

    private static void replaceFile(File source, File target) {
        Path sourcePath = source.toPath()
        Path targetPath = target.toPath()
        try {
            Files.move(sourcePath, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * One sentence the operator always gets — even a run with nothing pending shows its work. The
     * window-start clause is only appended when it can actually apply (mirrors the retired
     * suppression-caveat sentence's own "never a blanket disclaimer" rule).
     */
    private static String buildAuditNote(int pendingCount, int preWindowCount, int graceHours,
                                         int cancelledRefundCount) {
        String note = "${AUDIT_NOTE_PREFIX}${pendingCount} pending (younger than ${graceHours}h)."
        if (preWindowCount > 0) {
            note += " ${preWindowCount} pre-window Shopify event(s) excluded from the missing-in-OMS count (extractor lookback artifact, not a genuine gap)."
        }
        if (cancelledRefundCount > 0) {
            note += " ${cancelledRefundCount} cancellation refund(s) suppressed from the missing-in-OMS count — the originating order is cancelled, which books no return in OMS."
        }
        return note
    }
}
