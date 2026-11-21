package darpan.reconciliation.source

import groovy.json.JsonOutput
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-318 plan 3b-2b Task 2. The set-aside artifact, on a made-up system (ACME). */
class KeepArtifactsTests {

    @TempDir
    File dir

    private File extract(List records, String name = "acme-orders.json") {
        File file = new File(dir, name)
        file.text = JsonOutput.toJson([records: records, metadata: [system: "ACME"]])
        return file
    }

    private static Map summary() {
        return [sourceFileName: "acme-orders.json",
                rules: [[id: "A:1", sequenceNum: 1, label: "salesChannelEnumId", source: "TENANT"]],
                configuredExclusions: [[sequenceNum: 1, fieldExpression: "salesChannelEnumId", operator: "EXCLUDE_IN", values: ["POS"],
                                        excludedCount: 3, fieldAbsentCount: 0]],
                builtIn: [], keptCount: 3, setAsideCount: 3]
    }

    @Test
    void theSummaryRoundTripsAndConfiguredExclusionsReadsIt() {
        File file = extract([[n: 0]])
        assertNull(KeepArtifacts.readSummary(file))
        assertNull(KeepArtifacts.configuredExclusions(file))

        KeepArtifacts.writeSummary(file, summary())

        assertEquals("acme-orders.keep.json", KeepArtifacts.keepFile(file).name)
        assertEquals(summary(), KeepArtifacts.readSummary(file))
        assertEquals(summary().configuredExclusions, KeepArtifacts.configuredExclusions(file))
        KeepArtifacts.writeSummary(file, summary() + [configuredExclusions: []])
        assertEquals([], KeepArtifacts.configuredExclusions(file))
    }

    @Test
    void forEachKeptRecordSkipsExactlyTheListedRows() {
        // A non-object entry in records takes no ordinal: rows count objects only.
        File file = new File(dir, "acme-orders.json")
        file.text = '{"records":[{"n":0},7,{"n":1},{"n":2},"x",{"n":3},{"n":4},{"n":5}],"metadata":{"k":1}}'
        KeepArtifacts.writeRows(file, [[0, "A:1"], [3, "A:1"], [4, "A:2"]].iterator())

        List seen = []
        Map head = KeepArtifacts.forEachKeptRecord(file) { Map record -> seen.add(record.n) }

        assertEquals([1, 2, 5], seen)
        assertEquals([k: 1], head.metadata)
        assertEquals("0\tA:1\n3\tA:1\n4\tA:2\n", KeepArtifacts.rowsFile(file).text)
    }

    @Test
    void forEachKeptRecordReadsEverythingWhenNothingWasSetAside() {
        File file = extract([[n: 0], [n: 1]])
        List seen = []
        KeepArtifacts.forEachKeptRecord(file) { Map record -> seen.add(record.n) }
        assertEquals([0, 1], seen)
    }

    @Test
    void forEachSetAsideRecordNamesEachRecordsRule() {
        File file = extract((0..5).collect { [n: it] })
        KeepArtifacts.writeRows(file, [[1, "A:1"], [2, "B:9"], [5, "A:1"]].iterator())

        List seen = []
        KeepArtifacts.forEachSetAsideRecord(file) { Map record, String ruleId -> seen.add([record.n, ruleId]) }

        assertEquals([[1, "A:1"], [2, "B:9"], [5, "A:1"]], seen)
    }

    @Test
    void writingNoRowsRemovesAStaleRowsFile() {
        File file = extract([[n: 0]])
        KeepArtifacts.writeRows(file, [[0, "A:1"]].iterator())
        assertTrue(KeepArtifacts.rowsFile(file).exists())
        KeepArtifacts.writeRows(file, [].iterator())
        assertFalse(KeepArtifacts.rowsFile(file).exists())
    }

    @Test
    void rowsMustBeStrictlyAscendingAndNothingIsLeftBehind() {
        File file = extract((0..4).collect { [n: it] })
        [[[3, "A:1"], [1, "A:1"]], [[2, "A:1"], [2, "A:1"]]].each { List rows ->
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException) { KeepArtifacts.writeRows(file, rows.iterator()) }
            assertTrue(thrown.message.contains("row ${rows[1][0]}"), thrown.message)
            assertFalse(KeepArtifacts.rowsFile(file).exists())
            assertEquals(["acme-orders.json"], dir.list().toList())
        }
    }

    @Test
    void theRowsReaderIsClosedWhenVisitThrowsAndWhenRowsRunPastTheRecords() {
        File file = extract((0..2).collect { [n: it] })
        int before = KeepArtifacts.OPEN_ROW_READERS.get()
        KeepArtifacts.writeRows(file, [[0, "A:1"], [1, "A:1"], [9, "A:1"]].iterator())
        assertThrows(IllegalStateException) { KeepArtifacts.forEachKeptRecord(file) { Map r -> throw new IllegalStateException("boom") } }
        assertThrows(IllegalStateException) { KeepArtifacts.forEachSetAsideRecord(file) { Map r, String id -> throw new IllegalStateException("boom") } }
        KeepArtifacts.forEachKeptRecord(file) { Map r -> }
        KeepArtifacts.forEachSetAsideRecord(file) { Map r, String id -> }

        assertEquals(before, KeepArtifacts.OPEN_ROW_READERS.get(), "a rows reader was left open")
    }

    @Test
    void aFailingFirstReadClosesTheReader() {
        boolean[] closed = [false]
        BufferedReader failing = new BufferedReader(new StringReader("")) {
            @Override
            String readLine() { throw new IOException("disk gone") }

            @Override
            void close() { closed[0] = true; super.close() }
        }
        int before = KeepArtifacts.OPEN_ROW_READERS.get()

        assertThrows(IOException) { new KeepArtifacts.RowReader(failing) }

        assertTrue(closed[0])
        assertEquals(before, KeepArtifacts.OPEN_ROW_READERS.get())
    }
}
