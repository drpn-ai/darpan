package darpan.common

import darpan.ops.ErrorDigestSupportTests
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertSame
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-OPS-029 S99-050: the shared message scope. No Moqui; the stub is Moqui's MessageFacade as far as the scope uses it. */
class MessageScopeTests {

    private static Map ecWithCallerState() {
        ErrorDigestSupportTests.StubMessages messages = new ErrorDigestSupportTests.StubMessages()
        messages.addPublic("Saved the run", "info")
        messages.addMessage("Two rows skipped", "warning")
        messages.addError("caller's own error")
        return [message: messages]
    }

    @Test
    void workErrorsAndMessagesAreDroppedAndTheCallersComeBack() {
        Map ec = ecWithCallerState()
        Object result = MessageScope.isolated(ec) {
            assertFalse(ec.message.hasError(), "work starts with a fresh error scope")
            ec.message.addError("Lock wait timeout exceeded on WELCOME_EMAIL")
            ec.message.addMessage("Error sending email: could not connect")
            return "outcome"
        }
        assertEquals("outcome", result)
        assertEquals([[message: "Saved the run", type: "info"], [message: "Two rows skipped", type: "warning"]], ec.message.infos)
        assertEquals([[message: "Saved the run", type: "info"]], ec.message.publics)
        assertEquals([[message: "caller's own error"]], ec.message.errors, "only the caller's error remains")
        assertFalse(ec.message.infos.any { it.type == "danger" }, "no work error turned into a danger message")
    }

    @Test
    void aThrowingWorkStillRestoresTheCaller() {
        Map ec = ecWithCallerState()
        IllegalStateException thrown = new IllegalStateException("boom")
        IllegalStateException seen = assertThrows(IllegalStateException) {
            MessageScope.isolated(ec) { ec.message.addError("work's error"); throw thrown }
        }
        assertSame(thrown, seen)
        assertEquals([[message: "caller's own error"]], ec.message.errors)
        assertEquals(2, ec.message.infos.size())
    }

    @Test
    void restoreKeepsPublicMessagesPublic() {
        Map ec = ecWithCallerState()
        Map snap = MessageScope.snapshot(ec)
        ec.message.addMessage("added later")
        MessageScope.restore(ec, snap)
        assertEquals(["Saved the run", "Two rows skipped"], ec.message.infos*.message)
        assertTrue(ec.message.publics*.message == ["Saved the run"])
    }
}
