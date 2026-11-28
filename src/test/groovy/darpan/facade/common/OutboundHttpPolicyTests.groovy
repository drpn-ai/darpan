package darpan.facade.common

import org.junit.jupiter.api.Test

import java.net.InetAddress

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * HIGH gap 2 (reworked 2026-06-30): SFTP host SSRF policy must be NARROWER than the outbound LLM/partner
 * URL policy. Internal/VPC SFTP servers on RFC1918 and IPv6-ULA are LEGITIMATE and must be ALLOWED;
 * only loopback / link-local-metadata / wildcard targets are blocked. The LLM validate() policy still
 * blocks all private ranges (including IPv6-ULA, which was previously silently treated as public).
 */
class OutboundHttpPolicyTests {

    // ---- validateSftpHost: dangerous targets are REJECTED -----------------------------------------

    @Test
    void sftpRejectsCloudMetadataIp() {
        def r = OutboundHttpPolicy.validateSftpHost("169.254.169.254")
        assertFalse(r.ok, "metadata IP must be rejected for SFTP")
    }

    @Test
    void sftpRejectsLoopbackIpv4() {
        assertFalse(OutboundHttpPolicy.validateSftpHost("127.0.0.1").ok)
    }

    @Test
    void sftpRejectsLoopbackIpv6() {
        assertFalse(OutboundHttpPolicy.validateSftpHost("::1").ok)
        assertFalse(OutboundHttpPolicy.validateSftpHost("[::1]").ok)
    }

    @Test
    void sftpRejectsLinkLocalIpv6() {
        assertFalse(OutboundHttpPolicy.validateSftpHost("fe80::1").ok)
    }

    @Test
    void sftpRejectsWildcardUnspecified() {
        assertFalse(OutboundHttpPolicy.validateSftpHost("0.0.0.0").ok)
        assertFalse(OutboundHttpPolicy.validateSftpHost("::").ok)
    }

    @Test
    void sftpRejectsLocalhostName() {
        assertFalse(OutboundHttpPolicy.validateSftpHost("localhost").ok)
    }

    @Test
    void sftpRejectsControlCharsAndWhitespace() {
        assertFalse(OutboundHttpPolicy.validateSftpHost("sftp.example.com\nHost: evil").ok)
        assertFalse(OutboundHttpPolicy.validateSftpHost("sftp .example.com").ok)
    }

    // ---- validateSftpHost: legitimate internal + public targets are ACCEPTED ----------------------

    @Test
    void sftpAllowsRfc1918PrivateTen() {
        def r = OutboundHttpPolicy.validateSftpHost("10.0.0.5")
        assertTrue(r.ok, "10.0.0.5 is a legitimate internal SFTP target: ${r.error}")
    }

    @Test
    void sftpAllowsRfc1918PrivateOneNineTwo() {
        def r = OutboundHttpPolicy.validateSftpHost("192.168.1.10")
        assertTrue(r.ok, "192.168.1.10 is a legitimate internal SFTP target: ${r.error}")
    }

    @Test
    void sftpAllowsIpv6Ula() {
        def r = OutboundHttpPolicy.validateSftpHost("fd12:3456:789a::1")
        assertTrue(r.ok, "IPv6-ULA fd12:3456:789a::1 is a legitimate internal SFTP target: ${r.error}")
    }

    @Test
    void sftpAllowsPublicHostname() {
        // A normal public SFTP hostname (DNS may or may not resolve in CI; either way it must not be
        // rejected by the literal/blocklist checks). Port is supplied separately in the save service.
        def r = OutboundHttpPolicy.validateSftpHost("sftp.example.com")
        assertTrue(r.ok, "public hostname must be allowed: ${r.error}")
    }

    // ---- IPv6-ULA detection + LLM validate() policy -----------------------------------------------

    @Test
    void ipv6UlaDetectionTrueForFcAndFd() {
        assertTrue(OutboundHttpPolicy.isIpv6UniqueLocalAddress(InetAddress.getByName("fd12:3456:789a::1")))
        assertTrue(OutboundHttpPolicy.isIpv6UniqueLocalAddress(InetAddress.getByName("fc00::1")))
    }

    @Test
    void ipv6UlaDetectionFalseForPublicAndIpv4() {
        assertFalse(OutboundHttpPolicy.isIpv6UniqueLocalAddress(InetAddress.getByName("2606:4700:4700::1111")))
        assertFalse(OutboundHttpPolicy.isIpv6UniqueLocalAddress(InetAddress.getByName("8.8.8.8")))
    }

    @Test
    void llmPolicyStillBlocksIpv6Ula() {
        // The outbound LLM/partner URL policy must NOT silently treat IPv6-ULA as public.
        String reason = OutboundHttpPolicy.describeUnsafeAddress(InetAddress.getByName("fd12:3456:789a::1"))
        assertNotNull(reason, "IPv6-ULA must be flagged as non-routable for the LLM URL policy")
        assertTrue(reason.contains("unique-local"), "Unexpected reason: ${reason}")
    }

    @Test
    void llmPolicyStillBlocksRfc1918Private() {
        // Unchanged behavior: outbound LLM URLs to private hosts are still rejected.
        def r = OutboundHttpPolicy.validate("https://10.0.0.5/v1")
        assertFalse(r.ok, "LLM URL to RFC1918 must remain blocked")
    }

    // ---- DAR-OPS-029 S26b: a partner allow-list matches on a dot boundary ----------------------------

    @Test
    void allowListEntryMatchesOnlyItsOwnHostOrSubdomains() {
        // A bare entry: the host itself or a subdomain, never a different registrable name that merely ends the same way.
        assertTrue(OutboundHttpPolicy.validate("https://api.anthropic.com/v1", ["api.anthropic.com"]).ok)
        assertTrue(OutboundHttpPolicy.validate("https://eu.api.anthropic.com/v1", ["api.anthropic.com"]).ok)
        assertFalse(OutboundHttpPolicy.validate("https://evilapi.anthropic.com/v1", ["api.anthropic.com"]).ok,
                "evilapi.anthropic.com matched the bare entry api.anthropic.com")
        assertFalse(OutboundHttpPolicy.validate("https://evilmyshopify.com/admin", ["myshopify.com"]).ok,
                "evilmyshopify.com (anyone can register it) matched myshopify.com")
        // A leading-dot entry: subdomains only, as every partner list writes it today.
        assertTrue(OutboundHttpPolicy.validate("https://krewe.myshopify.com/admin", [".myshopify.com"]).ok)
        assertFalse(OutboundHttpPolicy.validate("https://evilmyshopify.com/admin", [".myshopify.com"]).ok)
        assertTrue(OutboundHttpPolicy.validate("https://1234.suitetalk.api.netsuite.com/x", [".suitetalk.api.netsuite.com", ".app.netsuite.com"]).ok)
    }

    // ---- DAR-OPS-029 S26b: addresses no partner lives at -----------------------------------------------

    static final List<String> NOT_A_PARTNER = [
            "0.1.2.3",            // 0.0.0.0/8: "this network"; 0.x reaches the host itself on Linux
            "255.255.255.255",    // limited broadcast
            "240.0.0.1",          // 240.0.0.0/4 reserved
            "198.18.0.1",         // 198.18.0.0/15 benchmarking, internal on many networks
            "192.0.0.192",        // 192.0.0.0/24 IETF protocol assignments
            "[::169.254.169.254]",  // IPv4-compatible IPv6 carrying the metadata address
            "[64:ff9b::a9fe:a9fe]", // NAT64 (64:ff9b::/96) carrying 169.254.169.254
            "[64:ff9b::7f00:1]",    // NAT64 carrying 127.0.0.1
    ]

    @Test
    void validateRefusesAddressesNoPartnerLivesAt() {
        NOT_A_PARTNER.each { String host ->
            assertFalse(OutboundHttpPolicy.validate("https://${host}/x".toString()).ok, "validate accepted https://${host}/x")
        }
        assertTrue(OutboundHttpPolicy.validate("https://[2606:4700:4700::1111]/x").ok, "a public IPv6 literal must still pass")
        assertTrue(OutboundHttpPolicy.validate("https://1.1.1.1/x").ok, "a public IPv4 literal must still pass")
    }

    @Test
    void connectTimeResolutionRefusesTheSameAddresses() {
        NOT_A_PARTNER.each { String host ->
            String bare = host.replace("[", "").replace("]", "")
            def thrown = null
            try {
                OutboundHttpPolicy.resolveVetted("partner.example", { String h -> [InetAddress.getByName(bare)] as InetAddress[] } as java.util.function.Function)
            } catch (UnknownHostException e) {
                thrown = e
            }
            assertNotNull(thrown, "resolveVetted connected to ${bare}")
        }
    }

    // ---- SFTP-narrow danger classifier ------------------------------------------------------------

    @Test
    void describeUnsafeSftpAddressAllowsPrivateButBlocksLoopback() {
        assertNull(OutboundHttpPolicy.describeUnsafeSftpAddress(InetAddress.getByName("10.0.0.5")))
        assertNull(OutboundHttpPolicy.describeUnsafeSftpAddress(InetAddress.getByName("192.168.1.10")))
        assertNull(OutboundHttpPolicy.describeUnsafeSftpAddress(InetAddress.getByName("fd12::1")))
        assertNotNull(OutboundHttpPolicy.describeUnsafeSftpAddress(InetAddress.getByName("127.0.0.1")))
        assertNotNull(OutboundHttpPolicy.describeUnsafeSftpAddress(InetAddress.getByName("169.254.169.254")))
    }
}
