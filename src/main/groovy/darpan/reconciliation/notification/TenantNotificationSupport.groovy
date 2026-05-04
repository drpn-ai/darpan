package darpan.reconciliation.notification

import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
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

    private static final Logger logger = LoggerFactory.getLogger(TenantNotificationSupport)
    private static Closure deliveryHook = null

    static void setDeliveryHook(Closure hook) {
        deliveryHook = hook
    }

    static void resetDeliveryHook() {
        deliveryHook = null
    }

    static Map<String, Object> readSettings(def ec) {
        String tenantId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (!tenantId) return emptySettings(null)

        def setting = findSettingsForTenant(ec, tenantId)
        return buildSettingsResponse(ec, setting, tenantId)
    }

    static Map<String, Object> saveSettings(def ec, Object rawWebhookUrl, Object rawIsActive) {
        String tenantId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (!tenantId) {
            ec.message.addError("Active tenant is required for notification settings.")
            return emptySettings(null)
        }

        TenantAccessSupport.requireActiveTenantWriteAccess(ec, "Your active tenant only has view access for notification settings.")
        if (ec.message.hasError()) return buildSettingsResponse(ec, findSettingsForTenant(ec, tenantId), tenantId)

        def existing = findSettingsForTenant(ec, tenantId)
        String webhookUrl = FacadeSupport.normalize(rawWebhookUrl)
        String existingWebhookUrl = FacadeSupport.normalize(existing?.googleChatWebhookUrl)
        String webhookUrlToSave = webhookUrl ?: existingWebhookUrl
        boolean isActive = FacadeSupport.normalizeBool(rawIsActive, true)
        if (isActive && !webhookUrlToSave) ec.message.addError("Google Chat webhook URL is required when notifications are enabled.")

        String validationError = validateGoogleChatWebhookUrl(webhookUrlToSave)
        if (validationError) ec.message.addError(validationError)
        if (ec.message.hasError()) return buildSettingsResponse(ec, existing, tenantId)

        def nowTs = ec.user.nowTimestamp
        Map<String, Object> settingMap = [
                companyUserGroupId   : tenantId,
                createdByUserId      : FacadeSupport.normalize(existing?.createdByUserId) ?: TenantAccessSupport.currentUserId(ec),
                googleChatWebhookUrl : webhookUrlToSave,
                isActive             : isActive ? "Y" : "N",
                createdDate          : existing?.createdDate ?: nowTs,
                lastUpdatedDate      : nowTs,
        ]

        ec.service.sync()
                .name("store#${SETTINGS_ENTITY_NAME}".toString())
                .parameters(settingMap)
                .call()

        if (!ec.message.hasError()) ec.message.addMessage("Saved notification settings.")
        return buildSettingsResponse(ec, findSettingsForTenant(ec, tenantId), tenantId)
    }

    static String validateGoogleChatWebhookUrl(Object rawWebhookUrl) {
        String webhookUrl = FacadeSupport.normalize(rawWebhookUrl)
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
        String webhookUrl = FacadeSupport.normalize(rawWebhookUrl)
        if (!webhookUrl) return null

        try {
            URI uri = URI.create(webhookUrl)
            String path = uri.path ?: ""
            List<String> segments = path.split("/").findAll { it }
            String spaceId = segments.size() >= 3 ? segments[2] : null
            String maskedSpace = spaceId ? maskToken(spaceId) : "space"
            return "${uri.scheme}://${uri.host}/v1/spaces/${maskedSpace}/messages?key=...&token=..."
        } catch (Exception ignored) {
            return "configured"
        }
    }

    static Map<String, Object> notifyRunCompleted(def ec, Map<String, Object> runResult) {
        Map<String, Object> context = buildRunResultContext(ec, runResult ?: [:])
        String tenantId = FacadeSupport.normalize(context.companyUserGroupId)
        String resultId = FacadeSupport.normalize(context.reconciliationRunResultId)
        if (!tenantId) return [ok: true, attempted: false, skippedReason: "NO_TENANT"]

        def settings = findSettingsForTenant(ec, tenantId)
        String webhookUrl = FacadeSupport.normalize(settings?.googleChatWebhookUrl)
        boolean active = FacadeSupport.normalize(settings?.isActive) != "N"
        if (!settings || !active || !webhookUrl) {
            return [ok: true, attempted: false, skippedReason: "NOT_CONFIGURED"]
        }

        Map<String, Object> payload = buildRunCompletedPayload(ec, context)
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

    static Map<String, Object> buildRunCompletedPayload(def ec, Map<String, Object> context) {
        String tenantLabel = FacadeSupport.normalize(context.companyLabel) ?:
                TenantAccessSupport.resolveTenantLabelForUserGroupId(ec, context.companyUserGroupId)
        String runName = FacadeSupport.normalize(context.runName) ?:
                FacadeSupport.normalize(context.savedRunId) ?:
                FacadeSupport.normalize(context.reconciliationRunId) ?:
                "reconciliation run"
        String resultId = FacadeSupport.normalize(context.reconciliationRunResultId)
        String resultUrl = buildRunResultUrl(ec, context)
        String file1SystemLabel = resolveFileSystemLabel(ec, context, "file1", null)
        String file2SystemLabel = resolveFileSystemLabel(ec, context, "file2", null)
        List<String> lines = ["Darpan run completed: ${runName}".toString()]
        if (tenantLabel) lines << "Tenant: ${tenantLabel}".toString()
        if (resultId) lines << "Result ID: ${resultId}".toString()
        if (resultUrl) lines << "Run result: <${resultUrl}|Open run result>".toString()
        lines << "Differences: ${toDisplayCount(context.differenceCount)}".toString()
        lines << "Only in ${file1SystemLabel ?: "File 1"}: ${toDisplayCount(context.onlyInFile1Count)}".toString()
        lines << "Only in ${file2SystemLabel ?: "File 2"}: ${toDisplayCount(context.onlyInFile2Count)}".toString()
        return [text: lines.join("\n")]
    }

    protected static String buildRunResultUrl(def ec, Map<String, Object> context) {
        String appBaseUrl = resolveAppBaseUrl(ec)
        String savedRunId = FacadeSupport.normalize(context.savedRunId) ?:
                FacadeSupport.normalize(context.reconciliationMappingId) ?:
                FacadeSupport.normalize(context.ruleSetId)
        String outputFileName = FacadeSupport.normalize(context.resultDataManagerPath)
        if (!appBaseUrl || !savedRunId || !outputFileName) return null

        String path = "/reconciliation/run-result/${encodePathSegment(savedRunId)}/${encodePathSegment(outputFileName)}"
        Map<String, String> queryParams = [
                runName         : FacadeSupport.normalize(context.runName),
                file1SystemLabel: resolveFileSystemLabel(ec, context, "file1", null),
                file2SystemLabel: resolveFileSystemLabel(ec, context, "file2", null),
        ].findAll { entry -> FacadeSupport.normalize(entry.value) } as Map<String, String>

        String queryText = queryParams.collect { entry ->
            "${encodeQueryComponent(entry.key)}=${encodeQueryComponent(entry.value)}"
        }.join("&")
        return "${appBaseUrl}${path}${queryText ? "?" + queryText : ""}".toString()
    }

    protected static String resolveFileSystemLabel(def ec, Map<String, Object> context, String prefix, String fallback) {
        String explicitLabel = FacadeSupport.normalize(context["${prefix}SystemLabel"]) ?:
                FacadeSupport.normalize(context["${prefix}Label"])
        if (explicitLabel) return explicitLabel

        String systemEnumId = FacadeSupport.normalize(context["${prefix}SystemEnumId"])
        if (!systemEnumId) return fallback

        try {
            return ReconciliationServices.resolveEnumLabel(ec, systemEnumId, fallback ?: systemEnumId)
        } catch (Throwable ignored) {
            return systemEnumId
        }
    }

    protected static String resolveAppBaseUrl(def ec) {
        String rawBaseUrl = FacadeSupport.normalize(System.getenv(APP_BASE_URL_ENV)) ?:
                FacadeSupport.normalize(ec?.resource?.properties?.get(APP_BASE_URL_PROPERTY)) ?:
                resolveFirstAllowedOrigin(ec) ?:
                DEFAULT_APP_BASE_URL
        return normalizeAppBaseUrl(rawBaseUrl)
    }

    protected static String resolveFirstAllowedOrigin(def ec) {
        String rawOrigins = FacadeSupport.normalize(ec?.resource?.properties?.get("webapp_allow_origins"))
        if (!rawOrigins || rawOrigins == "*") return null
        return rawOrigins.split(",").collect { it.trim() }.find { it && it != "*" }
    }

    protected static String normalizeAppBaseUrl(Object rawBaseUrl) {
        String value = FacadeSupport.normalize(rawBaseUrl)
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

    protected static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }

    protected static String encodeQueryComponent(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
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

    protected static Map<String, Object> buildRunResultContext(def ec, Map<String, Object> input) {
        Map<String, Object> context = new LinkedHashMap<String, Object>(input)
        String resultId = FacadeSupport.normalize(context.reconciliationRunResultId)
        def runResultRecord = resultId ? ec?.entity?.find("darpan.reconciliation.ReconciliationRunResult")
                ?.condition("reconciliationRunResultId", resultId)
                ?.disableAuthz()
                ?.useCache(false)
                ?.one() : null
        if (runResultRecord) {
            [
                    "savedRunId",
                    "savedRunType",
                    "reconciliationRunId",
                    "reconciliationMappingId",
                    "ruleSetId",
                    "compareScopeId",
                    "companyUserGroupId",
                    "createdByUserId",
                    "file1Name",
                    "file2Name",
                    "resultDataManagerPath",
                    "differenceCount",
                    "onlyInFile1Count",
                    "onlyInFile2Count",
            ].each { String fieldName ->
                if (context[fieldName] == null) context[fieldName] = runResultRecord[fieldName]
            }
        }
        if (!context.companyUserGroupId) {
            context.companyUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        }
        return context
    }

    protected static def findSettingsForTenant(def ec, String tenantId) {
        if (!tenantId) return null
        return ec?.entity?.find(SETTINGS_ENTITY_NAME)
                ?.condition("companyUserGroupId", tenantId)
                ?.disableAuthz()
                ?.useCache(false)
                ?.one()
    }

    protected static Map<String, Object> buildSettingsResponse(def ec, def setting, String tenantId) {
        String webhookUrl = FacadeSupport.normalize(setting?.googleChatWebhookUrl)
        return [
                companyUserGroupId        : tenantId,
                companyLabel              : TenantAccessSupport.resolveTenantLabelForUserGroupId(ec, tenantId),
                googleChatConfigured      : !!webhookUrl,
                googleChatWebhookUrlMasked: maskGoogleChatWebhookUrl(webhookUrl),
                isActive                  : FacadeSupport.normalize(setting?.isActive) ?: "N",
                createdByUserId           : FacadeSupport.normalize(setting?.createdByUserId),
                createdDate               : setting?.createdDate,
                lastUpdatedDate           : setting?.lastUpdatedDate,
        ]
    }

    protected static Map<String, Object> emptySettings(String tenantId) {
        return [
                companyUserGroupId        : tenantId,
                companyLabel              : null,
                googleChatConfigured      : false,
                googleChatWebhookUrlMasked: null,
                isActive                  : "N",
        ]
    }

    protected static String maskToken(String value) {
        if (!value) return null
        if (value.length() <= 8) return "..."
        return value.substring(0, 4) + "..." + value.substring(value.length() - 4)
    }

    protected static String toDisplayCount(Object value) {
        if (value == null) return "0"
        if (value instanceof Number) return ((Number) value).intValue().toString()
        String normalized = FacadeSupport.normalize(value)
        return normalized ?: "0"
    }
}
