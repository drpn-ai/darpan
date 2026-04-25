package darpan.facade.settings

import darpan.facade.common.FacadeSupport
import groovy.json.JsonSlurper

class SettingsFacadeSupport {
    static final String DARPAN_SYSTEM_SOURCE_ENUM_TYPE_ID = "DarpanSystemSource"

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

    static List<Map<String, Object>> deduplicateEnumOptions(String enumTypeId, List<Map<String, Object>> rawOptions) {
        List<Map<String, Object>> optionList = (rawOptions ?: []) as List<Map<String, Object>>
        if (FacadeSupport.normalize(enumTypeId) != DARPAN_SYSTEM_SOURCE_ENUM_TYPE_ID) return optionList

        LinkedHashMap<String, Map<String, Object>> preferredByKey = [:]
        optionList.each { Map<String, Object> option ->
            String dedupeKey = buildEnumOptionDedupeKey(option)
            if (!dedupeKey) return

            Map<String, Object> currentPreferred = preferredByKey[dedupeKey]
            if (currentPreferred == null || shouldPreferEnumOption(option, currentPreferred)) {
                preferredByKey[dedupeKey] = option
            }
        }

        return preferredByKey.values() as List<Map<String, Object>>
    }

    protected static String normalizeId(String rawValue) {
        return rawValue?.toLowerCase()?.replaceAll(/[^a-z0-9_-]+/, "_")?.replaceAll(/^_+|_+$/, "")
    }

    protected static String trimToLength(String value, int maxLength) {
        if (!value) return value
        return value.length() > maxLength ? value.substring(0, maxLength) : value
    }

    protected static String buildEnumOptionDedupeKey(Map<String, Object> option) {
        return FacadeSupport.normalize(option?.enumCode) ?:
                FacadeSupport.normalize(option?.label) ?:
                FacadeSupport.normalize(option?.enumId)
    }

    protected static boolean shouldPreferEnumOption(Map<String, Object> candidate, Map<String, Object> currentPreferred) {
        boolean candidateCanonical = isCanonicalEnumOption(candidate)
        boolean currentCanonical = isCanonicalEnumOption(currentPreferred)
        if (candidateCanonical != currentCanonical) return candidateCanonical

        Integer candidateSequence = normalizeSequenceNumber(candidate?.sequenceNum)
        Integer currentSequence = normalizeSequenceNumber(currentPreferred?.sequenceNum)
        if (candidateSequence != currentSequence) return candidateSequence < currentSequence

        String candidateId = FacadeSupport.normalize(candidate?.enumId) ?: ""
        String currentId = FacadeSupport.normalize(currentPreferred?.enumId) ?: ""
        return candidateId < currentId
    }

    protected static boolean isCanonicalEnumOption(Map<String, Object> option) {
        String enumId = FacadeSupport.normalize(option?.enumId)
        String enumCode = FacadeSupport.normalize(option?.enumCode)
        return enumId != null && enumCode != null && enumId == enumCode
    }

    protected static Integer normalizeSequenceNumber(Object rawValue) {
        if (rawValue instanceof Number) return rawValue.intValue()
        Integer parsed = FacadeSupport.normalizeInt(rawValue, Integer.MAX_VALUE)
        return parsed != null ? parsed : Integer.MAX_VALUE
    }
}
