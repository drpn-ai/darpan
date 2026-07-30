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
 */
class ExchangePairVerificationSupport {
    static final String TYPE_MISSING_IN_SHOPIFY = "EXCHANGE_MISSING_IN_SHOPIFY"
    static final String TYPE_ORIGINAL_MISSING_IN_OMS = "EXCHANGE_ORIGINAL_MISSING_IN_OMS"
    static final String TYPE_PAIR_AMOUNT_MISMATCH = "EXCHANGE_PAIR_AMOUNT_MISMATCH"
    static final int DEFAULT_GRACE_DAYS = 7
    static final int DEFAULT_MAX_PAIRS = 500
    static final BigDecimal DEFAULT_AMOUNT_TOLERANCE = new BigDecimal("0.01")

    private static final String DIFFERENCES_HEADER = "\"differences\":["
    private static final Pattern TOTAL_DIFFERENCES_COUNT = Pattern.compile(/("totalDifferences":)(\d+)/)

    static Map<String, Object> verifyExchangePairs(Map<String, Object> args) {
        List<String> warnings = []
        Map<String, Object> result = [performed: false, appendedCount: 0, appendedByType: [:], pendingCount: 0,
                skippedCancelledCount: 0, checkedPairCount: 0, lookupFailed: false, warnings: warnings, auditNote: null]
        File manifestFile = (File) args.manifestFile
        File diffFile = (File) args.diffFile
        Closure shopifyLookup = (Closure) args.shopifyLookup
        Closure omsPairLookup = (Closure) args.omsPairLookup
        long nowMillis = ((Number) args.nowMillis).longValue()
        int graceDays = (args.graceDays instanceof Number) ? ((Number) args.graceDays).intValue() : DEFAULT_GRACE_DAYS
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

        long graceFloor = nowMillis - graceDays * 86400000L
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
        if (!entriesByExternalId) return result
        if (entriesByExternalId.size() > maxPairs) {
            warnings.add("Exchange verification skipped: ${entriesByExternalId.size()} pairs exceeds the ${maxPairs}-pair cap.".toString())
            return result
        }

        List<String> externalIds = new ArrayList<>(entriesByExternalId.keySet())
        Map shopify = invokeLookup("Shopify exchange-state", shopifyLookup, externalIds, warnings)
        Map omsPairs = invokeLookup("OMS pair", omsPairLookup, externalIds, warnings)
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
            if (!shopifyHasExchange) {
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
                BigDecimal omsPairTotal = omsOrders.inject(BigDecimal.ZERO) { BigDecimal acc, Object order ->
                    Object total = order instanceof Map ? ((Map) order).get('grandTotal') : null
                    total == null ? acc : acc + new BigDecimal(total.toString())
                }
                BigDecimal shopifyTotal = (BigDecimal) state.get('currentTotalAmount')
                if (shopifyTotal != null && (omsPairTotal - shopifyTotal).abs() > tolerance) {
                    rows.add([diffType: TYPE_PAIR_AMOUNT_MISMATCH, primaryId: externalId,
                            note: "OMS pair total ${omsPairTotal.toPlainString()} differs from Shopify current total ${shopifyTotal.toPlainString()} for order ${externalId}.".toString(),
                            data: [omsPairTotal: omsPairTotal, shopifyCurrentTotal: shopifyTotal,
                                   omsOrders: omsOrders, exchanges: state.get('exchanges')]])
                }
            }
        }

        if (rows) {
            try {
                appendDiffRows(diffFile, rows)
            } catch (Exception e) {
                warnings.add("Exchange verification could not write diff rows: ${e.message}".toString())
                result.lookupFailed = true
                return result
            }
            result.appendedCount = rows.size()
            result.appendedByType = rows.countBy { it.diffType }
        }
        result.auditNote = "Exchange pair verification checked ${result.checkedPairCount} pair(s): " +
                "${result.appendedCount} discrepancy row(s) appended, ${result.pendingCount} pending (younger than ${graceDays}d), " +
                "${result.skippedCancelledCount} cancelled skipped."
        return result
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
     * new rows, and bumps "totalDifferences" in the summary line. Atomic same-directory move.
     */
    protected static void appendDiffRows(File diffFile, List<Map> rows) {
        File tempFile = File.createTempFile(diffFile.name + "-", ".exchange-verify", diffFile.parentFile)
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
    }

    private static void replaceFile(File source, File target) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
