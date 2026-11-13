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
     * DAR-BE-261. A web sign-in ends after this many minutes with no request (sliding: each request moves the end on),
     * and in any case once the absolute lifetime has passed since sign-in: {@code user-facade.login-key.@expire-hours},
     * 24 in this component's MoquiConf.xml. The servlet session idles out at the same 60 minutes (webapp
     * session-config, pinned there too).
     */
    static final int IDLE_TIMEOUT_MINUTES = 60
    static final long IDLE_TIMEOUT_MILLIS = IDLE_TIMEOUT_MINUTES * 60_000L
    /** A key's end is rewritten only when it would move by at least this much: one write a minute per active key, not one per request. */
    static final long SLIDE_STEP_MILLIS = 60_000L

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

        // DAR-BE-261: a key past its idle end or sign-in + the absolute lifetime restores nothing. Checked (and the
        // restore counted as activity: the key slides) BEFORE signing in. No user is signed in here (the early return
        // above), so there is no one to sign out: the stale cookie is cleared and the key written as ended.
        if (!slideWebLoginKey(ec, userLoginKey, System.currentTimeMillis(), absoluteLifetimeMillis(ec))) {
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

    // ---- DAR-BE-261: idle timeout and absolute lifetime of a web sign-in ----

    /** Hours a new web login key lives before its first slide: the idle window, never past the absolute lifetime. */
    static float initialLoginKeyHours(def ec) {
        return Math.min(IDLE_TIMEOUT_MINUTES / 60.0f, absoluteLifetimeMillis(ec) / 3_600_000.0f)
    }

    /** The absolute lifetime of a web sign-in, in milliseconds (login-key.@expire-hours). */
    static long absoluteLifetimeMillis(def ec) {
        return resolveCookieMaxAgeSeconds(ec) * 1000L
    }

    /** Where a web key's end slides to at {@code nowMillis}: one idle window ahead, never past sign-in + the absolute lifetime. */
    static Timestamp slidThruDate(Timestamp fromDate, long nowMillis, long absoluteLifetimeMillis) {
        long idleEnd = nowMillis + IDLE_TIMEOUT_MILLIS
        return new Timestamp(fromDate == null ? idleEnd : Math.min(idleEnd, fromDate.time + absoluteLifetimeMillis))
    }

    /**
     * From the webroot before-request hook, after the framework has signed the request in (UserFacadeImpl.initFromHttpRequest
     * checks a login_key header against its UserLoginKey.thruDate on every request that has no session user).
     *
     * Every live login key the request carries for the signed-in user slides one idle window on, capped at sign-in + the
     * absolute lifetime, so the framework's own expiry check is the idle timeout. The sign-in is ended here (signed out of
     * this request, web session invalidated) when the first of the signed-in user's own keys it presents (headers, then
     * parameters, then the persistent-login cookie: the framework's order; hashes that are no key, or another user's, are
     * passed over) has run out, or when its web session is older than the absolute lifetime: a session the framework
     * restores without the key would otherwise outlive both.
     *
     * A CLI key keeps its own lifetime (CliAccess.SIGN_IN_DAYS), re-checked by CliAuthSupport.revalidateCliWebSession, so a
     * CLI session is left alone. Returns true when the sign-in was ended.
     */
    static boolean enforceWebSignInLifetime(def ec) {
        def request = ec?.web?.request
        String userId = ec?.user?.userId?.toString()?.trim()
        if (request == null || !userId) return false
        if (CliAuthSupport.isCliSession(ec)) return false

        long now = System.currentTimeMillis()
        long absoluteMillis = absoluteLifetimeMillis(ec)
        boolean ended = sessionOlderThan(request, now, absoluteMillis)
        // DAR-BE-264: a key (or, with no key, a web session) from before the person's two-factor was confirmed stops working.
        Timestamp factorConfirmed = TwoFactorSupport.activeConfirmedDate(ec, userId)
        boolean decided = false
        for (String hash : CliAuthSupport.requestLoginKeyHashes(ec)) {
            def key = TenantScopedFinder.findGlobalUnscopedPerRequest(ec, "moqui.security.UserLoginKey",
                            "self-scoped auth: slide the request's own web login key (pre-auth path, no active tenant)")
                    .condition("loginKey", hash).useCache(false).one()
            if (key == null || key.userId != userId) continue
            boolean predates = factorConfirmed != null && key.fromDate instanceof Timestamp &&
                    ((Timestamp) key.fromDate).before(factorConfirmed)
            boolean live = !predates && slideWebLoginKey(ec, key, now, absoluteMillis)
            if (!decided && !live) ended = true
            decided = true
        }
        if (!decided && factorConfirmed != null && sessionCreatedBefore(request, factorConfirmed.time)) ended = true
        if (ended) endWebSignIn(ec, request)
        return ended
    }

    /**
     * Moves one web key's end to {@link #slidThruDate} when it differs by a step or more (shortening a key issued before
     * DAR-BE-261 to the same rule). Returns false when the key has run out: its end was already past, or sign-in + the
     * absolute lifetime has passed (the key is then written as ended, so the framework refuses it from the next request).
     */
    static boolean slideWebLoginKey(def ec, def key, long nowMillis, long absoluteLifetimeMillis) {
        Timestamp thruDate = (Timestamp) key.thruDate
        if (thruDate != null && thruDate.time <= nowMillis) return false
        Timestamp slid = slidThruDate((Timestamp) key.fromDate, nowMillis, absoluteLifetimeMillis)
        if (thruDate == null || Math.abs(slid.time - thruDate.time) >= SLIDE_STEP_MILLIS) {
            try {
                ec.transaction.runUseOrBegin(null, "DAR-BE-261: could not slide a web login key", {
                    TenantScopedFinder.findGlobalUnscopedPerRequest(ec, "moqui.security.UserLoginKey",
                                    "self-scoped auth: slide the request's own web login key (pre-auth path, no active tenant)")
                            .condition("loginKey", key.loginKey).updateAll([thruDate: slid])
                })
            } catch (Exception e) {
                // A failed write costs this request's slide only; the end still applies below.
                logger.warn("Could not slide a web login key's end: ${e.message}")
            }
        }
        return slid.time > nowMillis
    }

    private static boolean sessionCreatedBefore(def request, long millis) {
        try {
            def session = request.getSession(false)
            return session != null && session.creationTime < millis
        } catch (IllegalStateException ignored) {
            return false // already invalidated
        }
    }

    private static boolean sessionOlderThan(def request, long nowMillis, long ageMillis) {
        try {
            def session = request.getSession(false)
            return session != null && nowMillis - session.creationTime >= ageMillis
        } catch (IllegalStateException ignored) {
            return false // already invalidated
        }
    }

    private static void endWebSignIn(def ec, def request) {
        try {
            ec.user.logoutLocal()
        } catch (Exception e) {
            logger.warn("Could not sign out a web session past its lifetime: ${e.message}")
        }
        try {
            request.getSession(false)?.invalidate()
        } catch (IllegalStateException ignored) {
            // already invalidated by the logout
        }
    }

    /**
     * Deletes the {@code UserLoginKey} row matching the cookie value, if any. Used on logout so
     * the cookie cannot be replayed even before its browser-side expiry. Returns true when a row
     * was removed.
     */
    static boolean revokePersistentLogin(def ec) {
        String loginKey = readPersistentLoginCookie(ec)
        if (!loginKey) return false

        String hashedKey = hashLoginKey(ec, loginKey)
        if (!hashedKey) return false

        def deleted = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.security.UserLoginKey",
                        "self-scoped auth: revoke persistent-login cookie token by hash")
                .condition("loginKey", hashedKey)
                .deleteAll()
        return ((deleted ?: 0) as int) > 0
    }

    /**
     * The {@code UserLoginKey.loginKey} value for a raw login key: the framework's login-key hash
     * ({@code login-key.@encrypt-hash-type}, no salt), exactly as {@code UserFacadeImpl.loginUserKey}
     * hashes before its lookup.
     */
    static String hashLoginKey(def ec, String rawKey) {
        if (!rawKey) return null
        return ec?.factory?.getSimpleHash(rawKey, "", ec?.factory?.getLoginKeyHashType(), false)
    }

    /**
     * Maps the configured login-key expiry (hours) — the absolute lifetime of a web sign-in (DAR-BE-261) — into a
     * cookie Max-Age (seconds). Defaults to 24 hours if the factory accessor throws or returns something unparseable.
     */
    static Integer resolveCookieMaxAgeSeconds(def ec) {
        float expireHours = 24.0f
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

        String hashedKey = hashLoginKey(ec, normalizedKey)
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
