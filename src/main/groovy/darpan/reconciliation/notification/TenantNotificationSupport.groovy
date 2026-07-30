package darpan.reconciliation.notification

import darpan.common.DarpanEntityConstants
import darpan.facade.common.TenantScopedFinder
import darpan.reconciliation.core.ReconciliationServices
import groovy.json.JsonOutput
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

class TenantNotificationSupport {
    static final String GOOGLE_CHAT_HOST = "chat.googleapis.com"
    static final String APP_BASE_URL_ENV = "DARPAN_APP_BASE_URL"
    static final String APP_BASE_URL_PROPERTY = "darpan.app.baseUrl"
    // Audit H11.2 — DEFAULT_APP_BASE_URL was 'https://hotwax-darpan-dev.web.app', meaning every prod
    // deployment that forgot to set DARPAN_APP_BASE_URL / darpan.app.baseUrl silently routed Google
    // Chat notification deep-links to the DEV environment. There is no safe production default — we
    // now treat 'no resolved base URL' as a hard fail at link-build time (buildRunResultUrl returns
    // null and the caller suppresses the chat link rather than sending the wrong URL).
    static final String DEFAULT_APP_BASE_URL = null

    private static final Logger logger = LoggerFactory.getLogger(TenantNotificationSupport.class)
    private static Closure deliveryHook = null

    static void setDeliveryHook(Closure hook) {
        deliveryHook = hook
    }

    static void resetDeliveryHook() {
        deliveryHook = null
    }

    static String validateGoogleChatWebhookUrl(Object rawWebhookUrl) {
        String webhookUrl = ((rawWebhookUrl)?.toString()?.trim())
        if (!webhookUrl) return null

        URI uri
        try {
            uri = URI.create(webhookUrl)
        } catch (Exception ignored) {
            return "Google Chat webhook URL is invalid."
        }

        if (uri.scheme != "https") return "Google Chat webhook URL must use https."
        if (uri.host != GOOGLE_CHAT_HOST) return "Google Chat webhook URL must use chat.googleapis.com."
        if (!uri.path?.startsWith("/v1/spaces/") || !uri.path?.endsWith("/messages")) {
            return "Google Chat webhook URL must target a Google Chat space messages endpoint."
        }
        String query = uri.rawQuery ?: ""
        if (!query.contains("key=") || !query.contains("token=")) {
            return "Google Chat webhook URL must include key and token query parameters."
        }
        return null
    }

    static String maskGoogleChatWebhookUrl(Object rawWebhookUrl) {
        String webhookUrl = ((rawWebhookUrl)?.toString()?.trim())
        if (!webhookUrl) return null

        try {
            URI uri = URI.create(webhookUrl)
            String path = uri.path ?: ""
            List<String> segments = path.split("/").findAll { it }
            String spaceId = segments.size() >= 3 ? segments[2] : null
            String maskedSpace = spaceId ? (spaceId.length() <= 8 ? "..." : spaceId.substring(0, 4) + "..." + spaceId.substring(spaceId.length() - 4)) : "space"
            return "${uri.scheme}://${uri.host}/v1/spaces/${maskedSpace}/messages?key=...&token=..."
        } catch (Exception ignored) {
            return "configured"
        }
    }

    static Map<String, Object> notifyRunCompleted(def ec, Map<String, Object> runResult) {
        Map<String, Object> context = new LinkedHashMap<String, Object>((runResult ?: [:]) as Map<String, Object>)
        String tenantId = ((context.companyUserGroupId)?.toString()?.trim())
        String resultId = ((context.reconciliationRunResultId)?.toString()?.trim())
        if (!tenantId) return [ok: true, attempted: false, skippedReason: "NO_TENANT"]
        if (!resultId) return [ok: true, attempted: false, skippedReason: "NO_RESULT_ID"]

        // Dedupe + tenant re-check read the trusted run-result row, not caller input.
        def resultRow = TenantScopedFinder.findGlobalUnscoped(ec,
                        DarpanEntityConstants.RECONCILIATION_RUN_RESULT,
                        "notify dedupe read keyed by run-result id — tenant re-checked against the row")
                ?.condition("reconciliationRunResultId", resultId)
                ?.useCache(false)?.one()
        if (resultRow == null) return [ok: true, attempted: false, skippedReason: "RESULT_NOT_FOUND"]
        if (((resultRow.companyUserGroupId)?.toString()?.trim()) != tenantId)
            return [ok: true, attempted: false, skippedReason: "TENANT_MISMATCH"]
        if (resultRow.notifiedDate != null) return [ok: true, attempted: false, skippedReason: "ALREADY_NOTIFIED"]

        List<Map<String, Object>> destinations = resolveDestinationChatSpaces(
                ec, tenantId, ((context.chatSpaceId)?.toString()?.trim()), resultId)
        if (!destinations) {
            return claimNotification(ec, resultId) ?
                    [ok: true, attempted: false, skippedReason: "NO_DESTINATIONS"] :
                    [ok: true, attempted: false, skippedReason: "ALREADY_NOTIFIED"]
        }
        // Atomic claim-then-deliver: the conditional update below (WHERE notifiedDate IS NULL) is the
        // race guard — two concurrent calls for the same run-result can both pass the read-only
        // ALREADY_NOTIFIED check above, but only one can win this claim. The loser reports
        // ALREADY_NOTIFIED without delivering. A failed delivery still consumes the claim (no
        // automatic retries) — same semantics as the prior post-delivery stamp.
        if (!claimNotification(ec, resultId)) return [ok: true, attempted: false, skippedReason: "ALREADY_NOTIFIED"]

        Map<String, Object> payload = ((ec.service.sync()
                .name("reconciliation.ReconciliationNotificationServices.build#RunCompletedPayload")
                .parameters(context)
                .disableAuthz()
                .call()?.payload) ?: [:]) as Map<String, Object>

        int deliveredCount = 0
        int failedCount = 0
        for (Map<String, Object> destination : destinations) {
            try {
                Map<String, Object> delivery = deliverGoogleChat((String) destination.webhookUrl, payload)
                if (delivery.ok == true) { deliveredCount++ } else {
                    failedCount++
                    logger.warn("Google Chat run notification returned status {} for tenant {} result {} space {}",
                            delivery.statusCode, tenantId, resultId, destination.spaceName)
                }
            } catch (Throwable t) {
                failedCount++
                logger.warn("Google Chat run notification failed for tenant {} result {} space {}: {}",
                        tenantId, resultId, destination.spaceName, t.message)
            }
        }
        return [ok: failedCount == 0, attempted: true, deliveredCount: deliveredCount, failedCount: failedCount]
    }

    static List<Map<String, Object>> resolveDestinationChatSpaces(def ec, String tenantId,
                                                                  String automationChatSpaceId, String resultId) {
        Set<String> chatSpaceIds = new LinkedHashSet<String>()
        if (automationChatSpaceId) chatSpaceIds.add(automationChatSpaceId)
        def subscriptionRows = TenantScopedFinder.findGlobalUnscoped(ec,
                        DarpanEntityConstants.RUN_NOTIFY_SUBSCRIPTION,
                        "subscription rows keyed by run-result id — space rows re-pinned to run tenant below")
                ?.condition("reconciliationRunResultId", resultId)
                ?.useCache(false)?.list() ?: []
        for (def subscriptionRow : subscriptionRows) {
            String chatSpaceId = ((subscriptionRow.chatSpaceId)?.toString()?.trim())
            if (chatSpaceId) chatSpaceIds.add(chatSpaceId)
        }
        List<Map<String, Object>> destinations = []
        for (String chatSpaceId : chatSpaceIds) {
            def spaceRow = TenantScopedFinder.findGlobalUnscoped(ec,
                            DarpanEntityConstants.TENANT_CHAT_SPACE,
                            "chat-space read pinned to run-result tenantId — explicit companyUserGroupId condition always applied")
                    ?.condition("chatSpaceId", chatSpaceId)
                    ?.condition("companyUserGroupId", tenantId)
                    ?.useCache(false)?.one()
            String webhookUrl = ((spaceRow?.googleChatWebhookUrl)?.toString()?.trim())
            if (spaceRow != null && ((spaceRow.isActive)?.toString()?.trim()) != "N" && webhookUrl) {
                destinations.add([chatSpaceId: chatSpaceId, spaceName: spaceRow.spaceName, webhookUrl: webhookUrl])
            }
        }
        return destinations
    }

    private static boolean claimNotification(def ec, String resultId) {
        try {
            long updated = TenantScopedFinder.findGlobalUnscoped(ec,
                            DarpanEntityConstants.RECONCILIATION_RUN_RESULT,
                            "atomic notify claim — conditional update keyed by run-result id where notifiedDate is null")
                    ?.condition("reconciliationRunResultId", resultId)
                    ?.condition("notifiedDate", null)
                    ?.updateAll([notifiedDate: ec.user.nowTimestamp]) ?: 0
            return updated > 0
        } catch (Throwable t) {
            logger.warn("Failed to claim notification for run result {}: {}", resultId, t.message)
            return false
        }
    }

    static String buildRunResultUrl(def ec, Map<String, Object> context) {
        String appBaseUrl = resolveAppBaseUrl(ec)
        String savedRunId = ((context.savedRunId)?.toString()?.trim()) ?:
                ((context.reconciliationMappingId)?.toString()?.trim()) ?:
                ((context.ruleSetId)?.toString()?.trim())
        String outputFileName = ((context.resultDataManagerPath)?.toString()?.trim())
        if (!appBaseUrl || !savedRunId || !outputFileName) return null

        String encodedSavedRunId = URLEncoder.encode(savedRunId, StandardCharsets.UTF_8.name()).replace("+", "%20")
        String encodedOutputFileName = URLEncoder.encode(outputFileName, StandardCharsets.UTF_8.name()).replace("+", "%20")
        String path = "/reconciliation/run-result/${encodedSavedRunId}/${encodedOutputFileName}"
        Map<String, String> queryParams = [
                runName         : ((context.runName)?.toString()?.trim()),
                file1SystemLabel: resolveFileSystemLabel(ec, context, "file1", null),
                file2SystemLabel: resolveFileSystemLabel(ec, context, "file2", null),
        ].findAll { entry -> ((entry.value)?.toString()?.trim()) } as Map<String, String>

        String queryText = queryParams.collect { entry ->
            "${URLEncoder.encode(entry.key, StandardCharsets.UTF_8.name())}=${URLEncoder.encode(entry.value, StandardCharsets.UTF_8.name())}"
        }.join("&")
        return "${appBaseUrl}${path}${queryText ? "?" + queryText : ""}".toString()
    }

    static String resolveFileSystemLabel(def ec, Map<String, Object> context, String prefix, String fallback) {
        String explicitLabel = ((context["${prefix}SystemLabel"])?.toString()?.trim()) ?:
                ((context["${prefix}Label"])?.toString()?.trim())
        if (explicitLabel) return explicitLabel

        String systemEnumId = ((context["${prefix}SystemEnumId"])?.toString()?.trim())
        if (!systemEnumId) return fallback

        try {
            return ReconciliationServices.resolveEnumLabel(ec, systemEnumId, fallback ?: systemEnumId)
        } catch (Throwable ignored) {
            return systemEnumId
        }
    }

    protected static String resolveAppBaseUrl(def ec) {
        // Resolution order: env var, Moqui resource property, Java system property (deploy/test override),
        // then the first non-* webapp_allow_origin. DEFAULT_APP_BASE_URL is now null (audit H11.2 — used
        // to silently route prod chat links to the dev firebase host when nothing was configured); when
        // every source is empty buildRunResultUrl returns null and the link is suppressed.
        String rawBaseUrl = System.getenv(APP_BASE_URL_ENV)?.toString()?.trim() ?:
                ec?.resource?.properties?.get(APP_BASE_URL_PROPERTY)?.toString()?.trim() ?:
                System.getProperty(APP_BASE_URL_PROPERTY)?.toString()?.trim() ?:
                resolveFirstAllowedOrigin(ec) ?:
                DEFAULT_APP_BASE_URL
        return normalizeAppBaseUrl(rawBaseUrl)
    }

    protected static String resolveFirstAllowedOrigin(def ec) {
        String rawOrigins = ec?.resource?.properties?.get("webapp_allow_origins")?.toString()?.trim()
        if (!rawOrigins || rawOrigins == "*") return null
        return rawOrigins.split(",").collect { it.trim() }.find { it && it != "*" }
    }

    protected static String normalizeAppBaseUrl(Object rawBaseUrl) {
        String value = ((rawBaseUrl)?.toString()?.trim())
        if (!value) return null
        if (!value.startsWith("http://") && !value.startsWith("https://")) value = "https://${value}".toString()
        try {
            URI uri = URI.create(value)
            if (!uri.scheme || !uri.host) return null
        } catch (Exception ignored) {
            return null
        }
        return value.replaceAll(/\/+$/, "")
    }

    protected static Map<String, Object> deliverGoogleChat(String webhookUrl, Map<String, Object> payload) {
        if (deliveryHook != null) return (deliveryHook.call(webhookUrl, payload) ?: [:]) as Map<String, Object>

        String body = JsonOutput.toJson(payload)
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()
        HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString())
        int statusCode = response.statusCode()
        return [
                ok        : statusCode >= 200 && statusCode < 300,
                statusCode: statusCode,
        ]
    }
}
