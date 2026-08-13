package darpan.facade.reconciliation

import java.time.Instant
import java.util.regex.Matcher
import java.util.regex.Pattern

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.normalizeInt

/**
 * Return presence verification (DAR-BE-018, design §4).
 *
 * Forward: an OMS return is present in Shopify if its externalId appears among its own order's
 * refund ids (primary) or that order's return ids (backup). Neither → missing in Shopify.
 *
 * Reverse: a Shopify refund whose id is no OMS return's externalId on that order is missing in OMS,
 * EXCEPT where the order already matched forward — that suppression is what keeps the permanent
 * ex-IN-PROGRESS minority (keyed by Return id, never backfilled to the refund id) from phantom
 * -flagging. A refunded Shopify Return is never separately expected; its event is its refund.
 *
 * Grace: a one-sided return younger than graceHours is pending, not missing. Shopify→OMS return
 * sync was measured at ~38 minutes (RQ-23); the 3h default matches the exchange stage. The reverse
 * (missing-in-OMS) grace is keyed on the REFUND's own createdAt (from the upstream extractor's
 * refundsCreatedAt map), not the order's — a refund created minutes ago on a months-old order must
 * still read as young (fix I1; see verifyReturnPresence below).
 *
 * ID normalization: BOTH the OMS-side externalId AND orderExternalId are GID-tail-normalized the
 * same way CompareDatasetSupport.applyIdNormalizer's SHOPIFY_GID_TAIL does for Spark columns, since
 * the OMS returns endpoint's id format (bare vs GID) has never been observed live (OQ-8) — that
 * uncertainty applies identically to both fields, which come off the same endpoint and the same
 * connector projection. The normalization is idempotent for bare numerics, so this is free
 * insurance either way (fix I5, widened to orderExternalId as Important #2 of the fix-wave-C
 * re-review: leaving orderExternalId un-normalized would silently fail every byOrder lookup if OMS
 * ever emits GIDs, nullifying I5's own premise).
 *
 * Reporting window vs. lookup floor (Important #3, fix-wave-C): the upstream Shopify extractor now
 * fetches/emits events from [windowStart - lookback, windowEnd) rather than [windowStart, windowEnd)
 * — see ShopifyReturnRefsSupport's class doc — because OMS lags Shopify by ~38min (RQ-23), so a
 * Shopify refund for an OMS return sitting just inside windowStart can itself have been created just
 * BEFORE windowStart. The forward pass above is deliberately unaffected: it may match against ANY
 * emitted event, pre-window included, which is the entire point of the widened net. The reverse pass
 * must NOT report a pre-window Shopify refund as missing-in-OMS, though — that would just relocate
 * the false-missing this fix closes from the OMS side to the Shopify side — so it is additionally
 * gated on the caller-supplied windowStartMillis (verifyReturnPresence's windowStartMillis arg,
 * threaded from runSavedRunDiff.groovy's own windowStartDate, which already knows the run's window).
 * A caller that does not supply windowStartMillis degrades to pre-fix behaviour (no gate).
 *
 * Known phase-1 imprecision: on an order carrying several returns, the forward-match suppression is
 * per-order rather than per-event. Exact reverse attribution needs the design's §8 typed-field hedge
 * or a Shopify Return→Refund link. Accepted, and stated in the audit note.
 */
class ReturnPresenceVerificationSupport {

    static final String TYPE_MISSING_IN_SHOPIFY = "RETURN_MISSING_IN_SHOPIFY"
    static final String TYPE_MISSING_IN_OMS = "RETURN_MISSING_IN_OMS"
    static final int DEFAULT_GRACE_HOURS = 3
    static final String DEFAULT_OMS_SIDE_LABEL = "OMS"
    static final String DEFAULT_SHOPIFY_SIDE_LABEL = "Shopify"
    // Mirrors ExchangePairVerificationSupport.AUDIT_NOTE_PREFIX: the opening phrase of the
    // always-emitted audit sentence. Fix I6: now consumed by TenantNotificationSupport's
    // partitionAuditNotes (previously that classifier recognized only the exchange and
    // missing-diff prefixes, so every returns run's always-on note fell into "warnings" and
    // misclassified the run as WITH ISSUES, all-clear runs included).
    static final String AUDIT_NOTE_PREFIX = "Return presence check: "
    // Mirrors CompareDatasetSupport.applyIdNormalizer's SHOPIFY_GID_TAIL Spark expression exactly,
    // for the plain-Groovy (non-Spark) values this class compares. See fix I5 above.
    private static final Pattern SHOPIFY_GID_TAIL_PATTERN = Pattern.compile(/gid:\/\/shopify\/[^\/]+\/(\d+)(?:\?.*)?$/)
    private static final Pattern TRAILING_DIGITS_PATTERN = Pattern.compile(/(\d+)$/)
    // All-digits form: what a JSON epoch-millis integer looks like once ValueSupport.normalize has
    // already turned it into a String. See parseMillis / fix C3.
    private static final Pattern ALL_DIGITS_PATTERN = Pattern.compile(/^\d+$/)

    static Map<String, Object> verifyReturnPresence(Map<String, Object> args) {
        List omsReturns = (args?.omsReturns instanceof List) ? (List) args.omsReturns : []
        List shopifyOrders = (args?.shopifyOrders instanceof List) ? (List) args.shopifyOrders : []
        int graceHours = normalizeInt(args?.graceHours, DEFAULT_GRACE_HOURS)
        long nowMillis = (args?.nowMillis instanceof Number)
                ? ((Number) args.nowMillis).longValue()
                : System.currentTimeMillis()
        long graceFloor = nowMillis - graceHours * 3600_000L
        // Important #3 (fix-wave-C): the run's REPORTING window start, distinct from the wider
        // lookup floor the Shopify extractor now fetches from. Optional and nullable — a caller
        // that does not supply it (older call site, or a test) gets pre-fix behaviour. See the
        // class doc's "Reporting window vs. lookup floor" section.
        Long windowStartMillis = (args?.windowStartMillis instanceof Number)
                ? ((Number) args.windowStartMillis).longValue()
                : null
        // C2 fix: appended diff rows must carry the fields the rest of the pipeline actually reads
        // (RULESET_DIFF_SCHEMA / RULESET_CSV_COLUMNS / OutputDescriptorSupport / DiffDetailClassifier
        // all key off primaryId + presentIn/missingIn + message) — a row missing them exported as
        // "RETURN_MISSING_IN_SHOPIFY,,,,,,,,,," and displayed with no id at all. Labels default to
        // the same words ExchangePairVerificationSupport defaults its own OMS-side label to.
        String omsSideLabel = normalize(args?.omsSideLabel) ?: DEFAULT_OMS_SIDE_LABEL
        String shopifySideLabel = normalize(args?.shopifySideLabel) ?: DEFAULT_SHOPIFY_SIDE_LABEL

        Map<String, Map<String, Set<String>>> byOrder = indexShopifyOrders(shopifyOrders)

        int matchedCount = 0
        int pendingCount = 0
        // M2: malformed input records were dropped with no signal at all — unlike
        // ExchangePairVerificationSupport, which surfaces its own skip conditions as warnings.
        int malformedOmsReturnCount = 0
        int malformedShopifyOrderCount = 0
        List<Map<String, Object>> missingInShopify = []
        Set<String> ordersMatchedForward = new HashSet<>()

        omsReturns.each { Object raw ->
            if (!(raw instanceof Map)) { malformedOmsReturnCount++; return }
            Map omsReturn = (Map) raw
            // I5 fix, widened by Important #2 (fix-wave-C): GID-normalize BOTH OMS-side ids before
            // comparing against Shopify's (already bare) id sets/order keys. orderExternalId was
            // left un-normalized by the original I5 fix, which nullified it end to end: if OMS ever
            // emits GIDs, byOrder.get(orderExternalId) below would return null for every return and
            // every OMS return would report missing-in-Shopify regardless of externalId. Idempotent
            // for bare numerics, so this cannot regress the observed case.
            String externalId = stripShopifyGidTail(normalize(omsReturn.get("externalId")))
            String orderExternalId = stripShopifyGidTail(normalize(omsReturn.get("orderExternalId")))
            if (!externalId || !orderExternalId) { malformedOmsReturnCount++; return }

            Map<String, Set<String>> order = byOrder.get(orderExternalId)
            boolean refundMatch = order != null && order.get("refundIds").contains(externalId)
            boolean returnMatch = !refundMatch && order != null && order.get("returnIds").contains(externalId)

            if (refundMatch || returnMatch) {
                matchedCount++
                ordersMatchedForward.add(orderExternalId)
                return
            }

            if (parseMillis(omsReturn.get("entryDate")) > graceFloor) {
                pendingCount++
                return
            }
            String returnId = normalize(omsReturn.get("returnId"))
            String returnDescriptor = (returnId ? "return ${returnId}" : "return").toString()
            missingInShopify.add([
                    diffType : TYPE_MISSING_IN_SHOPIFY,
                    primaryId: externalId,
                    presentIn: omsSideLabel,
                    missingIn: shopifySideLabel,
                    message  : "${omsSideLabel} ${returnDescriptor} (id ${externalId}) on order ${orderExternalId} has no matching refund or return in ${shopifySideLabel}.".toString(),
                    data     : [orderExternalId: orderExternalId, returnId: returnId],
            ])
        }

        List<Map<String, Object>> missingInOms = []
        int suppressedOrderCount = 0
        shopifyOrders.each { Object raw ->
            if (!(raw instanceof Map)) { malformedShopifyOrderCount++; return }
            Map order = (Map) raw
            String orderId = normalize(order.get("orderId"))
            if (!orderId) { malformedShopifyOrderCount++; return }
            // Per design §4: the event is demonstrably captured under the other id, so do not
            // re-report it against this order. Counted so the audit note can disclose that a
            // per-order (not per-return) suppression occurred — see design §4 known phase-1
            // imprecision: an order with more than one return could mask a second missing refund.
            if (ordersMatchedForward.contains(orderId)) {
                suppressedOrderCount++
                return
            }

            // I1 fix: the reverse grace must measure the REFUND's own creation time, not the
            // order's — a refund created 5 minutes ago on a 3-month-old order is still young. The
            // upstream Shopify extractor emits per-refund createdAt (Important #1, fix-wave-C: now a
            // `refunds: [{id, createdAt}, ...]` list — a data-keyed map inflated the Spark-inferred
            // ingest schema's field count to the file's total distinct id count; see
            // resolveRefundsCreatedAt). Fall back to the order's createdAt only when no per-event
            // date is available at all (an older extract shape with neither shape present).
            Map<String, String> refundsCreatedAt = resolveRefundsCreatedAt(order)
            long orderCreatedMillis = parseMillis(order.get("createdAt"))

            // Invariant: any refundId here equal to an OMS externalId on this order would already
            // have set refundMatch = true in the forward pass above (same normalized string, same
            // order lookup), which adds this order to ordersMatchedForward and skips it at the
            // guard above — so no membership check against OMS ids is needed or reachable here.
            idSet(order.get("refundIds")).each { String refundId ->
                String rawRefundCreatedAt = refundsCreatedAt.get(refundId)
                long refundCreatedMillis = rawRefundCreatedAt ? parseMillis(rawRefundCreatedAt) : orderCreatedMillis
                // Important #3 (fix-wave-C): this refund can be present ONLY because the Shopify
                // extractor's fetch/emit floor now reaches back before windowStart (RQ-23 lookback,
                // see class doc). It was available to the forward pass above for exactly that
                // reason; the reverse pass must not turn around and report it missing-in-OMS, which
                // would just relocate the false-missing onto the other side. Gate on the caller-
                // supplied reporting windowStartMillis; absent that, degrade to pre-fix behaviour.
                if (windowStartMillis != null && refundCreatedMillis < windowStartMillis) return
                if (refundCreatedMillis > graceFloor) {
                    pendingCount++
                    return
                }
                missingInOms.add([
                        diffType : TYPE_MISSING_IN_OMS,
                        primaryId: refundId,
                        presentIn: shopifySideLabel,
                        missingIn: omsSideLabel,
                        message  : "${shopifySideLabel} refund ${refundId} on order ${orderId} has no matching return in ${omsSideLabel}.".toString(),
                        data     : [orderExternalId: orderId],
                ])
            }
        }

        return [
                matchedCount              : matchedCount,
                pendingCount              : pendingCount,
                missingInShopify          : missingInShopify,
                missingInOms              : missingInOms,
                malformedOmsReturnCount   : malformedOmsReturnCount,
                malformedShopifyOrderCount: malformedShopifyOrderCount,
                auditNote                 : buildAuditNote(matchedCount, missingInShopify.size(),
                        missingInOms.size(), pendingCount, graceHours, suppressedOrderCount),
        ]
    }

    /**
     * File-facing wrapper over verifyReturnPresence. Kept separate from the rule itself so the rule
     * stays a pure function over two lists and can be tested without touching disk.
     *
     * Advisory by design: the compare has already succeeded by the time this runs, so a missing or
     * unreadable extract degrades to a warning rather than failing the run — the same posture as the
     * exchange manifest sidecar.
     */
    static Map<String, Object> verifyReturnPresenceForRun(Map<String, Object> args) {
        List<String> warnings = []
        File omsFile = (File) args?.omsFile
        File shopifyFile = (File) args?.shopifyFile
        File diffFile = (File) args?.diffFile

        List omsReturns = readRecords(omsFile, "OMS returns", warnings)
        List shopifyOrders = readRecords(shopifyFile, "Shopify return references", warnings)
        if (omsReturns == null || shopifyOrders == null || diffFile == null || !diffFile.isFile()) {
            return [performed: false, appendedCount: 0, warnings: warnings, auditNote: null]
        }

        Map<String, Object> result = verifyReturnPresence([
                omsReturns       : omsReturns,
                shopifyOrders    : shopifyOrders,
                graceHours       : args?.graceHours,
                nowMillis        : args?.nowMillis,
                omsSideLabel     : args?.omsSideLabel,
                shopifySideLabel : args?.shopifySideLabel,
                // Important #3 (fix-wave-C): threaded straight through from the caller (runSavedRunDiff
                // .groovy's windowStartDate), which already resolves the run's reporting window.
                // verifyReturnPresence itself degrades to pre-fix behaviour when this is absent.
                windowStartMillis: args?.windowStartMillis,
        ])

        // M2: surface malformed-record counts as ordinary (actionable) warnings — deliberately NOT
        // prefixed with AUDIT_NOTE_PREFIX, so partitionAuditNotes keeps treating this as real signal
        // rather than folding it into the always-on "show your work" sentence (fix I6).
        int malformedOmsCount = normalizeInt(result.malformedOmsReturnCount, 0)
        int malformedShopifyCount = normalizeInt(result.malformedShopifyOrderCount, 0)
        if (malformedOmsCount > 0) {
            warnings.add("Return presence check skipped ${malformedOmsCount} malformed OMS return record(s) (not an object, or missing externalId/orderExternalId).".toString())
        }
        if (malformedShopifyCount > 0) {
            warnings.add("Return presence check skipped ${malformedShopifyCount} malformed Shopify return reference record(s) (not an object, or missing orderId).".toString())
        }

        List<Map<String, Object>> rows = []
        rows.addAll((List) result.missingInShopify)
        rows.addAll((List) result.missingInOms)

        // appendDiffRows is `protected static VOID` (ExchangePairVerificationSupport.groovy:246) with
        // signature (File diffFile, List<Map> rows, String auditNote, Map<String,Integer> summaryBumps).
        // It does NOT return a count and its 4th argument is the summary-bump map, not a warnings list.
        // Wrap it exactly as the exchange stage does (:154-160): skip when there are no rows, and treat
        // a write failure as a warning — the compare already succeeded, so this must not fail the run.
        int appended = 0
        if (rows) {
            try {
                ExchangePairVerificationSupport.appendDiffRows(diffFile, rows, result.auditNote as String,
                        [totalDifferences: rows.size(), missingObjectDifferenceCount: rows.size()])
                appended = rows.size()
            } catch (Exception e) {
                warnings.add("Return presence check could not write diff rows: ${e.message}".toString())
            }
        }

        Map<String, Object> out = new LinkedHashMap<>(result)
        out.put("performed", true)
        out.put("appendedCount", appended)
        out.put("warnings", warnings)
        return out
    }

    private static List readRecords(File file, String label, List<String> warnings) {
        if (file == null || !file.isFile()) {
            warnings.add("${label} extract was not available; the return presence check did not run.".toString())
            return null
        }
        try {
            Object parsed = new groovy.json.JsonSlurper().parse(file, "UTF-8")
            Object records = (parsed instanceof Map) ? ((Map) parsed).get("records") : parsed
            return (records instanceof List) ? (List) records : []
        } catch (Exception e) {
            warnings.add("${label} extract could not be read (${e.message}); the return presence check did not run.".toString())
            return null
        }
    }

    private static Map<String, Map<String, Set<String>>> indexShopifyOrders(List shopifyOrders) {
        Map<String, Map<String, Set<String>>> byOrder = [:]
        shopifyOrders.each { Object raw ->
            if (!(raw instanceof Map)) return
            Map order = (Map) raw
            String orderId = normalize(order.get("orderId"))
            if (!orderId) return
            byOrder.put(orderId, [
                    refundIds: idSet(order.get("refundIds")),
                    returnIds: idSet(order.get("returnIds")),
            ])
        }
        return byOrder
    }

    private static Set<String> idSet(Object rawIds) {
        if (!(rawIds instanceof List)) return new HashSet<String>()
        return ((List) rawIds).collect { normalize(it) }.findAll { it } as Set<String>
    }

    /**
     * C3 fix: real captured OMS REST output serializes timestamps as epoch-millis integers, not
     * ISO-8601 strings — verified directly against a captured extract (entryDate/orderDate/
     * createdStamp all ints). The old Instant.parse-only implementation returned 0L for every such
     * value, which is always <= graceFloor, so every young unmatched OMS return silently reported
     * missing instead of pending. Accepts a raw Number, an all-digits String (what normalize()
     * turns a JSON integer into), or an ISO-8601 String, in that order.
     */
    private static long parseMillis(Object rawTimestamp) {
        if (rawTimestamp instanceof Number) return ((Number) rawTimestamp).longValue()
        String value = normalize(rawTimestamp)
        if (!value) return 0L
        if (ALL_DIGITS_PATTERN.matcher(value).matches()) {
            try {
                return Long.parseLong(value)
            } catch (NumberFormatException ignored) {
                return 0L
            }
        }
        try {
            return Instant.parse(value).toEpochMilli()
        } catch (Exception ignored) {
            return 0L
        }
    }

    /**
     * I5 fix: mirrors CompareDatasetSupport.applyIdNormalizer's SHOPIFY_GID_TAIL Spark expression
     * exactly, for the plain-Groovy value comparisons this class does. A `gid://shopify/.../123`
     * value yields "123"; a bare numeric value passes through unchanged (idempotent); anything else
     * (no trailing digits at all) passes through unchanged too.
     */
    private static String stripShopifyGidTail(String value) {
        if (!value) return value
        Matcher gidMatcher = SHOPIFY_GID_TAIL_PATTERN.matcher(value)
        if (gidMatcher.find()) return gidMatcher.group(1)
        Matcher digitsMatcher = TRAILING_DIGITS_PATTERN.matcher(value)
        if (digitsMatcher.find()) return digitsMatcher.group(1)
        return value
    }

    /**
     * Important #1 (fix-wave-C): the upstream Shopify extractor now emits `refunds: [{id,
     * createdAt}, ...]` — a stable-shape list — instead of a `refundsCreatedAt: {id: createdAt}`
     * map. The map shape's field count tracked the number of distinct refund ids in the file, which
     * a plain (schema-inferring) Spark JSON read turns into a StructType whose width is the union of
     * every id across the whole window (see ShopifyReturnRefsSupport's class doc). Read the list
     * shape when present; fall back to the old map shape so the two repos' fixes can land
     * independently of each other.
     */
    private static Map<String, String> resolveRefundsCreatedAt(Map order) {
        Object refundsList = order.get("refunds")
        if (refundsList instanceof List) {
            Map<String, String> out = [:]
            ((List) refundsList).each { Object raw ->
                if (!(raw instanceof Map)) return
                String id = normalize(((Map) raw).get("id"))
                if (id) out.put(id, normalize(((Map) raw).get("createdAt")))
            }
            return out
        }
        return normalizedStringMap(order.get("refundsCreatedAt"))
    }

    /** I1 fix: normalized (trimmed, blank-key-dropped) String->String view of a raw JSON map. */
    private static Map<String, String> normalizedStringMap(Object rawMap) {
        Map<String, String> out = [:]
        if (!(rawMap instanceof Map)) return out
        ((Map) rawMap).each { Object key, Object value ->
            String normalizedKey = normalize(key)
            if (normalizedKey) out.put(normalizedKey, normalize(value))
        }
        return out
    }

    /**
     * One sentence the operator always gets — even an all-matched run shows its work. When at
     * least one order's reverse check was suppressed by an earlier forward match, a second
     * sentence discloses it: suppression is per order, not per return event (design §4 known
     * phase-1 imprecision), so a second missing refund on that order would not be independently
     * caught. Only appended when it can actually apply — never a blanket disclaimer.
     */
    private static String buildAuditNote(int matchedCount, int missingInShopifyCount,
                                         int missingInOmsCount, int pendingCount, int graceHours,
                                         int suppressedOrderCount) {
        String note = "Return presence check: ${matchedCount} matched, ${missingInShopifyCount} missing in Shopify, " +
                "${missingInOmsCount} missing in OMS, ${pendingCount} pending (younger than ${graceHours}h)."
        if (suppressedOrderCount > 0) {
            note += " ${suppressedOrderCount} order(s) had their reverse (missing-in-OMS) check suppressed by " +
                    "an earlier forward match; on an order with more than one return, a second missing refund " +
                    "would not be caught independently (known phase-1 limitation)."
        }
        return note
    }
}
