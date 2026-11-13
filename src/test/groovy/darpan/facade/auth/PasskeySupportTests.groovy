package darpan.facade.auth

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*
import static org.junit.jupiter.api.Assumptions.assumeTrue

/** DAR-BE-270 — the relying party's allowed origins per instance (pure). */
class PasskeySupportTests {

    @Test
    @DisplayName("origins: https on the rpId or under it, http only for localhost; the API host, other sites and * are dropped")
    void originsFor() {
        assertEquals(["https://app.drpn.ai"] as Set,
                PasskeySupport.originsFor("app.drpn.ai", ["https://app.drpn.ai", "https://api.drpn.ai", "*", "http://app.drpn.ai",
                                                          "https://app.drpn.ai.evil.example", "https://evilapp.drpn.ai", ""]))
        assertEquals(["https://app.drpn.ai", "https://admin.drpn.ai"] as Set,
                PasskeySupport.originsFor("drpn.ai", ["https://app.drpn.ai/", "https://admin.drpn.ai", "https://drpn.ai.example.com"]))
        assertEquals(["http://localhost:5173", "http://localhost:5174"] as Set,
                PasskeySupport.originsFor("localhost", ["http://localhost:5173", "http://localhost:5174", "https://localhost.example"]))
        assertEquals([] as Set, PasskeySupport.originsFor("app.drpn.ai", ["https://app.drpn.ai/login", "not a url"]),
                "a path is not an origin")
    }

    @Test
    @DisplayName("with no DARPAN_APP_BASE_URL, rpId and origins come from the webapp_allow_origins System property (Moqui's default-property)")
    void fallsBackToTheAllowOriginsSystemProperty() {
        // An ec whose resource properties do not carry webapp_allow_origins, as on a real instance.
        def ec = [resource: [properties: [:]]]
        String before = System.getProperty("webapp_allow_origins")
        try {
            System.setProperty("webapp_allow_origins", "http://localhost:5173,http://localhost:5174")
            assumeTrue(!System.getenv("DARPAN_WEBAUTHN_RP_ID") && !System.getenv("DARPAN_APP_BASE_URL"),
                    "the environment names the relying party: not this case")
            assertEquals("localhost", PasskeySupport.rpId(ec))
            assertEquals(["http://localhost:5173", "http://localhost:5174"] as Set, PasskeySupport.origins(ec))
            assertTrue(PasskeySupport.available(ec))
        } finally {
            if (before == null) System.clearProperty("webapp_allow_origins") else System.setProperty("webapp_allow_origins", before)
        }
    }

    @Test
    @DisplayName("names are trimmed to 60 characters without control characters; blank becomes Passkey")
    void names() {
        assertEquals("MacBook Touch ID", PasskeySupport.cleanName("  MacBook\u0007 Touch ID "))
        assertEquals(PasskeySupport.DEFAULT_NAME, PasskeySupport.cleanName("   "))
        assertEquals(60, PasskeySupport.cleanName("x" * 80).length())
    }
}
