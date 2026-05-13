package darpan.facade.reconciliation

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.Arrays

import static darpan.common.ValueSupport.normalize

class ReconciliationDashboardPreferenceSupport {
    protected static final Logger logger = LoggerFactory.getLogger(ReconciliationDashboardPreferenceSupport.class)

    static final String PINNED_MAPPING_PREFERENCE_KEY = "darpan.dashboard.pinnedMappingIds"

    static List<String> parsePinnedReconciliationMappingIds(Object rawValue) {
        String normalized = normalize(rawValue)
        if (!normalized) return []

        try {
            return normalizePinnedReconciliationMappingIds(new JsonSlurper().parseText(normalized))
        } catch (Exception e) {
            logger.warn("Failed to parse pinned mapping preference JSON", e)
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

        return values.collect { normalize(it) }
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
}
