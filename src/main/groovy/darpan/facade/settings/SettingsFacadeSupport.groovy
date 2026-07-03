package darpan.facade.settings

import darpan.facade.common.FacadeSupport
import groovy.json.JsonSlurper

import static darpan.common.ValueSupport.normalize

class SettingsFacadeSupport {
    static final String DARPAN_SYSTEM_SOURCE_ENUM_TYPE_ID = "DarpanSystemSource"
    private static final String HOTWAX_SYSTEM_DEDUPE_KEY = "HOTWAX"
    private static final String HOTWAX_CANONICAL_ENUM_ID = "OMS"

    static String validateJsonObjectText(String text, String label) {
        String normalized = normalize(text)
        if (!normalized) return null

        try {
            def parsed = new JsonSlurper().parseText(normalized)
            if (!(parsed instanceof Map)) return "${label} must be a JSON object."
            return null
        } catch (Exception e) {
            return "${label} is invalid: ${e.message}"
        }
    }

    static List<Map<String, Object>> deduplicateEnumOptions(String enumTypeId, List<Map<String, Object>> rawOptions) {
        List<Map<String, Object>> optionList = (rawOptions ?: []) as List<Map<String, Object>>
        if (normalize(enumTypeId) != DARPAN_SYSTEM_SOURCE_ENUM_TYPE_ID) return optionList

        LinkedHashMap<String, Map<String, Object>> preferredByKey = [:]
        optionList.each { Map<String, Object> option ->
            Map<String, Object> normalizedOption = normalizeSystemSourceOption(option)
            String dedupeKey = buildEnumOptionDedupeKey(normalizedOption)
            if (!dedupeKey) return

            Map<String, Object> currentPreferred = preferredByKey[dedupeKey]
            if (currentPreferred == null || shouldPreferEnumOption(normalizedOption, currentPreferred)) {
                preferredByKey[dedupeKey] = normalizedOption
            }
        }

        return preferredByKey.values() as List<Map<String, Object>>
    }

    protected static Map<String, Object> normalizeSystemSourceOption(Map<String, Object> option) {
        if (!isHotWaxSystemOption(option)) return option

        Map<String, Object> normalized = new LinkedHashMap<>(option ?: [:])
        normalized.enumCode = "HOTWAX"
        normalized.description = "HotWax"
        normalized.label = "HotWax"
        return normalized
    }

    protected static String buildEnumOptionDedupeKey(Map<String, Object> option) {
        String logicalSystemKey = logicalSystemDedupeKey(option)
        if (logicalSystemKey) return logicalSystemKey

        return normalize(option?.enumCode) ?:
                normalize(option?.label) ?:
                normalize(option?.enumId)
    }

    protected static boolean shouldPreferEnumOption(Map<String, Object> candidate, Map<String, Object> currentPreferred) {
        boolean candidateCanonical = isCanonicalEnumOption(candidate)
        boolean currentCanonical = isCanonicalEnumOption(currentPreferred)
        if (candidateCanonical != currentCanonical) return candidateCanonical

        Integer candidateSequence = normalizeSequenceNumber(candidate?.sequenceNum)
        Integer currentSequence = normalizeSequenceNumber(currentPreferred?.sequenceNum)
        if (candidateSequence != currentSequence) return candidateSequence < currentSequence

        String candidateId = normalize(candidate?.enumId) ?: ""
        String currentId = normalize(currentPreferred?.enumId) ?: ""
        return candidateId < currentId
    }

    protected static boolean isCanonicalEnumOption(Map<String, Object> option) {
        String enumId = normalize(option?.enumId)
        String enumCode = normalize(option?.enumCode)
        String logicalSystemKey = logicalSystemDedupeKey(option)
        if (logicalSystemKey == HOTWAX_SYSTEM_DEDUPE_KEY) return enumId == HOTWAX_CANONICAL_ENUM_ID

        return enumId != null && enumCode != null && enumId == enumCode
    }

    protected static String logicalSystemDedupeKey(Map<String, Object> option) {
        return isHotWaxSystemOption(option) ? HOTWAX_SYSTEM_DEDUPE_KEY : null
    }

    protected static boolean isHotWaxSystemOption(Map<String, Object> option) {
        List<String> values = [
                normalize(option?.enumId),
                normalize(option?.enumCode),
                normalize(option?.label),
                normalize(option?.description),
        ].findAll { it } as List<String>

        return values.any { String value ->
            String normalized = value.toUpperCase(Locale.ROOT)
            normalized == "OMS" || normalized == "HOTWAX"
        }
    }

    protected static Integer normalizeSequenceNumber(Object rawValue) {
        if (rawValue instanceof Number) return rawValue.intValue()
        String normalized = rawValue?.toString()?.trim()
        return normalized?.isInteger() ? normalized.toInteger() : Integer.MAX_VALUE
    }
}
