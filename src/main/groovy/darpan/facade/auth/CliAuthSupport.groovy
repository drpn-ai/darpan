package darpan.facade.auth

import darpan.facade.common.TenantScopedFinder
import darpan.reconciliation.notification.TenantNotificationSupport
import org.moqui.entity.EntityCondition
import org.moqui.util.ContextStack
import org.moqui.util.StringUtilities

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Timestamp

import static darpan.common.ValueSupport.normalize

/**
 * DAR-CLI-003 — the Groovy half of browser-approved CLI sign-in (service/facade/CliAuthFacadeServices.xml holds
 * the entity reads and writes): random codes, hashing, issuing the login key, reading the request's key, and the
 * per-address start limit. Spec: wrapper docs/superpowers/specs/2026-11-03-tenant-agent-cli-mcp-design.md 6.2, 7.
 */
class CliAuthSupport {
    static final int DEVICE_CODE_LIFETIME_SECONDS = 600
    static final int POLL_INTERVAL_SECONDS = 3
    static final int MAX_STARTS_PER_ADDRESS_PER_MINUTE = 10
    static final String USER_CODE_ALPHABET = "BCDFGHJKLMNPQRSTVWXZ23456789"
    static final String DEFAULT_APP_BASE_URL = "https://app.drpn.ai"
    /** The address bucket for a start whose client IP is unknown (no web request, or a proxy that sent none). */
    static final String UNKNOWN_ADDRESS = "unknown"
    /** The memo key on ec.context's shared map (and, TRUE only, its stack). See isCliSession for which values count. */
    static final String CLI_SESSION_CONTEXT_KEY = "darpanCliSession"
    /** The memo on the servlet request, and the mark on a web session a CLI key signed in. A client cannot set either. */
    static final String CLI_SESSION_ATTRIBUTE = "darpan.cliSession"
    static final int HOST_LABEL_MAX_LENGTH = 60
    static final int CLI_SESSION_ID_LENGTH = 12

    private static final int DEVICE_CODE_BYTES = 32
    private static final int USER_CODE_TRIES = 20
    private static final SecureRandom random = new SecureRandom()

    // ---- codes ----

    /** 32 random bytes, base64url without padding (43 characters). Only its hash is stored. */
    static String newDeviceCode() {
        byte[] bytes = new byte[DEVICE_CODE_BYTES]
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** SHA-256 hex: the device code is 256 random bits, so no salt or slow hash is needed. */
    static String hashDeviceCode(Object deviceCode) {
        String value = normalize(deviceCode)
        if (!value) return null
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)).encodeHex().toString()
    }

    /** XXXX-XXXX from USER_CODE_ALPHABET (no vowels, no 0/1: nothing to misread, no words), unused by any row. */
    static String newUserCode(def ec) {
        for (int attempt = 0; attempt < USER_CODE_TRIES; attempt++) {
            StringBuilder code = new StringBuilder(9)
            for (int i = 0; i < 8; i++) {
                if (i == 4) code.append('-')
                code.append(USER_CODE_ALPHABET.charAt(random.nextInt(USER_CODE_ALPHABET.length())))
            }
            String candidate = code.toString()
            long taken = TenantScopedFinder.findGlobalUnscoped(ec, "darpan.auth.CliDeviceCode",
                            "self-scoped auth: CLI user codes are one namespace, unique across all rows")
                    .condition("userCode", candidate).useCache(false).count()
            if (taken == 0L) return candidate
        }
        throw new IllegalStateException("Could not find an unused CLI user code")
    }

    /** What the person typed, as stored: upper case, dash in the middle. Null when it cannot be a user code. */
    static String normalizeUserCode(Object userCode) {
        String letters = normalize(userCode)?.toUpperCase()?.replaceAll(/[^A-Z0-9]/, "")
        if (letters?.length() != 8) return null
        return letters.substring(0, 4) + "-" + letters.substring(4)
    }

    /** The CLI's hostname as shown to the person: printable, one line, at most 60 characters. */
    static String normalizeHostLabel(Object hostLabel) {
        String value = hostLabel?.toString()?.replaceAll(/\p{Cntrl}/, " ")?.replaceAll(/\s+/, " ")?.trim()
        if (!value) return null
        return value.length() > HOST_LABEL_MAX_LENGTH ? value.substring(0, HOST_LABEL_MAX_LENGTH) : value
    }

    /** The public id of a CLI session: the first 12 hex characters of its key's hash. Not a credential. */
    static String cliSessionId(Object loginKeyHash) {
        String value = normalize(loginKeyHash)
        return value ? value.take(CLI_SESSION_ID_LENGTH) : null
    }

    /** The Darpan app's base URL, as Google Chat links resolve it (TenantNotificationSupport.resolveAppBaseUrl:
     *  DARPAN_APP_BASE_URL, then the darpan.app.baseUrl property, then the first allowed origin), else app.drpn.ai. */
    static String appBaseUrl(def ec) {
        return TenantNotificationSupport.resolveAppBaseUrl(ec) ?: DEFAULT_APP_BASE_URL
    }

    // ---- start limit ----

    static String requestAddress(def ec) {
        return normalize(ec?.user?.clientIp) ?: UNKNOWN_ADDRESS
    }

    /** True once MAX_STARTS_PER_ADDRESS_PER_MINUTE starts came from this address in the last 60 s. Counted from
     *  stored rows, as PasswordResetSupport counts reset codes per IP, so the limit survives a restart. */
    static boolean overStartLimit(def ec, String address, Timestamp now) {
        Timestamp minuteAgo = new Timestamp(now.time - 60_000L)
        long recent = TenantScopedFinder.findGlobalUnscoped(ec, "darpan.auth.CliDeviceCode",
                        "rate limit: CLI sign-in starts per client address")
                .condition("requestIp", address ?: UNKNOWN_ADDRESS)
                .condition("createdDate", EntityCondition.ComparisonOperator.GREATER_THAN_EQUAL_TO, minuteAgo)
                .useCache(false).count()
        return recent >= MAX_STARTS_PER_ADDRESS_PER_MINUTE
    }

    // ---- the key ----

    /** Whether the framework would let this account sign in now: not disabled (or past a temporary lockout), not
     *  terminated. A key is never issued to an account that fails this. */
    static boolean canSignIn(def ec, def account) {
        if (account == null) return false
        if (PasswordResetSupport.lockedOut(ec, account)) return false
        Timestamp terminateDate = (Timestamp) account.terminateDate
        return terminateDate == null || terminateDate.time > System.currentTimeMillis()
    }

    /**
     * A Moqui login key for userId, as UserFacadeImpl.getLoginKey issues one for the signed-in user: 40 random
     * characters, stored hashed in moqui.security.UserLoginKey, expiring after the framework's login-key hours
     * (the same lifetime as a sign-in in the app), and the user's already-expired keys cleared out.
     * Returns [rawKey, hash, thruDate, expiresInSeconds]; rawKey exists nowhere else.
     */
    static Map<String, Object> issueLoginKey(def ec, String userId, String description = null) {
        if (!userId) throw new IllegalArgumentException("issueLoginKey needs a userId")
        String rawKey = StringUtilities.getRandomString(40)
        String hash = AuthSessionSupport.hashLoginKey(ec, rawKey)
        int expiresInSeconds = AuthSessionSupport.resolveCookieMaxAgeSeconds(ec)
        Timestamp fromDate = ec.user.nowTimestamp
        Timestamp thruDate = new Timestamp(fromDate.time + expiresInSeconds * 1000L)
        ec.entity.makeValue("moqui.security.UserLoginKey")
                .setAll([loginKey: hash, userId: userId, fromDate: fromDate, thruDate: thruDate, description: description])
                .create()
        TenantScopedFinder.findGlobalUnscoped(ec, "moqui.security.UserLoginKey",
                        "self-scoped auth: clear the user's expired login keys, as UserFacadeImpl.getLoginKey does")
                .condition("userId", userId)
                .condition("thruDate", EntityCondition.ComparisonOperator.LESS_THAN, fromDate)
                .deleteAll()
        return [rawKey: rawKey, hash: hash, thruDate: thruDate, expiresInSeconds: expiresInSeconds]
    }

    // ---- is this request a CLI session ----

    /**
     * True when the request is signed in with a key issued to the Darpan CLI. Call this; never read the memos.
     *
     * Memo, read first: darpanCliSession on ec.context's shared map (ContextStack.getSharedMap()), which a service
     * call does not hide — the inline service runner calls pushContext(), so a value put on the context stack
     * outside a service is invisible inside it. A TRUE (shared map or stack) is honoured at once: it can only
     * restrict. A FALSE is never honoured on a web request: the REST handler pushes the request's own parameters
     * onto the stack, so a client could send darpanCliSession=false. There the answer is memoised on the servlet
     * request, which no client can set.
     *
     * The keys looked at are every place the framework or Darpan signs a request in from: the api_key and
     * login_key headers and parameters (UserFacadeImpl.initFromHttpRequest) and the persistent-login cookie
     * (AuthSessionSupport.restoreAuthenticatedSession). A web session a CLI key signed in is marked, so a later
     * request on that session alone (no key) is still a CLI session. A revoked CLI session's key still counts.
     */
    static boolean isCliSession(def ec) {
        Map shared = sharedMap(ec)
        if (shared?.get(CLI_SESSION_CONTEXT_KEY) == Boolean.TRUE || ec?.context?.get(CLI_SESSION_CONTEXT_KEY) == Boolean.TRUE) return true
        def request = ec?.web?.request
        // No web request: nothing carries a key, so the answer is false whether or not a FALSE memo is set.
        if (request == null) return false

        Object known = request.getAttribute(CLI_SESSION_ATTRIBUTE)
        boolean answer
        if (known instanceof Boolean) {
            answer = (Boolean) known
        } else {
            answer = sessionMarked(request) || requestLoginKeyHashes(ec).any { String hash -> isCliKeyHash(ec, hash) }
            request.setAttribute(CLI_SESSION_ATTRIBUTE, answer)
            if (answer) markSession(request)
        }
        shared?.put(CLI_SESSION_CONTEXT_KEY, answer)
        return answer
    }

    /** ec.context's shared map: one per ExecutionContext, kept across pushContext(). Null for a stub context. */
    private static Map sharedMap(def ec) {
        def context = ec?.context
        return context instanceof ContextStack ? ((ContextStack) context).getSharedMap() : null
    }

    /** The hashes of every login key this request carries (headers, parameters, persistent-login cookie). */
    static List<String> requestLoginKeyHashes(def ec) {
        def request = ec?.web?.request
        if (request == null) return []
        List<Object> candidates = [request.getHeader("api_key"), request.getHeader("login_key")]
        try {
            Map parameters = ec.web.secureRequestParameters as Map
            if (parameters) candidates.addAll([parameters.api_key, parameters.login_key])
        } catch (Exception ignored) {
            // no parameters to read (a request the framework could not parse): the headers and cookie still count
        }
        candidates << AuthSessionSupport.readPersistentLoginCookie(ec)
        return candidates.collect { normalize(it) }
                .findAll { String key -> key && key != "null" && key != "undefined" }
                .collect { String key -> AuthSessionSupport.hashLoginKey(ec, key) }
                .findAll { it }
                .unique()
    }

    private static boolean isCliKeyHash(def ec, String hash) {
        return TenantScopedFinder.findGlobalUnscoped(ec, "darpan.auth.CliSession",
                        "self-scoped auth: is the request's login key a CLI key (pre-auth path, no active tenant)")
                .condition("loginKeyHash", hash).useCache(false).count() > 0
    }

    private static boolean sessionMarked(def request) {
        return request.getSession(false)?.getAttribute(CLI_SESSION_ATTRIBUTE) == Boolean.TRUE
    }

    private static void markSession(def request) {
        request.getSession(false)?.setAttribute(CLI_SESSION_ATTRIBUTE, Boolean.TRUE)
    }
}
