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
}
