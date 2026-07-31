package darpan.facade.reconciliation

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Exchange presence check (product contract 2026-07-31): every exchange CREATED IN SHOPIFY within
 * the run window must have been imported into OMS as an exchange order. Direction is strictly
 * Shopify → OMS; confirmed absences surface as ordinary missing-from-the-OMS-side diff rows that
 * the result page's tiles count, exactly like base compare misses.
 *
 * Evidence chain per Shopify exchange (keyed by the order's legacy id == OMS externalId):
 *   1. matched against the run's OMS exchange manifest (the extraction's sidecar) — free;
 *   2. unmatched candidates older than the sync grace get ONE chunked OMS point lookup
 *      (escapes window boundaries: an exchange order created after midnight still counts);
 *   3. only lookup-confirmed absences become rows. Sweep/lookup failures, young exchanges, and
 *      over-cap candidates never flag anything — conservative posture identical to the
 *      missing-diff verify pass.
 *
 * Appended rows use the same one-JSON-row-per-line contract writeDiffDatasetOutput produces and
 * MissingDiffVerificationSupport streams; the on-disk summary's running total lives under
 * "totalDifferences", side misses under "onlyInFile1Count"/"onlyInFile2Count".
 */
class ExchangePairVerificationSupport {
    static final String TYPE_MISSING_IN_OMS = "EXCHANGE_MISSING_IN_OMS"
    // Product rule (2026-07-31): exchanges/returns within 3 hours of creation may still be syncing
    // into OMS — held as pending, never flagged. Sync itself completes in minutes (observed ~38min);
    // anything older than the grace with no OMS exchange order is a genuine import gap.
    static final int DEFAULT_GRACE_HOURS = 3
    // Per-run latency bound on CONFIRMING point lookups (manifest matching is free): only unmatched
    // candidates need a lookup. 200 covers the largest observed daily candidate set (102, gorjana
    // 2026-07-30) with headroom; the deferral path stays as the pathological-case backstop.
    static final int DEFAULT_MAX_LOOKUPS = 200
    // Mirrors OmsRestSourceSupport.PAIR_LOOKUP_MAX_IDS (darpan-hotwax): the OMS pair lookup service
    // hard-rejects any single call requesting more than this many externalIds.
    static final int OMS_PAIR_LOOKUP_CHUNK_SIZE = 100

    private static final String DIFFERENCES_HEADER = "\"differences\":["
    private static final String PROCESSING_WARNINGS_PREFIX = "\"processingWarnings\":"

    static Map<String, Object> verifyExchangePairs(Map<String, Object> args) {
        List<String> warnings = []
        Map<String, Object> result = [performed: false, appendedCount: 0, sweepExchangeCount: 0,
                matchedCount: 0, confirmedPresentCount: 0, pendingCount: 0, deferredLookupCount: 0,
                sweepTruncated: false, lookupFailed: false, warnings: warnings, auditNote: null]
        File manifestFile = (File) args.manifestFile   // OPTIONAL: no sidecar just means zero OMS exchanges
        File diffFile = (File) args.diffFile
        Closure shopifySweep = (Closure) args.shopifySweep
        Closure omsPairLookup = (Closure) args.omsPairLookup
        long nowMillis = ((Number) args.nowMillis).longValue()
        long windowStartMillis = ((Number) args.windowStartMillis).longValue()
        long windowEndMillis = ((Number) args.windowEndMillis).longValue()
        String omsSideLabel = args.omsSideLabel?.toString()?.trim() ?: "OMS"
        String omsSummaryCountKey = "FILE_1" == args.omsFileSide?.toString() ? "onlyInFile2Count" : "onlyInFile1Count"
        int graceHours = (args.graceHours instanceof Number) ? ((Number) args.graceHours).intValue() : DEFAULT_GRACE_HOURS
        int maxLookups = (args.maxLookups instanceof Number) ? ((Number) args.maxLookups).intValue() : DEFAULT_MAX_LOOKUPS

        if (diffFile == null || !diffFile.isFile() || shopifySweep == null || omsPairLookup == null) return result

        Set<String> omsExchangeExternalIds = readManifestExternalIds(manifestFile, warnings)
        if (omsExchangeExternalIds == null) return result   // unreadable manifest: warned, stay inert

        Map sweep = invokeSweep(shopifySweep, windowStartMillis, windowEndMillis, warnings)
        if (sweep == null) { result.lookupFailed = true; return result }
        result.performed = true
        result.sweepTruncated = sweep.truncated == true
        if (result.sweepTruncated) {
            warnings.add("Shopify exchange sweep hit its page cap — presence coverage for this window is partial.".toString())
        }

        long graceFloor = nowMillis - graceHours * 3600000L
        List<Map> eligible = []
        for (Object raw : (List) (sweep.exchanges ?: [])) {
            if (!(raw instanceof Map)) continue
            Map exchange = (Map) raw
            if (!exchange.get('externalId')) continue
            result.sweepExchangeCount = (result.sweepExchangeCount as int) + 1
            Long returnCreatedAt = exchange.get('returnCreatedAtMillis') instanceof Number
                    ? ((Number) exchange.get('returnCreatedAtMillis')).longValue() : null
            if (returnCreatedAt != null && returnCreatedAt > graceFloor) {
                result.pendingCount = (result.pendingCount as int) + 1
                continue
            }
            eligible.add(exchange)
        }

        List<Map> candidates = []
        eligible.each { Map exchange ->
            int exchangeReturnCount = exchange.get('exchangeReturnCount') instanceof Number
                    ? ((Number) exchange.get('exchangeReturnCount')).intValue() : 1
            // The manifest shortcut only proves "≥1 exchange order in this OMS window", so it can
            // only settle single-exchange orders. Repeat-exchange orders (count > 1) always go to
            // the point lookup, which counts OMS exchange orders — a missing second exchange must
            // not hide behind an earlier imported one.
            if (exchangeReturnCount <= 1 && omsExchangeExternalIds.contains(exchange.get('externalId').toString())) {
                result.matchedCount = (result.matchedCount as int) + 1
            } else {
                candidates.add(exchange)
            }
        }
        if (candidates.size() > maxLookups) {
            result.deferredLookupCount = candidates.size() - maxLookups
            candidates = candidates.subList(0, maxLookups)
            warnings.add("Exchange presence check confirming the first ${maxLookups} unmatched candidates; ${result.deferredLookupCount} deferred to a later run.".toString())
        }

        List<Map> rows = []
        if (candidates) {
            List<String> candidateIds = candidates.collect { it.get('externalId').toString() }.unique()
            Map omsPairs = invokeChunkedOmsPairLookup(omsPairLookup, candidateIds, warnings)
            if (omsPairs == null) { result.lookupFailed = true; return result }
            Map ordersByExternalId = (Map) (omsPairs.ordersByExternalId ?: [:])
            candidates.each { Map exchange ->
                String externalId = exchange.get('externalId').toString()
                List omsOrders = (List) (ordersByExternalId.get(externalId) ?: [])
                int omsExchangeOrderCount = omsOrders.count { it instanceof Map && ((Map) it).get('hasExchangeAssoc') == true } as int
                int exchangeReturnCount = exchange.get('exchangeReturnCount') instanceof Number
                        ? ((Number) exchange.get('exchangeReturnCount')).intValue() : 1
                if (omsExchangeOrderCount >= exchangeReturnCount) {
                    result.confirmedPresentCount = (result.confirmedPresentCount as int) + 1
                } else {
                    String shortfall = exchangeReturnCount > 1
                            ? "has ${exchangeReturnCount} Shopify exchange(s) but only ${omsExchangeOrderCount} exchange order(s) in ${omsSideLabel}"
                            : "has no exchange order in ${omsSideLabel}"
                    rows.add([diffType: TYPE_MISSING_IN_OMS, primaryId: externalId, missingIn: omsSideLabel,
                            note: "Shopify exchange on order ${exchange.get('orderName')} (return ${exchange.get('returnName') ?: exchange.get('returnId')}) ${shortfall}.".toString(),
                            data: [orderName: exchange.get('orderName'), returnId: exchange.get('returnId'),
                                   returnName: exchange.get('returnName'), returnStatus: exchange.get('returnStatus'),
                                   returnCreatedAtMillis: exchange.get('returnCreatedAtMillis'),
                                   shopifyExchangeReturnCount: exchangeReturnCount,
                                   omsExchangeOrderCount: omsExchangeOrderCount,
                                   omsOrdersSharingExternalId: omsOrders]])
                }
            }
        }

        String auditNote = buildAuditNote(result, rows.size(), graceHours, omsSideLabel)
        if (rows) {
            try {
                appendDiffRows(diffFile, rows, auditNote,
                        [totalDifferences: rows.size(), (omsSummaryCountKey): rows.size(), missingObjectDifferenceCount: rows.size()])
            } catch (Exception e) {
                warnings.add("Exchange presence check could not write diff rows: ${e.message}".toString())
                result.lookupFailed = true
                return result
            }
            result.appendedCount = rows.size()
        }
        result.auditNote = auditNote
        return result
    }

    /** null = unreadable (warned); empty set = no manifest or no exchange orders in the OMS window. */
    private static Set<String> readManifestExternalIds(File manifestFile, List<String> warnings) {
        if (manifestFile == null || !manifestFile.isFile()) return new HashSet<String>()
        try {
            Object parsed = new JsonSlurper().parse(manifestFile, "UTF-8")
            List manifest = parsed instanceof Map ? (List) (((Map) parsed).get('manifest') ?: []) : []
            Set<String> externalIds = new HashSet<>()
            manifest.each { Object raw ->
                if (raw instanceof Map && ((Map) raw).get('externalId')) externalIds.add(((Map) raw).get('externalId').toString())
            }
            return externalIds
        } catch (Exception e) {
            warnings.add("Exchange presence check skipped: manifest unreadable (${e.message}).".toString())
            return null
        }
    }

    /** One sentence the operator always gets — even an all-matched or all-pending run shows its work. */
    private static String buildAuditNote(Map<String, Object> result, int missingRowCount, int graceHours, String omsSideLabel) {
        String note = "Exchange presence check: ${result.sweepExchangeCount} Shopify exchange(s) in window — " +
                "${result.matchedCount} matched in ${omsSideLabel}, ${result.confirmedPresentCount} confirmed by lookup, " +
                "${missingRowCount} missing from ${omsSideLabel}, ${result.pendingCount} pending (younger than ${graceHours}h)."
        if ((result.deferredLookupCount as int) > 0) note += " ${result.deferredLookupCount} deferred to a later run."
        if (result.sweepTruncated) note += " Sweep truncated — partial coverage."
        return note
    }

    private static Map invokeSweep(Closure shopifySweep, long windowStartMillis, long windowEndMillis, List<String> warnings) {
        Map sweepResult
        try {
            sweepResult = (Map) shopifySweep.call(windowStartMillis, windowEndMillis)
        } catch (Exception e) {
            warnings.add("Shopify exchange sweep failed: ${e.message}".toString())
            return null
        }
        if (sweepResult?.ok != true) {
            warnings.add("Shopify exchange sweep unavailable: ${(sweepResult?.errors ?: ['no result']).join('; ')}".toString())
            return null
        }
        return sweepResult
    }

    /**
     * The OMS pair lookup service hard-rejects calls above OMS_PAIR_LOOKUP_CHUNK_SIZE ids, so
     * candidate confirmation is split into ≤100-id chunks with merged results. Conservative: the
     * first chunk that fails or throws fails the whole lookup — no partial merge is returned.
     */
    private static Map invokeChunkedOmsPairLookup(Closure omsPairLookup, List<String> externalIds, List<String> warnings) {
        Map<String, Object> mergedOrdersByExternalId = new LinkedHashMap<>()
        for (List<String> chunk : externalIds.collate(OMS_PAIR_LOOKUP_CHUNK_SIZE)) {
            Map chunkResult
            try {
                chunkResult = (Map) omsPairLookup.call(chunk)
            } catch (Exception e) {
                warnings.add("OMS pair lookup failed: ${e.message}".toString())
                return null
            }
            if (chunkResult?.ok != true) {
                warnings.add("OMS pair lookup unavailable: ${(chunkResult?.errors ?: ['no result']).join('; ')}".toString())
                return null
            }
            mergedOrdersByExternalId.putAll((Map) (chunkResult.ordersByExternalId ?: [:]))
        }
        return [ok: true, ordersByExternalId: mergedOrdersByExternalId]
    }

    /**
     * Streaming append: rows live one-per-line inside "differences":[ ... ]; the last row line ends
     * with "]". Rewrites line by line to a temp file, converts the last row's "]" to ",", writes the
     * new rows, bumps each summaryBumps key found on the summary line, and — mirroring
     * MissingDiffVerificationSupport's PROCESSING_WARNINGS_PREFIX handling exactly — injects
     * auditNote into the artifact's "processingWarnings" array in the same single pass. If the
     * document has no "processingWarnings" line (never true for writeDiffDatasetOutput's real
     * output, but true for slimmer test fixtures) nothing is injected and no such line is created.
     * Atomic same-directory move.
     */
    protected static void appendDiffRows(File diffFile, List<Map> rows, String auditNote, Map<String, Integer> summaryBumps) {
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
                    if (!inRows) {
                        summaryBumps.each { String key, Integer bump ->
                            Matcher countMatcher = Pattern.compile(/("${key}":)(\d+)/).matcher(outLine)
                            if (countMatcher.find()) {
                                long bumped = Long.parseLong(countMatcher.group(2)) + bump
                                outLine = countMatcher.replaceFirst('$1' + bumped)
                            }
                        }
                        if (auditNote && outLine.startsWith(PROCESSING_WARNINGS_PREFIX)) {
                            outLine = PROCESSING_WARNINGS_PREFIX +
                                    JsonOutput.toJson(appendedWarnings(slurper, outLine, auditNote)) + ","
                        }
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
