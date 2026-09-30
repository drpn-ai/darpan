package darpan.reconciliation.conclusion

import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-UI-044. The conclusion rules are tenant configuration a run reads, so their shape is a contract:
 * tenant-scoped, and free of a field named `values` (on a Groovy Map that name resolves to Map.values()).
 */
class ConclusionEntityContractTests {

    private static Path componentRoot() {
        Path p = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (p != null && !Files.exists(p.resolve("entity/RuleEntities.xml"))) {
            Path candidate = p.resolve("runtime/component/darpan")
            if (Files.exists(candidate.resolve("entity/RuleEntities.xml"))) return candidate
            p = p.parent
        }
        if (p == null) throw new IllegalStateException("darpan component root not found")
        return p
    }

    private static String read(String rel) {
        return Files.readString(componentRoot().resolve(rel))
    }

    private static String entityBlock(String xml, String name) {
        int at = xml.indexOf("entity-name=\"${name}\"")
        assertTrue(at > 0, "${name} missing")
        return xml.substring(at, xml.indexOf("</entity>", at))
    }

    @Test
    void conclusionEntitiesAreTenantScopedConfiguration() {
        String xml = read("entity/RuleEntities.xml")
        ["RuleSetConclusionRule", "RuleSetConclusionCondition"].each { String name ->
            String block = entityBlock(xml, name)
            assertTrue(block.contains('use="configuration"'), "${name} must be configuration")
            assertTrue(block.contains('name="companyUserGroupId"'), "${name} must carry the tenant")
        }
    }

    @Test
    void conditionHasNoFieldNamedValues() {
        String block = entityBlock(read("entity/RuleEntities.xml"), "RuleSetConclusionCondition")
        assertFalse(block.contains('name="values"'), "a Map field named values shadows Map.values()")
        assertTrue(block.contains('name="conditionValues"'))
    }

    @Test
    void connectorCarriesEvidenceProjectionAsVeryLongText() {
        assertTrue(read("entity/ReconciliationEntities.xml").contains('<field name="evidenceFieldsJson" type="text-very-long"'),
                "text-very-long: SourceSystemConnector already holds a text-long and MySQL rows cap near 65535 bytes")
    }

    @Test
    void connectorMapCarriesTheEvidenceProjection() {
        assertTrue(read("src/main/groovy/darpan/reconciliation/automation/SourceSystemConnectorSupport.groovy")
                .contains('"evidenceFieldsJson"'), "toConnectorMap whitelists fields; a missing entry drops it silently")
    }

    @Test
    void unexplainedIsSeededAndSeedIsSeed() {
        String data = read("data/ConclusionCatalogSeedData.xml")
        assertTrue(data.contains('<entity-facade-xml type="seed">'))
        assertTrue(data.contains('enumTypeId="DarpanConclusion"'))
        assertTrue(data.contains('enumId="UNEXPLAINED"'))
    }
}
