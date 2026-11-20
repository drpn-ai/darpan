package darpan.dataset

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.sql.Timestamp
import java.time.Instant
import java.util.zip.GZIPOutputStream

import static org.junit.jupiter.api.Assertions.*

/** DAR-BE-318 plan 3a Task 4: KeptCopyStore, pure Groovy over temp folders. */
class KeptCopyStoreTests {
    File dir

    @BeforeEach void setUp() { dir = new File(Files.createTempDirectory("kept-copy-").toFile(), "ds") }
    @AfterEach void tearDown() { KeptCopyStore.lineWrittenHook = null; dir.parentFile.deleteDir() }

    static Timestamp ts(String iso) { Timestamp.from(Instant.parse(iso)) }
    static Map rec(String id, String updated, Map more = [:]) {
        [id: id, updatedAt: updated, createdAt: "2026-10-01T00:00:00Z", total: "10.00"] + more
    }
    static Map merge(File d, List<Map> changed, String thru = "2026-11-01T00:00:00Z") {
        KeptCopyStore.merge(d, changed.iterator(), "id", "updatedAt", ts(thru), ts("2026-11-01T00:00:05Z"))
    }
    static Map replace(File d, List<Map> full, String horizon = "2026-09-01T00:00:00Z", String thru = "2026-11-08T00:00:00Z") {
        KeptCopyStore.replace(d, full.iterator(), "id", "updatedAt", "createdAt", ts(horizon), ts(thru), ts("2026-11-08T00:00:05Z"), 2)
    }
    Map byId() {
        Map m = [:]
        KeptCopyStore.records(dir).each { m[it.id] = it }
        m
    }
    List<String> versionDirs() { dir.listFiles().findAll { it.isDirectory() && it.name.startsWith("v") }*.name.sort() }

    @Test void aFirstMergeMakesVersionOne() {
        assertNull(KeptCopyStore.manifest(dir))
        Map r = merge(dir, [rec("1", "2026-10-03T14:22:01Z"), rec("2", "2026-10-03T15:00:00Z")])
        assertEquals(2, r.addedCount)
        assertEquals(0, r.changedCount)
        assertEquals(2, r.recordsRead)
        Map m = KeptCopyStore.manifest(dir)
        assertEquals(r.version, m.version)
        assertEquals(2L, m.recordCount)
        assertEquals(ts("2026-11-01T00:00:00Z"), m.readThru)
        assertEquals(ts("2026-11-01T00:00:05Z"), m.readAt)
        assertEquals(["1", "2"], byId().keySet().sort())
    }

    @Test void aNewerStampReplacesAndAnOlderOneDoesNot() {
        merge(dir, [rec("1", "2026-10-03T14:22:01Z", [total: "10.00"]), rec("2", "2026-10-03T14:22:01Z", [total: "20.00"])])
        Map r = merge(dir, [rec("1", "2026-10-04T00:00:00Z", [total: "11.00"]), rec("2", "2026-10-02T00:00:00Z", [total: "99.00"])])
        assertEquals(1, r.changedCount)
        assertEquals("11.00", byId()["1"].record.total)
        assertEquals("20.00", byId()["2"].record.total)
    }

    @Test void anEqualStampReplacesWithTheReRead() {
        merge(dir, [rec("1", "2026-10-03T14:22:01Z", [total: "10.00"])])
        // same instant written with an offset: compared as instants, not strings
        Map r = merge(dir, [rec("1", "2026-10-03T16:22:01+02:00", [total: "12.00"])])
        assertEquals(1, r.changedCount)
        assertEquals("12.00", byId()["1"].record.total)
    }

    @Test void stampsAreComparedAsInstantsNotText() {
        // as text "...23:00:00+09:00" sorts after "...14:30:00Z", as an instant it is 9 hours earlier
        merge(dir, [rec("1", "2026-10-03T14:30:00Z", [total: "10.00"])])
        Map r = merge(dir, [rec("1", "2026-10-03T23:00:00+09:00", [total: "99.00"])])
        assertEquals(0, r.changedCount)
        assertEquals("10.00", byId()["1"].record.total)
        // the same instant with and without millis is equal, so it replaces
        Map r2 = merge(dir, [rec("1", "2026-10-03T14:30:00.000Z", [total: "11.00"])])
        assertEquals(1, r2.changedCount)
        assertEquals("11.00", byId()["1"].record.total)
        // and in a full read the same instant in another form is not a changed stamp
        Map r3 = replace(dir, [rec("1", "2026-10-03T23:30:00+09:00", [total: "11.00"])])
        assertEquals(0, r3.changedCount)
        assertEquals(0, r3.missCount)
    }

    private void assertFailsMidWriteLeavesCopyAlone(Closure refresh) {
        merge(dir, [rec("1", "2026-10-03T14:22:01Z"), rec("2", "2026-10-03T14:22:01Z")])
        String currentBefore = new File(dir, "current.json").text
        List<String> versionsBefore = versionDirs()
        int[] lines = [0]
        KeptCopyStore.lineWrittenHook = { if (++lines[0] == 2) throw new IllegalStateException("disk full") }
        assertThrows(IllegalStateException) { refresh.call() }
        KeptCopyStore.lineWrittenHook = null
        assertTrue(lines[0] >= 2, "the failure must happen after writing started")
        assertEquals(currentBefore, new File(dir, "current.json").text)
        assertEquals(versionsBefore, versionDirs())
        assertEquals(["1", "2"], byId().keySet().sort())
        assertEquals([], dir.listFiles().findAll { it.name.startsWith(".tmp-") })
    }

    @Test void aMergeThatFailsWhileWritingLeavesCurrentUnchanged() {
        assertFailsMidWriteLeavesCopyAlone { merge(dir, [rec("3", "2026-10-06T00:00:00Z")]) }
    }

    @Test void aReplaceThatFailsWhileWritingLeavesCurrentUnchanged() {
        assertFailsMidWriteLeavesCopyAlone { replace(dir, [rec("1", "2026-10-03T14:22:01Z"), rec("2", "2026-10-03T14:22:01Z"), rec("3", "2026-10-06T00:00:00Z")]) }
    }

    /** Ruling P3a-15: a merge or a replace that fails while writing closes the old version it was reading. */
    @Test void aMergeOrReplaceThatFailsWhileWritingClosesTheOldVersion() {
        // three old records, so the merge still holds the old version open when the second line fails
        merge(dir, [rec("1", "2026-10-03T14:22:01Z"), rec("2", "2026-10-03T14:22:01Z"), rec("3", "2026-10-03T14:22:01Z")])
        int before = KeptCopyStore.openReaders()
        int[] lines = [0]
        KeptCopyStore.lineWrittenHook = { if (++lines[0] == 2) throw new IllegalStateException("disk full") }
        assertThrows(IllegalStateException) { merge(dir, [rec("4", "2026-10-06T00:00:00Z")]) }
        assertEquals(2, lines[0], "the merge failed mid-write")
        assertEquals(before, KeptCopyStore.openReaders(), "the merge closed the old version")
        lines[0] = 0
        assertThrows(IllegalStateException) {
            replace(dir, [rec("1", "2026-10-03T14:22:01Z"), rec("2", "2026-10-03T14:22:01Z"), rec("4", "2026-10-06T00:00:00Z")])
        }
        assertEquals(2, lines[0], "the replace failed mid-write")
        assertEquals(before, KeptCopyStore.openReaders(), "the replace closed the old version")
        KeptCopyStore.lineWrittenHook = null
        assertEquals(["1", "2", "3"], byId().keySet().sort())
    }

    /** Ruling P3a-15: an old version that breaks mid-file fails the merge or replace and is closed, not left to GC. */
    @Test void aCorruptOldVersionFailsMergeAndReplaceAndIsClosed() {
        String version = merge(dir, [rec("1", "2026-10-03T14:22:01Z")]).version
        File gz = new File(new File(dir, version), "records.jsonl.gz")
        gz.withOutputStream { OutputStream os ->
            new GZIPOutputStream(os).withWriter("UTF-8") { Writer w ->
                w.write('{"id":"1","stamp":"2026-10-03T14:22:01Z","record":{"id":"1"}}\n{not json\n{"id":"3","stamp":null,"record":{}}\n')
            }
        }
        int before = KeptCopyStore.openReaders()
        assertThrows(RuntimeException) { replace(dir, [rec("1", "2026-10-03T14:22:01Z")]) }
        assertEquals(before, KeptCopyStore.openReaders(), "the replace closed the old version")
        assertThrows(RuntimeException) { merge(dir, [rec("4", "2026-10-06T00:00:00Z")]) }
        assertEquals(before, KeptCopyStore.openReaders(), "the merge closed the old version")
        assertEquals(version, KeptCopyStore.manifest(dir).version, "the current version is untouched")
        assertEquals([], dir.listFiles().findAll { it.name.startsWith(".tmp-") })
    }

    /** Ruling P3a-15: an empty records file fails in the gzip header read, and the file it opened is closed there. */
    @Test void anEmptyVersionFileThrowsAndLeavesNoStreamOpen() {
        String version = merge(dir, [rec("1", "2026-10-03T14:22:01Z")]).version
        new File(new File(dir, version), "records.jsonl.gz").bytes = new byte[0]
        int readersBefore = KeptCopyStore.openReaders()
        long fdsBefore = openFileDescriptors()
        50.times {
            assertThrows(EOFException) { KeptCopyStore.records(dir) }
            assertThrows(EOFException) { KeptCopyStore.withRecords(dir) { Iterator<Map> copy -> copy.hasNext() } }
        }
        long grown = openFileDescriptors() - fdsBefore
        assertTrue(grown < 10, "100 failed opens left ${grown} file descriptors open")
        assertEquals(readersBefore, KeptCopyStore.openReaders())
    }

    private static long openFileDescriptors() {
        return ((com.sun.management.UnixOperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getOpenFileDescriptorCount()
    }

    /** The manifest records what the copy covers: a full read sets it, a merge carries it, another idField drops it. */
    @Test void theManifestRecordsWhatTheCopyCovers() {
        merge(dir, [rec("1", "2026-10-03T14:22:01Z")])
        assertNull(KeptCopyStore.manifest(dir).coveredFrom, "a merge onto nothing covers nothing")
        replace(dir, [rec("1", "2026-10-03T14:22:01Z")], "2026-09-01T00:00:00Z")
        Map full = KeptCopyStore.manifest(dir)
        assertEquals(ts("2026-09-01T00:00:00Z"), full.coveredFrom)
        assertEquals(["id", "updatedAt", "createdAt"], [full.idField, full.stampField, full.windowField])
        merge(dir, [rec("2", "2026-10-05T00:00:00Z")])
        Map merged = KeptCopyStore.manifest(dir)
        assertEquals(ts("2026-09-01T00:00:00Z"), merged.coveredFrom, "a merge carries coverage forward")
        assertEquals(["id", "updatedAt", "createdAt"], [merged.idField, merged.stampField, merged.windowField])
        KeptCopyStore.merge(dir, [rec("3", "2026-10-06T00:00:00Z", [legacy: "L3"])].iterator(), "legacy", "updatedAt",
                ts("2026-11-02T00:00:00Z"), ts("2026-11-02T00:00:05Z"))
        Map rekeyed = KeptCopyStore.manifest(dir)
        assertNull(rekeyed.coveredFrom, "a merge keyed another way covers nothing")
        assertNull(rekeyed.idField)
    }

    @Test void anOrphanVersionNeverDisplacesThePrevious() {
        // previous v20261101, orphan v20261102 (sorts between), current is made now (later than both)
        String made = merge(dir, [rec("a", "2026-10-03T14:22:01Z")]).version
        File previous = new File(dir, "v20261101-000000000")
        assertTrue(new File(dir, made).renameTo(previous))
        new File(dir, "current.json").setText('{"version":"v20261101-000000000"}', "UTF-8")
        File orphan = new File(dir, "v20261102-000000000")
        assertTrue(orphan.mkdirs())
        String current = merge(dir, [rec("b", "2026-10-04T00:00:00Z")]).version
        assertTrue(current > "v20261102-000000000")
        KeptCopyStore.prune(dir)
        assertEquals(["v20261101-000000000", current].sort(), versionDirs())
        assertFalse(orphan.exists())
        assertEquals(["a", "b"], byId().keySet().sort())
    }

    @Test void mergeKeepsUntouchedRecords() {
        merge(dir, [rec("1", "2026-10-03T14:22:01Z"), rec("2", "2026-10-03T14:22:01Z")])
        Map r = merge(dir, [rec("3", "2026-10-05T00:00:00Z")])
        assertEquals(1, r.addedCount)
        assertEquals(["1", "2", "3"], byId().keySet().sort())
        assertEquals(3L, KeptCopyStore.manifest(dir).recordCount)
    }

    @Test void aMergeThatThrowsMidwayLeavesCurrentUnchanged() {
        merge(dir, [rec("1", "2026-10-03T14:22:01Z"), rec("2", "2026-10-03T14:22:01Z")])
        String currentBefore = new File(dir, "current.json").text
        List<String> versionsBefore = versionDirs()
        Iterator<Map> boom = new Iterator<Map>() {
            int n = 0
            boolean hasNext() { true }
            Map next() { if (n++ >= 3) throw new IllegalStateException("extractor died"); rec("n" + n, "2026-10-06T00:00:00Z") }
        }
        assertThrows(IllegalStateException) {
            KeptCopyStore.merge(dir, boom, "id", "updatedAt", ts("2026-11-02T00:00:00Z"), ts("2026-11-02T00:00:05Z"))
        }
        assertEquals(currentBefore, new File(dir, "current.json").text)
        assertEquals(versionsBefore, versionDirs())
        assertEquals(["1", "2"], byId().keySet().sort())
        assertEquals([], dir.listFiles().findAll { it.name.startsWith(".tmp-") })
    }

    @Test void aFullReadCountsAChangeWithAStillStampAsAMiss() {
        merge(dir, [rec("1", "2026-10-03T14:22:01Z", [total: "10.00"]), rec("2", "2026-10-03T14:22:01Z")])
        Map r = replace(dir, [rec("1", "2026-10-03T14:22:01Z", [total: "15.00"]), rec("2", "2026-10-03T14:22:01Z")])
        assertEquals(1, r.missCount)
        assertEquals(1, r.changedCount)
        assertEquals(0, r.removedCount)
        assertEquals("15.00", byId()["1"].record.total)
    }

    @Test void aFullReadCountsANeverReadOldRecordAsAMiss() {
        merge(dir, [rec("1", "2026-10-03T14:22:01Z")], "2026-11-01T00:00:00Z")
        // 9 is absent from the copy and was created well before readThru - 2 days; 10 was created after
        Map r = replace(dir, [rec("1", "2026-10-03T14:22:01Z"),
                rec("9", "2026-10-20T00:00:00Z", [createdAt: "2026-10-10T00:00:00Z"]),
                rec("10", "2026-11-02T00:00:00Z", [createdAt: "2026-11-02T00:00:00Z"])])
        assertEquals(1, r.missCount)
        assertEquals(2, r.addedCount)
        assertEquals(["1", "10", "9"], byId().keySet().sort())
    }

    @Test void aRecordMissingFromTheFullReadIsRemovedNotMissed() {
        merge(dir, [rec("1", "2026-10-03T14:22:01Z"), rec("2", "2026-10-03T14:22:01Z")])
        Map r = replace(dir, [rec("1", "2026-10-03T14:22:01Z")])
        assertEquals(1, r.removedCount)
        assertEquals(0, r.missCount)
        assertEquals(["1"], byId().keySet().sort())
    }

    @Test void aRecordOlderThanTheHorizonIsDroppedOnFullRead() {
        merge(dir, [rec("1", "2026-10-03T14:22:01Z"), rec("old", "2026-06-03T00:00:00Z", [createdAt: "2026-06-01T00:00:00Z"])])
        Map r = replace(dir, [rec("1", "2026-10-03T14:22:01Z"), rec("old", "2026-06-03T00:00:00Z", [createdAt: "2026-06-01T00:00:00Z"])])
        assertEquals(["1"], byId().keySet().sort())
        assertEquals(0, r.removedCount)
        assertEquals(0, r.missCount)
        assertEquals(1L, KeptCopyStore.manifest(dir).recordCount)
        // an old-copy record outside the horizon that the full read lacks is not "removed" either
        Map r2 = replace(dir, [], "2026-10-15T00:00:00Z")
        assertEquals(0, r2.removedCount)
    }

    @Test void pruneKeepsTwoVersions() {
        List<String> made = (1..4).collect { merge(dir, [rec("r" + it, "2026-10-03T14:22:01Z")]).version }
        KeptCopyStore.prune(dir)
        assertEquals(made[2..3], versionDirs())
        assertEquals(4, byId().size())
    }

    @Test void contentHashIgnoresKeyOrderAndTheStamp() {
        Map a = [id: "1", updatedAt: "2026-10-03T14:22:01Z", lines: [[sku: "a", qty: 1], [qty: 2, sku: "b"]], n: [x: 1, y: 2]]
        Map b = [n: [y: 2, x: 1], lines: [[qty: 1, sku: "a"], [sku: "b", qty: 2]], updatedAt: "2027-01-01T00:00:00Z", id: "1"]
        assertEquals(KeptCopyStore.contentHash(a, "updatedAt"), KeptCopyStore.contentHash(b, "updatedAt"))
        assertNotEquals(KeptCopyStore.contentHash(a, "updatedAt"), KeptCopyStore.contentHash(a + [id: "2"], "updatedAt"))
        assertEquals(64, KeptCopyStore.contentHash(a, "updatedAt").length())
    }

    @Test void aVersionIsWrittenGzippedAndReadBackIdentically() {
        Map nested = rec("1", "2026-10-03T14:22:01Z", [lines: [[sku: "a", qty: 1]], note: "café \"quoted\"", empty: null])
        Map r = merge(dir, [nested])
        File gz = new File(new File(dir, r.version), "records.jsonl.gz")
        byte[] head = gz.bytes
        assertEquals(0x1f, head[0] & 0xff)
        assertEquals(0x8b, head[1] & 0xff)
        Map back = byId()["1"].record
        assertEquals(nested, back)
        assertEquals("2026-10-03T14:22:01Z", byId()["1"].stamp)
    }

    @Test void replaceWithoutMissCountingStillCountsChangedAddedRemoved() {
        replace(dir, [rec("1", "2026-10-03T14:22:01Z"), rec("2", "2026-10-03T14:22:01Z"), rec("3", "2026-10-03T14:22:01Z")])
        // 1 silently changed (a miss when counted), 2 absent (removed), 3 newer stamp (changed), 4 new from before the old readThru
        Map r = KeptCopyStore.replace(dir, [rec("1", "2026-10-03T14:22:01Z", [total: "99.00"]), rec("3", "2026-10-05T00:00:00Z"),
                                           rec("4", "2026-10-03T00:00:00Z", [createdAt: "2026-09-10T00:00:00Z"])].iterator(),
                "id", "updatedAt", "createdAt", ts("2026-09-01T00:00:00Z"), ts("2026-11-09T00:00:00Z"), ts("2026-11-09T00:00:05Z"), 2, null, false)
        assertNull(r.missCount)
        assertEquals(2, r.changedCount)
        assertEquals(1, r.addedCount)
        assertEquals(1, r.removedCount)
        assertEquals(["1", "3", "4"], byId().keySet().sort())
        // the same read with counting on does count the misses
        replace(dir, [rec("1", "2026-10-03T14:22:01Z"), rec("2", "2026-10-03T14:22:01Z")])
        Map counted = KeptCopyStore.replace(dir, [rec("1", "2026-10-03T14:22:01Z", [total: "99.00"]), rec("2", "2026-10-03T14:22:01Z")].iterator(),
                "id", "updatedAt", "createdAt", ts("2026-09-01T00:00:00Z"), ts("2026-11-09T00:00:00Z"), ts("2026-11-09T00:00:05Z"), 2)
        assertEquals(1, counted.missCount)
    }
}
