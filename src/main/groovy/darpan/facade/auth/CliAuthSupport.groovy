package darpan.facade.auth

import darpan.facade.common.RpcMethodFence
import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.reconciliation.notification.TenantNotificationSupport
import org.moqui.entity.EntityCondition
import org.moqui.util.ContextStack
import org.moqui.util.StringUtilities
import org.slf4j.Logger
import org.slf4j.LoggerFactory

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
    private static final Logger logger = LoggerFactory.getLogger(CliAuthSupport.class)

    static final int DEVICE_CODE_LIFETIME_SECONDS = 600
    static final int POLL_INTERVAL_SECONDS = 3
    static final int MAX_STARTS_PER_ADDRESS_PER_MINUTE = 10
    static final String USER_CODE_ALPHABET = "BCDFGHJKLMNPQRSTVWXZ23456789"
    static final String DEFAULT_APP_BASE_URL = "https://app.drpn.ai"
    /** The address bucket for a start whose client IP is unknown (no web request, or a proxy that sent none). */
    static final String UNKNOWN_ADDRESS = "unknown"
    /** The memo key on ec.context's shared map (and, TRUE only, its stack). See isCliSession for which values count. */
    static final String CLI_SESSION_CONTEXT_KEY = "darpanCliSession"
    /** The memo on the servlet request (a Boolean). A client cannot set it. */
    static final String CLI_SESSION_ATTRIBUTE = "darpan.cliSession"
    /** The mark on a web session a CLI key signed in: that key's hash, so revalidateCliWebSession can re-check it.
     *  A client cannot set it. */
    static final String CLI_SESSION_MARK_ATTRIBUTE = "darpan.cliSessionKeyHash"
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

    /**
     * The CLI's hostname as shown to the person: printable, one line, at most 60 characters. Control characters
     * (C0 and C1), format characters (final review R26: bidi overrides and isolates, zero-width characters, the BOM)
     * and line/paragraph separators become spaces, so a label cannot reorder or hide text on the approval page.
     */
    static String normalizeHostLabel(Object hostLabel) {
        String value = hostLabel?.toString()?.replaceAll(/[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]/, " ")?.replaceAll(/\s+/, " ")?.trim()
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

    // ---- who may use the CLI (DAR-BE-195) ----

    static final String CLI_ACCESS_REFUSED = "The Darpan CLI is not turned on for this account. A Darpan admin turns it on."

    /**
     * Whether this user may use the CLI (approve a sign-in, keep using a key): a Darpan admin, or a live CLI grant in a
     * tenant they still belong to (DAR-BE-195, darpan.facade.auth.CliAccess).
     */
    static boolean cliAccessAllowed(def ec, String userId) {
        return normalize(userId) != null && CliAccess.cliAllowed(ec, normalize(userId))
    }

    /** The one RPC method held to the key's own expiry, not to a tenant: signing the caller's own CLI session out (DAR-BE-195). */
    static final String OWN_SIGN_OUT_METHOD = "facade.CliAuthFacadeServices.logout#CliSession"
    static final String CLI_KEY_NOT_SIGNED_IN = "A CLI call signs in with its login_key header. Run darpan login to sign in again."

    /**
     * A Moqui login key for the Darpan CLI, as UserFacadeImpl.getLoginKey issues one: 40 random characters, stored
     * hashed in moqui.security.UserLoginKey, and the user's already-expired keys cleared out. It lives as long as the
     * user's longest CLI sign-in lifetime (CliAccess.keyLifetimeDays: 30 days for an admin, 7 otherwise; DAR-BE-195),
     * read for userId, the approver: poll#CliLogin runs anonymous.
     * Returns [rawKey, hash, thruDate, expiresInSeconds]; rawKey exists nowhere else.
     */
    static Map<String, Object> issueLoginKey(def ec, String userId, String description = null) {
        if (!userId) throw new IllegalArgumentException("issueLoginKey needs a userId")
        int expiresInSeconds = CliAccess.keyLifetimeDays(ec, userId) * 86_400
        if (expiresInSeconds <= 0) throw new IllegalStateException(CLI_ACCESS_REFUSED)
        String rawKey = StringUtilities.getRandomString(40)
        String hash = AuthSessionSupport.hashLoginKey(ec, rawKey)
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
     * (AuthSessionSupport.restoreAuthenticatedSession). A web session a CLI key signed in is marked with that key's
     * hash, so a later request on that session alone (no key) is still a CLI session, and revalidateCliWebSession
     * can re-check it. A revoked CLI session's key still counts.
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
            String mark = sessionMark(request)
            String cliKeyHash = mark ? null : requestLoginKeyHashes(ec).find { String hash -> isCliKeyHash(ec, hash) }
            answer = mark != null || cliKeyHash != null
            request.setAttribute(CLI_SESSION_ATTRIBUTE, answer)
            if (cliKeyHash) markSession(request, cliKeyHash)
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
        return TenantScopedFinder.findGlobalUnscopedPerRequest(ec, "darpan.auth.CliSession",
                        "self-scoped auth: is the request's login key a CLI key (pre-auth path, no active tenant)")
                .condition("loginKeyHash", hash).useCache(false).count() > 0
    }

    /** The CLI key hash a web session was marked with, or null. */
    private static String sessionMark(def request) {
        Object mark = request.getSession(false)?.getAttribute(CLI_SESSION_MARK_ATTRIBUTE)
        return mark instanceof String && mark ? (String) mark : null
    }

    private static void markSession(def request, String cliKeyHash) {
        request.getSession(false)?.setAttribute(CLI_SESSION_MARK_ATTRIBUTE, cliKeyHash)
    }

    // ---- a CLI key's web session, re-checked on every request ----

    /**
     * Null when the request may go on; otherwise why it is refused, after the web session has been ended.
     *
     * A key login lives on in the Moqui web session (UserFacadeImpl.initFromHttpRequest stores the Shiro subject
     * in it and later requests are restored from it without the key: only hasLoggedOut is checked). So a session a
     * CLI key opened is re-checked here on every request, from the webroot before-request hook: its CliSession is
     * not revoked, its UserLoginKey has not expired, and the account can still sign in. Revoking, signing out or
     * disabling therefore takes effect on the next call. On a failure the user is logged out of this request
     * (logoutLocal: never hasLoggedOut, which would sign the person out of the app everywhere too) and the web
     * session is invalidated. Calls isCliSession first, which marks a session a CLI key has just signed in.
     */
    static String revalidateCliWebSession(def ec) {
        def request = ec?.web?.request
        if (request == null) return null
        isCliSession(ec)
        String cliKeyHash = sessionMark(request)
        if (!cliKeyHash) return null
        def cliSession = TenantScopedFinder.findGlobalUnscopedPerRequest(ec, "darpan.auth.CliSession",
                        "self-scoped auth: re-check a CLI key's web session (pre-auth path, no active tenant)")
                .condition("loginKeyHash", cliKeyHash).useCache(false).one()
        String reason = cliSessionProblem(ec, cliKeyHash, cliSession)
        if (reason) {
            endWebSession(ec, request)
            return reason
        }
        // DAR-BE-195: refuses this call only; the same sign-in may still be good in another tenant.
        return tenantCallProblem(ec, cliSession)
    }

    /**
     * Null, or why this call may not run in its tenant (the X-Darpan-Tenant header, else the user's preferred tenant, so
     * leaving the header off is no way round it): no live CLI grant there, or the sign-in is older than the user's role
     * there allows. Darpan admins pass: 30 days everywhere is their key's own lifetime. Signing the session out
     * (ownSignOutCall) passes too.
     *
     * The tenant is resolved for the signed-in user, so the request must be signed in as the key's own user: a CLI key
     * carried only as the persistent-login cookie is not a login yet here (no user, so no tenant to check), and a key
     * beside another user's web session would be checked against that user. Both are refused (review C2).
     */
    static String tenantCallProblem(def ec, def cliSession) {
        String userId = cliSession.userId as String
        if (TenantAccessSupport.currentUserId(ec) != userId) return CLI_KEY_NOT_SIGNED_IN
        if (CliAccess.isDarpanAdmin(ec, userId)) return null
        def request = ec.web?.request
        if (request != null && ownSignOutCall(RpcMethodFence.routedSegments(request), ec.web.requestParameters as Map)) return null
        return CliAccess.tenantProblem(ec, userId, TenantAccessSupport.currentActiveTenantUserGroupId(ec), (Timestamp) cliSession.createdDate)
    }

    /**
     * True only for a JSON-RPC call (or batch) of logout#CliSession alone. The method is read only on the rpc/json route,
     * as RpcMethodFence.enforce reads it: elsewhere (the GraphQL path) a method parameter means nothing (review C1).
     */
    static boolean ownSignOutCall(List<String> routedSegments, Map params) {
        if (!RpcMethodFence.isJsonRpc(routedSegments)) return false
        List<String> methods = RpcMethodFence.requestedMethods(params).findAll { it }
        return !methods.isEmpty() && methods.every { it == OWN_SIGN_OUT_METHOD }
    }

    private static String cliSessionProblem(def ec, String cliKeyHash, def cliSession) {
        if (cliSession == null || cliSession.revokedDate != null) return "This CLI session was signed out. Run darpan login to sign in again."
        def loginKey = TenantScopedFinder.findGlobalUnscopedPerRequest(ec, "moqui.security.UserLoginKey",
                        "self-scoped auth: re-check a CLI key's expiry for its web session")
                .condition("loginKey", cliKeyHash).useCache(false).one()
        Timestamp thruDate = (Timestamp) loginKey?.thruDate
        if (loginKey == null || (thruDate != null && thruDate.time <= System.currentTimeMillis())) {
            return "This CLI session has expired. Run darpan login to sign in again."
        }
        def account = TenantScopedFinder.findGlobalUnscopedPerRequest(ec, "moqui.security.UserAccount",
                        "self-scoped auth: re-check that a CLI session's account can still sign in")
                .condition("userId", cliSession.userId).useCache(false).one()
        if (!canSignIn(ec, account)) return "This account can no longer sign in."
        if (!cliAccessAllowed(ec, (String) cliSession.userId)) return CLI_ACCESS_REFUSED
        return null
    }

    private static void endWebSession(def ec, def request) {
        try {
            if (ec.user?.userId) ec.user.logoutLocal()
        } catch (Exception e) {
            logger.warn("Could not log out a refused CLI web session: ${e.message}")
        }
        try {
            request.getSession(false)?.invalidate()
        } catch (IllegalStateException ignored) {
            // already invalidated by the logout
        }
    }
}
