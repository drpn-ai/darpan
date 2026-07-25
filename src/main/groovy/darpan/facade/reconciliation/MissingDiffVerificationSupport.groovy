package darpan.facade.reconciliation

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Verification pass over a ruleset diff document: rows reported missing in a lookup-capable side
 * (connector declares a lookupServiceName) are rechecked against that side's primary datastore via
 * a point lookup; rows the lookup confirms present are bulk-export index-skew false positives and
 * are removed, with summary counts adjusted and an audit note appended to the artifact's
 * processingWarnings.
 *
 * Failure posture is strictly conservative: a failed or skipped lookup never reclassifies anything —
 * the document is left untouched and the caller gets a warning. A row is only removed when the
 * source of record itself confirmed the id exists.
 *
 * Streams the {@link darpan.reconciliation.core.ReconciliationServices#writeDiffDatasetOutput}
 * line-oriented format (one JSON row per line inside {@code "differences":[}) instead of parsing the
 * whole document — diff files reach GB scale (see the OutputDescriptorSupport OOM fix), so only one
 * row is ever held at a time. {@code MissingDiffVerificationSupportTests} locks the format coupling.
 */
class MissingDiffVerificationSupport {

    /** Above this many missing rows per side the pass skips itself: a sync that broken is not
     *  index skew, and point-checking it would hammer the source API for no signal. */
    static final int DEFAULT_MAX_LOOKUP_IDS = 1000

    private static final String DIFFERENCES_HEADER = "\"differences\":["
    private static final String SUMMARY_PREFIX = "\"summary\":"
    private static final String PROCESSING_WARNINGS_PREFIX = "\"processingWarnings\":"

    /**
     * args:
     *   diffFile     : File — diff document in writeDiffDatasetOutput format (required)
     *   file1Label   : String, file2Label : String — side labels as written into the document
     *   sideLookups  : Map side label -> Closure(List<String> ids) returning
     *                  [ok, foundIds, missingIds, errors] (the lookup service contract)
     *   maxLookupIds : optional per-side cap, default {@link #DEFAULT_MAX_LOOKUP_IDS}
     *
     * returns [performed, rewritten, checkedCount, removedCount, confirmedMissingCount,
     *          removedMissingInFile1, removedMissingInFile2, warnings, auditNote]
     */
    static Map<String, Object> verifyMissingDiffs(Map<String, Object> args) {
        File diffFile = (File) args.diffFile
        String file1Token = DiffDetailClassifier.normalizeToken(args.file1Label)
        String file2Token = DiffDetailClassifier.normalizeToken(args.file2Label)
        int maxLookupIds = (args.maxLookupIds instanceof Number) ? ((Number) args.maxLookupIds).intValue() : DEFAULT_MAX_LOOKUP_IDS
        List<String> warnings = []
        Map<String, Object> inert = [performed: false, rewritten: false, checkedCount: 0, removedCount: 0,
                confirmedMissingCount: 0, removedMissingInFile1: 0, removedMissingInFile2: 0,
                lookupFailed: false, warnings: warnings, auditNote: null] as Map<String, Object>

        Map<String, Map<String, Object>> sidesByToken = new LinkedHashMap<>()
        ((Map) (args.sideLookups ?: [:])).each { Object label, Object lookup ->
            String token = DiffDetailClassifier.normalizeToken(label)
            if (token && lookup instanceof Closure) sidesByToken.put(token, [label: label.toString(), lookup: lookup] as Map<String, Object>)
        }
        if (!sidesByToken || diffFile == null || !diffFile.isFile()) return inert

        // Pass 1 — collect candidate ids per lookup-capable side, one row in memory at a time.
        JsonSlurper slurper = new JsonSlurper()
        Map<String, Set<String>> candidateIdsByToken = [:].withDefault { new LinkedHashSet<String>() }
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
                String rowId = rowIdOf(row)
                if (rowId && sidesByToken.containsKey(missingToken)) candidateIdsByToken.get(missingToken).add(rowId)
            }
        }
        if (!sawDifferencesHeader) {
            warnings.add("Verification pass skipped: diff document has no differences section.")
            return inert
        }

        // Dispatch the point lookups. foundIds = verified present in the side that reported them
        // missing -> false positives to remove. Anything else (failure, over cap) removes nothing.
        boolean performed = false
        boolean lookupFailed = false
        int checkedCount = 0
        int confirmedMissingCount = 0
        Map<String, Set<String>> removeIdsByToken = [:]
        Map<String, Integer> removedCountByToken = [:]
        List<String> auditSentences = []
        sidesByToken.each { String token, Map<String, Object> side ->
            Set<String> candidates = candidateIdsByToken.containsKey(token) ? candidateIdsByToken.get(token) : (Set<String>) null
            if (!candidates) return
            if (candidates.size() > maxLookupIds) {
                warnings.add("Verification pass skipped for ${side.label}: ${candidates.size()} missing differences exceeds the ${maxLookupIds}-id lookup cap; a gap that large is not bulk-export index skew.".toString())
                return
            }
            List<String> ids = new ArrayList<>(candidates)
            Map lookupResult
            try {
                lookupResult = (Map) ((Closure) side.lookup).call(ids)
            } catch (Exception e) {
                lookupResult = [ok: false, errors: [e.message ?: e.class.simpleName]]
            }
            performed = true
            checkedCount += ids.size()
            if (!(lookupResult?.ok)) {
                lookupFailed = true
                List errors = lookupResult?.errors instanceof List ? (List) lookupResult.errors : []
                warnings.add("Verification lookup for ${side.label} failed; ${ids.size()} missing differences left as reported. ${errors ? errors.join("; ") : ""}".toString().trim())
                return
            }
            Set<String> found = new LinkedHashSet<String>()
            (lookupResult.foundIds instanceof List ? (List) lookupResult.foundIds : []).each { Object id ->
                String value = id?.toString()?.trim()
                if (value) found.add(value)
            }
            Set<String> removable = ids.findAll { found.contains(it) } as Set<String>
            confirmedMissingCount += (ids.size() - removable.size())
            if (removable) {
                removeIdsByToken.put(token, removable)
                removedCountByToken.put(token, removable.size())
                auditSentences.add("Verification pass: ${removable.size()} of ${ids.size()} 'missing in ${side.label}' difference(s) confirmed present in ${side.label} by point lookup (bulk-export index gap) and removed; ${ids.size() - removable.size()} confirmed missing.".toString())
            }
        }

        int removedMissingInFile1 = removedCountByToken.get(file1Token) ?: 0
        int removedMissingInFile2 = removedCountByToken.get(file2Token) ?: 0
        int removedCount = (removedCountByToken.values().sum() ?: 0) as int
        String auditNote = auditSentences ? auditSentences.join(" ") : null
        if (!removedCount) {
            return [performed: performed, rewritten: false, checkedCount: checkedCount, removedCount: 0,
                    confirmedMissingCount: confirmedMissingCount, removedMissingInFile1: 0, removedMissingInFile2: 0,
                    lookupFailed: lookupFailed, warnings: warnings, auditNote: auditNote] as Map<String, Object>
        }

        // Pass 2 — stream-rewrite to a sibling temp file, then atomically replace the original.
        File tempFile = new File(diffFile.getParentFile(), diffFile.getName() + ".verify-tmp")
        diffFile.withReader("UTF-8") { Reader reader ->
            BufferedReader lines = new BufferedReader(reader)
            tempFile.withWriter("UTF-8") { Writer writer ->
                String line
                boolean inRows = false
                boolean documentClosed = false
                boolean firstRowWritten = false
                while ((line = lines.readLine()) != null) {
                    // once "]\n}" has been emitted the only remaining input line is the original
                    // closing "}", which the row branch already wrote — skip it
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

        return [performed: true, rewritten: true, checkedCount: checkedCount, removedCount: removedCount,
                confirmedMissingCount: confirmedMissingCount, removedMissingInFile1: removedMissingInFile1,
                removedMissingInFile2: removedMissingInFile2, lookupFailed: lookupFailed,
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

    /** Ruleset diff rows carry the record id in primaryId; generic diff rows in id. */
    private static String rowIdOf(Map row) {
        String primaryId = row.get("primaryId")?.toString()?.trim()
        if (primaryId) return primaryId
        String id = row.get("id")?.toString()?.trim()
        return id ?: null
    }

    private static Map adjustSummary(JsonSlurper slurper, String summaryLine, int removedCount,
                                     int removedMissingInFile1, int removedMissingInFile2) {
        Map summary = headerFragment(slurper, summaryLine, SUMMARY_PREFIX) instanceof Map ?
                (Map) headerFragment(slurper, summaryLine, SUMMARY_PREFIX) : [:]
        decrement(summary, "totalDifferences", removedCount)
        // onlyInFile1Count = present only in file 1 = missing in file 2, and vice versa
        decrement(summary, "onlyInFile1Count", removedMissingInFile2)
        decrement(summary, "onlyInFile2Count", removedMissingInFile1)
        // removed rows are missing-object diffs by definition (ruleset summary key; absent in generic docs)
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
}
