package darpan.audit

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-199. Migration AUDIT_SCHEDULE_OWNED, shaped on prod gorjana's month close before 2026-11-06: five checks at 01:30
 * LA, three made later at 06:00 and resumed alone, one paused at 06:00. The audit takes the time most running checks
 * share, is on because some run, and names the checks off it without rewriting them. An audit whose checks are all paused
 * takes their time, paused; one with no automation, and one already stamped, are left alone; another tenant's audit is
 * its own. Dry run first, then the run, then a second run that changes nothing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditScheduleOwnedMigrationSmokeTests {

    private static final String T = "TASO"
    private static final String T2 = "TASO2"
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "audit-schedule-owned")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/AutomationSeedData.xml")
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            [T, T2].each { insert("moqui.security.UserGroup", [userGroupId: it, description: it]) }
            audit(T, "MC", null)
            [["A1", "0 30 1 * * ?", "Y"], ["A2", "0 30 1 * * ?", "Y"], ["A3", "0 30 1 * * ?", "Y"],
             ["A4", "0 0 6 * * ?", "Y"], ["A5", "0 0 6 * * ?", "N"]].each { List a ->
                checkWithAutomation(T, "MC", "${T}_${a[0]}".toString(), a[1] as String, "America/Los_Angeles", a[2] as String)
            }
            audit(T2, "MC", null)
            ["B1", "B2"].each { checkWithAutomation(T2, "MC", "${T2}_${it}".toString(), "0 0 2 * * ?", "UTC", "N") }
            audit(T, "EMPTY", null)
            audit(T, "DONE", "09:00")
            checkWithAutomation(T, "DONE", "${T}_D1", "0 0 6 * * ?", "UTC", "Y")
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @Test
    void eachAuditTakesTheTimeMostOfItsRunningChecksShareOnceAndOnlyOnce() {
        Map dry = migrate(true)
        assertEquals([2, 1], [dry.rowsUpdated, dry.auditsOffSchedule], "${dry.messages}")
        assertEquals([null, null], [stamp(T, "MC")[0], stamp(T2, "MC")[0]], "a dry run writes nothing")
        assertTrue((dry.messages as List).any { it.toString().contains("${T}_A4, ${T}_A5") }, "${dry.messages}")

        Map done = migrate(false)
        assertEquals([2, 1], [done.rowsUpdated, done.auditsOffSchedule], "${done.messages}")
        assertEquals(["01:30", "America/Los_Angeles", "Y"], stamp(T, "MC"))
        assertEquals(["02:00", "UTC", "N"], stamp(T2, "MC"), "every check paused: the audit is paused at their time")
        assertEquals([null, null, null], stamp(T, "EMPTY"), "no automation: the defaults stand")
        assertEquals(["09:00", null, null], stamp(T, "DONE"), "already stamped: skipped")
        assertEquals(["0 0 6 * * ?", "Y"], ["scheduleExpr", "isActive"].collect { automation("${T}_A4").get(it) },
                "no automation is rewritten")

        Map again = migrate(false)
        assertEquals([0, 0], [again.rowsUpdated, again.auditsOffSchedule], "a second run changes nothing: ${again.messages}")
    }

    private Map migrate(boolean dryRun) {
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            return ec.service.sync().name("audit.AuditLedgerServices.migrate#AuditScheduleOwned").parameters([dryRun: dryRun]).call()
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    private void audit(String t, String code, String scheduleTime) {
        insert("darpan.audit.Audit", [auditId: "${t}_${code}".toString(), companyUserGroupId: t, auditCode: code.toLowerCase(),
                auditName: code, periodEnumId: "AUDP_MONTH", scheduleTime: scheduleTime])
        insert("darpan.audit.AuditStatement", [statementId: "${t}_${code}_R1".toString(), companyUserGroupId: t,
                auditId: "${t}_${code}".toString(), statementCode: "R1", categoryEnumId: "AUDC_ORDERS", weightEnumId: "AUDSW_FIRM",
                sequenceNum: 1L, statementText: code])
    }

    private void checkWithAutomation(String t, String code, String id, String cron, String zone, String active) {
        insert("darpan.rule.RuleSet", [ruleSetId: "${id}_RS".toString(), ruleSetName: id, version: "1.0", companyUserGroupId: t])
        insert("darpan.reconciliation.ReconciliationAutomation", [automationId: id, automationName: id, companyUserGroupId: t,
                inputModeEnumId: "AUT_IN_API_RANGE", savedRunId: "${id}_RS".toString(), scheduleExpr: cron, windowTimeZone: zone,
                isActive: active])
        insert("darpan.audit.AuditCheck", [checkId: id, companyUserGroupId: t, checkCode: id.toLowerCase().replace('_', '-'),
                label: id, savedRunId: "${id}_RS".toString(), automationId: id])
        insert("darpan.audit.AuditStatementCheck", [statementId: "${t}_${code}_R1".toString(), checkId: id, companyUserGroupId: t,
                sequenceNum: 1L])
    }

    private List stamp(String t, String code) {
        def row = ec.entity.find("darpan.audit.Audit").condition("auditId", "${t}_${code}".toString()).disableAuthz().useCache(false).one()
        return ["scheduleTime", "scheduleTimeZone", "scheduleActive"].collect { row.get(it) }
    }

    private def automation(String id) {
        return ec.entity.find("darpan.reconciliation.ReconciliationAutomation").condition("automationId", id).disableAuthz().useCache(false).one()
    }

    private void insert(String entityName, Map<String, Object> fields) {
        ReconciliationSmokeTestSupport.insertEntityDirect(ec, entityName, fields)
    }
}
