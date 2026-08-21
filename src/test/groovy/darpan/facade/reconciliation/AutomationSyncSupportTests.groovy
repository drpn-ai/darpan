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

    private static Map storedSftpSource(String fileSide) {
        return [
                fileSide          : fileSide,
                sourceTypeEnumId  : AutomationFacadeSupport.SOURCE_TYPE_SFTP,
                systemEnumId      : "OLD_SYSTEM",
                schemaFileName    : "old-schema.json",
                sftpServerId      : "SFTP1",
                remotePathTemplate: "/drop/orders",
                fileNamePattern   : "orders-*.csv",
                extractStatusIds  : "APPROVED,COMPLETED",
        ]
    }

    private static Map<String, Map<String, Object>> derivedFile1(Map overrides = [:]) {
        Map<String, Object> entry = [fileSide            : "FILE_1",
                                     sourceTypeEnumId    : AutomationFacadeSupport.SOURCE_TYPE_SFTP,
                                     systemEnumId        : "NEW_SYSTEM",
                                     schemaFileName      : "new-schema.json",
                                     recordRootExpression: "\$.records",
                                     primaryIdExpression : "\$.id",
                                     idValueNormalizer   : "TRIM",
                                     fileTypeEnumId      : "DftCsv"] + overrides
        return [FILE_1: entry]
    }

    @Test
    void mergePreservesSftpAndStateFieldsWhileOverwritingRunFields() {
        List merged = AutomationFacadeSupport.mergeSyncedSourceEntries(
                [storedSftpSource("FILE_1")], derivedFile1(), [FILE_1: []])

        assertEquals(1, merged.size())
        Map entry = (Map) merged[0]
        assertEquals("NEW_SYSTEM", entry.systemEnumId)
        assertEquals("new-schema.json", entry.schemaFileName)
        // The SFTP carve-out. The run cannot supply any of these.
        assertEquals("SFTP1", entry.sftpServerId)
        assertEquals("/drop/orders", entry.remotePathTemplate)
        assertEquals("orders-*.csv", entry.fileNamePattern)
        assertEquals("APPROVED,COMPLETED", entry.extractStatusIds)
    }

    @Test
    void mergeAlwaysSetsExcludeFiltersSoTheyAreReplacedNotKept() {
        List merged = AutomationFacadeSupport.mergeSyncedSourceEntries(
                [storedSftpSource("FILE_1")], derivedFile1(), [FILE_1: []])
        // resolveSourceFilters KEEPS the automation's existing rows when the key is absent, so an
        // omitted excludeFilters would make sync a silent no-op for filters.
        assertTrue(((Map) merged[0]).containsKey("excludeFilters"))
        assertEquals([], ((Map) merged[0]).excludeFilters)
    }

    @Test
    void mergeConvertsFilterValuesToTheDeclaredValuesListParameter() {
        List merged = AutomationFacadeSupport.mergeSyncedSourceEntries(
                [storedSftpSource("FILE_1")], derivedFile1(),
                [FILE_1: [[fieldExpression: "status", operator: "EXCLUDE_IN", filterValues: "CANCELLED,VOID"]]])

        Map filter = (Map) ((List) ((Map) merged[0]).excludeFilters)[0]
        // save#Automation declares sources[].excludeFilters[].values; filterValues is undeclared and
        // may be stripped by Moqui parameter filtering before it reaches the validator.
        assertEquals(["CANCELLED", "VOID"], filter.get("values"))
        assertFalse(filter.containsKey("filterValues"))
        assertEquals("status", filter.fieldExpression)
        assertEquals("EXCLUDE_IN", filter.operator)
    }

    @Test
    void mergeAddsASideTheAutomationDoesNotYetHave() {
        Map<String, Map<String, Object>> derived = derivedFile1() +
                [FILE_2: [fileSide: "FILE_2", sourceTypeEnumId: AutomationFacadeSupport.SOURCE_TYPE_API]]
        List merged = AutomationFacadeSupport.mergeSyncedSourceEntries(
                [storedSftpSource("FILE_1")], derived, [:])
        assertEquals(["FILE_1", "FILE_2"], merged.collect { ((Map) it).fileSide })
    }

    @Test
    void mergeDropsAStoredSideTheRunNoLongerDefines() {
        List merged = AutomationFacadeSupport.mergeSyncedSourceEntries(
                [storedSftpSource("FILE_1"), storedSftpSource("FILE_2")], derivedFile1(), [:])
        assertEquals(["FILE_1"], merged.collect { ((Map) it).fileSide })
    }
}
