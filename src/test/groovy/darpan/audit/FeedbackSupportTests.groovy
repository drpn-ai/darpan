package darpan.audit

import darpan.config.DarpanSettings
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import darpan.reconciliation.support.SharedMoqui
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Date
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-361. Feedback on a finding: the note is saved first in its own transaction, then a card goes to Darpan's one chat
 * space (feedback.chat.webhookUrl) and the sender gets a thank-you email. A failed card, an unset space or a sender with
 * no email address never loses the note. The team's recent notes group by tenant + statement + cause (Unexplained groups
 * by statement alone) within audit.feedback.shownDays; older rows drop out of the list and stay in the table.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SharedMoqui
class FeedbackSupportTests {

    private static final String FACADE = "facade.AuditFacadeServices"
    private static final String LEDGER = "audit.AuditLedgerServices"
    private static final String ENTITY = "darpan.audit.AuditFindingFeedback"
    private static final String MINE = "KREWE"
    private static final String OTHER = "TFB_OTHER"
    private static final String WEBHOOK = "https://chat.googleapis.com/v1/spaces/FEEDBACK/messages?key=k&token=t"
    private static final String EMAIL = "smoke.customer@example.com"

    private ExecutionContext ec
    /** recordKey -> findingId of MINE's R1 findings. */
    private Map<String, String> ids = [:]
    private String otherFinding
    private String webhook = WEBHOOK
    private List<List> posts = []
    private List<Map> emails = []

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "audit-feedback")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/DarpanSystemSourceSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)   // signs in a KREWE editor, KREWE active
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            insert("moqui.basic.EnumerationType", [enumTypeId: "DarpanConclusion", description: "Run result conclusion"])
            insert("moqui.basic.Enumeration", [enumId: "TFB_CAUSE_A", enumTypeId: "DarpanConclusion", enumCode: "CAUSE_A",
                    description: "Shipped late by the 3PL"])
            // What the morning check records when a conclusion tree concludes nothing (data/ConclusionCatalogSeedData.xml).
            insert("moqui.basic.Enumeration", [enumId: "UNEXPLAINED", enumTypeId: "DarpanConclusion", enumCode: "UNEXPLAINED",
                    description: "Unexplained"])
            insert("moqui.security.UserGroup", [userGroupId: OTHER, description: "Other tenant"])
            [MINE, OTHER].each { String t ->
                insert("darpan.auth.TenantSetting", [companyUserGroupId: t, timeZone: "America/Los_Angeles"])
                insert("darpan.audit.Audit", [auditId: "${t}_TFB".toString(), companyUserGroupId: t, auditCode: "fb-audit",
                        auditName: "Feedback", periodEnumId: "AUDP_MONTH"])
                insert("darpan.audit.AuditStatement", [statementId: "${t}_TFB_R1".toString(), companyUserGroupId: t,
                        auditId: "${t}_TFB".toString(), statementCode: "R1", categoryEnumId: "AUDC_ORDERS",
                        weightEnumId: "AUDSW_FIRM", sequenceNum: 1L, statementText: "Every order is billed."])
                insert("darpan.audit.AuditCheck", [checkId: "${t}_TFB_C1".toString(), companyUserGroupId: t,
                        checkCode: "fb-check", label: "Check R1"])
                insert("darpan.audit.AuditStatementCheck", [checkId: "${t}_TFB_C1".toString(),
                        statementId: "${t}_TFB_R1".toString(), companyUserGroupId: t, sequenceNum: 1L])
            }
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
        ids.putAll(record(MINE, ["M201/01": "TFB_CAUSE_A", "M202/01": "TFB_CAUSE_A", "M203/01": null, "M204/01": null, "M205/01": null,
                              "M206/01": null, "M207/01": null, "M208/01": "UNEXPLAINED", "M209/01": null,
                              "M210/01": "UNEXPLAINED", "M211/01": null]))
        otherFinding = record(OTHER, ["M901/01": null])["M901/01"]

        DarpanSettings.rowLoader = { -> webhook ? [("feedback.chat.webhookUrl".toString()): [settingValue: webhook]] : [:] }
        FeedbackSupport.postHook = { String url, Map payload -> posts << [url, payload]; [ok: true, statusCode: 200] }
        FeedbackSupport.sendEmail = { def ectx, String to, String templateId, Map body ->
            emails << [to: to, templateId: templateId, body: body]; "MSG-1"
        }
    }

    private Map<String, String> record(String tenant, Map<String, String> causes) {
        List<String> keys = causes.keySet() as List<String>
        List<Map> findings = keys.collect { String item ->
            [statementCode: "R1", recordKey: item, headline: "Order ${item} is not billed".toString(), conclusionEnumId: causes[item],
             records: [[systemEnumId: "OMS_RETURNS_GQL", recordType: "orderItem", recordId: item]]]
        }
        Map out = ec.service.sync().name("${LEDGER}.record#CheckFindings").parameters([companyUserGroupId: tenant,
                checkId: "${tenant}_TFB_C1".toString(), reportDate: Date.valueOf("2026-11-20"), findings: findings]).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        return keys.withIndex().collectEntries { k, i -> [(k): (out.findingIds as List)[i]] } as Map<String, String>
    }

    @AfterAll
    void cleanup() {
        FeedbackSupport.resetForTests()
        DarpanSettings.resetForTests()
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void reset() {
        ec.message.clearErrors()
        webhook = WEBHOOK
        DarpanSettings.expire()
        posts.clear()
        emails.clear()
        setEmail(EMAIL)
    }

    private void setEmail(String address) {
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            ec.entity.find("moqui.security.UserAccount").condition("userId", ec.user.userId).useCache(false).one()
                    .set("emailAddress", address).update()
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    private Map call(String verbNoun, Map params) {
        Map out = ec.service.sync().name("${FACADE}.${verbNoun}").parameters(params).call() ?: [:]
        Map result = out + [hadError: ec.message.hasError(), errorText: ec.message.errorsString]
        ec.message.clearErrors()
        return result
    }

    /** Authz off: the tenant filter is skipped, so another tenant's rows would show here (a real cross-tenant check). */
    private List<Map> rows(String findingId) {
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try { return ec.entity.find(ENTITY).condition("findingId", findingId).useCache(false).list()*.getMap() as List<Map> }
        finally { if (!wasDisabled) ec.artifactExecution.enableAuthz() }
    }

    private String statementId(String tenant) { "${tenant}_TFB_R1".toString() }

    @Test
    void sendSavesPostsAndThanks() {
        String f = ids["M201/01"]
        Map out = call("send#AuditFindingFeedback", [findingId: f, note: "  The 3PL ships these a day late  "])
        assertFalse(out.hadError as boolean, out.errorText as String)

        List<Map> saved = rows(f)
        assertEquals(1, saved.size())
        Map row = saved[0]
        assertEquals(MINE, row.companyUserGroupId)
        assertEquals(statementId(MINE), row.statementId)
        assertEquals("CAUSE_A", row.conclusionCode, "the cause's code, from the finding's diagnosis")
        assertEquals("M201/01", row.recordKey)
        assertEquals("Order M201/01 is not billed", row.headline)
        assertEquals("The 3PL ships these a day late", row.note)
        assertEquals(ec.user.userId, row.sentByUserId)
        assertNotNull(row.sentStamp)
        assertTrue((row.feedbackId as String).startsWith("FB-"), row.feedbackId as String)

        Map fb = out.feedback as Map
        assertEquals(row.feedbackId, fb.feedbackId)
        assertEquals("The 3PL ships these a day late", fb.note)
        assertEquals(ec.user.userId, (fb.by as Map).id)
        assertEquals("Smoke Test Customer", (fb.by as Map).label)
        assertTrue((fb.sentAt as String) ==~ /\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d[-+]\d\d:\d\d/, fb.sentAt as String)

        assertEquals(1, posts.size())
        assertEquals(WEBHOOK, posts[0][0])
        String card = (posts[0][1] as Map).text as String
        assertTrue(card.contains("Feedback from Krewe"), card)
        assertTrue(card.contains("From: Smoke Test Customer"), card)
        assertTrue(card.contains("Statement: R1 Every order is billed."), card)
        assertTrue(card.contains("Cause: Shipped late by the 3PL"), card)
        assertTrue(card.contains("Record: M201/01"), card)
        assertTrue(card.contains('"The 3PL ships these a day late"'), card)
        assertTrue(card.contains("1 note on this statement + cause in 14 days"), card)
        assertTrue(card.contains("/audits/fb-audit?finding=${f}".toString()), card)

        assertEquals(1, emails.size())
        assertEquals(EMAIL, emails[0].to)
        assertEquals("DARPAN_FEEDBACK_RECEIVED", emails[0].templateId)
        Map body = emails[0].body as Map
        assertEquals("The 3PL ships these a day late", body.note)
        assertEquals("Smoke", body.firstName)
        assertEquals("R1", body.statementCode)
        assertEquals("M201/01", body.recordKey)

        // A second note on the same statement + cause is the second in the window.
        Map again = call("send#AuditFindingFeedback", [findingId: ids["M202/01"], note: "Same 3PL again"])
        assertFalse(again.hadError as boolean, again.errorText as String)
        assertTrue(((posts[1][1] as Map).text as String).contains("2 notes on this statement + cause in 14 days"), (posts[1][1] as Map).text as String)
        Map listed = call("get#AuditFindingFeedback", [findingId: f])
        assertFalse(listed.hadError as boolean, listed.errorText as String)
        assertEquals(14, listed.shownDays)
        assertEquals(["Same 3PL again", "The 3PL ships these a day late"], (listed.notes as List<Map>)*.note, "newest first")
        assertEquals(["M202/01", "M201/01"], (listed.notes as List<Map>)*.record)
        assertEquals(2, ((AuditReportAssembler.assembleFinding(ec, MINE, f).finding as Map).feedback as Map).recentCount)
    }

    @Test
    void webhookUnsetStillSavesAndEmails() {
        webhook = null
        DarpanSettings.expire()
        String f = ids["M203/01"]
        Map out = call("send#AuditFindingFeedback", [findingId: f, note: "Unexplained to us too"])
        assertFalse(out.hadError as boolean, out.errorText as String)
        assertEquals(1, rows(f).size())
        assertNull(rows(f)[0].conclusionCode, "Unexplained: no cause")
        assertEquals([], posts)
        assertEquals(["DARPAN_FEEDBACK_RECEIVED"], emails*.templateId)
    }

    /** DAR-BE-397. A sender whose account is disabled is never mailed: the note is still saved and posted. */
    @Test
    void aDisabledSenderIsNotMailedButTheNoteIsSaved() {
        String f = ids["M211/01"]
        setDisabled("Y")
        try {
            Map out = call("send#AuditFindingFeedback", [findingId: f, note: "Sent just before my account was turned off"])
            assertFalse(out.hadError as boolean, out.errorText as String)
            assertEquals(1, rows(f).size(), "the note is kept")
            assertEquals([], emails, "no thank-you to a disabled account")
        } finally {
            setDisabled("N")
            // its own finding, and its note removed, so the other tests' counts never see it
            boolean wasDisabled = ec.artifactExecution.disableAuthz()
            try { ec.entity.find(ENTITY).condition("findingId", f).deleteAll() }
            finally { if (!wasDisabled) ec.artifactExecution.enableAuthz() }
        }
    }

    private void setDisabled(String flag) {
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            ec.entity.find("moqui.security.UserAccount").condition("userId", ec.user.userId).useCache(false).one()
                    .set("disabled", flag).update()
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    /** Review I2. A tree's own UNEXPLAINED conclusion is Unexplained too: no cause stored, one group with the no-cause notes. */
    @Test
    void unexplainedConclusionGroupsWithNoCause() {
        String treeSaid = ids["M208/01"]
        String noCause = ids["M209/01"]
        assertFalse(call("send#AuditFindingFeedback", [findingId: treeSaid, note: "Tree said unexplained"]).hadError as boolean)
        assertFalse(call("send#AuditFindingFeedback", [findingId: noCause, note: "No cause at all"]).hadError as boolean)
        assertNull(rows(treeSaid)[0].conclusionCode, "UNEXPLAINED stores no cause")
        assertNull(rows(noCause)[0].conclusionCode)

        [treeSaid, noCause].each { String f ->
            Map listed = this.call("get#AuditFindingFeedback", [findingId: f])
            assertFalse(listed.hadError as boolean, listed.errorText as String)
            List<String> notes = (listed.notes as List<Map>)*.note
            assertTrue(notes.containsAll(["Tree said unexplained", "No cause at all"]), "one group from ${f}: ${notes}".toString())
            int count = ((AuditReportAssembler.assembleFinding(ec, MINE, f).finding as Map).feedback as Map).recentCount as int
            assertEquals(notes.size(), count, "the badge counts the same group")
        }
        assertNull(FeedbackSupport.conclusionCode(ec, "UNEXPLAINED"))
        assertEquals("CAUSE_A", FeedbackSupport.conclusionCode(ec, "TFB_CAUSE_A"))
    }

    /** Review I3. The email fails after the save (send#EmailTemplate adds an error, or the send throws): the note is saved and
     *  listed, the service answers ok with the note, and no error reaches the caller. */
    @Test
    void emailFailureAfterSaveStillAnswersOk() {
        Closure saved = FeedbackSupport.sendEmail
        try {
            [["M210/01", { def ectx, String to, String templateId, Map body -> ectx.message.addError("SMTP down for ${to}"); null }],
             ["M211/01", { def ectx, String to, String templateId, Map body -> throw new IllegalStateException("SMTP down") }]].each { List c ->
                FeedbackSupport.sendEmail = c[1] as Closure
                String f = ids[c[0] as String]
                Map out = this.call("send#AuditFindingFeedback", [findingId: f, note: "Mail is down but this matters"])
                assertFalse(out.hadError as boolean, "${c[0]}: ${out.errorText}".toString())
                assertEquals(true, out.ok, c[0] as String)
                assertEquals([], out.errors ?: [], c[0] as String)
                assertEquals("Mail is down but this matters", (out.feedback as Map)?.note, c[0] as String)
                assertEquals(1, rows(f).size(), "${c[0]}: the row is saved".toString())
                Map listed = this.call("get#AuditFindingFeedback", [findingId: f])
                assertTrue(((listed.notes as List<Map>)*.feedbackId).contains((out.feedback as Map).feedbackId), "${c[0]}: listed".toString())
            }
        } finally {
            FeedbackSupport.sendEmail = saved
        }
    }

    @Test
    void postFailureKeepsTheNote() {
        Closure saved = FeedbackSupport.postHook
        FeedbackSupport.postHook = { String url, Map payload -> throw new IllegalStateException("chat is down") }
        try {
            String f = ids["M204/01"]
            Map fb = FeedbackSupport.send(ec, MINE, f, "Still worth saying")
            assertNotNull(fb)
            assertEquals("Still worth saying", fb.note)
            assertEquals(1, rows(f).size())
            assertEquals(1, emails.size(), "the email still goes")

            Map viaFacade = call("send#AuditFindingFeedback", [findingId: f, note: "Twice"])
            assertFalse(viaFacade.hadError as boolean, viaFacade.errorText as String)
            assertEquals(2, rows(f).size())
        } finally {
            FeedbackSupport.postHook = saved
        }
    }

    @Test
    void senderWithoutEmailSkipsEmail() {
        setEmail(null)
        String f = ids["M205/01"]
        Map out = call("send#AuditFindingFeedback", [findingId: f, note: "No inbox here"])
        assertFalse(out.hadError as boolean, out.errorText as String)
        assertEquals(1, rows(f).size())
        assertEquals(1, posts.size())
        assertEquals([], emails)
    }

    @Test
    void blankNoteRefused() {
        String f = ids["M201/01"]
        int before = rows(f).size()
        Map out = call("send#AuditFindingFeedback", [findingId: f, note: "   "])
        assertTrue(out.hadError as boolean)
        assertTrue((out.errorText as String).contains("Add a note"), out.errorText as String)
        Map tooLong = call("send#AuditFindingFeedback", [findingId: f, note: "x" * 4001])
        assertTrue(tooLong.hadError as boolean)
        assertEquals(before, rows(f).size())
        assertEquals([], posts)
    }

    /** text-medium is VARCHAR(255) on MySQL and H2: a note the facade accepts must fit the column (text-long). */
    @Test
    void aLongNoteSaves() {
        String f = ids["M206/01"]
        String note = ("The 3PL ships late. " * 200).substring(0, 3999)
        Map out = call("send#AuditFindingFeedback", [findingId: f, note: note])
        assertFalse(out.hadError as boolean, out.errorText as String)
        assertEquals(1, rows(f).size())
        assertEquals(note.trim(), rows(f)[0].note)
        String card = (posts[0][1] as Map).text as String
        assertTrue(card.length() < 4096, "under Google Chat's text limit: ${card.length()}")
        assertTrue(card.endsWith("\u2026\""), "the card cuts a long note")
    }

    /** The person's note cannot fake a card line, mention a space or carry a link: one line, last, markup neutralised. */
    @Test
    void theNoteCannotSpoofTheCard() {
        String f = ids["M207/01"]
        String note = "Real words\r\nFeedback from Evil Co\n<users/all> <!channel> <https://evil|click> &lt;x&gt;"
        // The facade's parameter check refuses markup (allow-html none), so this goes to the support directly: the card must
        // hold even for a note that reaches it another way.
        assertNotNull(FeedbackSupport.send(ec, MINE, f, note))
        assertEquals(note.trim(), rows(f)[0].note, "the stored note is what the person wrote")
        String card = (posts[0][1] as Map).text as String
        List<String> lines = card.readLines()
        assertEquals(["Feedback from Krewe"], lines.findAll { it.startsWith("Feedback from") }, card)
        assertTrue(lines.last().startsWith("Note: \"Real words Feedback from Evil Co "), card)
        String noteLine = lines.last()
        ["<", ">", "|", "&"].each { assertFalse(noteLine.contains(it), "${it} in ${noteLine}") }
        assertFalse(card.contains("<") || card.contains("|"), card)
        assertTrue(lines[0..-2].every { !it.contains("Evil") }, "only the note line carries the note")
    }

    @Test
    void recentGroupsByStatementAndCause() {
        String st = "TFB_GROUP_R1"
        Timestamp now = new Timestamp(System.currentTimeMillis())
        List<List<String>> sent = [["CAUSE_A", "a1"], ["CAUSE_A", "a2"], ["CAUSE_B", "b1"], [null, "u1"]]
        for (int i = 0; i < sent.size(); i++) {
            insertFeedback(MINE, st, sent[i][0], sent[i][1], new Timestamp(now.time - (10 - i) * 60_000L))
        }
        insertFeedback(OTHER, st, "CAUSE_A", "theirs", now)

        List<Map> a = FeedbackSupport.recent(ec, MINE, st, "CAUSE_A", 14)
        assertEquals(["a2", "a1"], a*.note, "newest first, this tenant only")
        assertEquals(2, a.size())
        assertEquals(["u1"], FeedbackSupport.recent(ec, MINE, st, null, 14)*.note, "Unexplained groups by statement alone")
        assertEquals(["b1"], FeedbackSupport.recent(ec, MINE, st, "CAUSE_B", 14)*.note)
        Map first = a[0]
        assertEquals([id: ec.user.userId, label: "Smoke Test Customer"], first.by)
        assertEquals("REC-a2", first.record)
        assertNotNull(first.feedbackId)
        assertNotNull(first.sentAt)
    }

    @Test
    void olderThanWindowDropsOut() {
        String st = "TFB_OLD_R1"
        long day = 24L * 3600_000L
        insertFeedback(MINE, st, "CAUSE_A", "old", new Timestamp(System.currentTimeMillis() - 15 * day))
        insertFeedback(MINE, st, "CAUSE_A", "fresh", new Timestamp(System.currentTimeMillis() - day))
        assertEquals(["fresh"], FeedbackSupport.recent(ec, MINE, st, "CAUSE_A", 14)*.note)
        assertEquals(["fresh", "old"], FeedbackSupport.recent(ec, MINE, st, "CAUSE_A", 30)*.note)
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            assertEquals(2L, ec.entity.find(ENTITY).condition("statementId", st).useCache(false).count(), "the old row stays")
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    @Test
    void otherTenantCannotSend() {
        Map out = call("send#AuditFindingFeedback", [findingId: otherFinding, note: "Not mine"])
        assertTrue(out.hadError as boolean)
        assertTrue((out.errorText as String).contains("Finding ${otherFinding} was not found"), out.errorText as String)
        assertEquals([], rows(otherFinding))
        Map read = call("get#AuditFindingFeedback", [findingId: otherFinding])
        assertTrue((read.errorText as String).contains("Finding ${otherFinding} was not found"), read.errorText as String)
        assertNull(read.notes)
        assertEquals([], posts)
        assertEquals([], emails)
    }

    private void insertFeedback(String tenant, String statementId, String code, String note, Timestamp at) {
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            ec.entity.makeValue(ENTITY).setAll([companyUserGroupId: tenant, statementId: statementId, conclusionCode: code,
                    recordKey: "REC-${note}".toString(), headline: "h", note: note, sentByUserId: ec.user.userId, sentStamp: at])
                    .setSequencedIdPrimary().create()
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    private void insert(String entityName, Map<String, Object> fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }
}
