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

    /** DAR-BE-192: a statement link lists its codes in one 255-character column, so a shared code is short. */
    @Test
    void sharedConclusionCodesAreShort() {
        Set<String> older = ["CONC_PARTIAL_PAYMENT_GIVEN_BACK", "CONC_RETIRED_SKU", "CONC_PRODUCT_NOT_IN_NETSUITE"] as Set
        List<String> codes = (read("data/ConclusionCatalogSeedData.xml") =~ /enumId="([A-Z0-9_]+)" enumTypeId="DarpanConclusion"/)
                .collect { ((List) it)[1] as String }
        assertTrue(codes.size() >= 7, "${codes}")
        List<String> long_ = codes.findAll { !older.contains(it) && it.length() > 12 }
        assertTrue(long_.isEmpty(), "shared conclusion codes are 12 characters at most: ${long_}")
    }

    @Test
    void aRuleCanNameItsParentAndAConditionItsQuantifier() {
        String xml = read("entity/RuleEntities.xml")
        String rule = entityBlock(xml, "RuleSetConclusionRule")
        assertTrue(rule.contains('<field name="parentSequenceNum" type="number-integer"'), "DAR-BE-063 tree parent")
        assertTrue(rule.contains('RULE (both present'), "appliesToBucket documents the RULE bucket the conclude pass emits")
        String condition = entityBlock(xml, "RuleSetConclusionCondition")
        assertTrue(condition.contains('<field name="quantifier" type="text-short" default="\'ALL\'"'),
                "ALL default keeps every DAR-UI-044 row's meaning")
    }

    @Test
    void aCompareSourceCanReportDuplicateKeysAsFindings() {
        String block = entityBlock(read("entity/RuleEntities.xml"), "RuleSetCompareSource")
        assertTrue(block.contains('<field name="duplicateKeyHandling" type="text-short"'), "DAR-BE-063 opt-in")
    }
}
