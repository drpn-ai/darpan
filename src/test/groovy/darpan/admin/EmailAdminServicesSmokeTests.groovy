package darpan.admin

import darpan.email.EmailCopy
import darpan.email.EmailSamples
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.Moqui
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ExecutionContext

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-303: admin.EmailAdminServices through the real service engine, authz ON, as a real DARPAN_SUPER_ADMIN on a thread
 * of its own. Edge cases: a preview saves nothing and uses the draft, an edit is used by the next real render, every refused
 * edit saves nothing, reset restores the shipped words, a subject edit survives a release's rewrite of the template, and
 * only a super admin gets in.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmailAdminServicesSmokeTests {
    private ExecutionContext ec

    private static final String SUPER_ID = "EMLSVC_SUPER"
    private static final String SUPER = "emlsvc.super"
    private static final String PLAIN_ID = "EMLSVC_PLAIN"
    private static final String PLAIN = "emlsvc.plain"
    private static final String S = "admin.EmailAdminServices."
    private static final String CHANGED = "DARPAN_PASSWORD_CHANGED"
    private static final String WELCOME = "DARPAN_TENANT_WELCOME"

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "email-admin-services")
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ["PasswordResetEmailSeedData.xml", "TwoFactorSeedData.xml", "TenantWelcomeEmailSeedData.xml",
         "AuditDigestEmailSeedData.xml", "EmailCopySeedData.xml"].each {
            ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/${it}")
        }
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void resetFixtures() {
        ec.entity.find("darpan.email.EmailCopyBlock").list().each { r ->
            if (r.blockId == "subject") r.delete()
            else if (r.copyText != null) { r.set("copyText", null); r.update() }
        }
        EmailCopy.seedSubjects(ec).each { String id, String subject ->
            def t = ec.entity.find("moqui.basic.email.EmailTemplate").condition("emailTemplateId", id).useCache(false).one()
            if (t.subject != subject) { t.set("subject", subject); t.update() }
        }
        AuditLogTestSupport.wipe(ec, "darpan.admin.AdminAuditLog")
        [[SUPER_ID, SUPER], [PLAIN_ID, PLAIN]].each { id, name ->
            ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: id, username: name, userFullName: name,
                    emailAddress: "${name}@example.com", disabled: "N", requirePasswordChange: "N"]).createOrUpdate()
        }
        if (!ec.entity.find("moqui.security.UserGroupMember").condition([userGroupId: "DARPAN_SUPER_ADMIN", userId: SUPER_ID])
                .disableAuthz().useCache(false).count()) {
            ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userGroupId: "DARPAN_SUPER_ADMIN", userId: SUPER_ID,
                    fromDate: Timestamp.valueOf("2026-01-01 00:00:00")]).create()
        }
        ec.message.clearErrors()
    }

    // ---------------------------------------------------------------- reads

    @Test
    void theListIsTheTwelveEmailsAndTheShowCarriesBlocksWithVariables() {
        List<Map> templates = ok(svc("get#EmailTemplates", [:])).result.templates
        assertEquals(12, templates.size())
        assertEquals(12, templates*.emailTemplateId.toSet().size())
        assertEquals([0], templates*.editedBlockCount.unique())
        Map changed = templates.find { it.emailTemplateId == CHANGED }
        assertEquals("Your Darpan password was changed", changed.subject)

        Map shown = ok(svc("get#EmailTemplate", [emailTemplateId: CHANGED])).result
        assertEquals("Your Darpan password was changed", shown.defaultSubject)
        assertEquals(["body", "greeting", "guides", "note", "smile"], (shown.blocks as List<Map>)*.blockId)
        Map body = (shown.blocks as List<Map>).find { it.blockId == "body" }
        assertTrue((body.variables as List).containsAll(["changedAt", "firstName", "smile"]), "${body.variables}")
        assertNull(body.copyText)
        assertTrue((body.defaultText as String).contains('${'), body.defaultText as String)
        assertEquals("Riya", (shown.sample as Map).firstName)

        Map reminder = ok(svc("get#EmailTemplate", [emailTemplateId: "DARPAN_2FA_REMINDER"])).result
        assertEquals(["before", "after"], reminder.branches)
        assertEquals(["subjectLine"], reminder.subjectVariables)
        assertTrue(svc("get#EmailTemplate", [emailTemplateId: "DARPAN_NOPE"]).errors.any { it.contains("No such email") })
    }

    // ---------------------------------------------------------------- preview

    @Test
    void aPreviewRendersTheDraftThroughTheRealScreenAndSavesNothing() {
        Map out = ok(svc("preview#EmailTemplate", [emailTemplateId: CHANGED, subject: "Heads up, your password changed",
                blocks: [note: 'Not you, ${firstName}? Tell us.']])).result
        assertTrue((out.html as String).contains("Not you, Riya? Tell us."), out.html)
        assertTrue((out.text as String).contains("Not you, Riya? Tell us."), out.text)
        assertEquals("Heads up, your password changed", out.subject)
        // the layout is the real one: the frame, the stored default words of the blocks not in the draft, the signature
        assertTrue((out.html as String).contains('class="dp-card"') && (out.html as String).contains("— Darpan"))
        assertTrue((out.html as String).contains("New password, who dis?"))

        assertEquals([], ec.entity.find("darpan.email.EmailCopyBlock").condition("copyText", org.moqui.entity.EntityCondition.NOT_EQUAL, null).list())
        assertNull(EmailCopy.row(ec, CHANGED, "subject"))
        assertEquals("Your Darpan password was changed", templateSubject(CHANGED))
        assertEquals([], auditRows(), "a preview writes no audit row")
        assertFalse(render("PasswordChanged", EmailSamples.SECURITY.PasswordChanged).contains("Not you, Riya? Tell us."), "the next real render is the default")
        assertFalse(EmailCopy.previewing(), "the draft is always cleared")

        // a draft that breaks a rule is refused, never rendered; and a branch is a sample shape
        assertTrue(svc("preview#EmailTemplate", [emailTemplateId: CHANGED, blocks: [note: "see https://evil.example"]]).errors.any { it.contains("web address") })
        Map after = ok(svc("preview#EmailTemplate", [emailTemplateId: "DARPAN_2FA_REMINDER", branch: "after"])).result
        assertTrue((after.html as String).contains("now needs a second step"), after.html)
        assertEquals("Set up two-factor before 2 December 2026", after.subject)
        assertTrue(svc("preview#EmailTemplate", [emailTemplateId: "DARPAN_2FA_REMINDER", branch: "nope"]).errors.any { it.contains("No such sample branch") })
        Map welcome = ok(svc("preview#EmailTemplate", [emailTemplateId: WELCOME, subject: 'Hello ${tenantLabel}'])).result
        assertEquals("Hello Malbon", welcome.subject)
    }

    @Test
    void everyEmailPreviewsWithItsSample() {
        (ok(svc("get#EmailTemplates", [:])).result.templates as List<Map>).each { Map t ->
            Map out = ok(svc("preview#EmailTemplate", [emailTemplateId: t.emailTemplateId])).result
            assertTrue((out.html as String).length() > 0 && (out.text as String).length() > 0 && (out.subject as String).length() > 0, t.emailTemplateId as String)
        }
    }

    // ---------------------------------------------------------------- update

    @Test
    void anEditIsSavedAuditedAndUsedByTheNextRealRender() {
        ok(svc("update#EmailTemplate", [emailTemplateId: CHANGED, blocks: [note: 'Not you, ${firstName}? Write to us today.',
                guides: "Two-factor sign-in|guides/two-factor"]]))
        def row = row(CHANGED, "note")
        assertEquals('Not you, ${firstName}? Write to us today.', row.copyText)
        assertEquals(SUPER_ID, row.lastChangedByUserId)
        assertNotNull(row.lastChangedDate)
        assertTrue(row.defaultText.contains("Tell your Darpan administrator"), "the shipped words stay")
        String html = render("PasswordChanged", EmailSamples.SECURITY.PasswordChanged)
        assertTrue(html.contains("Not you, Riya? Write to us today."), html)
        assertEquals(1, (html =~ /href="/).count, "the one-link guides edit is used")

        List audit = auditRows()
        assertEquals(1, audit.size())
        assertEquals([S + "update#EmailTemplate", "EmailTemplate", CHANGED, SUPER_ID], [audit[0].serviceName, audit[0].targetType, audit[0].targetId, audit[0].adminUserId])
        assertTrue(audit[0].detailText.contains("note") && audit[0].detailText.contains("guides"), audit[0].detailText)

        Map shown = ok(svc("get#EmailTemplate", [emailTemplateId: CHANGED])).result
        assertEquals(1, ok(svc("get#EmailTemplates", [:])).result.templates.find { it.emailTemplateId == CHANGED }.editedBlockCount - 1)
        assertEquals('Not you, ${firstName}? Write to us today.', (shown.blocks as List<Map>).find { it.blockId == "note" }.copyText)
    }

    @Test
    void aRefusedEditSavesNothingAndSaysWhy() {
        Closure<List<String>> refused = { Map blocks, String subject = null ->
            Map p = [emailTemplateId: CHANGED, blocks: blocks]
            if (subject != null) p.subject = subject
            return svc("update#EmailTemplate", p).errors as List<String>
        }
        assertTrue(refused([note: 'Not you, ${nope}?'])[0].contains('Unknown variable ${nope}'))
        assertTrue(refused([note: "Not <b>you</b>?"])[0].contains("no < or >"))
        assertTrue(refused([note: "Open https://evil.example/x"])[0].contains("web address"))
        assertTrue(refused([note: "   "])[0].contains("empty"))
        assertTrue(refused([note: "x" * 2001])[0].contains("2000"))
        assertTrue(refused([guides: "Evil|https://evil.example/x"])[0].contains("Guide links"))
        assertTrue(refused([guides: "no bar here"])[0].contains("Guide links") || refused([guides: "no bar here"])[0].contains("Label|"))
        assertTrue(refused([guides: "A|guides/two-factor#sign-in\nB|guides/two-factor#set-it-up"])[0].contains("Guide links"), "a repeated page")
        assertTrue(refused([smile: "Wow!"])[0].contains("smile"))
        assertTrue(refused([nope: "x"])[0].contains("No such block nope"))
        // all or nothing: a good block beside a bad one saves neither
        assertTrue(refused([body: "Fine words.", note: "Open https://evil.example"])[0].contains("note"))
        // subjects: one plain line, only the shipped variables, never Groovy
        assertTrue(refused(null, 'Hi ${firstName}')[0].contains("Unknown variable"))
        String plain = "letters, digits, spaces and basic punctuation"
        assertTrue(refused(null, "Line one\nline two")[0].contains(plain))
        assertTrue(refused(null, 'Run ${System.exit(1)}')[0].contains(plain))
        assertTrue(refused(null, 'Cost $5')[0].contains(plain))
        // Moqui compiles a subject as a Groovy string: none of these may ever be saved
        ['"""+\'x\'.class.name+java.lang.Thread.currentThread().name+"""${subjectLine}', 'Reminder: "${subjectLine}"', 'Hi ${\'x\'.class}',
         'Hi ${ec.entity}', 'Hi `id`', 'Hi $name', 'Hi {x}', 'Hi \\ there', "Hi <b>", 'Hi ${tenantLabel'].each { String bad ->
            List<String> why = refused(null, bad)
            assertTrue(why[0].contains(plain) || why[0].contains("Unknown variable") || why[0].contains("HTML not allowed"), bad + " -> " + why)
        }
        assertTrue(refused(null, "See evil.example.com")[0].contains("web address"))
        assertTrue(refused(null, "x" * 201)[0].contains("200"))
        assertTrue(refused(null, "")[0].contains("empty"))
        assertTrue(svc("update#EmailTemplate", [emailTemplateId: CHANGED]).errors.any { it.contains("Nothing to save") })

        assertEquals([], ec.entity.find("darpan.email.EmailCopyBlock").condition("copyText", org.moqui.entity.EntityCondition.NOT_EQUAL, null).list(), "nothing saved")
        assertNull(EmailCopy.row(ec, CHANGED, "subject"))
        assertEquals([], auditRows())
        assertTrue(render("PasswordChanged", EmailSamples.SECURITY.PasswordChanged).contains("Not you? Tell your Darpan administrator now."))
    }

    // ---------------------------------------------------------------- reset

    @Test
    void resetPutsTheShippedWordsBack() {
        ok(svc("update#EmailTemplate", [emailTemplateId: CHANGED, blocks: [note: "Not you? Say so.", body: 'Changed ${changedAt}.'],
                subject: "Your password changed"]))
        assertTrue(render("PasswordChanged", EmailSamples.SECURITY.PasswordChanged).contains("Not you? Say so."))

        Map one = ok(svc("reset#EmailTemplate", [emailTemplateId: CHANGED, blockIds: ["note"]])).result
        assertEquals(["note"], one.reset)
        assertNull(row(CHANGED, "note").copyText)
        assertEquals('Changed ${changedAt}.', row(CHANGED, "body").copyText, "only the named block")
        assertEquals("Your password changed", templateSubject(CHANGED), "the subject was not named")
        assertTrue(render("PasswordChanged", EmailSamples.SECURITY.PasswordChanged).contains("Not you? Tell your Darpan administrator now."))

        Map all = ok(svc("reset#EmailTemplate", [emailTemplateId: CHANGED])).result
        assertEquals(["body", "subject"], all.reset.sort())
        assertEquals("Your Darpan password was changed", templateSubject(CHANGED))
        assertNull(EmailCopy.row(ec, CHANGED, "subject").copyText)
        assertEquals([], ok(svc("reset#EmailTemplate", [emailTemplateId: CHANGED])).result.reset, "nothing left to reset")
        assertEquals(1 + 1 + 1 + 1, auditRows().size(), "update, two resets, the empty reset: each audited")
        assertTrue(svc("reset#EmailTemplate", [emailTemplateId: CHANGED, blockIds: ["nope"]]).errors.any { it.contains("No such block") })
    }

    /** DAR-BE-303: the audit run and digest emails are managed like the other ten: their words are blocks, shown and previewed. */
    @Test
    void theAuditEmailsAreManagedWithTheirBlocksAndPreviewADraft() {
        Map run = ok(svc("get#EmailTemplate", [emailTemplateId: "DARPAN_AUDIT_RUN"])).result
        List<String> runBlocks = (run.blocks as List<Map>)*.blockId
        assertTrue(runBlocks.containsAll(["act.heading", "button", "guides", "notrun", "rule.all", "subtitle", "tile.open"]), "${runBlocks}")
        Map digest = ok(svc("get#EmailTemplate", [emailTemplateId: "DARPAN_AUDIT_DIGEST"])).result
        assertTrue((digest.blocks as List<Map>)*.blockId.containsAll(["guides", "list.oldest", "none", "report", "week"]))

        Map out = ok(svc("preview#EmailTemplate", [emailTemplateId: "DARPAN_AUDIT_RUN", blocks: ["act.heading": "Do these first"]])).result
        assertTrue((out.html as String).contains("DO THESE FIRST") && (out.text as String).contains("Do these first"), out.text)
        assertFalse((out.html as String).contains("ACT ON FIRST"))
        assertTrue((out.html as String).contains("Gorjana · Month close") && (out.html as String).contains("did not run on this morning"))
        Map dig = ok(svc("preview#EmailTemplate", [emailTemplateId: "DARPAN_AUDIT_DIGEST", blocks: ["list.oldest": "Stuck longest"]])).result
        assertTrue((dig.text as String).contains("Stuck longest") && !(dig.text as String).contains("Oldest open"), dig.text)
        assertTrue((svc("update#EmailTemplate", [emailTemplateId: "DARPAN_AUDIT_RUN", blocks: [button: "Open https://evil.example"]])
                .errors.any { it.contains("web address") }), "an address is refused")
        assertNull(row("DARPAN_AUDIT_RUN", "button")?.copyText)
        ok(svc("update#EmailTemplate", [emailTemplateId: "DARPAN_AUDIT_RUN", blocks: [button: "Open the report"]]))
        assertEquals("Open the report", row("DARPAN_AUDIT_RUN", "button").copyText)
        ok(svc("reset#EmailTemplate", [emailTemplateId: "DARPAN_AUDIT_RUN"]))
        assertNull(row("DARPAN_AUDIT_RUN", "button").copyText)
    }

    // ---------------------------------------------------------------- subjects

    @Test
    void aSubjectEditWinsOverAReleaseAndResetsToTheShippedOne() {
        ok(svc("update#EmailTemplate", [emailTemplateId: WELCOME, subject: 'Welcome aboard, ${tenantLabel}']))
        assertEquals('Welcome aboard, ${tenantLabel}', templateSubject(WELCOME))
        assertEquals('Welcome aboard, ${tenantLabel}', EmailCopy.effectiveSubject(ec, WELCOME))
        Map shown = ok(svc("get#EmailTemplate", [emailTemplateId: WELCOME])).result
        assertEquals([true, "You're in: \${tenantLabel} on Darpan"], [shown.subjectEdited, shown.defaultSubject])

        // a release's seed reload rewrites EmailTemplate.subject; the edit has its own row, and the next send puts it back
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/TenantWelcomeEmailSeedData.xml")
        assertEquals("You're in: \${tenantLabel} on Darpan", templateSubject(WELCOME), "the reload wrote the shipped subject")
        assertEquals('Welcome aboard, ${tenantLabel}', row(WELCOME, "subject").copyText, "the edit survived the reload")
        assertEquals('Welcome aboard, ${tenantLabel}', EmailCopy.effectiveSubject(ec, WELCOME))
        EmailCopy.applySubject(ec, WELCOME)
        assertEquals('Welcome aboard, ${tenantLabel}', templateSubject(WELCOME))

        // the sender hook: a real send call runs the SECA first (the send itself has no mail server here)
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/TenantWelcomeEmailSeedData.xml")
        ec.service.sync().name("org.moqui.impl.EmailServices.send#EmailTemplate").ignorePreviousError(true)
                .parameters([emailTemplateId: WELCOME, toAddresses: "riya@example.com", bodyParameters: EmailSamples.OTHER.TenantWelcome]).call()
        ec.message.clearErrors()
        assertEquals('Welcome aboard, ${tenantLabel}', templateSubject(WELCOME), "the pre-send hook applies the edit")

        ok(svc("reset#EmailTemplate", [emailTemplateId: WELCOME, subject: true]))
        assertEquals("You're in: \${tenantLabel} on Darpan", templateSubject(WELCOME))
        assertEquals("You're in: \${tenantLabel} on Darpan", EmailCopy.effectiveSubject(ec, WELCOME))
    }

    /** The password reset is sent with no one signed in (anonymous-view): the subject write must not depend on a user. */
    @Test
    void anAnonymousSendAppliesAnEditedSubject() {
        ok(svc("update#EmailTemplate", [emailTemplateId: WELCOME, subject: 'Welcome aboard, ${tenantLabel}']))
        ok(svc("update#EmailTemplate", [emailTemplateId: CHANGED, blocks: [note: "Not you? Tell us."]]))
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/TenantWelcomeEmailSeedData.xml")
        assertEquals("You're in: \${tenantLabel} on Darpan", templateSubject(WELCOME), "the reload wrote the shipped subject")
        Map holder = [:]
        Thread worker = new Thread({
            ExecutionContext tec = Moqui.getExecutionContextFactory().getExecutionContext()   // no one signed in, authz on
            try {
                holder.authzWasOn = !tec.artifactExecution.disableAuthz()
                tec.artifactExecution.enableAuthz()
                EmailCopy.applySubject(tec, WELCOME)
                holder.note = EmailCopy.text(tec, CHANGED, "note", [:])
            } catch (Throwable t) { holder.failure = t } finally { tec.destroy() }
        } as Runnable, "email-anonymous-send")
        worker.start()
        worker.join()
        assertNull(holder.failure)
        assertEquals(true, holder.authzWasOn, "the anonymous caller runs with authz on")
        assertEquals("Not you? Tell us.", holder.note, "an edited block is read with no one signed in, not silently the default")
        assertEquals('Welcome aboard, ${tenantLabel}', templateSubject(WELCOME), "applied with no one signed in")
    }

    @Test
    void everyAcceptedSubjectExpandsTheSameThroughTheEngineAndThePureSubstitution() {
        List<List> cases = []
        EmailCopy.seedSubjects(ec).each { String id, String subject -> cases << [id, subject] }
        cases << [WELCOME, "Welcome aboard, \${tenantLabel}! (It's 100% ready — really; a/b + c & d?)"] << [CHANGED, "Password changed – Darpan’s notice: ok."] <<
                ["DARPAN_2FA_REMINDER", 'Re: ${subjectLine}'] << ["DARPAN_AUDIT_DIGEST", 'Digest ${digestSubject} ${digestSubject}']
        cases.each { List c ->
            String id = c[0], subject = c[1]
            assertNull(EmailCopy.subjectProblem(ec, id, subject), subject)
            Map params = EmailSamples.params(EmailCopy.screenFor(ec, id), null)
            ec.context.push()
            try {
                ec.context.putAll(params)
                assertEquals(EmailCopy.previewSubject(ec, id, subject, params), ec.resource.expand(subject, ""), subject)
            } finally { ec.context.pop() }
        }
    }

    @Test
    void aFailingSubjectWriteNeverStopsASend() {
        ok(svc("update#EmailTemplate", [emailTemplateId: WELCOME, subject: 'Welcome aboard, ${tenantLabel}']))
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/TenantWelcomeEmailSeedData.xml")
        def broken = [entity: ec.entity, resource: ec.resource, transaction: [runRequireNew: { a, b, c -> throw new IllegalStateException("boom") }]]
        EmailCopy.applySubject(broken, WELCOME) // must not throw
        assertEquals("You're in: \${tenantLabel} on Darpan", templateSubject(WELCOME), "nothing written")
        // a stored edit that fails the rules is skipped, not applied
        def r = row(WELCOME, "subject"); r.set("copyText", '"""+1+"""${tenantLabel}'); r.update()
        EmailCopy.applySubject(ec, WELCOME)
        assertEquals("You're in: \${tenantLabel} on Darpan", templateSubject(WELCOME))
        assertEquals("You're in: \${tenantLabel} on Darpan", EmailCopy.effectiveSubject(ec, WELCOME))
    }

    @Test
    void everyShippedSubjectPassesItsOwnRules() {
        EmailCopy.seedSubjects(ec).each { String id, String subject ->
            assertNull(EmailCopy.subjectProblem(ec, id, subject), id)
        }
        assertEquals(12, EmailCopy.seedSubjects(ec).size())
    }

    // ---------------------------------------------------------------- access

    @Test
    void onlyASuperAdminGetsIn() {
        ["get#EmailTemplates": [:], "get#EmailTemplate": [emailTemplateId: CHANGED], "preview#EmailTemplate": [emailTemplateId: CHANGED],
         "update#EmailTemplate": [emailTemplateId: CHANGED, subject: "Nope"], "reset#EmailTemplate": [emailTemplateId: CHANGED]].each { String method, Map params ->
            Map out = svc(method, params, PLAIN)
            assertTrue(out.authzDenied != null || out.hasError, "${method} must refuse a plain user")
        }
        assertNull(EmailCopy.row(ec, CHANGED, "subject"))
        assertEquals("Your Darpan password was changed", templateSubject(CHANGED))
    }

    // ---------------------------------------------------------------- helpers

    private String render(String screen, Map params) {
        ec.context.push()
        try {
            ec.context.putAll(params)
            return ec.screen.makeRender().rootScreen("component://darpan/screen/email/${screen}.xml".toString())
                    .webappName("webroot").renderMode("html").render()
        } finally {
            ec.context.pop()
        }
    }

    private String templateSubject(String id) {
        return ec.entity.find("moqui.basic.email.EmailTemplate").condition("emailTemplateId", id).useCache(false).one().subject
    }

    private def row(String id, String blockId) {
        return ec.entity.find("darpan.email.EmailCopyBlock").condition([emailTemplateId: id, blockId: blockId]).disableAuthz().useCache(false).one()
    }

    private List auditRows() {
        return ec.entity.find("darpan.admin.AdminAuditLog").orderBy("auditTimestamp").disableAuthz().useCache(false).list()
    }

    private Map svc(String method, Map params, String username = SUPER) {
        Map holder = [:]
        Thread worker = new Thread({
            ExecutionContext tec = Moqui.getExecutionContextFactory().getExecutionContext()
            try {
                if (!tec.user.internalLoginUser(username)) throw new IllegalStateException("could not log in ${username}")
                try {
                    holder.result = tec.service.sync().name(S + method).parameters(new LinkedHashMap(params)).call()
                } catch (ArtifactAuthorizationException e) {
                    holder.authzDenied = e
                }
                holder.errors = new ArrayList<String>(tec.message.errors) + (tec.message.validationErrors*.message ?: [])
                holder.hasError = tec.message.hasError()
            } catch (Throwable t) {
                holder.failure = t
            } finally {
                tec.destroy()
            }
        } as Runnable, "email-admin-smoke")
        worker.start()
        worker.join()
        if (holder.failure) throw (Throwable) holder.failure
        return holder
    }

    private static Map ok(Map out) {
        assertNull(out.authzDenied, "unexpected authz denial: ${out.authzDenied}")
        assertFalse(out.hasError as boolean, "unexpected errors: ${out.errors}")
        return out
    }
}
