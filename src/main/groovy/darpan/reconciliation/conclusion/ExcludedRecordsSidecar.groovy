package darpan.reconciliation.conclusion

import darpan.facade.common.DataManagerSupport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * DAR-UI-044. The rows a source filter dropped, kept beside the extract so the conclude pass can prove
 * what the run itself screened out. Run C's "Backordered in NetSuite" is exactly such a row: the
 * NetSuite line the run's own "quantityShipped EXCLUDE_IN 0" rule removed.
 *
 * Capped like the OMS exchange manifest. A cap is reported, never silent, because the conclude pass
 * must not read a truncated sidecar as "this record was not excluded".
 *
 * Write and flush (DAR-BE-095, Aditi 2026-10-22: production runs on limited resources): a collector holds
 * COUNTS only. Each dropped record is appended to a spill file as one JSON line as it is dropped, a page's
 * spill is streamed into the run's, and the sidecar is streamed out of the run's spill. Memory stays one
 * record deep however many a filter drops (it used to hold up to 50,000 records, then their whole JSON text).
 */
class ExcludedRecordsSidecar {

    static final int MAX_ENTRIES = 50000

    static String fileNameFor(String extractFileName) {
        String name = extractFileName ?: "extract"
        return name.toLowerCase().endsWith(".json")
                ? name.substring(0, name.length() - ".json".length()) + ".excluded.json"
                : name + ".excluded.json"
    }

    /** A collector: counts, plus a spill file opened on the first dropped record. */
    static Map newCollector() {
        return [total: 0, kept: 0, truncated: false, spill: null, writer: null]
    }

    /** The record is copied, never mutated: callers still hold it. */
    static void collect(Map collector, Map record, Map rule) {
        collector.total = ((collector.total ?: 0) as int) + 1
        if (((collector.kept ?: 0) as int) >= MAX_ENTRIES) {
            collector.truncated = true
            return
        }
        Map copy = new LinkedHashMap(record ?: [:])
        copy.put("_excludedBy", "${rule?.get('sequenceNum')}:${rule?.get('fieldExpression')}".toString())
        appendLine(collector, JsonOutput.toJson(copy))
    }

    /** Folds a page's collector into a run's, keeping the cap and the true total; the page's spill is streamed, then deleted. */
    static void merge(Map into, Map from) {
        if (!from) return
        closeWriter(from)
        File spill = (File) from.spill
        if (spill?.exists()) {
            spill.withReader("UTF-8") { Reader reader ->
                String line
                while ((line = reader.readLine()) != null) {
                    if (((into.kept ?: 0) as int) >= MAX_ENTRIES) {
                        into.truncated = true
                        break
                    }
                    if (line) appendLine(into, line)
                }
            }
            spill.delete()
        }
        from.spill = null
        into.total = ((into.total ?: 0) as int) + ((from.total ?: 0) as int)
        into.truncated = into.truncated == true || from.truncated == true || ((into.kept ?: 0) as int) < ((into.total ?: 0) as int)
        flush(into)
    }

    /** Pushes what was written to disk; a page calls this once it is done. */
    static void flush(Map collector) {
        ((Writer) collector?.writer)?.flush()
    }

    /** The collected records, read back from the spill. For tests and small checks only: it loads them all. */
    static List<Map> records(Map collector) {
        flush(collector)
        File spill = (File) collector?.spill
        if (spill == null || !spill.exists()) return []
        JsonSlurper slurper = new JsonSlurper()
        return spill.readLines("UTF-8").findAll { it }.collect { (Map) slurper.parseText(it) }
    }

    /** Streams the sidecar document out of the spill, then deletes the spill. */
    static void write(File target, Map collector, String sourceFileName) {
        target.withWriter("UTF-8") { Writer out -> writeTo(out, collector, sourceFileName) }
        discard(collector)
    }

    /** Closes and deletes a collector's spill: an extract that fails before writing its sidecar calls this. */
    static void discard(Map collector) {
        if (!collector) return
        closeWriter(collector)
        ((File) collector.spill)?.delete()
        collector.spill = null
    }

    /**
     * Writes the collector beside an extract the way the OMS exchange manifest is written: a temp file
     * in the output directory, then moved into the data-manager location. Nothing is written when no
     * row was excluded. Advisory by design — callers turn a failure into a warning, never a failed
     * extract, because the extract itself is complete without it.
     *
     * @return the sidecar's location, or null when there was nothing to write
     */
    static String writeBeside(def ec, Map collector, String outputBaseLocation, String outputFileName, File outputDirectory) {
        if (!collector || ((collector.total ?: 0) as int) == 0) {
            discard(collector)
            return null
        }
        File work = null
        try {
            work = outputDirectory != null
                    ? File.createTempFile("excluded-", ".partial", outputDirectory)
                    : File.createTempFile("excluded-", ".partial")
            write(work, collector, outputFileName)
            String location = DataManagerSupport.childLocation(outputBaseLocation, fileNameFor(outputFileName))
            DataManagerSupport.moveIntoLocation(ec, work, location as String)
            return location
        } finally {
            work?.delete()
            discard(collector)
        }
    }

    /** present:false when there is no sidecar or it cannot be read — never an empty "nothing excluded". */
    static Map read(File extractFile) {
        List<Map> records = []
        Map head = forEachRecord(extractFile) { Map record -> records.add(record) }
        return head + [records: records]
    }

    /**
     * Streams the sidecar's records to visit, one at a time; returns [present, truncated]. present:false when there is no
     * sidecar or it cannot be read, never an empty "nothing excluded".
     */
    static Map forEachRecord(File extractFile, Closure visit) {
        File sidecar = new File(extractFile.getParentFile(), fileNameFor(extractFile.getName()))
        if (!sidecar.exists()) return [present: false, truncated: false]
        try {
            Map head = JsonRecordStream.forEachRecord(sidecar, "records", visit)
            return [present: true, truncated: head.truncated == true]
        } catch (Exception ignored) {
            return [present: false, truncated: false]
        }
    }

    private static void writeTo(Writer out, Map collector, String sourceFileName) {
        flush(collector)
        out.write("{\"sourceFileName\":")
        out.write(JsonOutput.toJson(sourceFileName))
        out.write(",\"truncated\":${collector?.truncated == true},\"total\":${(collector?.total ?: 0) as int},\"records\":[")
        File spill = (File) collector?.spill
        if (spill?.exists()) {
            boolean first = true
            spill.withReader("UTF-8") { Reader reader ->
                String line
                while ((line = reader.readLine()) != null) {
                    if (!line) continue
                    if (!first) out.write(",")
                    out.write(line)
                    first = false
                }
            }
        }
        out.write("]}")
    }

    private static void appendLine(Map collector, String line) {
        Writer writer = (Writer) collector.writer
        if (writer == null) {
            // Owner-only (0600 on POSIX): the spill holds customer records. Deleted when written or discarded; every
            // extract path discards its collector in a finally, deleteOnExit is the last resort.
            File spill = Files.createTempFile("excluded-", ".jsonl").toFile()
            spill.deleteOnExit()
            collector.spill = spill
            writer = Files.newBufferedWriter(spill.toPath(), StandardCharsets.UTF_8)
            collector.writer = writer
        }
        writer.write(line)
        writer.write("\n")
        collector.kept = ((collector.kept ?: 0) as int) + 1
    }

    private static void closeWriter(Map collector) {
        Writer writer = (Writer) collector?.writer
        if (writer != null) {
            writer.close()
            collector.writer = null
        }
    }
}
