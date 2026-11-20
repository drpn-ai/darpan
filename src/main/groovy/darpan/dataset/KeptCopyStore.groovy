package darpan.dataset

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * DAR-BE-318 plan 3a: the kept copy of a dataset. Versions are gzipped JSONL under
 * &lt;datasetDir&gt;/v&lt;yyyyMMdd-HHmmssSSS&gt;/ (records.jsonl.gz + manifest.json); &lt;datasetDir&gt;/current.json
 * names the current one. The file format is private to this class: callers use manifest, records, merge,
 * replace and prune only. Pure Groovy, no Moqui.
 */
class KeptCopyStore {
    static final String RECORDS_FILE = "records.jsonl.gz"
    static final String MANIFEST_FILE = "manifest.json"
    static final String CURRENT_FILE = "current.json"
    /** The field a composite idField's records carry their derived key in ({@link DatasetRefreshSupport#copyId}). */
    static final String COPY_ID = "_copyId"

    /** The record's copy key: its own idField value, or its derived _copyId when idField names several fields. */
    private static String keyOf(Map rec, String idField) {
        return String.valueOf(rec.get(idField.contains(",") ? COPY_ID : idField))
    }

    /**
     * [version, readThru, readAt, recordCount, coveredFrom, idField, stampField, windowField] of the current version, or
     * null when there is none. The last four say what the copy covers: every record created (windowField) at or after
     * coveredFrom, keyed by idField and stamped by stampField. A version written before they were recorded has them null,
     * which reads as covering nothing.
     */
    static Map manifest(File datasetDir) {
        String version = currentVersion(datasetDir)
        if (version == null) return null
        File file = new File(new File(datasetDir, version), MANIFEST_FILE)
        if (!file.isFile()) return null
        Map m = (Map) new JsonSlurper().parse(file, "UTF-8")
        return [version    : version, readThru: toTimestamp(m.get("readThru")), readAt: toTimestamp(m.get("readAt")),
                recordCount: ((Number) (m.get("recordCount") ?: 0)).longValue(), coveredFrom: toTimestamp(m.get("coveredFrom")),
                idField    : m.get("idField") ?: null, stampField: m.get("stampField") ?: null, windowField: m.get("windowField") ?: null]
    }

    /** Streams the current version; each element is [id, stamp, readAt, record: Map]. Empty when there is none. */
    static Iterator<Map> records(File datasetDir) {
        String version = currentVersion(datasetDir)
        if (version == null) return Collections.<Map> emptyList().iterator()
        return new RecordReader(new File(new File(datasetDir, version), RECORDS_FILE))
    }

    /**
     * Streams the current version to {@code body} as an iterator of [id, stamp, readAt, record: Map] (empty when there is
     * none) and closes the file when body returns or throws, however far it read (ruling P3a-14). Returns body's result.
     */
    static <T> T withRecords(File datasetDir, Closure<T> body) {
        String version = currentVersion(datasetDir)
        RecordReader reader = version == null ? null : new RecordReader(new File(new File(datasetDir, version), RECORDS_FILE))
        try {
            return body.call(reader != null ? reader : Collections.<Map> emptyList().iterator())
        } finally {
            reader?.close()
        }
    }

    /** Version files open for reading in this JVM right now: a reader that is never closed shows here. */
    static int openReaders() {
        return OPEN_READERS.get()
    }

    private static final java.util.concurrent.atomic.AtomicInteger OPEN_READERS = new java.util.concurrent.atomic.AtomicInteger()

    /**
     * Merges the changed records into the copy: an equal or newer stamp replaces, a new id is appended. The new version
     * covers what the one it builds on covered (coveredFrom and windowField carry forward), but only while it is keyed and
     * stamped the same way: a merge under another idField or stampField, or onto a copy that records no coverage, writes a
     * version that covers nothing until a full read replaces it.
     */
    static Map merge(File datasetDir, Iterator<Map> changed, String idField, String stampField,
                     Timestamp readThru, Timestamp readAt, Closure swapGuard = null) {
        Map oldManifest = manifest(datasetDir)
        Map coverage = oldManifest?.coveredFrom != null && oldManifest.idField == idField && oldManifest.stampField == stampField ?
                [coveredFrom: oldManifest.coveredFrom, idField: idField, stampField: stampField, windowField: oldManifest.windowField] :
                [:]
        Map<String, Map> changes = new LinkedHashMap<String, Map>()
        long recordsRead = 0
        while (changed.hasNext()) {
            Map rec = changed.next()
            recordsRead++
            String id = keyOf(rec, idField)
            Map prior = changes.get(id)
            if (prior != null && compareStamps(prior.get("stamp"), rec.get(stampField)) > 0) continue
            changes.put(id, entry(id, rec.get(stampField), readAt, rec))
        }
        long changedCount = 0
        long addedCount = 0
        Map out = writeVersion(datasetDir, readThru, readAt, coverage, swapGuard) { Writer w ->
            long count = 0
            // ruling P3a-15: the old version is closed however the write ends
            withRecords(datasetDir) { Iterator<Map> old ->
                while (old.hasNext()) {
                    Map e = old.next()
                    String id = String.valueOf(e.get("id"))
                    Map repl = changes.get(id)
                    if (repl != null) {
                        changes.remove(id)
                        if (compareStamps(repl.get("stamp"), e.get("stamp")) >= 0) {
                            writeLine(w, repl)
                            changedCount++
                        } else {
                            writeLine(w, e)
                        }
                    } else {
                        writeLine(w, e)
                    }
                    count++
                }
            }
            for (Map e : changes.values()) {
                writeLine(w, e)
                addedCount++
                count++
            }
            return count
        }
        return [version: out.version, changedCount: changedCount, addedCount: addedCount, recordsRead: recordsRead]
    }

    /**
     * Replaces the copy with a full read. Records created before horizonStart are dropped. Counts, per ruling 3a-8:
     * a miss is the same stamp with a different content hash, or a record absent from the old copy that was created
     * before the old readThru minus marginDays (never read); a record in the old copy that the full read lacks, inside
     * the horizon, is removed, not a miss. The new version covers every record created (createdField) at or after
     * horizonStart, keyed by idField and stamped by stampField; a null horizonStart records no coverage. With
     * countMisses false (a rolling dataset, which re-reads its whole window daily) nothing is a miss: missCount is null,
     * and changedCount, addedCount and removedCount are counted as before. An idField naming several fields (comma) keys
     * each record by its _copyId.
     */
    static Map replace(File datasetDir, Iterator<Map> full, String idField, String stampField, String createdField,
                       Timestamp horizonStart, Timestamp readThru, Timestamp readAt, int marginDays, Closure swapGuard = null,
                       boolean countMisses = true) {
        Map oldManifest = manifest(datasetDir)
        Instant neverReadBefore = oldManifest?.readThru == null ? null :
                ((Timestamp) oldManifest.readThru).toInstant().minusSeconds(marginDays * 86400L)
        Instant horizon = horizonStart?.toInstant()
        // id -> [stamp, hash, created]: the only thing held in heap
        Map<String, List> index = new HashMap<String, List>()
        // ruling P3a-15: the old version is closed however the read ends
        withRecords(datasetDir) { Iterator<Map> old ->
            while (old.hasNext()) {
                Map e = old.next()
                Map rec = (Map) e.get("record")
                index.put(String.valueOf(e.get("id")), [e.get("stamp"), contentHash(rec, stampField), rec?.get(createdField)])
            }
        }
        long recordsRead = 0, changedCount = 0, addedCount = 0, missCount = 0
        Map coverage = horizonStart == null ? [:] :
                [coveredFrom: horizonStart, idField: idField, stampField: stampField, windowField: createdField]
        Map out = writeVersion(datasetDir, readThru, readAt, coverage, swapGuard) { Writer w ->
            long count = 0
            Set<String> written = new HashSet<String>()
            while (full.hasNext()) {
                Map rec = full.next()
                recordsRead++
                String id = keyOf(rec, idField)
                List prior = index.remove(id)
                Instant created = parseInstant(rec.get(createdField))
                boolean droppedByHorizon = horizon != null && created != null && created.isBefore(horizon)
                if (prior != null) {
                    boolean sameStamp = compareStamps(prior[0], rec.get(stampField)) == 0
                    boolean sameHash = prior[1] == contentHash(rec, stampField)
                    if (!sameStamp || !sameHash) changedCount++
                    if (countMisses && sameStamp && !sameHash) missCount++
                } else if (!droppedByHorizon) {
                    addedCount++
                    if (countMisses && neverReadBefore != null && created != null && created.isBefore(neverReadBefore)) missCount++
                }
                if (droppedByHorizon || !written.add(id)) continue
                writeLine(w, entry(id, rec.get(stampField), readAt, rec))
                count++
            }
            return count
        }
        long removedCount = index.values().count { List p ->
            Instant created = parseInstant(p[2])
            horizon == null || created == null || !created.isBefore(horizon)
        }
        return [version: out.version, recordsRead: recordsRead, changedCount: changedCount, addedCount: addedCount,
                removedCount: removedCount, missCount: countMisses ? missCount : null]
    }

    /** SHA-256 hex of the record as canonical JSON (keys sorted at every depth) without its stamp field. */
    static String contentHash(Map record, String stampField) {
        Map copy = new LinkedHashMap(record ?: [:])
        copy.remove(stampField)
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(DatasetSupport.canonicalJson(copy).getBytes(StandardCharsets.UTF_8))
        return digest.collect { String.format("%02x", it & 0xff) }.join()
    }

    /** Keeps the version current.json names and the one it replaced; deletes everything else (orphans, stale temp folders). */
    static void prune(File datasetDir) {
        File[] dirs = datasetDir.listFiles()
        if (dirs == null) return
        String current = currentVersion(datasetDir)
        String previous = previousVersion(datasetDir)
        dirs.findAll { it.isDirectory() }.each { File d ->
            boolean isVersion = d.name ==~ /v\d{8}-\d{9}.*/
            boolean isTemp = d.name.startsWith(".tmp-")
            if ((isVersion && d.name != current && d.name != previous) || isTemp) deleteTree(d)
        }
    }

    /** Test seam: called after each line is written to a new version; may throw to simulate a mid-write failure. */
    static Closure lineWrittenHook = null

    // ---- internals ----

    /**
     * swapGuard, when given, is called with the swap (a closure that names the new version current and returns true) and
     * returns its result, or false without calling it to refuse: a caller that lost its lock refuses, and the whole new
     * version is deleted. Null swaps unconditionally.
     */
    private static Map writeVersion(File datasetDir, Timestamp readThru, Timestamp readAt, Map coverage, Closure swapGuard,
                                    Closure<Long> body) {
        datasetDir.mkdirs()
        File tmp = new File(datasetDir, ".tmp-" + UUID.randomUUID().toString())
        tmp.mkdirs()
        try {
            long count
            new File(tmp, RECORDS_FILE).withOutputStream { OutputStream os ->
                Writer w = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(os, 65536), StandardCharsets.UTF_8))
                boolean closed = false
                try {
                    count = body.call(w)
                    w.close()
                    closed = true
                } finally {
                    // a failed write still frees its Deflater; the body's own error is the one that propagates
                    if (!closed) try { w.close() } catch (Throwable ignored) { }
                }
            }
            String version = freshVersionName(datasetDir)
            new File(tmp, MANIFEST_FILE).setText(JsonOutput.toJson([version: version, readThru: iso(readThru),
                    readAt: iso(readAt), recordCount: count, coveredFrom: iso((Timestamp) coverage?.coveredFrom),
                    idField: coverage?.idField, stampField: coverage?.stampField, windowField: coverage?.windowField]), "UTF-8")
            File target = new File(datasetDir, version)
            Closure<Boolean> swap = { ->
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                try {
                    writeCurrent(datasetDir, version)
                } catch (Throwable t) {
                    deleteTree(target)
                    throw t
                }
                return true
            }
            if ((swapGuard == null ? swap.call() : swapGuard.call(swap)) != true) {
                throw new IllegalStateException("The new version was not made current: the swap was refused")
            }
            return [version: version]
        } finally {
            if (tmp.exists()) deleteTree(tmp)
        }
    }

    /** current.json is replaced last, atomically where the filesystem allows. */
    private static void writeCurrent(File datasetDir, String version) {
        File tmpCurrent = new File(datasetDir, ".tmp-current-" + UUID.randomUUID().toString())
        Map prior = readCurrent(datasetDir)
        tmpCurrent.setText(JsonOutput.toJson([version: version, previousVersion: prior?.get("version")]), "UTF-8")
        Path src = tmpCurrent.toPath()
        Path dst = new File(datasetDir, CURRENT_FILE).toPath()
        try {
            try {
                Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE)
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(src)
        }
    }

    private static String freshVersionName(File datasetDir) {
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS")
        java.time.LocalDateTime t = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
        String name = "v" + fmt.format(t)
        while (new File(datasetDir, name).exists()) {
            t = t.plusNanos(1_000_000L)
            name = "v" + fmt.format(t)
        }
        return name
    }

    private static Map readCurrent(File datasetDir) {
        File file = new File(datasetDir, CURRENT_FILE)
        return file.isFile() ? (Map) new JsonSlurper().parse(file, "UTF-8") : null
    }

    private static String currentVersion(File datasetDir) {
        String v = readCurrent(datasetDir)?.get("version")
        return v && new File(datasetDir, v).isDirectory() ? v : null
    }

    private static String previousVersion(File datasetDir) {
        return readCurrent(datasetDir)?.get("previousVersion")
    }

    private static Map entry(String id, Object stamp, Timestamp readAt, Map record) {
        return [id: id, stamp: stamp, readAt: iso(readAt), record: record]
    }

    private static void writeLine(Writer w, Map entry) {
        w.write(JsonOutput.toJson(entry))
        w.write("\n")
        if (lineWrittenHook != null) lineWrittenHook.call()
    }

    /** One version's records, a line at a time; closes itself at the end, and on close(). */
    private static class RecordReader implements Iterator<Map>, Closeable {
        private final BufferedReader reader
        private final JsonSlurper slurper = new JsonSlurper()
        private String next
        private boolean closed

        RecordReader(File file) {
            FileInputStream input = new FileInputStream(file)
            try {
                reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(input, 65536), StandardCharsets.UTF_8))
            } catch (Throwable t) {
                // an empty or corrupt file fails in the gzip header read: the file is closed here, nothing else holds it
                input.close()
                throw t
            }
            OPEN_READERS.incrementAndGet()
            try {
                next = reader.readLine()
            } catch (Throwable t) {
                close()
                throw t
            }
            if (next == null) close()
        }

        boolean hasNext() {
            return next != null
        }

        Map next() {
            if (next == null) throw new NoSuchElementException()
            Map m = (Map) slurper.parseText(next)
            next = reader.readLine()
            if (next == null) close()
            return m
        }

        void close() {
            if (closed) return
            closed = true
            next = null
            OPEN_READERS.decrementAndGet()
            reader.close()
        }
    }

    private static String iso(Timestamp ts) { return ts == null ? null : ts.toInstant().toString() }

    private static Timestamp toTimestamp(Object text) {
        Instant i = parseInstant(text)
        return i == null ? null : Timestamp.from(i)
    }

    /**
     * A record's date or stamp as an instant: ISO-8601 text (an instant or an offset date-time), or integral epoch
     * milliseconds, as the HotWax extracts write entryDate and orderDate. Null when absent or unreadable. KeptCopySupport
     * reads a served side's window values with this same parser.
     */
    static Instant parseInstant(Object value) {
        if (value == null) return null
        if (value instanceof Long || value instanceof Integer || value instanceof BigInteger) {
            return Instant.ofEpochMilli(((Number) value).longValue())
        }
        String s = value.toString().trim()
        if (!s) return null
        try { return Instant.parse(s) } catch (Exception ignored) { }
        try { return OffsetDateTime.parse(s).toInstant() } catch (Exception ignored) { }
        return null
    }

    /** Compares two stamps as instants; an absent or unparseable stamp sorts oldest. */
    private static int compareStamps(Object a, Object b) {
        Instant x = parseInstant(a)
        Instant y = parseInstant(b)
        if (x == null && y == null) return 0
        if (x == null) return -1
        if (y == null) return 1
        return x.compareTo(y)
    }

    private static void deleteTree(File f) {
        if (f.isDirectory()) f.listFiles()?.each { deleteTree(it) }
        f.delete()
    }
}
