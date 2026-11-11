package darpan.conclusion.brain

import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-230. The describer turns rule metadata into deterministic JSON with no dangling edges. */
class BrainPackDescriberTests {

    private static final List<String> PACKS = ["oms-netsuite"]

    @Test
    void everyRuleAppearsOnce() {
        Set<String> expected = BrainEngine.forPacks(PACKS).ruleMetadata().keySet()
        Map graph = (Map) new JsonSlurper().parseText(BrainPackDescriber.toJson(PACKS))
        List<String> ruleNodes = graph.nodes.findAll { it.kind == "rule" }.collect { it.name as String }
        assertFalse(expected.isEmpty())
        assertEquals(expected.size(), ruleNodes.size())
        assertEquals(expected.sort(), ruleNodes.sort())
    }

    @Test
    void everyEdgePointsAtAKnownNode() {
        Map graph = (Map) new JsonSlurper().parseText(BrainPackDescriber.toJson(PACKS))
        Set<String> ids = graph.nodes.collect { it.id as String } as Set
        assertEquals(graph.nodes.size(), ids.size())
        assertFalse(graph.edges.isEmpty())
        graph.edges.each { Map e ->
            assertTrue(ids.contains(e.from as String), "unknown from ${e.from}")
            assertTrue(ids.contains(e.to as String), "unknown to ${e.to}")
        }
        ["read", "question", "piece", "cause", "statement"].each { String k ->
            assertTrue(graph.nodes.any { it.kind == k }, "no ${k} node")
        }
        assertTrue(graph.nodes.any { it.kind == "cause" && it.rank != null })
    }

    @Test
    void twoRunsGiveIdenticalOutput() {
        assertEquals(BrainPackDescriber.toJson(PACKS), BrainPackDescriber.toJson(PACKS))
    }
}
