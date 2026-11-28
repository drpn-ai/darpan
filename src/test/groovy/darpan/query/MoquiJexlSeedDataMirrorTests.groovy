package darpan.query

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.xml.XmlParser
import org.junit.jupiter.api.Test

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse

/** DAR-BE-334: darpan/data/MoquiJexlSeedData.xml carries moqui-jexl's seed rows as darpan-seed; it must not drift from them. */
class MoquiJexlSeedDataMirrorTests {
    private static List<Map<String, Object>> rows(Path file) {
        Node root = new XmlParser(false, false).parse(file.toFile())
        root.children().findAll { it instanceof Node }.collect { Node e -> [tag: e.name().toString(), attrs: new TreeMap(e.attributes())] as Map<String, Object> }
    }

    @Test
    void darpansCopyHoldsExactlyMoquiJexlSeedRows() {
        Path components = ReconciliationSmokeTestSupport.resolveBackendRoot().resolve("runtime/component")
        List<Map<String, Object>> source = rows(components.resolve("moqui-jexl/data/JexlSecurityData.xml")) +
                rows(components.resolve("moqui-jexl/data/JexlEmailData.xml"))
        List<Map<String, Object>> copy = rows(components.resolve("darpan/data/MoquiJexlSeedData.xml"))
        assertFalse(source.isEmpty(), "moqui-jexl's JexlSecurityData.xml + JexlEmailData.xml have no rows; the mirror check would compare nothing")
        assertEquals(source, copy, "darpan/data/MoquiJexlSeedData.xml differs from moqui-jexl's JexlSecurityData.xml + JexlEmailData.xml; copy the rows again")
    }
}
