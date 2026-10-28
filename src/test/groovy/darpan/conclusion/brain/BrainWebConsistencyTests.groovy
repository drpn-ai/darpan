package darpan.conclusion.brain

import groovy.json.JsonSlurper
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import static org.junit.jupiter.api.Assertions.assertEquals

/**
 * DAR-BE-151 (design D9). The web Aditi reads (claude.ai/artifact/P5r3oxsGXV99mjGaBn5X2V, source
 * docs/superpowers/specs/conclusion-graph/conclusion-graph.json in the wrapper) may not drift from the pack: every name
 * a pack rule produces, asks, concludes or states is a pilot node there, and every pilot rule node there is in the pack.
 * A node matches by its `brain` name, else its label, else its code. Skipped where the wrapper is not checked out.
 */
class BrainWebConsistencyTests {

    private static Path web() {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (p != null && !Files.exists(p.resolve("docs/superpowers/specs/conclusion-graph/conclusion-graph.json"))) p = p.parent
        return p?.resolve("docs/superpowers/specs/conclusion-graph/conclusion-graph.json")
    }

    private static String name(Object v) { v?.toString()?.replaceAll(/^"|"$/, "")?.trim() }

    @Test
    void theWebShowsExactlyThePacksRulesAsPilot() {
        Path file = web()
        Assumptions.assumeTrue(file != null, "the wrapper's web JSON is not checked out here")
        List<Map> nodes = (List<Map>) ((Map) new JsonSlurper().parse(file.toFile())).nodes
        Set<String> packNames = [] as Set
        BrainEngine.forPacks(["oms-netsuite"]).ruleMetadata().values().each { Map m ->
            ["produces", "asks", "concludes", "states"].each { if (m.get(it) != null) packNames.add(name(m.get(it))) }
        }
        Set<String> webPilot = nodes.findAll { it.stage == "pilot" && !(it.kind in ["read", "fact"]) }
                .collect { (it.brain ?: (it.kind in ["expected", "fix"] ? it.code : it.label)) as String } as Set
        assertEquals(packNames.sort(), webPilot.sort())
    }
}
