package darpan.script

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull

class ScriptBudgetResetForTestsTests {
    @Test
    void resetForTestsDropsTheCallingThreadsBudgetAndIsIdempotent() {
        ScriptBudget.open(new ScriptBudget(100L, 10, 10))
        assertNotNull(ScriptBudget.current())
        ScriptBudget.resetForTests()
        assertNull(ScriptBudget.current())
        ScriptBudget.resetForTests()
        assertNull(ScriptBudget.current())
    }
}
