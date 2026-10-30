package darpan.config

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-160 phase A. A config version holds the tenant's lifecycles (Aditi 2026-10-30: "in use, version them too"): the
 * lifecycle, its active questions, the runs it owns and the automations that run it. A lifecycle rebuild mints new ids
 * and turns the old questions off, so a restore writes rows back by their own ids, into the tenant they came from: what
 * the version holds is switched on and put back, what it does not hold is switched off, nothing is deleted.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantConfigLifecycleSmokeTests {

    private static final String FACADE = "facade.TenantConfigFacadeServices"
    private static final String MINE = "KREWE"                // seedCompanyScope's tenant
    private static final String LIFECYCLE = "darpan.reconciliation.Reconciliation"
    private static final String QUESTION = "darpan.reconciliation.ReconciliationRun"
    private static final String RULE_SET = "darpan.rule.RuleSet"
    private static final String AUTOMATION = "darpan.reconciliation.ReconciliationAutomation"
    private static final String LC = "TCL_LC"
    private static final String RULES_V1 = '[{"conclusionEnumId":"TCL_CONC","places":["Not invoiced"],"conditions":[]}]'

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "tenant-config-lifecycle")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/DarpanSystemSourceSeedData.xml", "component://darpan/data/AutomationSeedData.xml",
                "component://darpan/data/SourceSystemConnectorSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        asSystem {
            upsert("moqui.basic.EnumerationType", [enumTypeId: "DarpanConclusion", description: "Run result conclusion"])
            upsert("moqui.basic.Enumeration", [enumId: "TCL_CONC", enumTypeId: "DarpanConclusion", enumCode: "TCL_CONC",
                    description: "Billed in NetSuite, never invoiced"])
        }
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    /**
     * One stages lifecycle: START (owned run RS_TCL_START) and one finding question under it (owned run RS_TCL_INVOICE),
     * its conclusion rules, an automation that runs it, and a plain run beside it.
     */
    @BeforeEach
    void reset() {
        ec.message.clearErrors()
        asSystem {
            ec.entity.find("darpan.config.TenantConfigVersion").deleteAll()
            ec.entity.find(AUTOMATION).condition("companyUserGroupId", MINE).deleteAll()
            ec.entity.find(QUESTION).condition("companyUserGroupId", MINE).list().sort { -(it.runSequence ?: 0L) }.each { it.delete() }
            ec.entity.find(LIFECYCLE).condition("companyUserGroupId", MINE).deleteAll()
            ec.entity.find(RULE_SET).condition("companyUserGroupId", MINE).deleteAll()
            upsert(LIFECYCLE, [reconciliationId: LC, reconciliationName: "Sales orders", companyUserGroupId: MINE,
                    buildMode: "STAGES", conclusionRules: RULES_V1, notFindingPlaces: '["Closed"]'])
            [["RS_TCL_START", "Sales orders: start"], ["RS_TCL_INVOICE", "Sales orders: invoiced"]].each { List<String> r ->
                upsert(RULE_SET, [ruleSetId: r[0], ruleSetName: r[1], companyUserGroupId: MINE, ownerReconciliationId: LC])
            }
            upsert(RULE_SET, [ruleSetId: "RS_TCL_PLAIN", ruleSetName: "Plain run", companyUserGroupId: MINE])
            upsert(QUESTION, [reconciliationRunId: "TCL_Q1", reconciliationId: LC, companyUserGroupId: MINE, runName: "Start",
                    ruleSetId: "RS_TCL_START", runSequence: 1L, questionRole: "START", isActive: "Y"])
            upsert(QUESTION, [reconciliationRunId: "TCL_Q2", reconciliationId: LC, companyUserGroupId: MINE, runName: "Invoiced?",
                    ruleSetId: "RS_TCL_INVOICE", runSequence: 2L, parentReconciliationRunId: "TCL_Q1", parentBranch: "YES",
                    questionRole: "FINDING", noOutcomeLabel: "Not invoiced", isActive: "Y"])
            upsert(AUTOMATION, [automationId: "TCL_AUTO", automationName: "Sales orders every morning", companyUserGroupId: MINE,
                    inputModeEnumId: "AUT_IN_API_RANGE", savedRunType: "reconciliation", savedRunId: LC,
                    scheduleExpr: "0 0 6 * * ?", windowTimeZone: "America/Los_Angeles", isActive: "Y"])
        }
    }

    @Test
    void aVersionHoldsTheLifecycleItsQuestionsOwnedRunsAndAutomation() {
        Map version = snapshot()
        assertEquals(1, version.counts.lifecycles, "${version.counts}")
        Map pack = (Map) call("get#TenantConfigVersion", [id: version.id]).version.pack
        Map lifecycle = (pack.lifecycles as List<Map>)[0]
        assertEquals("LIFECYCLE:${LC}".toString(), lifecycle.ref)
        assertTrue((lifecycle.fields.conclusionRules as String).contains('"CONC:TCL_CONC"'), "codes travel as refs: ${lifecycle.fields}")
        List<Map> questions = lifecycle.children.ReconciliationRun as List<Map>
        assertEquals(["QUESTION:TCL_Q1", "QUESTION:TCL_Q2"], questions*.ref)
        assertEquals("QUESTION:TCL_Q1", questions[1].refs.parentReconciliationRunId)
        assertEquals("RUN:RS_TCL_INVOICE", questions[1].refs.ruleSetId)
        Map owned = (pack.runs as List<Map>).find { it.ref == "RUN:RS_TCL_INVOICE" }
        assertNotNull(owned, "a run the lifecycle owns is in the version")
        assertEquals("LIFECYCLE:${LC}".toString(), owned.ownedBy)
        Map automation = (pack.automations as List<Map>).find { it.ref == "AUTOMATION:TCL_AUTO" }
        assertEquals("LIFECYCLE:${LC}".toString(), automation?.refs?.savedRunId, "a lifecycle's automation names the lifecycle")
    }

    @Test
    void aRebuiltLifecycleGoesBackByItsOwnIds() {
        Map v1 = snapshot()
        asSystem {
            // What a stage rebuild does: the old question off, a new one (and its new owned run) on, new rules.
            upsert(RULE_SET, [ruleSetId: "RS_TCL_SHIP", ruleSetName: "Sales orders: shipped", companyUserGroupId: MINE,
                    ownerReconciliationId: LC])
            ec.entity.find(QUESTION).condition("reconciliationRunId", "TCL_Q2").one().set("isActive", "N").update()
            upsert(QUESTION, [reconciliationRunId: "TCL_Q3", reconciliationId: LC, companyUserGroupId: MINE, runName: "Shipped?",
                    ruleSetId: "RS_TCL_SHIP", runSequence: 3L, parentReconciliationRunId: "TCL_Q1", parentBranch: "YES",
                    questionRole: "FINDING", noOutcomeLabel: "Not shipped", isActive: "Y"])
            ec.entity.find(LIFECYCLE).condition("reconciliationId", LC).one()
                    .set("conclusionRules", '[{"conclusionEnumId":"TCL_CONC","places":["Not shipped"],"conditions":[]}]').update()
        }
        Map preview = call("preview#TenantConfigRestore", [id: v1.id])
        assertFalse(preview.hadError as boolean, preview.errorText as String)
        assertEquals([], preview.plan.blocking)
        assertEquals("UPDATE", (preview.plan.lines as List<Map>).find { it.kind == "LIFECYCLE" }?.action, "${preview.plan.lines}")

        Map out = call("restore#TenantConfigVersion", [id: v1.id, planHash: preview.planHash])
        assertTrue(out.restored as boolean, out.errorText as String)
        asSystem {
            assertEquals("Y", ec.entity.find(QUESTION).condition("reconciliationRunId", "TCL_Q2").one().isActive)
            assertEquals("N", ec.entity.find(QUESTION).condition("reconciliationRunId", "TCL_Q3").one().isActive, "switched off, not deleted")
            assertEquals(RULES_V1, ec.entity.find(LIFECYCLE).condition("reconciliationId", LC).one().conclusionRules)
        }
        assertEquals(v1.hash, out.after.hash, "exactly version 1 again")
    }

    @Test
    void aDeletedLifecycleComesBackWithItsIdsRunsAndAutomation() {
        Map v1 = snapshot()
        asSystem {
            ec.entity.find(AUTOMATION).condition("automationId", "TCL_AUTO").one().delete()
            ["TCL_Q2", "TCL_Q1"].each { ec.entity.find(QUESTION).condition("reconciliationRunId", it).one().delete() }
            ec.entity.find(RULE_SET).condition("ruleSetId", "RS_TCL_INVOICE").one().delete()
            ec.entity.find(LIFECYCLE).condition("reconciliationId", LC).one().delete()
        }
        Map preview = call("preview#TenantConfigRestore", [id: v1.id])
        assertEquals([], preview.plan.blocking, "${preview.plan}")
        Map out = call("restore#TenantConfigVersion", [id: v1.id, planHash: preview.planHash])
        assertTrue(out.restored as boolean, out.errorText as String)
        asSystem {
            assertNotNull(ec.entity.find(LIFECYCLE).condition("reconciliationId", LC).one(), "back under its own id")
            assertEquals("TCL_Q1", ec.entity.find(QUESTION).condition("reconciliationRunId", "TCL_Q2").one().parentReconciliationRunId)
            assertEquals(LC, ec.entity.find(RULE_SET).condition("ruleSetId", "RS_TCL_INVOICE").one().ownerReconciliationId,
                    "the owned run is the lifecycle's again")
            def automation = ec.entity.find(AUTOMATION).condition("automationId", "TCL_AUTO").one()
            assertEquals(LC, automation.savedRunId)
            assertEquals("N", automation.isActive, "an automation a restore brings back starts paused")
        }
        assertEquals(v1.hash, out.after.hash)
    }

    @Test
    void aRenamedRunGetsItsNameBackRatherThanACopy() {
        Map v1 = snapshot()
        asSystem { ec.entity.find(RULE_SET).condition("ruleSetId", "RS_TCL_PLAIN").one().set("ruleSetName", "Plain run, renamed").update() }
        Map preview = call("preview#TenantConfigRestore", [id: v1.id])
        Map out = call("restore#TenantConfigVersion", [id: v1.id, planHash: preview.planHash])
        assertTrue(out.restored as boolean, out.errorText as String)
        asSystem {
            List named = ec.entity.find(RULE_SET).condition("companyUserGroupId", MINE).condition("ruleSetName", "Plain run").list()
            assertEquals(["RS_TCL_PLAIN"], named*.ruleSetId, "the same run, renamed back; no second run")
        }
    }

    @Test
    void aLifecycleAddedSinceIsKeptAndListed() {
        Map v1 = snapshot()
        asSystem {
            upsert(LIFECYCLE, [reconciliationId: "TCL_LATER", reconciliationName: "Returns", companyUserGroupId: MINE, buildMode: "STAGES"])
        }
        Map preview = call("preview#TenantConfigRestore", [id: v1.id])
        assertTrue((preview.plan.kept as List<Map>).contains([kind: "LIFECYCLE", label: "Returns"]), "${preview.plan.kept}")
        Map out = call("restore#TenantConfigVersion", [id: v1.id, planHash: preview.planHash])
        assertTrue(out.restored as boolean, out.errorText as String)
        assertNotNull(asSystem { ec.entity.find(LIFECYCLE).condition("reconciliationId", "TCL_LATER").one() }, "kept, not deleted")
    }

    /**
     * Security review 2026-10-30: same-tenant restore (match by id, raw-id refs) must never be switched on by the bundle.
     * An admin import of a bundle that CLAIMS to come from the target tenant still matches by name.
     */
    @Test
    void aBundleCannotClaimSameTenantTrust() {
        Map v1 = snapshot()
        Map pack = (Map) call("get#TenantConfigVersion", [id: v1.id]).version.pack
        asSystem { ec.entity.find(RULE_SET).condition("ruleSetId", "RS_TCL_PLAIN").one().set("ruleSetName", "Plain run, renamed").update() }
        Map plan = asSystem { darpan.admin.transfer.ConfigTransferImporter.plan(ec, MINE, pack, sameConnections(pack)) }
        Map plain = (plan.lines as List<Map>).find { it.ref == "RUN:RS_TCL_PLAIN" }
        assertEquals("CREATE", plain?.action, "the admin path matches by name, whatever bundle.source says: ${plain}")
    }

    @Test
    void aQuestionRefTheBundleDoesNotCarryIsRefused() {
        Map v1 = snapshot()
        Map pack = (Map) call("get#TenantConfigVersion", [id: v1.id]).version.pack
        Map q2 = ((pack.lifecycles as List<Map>)[0].children.ReconciliationRun as List<Map>)[1]
        q2.refs.parentReconciliationRunId = "QUESTION:SOMEONE_ELSES_QUESTION"
        Map plan = asSystem { darpan.admin.transfer.ConfigTransferImporter.plan(ec, MINE, pack, sameConnections(pack)) }
        assertTrue((plan.blocking as List<Map>).any { it.code == "REF_UNRESOLVED" }, "a question ref must be carried: ${plan.blocking}")
    }

    private static Map<String, String> sameConnections(Map pack) {
        return TenantConfigRestoreSupport.sameConnections(pack)
    }

    private Map snapshot() {
        Map out = call("snapshot#TenantConfig")
        assertFalse(out.hadError as boolean, out.errorText as String)
        return (Map) out.version
    }

    private Map call(String verbNoun, Map params = [:]) {
        Map out = ec.service.sync().name("${FACADE}.${verbNoun}").parameters(params).call() ?: [:]
        Map result = out + [hadError: ec.message.hasError(), errorText: ec.message.errorsString]
        ec.message.clearErrors()
        return result
    }

    private <T> T asSystem(Closure<T> work) {
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            return work.call()
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    private void upsert(String entityName, Map<String, Object> fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }
}
