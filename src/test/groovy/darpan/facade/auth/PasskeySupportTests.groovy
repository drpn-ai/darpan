package darpan.facade.auth

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

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
    @DisplayName("names are trimmed to 60 characters without control characters; blank becomes Passkey")
    void names() {
        assertEquals("MacBook Touch ID", PasskeySupport.cleanName("  MacBook\u0007 Touch ID "))
        assertEquals(PasskeySupport.DEFAULT_NAME, PasskeySupport.cleanName("   "))
        assertEquals(60, PasskeySupport.cleanName("x" * 80).length())
    }
}
