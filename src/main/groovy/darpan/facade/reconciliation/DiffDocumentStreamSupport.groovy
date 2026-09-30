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
 */
class DiffDocumentStreamSupport {

    static final String DIFFERENCES_HEADER = "\"differences\":["
    static final String SUMMARY_PREFIX = "\"summary\":"
    static final String PROCESSING_WARNINGS_PREFIX = "\"processingWarnings\":"

    /** A row line ends with "," (more rows follow) or "]" (last row); the closing "}" line ends the region. */
    static String stripRowLine(String line) {
        String trimmed = line.trim()
        if (trimmed == "}" || trimmed == "]" || trimmed.isEmpty()) return null
        if (trimmed.endsWith(",") || trimmed.endsWith("]")) return trimmed.substring(0, trimmed.length() - 1)
        return trimmed
    }

    static Map parseRowQuietly(JsonSlurper slurper, String rowJson) {
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

    /** Visits every parseable row, read-only. */
    static void forEachRow(File diffFile, Closure visit) {
        JsonSlurper slurper = new JsonSlurper()
        diffFile.withReader("UTF-8") { Reader reader ->
            BufferedReader lines = new BufferedReader(reader)
            String line
            boolean inRows = false
            while ((line = lines.readLine()) != null) {
                if (!inRows) {
                    if (line.startsWith(DIFFERENCES_HEADER)) {
                        if (line.startsWith(DIFFERENCES_HEADER + "]")) return
                        inRows = true
                    }
                    continue
                }
                String rowJson = stripRowLine(line)
                if (rowJson == null) return
                Map row = parseRowQuietly(slurper, rowJson)
                if (row != null) visit.call(row)
                if (line.trim().endsWith("]")) return
            }
        }
    }

    /**
     * Rewrites the document row by row. {@code transformRow} returns the row to write, or null to drop
     * it; a row that does not parse is written through untouched. {@code transformSummary} runs once
     * AFTER every row, so a summary can report on the rows it precedes: rows go to a sibling file
     * first, then the document is assembled header + rows and moved over the original.
     */
    static void rewriteDocument(File diffFile, String tmpSuffix, Closure<Map> transformRow, Closure<Map> transformSummary) {
        JsonSlurper slurper = new JsonSlurper()
        File rowsFile = new File(diffFile.getParentFile(), "${diffFile.getName()}.${tmpSuffix}.rows")
        File outFile = new File(diffFile.getParentFile(), "${diffFile.getName()}.${tmpSuffix}")
        List<String> headerLines = []
        Map summary = null
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
                            } else if (line.startsWith(DIFFERENCES_HEADER)) {
                                inRows = !line.startsWith(DIFFERENCES_HEADER + "]")
                                if (!inRows) rowsDone = true
                            } else if (line.trim() != "{") {
                                headerLines << line
                            }
                            continue
                        }
                        String rowJson = stripRowLine(line)
                        if (rowJson == null) {
                            rowsDone = true
                            continue
                        }
                        boolean lastRow = line.trim().endsWith("]")
                        Map row = parseRowQuietly(slurper, rowJson)
                        String out = rowJson
                        if (row != null) {
                            Map transformed = transformRow.call(row)
                            out = transformed == null ? null : JsonOutput.toJson(transformed)
                        }
                        if (out != null) {
                            if (firstRowWritten) rows << ","
                            rows << "\n" << out
                            firstRowWritten = true
                        }
                        if (lastRow) rowsDone = true
                    }
                }
            }
            Map newSummary = transformSummary.call(summary ?: [:])
            outFile.withWriter("UTF-8") { Writer w ->
                w << "{\n"
                headerLines.each { String h ->
                    if (h == SUMMARY_PREFIX) w << SUMMARY_PREFIX + JsonOutput.toJson(newSummary) + ",\n"
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
