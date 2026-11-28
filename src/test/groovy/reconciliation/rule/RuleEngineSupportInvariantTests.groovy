package reconciliation.rule

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-OPS-029 S02 (lens E): the rule engine's invariants, each through the real Drools path with real generated rules.
 * Stub execution context, no Moqui. The invariants are listed in docs/audits/code-health-2026-11-28/S02.md.
 */
class RuleEngineSupportInvariantTests {

    private static final String TENANT = "KREWE"

    @AfterEach
    void clearCache() {
        RuleEngineSupport.resetForTests()
    }

    private static Map rule(String ruleId, int sequenceNum, String file1Path, String file2Path, String extra = "") {
        String expression = '{"type":"FIELD_COMPARISON","file1FieldPath":"' + file1Path + '","file2FieldPath":"' + file2Path +
                '","operator":"="' + extra + '}'
        return [ruleId: ruleId, ruleSetId: "", sequenceNum: sequenceNum, enabled: "Y",
                ruleLogic: FieldComparisonRuleLogicGenerator.generate(expression, 'id', 'id', ruleId, "WARN", 0)]
    }

    private static Map<String, Object> runPairs(String ruleSetId, List<Map> rules, List<Map> facts) {
        Map ruleSet = [ruleSetId: ruleSetId, companyUserGroupId: TENANT, ruleSetName: ruleSetId]
        List<Map> rows = rules.collect { new LinkedHashMap(it) + [ruleSetId: ruleSetId] }
        Map<String, Object> out = RuleEngineSupport.executeMatchedPairFacts(RuleEngineSupportTestEc.ec(TENANT, ruleSet, rows), ruleSetId, facts)
        assertNull(out.error, "unexpected error: ${out.error}")
        return out
    }

    private static List<Map> pairs() {
        return (1..12).collect { int i ->
            [compareScopeId: "S", primaryId: "K${i}",
             file1: [id: "K${i}", sku: "SKU${i}", price: "${i}.00"],
             file2: [id: "K${i}", sku: (i % 3 == 0 ? "OTHER" : "SKU${i}"), price: (i % 4 == 0 ? "${i}.50" : "${i}")]]
        }
    }

    private static List<Map> twoRules() {
        return [rule("SKU_RULE", 10, "sku", "sku"),
                rule("PRICE_RULE", 20, "price", "price",
                        ',"preActions":[{"fieldSide":"file1","action":"STRING_TO_NUMBER"},{"fieldSide":"file2","action":"STRING_TO_NUMBER"}]')]
    }

    private static Map<String, List<String>> diffsByKey(List<Map> diffs) {
        Map<String, List<String>> byKey = new TreeMap<>()
        diffs.each { Map d -> byKey.computeIfAbsent(d.primaryId as String) { [] }.add("${d.ruleId}:${d.file1Value}:${d.file2Value}".toString()) }
        return byKey
    }

    /** Invariant 3 (determinism): same facts, same verdict, run after run and in any fact order. */
    @Test
    void sameFactsGiveTheSameVerdictInAnyFactOrder() {
        List<Map> facts = pairs()
        Map first = runPairs("RS_DET", twoRules(), facts)
        Map again = runPairs("RS_DET", twoRules(), facts)
        List<Map> shuffled = new ArrayList<>(facts)
        Collections.shuffle(shuffled, new Random(20261128L))
        Map reordered = runPairs("RS_DET", twoRules(), shuffled)

        assertEquals(first.diffResults, again.diffResults, "a second run of the same facts differed")
        assertEquals(diffsByKey(first.diffResults as List), diffsByKey(reordered.diffResults as List), "fact order changed a verdict")
        // 4 SKU mismatches (3,6,9,12) and 3 price mismatches (4,8,12): 7, no more, no fewer.
        assertEquals(7, (first.diffResults as List).size())
        assertEquals(["SKU_RULE:SKU12:OTHER", "PRICE_RULE:12.00:12.50"], diffsByKey(first.diffResults as List)["K12"])
    }

    /** Invariant 3: every generated rule has the same (default) salience; the tie resolves by the rule sequence, explicitly. */
    @Test
    void withinAFactDiffsFollowTheRuleSequenceNotTheAgenda() {
        List<Map> facts = [pairs()[11]]
        Map skuFirst = runPairs("RS_ORDER_A", [rule("SKU_RULE", 10, "sku", "sku"), rule("PRICE_RULE", 20, "price", "price")], facts)
        Map priceFirst = runPairs("RS_ORDER_B", [rule("PRICE_RULE", 10, "price", "price"), rule("SKU_RULE", 20, "sku", "sku")], facts)
        assertEquals(["SKU_RULE", "PRICE_RULE"], (skuFirst.diffResults as List<Map>)*.ruleId)
        assertEquals(["PRICE_RULE", "SKU_RULE"], (priceFirst.diffResults as List<Map>)*.ruleId)
    }

    /** Invariant 1 (conservation): two equal rows are two facts, each matched once; neither collapses into the other. */
    @Test
    void identicalRowsAreEachCountedOnce() {
        Map ruleSet = [ruleSetId: "RS_TEXT", companyUserGroupId: TENANT, ruleSetName: "RS_TEXT"]
        List<Map> rules = [[ruleId: "PENDING", ruleSetId: "RS_TEXT", sequenceNum: 10, enabled: "Y", ruleText: "status is Pending"],
                           [ruleId: "ANY", ruleSetId: "RS_TEXT", sequenceNum: 20, enabled: "Y", ruleText: "orderId is 'O1'"]]
        List<Map> rows = [[orderId: "O1", status: "Pending"], [orderId: "O1", status: "Pending"], [orderId: "O1", status: "Done"]]
        Map out = RuleEngineSupport.executeRuleSetFacts(RuleEngineSupportTestEc.ec(TENANT, ruleSet, rules), "RS_TEXT", rows, false)
        assertNull(out.error, "unexpected error: ${out.error}")
        assertEquals(3, (out.matchedResults as List).size(), "a row matched by a rule must appear once, equal rows included")
        assertEquals([["PENDING", "ANY"], ["PENDING", "ANY"], ["ANY"]], (out.matchedResults as List<Map>)*._matchedRuleIds)
    }

    /** Invariant 8 (idempotence): a replayed row brings no stale diffs in, and the caller's row is never written to. */
    @Test
    void aReplayedRowCarriesNoStaleDiffsAndIsNotMutated() {
        List<Map> callerDiffs = [[diffType: "STALE", primaryId: "K1"]]
        Map replayed = [compareScopeId: "S", primaryId: "K1", file1: [id: "K1", sku: "A"], file2: [id: "K1", sku: "A"],
                        _generatedDiffs: callerDiffs, _matchedRuleIds: ["OLD_RULE"]]
        Map out = runPairs("RS_REPLAY", [rule("SKU_RULE", 10, "sku", "sku")], [replayed])
        assertEquals([], out.diffResults, "a clean pair reported a diff it did not raise")
        assertEquals(1, callerDiffs.size(), "the caller's diff list was appended to")
        assertEquals(["OLD_RULE"], replayed._matchedRuleIds)
    }

    /** Invariant 2 (no hollow green): a rule set with no active rules is an error, never an empty clean result. */
    @Test
    void aRuleSetWithNoActiveRulesIsAnErrorNotClean() {
        Map ruleSet = [ruleSetId: "RS_EMPTY", companyUserGroupId: TENANT, ruleSetName: "RS_EMPTY"]
        Map out = RuleEngineSupport.executeMatchedPairFacts(RuleEngineSupportTestEc.ec(TENANT, ruleSet, []), "RS_EMPTY", pairs())
        assertNotNull(out.error)
        assertTrue(out.diffResults == null, "an errored run must not also hand back a (clean-looking) diff list")
    }
}
