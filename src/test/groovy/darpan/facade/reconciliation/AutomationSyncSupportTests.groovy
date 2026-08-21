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

    private static Map pausedAutomation() {
        return [
                automationId            : "AUT1",
                automationName          : "Orders Daily Reconciliation",
                description             : "nightly",
                savedRunId              : "RS_ORDERS",
                savedRunType            : "ruleset",
                inputModeEnumId         : AutomationFacadeSupport.INPUT_MODE_SFTP_FILES,
                scheduleExpr            : "0 0 7 * * ?",
                nextScheduledFireTime   : new java.sql.Timestamp(1_800_000_000_000L),
                lastScheduledFireTime   : new java.sql.Timestamp(1_700_000_000_000L),
                relativeWindowTypeEnumId: "AUT_WIN_PREV_DAY",
                relativeWindowCount     : 1,
                maxWindowDays           : 28,
                splitWindowDays         : 28,
                windowTimeZone          : "Asia/Kolkata",
                chatSpaceId             : "CS1",
                isActive                : "N",
        ]
    }

    @Test
    void syncPayloadPassesIsActiveAsBooleanSoAPausedAutomationStaysPaused() {
        Map payload = AutomationFacadeSupport.buildAutomationSyncPayload(
                pausedAutomation(), AutomationFacadeSupport.INPUT_MODE_API_RANGE, [])
        // prepareAutomationSave reads `input.isActive == false`; the stored "N" string yields "Y".
        assertEquals(Boolean.FALSE, payload.isActive)
    }

    @Test
    void syncPayloadCarriesTheStoredFireTimesSoTheScheduleDoesNotShift() {
        Map automation = pausedAutomation()
        Map payload = AutomationFacadeSupport.buildAutomationSyncPayload(
                automation, AutomationFacadeSupport.INPUT_MODE_API_RANGE, [])
        assertEquals(automation.nextScheduledFireTime, payload.nextScheduledFireTime)
        assertEquals(automation.lastScheduledFireTime, payload.lastScheduledFireTime)
    }

    @Test
    void syncPayloadNamesTheSourceListSourcesNotSourceEntries() {
        List entries = [[fileSide: "FILE_1"]]
        Map payload = AutomationFacadeSupport.buildAutomationSyncPayload(
                pausedAutomation(), AutomationFacadeSupport.INPUT_MODE_API_RANGE, entries)
        // save#Automation declares `sources`; `sourceEntries` is its internal name and is ignored.
        assertEquals(entries, payload.get("sources"))
        assertFalse(payload.containsKey("sourceEntries"))
    }

    @Test
    void syncPayloadPreservesEveryOperatorOwnedAutomationField() {
        Map payload = AutomationFacadeSupport.buildAutomationSyncPayload(
                pausedAutomation(), AutomationFacadeSupport.INPUT_MODE_API_RANGE, [])
        assertEquals("Orders Daily Reconciliation", payload.automationName)
        assertEquals("nightly", payload.description)
        assertEquals("0 0 7 * * ?", payload.scheduleExpr)
        assertEquals("AUT_WIN_PREV_DAY", payload.relativeWindowTypeEnumId)
        assertEquals(1, payload.relativeWindowCount)
        assertEquals("Asia/Kolkata", payload.windowTimeZone)
        assertEquals("CS1", payload.chatSpaceId)
        assertEquals(28, payload.maxWindowDays)
        assertEquals(28, payload.splitWindowDays)
    }

    @Test
    void syncPayloadKeepsTheSavedRunAndTakesTheDerivedInputMode() {
        Map payload = AutomationFacadeSupport.buildAutomationSyncPayload(
                pausedAutomation(), AutomationFacadeSupport.INPUT_MODE_API_RANGE, [])
        assertEquals("RS_ORDERS", payload.savedRunId)
        assertEquals("ruleset", payload.savedRunType)
        assertEquals(AutomationFacadeSupport.INPUT_MODE_API_RANGE, payload.inputModeEnumId)
        assertEquals("AUT1", payload.automationId)
    }

    private static Map derivedConfigFor(String schemaFileName, String inputMode, List filters = []) {
        return [
                savedRunMissing  : false,
                error            : null,
                compareScopeId   : "CS_ORDERS",
                inputModeEnumId  : inputMode,
                sourcesByFileSide: [FILE_1: [fileSide        : "FILE_1", schemaFileName: schemaFileName,
                                             sourceTypeEnumId: AutomationFacadeSupport.SOURCE_TYPE_API]],
                filtersByFileSide: [FILE_1: filters],
        ]
    }

    private static List storedFile1(String schemaFileName, List excludeFilters = []) {
        return [[fileSide        : "FILE_1", schemaFileName: schemaFileName,
                 sourceTypeEnumId: AutomationFacadeSupport.SOURCE_TYPE_API,
                 excludeFilters  : excludeFilters]]
    }

    @Test
    void syncStatusIsInSyncWhenNothingDiffers() {
        Map automation = [inputModeEnumId: AutomationFacadeSupport.INPUT_MODE_API_RANGE,
                          compareScopeId : "CS_ORDERS"]
        Map status = AutomationFacadeSupport.buildAutomationSyncStatus(automation,
                storedFile1("run-schema.json"),
                derivedConfigFor("run-schema.json", AutomationFacadeSupport.INPUT_MODE_API_RANGE))
        assertTrue(status.inSync as Boolean, "unexpected drift: ${status.changedFields}")
        assertEquals([], status.changedFields)
        assertFalse(status.inputModeChanging as Boolean)
    }

    @Test
    void syncStatusNamesAChangedSourceField() {
        Map automation = [inputModeEnumId: AutomationFacadeSupport.INPUT_MODE_API_RANGE,
                          compareScopeId : "CS_ORDERS"]
        Map status = AutomationFacadeSupport.buildAutomationSyncStatus(automation,
                storedFile1("stale-schema.json"),
                derivedConfigFor("run-schema.json", AutomationFacadeSupport.INPUT_MODE_API_RANGE))
        assertFalse(status.inSync as Boolean)
        assertTrue((status.changedFields as List).contains("FILE_1.schemaFileName"))
    }

    @Test
    void syncStatusNoticesFilterDrift() {
        Map automation = [inputModeEnumId: AutomationFacadeSupport.INPUT_MODE_API_RANGE,
                          compareScopeId : "CS_ORDERS"]
        Map status = AutomationFacadeSupport.buildAutomationSyncStatus(automation,
                storedFile1("run-schema.json", [[fieldExpression: "status", operator: "EXCLUDE_IN",
                                                 filterValues   : "CANCELLED"]]),
                derivedConfigFor("run-schema.json", AutomationFacadeSupport.INPUT_MODE_API_RANGE, []))
        assertTrue((status.changedFields as List).contains("FILE_1.excludeFilters"))
    }

    @Test
    void syncStatusFlagsAnInputModeFlip() {
        Map automation = [inputModeEnumId: AutomationFacadeSupport.INPUT_MODE_SFTP_FILES,
                          compareScopeId : "CS_ORDERS"]
        Map status = AutomationFacadeSupport.buildAutomationSyncStatus(automation,
                storedFile1("run-schema.json"),
                derivedConfigFor("run-schema.json", AutomationFacadeSupport.INPUT_MODE_API_RANGE))
        assertTrue(status.inputModeChanging as Boolean)
        assertTrue((status.changedFields as List).contains("inputMode"))
    }

    @Test
    void syncStatusReportsAMissingSavedRunWithoutClaimingDrift() {
        Map status = AutomationFacadeSupport.buildAutomationSyncStatus([:], [],
                [savedRunMissing: true, sourcesByFileSide: [:], filtersByFileSide: [:]])
        assertTrue(status.savedRunMissing as Boolean)
        // A missing run is not drift. Calling it out-of-date offers an action that cannot work.
        assertTrue(status.inSync as Boolean)
    }

    @Test
    void mergeKeepsTheStoredSourceTypeWhenTheRunDidNotRecordOne() {
        // RuleSetCompareSource.sourceTypeEnumId is nullable; ReconciliationAutomationSource's is
        // not-null. Writing the run's null through makes validateSources reject the entire sync.
        Map<String, Map<String, Object>> derived = derivedFile1([sourceTypeEnumId: null])
        List merged = AutomationFacadeSupport.mergeSyncedSourceEntries(
                [storedSftpSource("FILE_1")], derived, [FILE_1: []])
        assertEquals(AutomationFacadeSupport.SOURCE_TYPE_SFTP, ((Map) merged[0]).sourceTypeEnumId)
    }

    @Test
    void mergeStillTakesTheRunsSourceTypeWhenItHasOne() {
        Map<String, Map<String, Object>> derived = derivedFile1([sourceTypeEnumId: AutomationFacadeSupport.SOURCE_TYPE_API])
        List merged = AutomationFacadeSupport.mergeSyncedSourceEntries(
                [storedSftpSource("FILE_1")], derived, [FILE_1: []])
        assertEquals(AutomationFacadeSupport.SOURCE_TYPE_API, ((Map) merged[0]).sourceTypeEnumId)
    }

    @Test
    void mergeKeepsTheStoredEndpointWhenTheRunDidNotRecordOne() {
        // An API source must carry systemMessageRemoteId or nsRestletConfigId. RuleSetCompareSource
        // may hold neither, and writing that null through makes validateSources reject the sync.
        Map stored = storedSftpSource("FILE_1") + [systemMessageRemoteId: "OMS_REMOTE"]
        Map<String, Map<String, Object>> derived = derivedFile1([systemMessageRemoteId: null])
        List merged = AutomationFacadeSupport.mergeSyncedSourceEntries([stored], derived, [FILE_1: []])
        assertEquals("OMS_REMOTE", ((Map) merged[0]).systemMessageRemoteId)
    }

    @Test
    void mergeTakesTheRunsEndpointWhenItHasOne() {
        Map stored = storedSftpSource("FILE_1") + [systemMessageRemoteId: "OMS_REMOTE"]
        Map<String, Map<String, Object>> derived = derivedFile1([systemMessageRemoteId: "SHOPIFY_REMOTE"])
        List merged = AutomationFacadeSupport.mergeSyncedSourceEntries([stored], derived, [FILE_1: []])
        assertEquals("SHOPIFY_REMOTE", ((Map) merged[0]).systemMessageRemoteId)
    }

    @Test
    void syncStatusDoesNotReportDriftOnFieldsSyncWillNotChange() {
        // The merge keeps the stored value where the run is blank. Comparing against the raw derive
        // would report drift here forever, because no sync could ever resolve it.
        Map automation = [inputModeEnumId: AutomationFacadeSupport.INPUT_MODE_API_RANGE,
                          compareScopeId : "CS_ORDERS"]
        List stored = [[fileSide             : "FILE_1", schemaFileName: "run-schema.json",
                        sourceTypeEnumId     : AutomationFacadeSupport.SOURCE_TYPE_API,
                        systemMessageRemoteId: "OMS_REMOTE", excludeFilters: []]]
        Map derived = derivedConfigFor("run-schema.json", AutomationFacadeSupport.INPUT_MODE_API_RANGE)
        // The run records no endpoint at all — exactly the blank case merge preserves.
        Map status = AutomationFacadeSupport.buildAutomationSyncStatus(automation, stored, derived)
        assertTrue(status.inSync as Boolean, "reported drift sync cannot fix: ${status.changedFields}")
    }

    @Test
    void syncedInputModeFollowsTheMergedSourcesNotTheRawRun() {
        // The run recorded no source types, so the raw derive would say API_RANGE; merge restores the
        // stored SFTP type, and the persisted mode has to follow that or validateSources rejects it.
        List merged = AutomationFacadeSupport.mergeSyncedSourceEntries(
                [storedSftpSource("FILE_1")], derivedFile1([sourceTypeEnumId: null]), [FILE_1: []])
        assertEquals(AutomationFacadeSupport.INPUT_MODE_SFTP_FILES,
                AutomationFacadeSupport.resolveSyncedInputMode(merged))
    }
}
