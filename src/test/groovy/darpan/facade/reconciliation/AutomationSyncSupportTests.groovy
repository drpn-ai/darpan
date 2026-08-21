package darpan.facade.reconciliation

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Pure tests for the automation sync derive/merge/payload/status helpers. Every method exercised
 * here is ec-free, so this class belongs to the unitTest pool, not smokeTest.
 */
class AutomationSyncSupportTests {

    @Test
    void deriveInputModeIsApiRangeWhenBothSidesAreApi() {
        Map<String, Map<String, Object>> sources = [
                FILE_1: [sourceTypeEnumId: AutomationFacadeSupport.SOURCE_TYPE_API],
                FILE_2: [sourceTypeEnumId: AutomationFacadeSupport.SOURCE_TYPE_API],
        ]
        assertEquals(AutomationFacadeSupport.INPUT_MODE_API_RANGE,
                AutomationFacadeSupport.deriveInputModeFromSources(sources))
    }

    @Test
    void deriveInputModeIsApiRangeWhenBothSidesAreDatabase() {
        Map<String, Map<String, Object>> sources = [
                FILE_1: [sourceTypeEnumId: AutomationFacadeSupport.SOURCE_TYPE_DB],
                FILE_2: [sourceTypeEnumId: AutomationFacadeSupport.SOURCE_TYPE_DB],
        ]
        assertEquals(AutomationFacadeSupport.INPUT_MODE_API_RANGE,
                AutomationFacadeSupport.deriveInputModeFromSources(sources))
    }

    @Test
    void deriveInputModeIsSftpWhenEitherSideIsSftp() {
        Map<String, Map<String, Object>> sources = [
                FILE_1: [sourceTypeEnumId: AutomationFacadeSupport.SOURCE_TYPE_API],
                FILE_2: [sourceTypeEnumId: AutomationFacadeSupport.SOURCE_TYPE_SFTP],
        ]
        assertEquals(AutomationFacadeSupport.INPUT_MODE_SFTP_FILES,
                AutomationFacadeSupport.deriveInputModeFromSources(sources))
    }

    @Test
    void deriveDatabaseQueryIdOnlyWhenSourceConfigTypeSaysDatabaseQuery() {
        assertEquals("Q1", AutomationFacadeSupport.deriveDatabaseSourceQueryId(
                [sourceConfigId: "Q1", sourceConfigType: "DATABASE_QUERY"]))
        assertNull(AutomationFacadeSupport.deriveDatabaseSourceQueryId(
                [sourceConfigId: "SHOP1", sourceConfigType: "SHOPIFY_AUTH"]))
        assertNull(AutomationFacadeSupport.deriveDatabaseSourceQueryId(
                [sourceConfigId: "Q1", sourceConfigType: null]))
    }

    @Test
    void runAuthoritativeFieldListHoldsOnlyStoredSourceColumns() {
        // sourceConfigId is derived and passed to save for config enrichment, but it is NOT a column
        // on ReconciliationAutomationSource — including it here would make drift comparison read a
        // field that is never stored and report permanent, unfixable drift.
        assertFalse(AutomationFacadeSupport.RUN_AUTHORITATIVE_SOURCE_FIELDS.contains("sourceConfigId"))
        assertTrue(AutomationFacadeSupport.RUN_AUTHORITATIVE_SOURCE_FIELDS.contains("schemaFileName"))
    }
}
