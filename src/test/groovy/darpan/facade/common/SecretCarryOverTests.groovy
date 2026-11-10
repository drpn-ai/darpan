package darpan.facade.common

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-080: a stored secret is reused only while the place it is sent to is unchanged. */
class SecretCarryOverTests {
    private static final Map STORED = [baseUrl: "https://oms.example.com/", host: "db.example.com", port: 5432, authType: "BEARER", headersJson: null]

    @Test
    void sameAddressWrittenDifferentlyIsNoChange() {
        assertEquals([], SecretCarryOver.changedFields(STORED, [baseUrl: "HTTPS://OMS.example.com", host: " db.example.com ", port: "5432",
                authType: "bearer", headersJson: ""], ["baseUrl", "host", "port", "authType", "headersJson"]))
    }

    @Test
    void anyMovedFieldIsNamed() {
        assertEquals(["baseUrl"], SecretCarryOver.changedFields(STORED, [baseUrl: "https://attacker.example"], ["baseUrl"]))
        assertEquals(["host", "port"], SecretCarryOver.changedFields(STORED, [host: "evil.example", port: 5433], ["host", "port"]))
        assertEquals(["headersJson"], SecretCarryOver.changedFields(STORED, [headersJson: '{"X":"1"}'], ["headersJson"]))
    }

    @Test
    void theRefusalNamesWhatMovedAndWhatToEnter() {
        String message = SecretCarryOver.refusal(["host", "port"], "password")
        assertTrue(message.contains("host and port changed"), message)
        assertTrue(message.startsWith("Enter the password again"), message)
    }
}
