package darpan.config

import darpan.admin.transfer.TransferSpecs
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-160 phase A. Automatic config versions. A committed write to any table a config version holds marks its tenant
 * pending, once per transaction, after the commit (a rolled-back save marks nothing). A minute job keeps a version for
 * each tenant quiet for 60 seconds, credited to whoever saved last, and writes none when nothing changed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantConfigAutoVersionSmokeTests {

    private static final String SERVICES = "config.TenantConfigServices"
    private static final String VERSION = "darpan.config.TenantConfigVersion"
    private static final String PENDING = "darpan.config.TenantConfigPending"
    private static final String MINE = "KREWE"                // seedCompanyScope's tenant
    private static final String USER = "TEST_CUSTOMER_USER"   // and its editor
    private static final String OTHER = "TCA_OTHER"
    private static final String R2_ID = "${MINE}_R2".toString()
    private static final Timestamp T = Timestamp.valueOf("2026-10-30 10:00:00")

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "tenant-config-auto-version")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/DarpanSystemSourceSeedData.xml", "component://darpan/data/AutomationSeedData.xml",
                "component://darpan/data/SourceSystemConnectorSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        asSystem {
            upsert("moqui.security.UserGroup", [userGroupId: OTHER, description: "Other tenant",
                    groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
            upsert("darpan.audit.Audit", [auditId: "${MINE}_MC".toString(), companyUserGroupId: MINE, auditCode: "month-close",
                    auditName: "Month close", periodEnumId: "AUDP_MONTH"])
            upsert("darpan.audit.AuditStatement", [statementId: R2_ID, companyUserGroupId: MINE, auditId: "${MINE}_MC".toString(),
                    statementCode: "R2", categoryEnumId: "AUDC_ORDERS", weightEnumId: "AUDSW_FIRM", sequenceNum: 2L,
                    statementText: "Every invoice carries the Etail id.", isActive: "Y"])
            upsert("darpan.audit.AuditCheck", [checkId: "${MINE}_INV".toString(), companyUserGroupId: MINE,
                    checkCode: "invoices-without-etail-id", label: "Invoices with no Etail id", graceDays: 1L])
            upsert("darpan.audit.AuditStatementCheck", [checkId: "${MINE}_INV".toString(), statementId: R2_ID,
                    companyUserGroupId: MINE, sequenceNum: 1L])
            upsert("darpan.reconciliation.NsSuiteQlSourceQuery", [nsSuiteQlSourceQueryId: "TCA_QUERY", companyUserGroupId: MINE,
                    description: "Open credit memos", recordType: "creditmemo"])
        }
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void reset() {
        ec.message.clearErrors()
        settle()
        asSystem {
            ec.entity.find(VERSION).deleteAll()
            ec.entity.find(PENDING).deleteAll()
        }
    }

    @Test
    void aCommittedSaveMarksItsTenantPending() {
        inTransaction { setR2Text("Saved once.") }
        Map pending = waitForPending(MINE)
        assertNotNull(pending, "the save marked KREWE")
        assertEquals(USER, pending.lastChangedByUserId)
        assertEquals(1L, pending.changeCount as Long)
        assertNull(pendingOf(OTHER), "only the tenant that saved")
    }

    @Test
    void aRolledBackSaveMarksNothing() {
        assertThrows(RuntimeException) {
            inTransaction {
                setR2Text("Never committed.")
                throw new RuntimeException("the save fails")
            }
        }
        settle()
        assertNull(pendingOf(MINE))
    }

    @Test
    void oneSaveOfManyRowsCountsOnce() {
        inTransaction {
            setR2Text("Three rows, one save.")
            asSystem {
                ec.entity.find("darpan.audit.AuditCheck").condition("checkId", "${MINE}_INV".toString()).one().set("label", "Invoices missing an Etail id").update()
                ec.entity.find("darpan.audit.Audit").condition("auditId", "${MINE}_MC".toString()).one().set("auditName", "Month-end close").update()
            }
        }
        Map pending = waitForPending(MINE)
        assertEquals(1L, pending.changeCount as Long, "one transaction, one mark")
    }

    @Test
    void aRowWithoutATenantColumnIsTracedToItsParent() {
        inTransaction {
            asSystem {
                upsert("darpan.reconciliation.NsSuiteQlSourceQueryField", [nsSuiteQlSourceQueryId: "TCA_QUERY",
                        recordFieldName: "amountUnapplied", sourceColumn: "amountunapplied"])
            }
        }
        assertNotNull(waitForPending(MINE), "a query column marks the query's tenant")
    }

    @Test
    void theJobWaitsUntilTheTenantIsQuiet() {
        markPending(MINE, T)
        Map early = call("snapshot#PendingTenantConfigs", [nowStamp: new Timestamp(T.time + 30_000L)])
        assertEquals("NOT_QUIET", outcomeOf(early, MINE))
        assertEquals(0L, versionCount())
        assertNotNull(pendingOf(MINE))

        Map quiet = call("snapshot#PendingTenantConfigs", [nowStamp: new Timestamp(T.time + 61_000L)])
        assertEquals("VERSIONED", outcomeOf(quiet, MINE))
        def version = asSystem { ec.entity.find(VERSION).condition("companyUserGroupId", MINE).one() }
        assertEquals("SETTINGS_SAVE", version.changeSource)
        assertEquals(USER, version.createdByUserId, "credited to whoever saved last")
        assertNull(pendingOf(MINE), "done")
    }

    @Test
    void aSaveThatChangedNothingWritesNoVersion() {
        Map first = TenantConfigVersionSupport.snapshot(ec, MINE, "baseline")
        assertNotNull(first, ec.message.errorsString)
        markPending(MINE, T)
        Map out = call("snapshot#PendingTenantConfigs", [nowStamp: new Timestamp(T.time + 61_000L)])
        assertEquals("UNCHANGED", outcomeOf(out, MINE))
        assertEquals(1L, versionCount())
        assertNull(pendingOf(MINE))
    }

    @Test
    void aTenantThatFailsBacksOffAndSaysWhy() {
        markPending("TCA_NO_SUCH_TENANT", T)
        Map out = call("snapshot#PendingTenantConfigs", [nowStamp: new Timestamp(T.time + 61_000L)])
        assertEquals("FAILED", outcomeOf(out, "TCA_NO_SUCH_TENANT"))
        Map pending = pendingOf("TCA_NO_SUCH_TENANT")
        assertNotNull(pending, "kept, to try again")
        assertTrue((pending.lastErrorText as String).contains("not found"), pending.lastErrorText as String)
        Map again = call("snapshot#PendingTenantConfigs", [nowStamp: new Timestamp(T.time + 120_000L)])
        assertEquals("BACKING_OFF", outcomeOf(again, "TCA_NO_SUCH_TENANT"), "not retried every minute")
    }

    @Test
    void everyTableAVersionHoldsIsWatched() {
        Set<String> held = new TreeSet<String>()
        Closure walk
        walk = { Map spec ->
            held.add(spec.entityName as String)
            if (spec.links) held.add((spec.links as Map).entityName as String)
            ((spec.children ?: []) as List<Map>).each { walk(it) }
        }
        TransferSpecs.KINDS.each { walk(it) }
        held.remove("moqui.basic.Enumeration")      // a conclusion code is the tenant's through TenantConclusion
        held.add("darpan.rule.TenantConclusion")
        assertEquals(held, TenantConfigChangeTracker.watchedEntities(ec) as Set<String>,
                "a table a version holds but nothing watches would change without a version")
        asSystem {
            held.findAll { ec.entity.isEntityDefined(it) }.each { String entityName ->
                boolean stamped = ec.entity.getEntityDefinition(entityName).isField("companyUserGroupId")
                assertTrue(stamped || TenantConfigChangeTracker.PARENTS.containsKey(entityName), "${entityName} cannot name its tenant")
            }
        }
    }

    private Map call(String verbNoun, Map params) {
        Map out = ec.service.sync().name("${SERVICES}.${verbNoun}").parameters(params).call() ?: [:]
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        return out
    }

    private static String outcomeOf(Map out, String tenant) {
        return ((out.outcomes as List<Map>).find { it.tenant == tenant })?.outcome as String
    }

    private void markPending(String tenant, Timestamp at) {
        asSystem {
            upsert(PENDING, [companyUserGroupId: tenant, firstChangeDate: at, lastChangeDate: at, lastChangedByUserId: USER,
                    changeCount: 1L])
        }
    }

    private Map pendingOf(String tenant) {
        def row = asSystem { ec.entity.find(PENDING).condition("companyUserGroupId", tenant).useCache(false).one() }
        return row == null ? null : new HashMap(row.getMap())
    }

    /** The mark is written by an async service after the commit, so wait for it. */
    private Map waitForPending(String tenant) {
        long until = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < until) {
            Map row = pendingOf(tenant)
            if (row != null) return row
            sleep(100)
        }
        return null
    }

    /** Lets any async mark from an earlier save land before the next test looks. */
    private void settle() {
        sleep(500)
    }

    private long versionCount() {
        return asSystem { ec.entity.find(VERSION).condition("companyUserGroupId", MINE).count() }
    }

    private void setR2Text(String text) {
        asSystem { ec.entity.find("darpan.audit.AuditStatement").condition("statementId", R2_ID).one().set("statementText", text).update() }
    }

    private void inTransaction(Closure work) {
        ec.transaction.runRequireNew(60, "test save", work)
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
