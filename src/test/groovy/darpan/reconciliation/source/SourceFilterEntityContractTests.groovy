package darpan.reconciliation.source

import groovy.xml.XmlParser
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

class SourceFilterEntityContractTests {

    private static Node entity(String relativePath, String entityName) {
        Path path = Paths.get(relativePath)
        assertTrue(Files.exists(path), "Missing entity file ${relativePath}")
        Node root = new XmlParser().parse(path.toFile())
        Node found = root.'entity'.find { Node node -> node.@'entity-name' == entityName }
        assertNotNull(found, "Entity ${entityName} not defined in ${relativePath}")
        return found
    }

    private static List<String> pkFieldNames(Node entityNode) {
        return entityNode.'field'.findAll { Node field -> field.@'is-pk' == "true" }.collect { Node field -> field.@name }
    }

    private static Node field(Node entityNode, String fieldName) {
        Node found = entityNode.'field'.find { Node node -> node.@name == fieldName }
        assertNotNull(found, "Field ${fieldName} missing")
        return found
    }

    @Test
    void ruleSetCompareSourceFilterMirrorsTheKeyFieldContract() {
        Node node = entity("entity/RuleEntities.xml", "RuleSetCompareSourceFilter")

        assertEquals("darpan.rule", node.@package)
        assertEquals("configuration", node.@use)
        assertEquals(["compareScopeId", "fileSide", "sequenceNum"], pkFieldNames(node))
        assertEquals("true", field(node, "fieldExpression").@'not-null')
        assertEquals("true", field(node, "filterValues").@'not-null')
        assertEquals("'EXCLUDE_IN'", field(node, "operator").@default)
        // text-long, not text-very-long: a comma-separated enum-id list is small, and MySQL caps a
        // row at ~4 text-long columns. This entity has exactly one.
        assertEquals("text-long", field(node, "filterValues").@type)
        assertNotNull(field(node, "companyUserGroupId"))

        Node parentRel = node.'relationship'.find { Node rel -> rel.@related == "darpan.rule.RuleSetCompareSource" }
        assertNotNull(parentRel, "Missing FK to RuleSetCompareSource")
        assertEquals(["compareScopeId", "fileSide"], parentRel.'key-map'.collect { Node km -> km.@'field-name' })
        assertNotNull(node.'index'.find { Node index -> index.@name == "RSCFL_TENANT" })
    }

    @Test
    void compareSourceExposesExcludeFiltersRelationship() {
        Node node = entity("entity/RuleEntities.xml", "RuleSetCompareSource")

        Node rel = node.'relationship'.find { Node r -> r.@'short-alias' == "excludeFilters" }
        assertNotNull(rel, "RuleSetCompareSource must expose an excludeFilters relationship")
        assertEquals("many", rel.@type)
        assertEquals("darpan.rule.RuleSetCompareSourceFilter", rel.@related)
    }

    @Test
    void automationSourceFilterMirrorsTheRuleSetTable() {
        Node node = entity("entity/ReconciliationEntities.xml", "ReconciliationAutomationSourceFilter")

        assertEquals("darpan.reconciliation", node.@package)
        assertEquals("configuration", node.@use)
        assertEquals(["automationId", "fileSide", "sequenceNum"], pkFieldNames(node))
        assertEquals("true", field(node, "fieldExpression").@'not-null')
        assertEquals("true", field(node, "filterValues").@'not-null')
        assertEquals("'EXCLUDE_IN'", field(node, "operator").@default)
        assertNotNull(field(node, "companyUserGroupId"))
        assertNotNull(field(node, "createdByUserId"))

        Node parentRel = node.'relationship'.find { Node rel -> rel.@related == "darpan.reconciliation.ReconciliationAutomationSource" }
        assertNotNull(parentRel, "Missing FK to ReconciliationAutomationSource")
        assertEquals(["automationId", "fileSide"], parentRel.'key-map'.collect { Node km -> km.@'field-name' })
        assertNotNull(node.'index'.find { Node index -> index.@name == "RECAUTSF_TENANT" })
    }

    @Test
    void neitherTableShipsSeedOrUpgradeRows() {
        // Backward compatibility: zero rows must mean today's behavior. Any seeded row would change
        // an existing tenant's extract silently on upgrade.
        ["data/SourceSystemConnectorSeedData.xml", "data/upgrade-data.xml"].each { String relativePath ->
            String text = Paths.get(relativePath).toFile().text
            assertTrue(!text.contains("RuleSetCompareSourceFilter"),
                    "${relativePath} must not seed RuleSetCompareSourceFilter rows")
            assertTrue(!text.contains("ReconciliationAutomationSourceFilter"),
                    "${relativePath} must not seed ReconciliationAutomationSourceFilter rows")
        }
    }
}
