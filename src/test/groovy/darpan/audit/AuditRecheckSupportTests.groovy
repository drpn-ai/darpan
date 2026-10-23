package darpan.audit

import darpan.reconciliation.conclusion.ExcludedRecordsSidecar
import darpan.reconciliation.conclusion.JsonRecordStream
import groovy.json.JsonOutput
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-119. An audit reads OMS for its recent days only (DAR-BE-116), so an open finding whose record is older is read
 * again by key each run and appended to each side's extract: it then closes when the missing record has appeared, or is
 * re-diagnosed by the same conclusions when it has not.
 */
class AuditRecheckSupportTests {

    @TempDir
    File dir

    private File extract(String name, List<Map> records, Map metadata = [windowFrom: "2026-10-16"]) {
        File f = new File(dir, name)
        f.text = JsonOutput.toJson([records: records, metadata: metadata])
        return f
    }

    private static List<Map> records(File f) {
        List<Map> out = []
        JsonRecordStream.forEachRecord(f, "records") { Map r -> out.add(r) }
        return out
    }

    @Test
    void onlyTheOpenFindingsTheRecentReadDoesNotHoldAreAskedFor() {
        File side = extract("oms.json", [[returnId: "M3", statusId: "RETURN_COMPLETED"], [returnId: "M9"]])
        assertEquals(["M1", "M2"] as Set, AuditRecheckSupport.missingKeys(side, "returnId", ["M1", "M2", "M3"] as Set))
        // A composite compare key is asked by its first field: an order's lines come back together.
        assertEquals(["M1167217"] as Set, AuditRecheckSupport.firstKeys(["M1167217\u001F01", "M1167217\u001F02"]))
    }

    @Test
    void theRecordsReadByKeyAreAppendedWithTheirExcludedOnesAndTheExtractKeepsItsMetadata() {
        File side = extract("ns.json", [[returnId: "M3", tranId: "RMA1"]])
        File byKey = extract("ns-recheck.json", [[returnId: "M1", tranId: "RMA9"], [returnId: "M3", tranId: "RMA1"]])
        Map c = ExcludedRecordsSidecar.newCollector()
        ExcludedRecordsSidecar.collect(c, [returnId: "M2", status: "Closed"], [sequenceNum: 1, fieldExpression: "status"])
        ExcludedRecordsSidecar.write(new File(dir, ExcludedRecordsSidecar.fileNameFor(byKey.name)), c, byKey.name)

        assertEquals(1, AuditRecheckSupport.append(side, byKey, "returnId", ["M3"] as Set),
                "M3 was asked for the other side only: this side already read it")

        assertEquals(["M3", "M1"], records(side)*.returnId)
        assertEquals("2026-10-16", JsonRecordStream.forEachRecord(side, "records") { }.metadata.windowFrom)
        Map sidecar = ExcludedRecordsSidecar.read(side)
        assertEquals(["M2"], sidecar.records*.returnId, "a record its filter dropped is still evidence")
        assertTrue((sidecar.records[0]._excludedBy as String).startsWith("1:"))
    }
}
