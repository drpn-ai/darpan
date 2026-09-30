package darpan.reconciliation.conclusion

import org.junit.jupiter.api.Test
import org.kie.api.KieServices

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull

/** DAR-BE-063. The tree, fired by Drools. Synthetic systems only: ALPHA is FILE_1, BETA is FILE_2. */
class ConclusionTreeEngineTests {

    static Map c(Map m) {
        return [presence: "ANY", keyScope: "FULL", quantifier: "ALL", fieldExpression: null, operator: null,
                conditionValues: [], checkLabel: "check"] + m
    }

    // BETA absent -> 20; its children split on ALPHA.kind. BETA excluded with state S -> 30.
    static final List<Map> TREE = [
            [sequenceNum: 20, conclusionEnumId: "GONE", label: "Gone from BETA", appliesToBucket: "MISSING_FROM_FILE_2",
             conditions: [c(subject: "FILE_2", presence: "ABSENT", checkLabel: "BETA has none")]],
            [sequenceNum: 21, parentSequenceNum: 20, conclusionEnumId: "GONE_K1", label: "Gone, kind one",
             conditions: [c(subject: "FILE_1", fieldExpression: "kind", operator: "IN", conditionValues: ["K1"], checkLabel: "kind")]],
            [sequenceNum: 22, parentSequenceNum: 20, conclusionEnumId: "GONE_K2", label: "Gone, kind two",
             conditions: [c(subject: "FILE_1", fieldExpression: "kind", operator: "IN", conditionValues: ["K2"], checkLabel: "kind")]],
            [sequenceNum: 30, conclusionEnumId: "HELD", label: "Held in BETA", appliesToBucket: "MISSING_FROM_FILE_2",
             conditions: [c(subject: "FILE_2", presence: "EXCLUDED", checkLabel: "BETA screened it"),
                          c(subject: "FILE_2", fieldExpression: "state", operator: "IN", conditionValues: ["S"], checkLabel: "state")]],
    ]

    static Map finding(String kind, String betaPresence, String betaState = null) {
        return [bucket: "MISSING_FROM_FILE_2",
                sides : [FILE_1: [presence: "KEPT", record: [kind: kind]],
                         FILE_2: [presence: betaPresence, record: betaState ? [state: betaState] : null]]]
    }

    static List<Map> conclude(List<Map> rules, List<Map> findings) {
        ConclusionTreeEngine engine = ConclusionTreeEngine.compile(rules)
        try {
            return engine.conclude(findings)
        } finally {
            engine.close()
        }
    }

    @Test
    void descendsIntoTheFirstMatchingChild() {
        Map r = conclude(TREE, [finding("K2", "ABSENT")])[0]
        assertEquals("GONE_K2", r.code)
        assertEquals(["GONE", "GONE_K2"], r.path*.code)
        assertEquals([["BETA has none", "absent"], ["kind", "K2"]], r.checks.collect { [it.label, it.value] })
    }

    @Test
    void keepsTheNodeWhenNoChildMatches() {
        assertEquals("GONE", conclude(TREE, [finding("K9", "ABSENT")])[0].code)
    }

    @Test
    void siblingFirstMatchIsPerFindingNotPerSession() {
        List<Map> results = conclude(TREE, [finding("K1", "ABSENT"), finding("K2", "ABSENT"),
                                            finding("K1", "EXCLUDED", "S"), finding("K1", "UNKNOWN")])
        assertEquals(["GONE_K1", "GONE_K2", "HELD", "UNEXPLAINED"], results*.code)
    }

    @Test
    void labelsAndConditionsComeFromTheRowsNotTheCompiledRules() {
        // Same shape, different words: one DRL, two meanings — nothing data-bearing was compiled in.
        List<Map> renamed = TREE.collect { new LinkedHashMap(it) }
        renamed[0].label = "Renamed"
        assertEquals("Renamed", conclude(renamed, [finding("K9", "ABSENT")])[0].label)
    }

    @Test
    void closeReleasesTheKieModule() {
        ConclusionTreeEngine engine = ConclusionTreeEngine.compile(TREE)
        assertNotNull(KieServices.Factory.get().getRepository().getKieModule(engine.releaseId))
        engine.close()
        assertNull(KieServices.Factory.get().getRepository().getKieModule(engine.releaseId))
    }

    @Test
    void anEmptyFindingListIsEmpty() {
        assertEquals([], conclude(TREE, []))
    }
}
