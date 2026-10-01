package darpan.reconciliation.flowchart

import groovy.xml.XmlParser
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-UI-048. The flowchart's columns exist, are nullable with no default (so every row written before
 * them reads as a flat, single-rule run), and the NOT_RUN status is seeded for fresh and upgraded
 * databases alike. XML only: no Moqui, unitTest pool.
 */
class RunFlowchartSchemaContractTests {

    @Test
    void flowchartColumnsExistWithNoDefault() {
        def entities = parse("entity/ReconciliationEntities.xml").entity
        assertNullableNoDefault(entity(entities, "Reconciliation"),
                ["companyUserGroupId", "isArchived", "defaultTimeWindow"])
        assertNullableNoDefault(entity(entities, "ReconciliationRun"),
                ["companyUserGroupId", "isActive", "parentReconciliationRunId", "parentBranch",
                 "questionRole", "noOutcomeLabel"])
        assertNullableNoDefault(entity(entities, "ReconciliationRunResult"),
                ["reconciliationExecutionId", "parentRunResultId", "yesCount", "noCount", "unaskedCount"])
    }

    @Test
    void parentLinkIsARelationshipToTheSameEntity() {
        def run = entity(parse("entity/ReconciliationEntities.xml").entity, "ReconciliationRun")
        def rel = run.relationship.find { attr(it, "title") == "Parent" }
        assertNotNull(rel, "ReconciliationRun needs a 'Parent' relationship")
        assertEquals("darpan.reconciliation.ReconciliationRun", attr(rel, "related"))
        def keyMap = rel."key-map"[0]
        assertEquals("parentReconciliationRunId", attr(keyMap, "field-name"))
        assertEquals("reconciliationRunId", attr(keyMap, "related-field-name"))
    }

    @Test
    void notRunStatusIsSeededAndInTheUpgradePack() {
        ["data/AutomationSeedData.xml", "data/upgrade-data.xml"].each { String path ->
            def row = parse(path)."moqui.basic.Enumeration".find { attr(it, "enumId") == "AUT_STAT_NOT_RUN" }
            assertNotNull(row, "${path} has no AUT_STAT_NOT_RUN")
            assertEquals("AutomationExecStatus", attr(row, "enumTypeId"))
            assertTrue((attr(row, "description") ?: "").trim().length() > 0)
        }
    }

    private static void assertNullableNoDefault(def entityNode, List<String> names) {
        names.each { String name ->
            def field = entityNode.field.find { attr(it, "name") == name }
            assertNotNull(field, "Missing ${attr(entityNode, 'entity-name')}.${name}")
            assertNull(attr(field, "default"), "${name} must have no default attribute")
            assertNull(attr(field, "not-null"), "${name} must be nullable")
        }
    }

    private static def entity(def entities, String name) {
        def node = entities.find { attr(it, "entity-name") == name }
        assertNotNull(node, "entity ${name} not found")
        return node
    }

    private static String attr(def node, String name) { return node.attributes().get(name) as String }

    private static def parse(String relativePath) {
        return new XmlParser(false, false).parse(componentRoot().resolve(relativePath).toFile())
    }

    private static Path componentRoot() {
        Path cwd = Paths.get("").toAbsolutePath().normalize()
        return [cwd, cwd.resolve("runtime/component/darpan"), cwd.resolve("darpan-backend/runtime/component/darpan")]
                .find { Files.exists(it.resolve("entity/ReconciliationEntities.xml")) }
    }
}
