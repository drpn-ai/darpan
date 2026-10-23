package reconciliation.rule

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

class RuleDiffSupportTests {

    @Test
    void applyPreActionsConvertsStringValuesBeforeOperatorComparison() {
        List<String> preActions = ["STRING_TO_INT"]

        Object left = RuleDiffSupport.applyPreActions("10", preActions)
        Object right = RuleDiffSupport.applyPreActions("2", preActions)

        assertEquals(10L, left)
        assertEquals(2L, right)
        assertTrue(RuleDiffSupport.matchesOperator(left, right, ">"))
    }

    @Test
    void applyPreActionsReturnsNullForInvalidIntegerInput() {
        assertNull(RuleDiffSupport.applyPreActions("not-a-number", ["STRING_TO_INT"]))
    }

    @Test
    void normalizePreActionsCanonicalizesAliasesAndFiltersUnsupportedValues() {
        assertEquals(
                ["STRING_TO_INT", "STRING_TO_NUMBER"],
                RuleDiffSupport.normalizePreActions(["to_integer", "TO_NUMBER", "TRIM"])
        )
    }

    @Test
    void aDiffOnAMatchedPairCarriesBothRecordsIdsAndNothingElse() {
        // DAR-BE-117: a FIELD_MISMATCH row names only its key; the finding needs each record's ids to be navigable.
        Map fact = [compareScopeId: "S", primaryId: "80852690",
                    file1: [memoId: "80852690", tranId: "CM7647980", internalId: "80852690", orderId: "M1085548", status: "Open"],
                    file2: [memoId: "80852690", tranId: "SO761841", orderId: "NO_ORDER_ON_REPLACEMENT", dayNumber: "2459269"]]
        RuleDiffSupport.addFieldMismatch(fact, "R13", "yearBeforeDay <= dayNumber", "2460970", "2459269")
        Map row = RuleDiffSupport.extractNormalizedDiffs(fact)[0]
        assertEquals([file1: [tranId: "CM7647980", internalId: "80852690", orderId: "M1085548"],
                      file2: [tranId: "SO761841", orderId: "NO_ORDER_ON_REPLACEMENT"]],
                new groovy.json.JsonSlurper().parseText(row.ids as String))
        assertNull(row.data, "data stays the present record's, which a matched pair has none of")
        assertNull(RuleDiffSupport.pairIds([primaryId: "K", file1: [status: "Open"]]), "no ids, no column")
    }
}
