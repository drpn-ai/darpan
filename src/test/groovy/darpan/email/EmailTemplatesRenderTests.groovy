package darpan.email

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

    private static final String AT = "18 Nov 2026 at 10:53 IST"
    /** Screen -> sample parameters. Security notices: no link of any kind, not even a stylesheet. */
    private static final Map<String, Map> SECURITY = [
            PasswordResetCode    : [firstName: "Riya", code: "042117", expiresMinutes: 15],
            PasswordChanged      : [firstName: "Riya", changedAt: AT],
            RecoveryEmailSet     : [firstName: "Riya", isOldAddress: false],
            TwoFactorPasskeyAdded: [firstName: "Riya", passkeyName: "Work laptop", at: AT],
            TwoFactorAppAdded    : [firstName: "Riya", at: AT],
            TwoFactorRecoveryUsed: [firstName: "Riya", at: AT, recoveryCodesLeft: 7],
            TwoFactorReset       : [firstName: "Riya", at: AT],
            TwoFactorCodesLocked : [firstName: "Riya", at: AT],
            TwoFactorReminder    : [firstName: "Riya", requiredDate: "2 December 2026", appUrl: "https://app.drpn.ai", past: false],
    ]
    private static final Map<String, Map> OTHER = [
            TenantWelcome: [firstName: "Riya", tenantLabel: "Malbon", rolePhrase: "as an Editor", username: "riya.k",
                            appBaseUrl: "https://app.drpn.ai"],
            AuditDigest  : [digestText: "Gorjana · Month close\nOpen 5 · New 2 · Fixed 1"],
    ]

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
        (SECURITY + OTHER).each { String screen, Map params ->
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
            AuditDigest            : ["AuditDigest", OTHER.AuditDigest],
    ]

    @Test
    void everyTemplateRendersAsBefore() {
        GOLDEN.each { String name, List v ->
            ["html", "text"].each { String mode ->
                String expected = Files.readString(Path.of("src/test/resources/email-golden/${name}.${mode}"))
                assertEquals(expected, render(v[0] as String, v[1] as Map, mode), "${name} ${mode}")
            }
        }
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
            // a variable the default does not use is refused at render too: the default goes
            setCopy("DARPAN_PASSWORD_CHANGED", "note", 'Not you, ${firstName}?')
            assertTrue(render("PasswordChanged", SECURITY.PasswordChanged, "html").contains("Not you? Tell your Darpan administrator now."))
        } finally {
            setCopy("DARPAN_PASSWORD_CHANGED", "body", null)
            setCopy("DARPAN_PASSWORD_CHANGED", "note", null)
        }
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
