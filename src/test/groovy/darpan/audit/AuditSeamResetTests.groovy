package darpan.audit

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertNotSame
import static org.junit.jupiter.api.Assertions.assertSame

/** DAR-OPS-026. resetForTests restores the audit email and dispatch seams to their production closures. */
class AuditSeamResetTests {

    @Test
    void digestSendEmailIsRestored() {
        Closure original = AuditDigestSupport.sendEmail
        try {
            AuditDigestSupport.sendEmail = { def ec, String to, Map body -> null }
            assertNotSame(original, AuditDigestSupport.sendEmail)
            AuditDigestSupport.resetForTests()
            assertSame(original, AuditDigestSupport.sendEmail)
            AuditDigestSupport.resetForTests()
            assertSame(original, AuditDigestSupport.sendEmail)
        } finally {
            AuditDigestSupport.resetForTests()
        }
    }

    @Test
    void runUpdateSeamsAreRestored() {
        Closure email = AuditRunUpdateSupport.sendEmail
        Closure dispatch = AuditRunUpdateSupport.dispatch
        try {
            AuditRunUpdateSupport.sendEmail = { def ec, String to, Map body, List<Map> a -> null }
            AuditRunUpdateSupport.dispatch = { def ec, Map p -> null }
            AuditRunUpdateSupport.resetForTests()
            assertSame(email, AuditRunUpdateSupport.sendEmail)
            assertSame(dispatch, AuditRunUpdateSupport.dispatch)
            AuditRunUpdateSupport.resetForTests()
            assertSame(email, AuditRunUpdateSupport.sendEmail)
        } finally {
            AuditRunUpdateSupport.resetForTests()
        }
    }
}
