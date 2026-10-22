package darpan.audit

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
import java.time.LocalDateTime
import java.time.ZoneId

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-102 — send#DueAuditDigests against real entities, the chat post caught by the delivery hook.
 *
 * GORJANA (Los Angeles) has a "Month close" audit with R2, checked by one check. On 2026-10-21 it found INV-1; on
 * 2026-10-22 the check did not run. KREWE has a chat space of its own. Each test sets up the digests it needs.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuditDigestSmokeTests {

    private static final String LEDGER = "audit.AuditLedgerServices"
    private static final String SEND = "audit.AuditDigestServices.send#DueAuditDigests"
    private static final String GORJANA = "GORJANA"
    private static final String KREWE = "KREWE"
    private static final ZoneId LA = ZoneId.of("America/Los_Angeles")
    private static final String CHECK = "TDG_INV"
    private static final String AUDIT = "TDG_AUDIT"
    private static final String SPACE = "TDG_SPACE"
    private static final String KREWE_SPACE = "TDG_K_SPACE"
    private static final String HOOK = "https://chat.googleapis.com/v1/spaces/TDG/messages?key=k&token=t"

    private ExecutionContext ec
    private final List<Map> posts = []
    private final List<Map> mails = []
    private Closure realSendEmail

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "audit-digest")
        realSendEmail = AuditDigestSupport.sendEmail
        AuditDigestSupport.sendEmail = { def ignoredEc, String to, Map body -> mails << [to: to, subject: body.digestSubject, text: body.digestText] }
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/DarpanSystemSourceSeedData.xml")
        [(GORJANA): "Gorjana", (KREWE): "Krewe"].each { String id, String name ->
            insert("moqui.security.UserGroup", [userGroupId: id, description: name])
        }
        insert("darpan.auth.TenantSetting", [companyUserGroupId: GORJANA, timeZone: "America/Los_Angeles"])
        insert("darpan.reconciliation.TenantChatSpace", [chatSpaceId: SPACE, companyUserGroupId: GORJANA, spaceName: "HotWax Support",
                webhookUrl: HOOK, isActive: "Y"])
        insert("darpan.reconciliation.TenantChatSpace", [chatSpaceId: KREWE_SPACE, companyUserGroupId: KREWE, spaceName: "Krewe",
                webhookUrl: HOOK, isActive: "Y"])
        insert("darpan.audit.Audit", [auditId: AUDIT, companyUserGroupId: GORJANA, auditCode: "month-close", auditName: "Month close",
                periodEnumId: "AUDP_MONTH"])
        insert("darpan.audit.AuditStatement", [statementId: "TDG_R2", companyUserGroupId: GORJANA, auditId: AUDIT, statementCode: "R2",
                categoryEnumId: "AUDC_ORDERS", weightEnumId: "AUDSW_FIRM", sequenceNum: 2L, statementText: "Every invoice carries the Etail id."])
        insert("darpan.audit.AuditCheck", [checkId: CHECK, companyUserGroupId: GORJANA, checkCode: "invoices-without-etail-id",
                label: "Invoices with no Etail id", graceDays: 0L])
        insert("darpan.audit.AuditStatementCheck", [checkId: CHECK, statementId: "TDG_R2", companyUserGroupId: GORJANA, sequenceNum: 1L])

        Map run = call("${LEDGER}.record#CheckRun", [companyUserGroupId: GORJANA, checkId: CHECK, reportDate: Date.valueOf("2026-10-21"),
                runStamp: at("2026-10-21", "06:05"), sourceReads: [[systemEnumId: "NETSUITE_SUITEQL", statusEnumId: "AUDSR_COMPLETE",
                                                                   recordCount: 120, readStamp: at("2026-10-21", "06:04")]]])
        call("${LEDGER}.record#CheckFindings", [companyUserGroupId: GORJANA, checkId: CHECK, reportDate: Date.valueOf("2026-10-21"),
                checkStamp: at("2026-10-21", "06:05"), findings: [[statementCode: "R2", recordKey: "INV-1",
                headline: "Invoice INV-1 has no Etail id", records: [[systemEnumId: "NETSUITE_SUITEQL", recordType: "invoice", recordId: "INV-1"]]]]])
        call("${LEDGER}.close#ResolvedFindings", [companyUserGroupId: GORJANA, checkId: CHECK, reportDate: Date.valueOf("2026-10-21"),
                checkStamp: at("2026-10-21", "06:05"), checkStatusEnumId: run.checkStatusEnumId, examinedRecordKeys: ["INV-1"]])
    }

    @AfterAll
    void cleanup() {
        TenantNotificationSupport.resetDeliveryHook()
        AuditDigestSupport.sendEmail = realSendEmail
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void catchPosts() {
        ec.message.clearErrors()
        posts.clear()
        mails.clear()
        TenantNotificationSupport.setDeliveryHook { String ref, Map payload -> posts << [ref: ref, text: payload.text]; return [ok: true, statusCode: 200] }
        ec.entity.find("darpan.audit.AuditDigest").deleteAll()
    }

    @AfterEach
    void releaseHook() {
        TenantNotificationSupport.resetDeliveryHook()
    }

    @Test
    void aDailyDigestGoesOutOnceAfterItsTimeWithTheMorningsReport() {
        digest("TDG_DAILY", SPACE, "AUDDK_DAILY", "07:00")

        assertEquals("NOT_YET", outcome(send("2026-10-21", "06:50"), "TDG_DAILY").reason)
        assertEquals([], posts)

        assertEquals(true, outcome(send("2026-10-21", "07:05"), "TDG_DAILY").sent)
        assertEquals(1, posts.size())
        assertEquals(HOOK, posts[0].ref)
        String text = posts[0].text
        assertTrue(text.startsWith("Month close · 2026-10 · Wed 21 Oct\n"), text)
        assertTrue(text.contains("Open 1 · Pending 0 · New today 1 · Fixed today 0"), text)
        assertTrue(text.contains(" · R2 · 0 days · Invoice INV-1 has no Etail id"), text)

        assertEquals("SENT_TODAY", outcome(send("2026-10-21", "07:20"), "TDG_DAILY").reason, "once a day")
        assertEquals(1, posts.size())
    }

    @Test
    void theNextMorningsDigestSaysTheCheckDidNotRun() {
        digest("TDG_DAILY", SPACE, "AUDDK_DAILY", "07:00")
        send("2026-10-22", "07:01")
        assertEquals(1, posts.size())
        assertEquals("Not complete: check invoices-without-etail-id did not run.", posts[0].text.readLines()[1])
    }

    @Test
    void theTenantsDayDecidesNotTheServers() {
        digest("TDG_DAILY", SPACE, "AUDDK_DAILY", "07:00")
        // 23:30 in Los Angeles on the 21st is the 22nd in UTC; sent the evening of the 21st already, it is not due again.
        send("2026-10-21", "07:05")
        assertEquals("SENT_TODAY", outcome(send("2026-10-21", "23:30"), "TDG_DAILY").reason)
        assertEquals(1, posts.size())
    }

    @Test
    void aWeeklySummaryGoesOutOnlyOnItsDay() {
        digest("TDG_WEEKLY", SPACE, "AUDDK_WEEKLY", "08:00", [dayOfWeek: 4L])  // Thursday
        assertEquals("NOT_TODAY", outcome(send("2026-10-21", "09:00"), "TDG_WEEKLY").reason, "the 21st is a Wednesday")
        assertEquals(true, outcome(send("2026-10-22", "09:00"), "TDG_WEEKLY").sent)
        assertTrue(posts[0].text.startsWith("Month close · 2026-10 · week to Thu 22 Oct\n"), posts[0].text)
        assertTrue(posts[0].text.contains("Open 1 (0 a week ago) · Pending 0 (0 a week ago)"), posts[0].text)
    }

    @Test
    void anotherTenantsChatSpaceIsNeverPostedTo() {
        digest("TDG_STRAY", KREWE_SPACE, "AUDDK_DAILY", "07:00")
        assertEquals("NO_DESTINATION", outcome(send("2026-10-21", "07:05"), "TDG_STRAY").reason)
        assertEquals([], posts)
    }

    @Test
    void anInactiveDigestIsNotSentAndABadTimeIsReportedNotThrown() {
        digest("TDG_OFF", SPACE, "AUDDK_DAILY", "07:00", [isActive: "N"])
        digest("TDG_BAD", SPACE, "AUDDK_DAILY", "7am")
        List<Map> outcomes = send("2026-10-21", "09:00")
        assertFalse(outcomes*.auditDigestId.contains("TDG_OFF"))
        assertEquals("BAD_SEND_TIME", outcome(outcomes, "TDG_BAD").reason)
        assertEquals([], posts)
    }

    @Test
    void aFailedPostKeepsTheDaysClaim() {
        digest("TDG_DAILY", SPACE, "AUDDK_DAILY", "07:00")
        TenantNotificationSupport.setDeliveryHook { String ref, Map payload -> posts << [ref: ref]; return [ok: false, statusCode: 404] }
        assertEquals("DELIVERY_FAILED", outcome(send("2026-10-21", "07:05"), "TDG_DAILY").reason)
        assertEquals("SENT_TODAY", outcome(send("2026-10-21", "07:20"), "TDG_DAILY").reason, "no retry storm on a dead webhook")
        assertEquals(1, posts.size())
    }

    @Test
    void aDigestCanGoByEmailAloneOrByEmailAndChat() {
        digest("TDG_MAIL", null, "AUDDK_DAILY", "07:00", [emailAddresses: "close@gorjana.example, not-an-address; ops@gorjana.example"])
        assertEquals(true, outcome(send("2026-10-21", "07:05"), "TDG_MAIL").sent)
        assertEquals([], posts, "no chat space, no post")
        assertEquals(1, mails.size())
        assertEquals("close@gorjana.example,ops@gorjana.example", mails[0].to, "a malformed address is dropped")
        assertEquals("Month close · 2026-10 · Wed 21 Oct", mails[0].subject, "the subject is the digest's first line")
        assertTrue(mails[0].text.contains("Invoice INV-1 has no Etail id"), mails[0].text)

        digest("TDG_BOTH", SPACE, "AUDDK_DAILY", "07:00", [emailAddresses: "close@gorjana.example"])
        assertEquals(true, outcome(send("2026-10-21", "07:06"), "TDG_BOTH").sent)
        assertEquals(1, posts.size())
        assertEquals(2, mails.size())
    }

    @Test
    void aDigestWithNoUsableDestinationIsNotSent() {
        digest("TDG_NONE", null, "AUDDK_DAILY", "07:00", [emailAddresses: "nobody"])
        assertEquals("NO_DESTINATION", outcome(send("2026-10-21", "07:05"), "TDG_NONE").reason)
        assertEquals([], mails)
    }

    private void digest(String id, String space, String kind, String time, Map extra = [:]) {
        insert("darpan.audit.AuditDigest", [auditDigestId: id, companyUserGroupId: GORJANA, auditId: AUDIT, chatSpaceId: space,
                kindEnumId: kind, sendTime: time, isActive: "Y"] + extra)
    }

    private List<Map> send(String day, String time) {
        Map out = call(SEND, [nowStamp: at(day, time)])
        return out.outcomes as List<Map>
    }

    private static Map outcome(List<Map> outcomes, String id) {
        return outcomes.find { it.auditDigestId == id } ?: [:]
    }

    private Map call(String name, Map params) {
        Map out = ec.service.sync().name(name).parameters(params).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        return out
    }

    private static Timestamp at(String day, String time) {
        return Timestamp.from(LocalDateTime.parse("${day}T${time}").atZone(LA).toInstant())
    }

    private void insert(String entityName, Map<String, Object> fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }
}
