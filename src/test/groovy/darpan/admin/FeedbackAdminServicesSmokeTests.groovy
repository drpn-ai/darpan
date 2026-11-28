package darpan.admin

import darpan.audit.FeedbackSupport
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import darpan.reconciliation.support.SharedMoqui
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.Moqui
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*
import static darpan.reconciliation.support.TestHelpers.upsert

/**
 * DAR-BE-361. Aditi lists feedback across tenants and answers one by email. Calls run on a thread of their own with authz ON,
 * as a real super admin (cross-tenant, active tenant ALPHA) and as a plain tenant user (refused by the DARPAN_ADMIN_API fence).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SharedMoqui
class FeedbackAdminServicesSmokeTests {
    private static final String SUPER_ID = "FBADM_SUPER"
    private static final String PLAIN_ID = "FBADM_PLAIN"
    private static final String SENDER_ID = "FBADM_SENDER"
    private static final String ALPHA = "FBADM_ALPHA"
    private static final String BRAVO = "FBADM_BRAVO"
    private static final Timestamp T0 = Timestamp.valueOf("2026-01-01 00:00:00")
    private static final String SERVICE_LIST = "admin.FeedbackAdminServices.get#FeedbackList"
    private static final String SERVICE_REPLY = "admin.FeedbackAdminServices.reply#Feedback"

    private ExecutionContext ec
    private Closure originalSend
    private List<Map> sent = Collections.synchronizedList([])
    private String sendResult = "MSG-1"

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "feedback-admin-smoke")
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml")
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void fixtures() {
        originalSend = FeedbackSupport.sendEmail
        sent.clear()
        sendResult = "MSG-1"
        FeedbackSupport.sendEmail = { def e, String to, String templateId, Map body ->
            sent << [to: to, templateId: templateId, body: body]
            return sendResult
        }
        ["darpan.audit.AuditFindingFeedback", "moqui.security.UserGroupMember", "moqui.security.UserPreference"].each {
            ec.entity.find(it).disableAuthz().deleteAll()
        }
        AuditLogTestSupport.wipe(ec)
        upsert(ec, "moqui.security.UserAccount", [userId: SUPER_ID, username: "fbadm.super", userFullName: "Admin Super",
                emailAddress: "fbadm.super@example.com", disabled: "N", requirePasswordChange: "N"])
        upsert(ec, "moqui.security.UserAccount", [userId: PLAIN_ID, username: "fbadm.plain", userFullName: "Plain User",
                emailAddress: "fbadm.plain@example.com", disabled: "N", requirePasswordChange: "N"])
        upsert(ec, "moqui.security.UserAccount", [userId: SENDER_ID, username: "fbadm.sender", userFullName: "Sam Sender",
                emailAddress: "fbadm.sender@example.com", disabled: "N", requirePasswordChange: "N"])
        [[ALPHA, "Alpha Co"], [BRAVO, "Bravo Co"]].each { List g ->
            upsert(ec, "moqui.security.UserGroup", [userGroupId: g[0], description: g[1],
                    groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
            upsert(ec, "darpan.audit.Audit", [auditId: "${g[0]}_A".toString(), companyUserGroupId: g[0], auditCode: "fb-audit",
                    auditName: "Feedback", periodEnumId: "AUDP_MONTH"])
            upsert(ec, "darpan.audit.AuditStatement", [statementId: "${g[0]}_R1".toString(), companyUserGroupId: g[0],
                    auditId: "${g[0]}_A".toString(), statementCode: "R1", categoryEnumId: "AUDC_ORDERS",
                    weightEnumId: "AUDSW_FIRM", sequenceNum: 1L, statementText: "Every order is billed."])
        }
        create("moqui.security.UserGroupMember", [userGroupId: "DARPAN_SUPER_ADMIN", userId: SUPER_ID, fromDate: T0])
        create("moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: PLAIN_ID, fromDate: T0])
        create("moqui.security.UserGroupMember", [userGroupId: ALPHA, userId: SUPER_ID, fromDate: T0])
        upsert(ec, "moqui.security.UserPreference", [userId: SUPER_ID, preferenceKey: TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY,
                preferenceValue: ALPHA])
        ec.message.clearErrors()
    }

    @AfterEach
    void restore() { FeedbackSupport.sendEmail = originalSend }

    @Test
    void listPutsUnansweredFirst() {
        long day = 86400000L
        long now = System.currentTimeMillis()
        feedback("FB-ANS", BRAVO, "answered, newest", new Timestamp(now - 1 * day), "Done.")
        feedback("FB-OLD", ALPHA, "unanswered, older", new Timestamp(now - 5 * day), null)
        feedback("FB-NEW", BRAVO, "unanswered, newer", new Timestamp(now - 2 * day), null)

        Map out = callAs("fbadm.super", SERVICE_LIST, [:])
        assertNoErrors(out)
        List<Map> rows = out.result.feedback as List<Map>
        assertEquals(["FB-NEW", "FB-OLD"], rows*.feedbackId, "unanswered only by default, newest first")
        assertEquals([id: BRAVO, name: "Bravo Co"], rows[0].tenant, "BRAVO reads though the admin's active tenant is ALPHA")
        assertEquals("R1", rows[0].statement)
        assertEquals("Sam Sender", rows[0].sentBy)
        assertEquals(2L, rows[0].ageDays as long)
        assertNull(rows[0].answer)

        List<Map> all = (callAs("fbadm.super", SERVICE_LIST, [unansweredOnly: false]).result.feedback as List<Map>)
        assertEquals(["FB-NEW", "FB-OLD", "FB-ANS"], all*.feedbackId, "answered ones follow the unanswered")
        assertEquals("Done.", all[2].answer)
        assertNotNull(all[2].answeredAt)
        assertEquals(["FB-NEW"], (callAs("fbadm.super", SERVICE_LIST, [limit: 1]).result.feedback as List<Map>)*.feedbackId)
    }

    @Test
    void replySavesAndEmailsTheSender() {
        feedback("FB-1", ALPHA, "The order was billed twice.", new Timestamp(System.currentTimeMillis() - 86400000L), null)

        Map out = callAs("fbadm.super", SERVICE_REPLY, [feedbackId: "FB-1", answer: "  We fixed the rule.  "])
        assertNoErrors(out)
        def row = ec.entity.find("darpan.audit.AuditFindingFeedback").condition("feedbackId", "FB-1").disableAuthz().useCache(false).one()
        assertEquals("We fixed the rule.", row.answer)
        assertEquals(SUPER_ID, row.answeredByUserId)
        assertNotNull(row.answeredStamp)
        assertEquals(1, sent.size())
        assertEquals("fbadm.sender@example.com", sent[0].to)
        assertEquals("DARPAN_FEEDBACK_ANSWER", sent[0].templateId)
        assertEquals("We fixed the rule.", sent[0].body.answer)
        assertEquals("The order was billed twice.", sent[0].body.note)
        assertEquals("Sam", sent[0].body.firstName)
        assertEquals("R1", sent[0].body.statementCode)
        List audit = ec.entity.find("darpan.admin.AdminAuditLog").disableAuthz().useCache(false).list()
        assertEquals(1, audit.size())
        assertEquals(SERVICE_REPLY, audit[0].serviceName)
        assertEquals("FB-1", audit[0].targetId)

        // A second reply overwrites the answer and sends again.
        assertNoErrors(callAs("fbadm.super", SERVICE_REPLY, [feedbackId: "FB-1", answer: "Second thoughts."]))
        assertEquals(2, sent.size())
        assertEquals("Second thoughts.", ec.entity.find("darpan.audit.AuditFindingFeedback").condition("feedbackId", "FB-1")
                .disableAuthz().useCache(false).one().answer)
    }

    @Test
    void blankAnswerRefused() {
        feedback("FB-1", ALPHA, "note", new Timestamp(System.currentTimeMillis()), null)
        Map blank = callAs("fbadm.super", SERVICE_REPLY, [feedbackId: "FB-1", answer: "   "])
        assertTrue(blank.hasError as boolean)
        Map unknown = callAs("fbadm.super", SERVICE_REPLY, [feedbackId: "FB-NOPE", answer: "Hello"])
        assertTrue(unknown.hasError as boolean)
        assertTrue((unknown.errors as List).any { it.toString().contains("FB-NOPE") })
        sendResult = null
        Map unsent = callAs("fbadm.super", SERVICE_REPLY, [feedbackId: "FB-1", answer: "Hello"])
        assertTrue(unsent.hasError as boolean, "a mail that was not sent is an error")
        assertNull(ec.entity.find("darpan.audit.AuditFindingFeedback").condition("feedbackId", "FB-1").disableAuthz().useCache(false).one().answer)
        assertEquals(0, ec.entity.find("darpan.admin.AdminAuditLog").disableAuthz().useCache(false).count())
    }

    @Test
    void plainUserIsDenied() {
        feedback("FB-1", ALPHA, "note", new Timestamp(System.currentTimeMillis()), null)
        assertNotNull(callAs("fbadm.plain", SERVICE_LIST, [:]).authzDenied)
        assertNotNull(callAs("fbadm.plain", SERVICE_REPLY, [feedbackId: "FB-1", answer: "Hi"]).authzDenied)
        assertEquals(0, sent.size())
    }

    private Map callAs(String username, String serviceName, Map params) {
        Map holder = [:]
        Thread worker = new Thread({
            ExecutionContext tec = Moqui.getExecutionContextFactory().getExecutionContext()
            try {
                if (!tec.user.internalLoginUser(username)) throw new IllegalStateException("could not log in ${username}")
                try {
                    holder.result = tec.service.sync().name(serviceName).parameters(new LinkedHashMap(params)).call()
                } catch (ArtifactAuthorizationException e) {
                    holder.authzDenied = e
                }
                holder.errors = new ArrayList<String>(tec.message.errors)
                holder.hasError = tec.message.hasError()
            } catch (Throwable t) {
                holder.failure = t
            } finally {
                tec.destroy()
            }
        } as Runnable, "feedback-admin-${username}")
        worker.start()
        worker.join()
        if (holder.failure) throw (Throwable) holder.failure
        return holder
    }

    private static void assertNoErrors(Map out) {
        assertNull(out.authzDenied, "unexpected authz denial: ${out.authzDenied}")
        assertFalse(out.hasError as boolean, "unexpected errors: ${out.errors}")
    }

    private void feedback(String id, String tenant, String note, Timestamp sent, String answer) {
        create("darpan.audit.AuditFindingFeedback", [feedbackId: id, companyUserGroupId: tenant, findingId: "F-" + id,
                statementId: "${tenant}_R1".toString(), conclusionCode: "CAUSE_A", recordKey: "M1/01", headline: "Billed twice",
                note: note, sentByUserId: SENDER_ID, sentStamp: sent, answer: answer,
                answeredByUserId: answer ? SUPER_ID : null, answeredStamp: answer ? sent : null])
    }

    private void create(String entityName, Map fields) { ec.entity.makeValue(entityName).setAll(fields).create() }
}
