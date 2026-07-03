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
