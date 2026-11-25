package darpan.ops

import darpan.config.DarpanSettings
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import darpan.reconciliation.support.SharedMoqui
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Date as SqlDate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-015 (spec F). Moqui wiring of the daily error email: the seed (template, words, both jobs), send#DueErrorDigest
 * reading real rows across tenants and sending once through the sendEmail seam, and flush#ErrorLog's purge. The pinned
 * moments are years ahead of the real clock, so rows other test classes leave in the shared database stay out of every
 * window; assertions look only at this class's own tenants.
 */
@SharedMoqui
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ErrorDigestServicesSmokeTests {
    private static final String SEND = "ops.ErrorDigestServices.send#DueErrorDigest"
    private static final String FLUSH = "ops.ErrorDigestServices.flush#ErrorLog"
    private static final String TENANT = "EDG_TENANT"
    private static final String OTHER = "EDG_OTHER"

    private ExecutionContext ec
    private final List<Map> mails = []
    private final List<Map> posts = []
    private int seq = 0

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "error-digest-services")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/OpsSeedData.xml",
                "component://darpan/data/AutomationSeedData.xml", "component://darpan/data/AuditSeedData.xml")
        insert("moqui.security.UserGroup", [userGroupId: TENANT, description: "Edge Goods"])
        insert("moqui.security.UserGroup", [userGroupId: OTHER, description: "Other Goods"])
    }

    @BeforeEach
    void seams() {
        DarpanSettings.rowLoader = { -> [("errors.email.to".toString()): [settingValue: "ops@drpn.test"]] as Map<String, Map> }
        DarpanSettings.expire()
        ErrorDigestSupport.sendEmail = { def sendEc, String to, Map body -> mails << [to: to, body: body]; return true }
        ErrorDigestSupport.postChat = { String url, Map payload -> posts << [url: url, text: payload.text]; return [ok: true] }
        ec.artifactExecution.disableAuthz()
        try { ec.entity.find("darpan.ops.ErrorDigestSend").deleteAll() } finally { ec.artifactExecution.enableAuthz() }
    }

    @AfterEach
    void reset() {
        mails.clear()
        posts.clear()
        ec.message.clearAll()
        ErrorDigestSupport.resetForTests()
        DarpanSettings.resetForTests()
    }

    @AfterAll
    void cleanup() {
        ErrorDigestSupport.resetForTests()
        DarpanSettings.resetForTests()
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    private void insert(String entity, Map fields) {
        ec.artifactExecution.disableAuthz()
        try { ec.entity.makeValue(entity).setAll(fields).createOrUpdate() } finally { ec.artifactExecution.enableAuthz() }
    }

    private static Timestamp ts(Instant at) { new Timestamp(at.toEpochMilli()) }

    private Map send(Instant now) {
        return ec.service.sync().name(SEND).parameters([nowStamp: ts(now)]).call()
    }

    /** One audit check on its own automation, in TENANT: the audit runs every morning, the statement is on. */
    private String auditCheck(String code, Instant automationCreated, Map automationExtra = [:]) {
        String n = "${++seq}"
        String automationId = "EDG_AUTO_${n}", auditId = "EDG_AUDIT_${n}", statementId = "EDG_ST_${n}", checkId = "EDG_CHK_${n}"
        insert("darpan.reconciliation.ReconciliationAutomation", [automationId: automationId, automationName: "Edge ${code}",
                companyUserGroupId: TENANT, inputModeEnumId: "AUT_IN_API_RANGE", savedRunId: "EDG_SR_${n}".toString(),
                windowTimeZone: "America/Los_Angeles", isActive: "Y", createdDate: ts(automationCreated)] + automationExtra)
        insert("darpan.audit.Audit", [auditId: auditId, companyUserGroupId: TENANT, auditCode: "edge-${n}".toString(), auditName: "Month close",
                periodEnumId: "AUDP_MONTH", scheduleActive: "Y"])
        insert("darpan.audit.AuditStatement", [statementId: statementId, companyUserGroupId: TENANT, auditId: auditId, statementCode: "R${n}".toString(),
                categoryEnumId: "AUDC_ORDERS", weightEnumId: "AUDSW_FIRM", statementText: "Every order is billed.", isActive: "Y"])
        insert("darpan.audit.AuditCheck", [checkId: checkId, companyUserGroupId: TENANT, checkCode: code, label: code, automationId: automationId])
        insert("darpan.audit.AuditStatementCheck", [statementId: statementId, checkId: checkId, companyUserGroupId: TENANT])
        return automationId
    }

    private void execution(String automationId, String status, Instant completed, Map extra = [:]) {
        insert("darpan.reconciliation.ReconciliationAutomationExecution", [automationExecutionId: "EDG_EX_${++seq}".toString(),
                automationId: automationId, companyUserGroupId: TENANT, statusEnumId: status, triggerType: "scheduled",
                scheduledDate: ts(completed.minus(Duration.ofMinutes(20))), completedDate: ts(completed)] + extra)
    }

    private static List<Map> mine(List rows) { (rows ?: []).findAll { it.tenant == TENANT } as List<Map> }

    @Test
    void theTemplateItsWordsAndBothJobsLoadFromSeed() {
        ec.artifactExecution.disableAuthz()
        try {
            def template = ec.entity.find("moqui.basic.email.EmailTemplate").condition("emailTemplateId", "DARPAN_ERROR_DIGEST").one()
            assertNotNull(template)
            assertEquals("component://darpan/screen/email/ErrorDigest.xml", template.bodyScreenLocation)
            assertEquals('${digestSubject}', template.subject)
            assertEquals("SYSTEM", template.emailServerId)
            assertNotNull(ec.entity.find("darpan.email.EmailCopyBlock").condition("emailTemplateId", "DARPAN_ERROR_DIGEST")
                    .condition("blockId", "chat.sent").one())

            def hourly = ec.entity.find("moqui.service.job.ServiceJob").condition("jobName", "send_ErrorDigest_hourly").one()
            assertEquals(SEND, hourly.serviceName)
            assertEquals("0 5 * * * ?", hourly.defaultCronExpression)
            def flush = ec.entity.find("moqui.service.job.ServiceJob").condition("jobName", "flush_ErrorLog_5m").one()
            assertEquals(FLUSH, flush.serviceName)
            assertEquals("0 0/5 * * * ?", flush.defaultCronExpression)
        } finally {
            ec.artifactExecution.enableAuthz()
        }
    }

    @Test
    void theConfiguredHourSendsOneEmailWithTheAssembledSubjectAndASecondCallSendsNothing() {
        Instant now = Instant.parse("2031-03-10T12:05:00Z")
        String automationId = auditCheck("completed-not-billed", now.minus(Duration.ofDays(20)))
        execution(automationId, "AUT_STAT_SUCCESS", now.minus(Duration.ofHours(27)), [file1ReadCount: 10L, file2ReadCount: 12L])
        execution(automationId, "AUT_STAT_DEAD_LETTER", now.minus(Duration.ofHours(3)), [errorMessage: "Connection reset by NetSuite",
                file1ReadCount: 6982L, file2ReadCount: 29963L, file1RecordCount: 5525L, file2RecordCount: 0L, file1Name: "oms.json"])

        assertEquals([sent: false, reason: "NOT_THE_HOUR"], send(now.minus(Duration.ofHours(1))).outcome.subMap(["sent", "reason"]))
        Map out = send(now).outcome
        assertTrue(out.sent as boolean, "${out}")
        assertEquals(1, mails.size())
        assertEquals("ops@drpn.test", mails[0].to)
        String subject = mails[0].body.digestSubject
        assertTrue(subject ==~ /\[[^\]]+\] Darpan errors 2031-03-10: \d+ checks, \d+ log errors/, subject)
        assertTrue(subject.startsWith("[${ErrorDigestSupport.instanceName(ec)}]".toString()), subject)

        Map body = mails[0].body.digestBody as Map
        List<Map> runs = mine(body.runErrors as List)
        assertEquals(["DEAD_LETTER"], runs*.kind)
        assertEquals("completed-not-billed", runs[0].check)
        assertEquals("Connection reset by NetSuite", runs[0].errorText)
        assertEquals(1, runs[0].daysInARow)
        assertEquals("Edge Goods", (body.tenantNames as Map)[TENANT])
        assertTrue(mine(body.watchdog as List).isEmpty(), "a success 27 hours ago is within the watchdog: ${body.watchdog}")

        def claimed = ec.entity.find("darpan.ops.ErrorDigestSend").condition("digestDay", SqlDate.valueOf("2031-03-10")).disableAuthz().one()
        assertNotNull(claimed.sentDate)
        assertTrue((claimed.checksCount as int) >= 1)

        String html
        ec.context.push()
        try {
            ec.context.putAll(mails[0].body as Map)
            html = ec.screen.makeRender().rootScreen("component://darpan/screen/email/ErrorDigest.xml").webappName("webroot").renderMode("html").render()
        } finally { ec.context.pop() }
        assertTrue(html.contains("completed-not-billed") && html.contains("Connection reset by NetSuite") && html.contains("Edge Goods"), html)

        assertEquals([sent: false, reason: "ALREADY_SENT"], send(now.plus(Duration.ofMinutes(30))).outcome.subMap(["sent", "reason"]))
        assertEquals(1, mails.size())
    }

    @Test
    void theWatchdogSparesAYoungAutomationAndAnEmptyStreakSkipsErrorRuns() {
        Instant now = Instant.parse("2031-04-10T12:05:00Z")
        auditCheck("never-ran-old", now.minus(Duration.ofDays(5)))
        auditCheck("never-ran-young", now.minus(Duration.ofHours(10)))
        String quiet = auditCheck("empty-for-a-week", now.minus(Duration.ofDays(30)))
        (1..7).each { int d ->
            execution(quiet, "AUT_STAT_NO_DATA", now.minus(Duration.ofDays(d)).minus(Duration.ofHours(1)), [file1ReadCount: 0L, file2ReadCount: 0L])
        }
        // an error between quiet runs is not a read of nothing, and does not break or fill the streak
        execution(quiet, "AUT_STAT_FAILED", now.minus(Duration.ofDays(2)), [errorMessage: "boom"])

        assertTrue(send(now).outcome.sent as boolean)
        List<Map> watchdog = mine(mails[0].body.digestBody.watchdog as List)
                .findAll { it.check in ["never-ran-old", "never-ran-young", "empty-for-a-week"] }
        assertEquals([["never-ran-old", "STALE"], ["empty-for-a-week", "EMPTY_STREAK"]] as Set,
                watchdog.collect { [it.check, it.kind] } as Set, "${watchdog}")
        Map stale = watchdog.find { it.check == "never-ran-old" }
        assertEquals(ts(now.minus(Duration.ofDays(5))), stale.lastSuccessDate, "never ran: last success is floored at the automation's creation")
    }

    @Test
    void rowsOlderThan30DaysArePurged() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC)
        insert("darpan.ops.ErrorLogEntry", [fingerprint: "EDG old", logDay: SqlDate.valueOf(today.minusDays(31)), count: 1])
        insert("darpan.ops.ErrorLogEntry", [fingerprint: "EDG kept", logDay: SqlDate.valueOf(today.minusDays(30)), count: 1])
        ec.service.sync().name(FLUSH).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertNull(ec.entity.find("darpan.ops.ErrorLogEntry").condition("fingerprint", "EDG old").disableAuthz().one())
        assertNotNull(ec.entity.find("darpan.ops.ErrorLogEntry").condition("fingerprint", "EDG kept").disableAuthz().one())
    }
}
