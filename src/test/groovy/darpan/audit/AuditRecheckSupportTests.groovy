package darpan.audit

import darpan.reconciliation.conclusion.ExcludedRecordsSidecar
import darpan.reconciliation.conclusion.JsonRecordStream
import groovy.json.JsonOutput
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
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
    void theOldestOpenFindingsAreReCheckedFirst() {
        // DAR-BE-156: a lexical sort re-read the same keys every morning; the oldest finding goes first.
        List<Map> rows = [[recordKey: "A1", firstFoundDate: java.sql.Date.valueOf("2026-10-20")],
                          [recordKey: "Z9", firstFoundDate: java.sql.Date.valueOf("2026-10-01")],
                          [recordKey: "M5\u001F02", firstFoundDate: java.sql.Date.valueOf("2026-10-05")],
                          [recordKey: "M5\u001F01", firstFoundDate: java.sql.Date.valueOf("2026-10-05")]]
        assertEquals(["Z9", "M5", "A1"], AuditRecheckSupport.oldestFirstKeys(rows))
    }

    @Test
    void everyMissingKeyIsReCheckedInBatchesWithNoCeiling() {
        // DAR-BE-156: MAX_KEYS bounds one read by key, not the re-check: 2,001 older findings are all read again.
        List<String> ordered = (1..2001).collect { "K${it}".toString() } + ["HELD"]
        Set<String> missing = (ordered - ["HELD"]) as Set
        List<List<String>> batches = AuditRecheckSupport.batches(ordered, missing)
        assertEquals([AuditRecheckSupport.MAX_KEYS, AuditRecheckSupport.MAX_KEYS, 1], batches*.size())
        assertEquals("K1", batches[0][0], "oldest first")
        assertEquals(ordered - ["HELD"], batches.flatten(), "every missing key once; a key the recent read holds is not asked")
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

    @Test
    void eachSideIsAskedOnlyForTheKeysItLacks() {
        // DAR-BE-156, gorjana R1: NetSuite's recent read already holds M2's lines; HotWax's (yesterday only) holds neither.
        File oms = extract("oms.json", [[omsOrderId: "M9", orderItemSeqId: "01"]])
        File ns = extract("ns.json", [[orderId: "M2", orderLineId: "01"]])
        List<Map> plan = [[side: [result: [recordCount: 1]], fileSide: "FILE_1", keyField: "omsOrderId", file: oms],
                          [side: [result: [recordCount: 1]], fileSide: "FILE_2", keyField: "orderId", file: ns]]
        Map asked = [:]
        Map outcome = AuditRecheckSupport.readAndAppend(plan, ["M1", "M2"]) { Map side, List<String> keys ->
            String fileSide = plan.find { it.side.is(side) }.fileSide
            asked[fileSide] = keys
            fileSide == "FILE_1" ? extract("oms-key.json", [[omsOrderId: "M1", orderItemSeqId: "01"], [omsOrderId: "M2", orderItemSeqId: "01"]]) :
                    extract("ns-key.json", [])
        }
        assertEquals([FILE_1: ["M1", "M2"], FILE_2: ["M1"]], asked, "NetSuite is not asked again for M2, which it holds")
        assertEquals(null, outcome.failure)
        assertEquals(["M9", "M1", "M2"], records(oms)*.omsOrderId)
        assertEquals(3, plan[0].side.result.recordCount)
        assertEquals(["M2"], records(ns)*.orderId, "M1 is still not in NetSuite, so it stays missing there")
    }

    @Test
    void aSideWhoseReadFailsLeavesBothExtractsAsTheyWereAndSaysWhy() {
        // DAR-BE-156: gorjana prod 2026-10-29, the NetSuite read by key timed out; the re-check used to log one warning and
        // the check still read "ran". Nothing is appended, so no record is judged against a side that was not read.
        File oms = extract("oms.json", [[omsOrderId: "M9"]])
        File ns = extract("ns.json", [[orderId: "M9"]])
        List<Map> plan = [[side: [result: [:]], fileSide: "FILE_1", keyField: "omsOrderId", file: oms],
                          [side: [result: [:]], fileSide: "FILE_2", keyField: "orderId", file: ns]]
        Map outcome = AuditRecheckSupport.readAndAppend(plan, ["M1"]) { Map side, List<String> keys ->
            if (plan[1].side.is(side)) throw new IllegalStateException("NetSuite SuiteQL page at offset 0 failed after 3 attempts")
            extract("oms-key.json", [[omsOrderId: "M1"]])
        }
        assertEquals("Older findings were not re-checked: NetSuite SuiteQL page at offset 0 failed after 3 attempts", outcome.failure)
        assertEquals(outcome.failure, plan[1].side.result.recheckFailure, "the failing side's read carries the reason")
        assertEquals(null, plan[0].side.result.recheckFailure)
        assertEquals(["M9"], records(oms)*.omsOrderId, "HotWax's M1 was read but not appended")
    }

    @Test
    void theRunsReCheckStepNamesTheSystemsReadAgainAndWhatTheyAdded() {
        // DAR-BE-156: the run page's RECHECK row. NetSuite already held every key here, so only HotWax is named.
        Map ok = AuditRecheckSupport.stepMetrics([keys: [FILE_1: 1294, FILE_2: 0], records: [FILE_1: 2241, FILE_2: 0], millis: 24774],
                [FILE_1: "OMS", FILE_2: "NETSUITE"])
        assertEquals(2241, ok.recordCount)
        assertEquals(null, ok.errorMessage)
        Map json = (Map) new groovy.json.JsonSlurper().parseText(ok.metricsJson as String)
        assertEquals(["OMS"], json.recheckedSystems)
        assertEquals([FILE_1: 1294, FILE_2: 0], json.keys)

        Map failed = AuditRecheckSupport.stepMetrics([keys: [FILE_1: 1000], records: [:], failure: "Older findings were not re-checked: timed out"],
                [FILE_1: "OMS", FILE_2: "NETSUITE"])
        assertEquals("Older findings were not re-checked: timed out", failed.errorMessage)
        assertEquals(0, failed.recordCount, "nothing is appended when a read fails")
    }

    @Test
    void aSideWhoseKeyCannotBeReadIsAFailureNotASkip() {
        File oms = extract("oms.json", [])
        List<Map> plan = [[side: [result: [:]], fileSide: "FILE_1", keyField: null, file: oms]]
        Map outcome = AuditRecheckSupport.readAndAppend(plan, ["M1"]) { Map side, List<String> keys -> null }
        assertEquals("Older findings were not re-checked: its FILE_1 key cannot be read", plan[0].side.result.recheckFailure)
        assertTrue(outcome.failure as boolean)
    }

    @Test
    void anOlderOrderThatChangedIsComparedOnBothSidesNeverAsMissingFromNetsuite() {
        // DAR-BE-356: gorjana M1214814 was placed Nov 3 and completed Nov 8; the recent HotWax read (orderDate, last ~2 days)
        // never held it, so R1 never compared it. HotWax answers it as changed; NetSuite is read for it by key, as for an open
        // finding; HotWax is not asked again for what its changed read holds.
        File oms = extract("oms.json", [[omsOrderId: "M1306138", orderItemSeqId: "01"]])
        File ns = extract("ns.json", [[orderId: "M1306138", orderLineId: "01"]])
        List<Map> plan = [[side: [result: [recordCount: 1]], fileSide: "FILE_1", keyField: "omsOrderId", file: oms, recentOnly: true],
                          [side: [result: [recordCount: 1]], fileSide: "FILE_2", keyField: "orderId", file: ns, recentOnly: false]]
        File changedFile = extract("oms-changed.json", [[omsOrderId: "M1214814", orderItemSeqId: "02", unitState: "COMPLETED"],
                                                       [omsOrderId: "M1306138", orderItemSeqId: "01"]])
        List<String> askedChanged = []
        Map changed = AuditRecheckSupport.readChangedFiles(plan) { Map side ->
            askedChanged.add(plan.find { it.side.is(side) }.fileSide as String)
            changedFile
        }
        assertEquals(["FILE_1"], askedChanged, "only the side read by date is asked what changed")
        assertEquals(["M1214814"], changed.keys, "an order the recent read already holds is not re-read anywhere")

        Map asked = [:]
        Map outcome = AuditRecheckSupport.readAndAppend(plan, ["M1"] + (List<String>) changed.keys) { Map side, List<String> keys ->
            String fileSide = plan.find { it.side.is(side) }.fileSide
            asked[fileSide] = keys
            fileSide == "FILE_1" ? extract("oms-key.json", [[omsOrderId: "M1"]]) :
                    extract("ns-key.json", [[orderId: "M1214814", orderLineId: "02", lineState: "OPEN"]])
        }
        assertEquals([FILE_1: ["M1"], FILE_2: ["M1", "M1214814"]], asked, "HotWax holds M1214814 in its changed read")
        assertEquals(["M1306138", "M1"], records(oms)*.omsOrderId, "changed records wait until every read by key finished")

        AuditRecheckSupport.appendChangedFiles(plan, (Map<String, Integer>) outcome.records)
        assertEquals(["M1306138", "M1", "M1214814"], records(oms)*.omsOrderId, "M1306138 once: the recent read had it")
        assertEquals(["M1306138", "M1214814"], records(ns)*.orderId)
        assertEquals(3, plan[0].side.result.recordCount)
        assertEquals(2, outcome.records.FILE_1)
    }

    @Test
    void aChangedReadThatFailsAddsNothingAndSaysWhy() {
        File oms = extract("oms.json", [[omsOrderId: "M9"]])
        List<Map> plan = [[side: [result: [:]], fileSide: "FILE_1", keyField: "omsOrderId", file: oms, recentOnly: true]]
        Map changed = AuditRecheckSupport.readChangedFiles(plan) { Map side -> throw new IllegalStateException("OMS GraphQL changed-orders read failed [THROTTLED]") }
        assertEquals("Records changed since the last read were not re-read: OMS GraphQL changed-orders read failed [THROTTLED]", changed.failure)
        assertEquals(changed.failure, plan[0].side.result.recheckFailure, "the check is partial and says why")
        assertEquals([], changed.keys)
        assertEquals(["M9"], records(oms)*.omsOrderId)
    }

    @Test
    void aSideWhoseExtractorCannotSayWhatChangedIsLeftAsItIs() {
        File oms = extract("oms.json", [[omsOrderId: "M9"]])
        List<Map> plan = [[side: [result: [:]], fileSide: "FILE_1", keyField: "omsOrderId", file: oms, recentOnly: true]]
        Map changed = AuditRecheckSupport.readChangedFiles(plan) { Map side -> null }
        assertEquals([], changed.keys)
        assertEquals(null, changed.failure)
        AuditRecheckSupport.appendChangedFiles(plan, [:])
        assertEquals(["M9"], records(oms)*.omsOrderId)
    }

    /**
     * DAR-BE-320. A one-sided NetSuite check read from the recent start (Nov 24 on): a line ordered Nov 3 that changed since
     * comes back in the changed read and joins the extract, and an open finding on a Nov 5 line that did not change is read
     * again by key. Every one is in the extract the check evaluates, so its key is examined and the finding can close or stay.
     */
    @Test
    void aNetsuiteSideReadByChangeHoldsWhatChangedAndEveryOpenFinding() {
        File ns = extract("ns.json", [[orderLineId: "L25", tranDate: "2026-11-25", lineState: "OPEN"]])
        Map side = [source: [systemEnumId: "NETSUITE_SUITEQL", fileSide: "FILE_1"], result: [recordCount: 1, fileLocation: ns.absolutePath],
                    readRecent: true]
        assertTrue(AuditRecheckSupport.readsRecent(null, side), "read by change: re-checked as a recent read")
        assertFalse(AuditRecheckSupport.readsRecent(null, side + [readRecent: false]), "read for the whole window: nothing to re-check")
        assertTrue(AuditRecheckSupport.readsRecent(null, [source: [systemEnumId: "OMS_GQL"]]), "HotWax OMS: unchanged")

        List<Map> plan = [[side: side, fileSide: "FILE_1", keyField: "orderLineId", file: ns, recentOnly: AuditRecheckSupport.readsRecent(null, side)]]
        File changedFile = extract("ns-changed.json", [[orderLineId: "L03", tranDate: "2026-11-03", lineState: "CLOSED"],
                                                       [orderLineId: "L25", tranDate: "2026-11-25", lineState: "OPEN"]])
        Map changed = AuditRecheckSupport.readChangedFiles(plan) { Map s -> changedFile }
        assertEquals(["L03"], changed.keys, "the recent read already holds L25")
        Map asked = [:]
        Map outcome = AuditRecheckSupport.readAndAppend(plan, ["L05"] + (List<String>) changed.keys) { Map s, List<String> keys ->
            asked.FILE_1 = keys
            extract("ns-key.json", [[orderLineId: "L05", tranDate: "2026-11-05", lineState: "OPEN"]])
        }
        assertEquals([FILE_1: ["L05"]], asked, "only the open finding is read by key: the changed read holds L03")
        AuditRecheckSupport.appendChangedFiles(plan, (Map<String, Integer>) outcome.records)
        assertEquals(["L25", "L05", "L03"], records(ns)*.orderLineId, "every open finding and every changed line is evaluated")
        assertEquals(3, side.result.recordCount)
    }
}
