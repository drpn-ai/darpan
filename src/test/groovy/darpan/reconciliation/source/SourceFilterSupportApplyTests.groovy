package darpan.reconciliation.source

import darpan.reconciliation.conclusion.ExcludedRecordsSidecar

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertSame

/**
 * DAR-BE-063 §1.5: a connector that filters writes the sidecar. This is the one loop that does both,
 * lifted from ShopifyOrderLineUnitSupport.applySourceFilters so every extractor can share it.
 */
class SourceFilterSupportApplyTests {

    static List<Map<String, Object>> rules(String field, String op, String values) {
        return SourceFilterSupport.parseRules([[sequenceNum: 1, fieldExpression: field, operator: op, filterValues: values]])
    }

    /**
     * DAR-OPS-029 S99-062: the one tally behind applyToRecords, the data-defined runner and the kept copy. Counts go
     * under the verdict's reason, the first rejecting rule owns the count, every rule is reported (zero counts too), a
     * null collector only skips the excluded file, and parallel readers lose no count.
     */
    @Test
    void theRejectionTallyCountsEachRejectionOnceUnderItsReasonAndReportsEveryRule() {
        List<Map<String, Object>> parsed = SourceFilterSupport.parseRules([
                [sequenceNum: 1, fieldExpression: "s", operator: "INCLUDE_IN", filterValues: "FULFILLED"],
                [sequenceNum: 2, fieldExpression: "t", operator: "EXCLUDE_IN", filterValues: "X"],
                [sequenceNum: 7, fieldExpression: "u", operator: "EXCLUDE_IN", filterValues: "never"]])
        List<Map> records = [[s: "FULFILLED", t: "X", id: "a"], [s: "OTHER", t: "X", id: "b"], [t: "X", id: "c"], [s: "FULFILLED", id: "d"]]
        SourceFilterSupport.RejectionTally tally = new SourceFilterSupport.RejectionTally()
        Map collector = ExcludedRecordsSidecar.newCollector()
        records.each { Map r ->
            Map<String, Object> verdict = SourceFilterSupport.evaluate(r, parsed)
            if (verdict != null) tally.reject(verdict, collector, r)
        }
        assertEquals(["2": 1, "1": 1], tally.excludedCounts())
        assertEquals(["1": 1], tally.fieldAbsentCounts())
        assertEquals(["a", "b", "c"], ExcludedRecordsSidecar.records(collector)*.id)
        assertEquals([[1, 1, 1], [2, 1, 0], [7, 0, 0]],
                tally.configuredExclusions(parsed).collect { [it.sequenceNum, it.excludedCount, it.fieldAbsentCount] })
        assertEquals(["sequenceNum", "fieldExpression", "operator", "values", "excludedCount", "fieldAbsentCount"],
                tally.configuredExclusions(parsed)[0].keySet() as List)

        SourceFilterSupport.RejectionTally noFile = new SourceFilterSupport.RejectionTally()
        noFile.reject(SourceFilterSupport.evaluate(records[1], parsed), null, records[1])
        assertEquals(["1": 1], noFile.excludedCounts())

        SourceFilterSupport.RejectionTally parallel = new SourceFilterSupport.RejectionTally()
        Map shared = ExcludedRecordsSidecar.newCollector()
        Map<String, Object> verdict = SourceFilterSupport.evaluate(records[1], parsed)
        List<Thread> threads = (1..8).collect { Thread.start { 500.times { parallel.reject(verdict, shared, records[1]) } } }
        threads*.join()
        assertEquals(["1": 4000], parallel.excludedCounts())
        assertEquals(4000, shared.total)
        assertEquals(4000, ExcludedRecordsSidecar.records(shared).size())
        ExcludedRecordsSidecar.discard(collector)
        ExcludedRecordsSidecar.discard(shared)
    }

    @Test
    void noRulesHandsBackTheSameListAndNoReport() {
        List records = [[a: 1]]
        Map r = SourceFilterSupport.applyToRecords(records, [])
        assertSame(records, r.records)
        assertNull(r.configuredExclusions)
        assertNull(r.excludedCollector)
    }

    @Test
    void includeKeepsMatchesAndCollectsTheRest() {
        List records = [[s: "FULFILLED", id: "1"], [s: "UNFULFILLED", id: "2"], [id: "3"]]
        Map r = SourceFilterSupport.applyToRecords(records, rules("s", "INCLUDE_IN", "FULFILLED"))
        assertEquals(["1"], r.records*.id)
        assertEquals(2, r.excludedCollector.total)
        assertEquals(["2", "3"], ExcludedRecordsSidecar.records(r.excludedCollector)*.id)
        Map entry = r.configuredExclusions[0]
        assertEquals(1, entry.excludedCount)
        assertEquals(1, entry.fieldAbsentCount)
    }

    @Test
    void everyRuleIsReportedIncludingOneThatMatchedNothing() {
        Map r = SourceFilterSupport.applyToRecords([[s: "FULFILLED"]], rules("s", "EXCLUDE_IN", "CANCELLED"))
        assertEquals(1, r.records.size())
        assertEquals(0, r.configuredExclusions[0].excludedCount)
        assertEquals(0, r.configuredExclusions[0].fieldAbsentCount)
        assertEquals(0, r.excludedCollector.total)
    }
}
