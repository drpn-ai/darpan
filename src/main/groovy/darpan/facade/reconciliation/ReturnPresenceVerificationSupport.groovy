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
 * {@code {eventId, eventType, orderId, createdAt}} — where eventId is a refund id OR a return id (see
 * ShopifyReturnRefsSupport's class doc). OMS externalId now matches eventId directly, so BOTH sides sit
 * at the same grain and the ordinary ruleset join (CompareDatasetSupport, keyed on
 * eventId <-> externalId) finds real missing-in-OMS / missing-in-Shopify rows correctly and at the
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
 * inside a single order-scoped byOrder lookup. With the byOrder lookup gone and eventId a single flat
 * join key with no precedence rule (an OMS externalId now matches exactly one Shopify eventId, full
 * stop), there is no second id for the same OMS return to be checked against, no per-order grouping to
 * suppress within, and nothing left for this suppression to protect. It is retired as dead weight, not
 * kept — the thing it used to guard against (double-counting one return under two ids) cannot happen
 * once there is only one id.
 *
 * WHAT THIS CLASS DOES NOW: two behaviours a plain present/absent join cannot replicate because it has
 * no notion of "how new":
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
     *
     * returns [performed, rewritten, pendingCount, preWindowSuppressedCount, malformedCount,
     *          removedCount, removedMissingInFile1, removedMissingInFile2, warnings, auditNote]
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

        String omsToken = DiffDetailClassifier.normalizeToken(omsSideLabel)
        String shopifyToken = DiffDetailClassifier.normalizeToken(shopifySideLabel)
        String file1Token = DiffDetailClassifier.normalizeToken(file1Label)
        String file2Token = DiffDetailClassifier.normalizeToken(file2Label)

        List<String> warnings = []
        Map<String, Object> inert = [performed: false, rewritten: false, pendingCount: 0,
                preWindowSuppressedCount: 0, malformedCount: 0, removedCount: 0,
                removedMissingInFile1: 0, removedMissingInFile2: 0,
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
        int pendingCount = 0
        int preWindowCount = 0
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
                if (!remove) continue

                String rowId = rowIdOf(row)
                if (!rowId) continue
                removeIdsByToken.computeIfAbsent(missingToken) { new LinkedHashSet<String>() }.add(rowId)
                if (pending) pendingCount++
                else if (preWindow) preWindowCount++
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

        int removedCount = (removeIdsByToken.values()*.size().sum() ?: 0) as int
        String auditNote = buildAuditNote(pendingCount, preWindowCount, graceHours)
        if (removedCount == 0) {
            return [performed: true, rewritten: false, pendingCount: 0, preWindowSuppressedCount: 0,
                    malformedCount: malformedCount, removedCount: 0, removedMissingInFile1: 0, removedMissingInFile2: 0,
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
    private static String buildAuditNote(int pendingCount, int preWindowCount, int graceHours) {
        String note = "${AUDIT_NOTE_PREFIX}${pendingCount} pending (younger than ${graceHours}h)."
        if (preWindowCount > 0) {
            note += " ${preWindowCount} pre-window Shopify event(s) excluded from the missing-in-OMS count (extractor lookback artifact, not a genuine gap)."
        }
        return note
    }
}
