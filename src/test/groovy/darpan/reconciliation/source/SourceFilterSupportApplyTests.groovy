package darpan.reconciliation.source

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
        assertEquals(["2", "3"], r.excludedCollector.records*.id)
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
