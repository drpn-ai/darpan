package darpan.facade.auth

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-028 — forgot password and recovery email, against a booted Moqui on H2.
 *
 * Mail never leaves the JVM: PasswordResetSupport.sendEmail is the one seam every send goes through,
 * and each test swaps it for a recorder, so a test asserts exactly which template went to which address
 * with which parameters (including the code, which nothing else ever returns).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PasswordResetServicesSmokeTests {
    private ExecutionContext ec
    private Closure originalSendEmail
    private List<Map> sent = []

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "password-reset-smoke")
        ec.entity.makeDataLoader()
                .location("component://darpan/data/SecuritySeedData.xml")
                .dummyFks(true)
                .load()
        ec.entity.makeDataLoader()
                .location("component://darpan/data/PasswordResetEmailSeedData.xml")
                .dummyFks(true)
                .load()
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void recordMail() {
        originalSendEmail = PasswordResetSupport.sendEmail
        sent = []
        PasswordResetSupport.sendEmail = { def ecArg, String templateId, String to, Map params ->
            sent << [templateId: templateId, to: to, params: params]
        }
        // The seed carries SYSTEM by id alone (EmailTemplate FKs it); a test that wants mail configured sets smtpHost.
        ec.entity.makeValue("moqui.basic.email.EmailServer").setAll([emailServerId: "SYSTEM", smtpHost: null]).createOrUpdate()
    }

    @AfterEach
    void restoreMail() {
        PasswordResetSupport.sendEmail = originalSendEmail
    }

    // ---- Task 1: email plumbing ----

    @Test
    void templatesSeeded() {
        [PasswordResetSupport.TEMPLATE_RESET_CODE, PasswordResetSupport.TEMPLATE_PASSWORD_CHANGED,
         PasswordResetSupport.TEMPLATE_RECOVERY_SET, PasswordResetSupport.TEMPLATE_RECOVERY_CONFLICT].each { String id ->
            def template = ec.entity.find("moqui.basic.email.EmailTemplate").condition("emailTemplateId", id)
                    .disableAuthz().one()
            assertNotNull(template, "missing EmailTemplate ${id}")
            assertEquals("SYSTEM", template.emailServerId)
            assertTrue(((String) template.fromAddress).contains("DARPAN_MAIL_FROM"), "${id} fromAddress")
            assertEquals("Darpan", template.fromName)
        }
    }

    @Test
    void resetCodeBodyRendersCode() {
        String html = renderBody("component://darpan/screen/email/PasswordResetCode.xml",
                [code: "042117", expiresMinutes: 15, username: "u1"])
        assertTrue(html.contains("042117"), html)
        assertTrue(html.contains("15 minutes"), html)
        assertFalse(html.contains("<b>") || html.contains("<strong>") || html.contains("href"), html)
    }

    @Test
    void notConfiguredWithoutSystemServer() {
        assertFalse(PasswordResetSupport.isEmailConfigured(ec))
    }

    private String renderBody(String location, Map params) {
        ec.context.push()
        try {
            ec.context.putAll(params)
            return ec.screen.makeRender().rootScreen(location).webappName("webroot").renderMode("html").render()
        } finally {
            ec.context.pop()
        }
    }
}
