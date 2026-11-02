package darpan.facade.reconciliation

import groovy.json.JsonSlurper

import static darpan.common.ValueSupport.normalize

/**
 * REBUILT-RETURN PAIRING (DAR-BE-180). A return on an order past Shopify's 60-day read limit is rebuilt
 * from its return_created event and keyed by the EVENT id (DAR-BE-085: no refund or return id is
 * reachable), while HotWax keys the same return by the Shopify refund id. The compare can never join them,
 * so it reports both halves of one return: the Shopify row as missing in HotWax, the HotWax row as missing
 * in Shopify. Every verify pass after that pays for the mismatch — a Shopify point lookup per HotWax row,
 * a superseded suppression per Shopify row — and on a month the counts outgrow every lookup cap.
 *
 * Measured on gorjana 2026-10 (run 102152, 20,892 Shopify / 20,707 HotWax rows): 10,978 Shopify rows were
 * rebuilt, and 10,818 of them pair 1:1 with an unmatched HotWax return on the same order + line; the
 * unmatched counts fell 85% on both sides. Both rows already carry the key: the rebuilt row's orderId and
 * lineIds, the HotWax row's orderExternalId and items[].orderItemExternalId (CompareDatasetSupport embeds
 * the whole record as the row's data). So this pass reads only the diff document — no lookup, no cost —
 * and runs FIRST, before the missing-diff pass's paid lookups.
 *
 * Rules, the same as Pass 1.6's REBUILT_RETURN pairing (ReturnPresenceVerificationSupport):
 *   - only rows with orderReadable == false; a readable row has a real id and stays with the id passes;
 *   - a HotWax return entered no more than 30 days before the Shopify event; a cancelled one never pairs;
 *   - one HotWax return line accounts for one Shopify event, oldest event first;
 *   - an event pairs only when EVERY line it touched finds a line; partial coverage pairs nothing;
 *   - a HotWax row is removed once every one of its lines is accounted for.
 */
class RebuiltReturnPairingSupport {

    /** Opening of the always-emitted audit sentence; TenantNotificationSupport.partitionAuditNotes reads it. */
    static final String AUDIT_NOTE_PREFIX = "Rebuilt return pairing: "
    static final String ORDER_READABLE_FIELD = ReturnPresenceVerificationSupport.ORDER_READABLE_FIELD
    static final String RETURN_CANCELLED_STATUS = "RETURN_CANCELLED"

    /**
     * args: diffFile, file1Label, file2Label, omsSideLabel, shopifySideLabel. Returns the verify-pass map
     * RunVerificationSupport.applyVerificationOutcome folds: performed, rewritten, removedCount,
     * removedMissingInFile1/2, pairedCount, warnings, auditNote.
     */
    static Map<String, Object> pairRebuiltReturns(Map args) {
        File diffFile = (File) args?.diffFile
        List<String> warnings = []
        Map<String, Object> inert = [performed: false, rewritten: false, removedCount: 0, removedMissingInFile1: 0,
                                     removedMissingInFile2: 0, pairedCount: 0, warnings: warnings, auditNote: null] as Map<String, Object>
        if (diffFile == null || !diffFile.isFile()) return inert

        String omsToken = DiffDetailClassifier.normalizeToken(args.omsSideLabel)
        String shopifyToken = DiffDetailClassifier.normalizeToken(args.shopifySideLabel)
        String file1Token = DiffDetailClassifier.normalizeToken(args.file1Label)
        String file2Token = DiffDetailClassifier.normalizeToken(args.file2Label)
        if (!omsToken || !shopifyToken) return inert

        // Pass 1 — collect only the keys. Rows missing in HotWax carry the Shopify record; rows missing in
        // Shopify carry the HotWax record.
        JsonSlurper slurper = new JsonSlurper()
        List<Map<String, Object>> events = []
        Map<String, List<Map<String, Object>>> omsByOrder = [:]
        boolean sawDifferences = DiffDocumentStreamSupport.forEachMissingRow(diffFile, slurper) { Map row ->
            String missingToken = DiffDetailClassifier.normalizeToken(row.get("missingIn"))
            if (missingToken != omsToken && missingToken != shopifyToken) return
            Map data = parseData(slurper, row.get("data"))
            if (data == null) return
            String rowId = DiffDocumentStreamSupport.rowIdOf(row)
            if (!rowId) return
            if (missingToken == omsToken) {
                if (data.get(ORDER_READABLE_FIELD) != false) return
                String orderId = normalize(data.get("orderId"))
                List<String> lineIds = splitIds(data.get("lineIds"))
                Long createdMillis = ReturnPresenceVerificationSupport.parseMillisOrNull(data.get("createdAt"))
                if (orderId && lineIds && createdMillis != null) {
                    events.add([rowId: rowId, orderId: orderId, lineIds: lineIds, createdMillis: createdMillis] as Map<String, Object>)
                }
            } else {
                if (normalize(data.get("statusId")) == RETURN_CANCELLED_STATUS) return
                String orderId = normalize(data.get("orderExternalId"))
                Long entryMillis = ReturnPresenceVerificationSupport.parseMillisOrNull(data.get("entryDate"))
                List<String> lines = ((data.get("items") instanceof List ? (List) data.get("items") : []) as List)
                        .collect { it instanceof Map ? normalize(((Map) it).get("orderItemExternalId")) : null }.findAll { it }
                if (orderId && entryMillis != null && lines) {
                    omsByOrder.computeIfAbsent(orderId) { [] }.add([rowId: rowId, entryMillis: entryMillis, lines: lines,
                                                                   claimed: new boolean[lines.size()]] as Map<String, Object>)
                }
            }
        }
        if (!sawDifferences) return inert

        // Pass 1.5 — pair in memory, oldest event first.
        Set<String> pairedEventIds = new LinkedHashSet<String>()
        events.sort { (Long) it.createdMillis }.each { Map<String, Object> event ->
            List<Map<String, Object>> returns = omsByOrder.get((String) event.orderId)
            if (!returns) return
            long floor = ((Long) event.createdMillis) - ReturnPresenceVerificationSupport.REBUILT_RETURN_PAIR_LOOKBACK_MILLIS
            List<List> tentative = []
            boolean covered = ((List<String>) event.lineIds).every { String lineId ->
                for (Map<String, Object> ret : returns) {
                    if (((Long) ret.entryMillis) < floor) continue
                    List<String> lines = (List<String>) ret.lines
                    boolean[] claimed = (boolean[]) ret.claimed
                    for (int i = 0; i < lines.size(); i++) {
                        if (!claimed[i] && lines[i] == lineId && !tentative.any { it[0].is(ret) && it[1] == i }) {
                            tentative.add([ret, i])
                            return true
                        }
                    }
                }
                return false
            }
            if (!covered) return
            tentative.each { List claim -> ((boolean[]) ((Map) claim[0]).claimed)[(int) claim[1]] = true }
            pairedEventIds.add((String) event.rowId)
        }

        Set<String> removedOmsIds = new LinkedHashSet<String>()
        omsByOrder.values().flatten().each { Object o ->
            Map ret = (Map) o
            boolean[] claimed = (boolean[]) ret.claimed
            if (claimed.length > 0 && (0..<claimed.length).every { claimed[it] }) removedOmsIds.add((String) ret.rowId)
        }

        int removedCount = pairedEventIds.size() + removedOmsIds.size()
        String auditNote = "${AUDIT_NOTE_PREFIX}${pairedEventIds.size()} return(s) on orders past Shopify's 60-day read limit " +
                "paired with their ${args.omsSideLabel} return by order and line; ${removedOmsIds.size()} ${args.omsSideLabel} " +
                "row(s) accounted for."
        if (removedCount == 0) {
            return [performed: true, rewritten: false, removedCount: 0, removedMissingInFile1: 0, removedMissingInFile2: 0,
                    pairedCount: 0, warnings: warnings, auditNote: null] as Map<String, Object>
        }

        // A row missing in HotWax is present only in Shopify's file, and vice versa (onlyInFileN mapping).
        int removedMissingInFile1 = (shopifyToken == file1Token ? removedOmsIds.size() : 0) +
                (omsToken == file1Token ? pairedEventIds.size() : 0)
        int removedMissingInFile2 = (shopifyToken == file2Token ? removedOmsIds.size() : 0) +
                (omsToken == file2Token ? pairedEventIds.size() : 0)
        Map<String, Set<String>> removeIdsByToken = [(omsToken): pairedEventIds, (shopifyToken): removedOmsIds]
        DiffDocumentStreamSupport.removeMissingRows(diffFile, ".rebuilt-pair-tmp", removeIdsByToken,
                removedCount, removedMissingInFile1, removedMissingInFile2, auditNote)

        return [performed: true, rewritten: true, removedCount: removedCount,
                removedMissingInFile1: removedMissingInFile1, removedMissingInFile2: removedMissingInFile2,
                pairedCount: pairedEventIds.size(), warnings: warnings, auditNote: auditNote] as Map<String, Object>
    }

    private static Map parseData(JsonSlurper slurper, Object raw) {
        if (raw instanceof Map) return (Map) raw
        String text = normalize(raw)
        if (!text) return null
        try {
            Object parsed = slurper.parseText(text)
            return parsed instanceof Map ? (Map) parsed : null
        } catch (Exception ignored) {
            return null
        }
    }

    private static List<String> splitIds(Object raw) {
        String text = normalize(raw)
        return text ? text.split(",").collect { normalize(it) }.findAll { it }.unique() : []
    }
}
