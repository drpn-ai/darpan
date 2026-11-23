package darpan.rules

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.xml.XmlParser
import org.junit.jupiter.api.Test

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals

/** darpan/data/MoquiRulesSeedData.xml carries moqui-rules' seed rows as darpan-seed; it must not drift from the source. */
class MoquiRulesSeedDataMirrorTests {
    private static List<Map<String, Object>> rows(Path file) {
        Node root = new XmlParser(false, false).parse(file.toFile())
        root.children().findAll { it instanceof Node }.collect { Node e -> [tag: e.name().toString(), attrs: new TreeMap(e.attributes())] as Map<String, Object> }
    }

    @Test
    void darpansCopyHoldsExactlyMoquiRulesSeedRows() {
        Path components = ReconciliationSmokeTestSupport.resolveBackendRoot().resolve("runtime/component")
        List<Map<String, Object>> source = rows(components.resolve("moqui-rules/data/RuleSeedData.xml"))
        List<Map<String, Object>> copy = rows(components.resolve("darpan/data/MoquiRulesSeedData.xml"))
        assertEquals(source, copy, "darpan/data/MoquiRulesSeedData.xml differs from moqui-rules/data/RuleSeedData.xml; copy the rows again")
    }
}
