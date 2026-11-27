package darpan.pulse

import darpan.reconciliation.source.KeepArtifacts
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

        Map read = SyncPulseReader.upstreamRecords(file, "orderName", "orderDate", "orderName")

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
        Map read = SyncPulseReader.upstreamRecords(file, "externalId", "orderDate", "orderName")
        assertEquals([[key: "5550001", orderName: "#GOR1", createdAt: Instant.parse("2026-11-21T10:15:00Z"), otherKey: "5550001"]], read.records)
        assertEquals([], SyncPulseReader.upstreamRecords(null, "externalId", "orderDate", "orderName").records, "no file, no records")
    }

    /**
     * DAR-BE-377: a Shopify order (as extract#ShopifyOrders writes it: id = legacy id, name, createdAt ISO text) is dated and
     * named by the Shopify connector's fields. Read with HotWax's field names it would carry no date at all and every order
     * would be skipped: the bug a hard-coded orderDate would hide as an empty, healthy-looking pulse.
     */
    @Test
    void shopifyOrdersAreDatedAndNamedByTheirConnectorsFields() {
        File file = json("shopify.json", [
                [id: "6123456789012", legacyResourceId: "6123456789012", name: "#GOR196940093", createdAt: "2026-11-27T08:01:02Z"],
                [id: "6123456789013", name: "#GOR196940094", createdAt: "not a date"],
        ])
        Map read = SyncPulseReader.upstreamRecords(file, "id", "createdAt", "name")
        assertEquals([[key: "6123456789012", orderName: "#GOR196940093", createdAt: Instant.parse("2026-11-27T08:01:02Z"),
                       otherKey: "6123456789012"]], read.records)
        assertEquals(["1 upstream record(s) skipped: no createdAt that reads as a date and time."], read.warnings)

        Map asHotwax = SyncPulseReader.upstreamRecords(file, "id", "orderDate", "orderName")
        assertEquals([], asHotwax.records, "HotWax's field names find no date on a Shopify order")
        assertEquals(["2 upstream record(s) skipped: no orderDate that reads as a date and time."], asHotwax.warnings)

        Map unnamed = SyncPulseReader.upstreamRecords(file, "id", "createdAt", null)
        assertEquals(null, unnamed.records[0].orderName, "no name field: the pulse shows the key")
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

    // ------------------------------------------------------------------- DAR-BE-318: what Keep set aside is not there

    /**
     * Ruling 3b2b-11. Under Spark Keep the extract holds every record and the set-aside artifact beside it names the rows Keep
     * set aside; the pulse reads them as the run does: not there.
     */
    @Test
    void upstreamRecordsSkipWhatKeepSetAside() {
        File file = json("up.json", UPSTREAM)
        Map before = SyncPulseReader.upstreamRecords(file, "orderName", "orderDate", "orderName")
        keep(file, [1L, 3L])

        Map read = SyncPulseReader.upstreamRecords(file, "orderName", "orderDate", "orderName")

        assertEquals([
                [key: "#GOR1", orderName: "#GOR1", createdAt: Instant.parse("2026-11-21T10:15:00Z"), otherKey: "#GOR1"],
                [key: "#GOR3", orderName: "#GOR3", createdAt: Instant.parse("2026-11-21T10:17:00Z"), otherKey: "#GOR3"],
        ], read.records, "#GOR2 and the undated #GOR4 were set aside: neither is an upstream record")
        assertEquals([], read.warnings, "a set-aside record is never counted as skipped")
        assertEquals(["#GOR1", "#GOR2", "#GOR3"], before.records*.key, "without the artifact every record was read")
    }

    /** With no .keep.json the read is today's, whatever else lies beside the file: even a rows file reads every record. */
    @Test
    void withNoKeepSummaryTheUpstreamReadIsTodays() {
        File file = json("up.json", UPSTREAM)
        Map today = SyncPulseReader.upstreamRecords(file, "orderName", "orderDate", "orderName")
        KeepArtifacts.writeRows(file, [[1L, "A:1"]].iterator())

        assertEquals(today, SyncPulseReader.upstreamRecords(file, "orderName", "orderDate", "orderName"), "the summary decides, not a rows file")
        assertEquals(["1 upstream record(s) skipped: no orderDate that reads as a date and time."], today.warnings)
    }

    @Test
    void targetPresenceSkipsWhatKeepSetAsideAndEachBatchTakesItsArtifactAlong() {
        List<String> keys = (1..1500).collect { "K${it}".toString() }
        int reads = 0
        Map found = SyncPulseReader.presentByBatches(keys, "otherRefNum") { List<String> batch ->
            reads++
            // every batch's file has the same name, as a read by key may write it; Keep set aside the keys ending in 7
            File f = json("target.json", batch.collect { [otherRefNum: it] })
            keep(f, batch.withIndex().findAll { String k, int i -> k.endsWith("7") }.collect { it[1] as long })
            assertFalse(new File(f.parentFile, "target.excluded.json").exists(), "the batch before took its side file along")
            new File(f.parentFile, "target.excluded.json").text = "{}"
            f
        }

        assertEquals(2, reads)
        assertEquals(1350, found.present.size(), "the 150 keys Keep set aside are not present")
        assertFalse(found.present.contains("K7"))
        assertFalse(found.present.contains("K1497"), "the second batch's Keep is its own")
        assertTrue(found.present.contains("K1500"))
        assertEquals([], tmp.toFile().listFiles().findAll { it.name.startsWith("target") }*.name,
                "the batch file, its summary, its rows and its side file are removed once read")
    }

    /** With no .keep.json a batch reads every record and only its file is removed, as before. */
    @Test
    void withNoKeepSummaryTheTargetReadIsTodays() {
        Map found = SyncPulseReader.presentByBatches(["K1", "K2"], "k") { List<String> batch ->
            File f = json("t.json", batch.collect { [k: it] })
            KeepArtifacts.writeRows(f, [[0L, "A:1"]].iterator())
            new File(tmp.toFile(), "t.excluded.json").text = "{}"
            f
        }

        assertEquals(["K1", "K2"] as Set, found.present)
        assertEquals(["t.excluded.json", "t.set-aside.rows"], tmp.toFile().listFiles()*.name.sort(),
                "only the batch file goes: the folder's removal takes the rest, as before")
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

    /** DAR-BE-386: a database endpoint's extract writes "+0000"; a pulse over it read no order at all and said all clear. */
    @Test
    void createdAtReadsADatabaseOffsetWithoutAColon() {
        assertEquals(Instant.parse("2026-11-26T14:41:46Z"), SyncPulseReader.createdAt("2026-11-26T14:41:46+0000"))
        Map read = SyncPulseReader.upstreamRecords(json("db.json", [[order_name: "8H-24739", created_at: "2026-11-27T05:30:42+0000"]]),
                "order_name", "created_at", "order_name")
        assertEquals(["8H-24739"], read.records*.key)
        assertEquals(Instant.parse("2026-11-27T05:30:42Z"), read.records[0].createdAt)
    }

    private static final List<Map> UPSTREAM = [
            [orderId: "O1", orderName: "#GOR1", orderDate: "2026-11-21T10:15:00Z"],
            [orderId: "O2", orderName: "#GOR2", orderDate: "2026-11-21T10:16:00Z"],
            [orderId: "O3", orderName: "#GOR3", orderDate: "2026-11-21T10:17:00Z"],
            [orderId: "O4", orderName: "#GOR4"],
    ].asImmutable()

    /** The artifact a Spark Keep leaves beside file: its summary, and these rows set aside. */
    private static void keep(File file, List<Long> rows) {
        KeepArtifacts.writeSummary(file, [sourceFileName: file.name, rules: [[id: "A:1", sequenceNum: 1, label: "status", source: "TENANT"]],
                                          configuredExclusions: [], builtIn: [], setAsideCount: rows.size()])
        KeepArtifacts.writeRows(file, rows.collect { [it, "A:1"] }.iterator())
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
