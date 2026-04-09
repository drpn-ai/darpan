package darpan.facade.reconciliation

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals

class PilotDashboardPreferenceSupportTests {

    @Test
    void parsesPinnedMappingIdsFromStoredJson() {
        assertEquals(
                ["Map8", "Map1"],
                PilotDashboardPreferenceSupport.parsePinnedReconciliationMappingIds('["Map8","Map1","Map8"," "]')
        )
    }

    @Test
    void returnsEmptyListForInvalidStoredJson() {
        assertEquals([], PilotDashboardPreferenceSupport.parsePinnedReconciliationMappingIds("not-json"))
    }

    @Test
    void normalizesIncomingPinnedMappingIds() {
        assertEquals(
                ["Map8", "Map1"],
                PilotDashboardPreferenceSupport.normalizePinnedReconciliationMappingIds([" Map8 ", "", "Map1", "Map8", null])
        )
    }

    @Test
    void filtersPinnedMappingIdsToKnownMappings() {
        assertEquals(
                ["Map8", "Map1"],
                PilotDashboardPreferenceSupport.filterPinnedReconciliationMappingIds(["Map8", "Unknown", "Map1"], ["Map8", "Map2", "Map1"] as Set<String>)
        )
    }
}
