package reconciliation.rule

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import darpan.reconciliation.support.SharedMoqui
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull

/**
 * DAR-OPS-029 S02 (F3, F4). save#Rule, delete#Rule and delete#RuleSet act only on the active tenant's rule sets, even
 * under disable-authz (where the entity filter is skipped). save#Rule read the parent with disableAuthz and no tenant
 * check, so the legacy Rule Engine screen could add a rule to any tenant's rule set by id. Another tenant's rule set
 * answers exactly as a missing one, and nothing is written.
 */
@SharedMoqui
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReconciliationRuleEngineServicesTenantSmokeTests {
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "rule-engine-services-tenant")
        ReconciliationSmokeTestSupport.seedBaseCompareRuleSet(ec)
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void tenant() {
        ec.message.clearErrors()
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: "GORJANA", description: "Gorjana",
                groupTypeEnumId: darpan.facade.common.TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID]).createOrUpdate()
        ec.entity.makeValue("darpan.rule.RuleSet").setAll([ruleSetId: "RS_GORJANA_ONLY", ruleSetName: "Gorjana run",
                companyUserGroupId: "GORJANA"]).createOrUpdate()
        ec.entity.makeValue("darpan.rule.Rule").setAll([ruleId: "GORJANA_RULE", ruleSetId: "RS_GORJANA_ONLY", sequenceNum: 10,
                ruleText: "status is Pending", enabled: "Y", companyUserGroupId: "GORJANA"]).createOrUpdate()
        ec.message.clearErrors()
    }

    private List<String> call(String service, Map params) {
        ec.message.clearErrors()
        ec.service.sync().name("reconciliation.ReconciliationRuleEngineServices.${service}").parameters(params).disableAuthz().call()
        List<String> errors = new ArrayList<String>(ec.message.errors)
        ec.message.clearErrors()
        return errors
    }

    private long rulesOf(String ruleSetId) {
        return ec.entity.find("darpan.rule.Rule").condition("ruleSetId", ruleSetId).disableAuthz().useCache(false).count()
    }

    @Test
    void saveRuleOnAnotherTenantsRuleSetAnswersNotFoundAndWritesNothing() {
        List<String> foreign = call("save#Rule", [ruleSetId: "RS_GORJANA_ONLY", ruleText: "status is Shipped"])
        List<String> missing = call("save#Rule", [ruleSetId: "RS_NO_SUCH_SET", ruleText: "status is Shipped"])
        assertEquals(["RuleSet RS_GORJANA_ONLY not found"], foreign)
        assertEquals(["RuleSet RS_NO_SUCH_SET not found"], missing)
        assertEquals(1L, rulesOf("RS_GORJANA_ONLY"), "a rule was added to another tenant's rule set")
        // The active tenant's own rule set still takes a rule.
        assertEquals([], call("save#Rule", [ruleSetId: "DARPAN_TEST_COMPARE_RS", ruleText: "status is Shipped"]))
    }

    @Test
    void deleteRuleOfAnotherTenantAnswersNotFoundAndDeletesNothing() {
        assertEquals(["Rule GORJANA_RULE not found"], call("delete#Rule", [ruleId: "GORJANA_RULE"]))
        assertNotNull(ec.entity.find("darpan.rule.Rule").condition("ruleId", "GORJANA_RULE").disableAuthz().useCache(false).one())
        // Positive control: the active tenant's own rule still deletes.
        Map own = ec.service.sync().name("reconciliation.ReconciliationRuleEngineServices.save#Rule")
                .parameters([ruleSetId: "DARPAN_TEST_COMPARE_RS", ruleText: "status is Held"]).disableAuthz().call()
        assertEquals([], call("delete#Rule", [ruleId: own.ruleId]))
        assertEquals(null, ec.entity.find("darpan.rule.Rule").condition("ruleId", own.ruleId).disableAuthz().useCache(false).one())
    }

    @Test
    void deleteAnotherTenantsRuleSetAnswersNotFoundAndDeletesNothing() {
        assertEquals(["RuleSet RS_GORJANA_ONLY not found"], call("delete#RuleSet", [ruleSetId: "RS_GORJANA_ONLY", forceDeleteRules: true]))
        assertNotNull(ec.entity.find("darpan.rule.RuleSet").condition("ruleSetId", "RS_GORJANA_ONLY").disableAuthz().useCache(false).one())
        assertEquals(1L, rulesOf("RS_GORJANA_ONLY"))
        // Positive control: the active tenant's own rule set still deletes.
        Map own = ec.service.sync().name("reconciliation.ReconciliationRuleEngineServices.save#RuleSet")
                .parameters([ruleSetName: "Own run"]).disableAuthz().call()
        assertEquals([], call("delete#RuleSet", [ruleSetId: own.ruleSetId, forceDeleteRules: true]))
        assertEquals(null, ec.entity.find("darpan.rule.RuleSet").condition("ruleSetId", own.ruleSetId).disableAuthz().useCache(false).one())
        assertFalse(ec.message.hasError())
    }

    /** QA refute: save#RuleSet relied on the entity filter too, and stamped no tenant on a new rule set. */
    @Test
    void saveRuleSetStampsTheActiveTenantAndNeverOverwritesAnotherTenants() {
        assertEquals(["RuleSet RS_GORJANA_ONLY not found"], call("save#RuleSet", [ruleSetId: "RS_GORJANA_ONLY", ruleSetName: "Hijacked"]))
        assertEquals("Gorjana run", ec.entity.find("darpan.rule.RuleSet").condition("ruleSetId", "RS_GORJANA_ONLY")
                .disableAuthz().useCache(false).one().ruleSetName)
        Map created = ec.service.sync().name("reconciliation.ReconciliationRuleEngineServices.save#RuleSet")
                .parameters([ruleSetName: "Stamped run"]).disableAuthz().call()
        assertEquals("KREWE", ec.entity.find("darpan.rule.RuleSet").condition("ruleSetId", created.ruleSetId)
                .disableAuthz().useCache(false).one().companyUserGroupId)
    }
}
