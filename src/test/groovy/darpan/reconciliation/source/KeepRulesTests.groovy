package darpan.reconciliation.source

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-318 plan 3b-2b Task 2. The Keep rule shape, tested on a made-up system (ACME). */
class KeepRulesTests {

    private static Map compare(String field, String text = "GROOVY", String op = "IN") {
        return [kind: "compare", field: field, text: text, op: op, values: ["X"], valueKind: "TEXT", fold: "A_UPPER"]
    }

    private static Map rule(String id, Map predicate, String source = "TENANT") {
        return [id: id, sequenceNum: 1, label: "l", source: source, tests: [[test: predicate, reason: "VALUE"]]]
    }

    @Test
    void aRuleWithoutTestsOrWithAnUnknownOpIsRefusedByName() {
        KeepRules.validate([rule("A:1", compare("acmeChannel"))])

        IllegalArgumentException noTests = assertThrows(IllegalArgumentException) {
            KeepRules.validate([[id: "A:7", sequenceNum: 7, label: "l", source: "TENANT", tests: []]])
        }
        assertTrue(noTests.message.contains("A:7"), noTests.message)

        IllegalArgumentException badOp = assertThrows(IllegalArgumentException) {
            KeepRules.validate([rule("B:9", [kind: "all", of: [compare("acmeChannel", "FACT", "CONTAINS")]], "DECISION")])
        }
        assertTrue(badOp.message.contains("B:9") && badOp.message.contains("CONTAINS"), badOp.message)
    }

    @Test
    void injectionsAreOneColumnPerFieldAndTextInFirstSeenOrder() {
        List<Map> rules = [
                rule("A:1", compare("acmeChannel")),
                rule("B:2", [kind: "any", of: [compare("acmeKind", "FACT"), [kind: "not", of: compare("acmeChannel")],
                                               [kind: "const", holds: true]]], "DECISION"),
                rule("A:3", compare("acmeChannel", "FACT"))]

        List<Map> injections = KeepRules.injections(rules)

        assertEquals([[column: "_darpanKeep_0", field: "acmeChannel", text: "GROOVY"],
                      [column: "_darpanKeep_1", field: "acmeKind", text: "FACT"],
                      [column: "_darpanKeep_2", field: "acmeChannel", text: "FACT"]], injections)
        assertEquals(["acmeChannel", "acmeKind"] as List, KeepRules.fields(rules).toList())
        assertEquals([], KeepRules.injections(null))
    }
}
