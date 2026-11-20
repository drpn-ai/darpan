package darpan.email

import darpan.audit.AuditDigestSupport
import darpan.audit.AuditRunMessage
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Instant

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-298: every Darpan notice wears EmailFrame, carries one or two user guide links (docs.drpn.ai only), and keeps the email rules, in both parts of the message. Each render
 * is also written to build/email-previews/<screen>.html, which is how a copy change is looked at before it ships (the
 * darpan-email skill's review step).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EmailTemplatesRenderTests {
    private ExecutionContext ec
    private Path previews

    private static final String AT = EmailSamples.AT
    private static final Map<String, Map> SECURITY = EmailSamples.SECURITY
    private static final Map<String, Map> OTHER = EmailSamples.OTHER

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "email-templates-render")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/EmailCopySeedData.xml")
        previews = Path.of("build", "email-previews")
        Files.createDirectories(previews)
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @Test
    void everyNoticeWearsTheFrameAndKeepsTheRules() {
        (SECURITY + OTHER - [AuditRun: OTHER.AuditRun]).each { String screen, Map params ->
            String html = render(screen, params, "html")
            Files.writeString(previews.resolve("${screen}.html"), html)
            assertTrue(html.contains('class="dp-card"'), "${screen}: inside EmailFrame\n${html}")
            assertTrue(html.contains("DARPAN · "), "${screen}: the eyebrow")
            assertFalse(html.contains("<b>") || html.contains("<strong>"), "${screen}: no bold")
            List<String> hrefs = (html =~ /href="([^"]*)"/).collect { it[1] as String }
            assertTrue(hrefs.size() in 1..2, "${screen}: one or two user guide links, got ${hrefs}")
            hrefs.each { assertTrue(it.startsWith("https://docs.drpn.ai/"), "${screen}: links only to the user guide, got ${it}") }
            if (screen != "AuditDigest") assertTrue(html.contains("— Darpan"), "${screen}: signed")

            String text = render(screen, params, "text")
            assertTrue(text.trim().length() > 0, "${screen}: a plain-text part")
            assertFalse(text.contains("<table") || text.contains("<style"), "${screen}: the frame stays out of the text part\n${text}")
            assertTrue(text.contains("User guide") && text.contains("https://docs.drpn.ai/"), "${screen}: the guide links in the text part\n${text}")
        }
    }

    @Test
    void theCodeIsTheTileAndTheNotYouLineIsBehindTheRule() {
        String html = render("PasswordResetCode", SECURITY.PasswordResetCode, "html")
        assertTrue((html =~ /class="dp-code"[^>]*>\s*<span[^>]*>042117</).find(), html)
        assertTrue((html =~ /class="dp-note"[^>]*>\s*<span[^>]*>Didn(&#39;|')t ask for this\?/).find(), html)
    }

    @Test
    void aGuideLinkIsADocsPageAndShowsWhereItGoes() {
        String html = EmailFrame.guides([["Two-factor sign-in", "guides/two-factor#set-it-up"]])
        assertTrue(html.contains('href="https://docs.drpn.ai/guides/two-factor#set-it-up"'), html)
        assertTrue(html.contains(">docs.drpn.ai/guides/two-factor</span>"), html)
        ["https://evil.example/x", "//evil.example", "guides/two-factor?next=x", "../x y"].each { String bad ->
            assertThrows(IllegalArgumentException) { EmailFrame.guides([["Bad", bad]]) }
        }
        assertThrows(IllegalArgumentException) { EmailFrame.guides([]) }
        assertThrows(IllegalArgumentException) { EmailFrame.guides([["A", "guides/two-factor#sign-in"], ["B", "guides/two-factor#set-it-up"]]) }
        assertThrows(IllegalArgumentException) { EmailFrame.guides([["a", "a"], ["b", "b"], ["c", "c"]]) }
    }

    @Test
    void theReminderReadsRightOnBothSidesOfTheDate() {
        Map after = SECURITY.TwoFactorReminder + [past: true]
        String html = render("TwoFactorReminder", after, "html")
        Files.writeString(previews.resolve("TwoFactorReminderAfterDate.html"), html)
        assertTrue(html.contains("now needs a second step") && !html.contains("2 December 2026"), html)
        String before = render("TwoFactorReminder", SECURITY.TwoFactorReminder, "html")
        assertTrue(before.contains("becomes required for Darpan on 2 December 2026"), before)
    }

    @Test
    void aSmileIsOneShortPlainLineOrTheTemplatesOwn() {
        ["Spare keys: the unsung heroes.", "Clean slate.", "(Future you says thanks.)", "Don't get attached."].each {
            assertTrue(EmailSmile.ok(it), it)
        }
        ["", null, "Line one\nline two", "Your code is 042117.", "Visit https://evil.example", "Click here to keep your account",
         "Reply with your password", "Wow!", "A line that rambles on and on well past the eighty characters an email smile gets",
         "<b>bold</b>", "write to x@y.co"].each { assertFalse(EmailSmile.ok(it), String.valueOf(it)) }

        // No agent answers in this JVM (the deployment switch is off): every template sends its own line.
        assertTrue(render("TwoFactorPasskeyAdded", SECURITY.TwoFactorPasskeyAdded, "html").contains("Look at you, going passwordless."))
        assertTrue(render("PasswordResetCode", SECURITY.PasswordResetCode, "html").contains("Forgot your Darpan password? Fair. Month-end does that."))
        assertTrue(render("TwoFactorRecoveryUsed", SECURITY.TwoFactorRecoveryUsed, "text").contains("Phone in the other bag? Happens."))
    }

    @Test
    void aPasskeyNameIsText() {
        String html = render("TwoFactorPasskeyAdded", [firstName: "Riya", passkeyName: "<img src=x>", at: AT], "html")
        assertFalse(html.contains("<img src=x>"), html)
        assertTrue(html.contains("&lt;img src=x&gt;"), html)
    }

    @Test
    void aMomentNamesTheReadersZone() {
        Timestamp at = Timestamp.from(Instant.parse("2026-11-18T05:23:00Z"))
        assertEquals("18 Nov 2026 at 10:53 IST", EmailFrame.when(at, "Asia/Kolkata"))
        assertEquals("17 Nov 2026 at 21:23 PST", EmailFrame.when(at, "America/Los_Angeles"))
        assertEquals("18 Nov 2026 at 05:23 UTC", EmailFrame.when(at, null))
        assertEquals("18 Nov 2026 at 05:23 UTC", EmailFrame.when(at, "Not/AZone"))
    }

    /** DAR-BE-303: name -> [screen, sample params]; src/test/resources/email-golden/<name>.html|text hold what each rendered
     *  before the words moved into EmailCopyBlock rows (the reminder's first line is the approved new default). */
    private static final Map<String, List> GOLDEN = [
            PasswordResetCode      : ["PasswordResetCode", SECURITY.PasswordResetCode],
            PasswordChanged        : ["PasswordChanged", SECURITY.PasswordChanged],
            RecoveryEmailSetNew    : ["RecoveryEmailSet", SECURITY.RecoveryEmailSet],
            RecoveryEmailSetOld    : ["RecoveryEmailSet", SECURITY.RecoveryEmailSet + [isOldAddress: true]],
            TwoFactorPasskeyAdded  : ["TwoFactorPasskeyAdded", SECURITY.TwoFactorPasskeyAdded],
            TwoFactorAppAdded      : ["TwoFactorAppAdded", SECURITY.TwoFactorAppAdded],
            TwoFactorRecoveryUsed  : ["TwoFactorRecoveryUsed", SECURITY.TwoFactorRecoveryUsed],
            TwoFactorReset         : ["TwoFactorReset", SECURITY.TwoFactorReset],
            TwoFactorCodesLocked   : ["TwoFactorCodesLocked", SECURITY.TwoFactorCodesLocked],
            TwoFactorReminderBefore: ["TwoFactorReminder", SECURITY.TwoFactorReminder],
            TwoFactorReminderAfter : ["TwoFactorReminder", SECURITY.TwoFactorReminder + [past: true]],
            TenantWelcomeUrl       : ["TenantWelcome", OTHER.TenantWelcome],
            TenantWelcomeNoUrl     : ["TenantWelcome", OTHER.TenantWelcome - [appBaseUrl: "https://app.drpn.ai"]],
            // the digest as a send passes it: the finished text (chat gets the same text), here a made-up one
            AuditDigest            : ["AuditDigest", [digestText: "Gorjana · Month close\nOpen 5 · New 2 · Fixed 1", digestSubject: "Gorjana · Month close"]],
    ]

    /** DAR-BE-303: the audit run email by variant (the screen builds the body from the run's data through the copy blocks);
     *  golden files are what AuditRunMessage built before its words moved into blocks. */
    private static final Map<String, Map> AUDIT_RUN = [
            AuditRunFull         : [runFacts: AuditEmailSamples.runFacts(), runWords: AuditEmailSamples.runWords(), runBase: AuditEmailSamples.BASE],
            AuditRunNoWordsNoLink: [runFacts: AuditEmailSamples.runFacts(), runWords: [:], runBase: null],
            AuditRunClean        : [runFacts: AuditEmailSamples.cleanRunFacts(), runWords: [:], runBase: AuditEmailSamples.BASE],
    ]

    private static final String DIGEST_URL = "https://app.drpn.ai/audits/month-close?date=2026-10-22&tenantId=GORJANA"

    /** name -> [report, kind, fullList, weekAgo, url]; golden/<name>.text is the digest text as it was built before the blocks. */
    private static Map<String, List> digestVariants() {
        Map incomplete = AuditEmailSamples.digestReport()
        incomplete.health = [complete: false, summary: "Not complete: check invoices-without-etail-id did not run."]
        Map none = AuditEmailSamples.digestReport()
        none.summary = [open: 0, newToday: 0, fixedToday: 2]
        none.categories = [orders: [label: "Orders", counts: [open: 0], statements: [:]]]
        Map big = AuditEmailSamples.digestReport()
        big.categories.orders.statements.R2.findings = (1..400).collect { int i ->
            [id: "F-${1000 + i}".toString(), headline: "Invoice INV${i} has no Etail id", state: "open", ageDays: i % 9]
        }
        big.categories.orders.statements.R2.findingsOmitted = 25
        return [DigestDaily: [AuditEmailSamples.digestReport(), AuditDigestSupport.DAILY, false, null, DIGEST_URL],
                DigestWeekly: [AuditEmailSamples.digestReport(), AuditDigestSupport.WEEKLY, false, [summary: [open: 5]], DIGEST_URL],
                DigestFullList: [AuditEmailSamples.digestReport(), AuditDigestSupport.DAILY, true, null, DIGEST_URL],
                DigestIncomplete: [incomplete, AuditDigestSupport.DAILY, false, null, null],
                DigestNothingOpen: [none, AuditDigestSupport.DAILY, false, null, DIGEST_URL],
                DigestFullCut: [big, AuditDigestSupport.DAILY, true, null, DIGEST_URL]]
    }

    private String digest(List v) {
        return AuditDigestSupport.render(v[0] as Map, v[1] as String, v[2] as boolean, v[3] as Map, v[4] as String,
                new EmailCopyReader(ec, "DARPAN_AUDIT_DIGEST"))
    }

    @Test
    void theAuditEmailsRenderAsBeforeThroughTheBlocks() {
        AUDIT_RUN.each { String name, Map params ->
            ["html", "text"].each { String mode ->
                String expected = Files.readString(Path.of("src/test/resources/email-golden/${name}.${mode}"))
                String actual = render("AuditRun", params, mode)
                Files.writeString(previews.resolve("${name}.${mode == 'html' ? 'html' : 'txt'}"), actual)
                // the screen's own indentation wraps the body, as it did the passthrough; the body itself is compared byte for byte
                assertEquals(expected.trim(), actual.trim(), "${name} ${mode}")
            }
        }
        digestVariants().each { String name, List v ->
            assertEquals(Files.readString(Path.of("src/test/resources/email-golden/${name}.text")), digest(v), name)
        }
        // the chat card shares the subtitle's block and is otherwise unchanged
        assertEquals(Files.readString(Path.of("src/test/resources/email-golden/AuditRunCard.json")), groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(
                AuditRunMessage.card(AuditEmailSamples.runFacts(), AuditEmailSamples.runWords(), AuditEmailSamples.BASE, new EmailCopyReader(ec, "DARPAN_AUDIT_RUN")))))
    }

    @Test
    void theDigestScreenBuildsItsTextFromTheReportSoAPreviewShowsADraft() {
        Map sample = EmailSamples.params("AuditDigest", null)
        String text = render("AuditDigest", sample, "text")
        assertTrue(text.contains(Files.readString(Path.of("src/test/resources/email-golden/DigestDaily.text")).replace(DIGEST_URL, sample.digestReportUrl as String)), text)
        assertTrue(text.contains("https://docs.drpn.ai/guides/audits"), text)
        String html = render("AuditDigest", sample, "html")
        assertTrue(html.contains("Oldest open") && html.contains('class="dp-card"'), html)
        // a draft block stands in, as the admin preview does
        String draft = EmailCopy.withDraft(["DARPAN_AUDIT_DIGEST/list.oldest": "Stuck longest"]) { render("AuditDigest", sample, "text") }
        assertTrue(draft.contains("Stuck longest") && !draft.contains("Oldest open"), draft)
    }

    @Test
    void anAuditRunEditIsUsedAndABadOneFallsBack() {
        Map full = AUDIT_RUN.AuditRunFull
        try {
            setCopy("DARPAN_AUDIT_RUN", "act.heading", "Do these first")
            setCopy("DARPAN_AUDIT_RUN", "rule.all", 'Every one of the ${open} is in ${file}')
            setCopy("DARPAN_AUDIT_RUN", "notrun", 'Skipped today: ${rules}.')
            String html = render("AuditRun", full, "html"), text = render("AuditRun", full, "text")
            assertTrue(html.contains("DO THESE FIRST") && text.contains("\nDo these first\n"), "heading in both parts")
            assertTrue(text.contains("  Every one of the 1 is in gorjana-month-close-2026-11-R10.csv · ${AuditEmailSamples.BASE}/audits/month-close"), text)
            assertTrue(html.contains("Skipped today: R9.") && text.contains("Skipped today: R9."))
            assertFalse(html.contains("ACT ON FIRST") || html.contains("did not run on this morning"))

            // an address, an unknown variable, empty text: the default goes, and the report link stays the code-built one
            setCopy("DARPAN_AUDIT_RUN", "button", "Open https://evil.example/login")
            setCopy("DARPAN_AUDIT_RUN", "act.heading", 'Act on ${nope}')
            setCopy("DARPAN_AUDIT_RUN", "attachments", "   ")
            html = render("AuditRun", full, "html")
            assertTrue(html.contains(">Open the report in Darpan</a>") && html.contains("ACT ON FIRST") && html.contains("Each attachment has one row"), html)
            assertFalse(html.contains("evil.example"), html)

            // the guide links are an edit like any other: valid ones show, one the frame refuses keeps the default
            setCopy("DARPAN_AUDIT_RUN", "guides", "Just one|guides/audits")
            assertEquals(1 + (html =~ /href="${java.util.regex.Pattern.quote(AuditEmailSamples.BASE)}[^"]*"/).count,
                    1 + (render("AuditRun", full, "html") =~ /href="${java.util.regex.Pattern.quote(AuditEmailSamples.BASE)}[^"]*"/).count)
            html = render("AuditRun", full, "html")
            assertEquals(1, (html =~ /href="https:\/\/docs\.drpn\.ai\//).count)
            setCopy("DARPAN_AUDIT_RUN", "guides", "Evil|https://evil.example/x")
            assertEquals(2, (render("AuditRun", full, "html") =~ /href="https:\/\/docs\.drpn\.ai\//).count)
        } finally {
            ["act.heading", "rule.all", "notrun", "button", "attachments", "guides"].each { setCopy("DARPAN_AUDIT_RUN", it, null) }
        }
    }

    @Test
    void aDigestEditReadsInChatAndEmailAlikeAndNeverAddsALink() {
        List v = digestVariants().DigestDaily
        try {
            setCopy("DARPAN_AUDIT_DIGEST", "list.oldest", "Stuck longest")
            setCopy("DARPAN_AUDIT_DIGEST", "report", "Open the report:")
            setCopy("DARPAN_AUDIT_DIGEST", "age.many", '${days}d')
            String text = digest(v)
            assertTrue(text.contains("\nStuck longest\nF-1 · R2 · 6d · Invoice INV1 has no Etail id"), text)
            assertTrue(text.endsWith("Open the report: ${DIGEST_URL}"), "the link is the code-built one: ${text}")
            setCopy("DARPAN_AUDIT_DIGEST", "report", "Report at https://evil.example:")
            assertTrue(digest(v).endsWith("Report: ${DIGEST_URL}"))
            assertEquals("Nothing open.", digest(digestVariants().DigestNothingOpen).readLines().findAll { it }[-2])
        } finally {
            ["list.oldest", "report", "age.many"].each { setCopy("DARPAN_AUDIT_DIGEST", it, null) }
        }
    }

    @Test
    void markupWrittenStraightToARowNeverReachesTheEmailOrChat() {
        try {
            setCopy("DARPAN_AUDIT_RUN", "tile.open", "<b>x</b>")
            String html = render("AuditRun", AUDIT_RUN.AuditRunFull, "html")
            assertFalse(html.contains("<b>x</b>") || html.contains("&lt;b&gt;x"), "the default goes, not the markup")
            assertTrue(html.contains("text-transform:uppercase;\">Open</div>"), html)
            setCopy("DARPAN_AUDIT_DIGEST", "list.oldest", "<users/all>")
            String text = digest(digestVariants().DigestDaily)
            assertFalse(text.contains("<users/all>"), text)
            assertTrue(text.contains("\nOldest open\n"), text)
        } finally {
            setCopy("DARPAN_AUDIT_RUN", "tile.open", null)
            setCopy("DARPAN_AUDIT_DIGEST", "list.oldest", null)
        }
    }

    @Test
    void everyBlockTheAuditBuildersReadIsSeeded() {
        [["src/main/groovy/darpan/audit/AuditRunMessage.groovy", "DARPAN_AUDIT_RUN"],
         ["src/main/groovy/darpan/audit/AuditDigestSupport.groovy", "DARPAN_AUDIT_DIGEST"]].each { List f ->
            String x = new File(f[0] as String).text
            Set<String> blocks = (x =~ /copy\.text\(\s*['"]([a-z.]+)['"]/).collect { it[1] as String }.toSet()
            assertTrue(blocks.size() >= 10, "${f[0]}: the scan found the reads (${blocks})")
            blocks.each { String id ->
                assertNotNull(ec.entity.find("darpan.email.EmailCopyBlock").condition("emailTemplateId", f[1]).condition("blockId", id).one(), "${f[0]}: no seed row for ${f[1]}/${id}")
            }
            assertNotNull(ec.entity.find("darpan.email.EmailCopyBlock").condition("emailTemplateId", f[1]).condition("blockId", "guides").one())
        }
    }

    @Test
    void everyTemplateRendersAsBefore() {
        GOLDEN.each { String name, List v ->
            ["html", "text"].each { String mode ->
                String expected = Files.readString(Path.of("src/test/resources/email-golden/${name}.${mode}"))
                String actual = render(v[0] as String, v[1] as Map, mode)
                Files.writeString(previews.resolve("golden-${name}.${mode}"), actual)
                assertEquals(expected, actual, "${name} ${mode}")
            }
        }
    }

    /** DAR-BE-315: the plain-text part carries every sentence the html does, in order, as the copy has it: a text-mode label cut
     *  each one at 132 columns and a branch under fail-widgets rendered empty, so the reminder lost its app address and its
     *  after-date lines. Every template, every branch (the reminder before and after the date, both recovery and welcome sides). */
    @Test
    void theTextPartCarriesEveryBlockTheHtmlDoesInOrderAndUncut() {
        GOLDEN.each { String name, List v ->
            String html = render(v[0] as String, v[1] as Map, "html"), text = render(v[0] as String, v[1] as Map, "text")
            List<String> blocks = (html =~ /(?s)<span class="text-inline">(.*?)<\/span>|<pre class="text-inline dp-pre">(.*?)<\/pre>/).collect { List m ->
                unescape((m[1] ?: m[2]) as String).trim()
            }.findAll { it }
            assertTrue(blocks.size() >= 1, "${name}: the scan found the html's blocks")
            int at = 0
            blocks.each { String block ->
                int found = text.indexOf(block, at)
                assertTrue(found >= 0, "${name}: the text part lost or cut a block the html carries, in order: [${block}]\n${text}")
                at = found + block.length()
            }
        }
    }

    @Test
    void noTextLineIsCutAtAColumn() {
        String before = render("TwoFactorReminder", SECURITY.TwoFactorReminder, "text")
        String after = render("TwoFactorReminder", SECURITY.TwoFactorReminder + [past: true], "text")
        [before, after].each { String t ->
            assertTrue(t.contains("Set one up in User Settings, under Two-factor, at https://app.drpn.ai. It takes about a minute, which is less time than the kettle."), t)
            assertTrue(t.contains("— Darpan"), t)
        }
        assertTrue(after.contains("Darpan now needs a second step when you sign in"), after)
        assertTrue(after.contains("Darpan will ask you to set it up the next time you sign in"), after)
        assertTrue(before.contains("becomes required for Darpan on 2 December 2026"), before)
        assertEquals("a\n\nb\n", EmailFrame.textOf(["a", null, " ", "b"]))
        String long150 = "x" * 300
        assertTrue(EmailFrame.textOf([long150]).contains(long150))
    }

    private static String unescape(String h) {
        return h.replace("&#39;", "'").replace("&quot;", '"').replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    }

    private String copyText(String templateId, String blockId) {
        return ec.entity.find("darpan.email.EmailCopyBlock").condition("emailTemplateId", templateId).condition("blockId", blockId).one().getString("copyText")
    }

    private void setCopy(String templateId, String blockId, String copyText) {
        def row = ec.entity.find("darpan.email.EmailCopyBlock").condition("emailTemplateId", templateId).condition("blockId", blockId).one()
        row.set("copyText", copyText)
        row.update()
    }

    @Test
    void aProductionEditIsUsed() {
        try {
            setCopy("DARPAN_PASSWORD_CHANGED", "body", 'Changed on ${changedAt}. ${smile}')
            String html = render("PasswordChanged", SECURITY.PasswordChanged, "html")
            assertTrue(html.contains("Changed on 18 Nov 2026 at 10:53 IST. New password, who dis?"), html)
            assertFalse(html.contains("signed out on the way"), html)
            // any variable the template provides is allowed in any of its blocks
            setCopy("DARPAN_PASSWORD_CHANGED", "note", 'Not you, ${firstName}?')
            assertTrue(render("PasswordChanged", SECURITY.PasswordChanged, "html").contains("Not you, Riya?"))
            // a variable the template does not have: the default goes
            setCopy("DARPAN_PASSWORD_CHANGED", "note", 'Not you, ${nope}?')
            assertTrue(render("PasswordChanged", SECURITY.PasswordChanged, "html").contains("Not you? Tell your Darpan administrator now."))
        } finally {
            setCopy("DARPAN_PASSWORD_CHANGED", "body", null)
            setCopy("DARPAN_PASSWORD_CHANGED", "note", null)
        }
    }

    @Test
    void anEditNeverCarriesAWebAddress() {
        try {
            ["Not you? Go to https://evil.example/reset", "Not you? www.evil.example", "Not you? Open evil.example.com now", "Not you? mail me at x.io"].each { String bad ->
                setCopy("DARPAN_PASSWORD_CHANGED", "note", bad)
                String html = render("PasswordChanged", SECURITY.PasswordChanged, "html")
                assertFalse(html.contains("evil.example") || html.contains("x.io"), bad)
                assertTrue(html.contains("Not you? Tell your Darpan administrator now."), bad)
            }
            setCopy("DARPAN_PASSWORD_CHANGED", "smile", "Click here today")
            assertTrue(render("PasswordChanged", SECURITY.PasswordChanged, "html").contains("New password, who dis?"))
        } finally {
            setCopy("DARPAN_PASSWORD_CHANGED", "note", null)
            setCopy("DARPAN_PASSWORD_CHANGED", "smile", null)
        }
    }

    @Test
    void noSeededDefaultCarriesAWebAddress() {
        def rows = ec.entity.find("darpan.email.EmailCopyBlock").list()
        assertTrue(rows.size() > 50, "seed rows loaded")
        rows.each { r ->
            assertNull(EmailCopy.problem(r.getString("emailTemplateId"), r.getString("blockId"), r.getString("defaultText")),
                    "${r.emailTemplateId}/${r.blockId}")
        }
        rows.each { r -> assertFalse(((r.getString("defaultText") ?: "") =~ /[<>]/).find(), "${r.emailTemplateId}/${r.blockId} carries markup") }
        assertNotNull(EmailCopy.problem("T", "note", "<b>x</b>"))
        assertNotNull(EmailCopy.problem("T", "note", "hi <users/all>"))
        assertNotNull(EmailCopy.problem("T", "note", "see https://x.example"))
        assertNotNull(EmailCopy.problem("T", "note", "see www.x"))
        assertNotNull(EmailCopy.problem("T", "smile", "Wow!"))
        assertNull(EmailCopy.problem("T", "note", 'Open \${appUrl} in your browser.'))
        // the guide labels (and the paths) of every shipped guides block, and every subject block, pass their own rules
        rows.findAll { it.getString("blockId") == EmailCopy.GUIDES }.each { r ->
            List<List<String>> links = EmailCopy.parseGuides(r.getString("defaultText"))
            links.each { assertNull(EmailCopy.guideLabelProblem(it[0]), "${r.emailTemplateId}: ${it[0]}") }
            assertNull(EmailCopy.editProblem(ec, r.getString("emailTemplateId"), EmailCopy.GUIDES, r.getString("defaultText")), r.getString("emailTemplateId"))
        }
        rows.findAll { EmailCopy.isSubjectBlock(it.getString("blockId")) }.each { r ->
            assertNull(EmailCopy.editProblem(ec, r.getString("emailTemplateId"), r.getString("blockId"), r.getString("defaultText")), r.getString("blockId"))
        }
        // every shipped block is a valid edit of itself (the save rules, not only the render rules)
        rows.each { r ->
            assertNull(EmailCopy.editProblem(ec, r.getString("emailTemplateId"), r.getString("blockId"), r.getString("defaultText")),
                    "${r.emailTemplateId}/${r.blockId}")
        }
    }

    /** DAR-BE-303 final review I1: a block may use only what the code that reads it passes; otherwise the default goes. */
    @Test
    void anEditUsingAVariableItsBlockIsNotPassedIsRefusedAndRendersTheDefault() {
        String why = EmailCopy.editProblem(ec, "DARPAN_AUDIT_RUN", "rule.clean", 'clean ${open}')
        assertTrue(why != null && why.contains('Unknown variable ${open}'), why)
        assertNull(EmailCopy.editProblem(ec, "DARPAN_AUDIT_RUN", "rule.open", '${open} to fix'))
        assertNotNull(EmailCopy.editProblem(ec, "DARPAN_AUDIT_RUN", "rule.open", '${open} of ${file}'), "file is rule.all's, not rule.open's")
        assertNotNull(EmailCopy.editProblem(ec, "DARPAN_AUDIT_DIGEST", "none", 'Nothing open (${open}).'))
        try {
            // written straight to the row (a data load, a transfer): still the default, never "clean "
            setCopy("DARPAN_AUDIT_RUN", "rule.clean", 'clean ${open}')
            assertEquals("clean", EmailCopy.text(ec, "DARPAN_AUDIT_RUN", "rule.clean", [:]))
            assertEquals("clean", new EmailCopyReader(ec, "DARPAN_AUDIT_RUN").text("rule.clean"))
            assertTrue(render("AuditRun", AUDIT_RUN.AuditRunFull, "html").contains(">clean</td>"))
            // a screen block the sender names a variable for but does not pass (no `smile` for the smile block itself)
            setCopy("DARPAN_PASSWORD_CHANGED", "smile", '${smile}')
            assertTrue(render("PasswordChanged", SECURITY.PasswordChanged, "html").contains("New password, who dis?"))
            // an edit whose variables ARE passed still applies
            setCopy("DARPAN_AUDIT_RUN", "rule.open", '${open} to fix')
            assertTrue(render("AuditRun", AUDIT_RUN.AuditRunFull, "html").contains("1 to fix"))
        } finally {
            setCopy("DARPAN_AUDIT_RUN", "rule.clean", null)
            setCopy("DARPAN_AUDIT_RUN", "rule.open", null)
            setCopy("DARPAN_PASSWORD_CHANGED", "smile", null)
        }
    }

    /** I2: a host of any ending, or an address, written to a row never renders; a guide label gets the same rules. */
    @Test
    void noHostOrAddressRendersFromABlockOrAGuideLabel() {
        try {
            ["Not you? bit.ly/x", "Not you? evil.ru", "Not you? darpan-login.help", "Not you? login.darpan.support", "Not you? x@evil.ru"].each { String bad ->
                setCopy("DARPAN_PASSWORD_CHANGED", "note", bad)
                String html = render("PasswordChanged", SECURITY.PasswordChanged, "html")
                assertTrue(html.contains("Not you? Tell your Darpan administrator now."), bad)
            }
            ["Evil evil.ru|guides/two-factor", "bit.ly/x|guides/two-factor", 'Hi ${firstName}|guides/two-factor', "a@b.co|guides/two-factor"].each { String bad ->
                setCopy("DARPAN_PASSWORD_CHANGED", "guides", bad)
                String html = render("PasswordChanged", SECURITY.PasswordChanged, "html")
                assertFalse(html.contains("evil.ru") || html.contains("bit.ly") || html.contains("b.co"), bad)
                assertTrue(html.contains("Lockouts and session length") && html.contains("Two-factor sign-in"), "the default links: ${bad}")
            }
        } finally {
            setCopy("DARPAN_PASSWORD_CHANGED", "note", null)
            setCopy("DARPAN_PASSWORD_CHANGED", "guides", null)
        }
    }

    /** I3: the subject words code builds are copy blocks; they follow the subject rules at render too. */
    @Test
    void theSubjectBlocksCodeBuildsFromAreEditableAndPlain() {
        assertEquals("Set up two-factor sign-in for Darpan by 2 December 2026", EmailCopy.text(ec, "DARPAN_2FA_REMINDER", "subject.before", [requiredDate: "2 December 2026"]))
        assertEquals("Two-factor sign-in is now required for Darpan", EmailCopy.text(ec, "DARPAN_2FA_REMINDER", "subject.after", [requiredDate: "2 December 2026"]))
        assertEquals("Gorjana Month close, 1–10 November: 57 open findings",
                AuditRunMessage.subject(AuditEmailSamples.runFacts(), new EmailCopyReader(ec, "DARPAN_AUDIT_RUN")))
        try {
            setCopy("DARPAN_2FA_REMINDER", "subject.before", 'Two-factor by ${requiredDate}, please')
            assertEquals("Two-factor by 2 December 2026, please", EmailCopy.text(ec, "DARPAN_2FA_REMINDER", "subject.before", [requiredDate: "2 December 2026"]))
            setCopy("DARPAN_AUDIT_RUN", "subject.run", '${open} open in ${auditName} for ${tenantName}')
            assertEquals("57 open in Month close for Gorjana", AuditRunMessage.subject(AuditEmailSamples.runFacts(), new EmailCopyReader(ec, "DARPAN_AUDIT_RUN")))
            // a quote, a breakout, a host, a variable the block was not given: refused at save, the default at render
            ['Say "hi" ${requiredDate}', 'x ${requiredDate} ${ec.entity}', "Go to bit.ly/x ${'$'}{requiredDate}", 'Hi ${firstName}', 'Hi `id`', 'a\nb'].each { String bad ->
                assertNotNull(EmailCopy.editProblem(ec, "DARPAN_2FA_REMINDER", "subject.before", bad), bad)
                setCopy("DARPAN_2FA_REMINDER", "subject.before", bad)
                assertEquals("Set up two-factor sign-in for Darpan by 2 December 2026",
                        EmailCopy.text(ec, "DARPAN_2FA_REMINDER", "subject.before", [requiredDate: "2 December 2026"]), bad)
            }
            assertNotNull(EmailCopy.editProblem(ec, "DARPAN_2FA_REMINDER", "subject.before", "x" * 201))
        } finally {
            setCopy("DARPAN_2FA_REMINDER", "subject.before", null)
            setCopy("DARPAN_AUDIT_RUN", "subject.run", null)
        }
    }

    /** M1: a release's seed reload writes defaultText only and leaves a production edit alone. */
    @Test
    void aSeedReloadKeepsAnEdit() {
        try {
            setCopy("DARPAN_PASSWORD_CHANGED", "note", "Not you? Say so.")
            ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/EmailCopySeedData.xml")
            assertEquals("Not you? Say so.", copyText("DARPAN_PASSWORD_CHANGED", "note"))
            assertTrue(render("PasswordChanged", SECURITY.PasswordChanged, "html").contains("Not you? Say so."))
        } finally {
            setCopy("DARPAN_PASSWORD_CHANGED", "note", null)
        }
    }

    @Test
    void aMissingBlockOrAFailingReadNeverStopsASend() {
        assertEquals("", EmailCopy.text(ec, "DARPAN_NOPE", "body", [:]))
        assertEquals([], EmailCopy.guides(ec, "DARPAN_NOPE"))
        assertEquals("", EmailCopy.guidesHtml([]))
        assertEquals("", EmailCopy.guidesText([]))
        assertEquals("", EmailCopy.guidesHtml([["Bad", "https://evil.example"]]))
        def broken = [entity: [find: { String n -> throw new IllegalStateException("db down") }], resource: ec.resource]
        assertEquals("Not you? Tell your Darpan administrator now.", EmailCopy.text(broken, "DARPAN_PASSWORD_CHANGED", "note", [:]))
        assertEquals(2, EmailCopy.guides(broken, "DARPAN_PASSWORD_CHANGED").size())
    }

    @Test
    void aBrokenEditFallsBackToTheDefault() {
        try {
            setCopy("DARPAN_PASSWORD_CHANGED", "note", "Not you? \${nope}")
            assertTrue(render("PasswordChanged", SECURITY.PasswordChanged, "html").contains("Not you? Tell your Darpan administrator now."))
            setCopy("DARPAN_PASSWORD_CHANGED", "note", "   ")
            assertTrue(render("PasswordChanged", SECURITY.PasswordChanged, "html").contains("Not you? Tell your Darpan administrator now."))
            // a guides edit that EmailFrame refuses (an outside address) keeps the default links; a valid edit is used
            setCopy("DARPAN_PASSWORD_CHANGED", "guides", "Evil|https://evil.example/x")
            String html = render("PasswordChanged", SECURITY.PasswordChanged, "html")
            assertFalse(html.contains("evil.example"), html)
            assertTrue(html.contains("https://docs.drpn.ai/guides/two-factor"), html)
            setCopy("DARPAN_PASSWORD_CHANGED", "guides", "Just one|guides/two-factor")
            assertEquals(1, (render("PasswordChanged", SECURITY.PasswordChanged, "html") =~ /href="/).count)
        } finally {
            setCopy("DARPAN_PASSWORD_CHANGED", "note", null)
            setCopy("DARPAN_PASSWORD_CHANGED", "guides", null)
        }
    }

    @Test
    void everyBlockAScreenReadsIsSeeded() {
        int reads = 0
        new File("screen/email").listFiles().findAll { it.name.endsWith(".xml") }.each { File f ->
            String x = f.text
            (x =~ /EmailCopy\.text\(ec, &quot;([A-Z0-9_]+)&quot;, &quot;([a-z.]+)&quot;/).each { List m ->
                reads++
                assertNotNull(ec.entity.find("darpan.email.EmailCopyBlock").condition("emailTemplateId", m[1]).condition("blockId", m[2]).one(),
                        "${f.name}: no seed row for ${m[1]}/${m[2]}")
            }
            (x =~ /EmailCopy\.guides\(ec, &quot;([A-Z0-9_]+)&quot;/).each { List m ->
                reads++
                assertNotNull(ec.entity.find("darpan.email.EmailCopyBlock").condition("emailTemplateId", m[1]).condition("blockId", "guides").one(),
                        "${f.name}: no guides row for ${m[1]}")
            }
        }
        assertTrue(reads > 50, "the scan found the reads (${reads})")
    }

    /** DAR-BE-325: with a temporary password the welcome shows it in its own code box after the sign-in line, in both
     *  parts; without one (the goldens above) there is no password line at all. */
    @Test
    void theWelcomeCarriesATemporaryPasswordInACodeBoxOnlyWhenThereIsOne() {
        String password = "AR6Yu<lK58Q?WlvM1"
        Map params = OTHER.TenantWelcome + [tempPassword: password]
        String html = render("TenantWelcome", params, "html")
        String text = render("TenantWelcome", params, "text")
        Files.writeString(previews.resolve("TenantWelcomePassword.html"), html)
        String line = "Your temporary password is below. Darpan asks you to swap it for your own the first time you sign in."
        assertTrue(html.contains(line), html)
        assertTrue(html.contains("dp-code"), "the password sits in a code box")
        assertTrue(html.contains("AR6Yu&lt;lK58Q?WlvM1"), "encoded like every value in a label")
        assertFalse(html.contains(password), "never raw html")
        assertTrue(html.indexOf("tenant menu") < html.indexOf(line), "after the sign-in line")
        assertTrue(text.contains(line + "\n\n" + password), text)
        [render("TenantWelcome", OTHER.TenantWelcome, "html"), render("TenantWelcome", OTHER.TenantWelcome, "text")].each { String bare ->
            assertFalse(bare.contains("temporary password"), bare)
        }
    }

    private String render(String screen, Map params, String mode) {
        ec.context.push()
        try {
            ec.context.putAll(params)
            return ec.screen.makeRender().rootScreen("component://darpan/screen/email/${screen}.xml".toString())
                    .webappName("webroot").renderMode(mode).render()
        } finally {
            ec.context.pop()
        }
    }
}
