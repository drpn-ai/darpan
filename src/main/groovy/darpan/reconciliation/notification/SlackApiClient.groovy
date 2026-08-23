package darpan.reconciliation.notification

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Slack Web API calls Darpan makes, plus the response contract that governs all of them.
 *
 * <p>Deliberately free of Moqui and of any entity access so it runs in the fast unitTest pool, and
 * so {@link #interpretResponse} — the part that is easy to get wrong — is testable without a
 * socket.</p>
 *
 * <h3>Why a status code is not an answer</h3>
 * <p>The Slack Web API answers a <b>failed</b> call with <b>HTTP 200</b> and {@code {"ok": false,
 * "error": "<code>"}}. Reading only the status code marks every dead token, deleted channel, and
 * un-invited bot as delivered, and the tenant never learns their notifications stopped arriving.
 * Incoming webhooks have the same shape but only ever say {@code ok} or a bare reason string; here
 * the reason is a documented code, so it is mapped to an operator-facing sentence rather than
 * logged raw.</p>
 */
class SlackApiClient {
    static final String API_BASE = "https://slack.com/api"

    /**
     * Failures that will keep failing until a human changes something — a channel was deleted, the
     * bot was never invited, the app was uninstalled. These consume the notification's one delivery
     * attempt legitimately; retrying them achieves nothing.
     */
    static final List<String> ACTIONABLE_ERRORS = [
            "channel_not_found", "not_in_channel", "is_archived", "channel_is_archived",
            "invalid_auth", "token_revoked", "token_expired", "account_inactive",
            "missing_scope", "not_allowed_token_type", "restricted_action",
            "restricted_action_read_only_channel", "no_permission",
    ].asImmutable()

    /**
     * Failures that would plausibly succeed on a later attempt. Distinguished from the above so a
     * rate-limited run reads as "Slack was busy" rather than "your channel is misconfigured" — the
     * two demand completely different operator responses, and conflating them is how a transient
     * blip gets debugged as a permissions problem.
     */
    static final List<String> TRANSIENT_ERRORS = [
            "rate_limited", "ratelimited", "service_unavailable", "internal_error", "fatal_error",
            "request_timeout",
    ].asImmutable()

    private static Closure transportHook = null

    /** Test seam: receives (method, token, payload) and returns [statusCode, body]. */
    static void setTransportHook(Closure hook) { transportHook = hook }

    static void resetTransportHook() { transportHook = null }

    /**
     * Posts a message and returns the {@code ts} handle on success.
     *
     * @return {@code [ok, channelId, ts, errorCode, transient, operatorMessage, statusCode]}
     */
    static Map<String, Object> postMessage(String botToken, String channelId, String text) {
        Map<String, Object> response = call("chat.postMessage", botToken,
                [channel: channelId, text: text])
        Map<String, Object> outcome = interpretResponse(
                (int) response.statusCode, (String) response.body)
        if (outcome.ok == true) {
            Map body = (Map) outcome.body
            outcome.put("ts", ((body?.get("ts"))?.toString()?.trim()))
            outcome.put("channelId", ((body?.get("channel"))?.toString()?.trim()) ?: channelId)
        }
        return outcome
    }

    /** Every scope the product needs; a token missing any of these is only partly usable. */
    static final List<String> REQUIRED_SCOPES = [
            "chat:write", "chat:write.public", "channels:read", "groups:read",
    ].asImmutable()

    /**
     * Identifies a bot token: is it live, whose workspace is it, and what was it granted?
     *
     * <p>This is what makes a pasted token safe to store. Without it Darpan would accept any string,
     * write it as a workspace connection, and the tenant would discover the mistake only when a run
     * failed to notify — by which time the run is over and the alert is gone.</p>
     *
     * @return {@code [ok, teamId, teamName, botUserId, botName, grantedScopes, missingScopes, errorCode, operatorMessage]}
     */
    static Map<String, Object> authTest(String botToken) {
        Map<String, Object> response = call("auth.test", botToken, [:])
        Map<String, Object> outcome = interpretResponse((int) response.statusCode, (String) response.body)
        if (outcome.ok != true) return outcome

        Map body = (Map) outcome.body
        String grantedScopes = ((response.scopes)?.toString()?.trim()) ?: ""
        List<String> granted = grantedScopes ? grantedScopes.split(",")*.trim() : []
        outcome.put("teamId", ((body?.get("team_id"))?.toString()?.trim()))
        outcome.put("teamName", ((body?.get("team"))?.toString()?.trim()))
        outcome.put("botUserId", ((body?.get("user_id"))?.toString()?.trim()))
        outcome.put("botName", ((body?.get("user"))?.toString()?.trim()))
        outcome.put("grantedScopes", grantedScopes)
        // Reported, not enforced: a token with only chat:write still delivers notifications, which is
        // the point of the feature. Refusing it outright would block a working setup over a picker.
        outcome.put("missingScopes", REQUIRED_SCOPES.findAll { !granted.contains(it) })
        return outcome
    }

    /** Edits a message posted earlier, addressed by the ts {@link #postMessage} returned. */
    static Map<String, Object> updateMessage(String botToken, String channelId, String messageTs, String text) {
        Map<String, Object> response = call("chat.update", botToken,
                [channel: channelId, ts: messageTs, text: text])
        return interpretResponse((int) response.statusCode, (String) response.body)
    }

    /**
     * One page of conversations for the channel picker.
     *
     * <p>Paginated deliberately rather than looped-to-exhaustion here: this endpoint is rate-limit
     * Tier 2 (~20 requests/minute) and a large workspace has thousands of channels, so draining it
     * on every settings page load would spend the tenant's whole budget on a dropdown.</p>
     *
     * @return {@code [ok, channels: [[id, name, isPrivate, isMember, isArchived]], nextCursor, ...]}
     */
    static Map<String, Object> listConversations(String botToken, String cursor, Integer limit) {
        Map<String, Object> params = [
                types            : "public_channel,private_channel",
                exclude_archived : true,
                limit            : limit == null ? 200 : limit,
        ]
        if (((cursor)?.toString()?.trim())) params.put("cursor", cursor.trim())
        Map<String, Object> response = call("conversations.list", botToken, params)
        Map<String, Object> outcome = interpretResponse((int) response.statusCode, (String) response.body)
        if (outcome.ok != true) return outcome

        Map body = (Map) outcome.body
        List<Map<String, Object>> channels = []
        ((body?.get("channels") instanceof List ? (List) body.get("channels") : []) as List).each { Object raw ->
            if (!(raw instanceof Map)) return
            Map channel = (Map) raw
            channels.add([
                    id       : ((channel.get("id"))?.toString()?.trim()),
                    name     : ((channel.get("name"))?.toString()?.trim()),
                    isPrivate: channel.get("is_private") == true,
                    // is_member drives the picker's "invite @Darpan first" warning: chat:write.public
                    // covers public channels the bot never joined, but a PRIVATE channel it is not in
                    // returns not_in_channel at the first run, hours after the admin walked away.
                    isMember : channel.get("is_member") == true,
                    isArchived: channel.get("is_archived") == true,
            ])
        }
        outcome.put("channels", channels)
        outcome.put("nextCursor",
                ((((body?.get("response_metadata")) instanceof Map
                        ? ((Map) body.get("response_metadata")).get("next_cursor") : null))?.toString()?.trim()) ?: null)
        return outcome
    }

    /**
     * The Slack Web API success contract, isolated from transport so it can be tested exhaustively.
     *
     * <p>An HTTP failure and an {@code ok:false} body are both failures, but only the latter carries
     * a code worth explaining. A body that will not parse is treated as a failure rather than
     * optimistically as success — silence about a delivery is worse than a false alarm about one.</p>
     */
    static Map<String, Object> interpretResponse(int statusCode, String rawBody) {
        Map<String, Object> parsed = null
        try {
            Object slurped = ((rawBody)?.toString()?.trim()) ? new JsonSlurper().parseText(rawBody) : null
            if (slurped instanceof Map) parsed = (Map<String, Object>) slurped
        } catch (Exception ignored) {
            parsed = null
        }

        boolean httpOk = statusCode >= 200 && statusCode < 300
        if (parsed == null) {
            // HTTP 429 arrives with no useful body; treat it as the transient it is rather than as
            // an unexplained parse failure.
            boolean rateLimited = statusCode == 429
            return [
                    ok             : false,
                    statusCode     : statusCode,
                    errorCode      : rateLimited ? "rate_limited" : "unparseable_response",
                    transient      : rateLimited || statusCode >= 500,
                    operatorMessage: rateLimited ? describeError("rate_limited") :
                            "Slack returned a response Darpan could not read (HTTP ${statusCode}).".toString(),
                    body           : null,
            ]
        }

        if (httpOk && parsed.get("ok") == true) {
            return [ok: true, statusCode: statusCode, errorCode: null, transient: false,
                    operatorMessage: null, body: parsed]
        }

        String errorCode = ((parsed.get("error"))?.toString()?.trim()) ?: "unknown_error"
        return [
                ok             : false,
                statusCode     : statusCode,
                errorCode      : errorCode,
                transient      : TRANSIENT_ERRORS.contains(errorCode) || statusCode >= 500,
                operatorMessage: describeError(errorCode),
                body           : parsed,
        ]
    }

    /** Whether a failure needs a human rather than a retry. */
    static boolean isActionable(Object errorCode) {
        return ACTIONABLE_ERRORS.contains(((errorCode)?.toString()?.trim()))
    }

    /**
     * An operator-facing sentence per Slack error code. The raw codes are developer-facing and
     * several of them ({@code not_in_channel}, {@code missing_scope}) name a fix the admin can
     * perform in under a minute — but only if the message says what it is.
     */
    static String describeError(Object rawErrorCode) {
        String errorCode = ((rawErrorCode)?.toString()?.trim())
        switch (errorCode) {
            case "channel_not_found":
                return "That Slack channel no longer exists, or the connected workspace cannot see it. Pick the channel again."
            case "not_in_channel":
                return "Darpan is not in that Slack channel. Invite @Darpan to it, then run again."
            case "is_archived":
            case "channel_is_archived":
                return "That Slack channel is archived. Pick an active channel."
            case "invalid_auth":
            case "token_revoked":
            case "token_expired":
                return "Slack rejected Darpan's access. Reconnect the workspace in Tenant Settings."
            case "account_inactive":
                return "The Slack app was removed from that workspace. Reconnect it in Tenant Settings."
            case "missing_scope":
            case "not_allowed_token_type":
                return "The Slack app is missing a permission it needs. Reconnect the workspace to grant the current scopes."
            case "restricted_action":
            case "restricted_action_read_only_channel":
            case "no_permission":
                return "That Slack channel does not allow this app to post."
            case "rate_limited":
            case "ratelimited":
                return "Slack is rate-limiting Darpan. The next run will try again."
            case "service_unavailable":
            case "internal_error":
            case "fatal_error":
            case "request_timeout":
                return "Slack was temporarily unavailable. The next run will try again."
            case "":
            case null:
                return "Slack rejected the message for an unstated reason."
            default:
                return "Slack rejected the message (${errorCode}).".toString()
        }
    }

    private static Map<String, Object> call(String method, String botToken, Map<String, Object> params) {
        if (transportHook != null) {
            return (transportHook.call(method, botToken, params) ?: [:]) as Map<String, Object>
        }
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        HttpRequest request = HttpRequest.newBuilder(URI.create("${API_BASE}/${method}"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer ${botToken}")
                .POST(HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(params)))
                .build()
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return [
                statusCode: response.statusCode(),
                body      : response.body(),
                // Slack reports granted scopes ONLY in this header — the JSON body never carries
                // them. Missing scopes are the single likeliest reason a token that authenticates
                // still cannot list channels or post, so it is worth surfacing at save time rather
                // than as a missing_scope error days later.
                scopes    : response.headers().firstValue("x-oauth-scopes").orElse(""),
        ]
    }
}
