package darpan.facade.settings

import darpan.facade.common.FacadeSupport
import groovy.json.JsonSlurper

class SettingsFacadeSupport {
    static String validateJsonObjectText(String text, String label) {
        String normalized = FacadeSupport.normalize(text)
        if (!normalized) return null

        try {
            def parsed = new JsonSlurper().parseText(normalized)
            if (!(parsed instanceof Map)) return "${label} must be a JSON object."
            return null
        } catch (Exception e) {
            return "${label} is invalid: ${e.message}"
        }
    }

    static Map<String, Object> resolveHcReadDbConfigId(def ec, String requestedId, String displayName, String host, String databaseName) {
        String normalizedRequestedId = FacadeSupport.normalize(requestedId)
        if (normalizedRequestedId) {
            String normalized = normalizeId(normalizedRequestedId)
            if (!normalized) return [configId: normalizedRequestedId, error: "Config ID must contain letters or numbers."]
            return [configId: trimToLength(normalized, 40), error: null]
        }

        String seed = FacadeSupport.normalize(displayName) ?: "${FacadeSupport.normalize(host) ?: 'hc'}_${FacadeSupport.normalize(databaseName) ?: 'db'}"
        String baseId = normalizeId(seed) ?: "hc_db"
        baseId = trimToLength(baseId, 40)

        String candidateId = baseId
        int suffix = 1
        while (ec?.entity?.find("darpan.reconciliation.HcReadDbConfig")
                ?.condition("hcReadDbConfigId", candidateId)
                ?.useCache(false)
                ?.one()) {
            suffix++
            String suffixPart = "_${suffix}"
            int maxBaseLength = Math.max(1, 40 - suffixPart.length())
            String trimmedBase = baseId.length() > maxBaseLength ? baseId.substring(0, maxBaseLength) : baseId
            candidateId = trimmedBase + suffixPart
        }

        return [configId: trimToLength(candidateId, 40), error: null]
    }

    static String normalizeAdditionalParameters(String additionalParameters) {
        String normalized = FacadeSupport.normalize(additionalParameters)
        if (normalized?.startsWith("?")) normalized = normalized.substring(1)
        return normalized
    }

    static String buildMysqlJdbcUrl(String host, Integer port, String databaseName, String additionalParameters) {
        String jdbcUrl = "jdbc:mysql://${host}:${port}/${databaseName}"
        if (additionalParameters) jdbcUrl += "?${additionalParameters}"
        return jdbcUrl
    }

    protected static String normalizeId(String rawValue) {
        return rawValue?.toLowerCase()?.replaceAll(/[^a-z0-9_-]+/, "_")?.replaceAll(/^_+|_+$/, "")
    }

    protected static String trimToLength(String value, int maxLength) {
        if (!value) return value
        return value.length() > maxLength ? value.substring(0, maxLength) : value
    }
}
