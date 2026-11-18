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
 * DAR-BE-298: every Darpan notice wears EmailFrame and keeps the email rules, in both parts of the message. Each render
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
    ]
    private static final Map<String, Map> OTHER = [
            TenantWelcome: [firstName: "Riya", tenantLabel: "Malbon", rolePhrase: "as an Editor", username: "riya.k",
                            appBaseUrl: "https://app.drpn.ai"],
            AuditDigest  : [digestText: "Gorjana · Month close\nOpen 5 · New 2 · Fixed 1"],
    ]

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "email-templates-render")
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
            assertFalse(html.contains("<a "), "${screen}: no link")
            if (SECURITY.containsKey(screen)) assertFalse(html.contains("href"), "${screen}: no outside address in a security email")
            if (screen != "AuditDigest") assertTrue(html.contains("— Darpan"), "${screen}: signed")

            String text = render(screen, params, "text")
            assertTrue(text.trim().length() > 0, "${screen}: a plain-text part")
            assertFalse(text.contains("<table") || text.contains("<style"), "${screen}: the frame stays out of the text part\n${text}")
        }
    }

    @Test
    void theCodeIsTheTileAndTheNotYouLineIsBehindTheRule() {
        String html = render("PasswordResetCode", SECURITY.PasswordResetCode, "html")
        assertTrue((html =~ /class="dp-code"[^>]*>\s*<span[^>]*>042117</).find(), html)
        assertTrue((html =~ /class="dp-note"[^>]*>\s*<span[^>]*>Didn(&#39;|')t ask for this\?/).find(), html)
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
