package darpan.reconciliation.notification

import darpan.facade.common.TenantScopedFinder
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.sql.Timestamp
import java.time.Duration

/**
 * The Slack app install (OAuth v2) flow: minting the CSRF state, redeeming it, and exchanging the
 * returned code for a workspace bot token.
 *
 * <h3>Why the state token carries all the security</h3>
 * <p>Slack redirects the installing admin's browser to a URL Darpan must serve <b>without
 * authentication</b> — the request arrives from Slack, not from a logged-in session. So the state
 * token is the only thing tying a returning {@code code} to the tenant that asked for it. It is
 * minted by an authenticated facade call, stored server-side, bound to one tenant, single-use, and
 * short-lived. A state that merely encoded the tenant id would be forgeable, and forging it would
 * let an attacker attach their own Slack workspace to another tenant's notifications.</p>
 *
 * <p>Nothing here ever logs the authorization code or the resulting token.</p>
 */
class SlackOAuthSupport {
    static final String AUTHORIZE_URL = "https://slack.com/oauth/v2/authorize"
    static final String ACCESS_URL = "https://slack.com/api/oauth.v2.access"
    static final String CALLBACK_PATH = "/apps/darpan/slackOauthCallback"

    /**
     * Scopes requested at install. chat:write.public is what lets Darpan post to a public channel
     * nobody remembered to invite it to; private channels still need an invite and the picker says so.
     */
    static final String REQUESTED_SCOPES = "chat:write,chat:write.public,channels:read,groups:read"

    static final String CLIENT_ID_ENV = "DARPAN_SLACK_CLIENT_ID"
    static final String CLIENT_ID_PROPERTY = "darpan.slack.clientId"
    static final String CLIENT_SECRET_ENV = "DARPAN_SLACK_CLIENT_SECRET"
    static final String CLIENT_SECRET_PROPERTY = "darpan.slack.clientSecret"
    static final String REDIRECT_URI_ENV = "DARPAN_SLACK_REDIRECT_URI"
    static final String REDIRECT_URI_PROPERTY = "darpan.slack.redirectUri"

    /** Long enough to authorise in Slack's UI, short enough that a leaked state is worthless. */
    static final int STATE_TTL_MINUTES = 10

    private static final Logger logger = LoggerFactory.getLogger(SlackOAuthSupport.class)
    private static final SecureRandom RANDOM = new SecureRandom()
    private static Closure exchangeHook = null

    /** Test seam: receives (code, redirectUri) and returns the parsed oauth.v2.access body. */
    static void setExchangeHook(Closure hook) { exchangeHook = hook }

    static void resetExchangeHook() { exchangeHook = null }

    static String resolveClientId(def ec) {
        return firstConfigured(ec, CLIENT_ID_ENV, CLIENT_ID_PROPERTY)
    }

    static String resolveClientSecret(def ec) {
        return firstConfigured(ec, CLIENT_SECRET_ENV, CLIENT_SECRET_PROPERTY)
    }

    /**
     * The callback URL Slack redirects to. Must be registered verbatim in the Slack app and must be
     * HTTPS (Slack permits localhost only during development).
     *
     * <p>Derived from the app base URL when not set explicitly, but the base URL points at the SPA
     * while the callback is served by the BACKEND — so on any deployment where those are different
     * hosts, {@code darpan.slack.redirectUri} must be set. Returning null here is what makes
     * begin#SlackInstall refuse with a configuration message instead of sending the admin to Slack
     * with a redirect_uri that will be rejected.</p>
     */
    static String resolveRedirectUri(def ec) {
        String explicit = firstConfigured(ec, REDIRECT_URI_ENV, REDIRECT_URI_PROPERTY)
        if (explicit) return explicit
        String appBaseUrl = TenantNotificationSupport.resolveAppBaseUrl(ec)
        return appBaseUrl ? "${appBaseUrl}${CALLBACK_PATH}".toString() : null
    }

    private static String firstConfigured(def ec, String envName, String propertyName) {
        return System.getenv(envName)?.toString()?.trim() ?:
                ec?.resource?.properties?.get(propertyName)?.toString()?.trim() ?:
                System.getProperty(propertyName)?.toString()?.trim() ?:
                null
    }

    /**
     * Creates a single-use state row and returns its token. Called only from an authenticated
     * facade service, which is where the tenant binding comes from.
     */
    static String mintState(def ec, String tenantId, String userId) {
        if (!((tenantId)?.toString()?.trim())) return null
        byte[] entropy = new byte[32]
        RANDOM.nextBytes(entropy)
        String stateToken = Base64.urlEncoder.withoutPadding().encodeToString(entropy)

        def now = ec.user.nowTimestamp
        def stateRow = ec.entity.makeValue("darpan.reconciliation.SlackOAuthState")
        stateRow.set("stateToken", stateToken)
        stateRow.set("companyUserGroupId", tenantId)
        stateRow.set("initiatedByUserId", userId)
        stateRow.set("createdDate", now)
        stateRow.set("expiresDate", new Timestamp(((Timestamp) now).time + STATE_TTL_MINUTES * 60_000L))
        stateRow.create()
        return stateToken
    }

    /**
     * Redeems a state token exactly once and returns the tenant it was minted for.
     *
     * <p>The consume is an atomic conditional update (WHERE consumedDate IS NULL) rather than a
     * read-then-write, for the same reason the notification claim is: a double-clicked callback can
     * pass a read-only check twice, and only one caller may win.</p>
     *
     * @return {@code [ok, companyUserGroupId, initiatedByUserId, reason]}
     */
    static Map<String, Object> consumeState(def ec, Object rawStateToken) {
        String stateToken = ((rawStateToken)?.toString()?.trim())
        if (!stateToken) return [ok: false, reason: "MISSING_STATE"]

        def stateRow = TenantScopedFinder.findGlobalUnscoped(ec, "darpan.reconciliation.SlackOAuthState",
                        "oauth state redemption keyed by an unguessable single-use token — the token IS the authorization")
                ?.condition("stateToken", stateToken)
                ?.useCache(false)?.one()
        if (stateRow == null) return [ok: false, reason: "UNKNOWN_STATE"]

        def expiresDate = stateRow.expiresDate
        if (expiresDate != null && ((Timestamp) expiresDate).before((Timestamp) ec.user.nowTimestamp)) {
            return [ok: false, reason: "EXPIRED_STATE"]
        }

        long claimed = TenantScopedFinder.findGlobalUnscoped(ec, "darpan.reconciliation.SlackOAuthState",
                        "atomic single-use state claim — conditional update where consumedDate is null")
                ?.condition("stateToken", stateToken)
                ?.condition("consumedDate", null)
                ?.updateAll([consumedDate: ec.user.nowTimestamp]) ?: 0
        if (claimed == 0) return [ok: false, reason: "STATE_ALREADY_USED"]

        return [
                ok                : true,
                companyUserGroupId: ((stateRow.companyUserGroupId)?.toString()?.trim()),
                initiatedByUserId : ((stateRow.initiatedByUserId)?.toString()?.trim()),
        ]
    }

    /** The URL to send the admin to. Scope list and state are what Slack shows on its consent screen. */
    static String buildAuthorizeUrl(String clientId, String redirectUri, String stateToken) {
        Map<String, String> params = [
                client_id   : clientId,
                scope       : REQUESTED_SCOPES,
                redirect_uri: redirectUri,
                state       : stateToken,
        ]
        String query = params.collect { entry ->
            "${URLEncoder.encode(entry.key, StandardCharsets.UTF_8.name())}=${URLEncoder.encode(entry.value, StandardCharsets.UTF_8.name())}"
        }.join("&")
        return "${AUTHORIZE_URL}?${query}".toString()
    }

    /**
     * Exchanges the callback code for a bot token.
     *
     * @return {@code [ok, teamId, teamName, appId, botUserId, botAccessToken, grantedScopes, reason]}
     */
    static Map<String, Object> exchangeCode(def ec, String code, String redirectUri) {
        String clientId = resolveClientId(ec)
        String clientSecret = resolveClientSecret(ec)
        if (!clientId || !clientSecret) return [ok: false, reason: "SLACK_APP_NOT_CONFIGURED"]

        Map<String, Object> body
        try {
            body = exchangeHook != null ?
                    ((exchangeHook.call(code, redirectUri) ?: [:]) as Map<String, Object>) :
                    postForm(clientId, clientSecret, code, redirectUri)
        } catch (Throwable t) {
            // Deliberately does not include the exception's message verbatim in the returned reason:
            // an HTTP client error can echo the request, and the request carries the code.
            logger.warn("Slack OAuth code exchange failed: {}", t.class.simpleName)
            return [ok: false, reason: "EXCHANGE_FAILED"]
        }
        return readExchangeBody(body)
    }

    /**
     * Reads an oauth.v2.access body. Pure, so the field-selection rule below is unit-testable.
     *
     * <p>The bot token is the <b>top-level</b> {@code access_token}. {@code authed_user.access_token}
     * is a different, user-scoped token: storing that by mistake yields something that works during
     * testing — the installer is nearly always a workspace admin — and then posts as that person, or
     * fails outright, for everyone else. The {@code token_type} check is what makes the mistake loud.</p>
     */
    static Map<String, Object> readExchangeBody(Map<String, Object> body) {
        if (body == null || body.get("ok") != true) {
            return [ok: false, reason: ((body?.get("error"))?.toString()?.trim()) ?: "EXCHANGE_REJECTED"]
        }
        String botAccessToken = ((body.get("access_token"))?.toString()?.trim())
        String tokenType = ((body.get("token_type"))?.toString()?.trim())
        if (!botAccessToken) return [ok: false, reason: "NO_BOT_TOKEN"]
        if (tokenType && tokenType != "bot") return [ok: false, reason: "NOT_A_BOT_TOKEN"]

        Map team = body.get("team") instanceof Map ? (Map) body.get("team") : [:]
        return [
                ok            : true,
                teamId        : ((team.get("id"))?.toString()?.trim()),
                teamName      : ((team.get("name"))?.toString()?.trim()),
                appId         : ((body.get("app_id"))?.toString()?.trim()),
                botUserId     : ((body.get("bot_user_id"))?.toString()?.trim()),
                botAccessToken: botAccessToken,
                grantedScopes : ((body.get("scope"))?.toString()?.trim()),
        ]
    }

    /**
     * Creates or refreshes the tenant's install row. Re-installing the same workspace updates the
     * existing row rather than accumulating duplicates — Slack issues a fresh token on every
     * install, and the old one stops working, so a second row would be a silently dead destination.
     */
    static String upsertInstall(def ec, String tenantId, String userId, Map<String, Object> exchange) {
        String teamId = ((exchange.teamId)?.toString()?.trim())
        def now = ec.user.nowTimestamp
        def existing = teamId ? TenantScopedFinder.findGlobalUnscoped(ec,
                        "darpan.reconciliation.SlackWorkspaceInstall",
                        "install upsert pinned to the redeemed state's tenant — explicit companyUserGroupId condition applied")
                ?.condition("companyUserGroupId", tenantId)
                ?.condition("teamId", teamId)
                ?.useCache(false)?.one() : null

        Map<String, Object> fields = [
                companyUserGroupId: tenantId,
                teamId            : teamId,
                teamName          : exchange.teamName,
                appId             : exchange.appId,
                botUserId         : exchange.botUserId,
                botAccessToken    : exchange.botAccessToken,
                grantedScopes     : exchange.grantedScopes,
                isActive          : "Y",
                lastUpdatedDate   : now,
        ]
        if (existing != null) {
            fields.each { key, value -> existing.set(key as String, value) }
            existing.update()
            return ((existing.slackInstallId)?.toString()?.trim())
        }
        def created = ec.entity.makeValue("darpan.reconciliation.SlackWorkspaceInstall")
        fields.each { key, value -> created.set(key as String, value) }
        created.set("installedByUserId", userId)
        created.set("installedDate", now)
        created.setSequencedIdPrimary()
        created.create()
        return ((created.slackInstallId)?.toString()?.trim())
    }

    /** The active bot token for one install, or null. Never returned through a facade. */
    static String resolveBotToken(def ec, String tenantId, Object rawInstallId) {
        String installId = ((rawInstallId)?.toString()?.trim())
        if (!installId || !tenantId) return null
        // findOneOwnedByTenant, NOT a companyUserGroupId condition: slackInstallId is the complete
        // primary key, and Moqui drops non-PK conditions on that path — the tenant pin would be
        // decoration and this read would hand back another tenant's bot token.
        def installRow = TenantScopedFinder.findOneOwnedByTenant(ec,
                "darpan.reconciliation.SlackWorkspaceInstall", "slackInstallId", installId, tenantId,
                "bot token read verified against the destination's tenant after a primary-key read")
        if (installRow == null || ((installRow.isActive)?.toString()?.trim()) == "N") return null
        return ((installRow.botAccessToken)?.toString()?.trim()) ?: null
    }

    /**
     * The whole callback, as one testable unit: redeem the state, exchange the code, store the
     * install, and decide where to send the browser.
     *
     * <p>Always returns a URL to redirect to — never throws and never renders. An admin who lands
     * here has left Darpan's UI entirely, so the only way to tell them anything is to put it in the
     * URL they come back on. Failure reasons are short slugs, not raw Slack text, so nothing
     * user-controlled is reflected into the SPA.</p>
     *
     * <p>Order matters: the state is consumed BEFORE the code is exchanged. A code that fails to
     * exchange still burns its state, which is correct — the state authorises one attempt, and
     * leaving it live would let a replayed callback keep trying.</p>
     */
    static String handleCallback(def ec, Object rawCode, Object rawState, Object rawError) {
        String appBaseUrl = TenantNotificationSupport.resolveAppBaseUrl(ec)
        // With no base URL there is nowhere to send them; the caller renders a bare page instead of
        // redirecting to a guessed host (audit H11.2 — a wrong default once routed prod links to dev).
        if (!appBaseUrl) return null

        String declined = ((rawError)?.toString()?.trim())
        if (declined) return failureUrl(appBaseUrl, declined == "access_denied" ? "declined" : "slack_error")

        Map<String, Object> state = consumeState(ec, rawState)
        if (state.ok != true) return failureUrl(appBaseUrl, stateReasonSlug((String) state.reason))

        String code = ((rawCode)?.toString()?.trim())
        if (!code) return failureUrl(appBaseUrl, "missing_code")

        Map<String, Object> exchange = exchangeCode(ec, code, resolveRedirectUri(ec))
        if (exchange.ok != true) {
            logger.warn("Slack install exchange rejected for tenant {}: {}",
                    state.companyUserGroupId, exchange.reason)
            return failureUrl(appBaseUrl, exchange.reason == "SLACK_APP_NOT_CONFIGURED" ? "not_configured" : "exchange_failed")
        }

        try {
            upsertInstall(ec, (String) state.companyUserGroupId, (String) state.initiatedByUserId, exchange)
        } catch (Throwable t) {
            logger.error("Slack install could not be stored for tenant {}: {}",
                    state.companyUserGroupId, t.message)
            return failureUrl(appBaseUrl, "store_failed")
        }

        String teamName = ((exchange.teamName)?.toString()?.trim())
        String query = "slack=connected" + (teamName ?
                "&team=" + URLEncoder.encode(teamName, StandardCharsets.UTF_8.name()) : "")
        return "${appBaseUrl}/settings?${query}".toString()
    }

    private static String stateReasonSlug(String reason) {
        switch (reason) {
            case "EXPIRED_STATE": return "expired"
            case "STATE_ALREADY_USED": return "already_used"
            default: return "bad_state"
        }
    }

    private static String failureUrl(String appBaseUrl, Object reasonSlug) {
        String slug = ((reasonSlug)?.toString()?.trim()) ?: "failed"
        return "${appBaseUrl}/settings?slack=error&reason=${URLEncoder.encode(slug, StandardCharsets.UTF_8.name())}".toString()
    }

    private static Map<String, Object> postForm(String clientId, String clientSecret, String code, String redirectUri) {
        String form = [client_id: clientId, client_secret: clientSecret, code: code, redirect_uri: redirectUri]
                .findAll { it.value }
                .collect { "${URLEncoder.encode(it.key, StandardCharsets.UTF_8.name())}=${URLEncoder.encode(it.value as String, StandardCharsets.UTF_8.name())}" }
                .join("&")
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        HttpRequest request = HttpRequest.newBuilder(URI.create(ACCESS_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build()
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString())
        Object parsed = new JsonSlurper().parseText(response.body() ?: "{}")
        return parsed instanceof Map ? (Map<String, Object>) parsed : [:]
    }
}
