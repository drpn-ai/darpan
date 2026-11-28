package darpan.datasource

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-OPS-029 S15a, OC176 (the MATCHES half). A definition's MATCHES pattern runs on every record value it is given, with no
 * time bound: a backtracking pattern on an unlucky value pinned the read's thread. The match is now decided within a bound,
 * and a value the pattern cannot decide in time does not match (the condition is false), as any value the pattern rejects.
 */
class RecordConditionsTests {

    private static Map matching(String pattern) { [field: "code", op: "MATCHES", values: [pattern]] }

    @Test
    void aBacktrackingPatternIsDecidedWithinTheBound() {
        // (.*a){8}x on 52 a's backtracks polynomially (about 1 s at 40 characters on a warm JDK 21, which memoises the classic
        // (a+)+ cases but not this); within the bound it is decided at once, as no match.
        long started = System.currentTimeMillis()
        boolean matched = RecordConditions.matches([code: "a" * 52], matching('(.*a){8}x'))
        long took = System.currentTimeMillis() - started
        assertFalse(matched)
        assertTrue(took < 2000L, "decided in ${took} ms")
    }

    @Test
    void anOrdinaryPatternStillMatchesAndRejects() {
        assertTrue(RecordConditions.matches([code: " SKU-42 "], matching('SKU-\\d+')), "the value is trimmed, then matched whole")
        assertFalse(RecordConditions.matches([code: "XSKU-42"], matching('SKU-\\d+')))
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 10, threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    void aValueThePatternWouldNeverFinishOnIsNoMatch() {
        // 80 characters under (.*a){8}x would run for days; the bound decides it as no match.
        assertFalse(RecordConditions.matches([code: "a" * 80], matching('(.*a){8}x')))
    }
}
