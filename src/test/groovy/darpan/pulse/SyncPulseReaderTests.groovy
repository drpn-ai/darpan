package darpan.pulse

import groovy.json.JsonOutput
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Path
import java.time.Instant

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-328 plan 3, Task 3: the reader's pure parts (records, keys, batches, the guard). */
class SyncPulseReaderTests {

    @TempDir
    Path tmp

    @Test
    void recordsCarryKeyCreatedAndOtherKey() {
        File file = json("up.json", [
                [orderId: "O1", orderName: "#GOR1", orderDate: "2026-11-21T10:15:00Z"],
                [orderId: "O2", orderName: "#GOR2", orderDate: 1763720100000L],
                [orderId: "O3", orderName: "#GOR3", orderDate: "2026-11-21T12:00:00-05:00"],
                [orderId: "O4", orderName: "#GOR4"],
                [orderId: "O5", orderName: "#GOR5", orderDate: "yesterday"],
                [orderId: "O6", orderDate: "2026-11-21T10:15:00Z"],
                [orderId: "O1b", orderName: "#GOR1", orderDate: "2026-11-21T11:00:00Z"],
        ])

        Map read = SyncPulseReader.upstreamRecords(file, "orderName")

        assertEquals([
                [key: "#GOR1", orderName: "#GOR1", createdAt: Instant.parse("2026-11-21T10:15:00Z"), otherKey: "#GOR1"],
                [key: "#GOR2", orderName: "#GOR2", createdAt: Instant.ofEpochMilli(1763720100000L), otherKey: "#GOR2"],
                [key: "#GOR3", orderName: "#GOR3", createdAt: Instant.parse("2026-11-21T17:00:00Z"), otherKey: "#GOR3"],
        ], read.records, "each key once, the first; createdAt from orderDate")
        assertEquals(["1 upstream record(s) skipped: no orderName.",
                      "2 upstream record(s) skipped: no orderDate that reads as a date and time."], read.warnings,
                "one aggregated warning per kind, not one per record")
    }

    @Test
    void recordsKeyedByAnotherFieldCarryThatField() {
        File file = json("up.json", [[orderId: "O1", orderName: "#GOR1", externalId: "5550001", orderDate: "2026-11-21T10:15:00Z"]])
        Map read = SyncPulseReader.upstreamRecords(file, "externalId")
        assertEquals([[key: "5550001", orderName: "#GOR1", createdAt: Instant.parse("2026-11-21T10:15:00Z"), otherKey: "5550001"]], read.records)
        assertEquals([], SyncPulseReader.upstreamRecords(null, "externalId").records, "no file, no records")
    }

    @Test
    void targetLookupBatchesByOneThousand() {
        List<String> keys = (1..2500).collect { "K${it}".toString() }
        List<List<String>> sent = []
        Map found = SyncPulseReader.presentByBatches(keys, "otherRefNum") { List<String> batch ->
            sent << new ArrayList<String>(batch)
            // the target holds every key ending in 0, and one record keyed by a key no one asked for
            json("t${sent.size()}.json", batch.findAll { it.endsWith("0") }.collect { [otherRefNum: it, tranId: "SO-${it}"] } +
                    [[otherRefNum: "NOT_ASKED"]])
        }

        assertEquals([1000, 1000, 500], sent*.size(), "2,500 keys -> three reads")
        assertEquals(keys, sent.flatten(), "every key asked once, in order")
        assertEquals(250, found.present.size())
        assertTrue(found.present.contains("K2500"))
        assertFalse(found.present.contains("NOT_ASKED"), "only asked keys are present")
        assertEquals([], found.warnings)
        assertEquals([], tmp.toFile().listFiles().findAll { it.name.startsWith("t") }, "each batch's file is removed once read")
    }

    @Test
    void invalidKeysAreSkippedNotSent() {
        String tooLong = "A" * 65
        List<List<String>> sent = []
        Map found = SyncPulseReader.presentByBatches(["#GOR1", "bad key", "x'); DROP", tooLong, "#GOR1", "", null, "GOR-2.b_3"], "k") { List<String> batch ->
            sent << batch
            json("t.json", [[k: "#GOR1"]])
        }

        assertEquals([["#GOR1", "GOR-2.b_3"]], sent, "invalid keys never reach the read; duplicates and blanks dropped")
        assertEquals(["#GOR1"] as Set, found.present)
        assertEquals(1, found.warnings.size(), "one warning for every invalid key")
        assertTrue((found.warnings[0] as String).startsWith("3 key(s) not asked for"), found.warnings[0] as String)
        assertEquals(["bad key", "x'); DROP", tooLong] as Set, found.unasked,
                "a skipped key comes back as unasked, never as absent")

        List<List<String>> none = []
        Map nothing = SyncPulseReader.presentByBatches(["bad key"], "k") { List<String> batch -> none << batch; null }
        assertEquals([], none, "no valid key, no read")
        assertEquals([] as Set, nothing.present)
        assertEquals(["bad key"] as Set, nothing.unasked)
    }

    @Test
    void extractorErrorIsReturnedNotThrown() {
        File folder = folderWithAFile()
        Map out = SyncPulseReader.guarded("upstream", folder, [records: []]) { ->
            throw new IllegalStateException("HotWax said 503")
        }

        assertEquals(false, out.ok)
        assertEquals(["The upstream read failed: HotWax said 503"], out.errors)
        assertEquals([], out.records, "the failed shape still carries its empty records")
        assertFalse(folder.exists(), "a failed read removes its folder too")
    }

    @Test
    void outputFolderRemoved() {
        File folder = folderWithAFile()
        Map out = SyncPulseReader.guarded("target", folder, [present: [] as Set]) { ->
            assertTrue(folder.exists(), "the folder lives while the read runs")
            [ok: true, present: ["K1"] as Set, errors: []]
        }

        assertEquals(true, out.ok)
        assertEquals(["K1"] as Set, out.present)
        assertFalse(folder.exists(), "the pulse folder and everything in it is gone")
        assertTrue(folder.parentFile.exists(), "only the pulse's own folder is removed")
    }

    @Test
    void createdAtReadsIsoTextAndEpochMillis() {
        assertEquals(Instant.parse("2026-11-21T10:15:00Z"), SyncPulseReader.createdAt("2026-11-21T10:15:00Z"))
        assertEquals(Instant.ofEpochMilli(1763720100000L), SyncPulseReader.createdAt("1763720100000"))
        assertEquals(Instant.ofEpochMilli(1763720100000L), SyncPulseReader.createdAt(1763720100000L))
        assertEquals(Instant.ofEpochMilli(1763720100000L), SyncPulseReader.createdAt(BigInteger.valueOf(1763720100000L)))
        assertEquals(null, SyncPulseReader.createdAt("1763720100"), "a 10-digit string is no epoch-ms date (it would read as 1970)")
        assertEquals(null, SyncPulseReader.createdAt(1763720100), "a small Integer is no date")
        assertEquals(null, SyncPulseReader.createdAt(1.5d), "a decimal is no date")
        assertEquals(null, SyncPulseReader.createdAt("2026-11-21 10:15"))
        assertEquals(null, SyncPulseReader.createdAt(null))
    }

    private File json(String name, List<Map> records) {
        File f = tmp.resolve(name).toFile()
        f.setText(JsonOutput.toJson([metadata: [:], records: records]), "UTF-8")
        return f
    }

    private File folderWithAFile() {
        File folder = tmp.resolve("sync-pulse/KREWE/P1/stamp").toFile()
        folder.mkdirs()
        new File(folder, "oms.json").text = "{}"
        new File(folder, "oms.json.excluded.json").text = "{}"
        return folder
    }
}
