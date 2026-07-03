package darpan.facade.common

import groovy.transform.CompileStatic

import java.net.InetAddress

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
 *      loopback, link-local, multicast, broadcast, or cloud-metadata range.
 *   4. Optional host allow-list (one or more suffixes) is enforced when provided.
 *
 * The DNS resolution is done once at validation time; we intentionally do NOT
 * provide a per-request DNS-pin (DNS rebinding is the remaining residual risk
 * and is documented as a known limitation — partner integrations resolve at
 * connect time too).
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
            allowListMatched = hostSuffixAllowList.any { String suffix ->
                String suf = suffix?.toLowerCase(Locale.ROOT)?.trim()
                return suf && hostLower.endsWith(suf)
            }
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
            // No allow-list (or no match for one): best-effort DNS resolution. If it succeeds and
            // resolves to a non-routable address (private/loopback/link-local/multicast/metadata),
            // BLOCK. If resolution fails (corporate firewall, just-provisioned subdomain, network
            // partition), ALLOW — the IP-literal check above is the load-bearing SSRF defense; we
            // refuse to make first-time customer setup impossible because we can't dig their DNS.
            InetAddress[] resolved = null
            try {
                resolved = InetAddress.getAllByName(host)
            } catch (Exception ignored) {
                // resolved stays null -> skip
            }
            if (resolved != null) {
                for (InetAddress addr : resolved) {
                    String reason = describeUnsafeAddress(addr)
                    if (reason != null) {
                        return ValidationResult.failure("URL host resolves to a non-routable address (${addr.hostAddress}): ${reason}")
                    }
                }
            }
        }

        return ValidationResult.success(trimmed)
    }

    /**
     * Validate a bare host (no scheme/port) for a non-HTTP outbound connection such as SFTP.
     *
     * Reuses the same SSRF mechanism as {@link #validate}: blocked-hostname list, IP-literal
     * non-routable-range check via {@link #describeUnsafeAddress}, and best-effort DNS resolution.
     * Unlike {@link #validate} it does NOT require an https scheme (SFTP hosts are not URLs) and it
     * rejects control characters / embedded whitespace (which would otherwise reach JSch verbatim).
     *
     * A failed DNS lookup is allowed (corporate firewall / just-provisioned host) — the IP-literal and
     * blocklist checks are the load-bearing defense, matching {@link #validate}'s posture.
     *
     * @param rawHost the host the tenant configured (e.g. "sftp.example.com" or "10.0.0.5")
     * @return ValidationResult with the trimmed host on success.
     */
    static ValidationResult validateHost(String rawHost) {
        if (rawHost == null || rawHost.trim().isEmpty()) {
            return ValidationResult.failure("Host is required.")
        }
        String trimmed = rawHost.trim()
        // Reject control chars or internal whitespace — a clean hostname/IP never contains them and
        // they are a classic vector for header/command smuggling into the SSH client.
        if (trimmed =~ /[\x00-\x1f\x7f\s]/) {
            return ValidationResult.failure("Host contains illegal whitespace or control characters.")
        }
        String hostLower = trimmed.toLowerCase(Locale.ROOT)
        if (BLOCKED_HOSTNAMES.contains(hostLower)) {
            return ValidationResult.failure("Host is on the blocklist (cloud-metadata / loopback).")
        }

        InetAddress literalAddr = parseIpLiteral(trimmed)
        if (literalAddr != null) {
            String reason = describeUnsafeAddress(literalAddr)
            if (reason != null) {
                return ValidationResult.failure("Host literal is non-routable (${literalAddr.hostAddress}): ${reason}")
            }
        } else {
            InetAddress[] resolved = null
            try {
                resolved = InetAddress.getAllByName(trimmed)
            } catch (Exception ignored) {
                // resolved stays null -> allow (DNS may be unreachable from the server)
            }
            if (resolved != null) {
                for (InetAddress addr : resolved) {
                    String reason = describeUnsafeAddress(addr)
                    if (reason != null) {
                        return ValidationResult.failure("Host resolves to a non-routable address (${addr.hostAddress}): ${reason}")
                    }
                }
            }
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
            // Best-effort DNS: if a hostname resolves to a loopback/link-local/metadata/wildcard target,
            // BLOCK (it is a disguised SSRF target); if resolution fails (corporate firewall / private
            // DNS), ALLOW — the literal check is the load-bearing defense, matching validate()'s posture.
            InetAddress[] resolved = null
            try {
                resolved = InetAddress.getAllByName(trimmed)
            } catch (Exception ignored) {
                // resolved stays null -> allow (DNS may be unreachable from the server)
            }
            if (resolved != null) {
                for (InetAddress addr : resolved) {
                    String reason = describeUnsafeSftpAddress(addr)
                    if (reason != null) {
                        return ValidationResult.failure("Host resolves to a non-routable address (${addr.hostAddress}): ${reason}")
                    }
                }
            }
        }
        return ValidationResult.success(trimmed)
    }

    /** Convenience: validate-or-throw for the SFTP host path (use where caller turns into a Moqui error). */
    static String requireValidSftpHost(String rawHost) {
        ValidationResult result = validateSftpHost(rawHost)
        if (!result.ok) throw new IllegalArgumentException(result.error)
        return result.normalizedUrl
    }

    /**
     * SFTP-narrow danger check: returns null if the address is an acceptable SFTP target, else a short
     * reason. Unlike {@link #describeUnsafeAddress} it does NOT block RFC1918 private, IPv6-ULA, CGNAT,
     * or multicast — internal/VPC SFTP servers live there. It blocks ONLY loopback, link-local (incl.
     * 169.254.169.254 metadata) and wildcard/unspecified.
     */
    static String describeUnsafeSftpAddress(InetAddress addr) {
        if (addr == null) return "null address"
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

    /** Convenience: validate-or-throw IllegalArgumentException (use where caller turns into Moqui error). */
    static String requireValid(String rawUrl, List<String> hostSuffixAllowList = null) {
        ValidationResult result = validate(rawUrl, hostSuffixAllowList)
        if (!result.ok) throw new IllegalArgumentException(result.error)
        return result.normalizedUrl
    }

    /** Returns null if the address is safe, else a short human-readable reason. */
    static String describeUnsafeAddress(InetAddress addr) {
        if (addr == null) return "null address"
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
        if (literal.startsWith("100.")) {
            // 100.64.0.0/10 (carrier-grade NAT, RFC 6598).
            String[] octets = literal.split("\\.")
            if (octets.length == 4) {
                int second = Integer.parseInt(octets[1])
                if (second >= 64 && second <= 127) return "CGNAT (100.64.0.0/10)"
            }
        }
        return null
    }
}
