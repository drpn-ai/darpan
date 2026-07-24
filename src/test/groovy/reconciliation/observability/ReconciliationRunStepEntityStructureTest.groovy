package reconciliation.observability

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.xml.XmlParser
import org.junit.jupiter.api.Test

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Structure ratchet for the observability schema. Parses ReconciliationEntities.xml directly
 * (no live EC) and enforces: the new step entity + fields exist, and the MySQL row-budget guard —
 * ReconciliationRunResult must never carry more than 4 text-long fields (InnoDB 65535-byte row
 * limit fails CREATE TABLE on MySQL while passing on H2).
 */
class ReconciliationRunStepEntityStructureTest {

    private static Node entitiesRoot() {
        Path xml = ReconciliationSmokeTestSupport.resolveBackendRoot()
                .resolve("runtime/component/darpan/entity/ReconciliationEntities.xml")
        return new XmlParser().parse(xml.toFile())
    }

    private static Node entity(Node root, String name) {
        return root.entity.find { it.@'entity-name' == name } as Node
    }

    private static Set<String> fieldNames(Node entity) {
        return entity.field.collect { it.@name }.toSet() as Set<String>
    }

    @Test
    void runStepEntityHasTimelineFields() {
        Node root = entitiesRoot()
        Node step = entity(root, "ReconciliationRunStep")
        assertNotNull(step, "ReconciliationRunStep entity must be defined")
        assertEquals("transactional", step.@use)
        assertEquals("never", step.@cache)

        Set<String> f = fieldNames(step)
        ["reconciliationRunStepId", "reconciliationRunResultId", "companyUserGroupId", "stageCode",
         "stageSequence", "statusEnumId", "startedDate", "completedDate", "heartbeatDate",
         "deadlineDate", "recordCount", "errorMessage", "errorDetail", "metricsJson"].each {
            assertTrue(f.contains(it), "ReconciliationRunStep missing field ${it}")
        }
        Node pk = step.field.find { it.@name == "reconciliationRunStepId" } as Node
        assertEquals("true", pk.@'is-pk')
    }

    @Test
    void runResultHasCurrentAndErrorFields() {
        Node result = entity(entitiesRoot(), "ReconciliationRunResult")
        Set<String> f = fieldNames(result)
        ["currentStage", "lastHeartbeatDate", "progressPercent", "errorMessage", "errorDetail",
         "notifiedDate"].each {
            assertTrue(f.contains(it), "ReconciliationRunResult missing field ${it}")
        }
    }

    @Test
    void runResultRespectsMysqlTextLongRowBudget() {
        Node result = entity(entitiesRoot(), "ReconciliationRunResult")
        List<String> textLong = result.field.findAll { it.@type == "text-long" }.collect { it.@name }
        assertTrue(textLong.size() <= 4,
                "ReconciliationRunResult has ${textLong.size()} text-long fields (${textLong}); " +
                "MySQL InnoDB row limit allows ~4. errorMessage must be text-medium, errorDetail text-very-long.")
        Node errorMessage = result.field.find { it.@name == "errorMessage" } as Node
        assertEquals("text-medium", errorMessage.@type, "errorMessage must be text-medium, not text-long")
    }
}
