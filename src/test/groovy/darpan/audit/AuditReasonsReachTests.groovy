package darpan.audit

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals

/**
 * DAR-BE-353. A statement's reasons are the causes its own rows can reach: a link's diffTypes name the rows it reports, each
 * row concludes in its bucket (RunConclusionSupport.bucketOf), and a cause tree node applies to one bucket (its own or an
 * ancestor's). Several statements sharing one run no longer each list the run's whole tree.
 */
class AuditReasonsReachTests {

    private static Map rule(int seq, String conclusion, String bucket, Integer parent = null, String outcome = null) {
        return [sequenceNum: seq, parentSequenceNum: parent, conclusionEnumId: conclusion, appliesToBucket: bucket, outcome: outcome]
    }

    private static Map check(String run, String diffTypes, String ruleIds = null) {
        return [savedRunId: run, linkDiffTypes: diffTypes, linkRuleIds: ruleIds]
    }

    private static Map<String, List<Map>> reasons(Map<String, List<Map>> checksByStatement, Map<String, List<Map>> rulesByRun,
                                                  Map<String, Map<String, String>> overrides = [:]) {
        return AuditSettingsSupport.reasonsFrom(checksByStatement, rulesByRun, overrides) { String id -> "label ${id}".toString() }
    }

    private static List<String> codes(List<Map> reasons) { reasons*.conclusion as List<String> }

    // MALBON RS_100510: R2 (Shopify orders missing from HotWax), R3 and R5 (rule checks) share one run.
    private static final List<Map> SHARED_TREE = [
            rule(10, "PLACED_LAST_HOUR", "MISSING_FROM_FILE_2"),
            rule(20, "NOT_IN_HOTWAX", "MISSING_FROM_FILE_2"),
            rule(30, "NOT_FULFILLED", "RULE"),
            rule(40, "NOT_FULFILLED_SHIPPED", null, 30),
            rule(50, "NOT_FULFILLED_CANCELLED", null, 30),
    ]

    @Test
    void "a presence check lists only the causes of the side it reports missing"() {
        Map out = reasons([R2: [check("RS1", "MISSING_IN_FILE_2")]], [RS1: SHARED_TREE])
        assertEquals(["PLACED_LAST_HOUR", "NOT_IN_HOTWAX"], codes(out.R2))
    }

    @Test
    void "a rule check lists the rule causes and none of the missing side's, children included"() {
        Map out = reasons([R5: [check("RS1", "FIELD_MISMATCH", "RULE_FULFILLED")]], [RS1: SHARED_TREE])
        assertEquals(["NOT_FULFILLED", "NOT_FULFILLED_SHIPPED", "NOT_FULFILLED_CANCELLED"], codes(out.R5))
    }

    @Test
    void "a cause tree node has no rule of its own, so two rule checks on one run both list the rule causes"() {
        // Per-rule scoping needs a rule on RuleSetConclusionRule; until then a link's ruleIds cannot narrow the RULE causes.
        Map out = reasons([R3: [check("RS1", "FIELD_MISMATCH", "RULE_PAYMENT")], R5: [check("RS1", "FIELD_MISMATCH", "RULE_FULFILLED")]],
                [RS1: SHARED_TREE])
        assertEquals(codes(out.R5), codes(out.R3))
    }

    @Test
    void "a link that names no row type reaches the whole tree"() {
        Map out = reasons([R1: [check("RS1", null)]], [RS1: SHARED_TREE])
        assertEquals(SHARED_TREE*.conclusionEnumId, codes(out.R1))
    }

    @Test
    void "a node with no bucket anywhere on its path is reached by every row"() {
        Map out = reasons([R2: [check("RS1", "MISSING_IN_FILE_2")], R5: [check("RS1", "FIELD_MISMATCH")]],
                [RS1: [rule(10, "ANY_ROW", null), rule(20, "ONLY_RULE", "RULE")]])
        assertEquals(["ANY_ROW"], codes(out.R2))
        assertEquals(["ANY_ROW", "ONLY_RULE"], codes(out.R5))
    }

    @Test
    void "a single-sided run's findings reach every check on it"() {
        Map out = reasons([R1: [check("RS9", "FINDING")], R4: [check("RS9", "MISSING_IN_FILE_1")]],
                [RS9: [rule(10, "OPEN_ORDER", "FINDING")]])
        assertEquals(["OPEN_ORDER"], codes(out.R1))
        assertEquals(["OPEN_ORDER"], codes(out.R4))
    }

    @Test
    void "a node whose bucket contradicts its parent's is never reached"() {
        Map out = reasons([R5: [check("RS1", "FIELD_MISMATCH")]],
                [RS1: [rule(10, "MISSING", "MISSING_FROM_FILE_2"), rule(20, "ODD_CHILD", "RULE", 10)]])
        assertEquals([], codes(out.R5))
    }

    @Test
    void "hidden and expected are read on the nodes the check reaches"() {
        List<Map> tree = [rule(10, "KNOWN_LAG", "MISSING_FROM_FILE_2", null, "EXPECTED"),
                          rule(20, "KNOWN_LAG", "RULE", null, "HIDE"),
                          rule(30, "SHOWN", "RULE")]
        Map out = reasons([R2: [check("RS1", "MISSING_IN_FILE_2")], R5: [check("RS1", "FIELD_MISMATCH")]], [RS1: tree])
        assertEquals([[conclusion: "KNOWN_LAG", label: "label KNOWN_LAG", priority: null, closes: "all", closesOn: 1, checks: 1]], out.R2)
        assertEquals(["SHOWN"], codes(out.R5))
    }

    @Test
    void "counts span the statement's checks and an override no check reaches is still listed"() {
        Map out = reasons([R7: [check("RS1", "MISSING_IN_FILE_2"), check("RS2", "MISSING_IN_FILE_2")]],
                [RS1: [rule(10, "LAG", "MISSING_FROM_FILE_2", null, "EXPECTED")], RS2: [rule(10, "LAG", "MISSING_FROM_FILE_2"),
                                                                                      rule(20, "RULE_ONLY", "RULE")]],
                [R7: [GONE: "KNOWN", LAG: "P1"]])
        assertEquals([[conclusion: "LAG", label: "label LAG", priority: "P1", closes: "some", closesOn: 1, checks: 2],
                      [conclusion: "GONE", label: "label GONE", priority: "KNOWN", closes: "none", closesOn: 0, checks: 0]], out.R7)
    }
}
