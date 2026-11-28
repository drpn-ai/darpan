package darpan.config

import darpan.reconciliation.support.SharedMoqui
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonSlurper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

import static org.junit.jupiter.api.Assertions.*
import static darpan.reconciliation.support.TestHelpers.asSystem
import static darpan.reconciliation.support.TestHelpers.upsert

/**
 * DAR-BE-160 phase A. A config version holds the tenant's automations too, because the morning audit runs an automation's
 * own copy of its run's sources and exclusion filters (and its schedule), not the run's. So a restore puts an
 * automation's sources, filters and schedule back. Whether it is paused stays as it is: that is an operational switch,
 * as are its last and next firing, so neither makes a version.
 */
@SharedMoqui
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantConfigAutomationSmokeTests {

    private static final String FACADE = "facade.TenantConfigFacadeServices"
    private static final String VERSION = "darpan.config.TenantConfigVersion"
    private static final String PENDING = "darpan.config.TenantConfigPending"
    private static final String AUTOMATION = "darpan.reconciliation.ReconciliationAutomation"
    private static final String FILTER = "darpan.reconciliation.ReconciliationAutomationSourceFilter"
    private static final String MINE = "KREWE"                // seedCompanyScope's tenant
    private static final String AUTO_ID = "TCAU_AUTO"
    private static final String RUN_ID = "RS_TCAU"
    private static final String QUERY_ID = "TCAU_QUERY"
    private static final String MORNING = "0 0 6 * * ?"

    private ExecutionContext ec
    /** S99-211 F32: the pending marks the saves sent, recorded instead of sent async (no sleeping for a thread). */
    private final List<Map> marks = Collections.synchronizedList([])

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "tenant-config-automation")
        // After the boot: the shared harness's StaticResets would put the async default back.
        TenantConfigChangeTracker.dispatch = { def ecf, Map params -> marks << new LinkedHashMap(params) }
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/DarpanSystemSourceSeedData.xml", "component://darpan/data/AutomationSeedData.xml",
                "component://darpan/data/SourceSystemConnectorSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        asSystem(ec) {
            upsert(ec, "darpan.rule.RuleSet", [ruleSetId: RUN_ID, ruleSetName: "Morning invoices", companyUserGroupId: MINE])
            // Used by the automation alone: a version must still hold it.
            upsert(ec, "darpan.reconciliation.NsSuiteQlSourceQuery", [nsSuiteQlSourceQueryId: QUERY_ID, companyUserGroupId: MINE,
                    description: "Invoices with no Etail id", recordType: "invoice"])
        }
    }

    @AfterAll
    void cleanup() {
        TenantConfigChangeTracker.resetForTests()
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    /** One paused-then-on automation of the run: a SuiteQL source naming the query, with one exclusion filter. */
    @BeforeEach
    void reset() {
        ec.message.clearErrors()
        asSystem(ec) {
            ec.entity.find(VERSION).deleteAll()
            ec.entity.find(FILTER).condition("companyUserGroupId", MINE).deleteAll()
            ec.entity.find("darpan.reconciliation.ReconciliationAutomationSource").condition("companyUserGroupId", MINE).deleteAll()
            ec.entity.find(AUTOMATION).condition("companyUserGroupId", MINE).deleteAll()
            upsert(ec, AUTOMATION, [automationId: AUTO_ID, automationName: "Morning invoices", companyUserGroupId: MINE,
                    inputModeEnumId: "AUT_IN_API_RANGE", savedRunId: RUN_ID, scheduleExpr: MORNING, windowTimeZone: "America/Los_Angeles",
                    isActive: "Y", nextScheduledFireTime: Timestamp.valueOf("2026-10-31 13:00:00")])
            upsert(ec, "darpan.reconciliation.ReconciliationAutomationSource", [automationId: AUTO_ID, fileSide: "FILE_1",
                    companyUserGroupId: MINE, sourceTypeEnumId: "AUT_SRC_API", systemEnumId: "NETSUITE_SUITEQL",
                    safeMetadataJson: '{"parameters":{"nsSuiteQlSourceQueryId":"' + QUERY_ID + '"}}'])
            upsert(ec, FILTER, [automationId: AUTO_ID, fileSide: "FILE_1", sequenceNum: 1L, companyUserGroupId: MINE,
                    fieldExpression: "status", operator: "EXCLUDE_IN", filterValues: '["Closed"]'])
        }
        asSystem(ec) { ec.entity.find(PENDING).deleteAll() }
        marks.clear()   // the fixture's own writes marked KREWE: recorded, never sent
    }

    @Test
    void aVersionHoldsTheAutomationItsSourcesFiltersAndTheQueryItReads() {
        Map version = snapshot()
        assertEquals(1, version.counts.automations, "${version.counts}")
        Map pack = (Map) call("get#TenantConfigVersion", [id: version.id]).version.pack
        Map automation = (pack.automations as List<Map>).find { it.label == "Morning invoices" }
        assertNotNull(automation, "the automation is in the version: ${pack.automations}")
        assertEquals("RUN:${RUN_ID}".toString(), automation.refs.savedRunId)
        assertEquals(MORNING, automation.fields.scheduleExpr)
        ["isActive", "nextScheduledFireTime", "lastScheduledFireTime"].each {
            assertFalse(automation.fields.containsKey(it), "${it} is operational, not config")
        }
        Map source = (automation.children.ReconciliationAutomationSource as List<Map>)[0]
        Map metadata = (Map) new JsonSlurper().parseText(source.fields.safeMetadataJson as String)
        assertEquals("NETSUITE_SUITEQL:${QUERY_ID}".toString(), metadata.parameters.nsSuiteQlSourceQueryId,
                "the query the source reads travels as a ref")
        assertEquals([['Closed']], (source.children.ReconciliationAutomationSourceFilter as List<Map>)
                .collect { new JsonSlurper().parseText(it.fields.filterValues as String) })
        assertTrue((pack.queries as List<Map>)*.ref.contains("NETSUITE_SUITEQL:${QUERY_ID}".toString()),
                "a query only an automation reads is still in the version")
    }

    @Test
    void firingOrPausingAnAutomationIsNotAConfigChange() {
        Map first = snapshot()
        inTransaction {
            asSystem(ec) {
                ec.entity.find(AUTOMATION).condition("automationId", AUTO_ID).one().setAll([isActive: "N",
                        lastScheduledFireTime: Timestamp.valueOf("2026-10-30 13:00:00"),
                        nextScheduledFireTime: Timestamp.valueOf("2026-11-01 13:00:00")]).update()
            }
        }
        assertEquals([], marks, "pausing or firing sends no pending mark")
        assertNull(asSystem(ec) { ec.entity.find(PENDING).condition("companyUserGroupId", MINE).one() }, "no version is owed")
        Map again = call("snapshot#TenantConfig")
        assertFalse(again.created as boolean)
        assertEquals(first.id, again.version.id)
        // Positive control: a real config change on the same automation does send one, so the empty list above is meaningful.
        inTransaction {
            asSystem(ec) { ec.entity.find(FILTER).condition("automationId", AUTO_ID).one().set("filterValues", '["Closed","Void"]').update() }
        }
        assertEquals([MINE], marks*.tenantId, "a filter edit marks its tenant once the save commits")
    }

    @Test
    void aRestorePutsTheAutomationsFilterAndScheduleBackAndLeavesItOn() {
        Map v1 = snapshot()
        asSystem(ec) {
            ec.entity.find(FILTER).condition("automationId", AUTO_ID).one().set("filterValues", '["Closed","Cancelled"]').update()
            ec.entity.find(AUTOMATION).condition("automationId", AUTO_ID).one().setAll([scheduleExpr: "0 0 9 * * ?",
                    nextScheduledFireTime: Timestamp.valueOf("2026-10-31 16:00:00")]).update()
        }
        Map preview = call("preview#TenantConfigRestore", [id: v1.id])
        assertFalse(preview.hadError as boolean, preview.errorText as String)
        assertEquals([], preview.plan.blocking)
        Map line = (preview.plan.lines as List<Map>).find { it.kind == "AUTOMATION" }
        assertEquals("UPDATE", line?.action, "the automation goes back: ${preview.plan.lines}")

        Timestamp restoredFrom = new Timestamp(System.currentTimeMillis())
        Map out = call("restore#TenantConfigVersion", [id: v1.id, planHash: preview.planHash])
        assertTrue(out.restored as boolean, out.errorText as String)
        def automation = asSystem(ec) { ec.entity.find(AUTOMATION).condition("automationId", AUTO_ID).one() }
        assertEquals(MORNING, automation.scheduleExpr)
        assertEquals("Y", automation.isActive, "a restore never pauses or starts an automation")
        // The next firing follows the restored schedule: the first 6:00 in the automation's own zone after the restore.
        Timestamp next = automation.nextScheduledFireTime as Timestamp
        assertNotNull(next, "the restore sets the next firing")
        ZonedDateTime nextLocal = next.toInstant().atZone(ZoneId.of("America/Los_Angeles"))
        assertEquals(LocalTime.of(6, 0), nextLocal.toLocalTime(), "6:00 in Los Angeles, the restored schedule: ${nextLocal}")
        assertTrue(next.after(restoredFrom) && next.time - restoredFrom.time <= 86_400_000L,
                "the first 6:00 after the restore, not a later one: ${nextLocal}, restored at ${restoredFrom}")
        assertEquals('["Closed"]', asSystem(ec) { ec.entity.find(FILTER).condition("automationId", AUTO_ID).one().filterValues })
        assertEquals(v1.hash, out.after.hash, "exactly version 1 again")
    }

    @Test
    void anAutomationAddedSinceIsKeptAndListed() {
        Map v1 = snapshot()
        asSystem(ec) {
            upsert(ec, AUTOMATION, [automationId: "TCAU_LATER", automationName: "Evening invoices", companyUserGroupId: MINE,
                    inputModeEnumId: "AUT_IN_API_RANGE", savedRunId: RUN_ID, scheduleExpr: "0 0 18 * * ?", isActive: "N"])
        }
        Map preview = call("preview#TenantConfigRestore", [id: v1.id])
        assertTrue((preview.plan.kept as List<Map>).contains([kind: "AUTOMATION", label: "Evening invoices"]), "${preview.plan.kept}")
        Map out = call("restore#TenantConfigVersion", [id: v1.id, planHash: preview.planHash])
        assertTrue(out.restored as boolean, out.errorText as String)
        assertNotNull(asSystem(ec) { ec.entity.find(AUTOMATION).condition("automationId", "TCAU_LATER").one() }, "kept, not deleted")
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

    private void inTransaction(Closure work) {
        ec.transaction.runRequireNew(60, "test save", work)
    }
}
