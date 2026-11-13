package darpan.retention

import darpan.admin.AuditLogTestSupport
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.Moqui
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Date
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-266. Retention through the real service engine on H2: unset periods keep everything, a dry run counts per table
 * and tenant without deleting, and a set period deletes only that table's older rows, of the tenant swept, never
 * another tenant's. Synthetic tenants RET_A and RET_B, each with an old and a recent row of every table.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetentionSmokeTests {
    private static final String A = "RET_A"
    private static final String B = "RET_B"
    private static final long DAY = 86_400_000L
    private static final Timestamp OLD = new Timestamp(System.currentTimeMillis() - 400L * DAY)
    private static final Timestamp RECENT = new Timestamp(System.currentTimeMillis() - DAY)

    private static final List<String> WIPE = ["darpan.audit.AuditFindingRecord", "darpan.audit.AuditFindingCondition",
            "darpan.audit.AuditFindingEvent", "darpan.audit.AuditFinding", "darpan.reconciliation.ReconciliationRunStep",
            "darpan.reconciliation.ReconciliationAutomationExecution", "darpan.reconciliation.ReconciliationRunResult",
            "darpan.agent.AgentMessage", "darpan.agent.AgentTurn", "darpan.agent.AgentConversation", "darpan.agent.AgentQueryLog"]

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "retention-smoke")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/DarpanSystemSourceSeedData.xml", "component://darpan/data/AutomationSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        asSystem {
            [A, B].each { String t ->
                upsert("moqui.security.UserGroup", [userGroupId: t, description: "${t} Co".toString(), groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
                upsert("darpan.audit.Audit", [auditId: "${t}_MC".toString(), companyUserGroupId: t, auditCode: "month-close",
                        auditName: "Month close", periodEnumId: "AUDP_MONTH"])
                upsert("darpan.audit.AuditStatement", [statementId: "${t}_R1".toString(), companyUserGroupId: t, auditId: "${t}_MC".toString(),
                        statementCode: "R1", categoryEnumId: "AUDC_ORDERS", weightEnumId: "AUDSW_FIRM", sequenceNum: 1L, statementText: "Every order is in both."])
                upsert("darpan.audit.AuditCheck", [checkId: "${t}_CHK".toString(), companyUserGroupId: t, checkCode: "orders", label: "Orders"])
                upsert("darpan.reconciliation.ReconciliationAutomation", [automationId: "${t}_AUTO".toString(), automationName: "${t}_AUTO".toString(),
                        companyUserGroupId: t, inputModeEnumId: "AUT_IN_API_RANGE", savedRunId: "${t}_RUN".toString()])
            }
        }
    }

    @AfterAll
    void cleanup() {
        clearProperties()
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void reset() {
        clearProperties()
        asSystem {
            WIPE.each { ec.entity.find(it).deleteAll() }
            [A, B].each { String t -> seedTenant(t) }
        }
        AuditLogTestSupport.wipe(ec)
        ec.message.clearErrors()
    }

    // ------------------------------------------------------------------ tests

    @Test
    void "with no period set a run deletes nothing and writes no audit row"() {
        Map before = snapshot()
        Map out = run("run#Retention", [:])
        assertTrue(out.tables.every { it.status == RetentionSupport.KEEP_FOREVER && it.rows == 0L }, "${out.tables}")
        assertEquals(before, snapshot(), "every row is still there")
        assertEquals(0L, auditRowCount())
    }

    @Test
    void "a period that is not a whole number of at least one day is reported and keeps the table"() {
        System.setProperty("darpan.retention.auditFinding.days", "0")
        System.setProperty("darpan.retention.agentQueryLog.days", "ninety")
        Map before = snapshot()
        Map out = run("run#Retention", [:])
        assertEquals(RetentionSupport.INVALID_PERIOD, table(out, RetentionSupport.AUDIT_FINDING).status)
        assertEquals(RetentionSupport.INVALID_PERIOD, table(out, RetentionSupport.AGENT_QUERY_LOG).status)
        assertEquals(before, snapshot())
    }

    @Test
    void "a dry run counts each table per tenant and deletes nothing"() {
        Map before = snapshot()
        Map out = run("dryRun#Retention", [periodDays: [AUDIT_FINDING: 30, RUN_RESULT: 30, AGENT_MESSAGE: 30, AGENT_QUERY_LOG: 30]])
        assertEquals(before, snapshot(), "a dry run deletes nothing")
        assertEquals(0L, auditRowCount(), "and writes no audit row")

        Map findings = table(out, RetentionSupport.AUDIT_FINDING)
        assertEquals([RetentionSupport.DRY_RUN, 30, 2L], [findings.status, findings.periodDays, findings.rows])
        assertEquals([(A): 1L, (B): 1L], findings.byTenant)
        assertEquals(["darpan.audit.AuditFindingRecord": 2L, "darpan.audit.AuditFindingCondition": 2L, "darpan.audit.AuditFindingEvent": 2L],
                findings.dependentRows)
        Map runs = table(out, RetentionSupport.RUN_RESULT)
        assertEquals(2L, runs.rows, "the old finished run per tenant; the old run still RUNNING is not counted")
        assertEquals(2L, runs.dependentRows["darpan.reconciliation.ReconciliationRunStep"])
        assertEquals(2L, runs.dependentRows["darpan.reconciliation.ReconciliationAutomationExecution"])
        assertEquals(4L, table(out, RetentionSupport.AGENT_MESSAGE).rows, "two messages per tenant, in the conversation with no recent turn")
        assertEquals(2L, table(out, RetentionSupport.AGENT_QUERY_LOG).rows)

        Map one = run("dryRun#Retention", [tenantUserGroupId: A, periodDays: [AUDIT_FINDING: 30]])
        assertEquals([(A): 1L], table(one, RetentionSupport.AUDIT_FINDING).byTenant, "one tenant asked, one tenant counted")
        assertEquals(RetentionSupport.KEEP_FOREVER, table(one, RetentionSupport.RUN_RESULT).status, "an override names only its tables")
    }

    @Test
    void "a set period deletes only that table's older rows and never another tenant's"() {
        System.setProperty("darpan.retention.auditFinding.days", "30")
        Map before = snapshot()
        Map out = run("run#Retention", [tenantUserGroupId: A])

        Map findings = table(out, RetentionSupport.AUDIT_FINDING)
        assertEquals([RetentionSupport.DELETED, 1L], [findings.status, findings.rows])
        assertEquals(["${A}_F_NEW".toString(), "${B}_F_NEW".toString(), "${B}_F_OLD".toString()], ids("darpan.audit.AuditFinding", "findingId"),
                "A's old finding went; A's recent one and all of B's stay")
        ["darpan.audit.AuditFindingRecord", "darpan.audit.AuditFindingCondition", "darpan.audit.AuditFindingEvent"].each { String child ->
            assertEquals(["${A}_F_NEW".toString(), "${B}_F_NEW".toString(), "${B}_F_OLD".toString()], ids(child, "findingId"), "${child} follows its finding")
        }
        Map after = snapshot()
        ["darpan.reconciliation.ReconciliationRunResult", "darpan.agent.AgentMessage", "darpan.agent.AgentQueryLog"].each { String other ->
            assertEquals(before[other], after[other], "${other} has no period: untouched")
        }
        List<Map> audit = asSystem { ec.entity.find("darpan.admin.AdminAuditLog").useCache(false).list()*.getMap() }
        assertEquals(1, audit.size(), "one audit row for the run")
        assertEquals(["retention.RetentionServices.run#Retention", "Retention", A], [audit[0].serviceName, audit[0].targetType, audit[0].targetId])
        assertTrue((audit[0].detailText as String).contains("AUDIT_FINDING 30d: 1"), "${audit[0].detailText}")
    }

    @Test
    void "run results, transcripts and query logs expire by their own rules, in batches"() {
        System.setProperty("darpan.retention.runResult.days", "30")
        System.setProperty("darpan.retention.agentMessage.days", "30")
        System.setProperty("darpan.retention.agentQueryLog.days", "30")
        System.setProperty("darpan.retention.batchSize", "1")
        Map out = run("run#Retention", [:])

        assertEquals(2L, table(out, RetentionSupport.RUN_RESULT).rows)
        [A, B].each { String t ->
            assertTrue(ids("darpan.reconciliation.ReconciliationRunResult", "reconciliationRunResultId", t)
                    .containsAll(["${t}_RR_NEW".toString(), "${t}_RR_RUNNING".toString()]), "a recent run and an unfinished old run stay")
            assertFalse(ids("darpan.reconciliation.ReconciliationRunResult", "reconciliationRunResultId", t).contains("${t}_RR_OLD".toString()))
            assertEquals(["${t}_STEP_NEW".toString()], ids("darpan.reconciliation.ReconciliationRunStep", "reconciliationRunStepId", t))
            Map execution = asSystem { ec.entity.find("darpan.reconciliation.ReconciliationAutomationExecution")
                    .condition("automationExecutionId", "${t}_EX".toString()).useCache(false).one().getMap() }
            assertNull(execution.reconciliationRunResultId, "the execution stays and forgets the deleted run result")
            assertEquals(5L, execution.differenceCount, "its own counts stay")

            assertEquals(["${t}_M_ACT".toString(), "${t}_M_NEW".toString()], ids("darpan.agent.AgentMessage", "messageId", t),
                    "the old conversation's transcript went; one with a recent turn and a recent one stay")
            assertEquals(["${t}_C_ACT".toString(), "${t}_C_NEW".toString(), "${t}_C_OLD".toString()], ids("darpan.agent.AgentConversation", "conversationId", t))
            assertEquals(["${t}_T_ACT".toString(), "${t}_T_OLD".toString()], ids("darpan.agent.AgentTurn", "turnId", t), "the spend ledger stays")
            assertEquals(["${t}_Q_NEW".toString()], ids("darpan.agent.AgentQueryLog", "agentQueryLogId", t))
        }
        assertEquals(4L, table(out, RetentionSupport.AGENT_MESSAGE).rows)
        assertEquals(1, out.batchSize)
        assertEquals(4, ids("darpan.audit.AuditFinding", "findingId").size(), "findings have no period: untouched")
    }

    // ------------------------------------------------------------------ fixture

    private void seedTenant(String t) {
        [OLD: OLD, NEW: RECENT].each { String age, Timestamp when ->
            String f = "${t}_F_${age}".toString()
            upsert("darpan.audit.AuditFinding", [findingId: f, companyUserGroupId: t, statementId: "${t}_R1".toString(), checkId: "${t}_CHK".toString(),
                    recordKey: f, period: "2025-10", stateEnumId: "AUDFS_OPEN", headline: "missing", firstFoundDate: new Date(when.time),
                    lastSeenDate: new Date(when.time)])
            upsert("darpan.audit.AuditFindingRecord", [findingId: f, recordSeqId: "01", companyUserGroupId: t, systemEnumId: "NETSUITE",
                    recordType: "order", recordId: f])
            upsert("darpan.audit.AuditFindingCondition", [findingId: f, sequenceNum: 1L, companyUserGroupId: t, label: "in both", passed: "N"])
            upsert("darpan.audit.AuditFindingEvent", [findingId: f, eventSeqId: "01", companyUserGroupId: t, eventStamp: when,
                    eventDate: new Date(when.time), eventTypeEnumId: "AUDFE_FOUND", byTypeEnumId: "AUDAT_CHECK"])
            upsert("darpan.agent.AgentQueryLog", [agentQueryLogId: "${t}_Q_${age}".toString(), companyUserGroupId: t, outcome: "OK", createdDate: when])
        }
        [[id: "OLD", created: OLD, status: "AUT_STAT_SUCCESS"], [id: "NEW", created: RECENT, status: "AUT_STAT_SUCCESS"],
         [id: "RUNNING", created: OLD, status: "AUT_STAT_RUNNING"]].each { Map r ->
            upsert("darpan.reconciliation.ReconciliationRunResult", [reconciliationRunResultId: "${t}_RR_${r.id}".toString(), companyUserGroupId: t,
                    statusEnumId: r.status, createdDate: r.created])
        }
        ["OLD", "NEW"].each { String age ->
            upsert("darpan.reconciliation.ReconciliationRunStep", [reconciliationRunStepId: "${t}_STEP_${age}".toString(),
                    reconciliationRunResultId: "${t}_RR_${age}".toString(), companyUserGroupId: t, stageCode: "COMPARE", statusEnumId: "AUT_STAT_SUCCESS"])
        }
        upsert("darpan.reconciliation.ReconciliationAutomationExecution", [automationExecutionId: "${t}_EX".toString(), automationId: "${t}_AUTO".toString(),
                companyUserGroupId: t, statusEnumId: "AUT_STAT_SUCCESS", reconciliationRunResultId: "${t}_RR_OLD".toString(), differenceCount: 5L])
        // OLD: started and last ran long ago. ACT: started long ago, ran yesterday. NEW: started yesterday.
        [[c: "OLD", created: OLD, turn: OLD, messages: ["${t}_M_OLD1", "${t}_M_OLD2"]], [c: "ACT", created: OLD, turn: RECENT, messages: ["${t}_M_ACT"]],
         [c: "NEW", created: RECENT, turn: null, messages: ["${t}_M_NEW"]]].each { Map c ->
            String conv = "${t}_C_${c.c}".toString()
            upsert("darpan.agent.AgentConversation", [conversationId: conv, companyUserGroupId: t, createdDate: c.created])
            if (c.turn) upsert("darpan.agent.AgentTurn", [turnId: "${t}_T_${c.c}".toString(), conversationId: conv, companyUserGroupId: t,
                    startedDate: c.turn, costMicros: 10L])
            (c.messages as List).eachWithIndex { Object m, int i ->
                upsert("darpan.agent.AgentMessage", [messageId: m.toString(), conversationId: conv, companyUserGroupId: t, sequenceNum: (long) i,
                        kind: "USER", contentJson: "{}"])
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Runs the service the way the scheduled job does: its own thread, nobody signed in. */
    private Map run(String verbNoun, Map params) {
        Map holder = [:]
        Thread worker = new Thread({
            ExecutionContext tec = Moqui.getExecutionContextFactory().getExecutionContext()
            try {
                assertNull(tec.user.userId, "the job runs as no one")
                holder.result = tec.service.sync().name("retention.RetentionServices.${verbNoun}".toString()).parameters(new LinkedHashMap(params))
                        .disableAuthz().call()
                holder.errors = new ArrayList<String>(tec.message.errors)
            } catch (Throwable t) {
                holder.failure = t
            } finally {
                tec.destroy()
            }
        } as Runnable, "retention-job")
        worker.start()
        worker.join()
        if (holder.failure) throw (Throwable) holder.failure
        assertTrue((holder.errors as List).isEmpty(), "${verbNoun}: ${holder.errors}")
        return holder.result as Map
    }

    @Test
    void "a signed-in user who is not a Darpan admin cannot run retention"() {
        System.setProperty("darpan.retention.auditFinding.days", "30")
        Map before = snapshot()
        ec.message.clearErrors()
        ec.service.sync().name("retention.RetentionServices.run#Retention").parameters([:]).disableAuthz().call()
        assertTrue(ec.message.errors.contains("Only super-admin users may run retention."), "${ec.message.errors}")
        ec.message.clearErrors()
        assertEquals(before, snapshot())
    }

    private static Map table(Map out, String key) { (out.tables as List<Map>).find { it.table == key } }

    private Map snapshot() {
        asSystem { WIPE.collectEntries { String e -> [(e): ec.entity.find(e).useCache(false).count()] } }
    }

    private List<String> ids(String entity, String pk, String tenant = null) {
        asSystem {
            def find = ec.entity.find(entity)
            if (tenant) find.condition("companyUserGroupId", tenant)
            find.orderBy(pk).useCache(false).list().collect { it.get(pk) as String }.unique()
        }
    }

    private long auditRowCount() { asSystem { ec.entity.find("darpan.admin.AdminAuditLog").useCache(false).count() } }

    private static void clearProperties() {
        (RetentionSupport.PERIOD_PROPERTIES.values() + [RetentionSupport.BATCH_SIZE_PROPERTY]).each { System.clearProperty(it) }
    }

    private <T> T asSystem(Closure<T> work) {
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try { return work.call() } finally { if (!wasDisabled) ec.artifactExecution.enableAuthz() }
    }

    private void upsert(String entityName, Map fields) { ec.entity.makeValue(entityName).setAll(fields).createOrUpdate() }
}
