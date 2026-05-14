package darpan.reconciliation.notification

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
    static final String SETTINGS_ENTITY_NAME = "darpan.reconciliation.TenantNotificationSetting"
    static final String GOOGLE_CHAT_HOST = "chat.googleapis.com"
    static final String APP_BASE_URL_ENV = "DARPAN_APP_BASE_URL"
    static final String APP_BASE_URL_PROPERTY = "darpan.app.baseUrl"
    static final String DEFAULT_APP_BASE_URL = "https://hotwax-darpan-dev.web.app"

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

        def settings = ec?.entity?.find(SETTINGS_ENTITY_NAME)
                ?.condition("companyUserGroupId", tenantId)
                ?.disableAuthz()
                ?.useCache(false)
                ?.one()
        String webhookUrl = ((settings?.googleChatWebhookUrl)?.toString()?.trim())
        boolean active = ((settings?.isActive)?.toString()?.trim()) != "N"
        if (!settings || !active || !webhookUrl) {
            return [ok: true, attempted: false, skippedReason: "NOT_CONFIGURED"]
        }

        Map<String, Object> payload = ((ec.service.sync()
                .name("reconciliation.ReconciliationNotificationServices.build#RunCompletedPayload")
                .parameters(context)
                .disableAuthz()
                .call()?.payload) ?: [:]) as Map<String, Object>
        try {
            Map<String, Object> delivery = deliverGoogleChat(webhookUrl, payload)
            boolean ok = delivery.ok == true
            if (!ok) {
                logger.warn("Google Chat run notification returned status {} for tenant {} result {}",
                        delivery.statusCode, tenantId, resultId ?: "unknown")
            }
            return [
                    ok              : ok,
                    attempted       : true,
                    statusCode      : delivery.statusCode,
                    webhookUrlMasked: maskGoogleChatWebhookUrl(webhookUrl),
            ]
        } catch (Throwable t) {
            logger.warn("Google Chat run notification failed for tenant {} result {}: {}",
                    tenantId, resultId ?: "unknown", t.message)
            return [
                    ok              : false,
                    attempted       : true,
                    errorMessage    : t.message,
                    webhookUrlMasked: maskGoogleChatWebhookUrl(webhookUrl),
            ]
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
        String rawBaseUrl = System.getenv(APP_BASE_URL_ENV)?.toString()?.trim() ?:
                ec?.resource?.properties?.get(APP_BASE_URL_PROPERTY)?.toString()?.trim() ?:
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
