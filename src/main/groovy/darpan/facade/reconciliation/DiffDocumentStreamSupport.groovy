package darpan.facade.reconciliation

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Line-level reading and rewriting of a result document in the exact shape
 * ReconciliationServices.writeDiffDatasetOutput writes: header lines ("metadata", "summary",
 * "validationErrors", "processingWarnings"), then `"differences":[` with one row per line, rows ending
 * "," while more follow and "]" on the last, then a closing "}". Documents reach GB scale, so nothing
 * here parses the whole file.
 *
 * Lifted out of MissingDiffVerificationSupport (DAR-UI-044) so the conclude pass rewrites the document
 * with the same row splitting the verify pass has already proven, rather than a second reading of it.
 * The verify passes share its side-aware missing-row read (forEachMissingRowBySide) and removal
 * (removeMissingRowsBySide, R1); the exchange pass shares its header handling.
 */
class DiffDocumentStreamSupport {

    static final String DIFFERENCES_HEADER = "\"differences\":["
    static final String SUMMARY_PREFIX = "\"summary\":"
    static final String PROCESSING_WARNINGS_PREFIX = "\"processingWarnings\":"
    static final String METADATA_PREFIX = "\"metadata\":"
    static final String FILE_1 = "FILE_1"
    static final String FILE_2 = "FILE_2"

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

    static Object headerFragment(JsonSlurper slurper, String line, String prefix) {
        String fragment = line.substring(prefix.length()).trim()
        if (fragment.endsWith(",")) fragment = fragment.substring(0, fragment.length() - 1)
        try {
            return slurper.parseText(fragment)
        } catch (Exception ignored) {
            return null
        }
    }

    static void replaceFile(File source, File target) {
        Path sourcePath = source.toPath()
        Path targetPath = target.toPath()
        try {
            Files.move(sourcePath, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Ruleset diff rows carry the record id in primaryId; generic diff rows in id. */
    static String rowIdOf(Map row) {
        String primaryId = row.get("primaryId")?.toString()?.trim()
        if (primaryId) return primaryId
        String id = row.get("id")?.toString()?.trim()
        return id ?: null
    }

    /** The "processingWarnings" array (empty list if unparseable) with auditNote appended when present. */
    static List appendedWarnings(JsonSlurper slurper, String warningsLine, String auditNote) {
        Object fragment = headerFragment(slurper, warningsLine, PROCESSING_WARNINGS_PREFIX)
        List warningsList = fragment instanceof List ? new ArrayList((List) fragment) : []
        if (auditNote) warningsList.add(auditNote)
        return warningsList
    }

    /** The summary's counts, less the missing-object rows removed: removedMissingInFile1 are rows missing from side 1. */
    private static Map lowerSummaryByRemovedMissing(Map summary, int removedCount, int removedMissingInFile1, int removedMissingInFile2) {
        decrement(summary, "totalDifferences", removedCount)
        // onlyInFile1Count = present only in file 1 = missing in file 2, and vice versa
        decrement(summary, "onlyInFile1Count", removedMissingInFile2)
        decrement(summary, "onlyInFile2Count", removedMissingInFile1)
        // removed rows are missing-object diffs by definition (ruleset summary key; absent in generic docs)
        decrement(summary, "missingObjectDifferenceCount", removedCount)
        return summary
    }

    private static void decrement(Map summary, String key, int by) {
        Object value = summary.get(key)
        if (value instanceof Number && by > 0) summary.put(key, Math.max(0L, ((Number) value).longValue() - by))
    }

    /**
     * S20 HANDOFF R1: which side a missing row is missing from, FILE_1 or FILE_2. The compare's own diffType says it first
     * (MISSING_IN_FILE_n, as DiffDetailClassifier reads it). Only when the row names a side in missingIn and the two
     * sides' labels differ does a label settle it (missingIn, or a missing_in_label diffType). Null when nothing can: two
     * sides of one system share a label, and a guess would verify one side against the other's lookup.
     *
     * A row that names no side it is missing from is not a missing row and gets no side: a repeated key's rows
     * (DUPLICATE_IN_FILE_n and its _PAIRED partner) and a single-sided finding carry missingIn "" (CompareDatasetSupport
     * .buildDuplicateRows), so no verify pass may look them up or remove them (QA refute of R1: presentIn must not decide).
     */
    static String missingSide(Map row, String file1Label, String file2Label) {
        String type = DiffDetailClassifier.normalizeToken(row?.get("diffType") ?: row?.get("type"))
        if (type == "missing_in_file_1") return FILE_1
        if (type == "missing_in_file_2") return FILE_2
        if (type.startsWith("duplicate_in_")) return null
        String missingIn = DiffDetailClassifier.normalizeToken(row?.get("missingIn"))
        if (!missingIn) return null
        String file1 = DiffDetailClassifier.normalizeToken(file1Label)
        String file2 = DiffDetailClassifier.normalizeToken(file2Label)
        if (!file1 || !file2 || file1 == file2) return null
        if (missingIn == file1 || type == "missing_in_${file1}".toString()) return FILE_1
        if (missingIn == file2 || type == "missing_in_${file2}".toString()) return FILE_2
        return null
    }

    /** The side (FILE_1 / FILE_2) a label names, or null when it names neither or both (two sides sharing a label). */
    static String sideForLabel(Object label, Object file1Label, Object file2Label) {
        String token = DiffDetailClassifier.normalizeToken(label)
        String file1 = DiffDetailClassifier.normalizeToken(file1Label)
        String file2 = DiffDetailClassifier.normalizeToken(file2Label)
        if (!token || file1 == file2) return null
        return token == file1 ? FILE_1 : token == file2 ? FILE_2 : null
    }

    /** The document's side labels from its metadata header line: [file1, file2], either null when absent. */
    private static List<String> documentLabels(JsonSlurper slurper, String metadataLine) {
        Object metadata = headerFragment(slurper, metadataLine, METADATA_PREFIX)
        Map m = metadata instanceof Map ? (Map) metadata : [:]
        return [(m.get("file1Label") ?: m.get("json1Label"))?.toString(), (m.get("file2Label") ?: m.get("json2Label"))?.toString()]
    }

    /**
     * S20 HANDOFF R1: read pass of a verify pass. Visits each missing row with its side (missingSide; null when nothing
     * settles it), one row in memory at a time, and returns what the read
     * saw. Rows that carry a "missingIn" are the ones read; unparsed counts those that did not parse.
     */
    static DiffRowScan forEachMissingRowBySide(File diffFile, JsonSlurper slurper, Closure visit) {
        DiffRowScan scan = new DiffRowScan()
        List<String> labels = [null, null]
        diffFile.withReader("UTF-8") { Reader reader ->
            BufferedReader lines = new BufferedReader(reader)
            String line
            boolean inRows = false
            while ((line = lines.readLine()) != null) {
                if (!inRows) {
                    if (line.startsWith(METADATA_PREFIX)) {
                        labels = documentLabels(slurper, line)
                    } else if (line.startsWith(DIFFERENCES_HEADER)) {
                        scan.headerFound = true
                        if (line.startsWith(DIFFERENCES_HEADER + "]")) {
                            scan.closed = true
                            return
                        }
                        inRows = true
                    }
                    continue
                }
                String trimmed = line.trim()
                String rowJson = stripRowLine(line)
                if (rowJson == null) {
                    scan.closed = trimmed == "]" || trimmed == "}"
                    return
                }
                if (rowJson.contains("\"missingIn\"")) {
                    Map row = parseRowQuietly(slurper, rowJson)
                    if (row == null) {
                        scan.unparsed++
                    } else {
                        scan.rows++
                        visit.call(row, missingSide(row, labels[0], labels[1]))
                    }
                }
                if (trimmed.endsWith("]")) {
                    scan.closed = true
                    return
                }
            }
        }
        return scan
    }

    /**
     * S20 HANDOFF R1: removes the missing rows whose id is listed under their own side (removeIdsBySide: FILE_n -> ids) and
     * returns how many rows were dropped per side, [FILE_1: n, FILE_2: n]. Every row with a listed id goes, so a repeated id
     * counts each of its rows. The summary is lowered by exactly those numbers and auditNote is appended to
     * processingWarnings. A row whose side cannot be settled is never removed. A cut-off document, or one holding a row that
     * does not parse, is refused and left untouched (rewriteDocument, OC56).
     */
    static Map<String, Integer> removeMissingRowsBySide(File diffFile, String tmpSuffix, Map<String, Set<String>> removeIdsBySide,
                                                         String auditNote) {
        JsonSlurper slurper = new JsonSlurper()
        List<String> labels = [null, null]
        diffFile.withReader("UTF-8") { Reader reader ->
            BufferedReader lines = new BufferedReader(reader)
            String line
            while ((line = lines.readLine()) != null && !line.startsWith(DIFFERENCES_HEADER)) {
                if (line.startsWith(METADATA_PREFIX)) {
                    labels = documentLabels(slurper, line)
                    return
                }
            }
        }
        Map<String, Integer> dropped = [(FILE_1): 0, (FILE_2): 0]
        rewriteDocument(diffFile, tmpSuffix, { Map row ->
            if (row.get("missingIn") == null) return row
            String side = missingSide(row, labels[0], labels[1])
            String id = rowIdOf(row)
            if (side == null || id == null || !removeIdsBySide?.get(side)?.contains(id)) return row
            dropped.put(side, dropped.get(side) + 1)
            return null
        }, { Map summary ->
            lowerSummaryByRemovedMissing(summary, dropped.get(FILE_1) + dropped.get(FILE_2), dropped.get(FILE_1), dropped.get(FILE_2))
        }, { List warnings ->
            auditNote ? warnings + [auditNote] : warnings
        })
        return dropped
    }

    /**
     * What one read of the differences section saw (OC47). A reader that counts, concludes or closes findings from the
     * rows must check {@link #isComplete}: a file cut before its "differences" header, cut part-way through its rows, or
     * holding a row that does not parse yields fewer rows than the run found, and fewer rows must never read as fewer
     * findings.
     */
    static class DiffRowScan {
        /** Rows visited. */
        int rows
        /** Row lines that did not parse; they were not visited. */
        int unparsed
        /** The document has a "differences" section. */
        boolean headerFound
        /** The section ended where the writer ends it (its last row's "]", or "[]"), not at the end of the file. */
        boolean closed

        boolean isComplete() { return headerFound && closed && unparsed == 0 }

        /** Why the rows are not the whole answer, in words for an error; null when complete. */
        String problem() {
            if (!headerFound) return "The result file has no differences section (cut off or unreadable)."
            if (!closed) return "The result file ends before its differences section does (cut off after ${rows} rows).".toString()
            if (unparsed > 0) return "${unparsed} row${unparsed == 1 ? '' : 's'} of the result file could not be read.".toString()
            return null
        }

        @Override
        String toString() { return "DiffRowScan(rows=${rows}, unparsed=${unparsed}, headerFound=${headerFound}, closed=${closed})".toString() }
    }

    /** Visits every parseable row, read-only, and says what it saw. */
    static DiffRowScan forEachRow(File diffFile, Closure visit) {
        JsonSlurper slurper = new JsonSlurper()
        DiffRowScan scan = new DiffRowScan()
        diffFile.withReader("UTF-8") { Reader reader ->
            BufferedReader lines = new BufferedReader(reader)
            String line
            boolean inRows = false
            while ((line = lines.readLine()) != null) {
                if (!inRows) {
                    if (line.startsWith(DIFFERENCES_HEADER)) {
                        scan.headerFound = true
                        if (line.startsWith(DIFFERENCES_HEADER + "]")) {
                            scan.closed = true
                            return
                        }
                        inRows = true
                    }
                    continue
                }
                String trimmed = line.trim()
                String rowJson = stripRowLine(line)
                if (rowJson == null) {
                    // "]" or "}" ends the section as the writer does; a blank line mid-rows is not the writer's.
                    scan.closed = trimmed == "]" || trimmed == "}"
                    return
                }
                Map row = parseRowQuietly(slurper, rowJson)
                if (row == null) {
                    scan.unparsed++
                } else {
                    scan.rows++
                    visit.call(row)
                }
                if (trimmed.endsWith("]")) {
                    scan.closed = true
                    return
                }
            }
        }
        return scan
    }

    /**
     * Rewrites the document row by row. {@code transformRow} returns the row to write, or null to drop it.
     * {@code transformSummary} runs once AFTER every row, so a summary can report on the rows it precedes, and so does
     * {@code transformWarnings} when given (OC71): it gets the document's top-level processingWarnings and returns the list
     * to write; without it that line is written back as it was. Rows go to a sibling file first, then the document is
     * assembled header + rows and moved over the original.
     *
     * <p>OC56: a document cut before its differences section, cut part-way through its rows, or holding a row that does not
     * parse is refused (IllegalStateException, in DiffRowScan.problem() words) and left exactly as it was. Rewriting it would
     * close the section after the rows it has and every later reader would see a whole, smaller result.</p>
     */
    static void rewriteDocument(File diffFile, String tmpSuffix, Closure<Map> transformRow, Closure<Map> transformSummary,
                                Closure<List> transformWarnings = null) {
        JsonSlurper slurper = new JsonSlurper()
        File rowsFile = new File(diffFile.getParentFile(), "${diffFile.getName()}.${tmpSuffix}.rows")
        File outFile = new File(diffFile.getParentFile(), "${diffFile.getName()}.${tmpSuffix}")
        List<String> headerLines = []
        Map summary = null
        List warnings = null
        DiffRowScan scan = new DiffRowScan()
        try {
            rowsFile.withWriter("UTF-8") { Writer rows ->
                diffFile.withReader("UTF-8") { Reader reader ->
                    BufferedReader lines = new BufferedReader(reader)
                    String line
                    boolean inRows = false
                    boolean rowsDone = false
                    boolean firstRowWritten = false
                    while ((line = lines.readLine()) != null) {
                        if (rowsDone) continue
                        if (!inRows) {
                            if (line.startsWith(SUMMARY_PREFIX)) {
                                Object fragment = headerFragment(slurper, line, SUMMARY_PREFIX)
                                summary = fragment instanceof Map ? (Map) fragment : [:]
                                headerLines << SUMMARY_PREFIX
                            } else if (transformWarnings != null && line.startsWith(PROCESSING_WARNINGS_PREFIX)) {
                                Object fragment = headerFragment(slurper, line, PROCESSING_WARNINGS_PREFIX)
                                warnings = fragment instanceof List ? new ArrayList((List) fragment) : []
                                headerLines << PROCESSING_WARNINGS_PREFIX
                            } else if (line.startsWith(DIFFERENCES_HEADER)) {
                                scan.headerFound = true
                                inRows = !line.startsWith(DIFFERENCES_HEADER + "]")
                                if (!inRows) {
                                    rowsDone = true
                                    scan.closed = true
                                }
                            } else if (line.trim() != "{") {
                                headerLines << line
                            }
                            continue
                        }
                        String trimmed = line.trim()
                        String rowJson = stripRowLine(line)
                        if (rowJson == null) {
                            rowsDone = true
                            scan.closed = trimmed == "]" || trimmed == "}"
                            continue
                        }
                        boolean lastRow = trimmed.endsWith("]")
                        Map row = parseRowQuietly(slurper, rowJson)
                        if (row == null) {
                            scan.unparsed++
                        } else {
                            scan.rows++
                            Map transformed = transformRow.call(row)
                            if (transformed != null) {
                                if (firstRowWritten) rows << ","
                                rows << "\n" << JsonOutput.toJson(transformed)
                                firstRowWritten = true
                            }
                        }
                        if (lastRow) {
                            rowsDone = true
                            scan.closed = true
                        }
                    }
                }
            }
            if (!scan.complete) throw new IllegalStateException("Not rewritten: ${scan.problem()}".toString())
            Map newSummary = transformSummary.call(summary ?: [:])
            List newWarnings = transformWarnings != null ? transformWarnings.call(warnings ?: []) : null
            outFile.withWriter("UTF-8") { Writer w ->
                w << "{\n"
                headerLines.each { String h ->
                    if (h == SUMMARY_PREFIX) w << SUMMARY_PREFIX + JsonOutput.toJson(newSummary) + ",\n"
                    else if (h == PROCESSING_WARNINGS_PREFIX) w << PROCESSING_WARNINGS_PREFIX + JsonOutput.toJson(newWarnings ?: []) + ",\n"
                    else w << h << "\n"
                }
                w << DIFFERENCES_HEADER
                rowsFile.withReader("UTF-8") { Reader r -> w << r }
                w << "]\n}"
            }
            replaceFile(outFile, diffFile)
        } finally {
            rowsFile.delete()
            outFile.delete()
        }
    }
}
