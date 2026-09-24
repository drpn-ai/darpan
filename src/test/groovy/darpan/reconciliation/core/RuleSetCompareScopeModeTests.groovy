package darpan.reconciliation.core

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-049: a compare scope may now describe ONE source rather than two.
 *
 * WHY THIS EXISTS. The engine was two-sided by construction — FILE_SIDES was a constant and a scope
 * missing either side threw. That is right for a comparison and wrong for an exception check ("which
 * rows fail a predicate"), which has one source and whose findings ARE its rows. Rather than bend a
 * diff around that, a scope now carries a mode, and the mode decides how many sides are legal.
 *
 * Pure by design: the side arithmetic is the part worth pinning, and it needs neither Moqui nor Spark,
 * so it lands in the fast unitTest pool rather than behind a booted smoke test.
 */
class RuleSetCompareScopeModeTests {

    private static List<String> sides(String mode, String... present) {
        return RuleSetCompareScopeAdapter.resolveActiveSides(mode, present.toList(), "Order presence")
    }

    // ---------------------------------------------------------------- COMPARE keeps its old contract

    @Test
    void compareModeRequiresBothSides() {
        assertEquals(["FILE_1", "FILE_2"], sides("COMPARE", "FILE_1", "FILE_2"))
    }

    @Test
    void absentModeIsCompare() {
        // Every scope that exists today has no mode stored. A null must mean COMPARE, or adding the
        // column would silently convert every configured reconciliation into a one-sided evaluate.
        assertEquals(["FILE_1", "FILE_2"], sides(null, "FILE_1", "FILE_2"))
        assertEquals(["FILE_1", "FILE_2"], sides("   ", "FILE_1", "FILE_2"))
    }

    @Test
    void compareModeStillRejectsAMissingSide() {
        def e = assertThrows(IllegalArgumentException) { sides("COMPARE", "FILE_1") }
        assertTrue(e.message.contains("must define both FILE_1 and FILE_2"), e.message)
        assertTrue(e.message.contains("Order presence"), e.message)
        assertThrows(IllegalArgumentException) { sides("COMPARE", "FILE_2") }
    }

    // ---------------------------------------------------------------- EVALUATE is single-sided

    @Test
    void evaluateModeTakesFileOneAlone() {
        assertEquals(["FILE_1"], sides("EVALUATE", "FILE_1"))
    }

    @Test
    void evaluateModeIsCaseAndWhitespaceInsensitive() {
        assertEquals(["FILE_1"], sides(" evaluate ", "FILE_1"))
    }

    @Test
    void evaluateModeRejectsASecondSide() {
        // Not merely ignored. A second source on an evaluate scope means the operator expected a
        // comparison, and silently dropping it would run a different reconciliation than the one
        // configured while reporting success.
        def e = assertThrows(IllegalArgumentException) { sides("EVALUATE", "FILE_1", "FILE_2") }
        assertTrue(e.message.contains("EVALUATE"), e.message)
        assertTrue(e.message.contains("FILE_2"), e.message)
    }

    @Test
    void evaluateModeRequiresFileOneSpecifically() {
        // FILE_2 alone is not "one source" — the whole pipeline keys its single-side plumbing on
        // FILE_1, so allowing FILE_2 alone would produce a scope that prepares nothing.
        def e = assertThrows(IllegalArgumentException) { sides("EVALUATE", "FILE_2") }
        assertTrue(e.message.contains("FILE_1"), e.message)
    }

    // ---------------------------------------------------------------- an unknown mode is not a default

    @Test
    void unknownModeThrowsRatherThanFallingBackToCompare() {
        // A typo'd mode must not quietly become a two-sided compare: that is the failure where the
        // operator configured one thing and the run did another.
        def e = assertThrows(IllegalArgumentException) { sides("EVALUTE", "FILE_1") }
        assertTrue(e.message.contains("EVALUTE"), e.message)
        assertTrue(e.message.contains("COMPARE"), e.message)
    }

    @Test
    void modeConstantsAreTheStoredValues() {
        assertEquals("COMPARE", RuleSetCompareScopeAdapter.SCOPE_MODE_COMPARE)
        assertEquals("EVALUATE", RuleSetCompareScopeAdapter.SCOPE_MODE_EVALUATE)
    }
}
