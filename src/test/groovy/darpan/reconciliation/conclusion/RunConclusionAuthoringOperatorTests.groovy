package darpan.reconciliation.conclusion

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-345. What a saved conclusion tree may say with the prefix operators: one or more prefixes, no commas inside one. */
class RunConclusionAuthoringOperatorTests {

    private static Map save(Map condition) {
        Map node = [key: "20", conclusion: "CONC_X", bucket: "MISSING_FROM_FILE_2",
                    conditions: [[subject: "FILE_1", presence: "ANY", checkLabel: "An exchange order"] + condition]]
        return RunConclusionAuthoringSupport.toRows([node], false, { String id -> id == "CONC_X" ? "Not in NetSuite" : null })
    }

    @Test
    void prefixOperatorsAreOfferedAndSaveTheirPrefixes() {
        assertTrue("STARTS_WITH_ANY" in RunConclusionAuthoringSupport.OPERATORS)
        assertTrue("NOT_STARTS_WITH_ANY" in RunConclusionAuthoringSupport.OPERATORS)
        Map r = save([field: "omsOrderName", operator: "starts_with_any", values: ["EXC-", "REPL-"]])
        assertEquals([], r.errors)
        Map c = r.rows[0].conditions[0]
        assertEquals("STARTS_WITH_ANY", c.operator)
        assertEquals("EXC-,REPL-", c.conditionValues)
        assertEquals([], save([field: "omsOrderName", operator: "NOT_STARTS_WITH_ANY", values: ["EXC-"]]).errors)
    }

    // DAR-OPS-029 S03a: the conclude pass puts a repeated-key row in DUPLICATE_IN_FILE_1/2 (RunConclusionSupport.bucketOf), and
    // a rule may hide those rows (DAR-BE-337). The app refused that bucket, so a tree holding such a rule could not be saved again.
    @Test
    void aTopLevelRuleMayApplyToRepeatedKeyRows() {
        // Aditi D7: and each side's partner row of a repeated key, DUPLICATE_IN_FILE_y_PAIRED.
        ["DUPLICATE_IN_FILE_1", "DUPLICATE_IN_FILE_2", "DUPLICATE_IN_FILE_1_PAIRED", "DUPLICATE_IN_FILE_2_PAIRED"].each { String bucket ->
            assertTrue(bucket in RunConclusionAuthoringSupport.BUCKETS)
            Map r = RunConclusionAuthoringSupport.toRows([[key: "10", conclusion: "CONC_X", bucket: bucket.toLowerCase(),
                    conditions: [[subject: "FILE_2", presence: "KEPT", checkLabel: "Repeated in NetSuite"]]]], false,
                    { String id -> "Split RMA" })
            assertEquals([], r.errors)
            assertEquals(bucket, r.rows[0].appliesToBucket)
        }
    }

    @Test
    void aPrefixOperatorNeedsPrefixesWithoutCommas() {
        Map none = save([field: "omsOrderName", operator: "STARTS_WITH_ANY", values: []])
        assertNull(none.rows)
        assertEquals(["'Not in NetSuite': 'omsOrderName' starts with needs prefixes without commas."], none.errors)
        Map comma = save([field: "omsOrderName", operator: "NOT_STARTS_WITH_ANY", values: ["EXC-,REPL-"]])
        assertEquals(["'Not in NetSuite': 'omsOrderName' does not start with needs prefixes without commas."], comma.errors)
    }
}
