package darpan.facade.auth

import darpan.facade.common.TenantScopedFinder
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp

/**
 * Persistent-login cookie writer with per-request SameSite escalation.
 *
 * Audit W5 #39 (re-ported from `component-worktrees/darpan-prod-patches/src/main/groovy/darpan/facade/auth/AuthSessionSupport.groovy`).
 *
 * <h3>Canonical auth-channel policy (MACH P1, decided 2026-07-02)</h3>
 * Two channels exist; the ambiguity between them is resolved as follows:
 * <ol>
 *  <li><b>Browser SPA (canonical): HttpOnly cookie.</b> When {@code VITE_DARPAN_COOKIE_AUTH=true}
 *      (DAR-290) the SPA authenticates via the {@code darpan_login_key} HttpOnly cookie plus the
 *      CSRF token from {@code /apps/darpan/csrfToken}. No token is persisted in JS-readable
 *      storage. This is the end-state for all browser deployments.</li>
 *  <li><b>Integration callers + transitional fallback: {@code login_key} header.</b> Non-browser
 *      clients and environments that cannot serve credentialed CORS (e.g. an ingress that
 *      rewrites Access-Control-Allow-Origin — the 2026-07-02 UAT posture) use the header token.
 *      Header tokens must live in memory only; never query params (W1 #3: access logs, history,
 *      Referer).</li>
 *  <li><b>CORS end-state:</b> exact-origin allowlist WITH {@code Access-Control-Allow-Credentials}
 *      for the SPA origins. A {@code *} allowlist is a temporary incident posture only and must be
 *      reverted together with the ingress ACAO fix (order matters: backend first or same change).</li>
 * </ol>
 *
 * Why this class exists, separately from {@link AuthFacadeSupport}:
 *  - {@code AuthFacadeSupport} is the header-only auth token surface (`login_key` request header) —
 *    the integration/fallback channel above.
 *  - This class implements the cookie channel: an httpOnly persistent-login cookie that also
 *    survives the SPA losing localStorage (private window, cleared site data, third-party-cookie
 *    blockers).
 *  - The {@code resolveCookieSameSite} helper is the real audit fix: the framework's web.xml had
 *    a static {@code __SAME_SITE_NONE__} sed-replace in the Docker build (see docker/Dockerfile),
 *    which forced ALL cookies (session id, framework auth, etc.) to SameSite=None for everyone.
 *    Per-request escalation here means we issue Lax by default and only escalate to None when the
 *    request is genuinely cross-site AND secure — i.e. the SPA running on
 *    {@code https://darpan.hotwax.io} calling {@code https://api.darpan.hotwax.io}. Same-origin
 *    requests get Lax, which is the more restrictive (safer) default.
 */
class AuthSessionSupport {
    private static final Logger logger = LoggerFactory.getLogger(AuthSessionSupport.class)

    static final String PERSISTENT_LOGIN_COOKIE_NAME = "darpan_login_key"
    static final String COOKIE_PATH = "/"
    static final String COOKIE_SAME_SITE_LAX = "Lax"
    static final String COOKIE_SAME_SITE_NONE = "None"
    static final String EXPIRED_COOKIE_DATE = "Thu, 01 Jan 1970 00:00:00 GMT"

    /**
     * Writes a persistent-login cookie carrying the just-issued login key. Caller passes the
     * raw (un-hashed) login key value returned by {@code ec.user.getLoginKey()}; it lands in the
     * cookie and is compared back to the {@code UserLoginKey.loginKey} column (which stores the
     * SHA-256 hash) during restore. Idempotent — extra calls just refresh the cookie's Max-Age.
     */
    static void writePersistentLoginCookie(def ec, String loginKey) {
        if (!loginKey?.trim()) return
        addCookieHeader(ec, loginKey.trim(), resolveCookieMaxAgeSeconds(ec), null)
    }

    /** Clears the persistent-login cookie by emitting Max-Age=0 + an Expires-in-the-past header. */
    static void clearPersistentLoginCookie(def ec) {
        addCookieHeader(ec, "", 0, EXPIRED_COOKIE_DATE)
    }

    /** Returns the persistent-login cookie value from the request, or null if absent/blank. */
    static String readPersistentLoginCookie(def ec) {
        def cookies = ec?.web?.request?.getCookies()
        if (cookies == null) return null

        for (def cookie : cookies) {
            if (cookie?.name == PERSISTENT_LOGIN_COOKIE_NAME) {
                String value = cookie.value?.toString()?.trim()
                if (value) return value
            }
        }
        return null
    }

    /**
     * If the current request is unauthenticated but carries a valid persistent-login cookie,
     * looks up the matching {@code UserLoginKey} row, validates it has not expired, and rebuilds
     * the authenticated session via {@code ec.user.internalLoginUser(username)}. Returns true
     * when the session was restored. Stale/invalid cookies are proactively cleared.
     */
    static boolean restoreAuthenticatedSession(def ec) {
        String userId = ec?.user?.userId?.toString()?.trim()
        if (userId) return true

        String loginKey = readPersistentLoginCookie(ec)
        if (!loginKey) return false

        def userLoginKey = findActiveUserLoginKey(ec, loginKey)
        if (userLoginKey == null) {
            clearPersistentLoginCookie(ec)
            return false
        }

        def userAccount = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.security.UserAccount",
                        "self-scoped auth: load account for cookie-based session restore")
                .condition("userId", userLoginKey.userId)
                .one()
        String username = userAccount?.username?.toString()?.trim()
        if (!username) {
            clearPersistentLoginCookie(ec)
            return false
        }

        boolean restored = false
        try {
            restored = ec?.user?.internalLoginUser(username) as boolean
        } catch (Exception e) {
            logger.warn("restoreAuthenticatedSession failed for cookie token (username=${username}): ${e.message}")
        }
        if (!restored) clearPersistentLoginCookie(ec)
        return restored
    }

    /**
     * Deletes the {@code UserLoginKey} row matching the cookie value, if any. Used on logout so
     * the cookie cannot be replayed even before its browser-side expiry. Returns true when a row
     * was removed.
     */
    static boolean revokePersistentLogin(def ec) {
        String loginKey = readPersistentLoginCookie(ec)
        if (!loginKey) return false

        String hashedKey = ec?.factory?.getSimpleHash(loginKey, "", ec?.factory?.getLoginKeyHashType(), false)
        if (!hashedKey) return false

        def deleted = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.security.UserLoginKey",
                        "self-scoped auth: revoke persistent-login cookie token by hash")
                .condition("loginKey", hashedKey)
                .deleteAll()
        return ((deleted ?: 0) as int) > 0
    }

    /**
     * Maps the configured login-key expiry (hours) into a cookie Max-Age (seconds). Defaults to
     * 144 hours / 6 days if the factory accessor throws or returns something unparseable.
     */
    static Integer resolveCookieMaxAgeSeconds(def ec) {
        float expireHours = 144.0f
        try {
            def configured = ec?.factory?.getLoginKeyExpireHours()
            if (configured instanceof Number) {
                expireHours = ((Number) configured).floatValue()
            } else if (configured != null) {
                expireHours = Float.parseFloat(configured.toString())
            }
        } catch (Exception ignored) {
        }

        return Math.max(1, Math.round(expireHours * 60.0f * 60.0f))
    }

    /**
     * Detects a transport-secure request, honoring reverse-proxy hints. Production traffic terminates
     * TLS at the LB/ingress and forwards plaintext over the cluster network, so {@code request.isSecure()}
     * alone returns false even when the original client-facing request was https.
     */
    static boolean isSecureRequest(def ec) {
        def request = ec?.web?.request
        if (request == null) return false

        String forwardedProto = request.getHeader("X-Forwarded-Proto")?.toString()?.trim()
        String forwardedSsl = request.getHeader("X-Forwarded-Ssl")?.toString()?.trim()
        String frontEndHttps = request.getHeader("Front-End-Https")?.toString()?.trim()

        return request.isSecure() ||
                "https".equalsIgnoreCase(forwardedProto) ||
                "on".equalsIgnoreCase(forwardedSsl) ||
                "on".equalsIgnoreCase(frontEndHttps)
    }

    /**
     * Resolves {@code SameSite} per request: {@code None} for genuine cross-site + secure traffic,
     * {@code Lax} otherwise. Audit fix: the Docker {@code __SAME_SITE_NONE__} sed-replace forced
     * {@code None} unconditionally, which downgraded CSRF protection for same-origin requests.
     */
    static String resolveCookieSameSite(def ec) {
        return isCrossSiteRequest(ec) && isSecureRequest(ec) ? COOKIE_SAME_SITE_NONE : COOKIE_SAME_SITE_LAX
    }

    /**
     * Cross-site = the Origin header host differs from the request host. We deliberately rely on
     * Origin (not Referer) because Origin is the browser's authoritative cross-site signal and is
     * present on all CORS preflighted requests. Absent Origin → treated as same-site (the most
     * conservative default, which results in SameSite=Lax).
     */
    static boolean isCrossSiteRequest(def ec) {
        def request = ec?.web?.request
        if (request == null) return false

        String originHeader = request.getHeader("Origin")?.toString()?.trim()
        if (!originHeader) return false

        String originHost = extractHost(originHeader)
        String requestHost = normalizeHost(
                request.getHeader("X-Forwarded-Host")
                        ?: request.getHeader("Host")
                        ?: request.getServerName()
        )
        return originHost != null && requestHost != null && originHost != requestHost
    }

    protected static String extractHost(String originHeader) {
        String normalizedOrigin = originHeader?.toString()?.trim()
        if (!normalizedOrigin) return null

        try {
            return normalizeHost(new URI(normalizedOrigin).host)
        } catch (Exception ignored) {
            int schemeIdx = normalizedOrigin.indexOf("://")
            String withoutScheme = schemeIdx >= 0 ? normalizedOrigin.substring(schemeIdx + 3) : normalizedOrigin
            int slashIdx = withoutScheme.indexOf("/")
            return normalizeHost(slashIdx >= 0 ? withoutScheme.substring(0, slashIdx) : withoutScheme)
        }
    }

    protected static String normalizeHost(Object value) {
        String normalized = value?.toString()?.trim()?.toLowerCase()
        if (!normalized) return null

        // X-Forwarded-Host can carry a comma-separated chain; the leftmost entry is the original.
        int commaIdx = normalized.indexOf(",")
        if (commaIdx >= 0) normalized = normalized.substring(0, commaIdx).trim()

        // Strip the :port suffix for comparison.
        int colonIdx = normalized.indexOf(":")
        if (colonIdx >= 0) normalized = normalized.substring(0, colonIdx).trim()

        return normalized ?: null
    }

    protected static def findActiveUserLoginKey(def ec, String loginKey) {
        String normalizedKey = loginKey?.toString()?.trim()
        if (!normalizedKey) return null

        String hashedKey = ec?.factory?.getSimpleHash(normalizedKey, "", ec?.factory?.getLoginKeyHashType(), false)
        if (!hashedKey) return null

        def userLoginKey = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.security.UserLoginKey",
                        "persistent-login token lookup by hash — pre-auth path, no active tenant")
                .condition("loginKey", hashedKey)
                .one()
        if (userLoginKey == null) return null

        Timestamp thruDate = userLoginKey.getTimestamp("thruDate")
        Timestamp now = new Timestamp(System.currentTimeMillis())
        // Policy: a null thruDate means the key never expires (Moqui convention for
        // UserLoginKey rows that were issued without an explicit expiry). Only reject
        // rows whose thruDate is set AND already in the past. This is intentional —
        // do not "fix" this to treat null as expired, as that would invalidate all
        // existing persistent-login cookies issued before expiry was wired up.
        if (thruDate != null && now.after(thruDate)) return null

        return userLoginKey
    }

    /**
     * Emits a Set-Cookie header with httpOnly + (conditionally) Secure + per-request SameSite.
     * We build the header string manually instead of using {@code response.addCookie(Cookie)}
     * because the servlet API's Cookie type does not expose SameSite in our target servlet
     * version, and writing the raw header is the supported workaround.
     */
    protected static void addCookieHeader(def ec, String value, Integer maxAgeSeconds, String expiresAt) {
        def response = ec?.web?.response
        if (response == null) return

        String sameSite = resolveCookieSameSite(ec)
        StringBuilder cookieHeader = new StringBuilder()
        cookieHeader.append(PERSISTENT_LOGIN_COOKIE_NAME).append("=").append(value ?: "")
        cookieHeader.append("; Max-Age=").append(Math.max(maxAgeSeconds ?: 0, 0))
        if (expiresAt) cookieHeader.append("; Expires=").append(expiresAt)
        cookieHeader.append("; Path=").append(COOKIE_PATH)
        cookieHeader.append("; HttpOnly")
        cookieHeader.append("; SameSite=").append(sameSite)
        // SameSite=None REQUIRES Secure per RFC 6265bis §5.3.7 — browsers reject otherwise.
        if (COOKIE_SAME_SITE_NONE.equals(sameSite) || isSecureRequest(ec)) cookieHeader.append("; Secure")

        response.addHeader("Set-Cookie", cookieHeader.toString())
    }
}
