package darpan.rules

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-318. The moqui-rules component is on the build path; Darpan reaches it through services only. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MoquiRulesWiringTests {

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "moqui-rules-wiring")
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @Test
    void rulesEntitiesAndServicesAreDefined() {
        assertTrue(ec.entity.isEntityDefined("rules.RuleSet"))
        assertTrue(ec.service.isServiceDefined("moqui.rules.RuleServices.evaluate#RuleSet"))
    }

    @Test
    void rulesTablesDoNotCollideWithDarpansRuleSet() {
        assertEquals("RULES_RULE_SET", ec.entity.getEntityDefinition("rules.RuleSet").getTableName())
        assertEquals("RULE_SET", ec.entity.getEntityDefinition("darpan.rule.RuleSet").getTableName())
    }
}
