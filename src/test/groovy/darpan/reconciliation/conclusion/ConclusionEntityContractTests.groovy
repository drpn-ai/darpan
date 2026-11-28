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

    /** systemEnumId -> attributes of every SourceSystemConnector row in one data file. */
    private static Map<String, Map<String, String>> connectorRows(String rel) {
        def root = new groovy.xml.XmlSlurper().parseText(read(rel))
        return root.'**'.findAll { it.name() == 'darpan.reconciliation.SourceSystemConnector' }
                .collectEntries { [(it.@systemEnumId.text()): it.attributes()] } as Map<String, Map<String, String>>
    }

    /**
     * DAR-BE-336: a run's expanded row shows a found record's status and facts from its connector's evidenceFieldsJson;
     * a row without one shows a blank card. DATABASE is exempt: its fields are whatever the tenant's query returns.
     */
    @Test
    void everyRecordReadingConnectorSaysWhatItsCardShows() {
        List<String> missing = connectorRows("data/SourceSystemConnectorSeedData.xml").findAll { String id, Map<String, String> row ->
            row.enabled == "Y" && row.extractServiceName && id != "DATABASE" && !row.evidenceFieldsJson
        }.keySet().toList()
        assertTrue(missing.isEmpty(), "no evidenceFieldsJson, so a found record's card is blank: ${missing}")
    }

    /**
     * DAR-BE-336: a connector that trims records to keep fields never adds the evidence fields to them, so a status or
     * fact outside keepFieldsBase is gone from every record by conclude time and the card is blank anyway.
     */
    @Test
    void evidenceOnATrimmedConnectorReadsOnlyKeptFields() {
        connectorRows("data/SourceSystemConnectorSeedData.xml").each { String id, Map<String, String> row ->
            if (!row.keepFieldsParameterName || !row.evidenceFieldsJson) return
            Set<String> kept = (row.keepFieldsBase ?: "").split(",")*.trim().findAll { it } as Set<String>
            Map evidence = (Map) new groovy.json.JsonSlurper().parseText(row.evidenceFieldsJson)
            List<String> read = ([evidence.state] + (List) (evidence.facts ?: [])).findAll { it }*.toString()
            assertTrue(kept.containsAll(read), "${id}: evidence reads ${read - kept} which keepFieldsBase trims away")
        }
    }

    /**
     * DAR-BE-336: the upgrade pack's evidence rows are the seed's, or an existing DB gets a different card than a fresh one.
     * They shipped in 3.4.0's pack; data/upgrade-data.xml is the CURRENT release's load target and is rebuilt every cut,
     * so the check reads the archived pack (it failed from the 3.4.1 cut on while reading the current one).
     */
    @Test
    void upgradePackEvidenceMatchesTheSeed() {
        Map<String, Map<String, String>> seed = connectorRows("data/SourceSystemConnectorSeedData.xml")
        ["OMS_GQL", "OMS_RETURNS_GQL", "SHOPIFY_RETURN_REFS"].each { String id ->
            Map<String, String> pack = connectorRows("data/releases/3.4.0/upgrade-data.xml").get(id)
            assertTrue(pack != null, "${id} missing from the upgrade pack")
            assertTrue(pack.evidenceFieldsJson == seed.get(id).evidenceFieldsJson, "${id}: pack evidence differs from the seed")
        }
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

    /**
     * DAR-OPS-029 S03b: every cause the brain pack concludes has its label in the seed (core owns the words, DAR-BE-160), or a
     * new cause reaches prod as its raw code. A release must also carry the new row in its upgrade pack.
     */
    @Test
    void everyCauseTheBrainConcludesIsSeeded() {
        String drl = ConclusionEntityContractTests.classLoader.getResourceAsStream("darpan/conclusion/brain/oms-netsuite.drl").getText("UTF-8")
        Set<String> concluded = (drl =~ /@concludes\("([A-Z0-9_]+)"\)/).collect { ((List) it)[1] as String } as Set<String>
        Set<String> seeded = (read("data/ConclusionCatalogSeedData.xml") =~ /enumId="([A-Z0-9_]+)" enumTypeId="DarpanConclusion"/)
                .collect { ((List) it)[1] as String } as Set<String>
        assertTrue(concluded.size() >= 20, "${concluded}")
        Set<String> unseeded = concluded.findAll { !seeded.contains(it) } as Set<String>
        assertTrue(unseeded.isEmpty(), "brain causes with no seeded label: ${unseeded}")
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
