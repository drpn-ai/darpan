package darpan.facade.reconciliation

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Exchange pair verify stage (spec 2026-07-30-exchange-order-reconciliation-design.md): reads the
 * OMS extraction's exchange-manifest sidecar and point-checks each pair against both sources of
 * record. Strictly conservative — a failed or skipped lookup appends nothing and only warns.
 * Appended rows use the same one-JSON-row-per-line contract writeDiffDatasetOutput produces and
 * MissingDiffVerificationSupport streams; see that class's fixtures for the format this mirrors.
 * The on-disk summary object's running total lives under the key "totalDifferences" (not
 * "differenceCount" — that name is only used for in-memory result maps elsewhere in this codebase).
 *
 * An externalId the Shopify lookup did not resolve at all (state == null — deleted/archived/never a
 * Shopify id) is evidence-free for the V1 check, not evidence of a missing exchange: it is counted in
 * unresolvedShopifyCount and warned about, never turned into an EXCHANGE_MISSING_IN_SHOPIFY row. V3
 * (original-missing-in-OMS) is unaffected since its evidence is the OMS pair lookup, which did resolve.
 */
class ExchangePairVerificationSupport {
    static final String TYPE_MISSING_IN_SHOPIFY = "EXCHANGE_MISSING_IN_SHOPIFY"
    static final String TYPE_ORIGINAL_MISSING_IN_OMS = "EXCHANGE_ORIGINAL_MISSING_IN_OMS"
    static final String TYPE_PAIR_AMOUNT_MISMATCH = "EXCHANGE_PAIR_AMOUNT_MISMATCH"
    // Product rule (2026-07-31): ignore exchanges/returns within 3 hours of creation — they may
    // still be syncing into OMS. Anything older is due immediately: the OMS exchange order's
    // creation date IS the return-processing date (Shopify return -> OMS return record observed at
    // ~38 minutes), so a longer wait would guard against a lag that does not exist in this direction.
    static final int DEFAULT_GRACE_HOURS = 3
    // Per-run latency bound, not a completeness cap: pairs beyond this are counted as deferred and
    // reported, never silently skipped. The OMS pair lookup is one sequential GET per externalId
    // (~1-3s each on the long-haul link), and high-exchange tenants run ~486/day (gorjana,
    // measured 2026-07-31) — checking everything in one interactive run would stall it for minutes.
    static final int DEFAULT_MAX_PAIRS = 50
    static final BigDecimal DEFAULT_AMOUNT_TOLERANCE = new BigDecimal("0.01")
    // Mirrors OmsRestSourceSupport.PAIR_LOOKUP_MAX_IDS (darpan-hotwax): the OMS pair lookup service
    // hard-rejects any single call requesting more than this many externalIds, so a manifest with
    // more pairs than this must be split across multiple omsPairLookup.call() invocations.
    static final int OMS_PAIR_LOOKUP_CHUNK_SIZE = 100

    private static final String DIFFERENCES_HEADER = "\"differences\":["
    private static final String PROCESSING_WARNINGS_PREFIX = "\"processingWarnings\":"
    private static final Pattern TOTAL_DIFFERENCES_COUNT = Pattern.compile(/("totalDifferences":)(\d+)/)

    static Map<String, Object> verifyExchangePairs(Map<String, Object> args) {
        List<String> warnings = []
        Map<String, Object> result = [performed: false, appendedCount: 0, appendedByType: [:], pendingCount: 0,
                skippedCancelledCount: 0, checkedPairCount: 0, unresolvedShopifyCount: 0, deferredPairCount: 0,
                lookupFailed: false, warnings: warnings, auditNote: null]
        File manifestFile = (File) args.manifestFile
        File diffFile = (File) args.diffFile
        Closure shopifyLookup = (Closure) args.shopifyLookup
        Closure omsPairLookup = (Closure) args.omsPairLookup
        long nowMillis = ((Number) args.nowMillis).longValue()
        int graceHours = (args.graceHours instanceof Number) ? ((Number) args.graceHours).intValue() : DEFAULT_GRACE_HOURS
        int maxPairs = (args.maxPairs instanceof Number) ? ((Number) args.maxPairs).intValue() : DEFAULT_MAX_PAIRS
        BigDecimal tolerance = args.amountTolerance instanceof BigDecimal ? (BigDecimal) args.amountTolerance : DEFAULT_AMOUNT_TOLERANCE

        if (manifestFile == null || !manifestFile.isFile() || diffFile == null || !diffFile.isFile()
                || shopifyLookup == null || omsPairLookup == null) return result

        List manifest
        try {
            Object parsed = new JsonSlurper().parse(manifestFile, "UTF-8")
            manifest = parsed instanceof Map ? (List) (((Map) parsed).get('manifest') ?: []) : []
        } catch (Exception e) {
            warnings.add("Exchange verification skipped: manifest unreadable (${e.message}).".toString())
            return result
        }
        if (!manifest) return result
        result.performed = true

        long graceFloor = nowMillis - graceHours * 3600000L
        Map<String, List<Map>> entriesByExternalId = new LinkedHashMap<>()
        manifest.each { Object raw ->
            if (!(raw instanceof Map)) return
            Map entry = (Map) raw
            String externalId = entry.get('externalId')?.toString()?.trim()
            if (!externalId) return
            String statusId = entry.get('statusId')?.toString() ?: ""
            if (statusId.toUpperCase().contains("CANCEL")) { result.skippedCancelledCount = (result.skippedCancelledCount as int) + 1; return }
            Long orderDate = entry.get('orderDate') instanceof Number ? ((Number) entry.get('orderDate')).longValue() : null
            if (orderDate != null && orderDate > graceFloor) { result.pendingCount = (result.pendingCount as int) + 1; return }
            entriesByExternalId.computeIfAbsent(externalId) { [] }.add(entry)
        }
        if (!entriesByExternalId) {
            result.auditNote = buildAuditNote(result, 0, graceHours)
            return result
        }
        List<String> externalIds = new ArrayList<>(entriesByExternalId.keySet())
        if (externalIds.size() > maxPairs) {
            // Latency bound, not a skip: check the first maxPairs (manifest order) and report the
            // rest as deferred so busy tenants always make bounded progress with a visible queue.
            result.deferredPairCount = externalIds.size() - maxPairs
            externalIds = new ArrayList<>(externalIds.subList(0, maxPairs))
            warnings.add("Exchange verification checking the first ${maxPairs} of ${maxPairs + (result.deferredPairCount as int)} due pairs; ${result.deferredPairCount} deferred to a later run.".toString())
        }
        Map shopify = invokeLookup("Shopify exchange-state", shopifyLookup, externalIds, warnings)
        Map omsPairs = invokeChunkedOmsPairLookup(omsPairLookup, externalIds, warnings)
        if (shopify == null || omsPairs == null) { result.lookupFailed = true; return result }

        Map statesByOrderId = (Map) (shopify.statesByOrderId ?: [:])
        Map ordersByExternalId = (Map) (omsPairs.ordersByExternalId ?: [:])
        List<Map> rows = []
        externalIds.each { String externalId ->
            result.checkedPairCount = (result.checkedPairCount as int) + 1
            List<Map> manifestEntries = entriesByExternalId.get(externalId)
            Map state = (Map) statesByOrderId.get(externalId)
            List omsOrders = (List) (ordersByExternalId.get(externalId) ?: [])
            String exchangeNames = manifestEntries.collect { "${it.orderName} (${it.omsOrderId})" }.join(", ")

            boolean shopifyHasExchange = state != null && ((List) (state.get('exchanges') ?: []))
            if (state == null) {
                // No evidence either way — Shopify never resolved this externalId at all (deleted,
                // archived, or never a Shopify id). Flagging EXCHANGE_MISSING_IN_SHOPIFY here would
                // assert something the source of record never confirmed; stay conservative instead.
                result.unresolvedShopifyCount = (result.unresolvedShopifyCount as int) + 1
                warnings.add("Shopify could not resolve order ${externalId} for exchange verification.".toString())
            } else if (!shopifyHasExchange) {
                rows.add([diffType: TYPE_MISSING_IN_SHOPIFY, primaryId: externalId,
                        note: "OMS exchange order(s) ${exchangeNames} have no exchange on Shopify order ${externalId}.".toString(),
                        data: [manifestEntries: manifestEntries, shopifyReturnStatus: state?.get('returnStatus')]])
            }
            boolean originalPresent = omsOrders.any { it instanceof Map && ((Map) it).get('hasExchangeAssoc') != true }
            if (!originalPresent) {
                rows.add([diffType: TYPE_ORIGINAL_MISSING_IN_OMS, primaryId: externalId,
                        note: "No original (non-exchange) OMS order shares externalId ${externalId} with ${exchangeNames}.".toString(),
                        data: [manifestEntries: manifestEntries, omsOrders: omsOrders]])
            }
            if (shopifyHasExchange && originalPresent) {
                // V2 comparand is the ORIGINAL order(s) alone — live data (2026-07-31, 19/19 pairs)
                // proved HotWax mutates the original's grandTotal to the current net state, so adding
                // the exchange order's own total double-counts it by exactly that amount.
                BigDecimal omsOriginalTotal = omsOrders.inject(BigDecimal.ZERO) { BigDecimal acc, Object order ->
                    if (!(order instanceof Map) || ((Map) order).get('hasExchangeAssoc') == true) return acc
                    Object total = ((Map) order).get('grandTotal')
                    total == null ? acc : acc + new BigDecimal(total.toString())
                }
                BigDecimal shopifyTotal = (BigDecimal) state.get('currentTotalAmount')
                if (shopifyTotal != null && (omsOriginalTotal - shopifyTotal).abs() > tolerance) {
                    rows.add([diffType: TYPE_PAIR_AMOUNT_MISMATCH, primaryId: externalId,
                            note: "OMS original total ${omsOriginalTotal.toPlainString()} differs from Shopify current total ${shopifyTotal.toPlainString()} for order ${externalId}.".toString(),
                            data: [omsOriginalTotal: omsOriginalTotal, shopifyCurrentTotal: shopifyTotal,
                                   omsOrders: omsOrders, exchanges: state.get('exchanges')]])
                }
            }
        }

        // Built before the append so the SAME rewrite pass that adds the rows also injects this note
        // into the artifact's processingWarnings — otherwise a reopened saved run shows the new rows
        // with no explanation (the in-memory auditNote never reached the file on its own). rows.size()
        // stands in for the eventual result.appendedCount (identical value; appendedCount is only
        // assigned below, after a successful append, since a failed append must leave it at 0).
        String auditNote = buildAuditNote(result, rows.size(), graceHours)

        if (rows) {
            try {
                appendDiffRows(diffFile, rows, auditNote)
            } catch (Exception e) {
                warnings.add("Exchange verification could not write diff rows: ${e.message}".toString())
                result.lookupFailed = true
                return result
            }
            result.appendedCount = rows.size()
            result.appendedByType = rows.countBy { it.diffType }
        }
        result.auditNote = auditNote
        return result
    }

    /** One sentence the operator always gets — even an all-pending run must show its queue. */
    private static String buildAuditNote(Map<String, Object> result, int appendedRowCount, int graceHours) {
        String note = "Exchange pair verification checked ${result.checkedPairCount} pair(s): " +
                "${appendedRowCount} discrepancy row(s) appended, ${result.pendingCount} pending (younger than ${graceHours}h), " +
                "${result.skippedCancelledCount} cancelled skipped."
        if ((result.deferredPairCount as int) > 0) note += " ${result.deferredPairCount} deferred to a later run."
        if ((result.unresolvedShopifyCount as int) > 0) note += " ${result.unresolvedShopifyCount} unresolved in Shopify."
        return note
    }

    /**
     * The OMS pair lookup service hard-rejects calls above OMS_PAIR_LOOKUP_CHUNK_SIZE ids, so a
     * manifest of up to maxPairs (default 500) must be split into ≤100-id chunks, calling
     * omsPairLookup once per chunk and merging the returned ordersByExternalId maps. Conservative
     * posture is unchanged: the first chunk that fails (ok != true) or throws stops the loop and
     * makes the whole lookup fail exactly as a single failed lookup does today — no partial merge
     * is returned to the caller.
     */
    private static Map invokeChunkedOmsPairLookup(Closure omsPairLookup, List<String> externalIds, List<String> warnings) {
        Map<String, Object> mergedOrdersByExternalId = new LinkedHashMap<>()
        for (List<String> chunk : externalIds.collate(OMS_PAIR_LOOKUP_CHUNK_SIZE)) {
            Map chunkResult = invokeLookup("OMS pair", omsPairLookup, chunk, warnings)
            if (chunkResult == null) return null
            mergedOrdersByExternalId.putAll((Map) (chunkResult.ordersByExternalId ?: [:]))
        }
        return [ok: true, ordersByExternalId: mergedOrdersByExternalId]
    }

    private static Map invokeLookup(String label, Closure lookup, List<String> externalIds, List<String> warnings) {
        Map lookupResult
        try {
            lookupResult = (Map) lookup.call(externalIds)
        } catch (Exception e) {
            warnings.add("${label} lookup failed: ${e.message}".toString())
            return null
        }
        if (lookupResult?.ok != true) {
            warnings.add("${label} lookup unavailable: ${(lookupResult?.errors ?: ['no result']).join('; ')}".toString())
            return null
        }
        return lookupResult
    }

    /**
     * Streaming append: rows live one-per-line inside "differences":[ ... ]; the last row line ends
     * with "]". Rewrites line by line to a temp file, converts the last row's "]" to ",", writes the
     * new rows, bumps "totalDifferences" in the summary line, and — mirroring
     * MissingDiffVerificationSupport's PROCESSING_WARNINGS_PREFIX handling exactly — injects
     * auditNote into the artifact's "processingWarnings" array in the same single pass, so a
     * reopened saved run explains the appended rows instead of only surfacing them silently.
     * If the document has no "processingWarnings" line (never true for writeDiffDatasetOutput's
     * real output, but true for slimmer test fixtures) nothing is injected and no such line is
     * created — same as the sibling's behavior when that header is absent. Atomic same-directory move.
     */
    protected static void appendDiffRows(File diffFile, List<Map> rows, String auditNote) {
        File tempFile = File.createTempFile(diffFile.name + "-", ".exchange-verify", diffFile.parentFile)
        boolean committed = false
        try {
            JsonSlurper slurper = new JsonSlurper()
            List<String> rowJson = rows.collect { JsonOutput.toJson(it) }
            tempFile.withWriter("UTF-8") { Writer writer ->
                boolean inRows = false
                boolean appended = false
                diffFile.eachLine("UTF-8") { String line ->
                    String outLine = line
                    Matcher countMatcher = TOTAL_DIFFERENCES_COUNT.matcher(line)
                    if (!inRows && countMatcher.find()) {
                        long bumped = Long.parseLong(countMatcher.group(2)) + rows.size()
                        outLine = countMatcher.replaceFirst('$1' + bumped)
                    }
                    if (!inRows && auditNote && outLine.startsWith(PROCESSING_WARNINGS_PREFIX)) {
                        outLine = PROCESSING_WARNINGS_PREFIX +
                                JsonOutput.toJson(appendedWarnings(slurper, outLine, auditNote)) + ","
                    }
                    if (!appended) {
                        if (!inRows && outLine.startsWith(DIFFERENCES_HEADER)) {
                            if (outLine.startsWith(DIFFERENCES_HEADER + "]")) {
                                // empty differences array on one line: open it and append
                                writer.write(DIFFERENCES_HEADER + "\n")
                                rowJson.eachWithIndex { String json, int i ->
                                    writer.write(json + (i == rowJson.size() - 1 ? "]" : ",") + "\n")
                                }
                                String rest = outLine.substring((DIFFERENCES_HEADER + "]").length())
                                if (rest) writer.write(rest + "\n")
                                appended = true
                                return
                            }
                            inRows = true
                        } else if (inRows && outLine.endsWith("]")) {
                            writer.write(outLine.substring(0, outLine.length() - 1) + ",\n")
                            rowJson.eachWithIndex { String json, int i ->
                                writer.write(json + (i == rowJson.size() - 1 ? "]" : ",") + "\n")
                            }
                            appended = true
                            return
                        }
                    }
                    writer.write(outLine + "\n")
                }
                if (!appended) throw new IllegalStateException("diff document has no differences section to append to")
            }
            replaceFile(tempFile, diffFile)
            committed = true
        } finally {
            if (!committed) tempFile.delete()
        }
    }

    /** Mirrors MissingDiffVerificationSupport.appendedWarnings exactly: parse the existing
     *  "processingWarnings" array (empty list if unparseable), append auditNote when present. */
    private static List appendedWarnings(JsonSlurper slurper, String warningsLine, String auditNote) {
        Object fragment = headerFragment(slurper, warningsLine, PROCESSING_WARNINGS_PREFIX)
        List warningsList = fragment instanceof List ? new ArrayList((List) fragment) : []
        if (auditNote) warningsList.add(auditNote)
        return warningsList
    }

    /** Mirrors MissingDiffVerificationSupport.headerFragment exactly. */
    private static Object headerFragment(JsonSlurper slurper, String line, String prefix) {
        String fragment = line.substring(prefix.length()).trim()
        if (fragment.endsWith(",")) fragment = fragment.substring(0, fragment.length() - 1)
        try {
            return slurper.parseText(fragment)
        } catch (Exception ignored) {
            return null
        }
    }

    private static void replaceFile(File source, File target) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
