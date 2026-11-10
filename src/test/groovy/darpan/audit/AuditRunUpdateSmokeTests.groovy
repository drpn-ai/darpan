package darpan.audit

import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.automation.RunAdmission
import darpan.reconciliation.notification.TenantNotificationSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Date
import java.sql.Timestamp
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-207 — send#AuditRunUpdate against real entities; the email caught by AuditRunUpdateSupport.sendEmail and the
 * chat post by the delivery hook.
 *
 * GORJANA has a "Month close" audit: R2 (orders) with three open unexplained findings, R8 (returns) with two returns
 * whose cause has a fix of its own, and R9 with none. One check feeds all three through automation TRU_AUTO. A run
 * digest sends to a chat space and two addresses.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditRunUpdateSmokeTests {

    private static final String LEDGER = "audit.AuditLedgerServices"
    private static final String SEND = "audit.AuditDigestServices.send#AuditRunUpdate"
    private static final String GORJANA = "GORJANA"
    private static final ZoneId LA = ZoneId.of("America/Los_Angeles")
    private static final String AUDIT = "TRU_AUDIT"
    private static final String CHECK = "TRU_CHK"
    private static final String AUTO = "TRU_AUTO"
    private static final String SPACE = "TRU_SPACE"
    private static final String HOOK = "https://chat.googleapis.com/v1/spaces/TRU/messages?key=k&token=t"

    private ExecutionContext ec
    private final List<Map> posts = Collections.synchronizedList([])
    private final List<Map> mails = Collections.synchronizedList([])
    private Closure realSendEmail
    private Closure realDispatch
    private LocalDate today
    private String period
    private int seq = 0

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "audit-run-update")
        realSendEmail = AuditRunUpdateSupport.sendEmail
        realDispatch = AuditRunUpdateSupport.dispatch
        AuditRunUpdateSupport.sendEmail = { def ignoredEc, String to, Map body, List<Map> attachments ->
            mails << [to: to, body: body, attachments: attachments]
        }
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/DarpanSystemSourceSeedData.xml", "component://darpan/data/AutomationSeedData.xml")
        today = LocalDate.now(LA)
        period = today.toString().substring(0, 7)
        insert("moqui.security.UserGroup", [userGroupId: GORJANA, description: "Gorjana"])
        insert("darpan.auth.TenantSetting", [companyUserGroupId: GORJANA, timeZone: "America/Los_Angeles"])
        insert("darpan.reconciliation.TenantChatSpace", [chatSpaceId: SPACE, companyUserGroupId: GORJANA, spaceName: "Close",
                webhookUrl: HOOK, isActive: "Y"])
        insert("darpan.audit.Audit", [auditId: AUDIT, companyUserGroupId: GORJANA, auditCode: "month-close", auditName: "Month close",
                periodEnumId: "AUDP_MONTH"])
        insert("darpan.rule.RuleSet", [ruleSetId: "TRU_RS", companyUserGroupId: GORJANA])
        insert("darpan.reconciliation.ReconciliationAutomation", [automationId: AUTO, automationName: "Month close checks",
                companyUserGroupId: GORJANA, inputModeEnumId: "AUT_IN_API_RANGE", savedRunId: "TRU_RS", windowTimeZone: "America/Los_Angeles"])
        insert("darpan.audit.AuditCheck", [checkId: CHECK, companyUserGroupId: GORJANA, checkCode: "close-checks", label: "Close checks",
                savedRunId: "TRU_RS", automationId: AUTO])
        [["R2", "AUDC_ORDERS", "Every order is billed."], ["R8", "AUDC_RETURNS", "Every return is credited."],
         ["R9", "AUDC_RETURNS", "Every exchange ships."]].eachWithIndex { List s, int i ->
            insert("darpan.audit.AuditStatement", [statementId: "TRU_${s[0]}".toString(), companyUserGroupId: GORJANA, auditId: AUDIT,
                    statementCode: s[0], categoryEnumId: s[1], weightEnumId: "AUDSW_FIRM", sequenceNum: (i + 1) as Long, statementText: s[2]])
            insert("darpan.audit.AuditStatementCheck", [checkId: CHECK, statementId: "TRU_${s[0]}".toString(), companyUserGroupId: GORJANA, sequenceNum: 1L])
        }
        // R8's cause has a fix of its own; {return} is a record's value, which a group's line cannot carry.
        insert("moqui.basic.EnumerationType", [enumTypeId: "DarpanConclusion", description: "Darpan conclusion"])
        insert("moqui.basic.Enumeration", [enumId: "TRU_RMA", enumTypeId: "DarpanConclusion", enumCode: "TRU_RMA",
                description: "Return has an RMA, no credit memo"])
        insert("darpan.rule.RuleSetCompareScope", [compareScopeId: "TRU_SCOPE", ruleSetId: "TRU_RS", companyUserGroupId: GORJANA])
        insert("darpan.rule.RuleSetConclusionRule", [compareScopeId: "TRU_SCOPE", sequenceNum: 1L, conclusionEnumId: "TRU_RMA",
                companyUserGroupId: GORJANA, fixActionTemplate: "Issue the credit memo in NetSuite {return}"])

        Date day = Date.valueOf(today)
        Timestamp stamp = new Timestamp(System.currentTimeMillis())
        Map run = call("${LEDGER}.record#CheckRun", [companyUserGroupId: GORJANA, checkId: CHECK, reportDate: day, runStamp: stamp,
                sourceReads: [[systemEnumId: "NETSUITE_SUITEQL", statusEnumId: "AUDSR_COMPLETE", recordCount: 50, readStamp: stamp]]])
        List findings = (1..3).collect { int i ->
            [statementCode: "R2", recordKey: "ORD-${i}".toString(), headline: "Order ${i} is not billed".toString(),
             records: [[systemEnumId: "OMS", recordType: "order", recordId: "#GOR${i}".toString(), internalId: "M${i}".toString()]]]
        } + (1..2).collect { int i ->
            [statementCode: "R8", recordKey: "RX-${i}".toString(), headline: "Return RX-${i} has no credit memo".toString(),
             conclusionEnumId: "TRU_RMA", records: [[systemEnumId: "OMS", recordType: "return", recordId: "RX-${i}".toString()]]]
        }
        call("${LEDGER}.record#CheckFindings", [companyUserGroupId: GORJANA, checkId: CHECK, reportDate: day, checkStamp: stamp,
                findings: findings])
        call("${LEDGER}.close#ResolvedFindings", [companyUserGroupId: GORJANA, checkId: CHECK, reportDate: day, checkStamp: stamp,
                checkStatusEnumId: run.checkStatusEnumId, examinedRecordKeys: findings*.recordKey])

        // GORJANA's people: two current members with email (one written in capitals, one membership ending next week),
        // a disabled account, a membership that ended, a member with no address, and a member of another tenant.
        member("TRU_ANA", "Ana@Gorjana.example", GORJANA, [:])
        member("TRU_BEN", "ben@gorjana.example", GORJANA, [thruDate: new Timestamp(System.currentTimeMillis() + 7L * 86400000)])
        member("TRU_OFF", "off@gorjana.example", GORJANA, [:], "Y")
        member("TRU_GONE", "gone@gorjana.example", GORJANA, [thruDate: new Timestamp(System.currentTimeMillis() - 86400000)])
        member("TRU_NOMAIL", null, GORJANA, [:])
        insert("moqui.security.UserGroup", [userGroupId: "KREWE", description: "Krewe"])
        member("TRU_KREWE", "kim@krewe.example", "KREWE", [:])
        // Darpan admins: one who is no member of GORJANA, and Ana, a member who is an admin as well.
        ["ADMIN", "DARPAN_SUPER_ADMIN"].each { insert("moqui.security.UserGroup", [userGroupId: it, description: it]) }
        member("TRU_ADM", "adm@drpn.example", "DARPAN_SUPER_ADMIN", [:])
        insert("moqui.security.UserGroupMember", [userGroupId: "ADMIN", userId: "TRU_ANA",
                fromDate: new Timestamp(System.currentTimeMillis() - 86400000)])
    }

    private void member(String userId, String email, String tenant, Map membership, String disabled = "N") {
        insert("moqui.security.UserAccount", [userId: userId, username: userId.toLowerCase(), emailAddress: email, disabled: disabled])
        insert("moqui.security.UserGroupMember", [userGroupId: tenant, userId: userId,
                fromDate: new Timestamp(System.currentTimeMillis() - 30L * 86400000)] + membership)
    }

    @AfterAll
    void cleanup() {
        TenantNotificationSupport.resetDeliveryHook()
        AuditRunUpdateSupport.sendEmail = realSendEmail
        AuditRunUpdateSupport.dispatch = realDispatch
        AutomationExecutionSupport.setRunAdmission(null)
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void reset() {
        ec.message.clearErrors()
        posts.clear()
        mails.clear()
        TenantNotificationSupport.setDeliveryHook { String ref, Map payload -> posts << [ref: ref, payload: payload]; return [ok: true, statusCode: 200] }
        ec.entity.find("darpan.audit.AuditDigest").deleteAll()
        ec.entity.find("darpan.reconciliation.ReconciliationAutomationExecution").condition("automationId", AUTO).deleteAll()
        insert("darpan.audit.AuditDigest", [auditDigestId: "TRU_RUN", companyUserGroupId: GORJANA, auditId: AUDIT, chatSpaceId: SPACE,
                emailAddresses: "close@gorjana.example, ops@gorjana.example", kindEnumId: "AUDDK_RUN", sendTime: "00:00", isActive: "Y"])
        execution("AUT_STAT_SUCCESS")
    }

    @AfterEach
    void restore() {
        TenantNotificationSupport.resetDeliveryHook()
        AutomationExecutionSupport.setRunAdmission(null)
    }

    @Test
    void theWholeRunGoesOutOnceByEmailWithACsvPerRuleAndASummaryCard() {
        assertEquals([[auditDigestId: "TRU_RUN", sent: true, reason: null]], send(AUTO))

        assertEquals(1, mails.size())
        Map mail = mails[0]
        assertEquals("adm@drpn.example,ana@gorjana.example,ben@gorjana.example,close@gorjana.example,ops@gorjana.example", mail.to,
                "the members and admins, and the digest's addresses as well")
        assertTrue((mail.body.runSubject as String).startsWith("Gorjana Month close, "), mail.body.runSubject as String)
        assertTrue((mail.body.runSubject as String).endsWith(": 5 open findings"), mail.body.runSubject as String)
        List<Map> attachments = mail.attachments as List<Map>
        assertEquals(["gorjana-month-close-${period}-R2.csv".toString(), "gorjana-month-close-${period}-R8.csv".toString()],
                attachments*.fileName, "one per rule with open findings; R9 has none")
        List<String> r8 = (attachments[1].contentText as String).split("\r\n") as List<String>
        assertEquals(3, r8.size(), "header and the two returns")
        assertTrue(r8[1].startsWith("R8,\"Return has an RMA, no credit memo\",F-"), r8[1])
        assertTrue(r8[1].contains(",RX-1,"), r8[1])
        assertTrue(r8[1].endsWith(",Issue the credit memo in NetSuite"), "the cause's fix, its record placeholder cut: ${r8[1]}")
        String html = mail.body.runHtml as String
        assertTrue(html.contains(">Issue the credit memo in NetSuite</div>"), "no writer installed: each cause's own fix")
        assertTrue(html.contains("M1 #GOR1, M2 #GOR2, M3 #GOR3"), "R2's orders by HotWax id and name")
        assertTrue((mail.body.runText as String).contains("Open 5 · New 5 · Fixed 0"), mail.body.runText as String)

        assertEquals(1, posts.size())
        assertEquals(HOOK, posts[0].ref)
        Map card = ((posts[0].payload.cardsV2 as List<Map>)[0].card) as Map
        assertEquals("Gorjana · Month close", card.header.title)
        assertEquals(1, (card.sections as List).size(), "a summary, no rule sections")

        assertEquals([[auditDigestId: "TRU_RUN", sent: false, reason: "ALREADY_SENT"]], send(AUTO), "the same run is not sent twice")
        assertEquals(1, mails.size())
    }

    @Test
    void anAuditStillRunningSendsNothing() {
        execution("AUT_STAT_RUNNING")
        assertEquals("RUN_NOT_DONE", send(AUTO)[0].reason)
        execution("AUT_STAT_PENDING", [nextRetryDate: new Timestamp(System.currentTimeMillis() + 60000)])
        assertEquals("RUN_NOT_DONE", send(AUTO)[0].reason, "a retry still to come")
        assertEquals([], mails)
        assertEquals([], posts)
    }

    @Test
    void anAutomationStillWaitingForASlotKeepsTheRunOpenButTheFinishingOneDoesNot() {
        // An executor that never runs: the run holds its slot, as one does between admission and its execution row.
        RunAdmission admission = new RunAdmission(4, 4, 100, { -> 0 }, { Runnable ignored -> } as Executor)
        AutomationExecutionSupport.setRunAdmission(admission)
        admission.submit(GORJANA, AutomationExecutionSupport.admissionLabel(AUTO), { -> } as Runnable)
        assertEquals("RUN_NOT_DONE", send("TRU_OTHER")[0].reason)
        assertEquals(true, send(AUTO)[0].sent, "the execution that just finished does not wait for itself")
    }

    @Test
    void anAutomationDueAndNotYetSubmittedKeepsTheRunOpenUnlessNoSchedulerRuns() {
        insert("darpan.reconciliation.ReconciliationAutomation", [automationId: "TRU_AUTO2", automationName: "More checks",
                companyUserGroupId: GORJANA, inputModeEnumId: "AUT_IN_API_RANGE", savedRunId: "TRU_RS", isActive: "Y",
                nextScheduledFireTime: new Timestamp(System.currentTimeMillis() - 4 * 60000)])
        insert("darpan.audit.AuditCheck", [checkId: "TRU_CHK2", companyUserGroupId: GORJANA, checkCode: "more-checks", label: "More",
                savedRunId: "TRU_RS", automationId: "TRU_AUTO2"])
        insert("darpan.audit.AuditStatementCheck", [checkId: "TRU_CHK2", statementId: "TRU_R9", companyUserGroupId: GORJANA, sequenceNum: 2L])
        try {
            assertEquals("RUN_NOT_DONE", send(AUTO)[0].reason, "the scan defers it to its next tick")
            insert("darpan.reconciliation.ReconciliationAutomation", [automationId: "TRU_AUTO2",
                    nextScheduledFireTime: new Timestamp(System.currentTimeMillis() - 3 * 3600000)])
            assertEquals(true, send(AUTO)[0].sent, "due hours ago: no scheduler runs here")
        } finally {
            ec.entity.find("darpan.audit.AuditStatementCheck").condition("checkId", "TRU_CHK2").deleteAll()
            ec.entity.find("darpan.audit.AuditCheck").condition("checkId", "TRU_CHK2").deleteAll()
        }
    }

    @Test
    void twoExecutionsFinishingTogetherSendOnceEvenWithNoDigestRowYet() {
        ec.entity.find("darpan.audit.AuditDigest").deleteAll()
        CountDownLatch start = new CountDownLatch(1)
        List<Map> outcomes = Collections.synchronizedList([])
        List<Thread> threads = (1..2).collect {
            Thread.start {
                def threadEc = ec.factory.getExecutionContext()
                threadEc.user.loginAnonymousIfNoUser()
                try {
                    start.await()
                    // As the background call runs: the service, on a thread with no user.
                    outcomes.addAll(threadEc.service.sync().name(SEND).parameters([companyUserGroupId: GORJANA, auditId: AUDIT,
                            reportDate: Date.valueOf(today), period: period, finishedAutomationId: AUTO]).call().outcomes as List<Map>)
                } finally {
                    threadEc.destroy()
                }
            }
        }
        start.countDown()
        threads*.join(60000)
        assertEquals(1, mails.size(), "${outcomes}")
        assertEquals("adm@drpn.example,ana@gorjana.example,ben@gorjana.example", mails[0].to)
        assertEquals([false, true], outcomes*.sent.sort())
        assertEquals("ALREADY_SENT", outcomes.find { !it.sent }.reason)

        // A later execution of the audit ends: a new run, a new update.
        execution("AUT_STAT_SUCCESS", [completedDate: new Timestamp(System.currentTimeMillis() + 5000)])
        assertEquals(true, send(AUTO)[0].sent)
        assertEquals(2, mails.size())
    }

    @Test
    void withoutTheOptionalWriterThereAreNoWordsAndTheHookAsksOnlyForAnAuditRun() {
        assertFalse(ec.service.isServiceDefined(AuditRunUpdateSupport.WORDS_SERVICE), "darpan-agent is not installed here")
        assertEquals([:], AuditRunUpdateSupport.words(ec, GORJANA, [:]))

        List<Map> asked = []
        AuditRunUpdateSupport.dispatch = { def ignoredEc, Map params -> asked << params }
        try {
            def automation = ec.entity.find("darpan.reconciliation.ReconciliationAutomation").condition("automationId", AUTO).one()
            AuditRunUpdateSupport.afterExecution(ec, automation, [auditVerifyKeys: ["RX-1"]], null)
            assertEquals([], asked, "a verify is not a run")
            AuditRunUpdateSupport.afterExecution(ec, automation, [:], [auditId: AUDIT, auditReportDate: Date.valueOf(today), auditPeriod: period])
            assertEquals([[companyUserGroupId: GORJANA, auditId: AUDIT, reportDate: Date.valueOf(today), period: period,
                           finishedAutomationId: AUTO]], asked)
            AuditRunUpdateSupport.afterExecution(ec, automation, [:], null)
            assertEquals(AUDIT, asked[1].auditId, "a failed execution still asks, its audit found from its checks")
            ec.entity.find("darpan.audit.AuditDigest").deleteAll()
            AuditRunUpdateSupport.afterExecution(ec, automation, [:], [auditId: AUDIT])
            assertEquals(3, asked.size(), "no run digest: on by default")
            insert("darpan.audit.AuditDigest", [auditDigestId: "TRU_RUN", companyUserGroupId: GORJANA, auditId: AUDIT,
                    kindEnumId: "AUDDK_RUN", sendTime: "00:00", isActive: "N"])
            AuditRunUpdateSupport.afterExecution(ec, automation, [:], [auditId: AUDIT])
            assertEquals(3, asked.size(), "turned off for this audit: nothing asked")
        } finally {
            AuditRunUpdateSupport.dispatch = realDispatch
        }
    }

    @Test
    void withNoDigestTheUpdateGoesToEveryCurrentMemberOnceAndAnInactiveDigestTurnsItOff() {
        assertEquals(["ana@gorjana.example", "ben@gorjana.example"], AuditRunUpdateSupport.tenantMemberEmails(ec, GORJANA),
                "lowercased; disabled, ended, address-less and other tenants' people left out")
        assertEquals(["adm@drpn.example", "ana@gorjana.example", "ben@gorjana.example"], AuditRunUpdateSupport.defaultRecipients(ec, GORJANA),
                "a Darpan admin who is no member is added; Ana, member and admin, once")
        ec.entity.find("darpan.audit.AuditDigest").deleteAll()
        assertEquals([[auditDigestId: "RUN_${AUDIT}".toString(), sent: true, reason: null]], send(AUTO))
        assertEquals(1, mails.size())
        assertEquals("adm@drpn.example,ana@gorjana.example,ben@gorjana.example", mails[0].to)
        assertEquals([], posts, "no digest, no chat space")
        assertEquals("Y", ec.entity.find("darpan.audit.AuditDigest").condition("auditDigestId", "RUN_${AUDIT}".toString()).one().isActive,
                "a default digest now holds the run's claim")
        assertEquals("ALREADY_SENT", send(AUTO)[0].reason)

        ec.entity.find("darpan.audit.AuditDigest").deleteAll()
        insert("darpan.audit.AuditDigest", [auditDigestId: "TRU_RUN", companyUserGroupId: GORJANA, auditId: AUDIT, chatSpaceId: SPACE,
                emailAddresses: "close@gorjana.example", kindEnumId: "AUDDK_RUN", sendTime: "00:00", isActive: "N"])
        execution("AUT_STAT_SUCCESS", [completedDate: new Timestamp(System.currentTimeMillis() + 5000)])
        assertEquals([], send(AUTO), "off for this audit")
        assertEquals(1, mails.size())
        assertEquals(1L, ec.entity.find("darpan.audit.AuditDigest").count(), "no default digest made beside it")
    }

    @Test
    void aDigestAddressThatIsNotOneIsLeftOut() {
        assertEquals(["close@gorjana.example", "ap@gorjana.example"],
                AuditRunUpdateSupport.extraEmails([auditDigestId: "X", emailAddresses: "Close@Gorjana.example; not-an-address, ap@gorjana.example, close@gorjana.example"]))
    }

    @Test
    void aRunDigestIsNeverSentOnTheClock() {
        Map out = call("audit.AuditDigestServices.send#DueAuditDigests", [nowStamp: new Timestamp(System.currentTimeMillis())])
        assertFalse((out.outcomes as List<Map>)*.auditDigestId.contains("TRU_RUN"))
        assertEquals([], mails)
    }

    @Test
    void theEmailScreenPassesTheBuiltHtmlAndTextThroughUnescaped() {
        ec.context.push()
        try {
            ec.context.put("runHtml", "<p style=\"color:#202124;\">5 &amp; more</p>")
            ec.context.put("runText", "Open 5 & more")
            String html = ec.screen.makeRender().rootScreen("component://darpan/screen/email/AuditRun.xml").webappName("webroot")
                    .renderMode("html").render()
            String text = ec.screen.makeRender().rootScreen("component://darpan/screen/email/AuditRun.xml").webappName("webroot")
                    .renderMode("text").render()
            assertEquals("<p style=\"color:#202124;\">5 &amp; more</p>", html.trim())
            assertEquals("Open 5 & more", text.trim())
        } finally {
            ec.context.pop()
        }
    }

    private List<Map> send(String finishedAutomationId) {
        Map out = call(SEND, [companyUserGroupId: GORJANA, auditId: AUDIT, reportDate: Date.valueOf(today), period: period,
                              finishedAutomationId: finishedAutomationId])
        return out.outcomes as List<Map>
    }

    private void execution(String status, Map extra = [:]) {
        Timestamp now = new Timestamp(System.currentTimeMillis())
        insert("darpan.reconciliation.ReconciliationAutomationExecution", [automationExecutionId: "TRU_EX_${seq++}".toString(),
                automationId: AUTO, companyUserGroupId: GORJANA, statusEnumId: status, scheduledDate: now,
                completedDate: status == "AUT_STAT_SUCCESS" ? now : null] + extra)
    }

    private Map call(String name, Map params) {
        Map out = ec.service.sync().name(name).parameters(params).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        return out
    }

    private void insert(String entityName, Map<String, Object> fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }
}
