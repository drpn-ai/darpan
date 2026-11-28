package darpan.facade.common

import groovy.transform.CompileStatic

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.function.Function

/**
 * Server-Side-Request-Forgery (SSRF) defense for outbound HTTPs that target
 * URLs supplied by tenants (NetSuite tokenUrl/endpointUrl, Shopify shopApiUrl,
 * HotWax OMS baseUrl, future remote sources).
 *
 * Rules enforced by {@link #validate(String, java.util.List)}:
 *   1. URL must parse as an absolute URI.
 *   2. Scheme must be https. (Allows tests to override via {@code allowInsecure} but
 *      the production helper does NOT accept http.)
 *   3. Host must be present and not an IP literal that resolves to a private,
 *      loopback, link-local, multicast, broadcast, reserved or cloud-metadata range
 *      (an IPv6 address carrying an IPv4 one, IPv4-compatible or NAT64, is judged by the IPv4 one).
 *   4. Optional host allow-list is enforced when provided: ".example.com" matches subdomains only,
 *      "api.example.com" that host or its subdomains, always on a dot boundary.
 *
 * validate() resolves the host once, best effort, when a URL is saved or about to be called. A client that resolves
 * again at connect time can be pointed elsewhere between the two (DNS rebinding), so the connection itself must use
 * {@link #resolveVetted}: OutboundHttpClient (DAR-BE-262) makes it the client's own DNS step, so the address checked
 * is the address connected to, on every new connection.
 *
 * Centralizing this here means future remote-config save paths can call one
 * helper rather than copy-pasting host-check logic (which is the pattern that
 * produced the cross-integration SSRF surface flagged by the 2026-05-29 audit).
 *
 * <strong>NOT thread-mutated state.</strong> All methods are static and pure.
 */
@CompileStatic
class OutboundHttpPolicy {

    static final List<String> BLOCKED_HOSTNAMES = [
            "localhost",
            "metadata.google.internal",
            "metadata.googleapis.com",
    ]

    /** Cloud-instance metadata IPv4 (AWS/GCP/Azure/Oracle/Alibaba all expose 169.254.169.254). */
    static final String AWS_METADATA_IP = "169.254.169.254"

    static class ValidationResult {
        final boolean ok
        final String error
        final String normalizedUrl
        ValidationResult(boolean ok, String error, String normalizedUrl) {
            this.ok = ok
            this.error = error
            this.normalizedUrl = normalizedUrl
        }
        static ValidationResult success(String normalized) { return new ValidationResult(true, null, normalized) }
        static ValidationResult failure(String reason) { return new ValidationResult(false, reason, null) }
    }

    /**
     * Validate a tenant-supplied outbound URL against the SSRF policy.
     *
     * @param rawUrl the URL the tenant configured (may include trailing slash, query, etc.)
     * @param hostSuffixAllowList optional list like ["myshopify.com", ".suitetalk.api.netsuite.com"];
     *        when non-empty the resolved host must end with at least one suffix (case-insensitive).
     * @return ValidationResult with the trimmed, normalized URL on success.
     */
    static ValidationResult validate(String rawUrl, List<String> hostSuffixAllowList = null) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            return ValidationResult.failure("URL is required.")
        }
        String trimmed = rawUrl.trim()

        URI uri
        try {
            uri = URI.create(trimmed)
        } catch (IllegalArgumentException e) {
            return ValidationResult.failure("URL is not a valid URI: ${e.message}")
        }

        if (uri.scheme == null || uri.scheme.toLowerCase(Locale.ROOT) != "https") {
            return ValidationResult.failure("URL must use https.")
        }

        String host = uri.host
        if (host == null || host.isEmpty()) {
            return ValidationResult.failure("URL has no host component.")
        }
        String hostLower = host.toLowerCase(Locale.ROOT)

        if (BLOCKED_HOSTNAMES.contains(hostLower)) {
            return ValidationResult.failure("URL host is on the blocklist (cloud-metadata / loopback).")
        }

        boolean hasAllowList = hostSuffixAllowList != null && !hostSuffixAllowList.isEmpty()
        boolean allowListMatched = false
        if (hasAllowList) {
            allowListMatched = hostSuffixAllowList.any { String entry -> matchesAllowListEntry(hostLower, entry) }
            if (!allowListMatched) {
                return ValidationResult.failure("URL host is not in the partner allow-list.")
            }
        }

        // When an allow-list suffix matched, we trust that the named partner controls its host zone
        // (e.g. *.suitetalk.api.netsuite.com is NetSuite's), so we skip DNS resolution — both because
        // a customer's just-provisioned subdomain may not yet propagate, and because the partner's
        // own DNS will not point to private/metadata ranges. If the host parses as an IP literal we
        // STILL block private ranges (a tenant can't set tokenUrl="https://169.254.169.254" even
        // when an allow-list is present).
        InetAddress literalAddr = parseIpLiteral(host)
        if (literalAddr != null) {
            String reason = describeUnsafeAddress(literalAddr)
            if (reason != null) {
                return ValidationResult.failure("URL host literal is non-routable (${literalAddr.hostAddress}): ${reason}")
            }
        } else if (!allowListMatched) {
            // No allow-list (or no match for one): best-effort DNS resolution. If it resolves to a non-routable address,
            // BLOCK. If resolution fails (corporate firewall, just-provisioned subdomain, network partition), ALLOW: the
            // IP-literal check above is the load-bearing check here, and the connection itself goes through resolveVetted.
            String unsafe = unsafeResolution(host, { InetAddress addr -> describeUnsafeAddress(addr) } as Function<InetAddress, String>)
            if (unsafe != null) return ValidationResult.failure("URL host resolves to ${unsafe}")
        }

        return ValidationResult.success(trimmed)
    }

    /**
     * Validate a bare host for an SFTP connection. SFTP needs a NARROWER policy than the outbound LLM
     * HTTP path: internal/VPC SFTP targets on RFC1918 (10.x / 192.168.x / 172.16-31.x) and IPv6-ULA
     * (fc00::/7) are LEGITIMATE and must be ALLOWED, whereas {@link #validate} correctly blocks them for
     * tenant-supplied outbound LLM/partner URLs.
     *
     * This check therefore BLOCKS ONLY the truly dangerous targets that no real SFTP server should be:
     *   - loopback (127.0.0.0/8, ::1)
     *   - link-local / cloud metadata (169.254.0.0/16 incl 169.254.169.254, fe80::/10)
     *   - unspecified / wildcard (0.0.0.0, ::)
     * ...and their IPv4-mapped/compatible/encoded forms (handled by {@link InetAddress} canonicalization).
     * It still rejects control chars / embedded whitespace (command/header smuggling into JSch) and the
     * blocked-hostname list (localhost / metadata.* names). RFC1918 private, IPv6-ULA, CGNAT, and public
     * hosts are ALLOWED.
     *
     * @param rawHost the host the tenant configured (e.g. "sftp.example.com", "10.0.0.5", "fd12::1")
     * @return ValidationResult with the trimmed host on success.
     */
    static ValidationResult validateSftpHost(String rawHost) {
        if (rawHost == null || rawHost.trim().isEmpty()) {
            return ValidationResult.failure("Host is required.")
        }
        String trimmed = rawHost.trim()
        // Reject control chars or internal whitespace — a clean hostname/IP never contains them and
        // they are a classic vector for command smuggling into the SSH client.
        if (trimmed =~ /[\x00-\x1f\x7f\s]/) {
            return ValidationResult.failure("Host contains illegal whitespace or control characters.")
        }
        String hostLower = trimmed.toLowerCase(Locale.ROOT)
        // Reuse the cloud-metadata / loopback hostname blocklist (localhost, metadata.* names).
        if (BLOCKED_HOSTNAMES.contains(hostLower)) {
            return ValidationResult.failure("Host is on the blocklist (cloud-metadata / loopback).")
        }

        InetAddress literalAddr = parseIpLiteral(trimmed)
        if (literalAddr != null) {
            String reason = describeUnsafeSftpAddress(literalAddr)
            if (reason != null) {
                return ValidationResult.failure("Host literal is non-routable (${literalAddr.hostAddress}): ${reason}")
            }
        } else {
            // Best-effort DNS: a hostname resolving to a loopback/link-local/metadata/wildcard target is BLOCKED (a disguised
            // SSRF target); a failed resolution (corporate firewall / private DNS) is ALLOWED, matching validate()'s posture.
            String unsafe = unsafeResolution(trimmed, { InetAddress addr -> describeUnsafeSftpAddress(addr) } as Function<InetAddress, String>)
            if (unsafe != null) return ValidationResult.failure("Host resolves to ${unsafe}")
        }
        return ValidationResult.success(trimmed)
    }

    /**
     * SFTP-narrow danger check: returns null if the address is an acceptable SFTP target, else a short
     * reason. Unlike {@link #describeUnsafeAddress} it does NOT block RFC1918 private, IPv6-ULA, CGNAT,
     * or multicast — internal/VPC SFTP servers live there. It blocks ONLY loopback, link-local (incl.
     * 169.254.169.254 metadata) and wildcard/unspecified.
     */
    static String describeUnsafeSftpAddress(InetAddress addr) {
        if (addr == null) return "null address"
        InetAddress carried = embeddedIpv4(addr)
        if (carried != null) return describeUnsafeSftpAddress(carried)
        if (addr.isLoopbackAddress()) return "loopback"
        if (addr.isAnyLocalAddress()) return "wildcard"
        if (addr.isLinkLocalAddress()) return "link-local"
        String literal = addr.hostAddress
        // isLinkLocalAddress() already covers fe80::/10 and 169.254.0.0/16, but pin the cloud-metadata
        // IPv4 explicitly so the reason is unambiguous even if encoded forms canonicalize oddly.
        if (literal == AWS_METADATA_IP) return "cloud metadata IPv4 169.254.169.254"
        return null
    }

    /** True if {@code addr} is an IPv6 Unique-Local Address (fc00::/7, i.e. fc.. or fd.. high byte). */
    static boolean isIpv6UniqueLocalAddress(InetAddress addr) {
        if (!(addr instanceof java.net.Inet6Address)) return false
        byte[] bytes = addr.getAddress()
        if (bytes == null || bytes.length != 16) return false
        // fc00::/7 — top 7 bits are 1111110, so the first byte ANDed with 0xFE equals 0xFC.
        return ((bytes[0] as int) & 0xFE) == 0xFC
    }

    /** Returns the address if {@code host} is an IPv4 or IPv6 literal, else null (so DNS resolution would be needed). */
    private static InetAddress parseIpLiteral(String host) {
        if (host == null || host.isEmpty()) return null
        // IPv6 in URI is bracketed; strip brackets before InetAddress parsing.
        String candidate = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host
        // A bare label like "localhost" or "foo.example" is not an IP literal — only attempt when it
        // looks like one (digits/dots or a colon for IPv6).
        if (!candidate.matches(/^[0-9a-fA-F:.]+$/)) return null
        try {
            return InetAddress.getByName(candidate)
        } catch (Exception ignored) {
            return null
        }
    }

    /**
     * DAR-BE-262. The DNS step of a guarded connection: the host's addresses, resolved once by {@code lookup}, returned only
     * when EVERY one passes {@link #describeUnsafeAddress}; otherwise UnknownHostException naming the address and why. The
     * caller connects to exactly these addresses, so a second lookup cannot swap in a private or metadata address. Unlike
     * validate(), a lookup that fails is a failure: there is nothing to connect to.
     */
    static List<InetAddress> resolveVetted(String host, Function<String, InetAddress[]> lookup) throws UnknownHostException {
        String candidate = host?.trim()
        if (!candidate) throw new UnknownHostException("No host to connect to.")
        if (BLOCKED_HOSTNAMES.contains(candidate.toLowerCase(Locale.ROOT))) {
            throw new UnknownHostException("${candidate} is on the outbound blocklist (cloud-metadata / loopback).".toString())
        }
        InetAddress literal = parseIpLiteral(candidate)
        InetAddress[] resolved = literal != null ? [literal] as InetAddress[] : lookup.apply(candidate)
        if (resolved == null || resolved.length == 0) throw new UnknownHostException("${candidate} did not resolve.".toString())
        for (InetAddress addr : resolved) {
            String reason = describeUnsafeAddress(addr)
            if (reason != null) {
                throw new UnknownHostException("${candidate} resolves to a non-routable address (${addr?.hostAddress}): ${reason}".toString())
            }
        }
        return Arrays.asList(resolved)
    }

    /** Returns null if the address is safe, else a short human-readable reason. */
    static String describeUnsafeAddress(InetAddress addr) {
        if (addr == null) return "null address"
        InetAddress carried = embeddedIpv4(addr)
        if (carried != null) return describeUnsafeAddress(carried)
        if (addr.isLoopbackAddress()) return "loopback"
        if (addr.isAnyLocalAddress()) return "wildcard"
        if (addr.isLinkLocalAddress()) return "link-local"
        if (addr.isSiteLocalAddress()) return "RFC1918 private"
        // isSiteLocalAddress() covers IPv4 RFC1918 and the deprecated IPv6 site-local fec0::/10, but NOT
        // IPv6 Unique-Local fc00::/7 — so fd12:3456:789a::1 was silently treated as public. Block it here
        // for the outbound LLM/partner URL policy (private addresses are correctly off-limits there).
        if (isIpv6UniqueLocalAddress(addr)) return "IPv6 unique-local (fc00::/7)"
        if (addr.isMulticastAddress()) return "multicast"
        String literal = addr.hostAddress
        if (literal == AWS_METADATA_IP) return "cloud metadata IPv4 169.254.169.254"
        if (addr instanceof Inet4Address) {
            byte[] b = addr.getAddress()
            int first = b[0] & 0xFF
            int second = b[1] & 0xFF
            if (first == 0) return "this network (0.0.0.0/8)"
            if (first >= 240) return "reserved or broadcast (240.0.0.0/4)"
            if (first == 100 && second >= 64 && second <= 127) return "CGNAT (100.64.0.0/10)"
            if (first == 198 && (second == 18 || second == 19)) return "benchmarking (198.18.0.0/15)"
            if (first == 192 && second == 0 && (b[2] & 0xFF) == 0) return "IETF protocol assignments (192.0.0.0/24)"
        }
        return null
    }

    /**
     * The IPv4 address an IPv6 one carries: IPv4-compatible (::a.b.c.d) or NAT64 (64:ff9b::a.b.c.d). Java maps ::ffff:a.b.c.d
     * to an Inet4Address itself, but not these, so [::169.254.169.254] or [64:ff9b::a9fe:a9fe] would otherwise pass as public.
     * Null for any other address (and for :: and ::1, which the wildcard and loopback checks own).
     */
    private static InetAddress embeddedIpv4(InetAddress addr) {
        if (!(addr instanceof Inet6Address)) return null
        byte[] b = addr.getAddress()
        boolean compatible = (0..11).every { int i -> b[i] == 0 } && !addr.isAnyLocalAddress() && !addr.isLoopbackAddress()
        boolean nat64 = (b[0] & 0xFF) == 0x00 && (b[1] & 0xFF) == 0x64 && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B &&
                (4..11).every { int i -> b[i] == 0 }
        if (!compatible && !nat64) return null
        return InetAddress.getByAddress([b[12], b[13], b[14], b[15]] as byte[])
    }

    /** A partner allow-list entry: ".example.com" matches its subdomains, "api.example.com" that host or its subdomains. */
    private static boolean matchesAllowListEntry(String hostLower, String entry) {
        String e = entry?.toLowerCase(Locale.ROOT)?.trim()
        if (!e) return false
        if (e.startsWith(".")) return hostLower.endsWith(e) && hostLower.length() > e.length()
        return hostLower == e || hostLower.endsWith("." + e)
    }

    /**
     * Best effort: the first resolved address {@code describe} refuses, as "a non-routable address (<ip>): <reason>"; null
     * when every address passes or the name does not resolve (the caller decides what a failed lookup means).
     */
    private static String unsafeResolution(String host, Function<InetAddress, String> describe) {
        InetAddress[] resolved
        try {
            resolved = InetAddress.getAllByName(host)
        } catch (Exception ignored) {
            return null
        }
        for (InetAddress addr : resolved) {
            String reason = describe.apply(addr)
            if (reason != null) return "a non-routable address (${addr.hostAddress}): ${reason}".toString()
        }
        return null
    }
}
