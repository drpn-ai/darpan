package darpan.reconciliation.source

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.ObjectMapper
import darpan.reconciliation.conclusion.JsonRecordStream
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import groovy.transform.PackageScope

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicInteger

/**
 * DAR-BE-318 (plan 3b-2b). The set-aside artifact a Spark Keep writes beside an extract, read by name:
 * {@code <base>.keep.json} (the summary), {@code <base>.set-aside.rows} (one "row TAB rule id" per set-aside record,
 * ascending) and, where the connector asks for it, the {@code .excluded.json} side file (written by KeepStepSupport, which
 * merges into a side file the extractor already wrote). The extract keeps every
 * record; these files say which were set aside. Everything streams: the records file is never held.
 */
class KeepArtifacts {

    static final String KEEP_SUFFIX = ".keep.json"
    static final String ROWS_SUFFIX = ".set-aside.rows"

    static File keepFile(File extract) {
        return new File(extract.getParentFile(), baseName(extract) + KEEP_SUFFIX)
    }

    static File rowsFile(File extract) {
        return new File(extract.getParentFile(), baseName(extract) + ROWS_SUFFIX)
    }

    static void writeSummary(File extract, Map summary) {
        File target = keepFile(extract)
        File work = File.createTempFile("keep-", ".partial", extract.getParentFile())
        try {
            work.setText(JsonOutput.toJson(summary), "UTF-8")
            Files.move(work.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            work.delete()
        }
    }

    /** The summary, or null when there is none (a side that had no Spark Keep). */
    private static final ObjectMapper FILTERED_MAPPER = new ObjectMapper()

    /**
     * True when the extract's own read filtered it: its metadata reports read-time configuredExclusions (top level or under
     * filters, where the conclude pass reads them). A file read that way never keeps in Spark when it is compared again (a judge
     * replay, a retried run handing back the side it landed): its records are already gone, and a zero-count Keep summary would
     * turn the conclude pass's "unknown" into "not found". Only the metadata is read; the records are skipped token by token. A
     * missing or unreadable extract also answers true, so the side keeps today's read and the compare reports the problem.
     */
    static boolean filteredAtRead(File extract) {
        if (extract == null || !extract.isFile()) return true
        try {
            JsonParser parser = FILTERED_MAPPER.getFactory().createParser(extract)
            try {
                if (parser.nextToken() != JsonToken.START_OBJECT) return true
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    String name = parser.currentName()
                    parser.nextToken()
                    if (name == "metadata" && parser.currentToken() == JsonToken.START_OBJECT) {
                        Map metadata = FILTERED_MAPPER.readValue(parser, Map)
                        Object nested = metadata.get("filters") instanceof Map ? ((Map) metadata.get("filters")).get("configuredExclusions") : null
                        return [metadata.get("configuredExclusions"), nested].any { it instanceof List && !((List) it).isEmpty() }
                    }
                    parser.skipChildren()
                }
                return false
            } finally {
                parser.close()
            }
        } catch (IOException ignored) {
            return true
        }
    }

    static Map readSummary(File extract) {
        File file = keepFile(extract)
        if (!file.isFile()) return null
        return (Map) new JsonSlurper().parse(file, "UTF-8")
    }

    /** The tenant rules' counts, [] when the side had none, null when there is no summary (read as "not applied"). */
    static List configuredExclusions(File extract) {
        Map summary = readSummary(extract)
        if (summary == null) return null
        return (summary.get("configuredExclusions") as List) ?: []
    }

    /**
     * Streams the rows out; no row and no file when nothing was set aside (a stale file is removed). The readers walk
     * this file with one cursor, so rows must be strictly ascending: callers sort first (a Spark Dataset is not ordered
     * after a shuffle). A row that is not greater than the one before it throws IllegalArgumentException naming it,
     * and nothing is left behind.
     */
    static void writeRows(File extract, Iterator<List> rowAndRuleId) {
        File target = rowsFile(extract)
        File work = File.createTempFile("set-aside-", ".partial", extract.getParentFile())
        boolean any = false
        long previous = -1
        try {
            work.withWriter("UTF-8") { Writer out ->
                while (rowAndRuleId.hasNext()) {
                    List entry = rowAndRuleId.next()
                    long row = ((Number) entry[0]).longValue()
                    if (any && row <= previous) {
                        throw new IllegalArgumentException("Set-aside rows must be strictly ascending: row ${row} follows row ${previous}.")
                    }
                    previous = row
                    out.write("${entry[0]}\t${entry[1]}\n".toString())
                    any = true
                }
            }
            if (any) Files.move(work.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            else target.delete()
        } finally {
            work.delete()
        }
    }

    /** Calls visit(record) for every record the Keep did not set aside; returns the extract's other top-level fields. */
    static Map forEachKeptRecord(File extract, Closure visit) {
        RowReader aside = rowIds(extract)
        try {
            long nextAside = -1
            if (aside.hasNext()) nextAside = Long.parseLong(aside.next().split("\t", 2)[0])
            long row = 0
            return JsonRecordStream.forEachRecord(extract, "records") { Map record ->
                if (row == nextAside) {
                    nextAside = aside.hasNext() ? Long.parseLong(aside.next().split("\t", 2)[0]) : -1
                } else {
                    visit.call(record)
                }
                row++
            }
        } finally {
            aside.close()
        }
    }

    /** Calls visit(record, ruleId) for every set-aside record, in row order. */
    static void forEachSetAsideRecord(File extract, Closure visit) {
        RowReader aside = rowIds(extract)
        try {
            if (!aside.hasNext()) return
            String[] next = aside.next().split("\t", 2)
            long row = 0
            JsonRecordStream.forEachRecord(extract, "records") { Map record ->
                if (next != null && row == Long.parseLong(next[0])) {
                    visit.call(record, next[1])
                    next = aside.hasNext() ? aside.next().split("\t", 2) : null
                }
                row++
            }
        } finally {
            aside.close()
        }
    }

    /** The rows file's lines, lazily; empty when there is none. Callers close it in a finally. */
    private static RowReader rowIds(File extract) {
        File rows = rowsFile(extract)
        return new RowReader(rows.isFile() ? rows.newReader("UTF-8") : null)
    }

    /** Test seam: rows readers opened and not yet closed. */
    @PackageScope
    static final AtomicInteger OPEN_ROW_READERS = new AtomicInteger()

    /** DAR-OPS-026: zeroes the open row-reader count. Test-only. */
    static void resetForTests() { OPEN_ROW_READERS.set(0) }

    @PackageScope
    static class RowReader implements Closeable {
        private final BufferedReader reader
        private String line
        private boolean closed

        RowReader(BufferedReader reader) {
            this.reader = reader
            if (reader == null) return
            OPEN_ROW_READERS.incrementAndGet()
            try {
                this.line = advance()
            } catch (Throwable failure) {
                close()
                throw failure
            }
        }

        private String advance() {
            if (reader == null) return null
            String next = reader.readLine()
            while (next != null && next.isEmpty()) next = reader.readLine()
            return next
        }

        boolean hasNext() { return line != null }

        String next() {
            String current = line
            line = advance()
            return current
        }

        void close() {
            if (reader == null || closed) return
            closed = true
            OPEN_ROW_READERS.decrementAndGet()
            reader.close()
        }
    }

    private static String baseName(File extract) {
        String name = extract.getName()
        return name.toLowerCase().endsWith(".json") ? name.substring(0, name.length() - ".json".length()) : name
    }
}
