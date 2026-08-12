package darpan.facade.reconciliation

import java.time.Instant

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
 * sync was measured at ~38 minutes (RQ-23); the 3h default matches the exchange stage.
 *
 * Known phase-1 imprecision: on an order carrying several returns, the forward-match suppression is
 * per-order rather than per-event. Exact reverse attribution needs the design's §8 typed-field hedge
 * or a Shopify Return→Refund link. Accepted, and stated in the audit note.
 */
class ReturnPresenceVerificationSupport {

    static final String TYPE_MISSING_IN_SHOPIFY = "RETURN_MISSING_IN_SHOPIFY"
    static final String TYPE_MISSING_IN_OMS = "RETURN_MISSING_IN_OMS"
    static final int DEFAULT_GRACE_HOURS = 3
    // Mirrors ExchangePairVerificationSupport.AUDIT_NOTE_PREFIX: the opening phrase of the
    // always-emitted audit sentence. Not currently consumed by TenantNotificationSupport's
    // partitionAuditNotes (that classifier only recognizes the exchange and missing-diff prefixes
    // today) — kept as the producer-owned constant this class's own audit note already renders,
    // ready for that classifier to be extended. See buildAuditNote below; the literal there is not
    // yet rewritten to use this constant.
    static final String AUDIT_NOTE_PREFIX = "Return presence check: "

    static Map<String, Object> verifyReturnPresence(Map<String, Object> args) {
        List omsReturns = (args?.omsReturns instanceof List) ? (List) args.omsReturns : []
        List shopifyOrders = (args?.shopifyOrders instanceof List) ? (List) args.shopifyOrders : []
        int graceHours = normalizeInt(args?.graceHours, DEFAULT_GRACE_HOURS)
        long nowMillis = (args?.nowMillis instanceof Number)
                ? ((Number) args.nowMillis).longValue()
                : System.currentTimeMillis()
        long graceFloor = nowMillis - graceHours * 3600_000L

        Map<String, Map<String, Set<String>>> byOrder = indexShopifyOrders(shopifyOrders)

        int matchedCount = 0
        int pendingCount = 0
        List<Map<String, Object>> missingInShopify = []
        Set<String> ordersMatchedForward = new HashSet<>()

        omsReturns.each { Object raw ->
            if (!(raw instanceof Map)) return
            Map omsReturn = (Map) raw
            String externalId = normalize(omsReturn.get("externalId"))
            String orderExternalId = normalize(omsReturn.get("orderExternalId"))
            if (!externalId || !orderExternalId) return

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
            missingInShopify.add([
                    diffType       : TYPE_MISSING_IN_SHOPIFY,
                    externalId     : externalId,
                    orderExternalId: orderExternalId,
                    returnId       : normalize(omsReturn.get("returnId")),
            ])
        }

        List<Map<String, Object>> missingInOms = []
        int suppressedOrderCount = 0
        shopifyOrders.each { Object raw ->
            if (!(raw instanceof Map)) return
            Map order = (Map) raw
            String orderId = normalize(order.get("orderId"))
            if (!orderId) return
            // Per design §4: the event is demonstrably captured under the other id, so do not
            // re-report it against this order. Counted so the audit note can disclose that a
            // per-order (not per-return) suppression occurred — see design §4 known phase-1
            // imprecision: an order with more than one return could mask a second missing refund.
            if (ordersMatchedForward.contains(orderId)) {
                suppressedOrderCount++
                return
            }

            boolean young = parseMillis(order.get("createdAt")) > graceFloor

            // Invariant: any refundId here equal to an OMS externalId on this order would already
            // have set refundMatch = true in the forward pass above (same normalized string, same
            // order lookup), which adds this order to ordersMatchedForward and skips it at the
            // guard above — so no membership check against OMS ids is needed or reachable here.
            idSet(order.get("refundIds")).each { String refundId ->
                if (young) {
                    pendingCount++
                    return
                }
                missingInOms.add([
                        diffType       : TYPE_MISSING_IN_OMS,
                        refundId       : refundId,
                        orderExternalId: orderId,
                ])
            }
        }

        return [
                matchedCount    : matchedCount,
                pendingCount    : pendingCount,
                missingInShopify: missingInShopify,
                missingInOms    : missingInOms,
                auditNote       : buildAuditNote(matchedCount, missingInShopify.size(),
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
                omsReturns   : omsReturns,
                shopifyOrders: shopifyOrders,
                graceHours   : args?.graceHours,
                nowMillis    : args?.nowMillis,
        ])

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

    private static long parseMillis(Object rawTimestamp) {
        String value = normalize(rawTimestamp)
        if (!value) return 0L
        try {
            return Instant.parse(value).toEpochMilli()
        } catch (Exception ignored) {
            return 0L
        }
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
