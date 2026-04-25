package darpan.facade.reconciliation

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.util.Arrays

class ReconciliationDashboardPreferenceSupport {
    static final String PINNED_MAPPING_PREFERENCE_KEY = "darpan.dashboard.pinnedMappingIds"
    static final String PINNED_SAVED_RUN_PREFERENCE_KEY = PINNED_MAPPING_PREFERENCE_KEY

    static List<String> parsePinnedReconciliationMappingIds(Object rawValue) {
        String normalized = rawValue?.toString()?.trim()
        if (!normalized) return []

        try {
            return normalizePinnedReconciliationMappingIds(new JsonSlurper().parseText(normalized))
        } catch (Exception ignored) {
            return []
        }
    }

    static List<String> normalizePinnedReconciliationMappingIds(Object rawValue) {
        Collection values
        if (rawValue == null) {
            values = []
        } else if (rawValue instanceof Collection) {
            values = rawValue as Collection
        } else if (rawValue.getClass().isArray()) {
            values = Arrays.asList((Object[]) rawValue)
        } else {
            values = [rawValue]
        }

        return values.collect { it?.toString()?.trim() }
                .findAll { it }
                .unique()
    }

    static List<String> filterPinnedReconciliationMappingIds(Collection<String> mappingIds, Collection<String> validMappingIds) {
        if (!mappingIds) return []
        if (!validMappingIds) return normalizePinnedReconciliationMappingIds(mappingIds)

        Set<String> validIdSet = normalizePinnedReconciliationMappingIds(validMappingIds) as Set<String>
        return normalizePinnedReconciliationMappingIds(mappingIds).findAll { validIdSet.contains(it) }
    }

    static List<String> listPinnedReconciliationMappingIds(def ec, Collection<String> validMappingIds = null) {
        return filterPinnedReconciliationMappingIds(
                parsePinnedReconciliationMappingIds(ec?.user?.getPreference(PINNED_MAPPING_PREFERENCE_KEY)),
                validMappingIds
        )
    }

    static List<String> savePinnedReconciliationMappingIds(def ec, Object requestedMappingIds, Collection<String> validMappingIds = null) {
        List<String> pinnedMappingIds = filterPinnedReconciliationMappingIds(
                normalizePinnedReconciliationMappingIds(requestedMappingIds),
                validMappingIds
        )
        ec.user.setPreference(PINNED_MAPPING_PREFERENCE_KEY, JsonOutput.toJson(pinnedMappingIds))
        return pinnedMappingIds
    }

    static List<String> parsePinnedSavedRunIds(Object rawValue) {
        return parsePinnedReconciliationMappingIds(rawValue)
    }

    static List<String> normalizePinnedSavedRunIds(Object rawValue) {
        return normalizePinnedReconciliationMappingIds(rawValue)
    }

    static List<String> filterPinnedSavedRunIds(Collection<String> savedRunIds, Collection<String> validSavedRunIds) {
        return filterPinnedReconciliationMappingIds(savedRunIds, validSavedRunIds)
    }

    static List<String> listPinnedSavedRunIds(def ec, Collection<String> validSavedRunIds = null) {
        return filterPinnedSavedRunIds(
                parsePinnedSavedRunIds(ec?.user?.getPreference(PINNED_SAVED_RUN_PREFERENCE_KEY)),
                validSavedRunIds
        )
    }

    static List<String> savePinnedSavedRunIds(def ec, Object requestedSavedRunIds, Collection<String> validSavedRunIds = null) {
        List<String> pinnedSavedRunIds = filterPinnedSavedRunIds(
                normalizePinnedSavedRunIds(requestedSavedRunIds),
                validSavedRunIds
        )
        ec.user.setPreference(PINNED_SAVED_RUN_PREFERENCE_KEY, JsonOutput.toJson(pinnedSavedRunIds))
        return pinnedSavedRunIds
    }
}
