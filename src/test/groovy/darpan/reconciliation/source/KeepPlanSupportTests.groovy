package darpan.reconciliation.source

import darpan.reconciliation.automation.SourceSystemConnectorSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonOutput
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 (plan 3b-2b, Task 9). A side's Keep plan: where its Keep runs (SPARK, READ or NONE), the tenant's filter rows read
 * for one named tenant only, and the connector's declared built-ins ahead of them. Every connector here is made up (ACME*):
 * core holds no system name, and no system component's rows are needed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeepPlanSupportTests {

    private static final String A = "KP_TENANT_A"
    private static final String B = "KP_TENANT_B"

    /** Two entries: the second is skipped when the side reads only cancelled records. */
    private static final String ACME_KEEP_LINES = JsonOutput.toJson([
            [sequenceNum: 2, label: "Draft records", kinds: [stage: "FVT_TEXT"],
             lines: [[conditionSeqId: "01", sequenceNum: 1, conditionTypeId: "RCT_COMPARE", fieldName: "stage",
                      operatorEnumId: "ENTCO_EQUALS", compareValue: "DRAFT"]]],
            [sequenceNum: 1, label: "Cancelled records", appliesUnless: [onlyCancelled: "true"], kinds: [isCancelled: "FVT_BOOLEAN"],
             lines: [[conditionSeqId: "01", sequenceNum: 1, conditionTypeId: "RCT_COMPARE", fieldName: "isCancelled",
                      operatorEnumId: "ENTCO_EQUALS", compareValue: "true"]]],
    ])

    /** Task 13's test-only extracts: one no longer declares sourceFilters (its system task done), one still does. */
    private static final String NO_LONGER_FILTERS = "reconciliation.KeepLiveTestExtractionServices.extract#KeepLiveRecords"
    private static final String STILL_FILTERS = "reconciliation.KeepLiveTestExtractionServices.extract#KeepLiveFilteringRecords"

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "keep-plan-support")
        Closure<String> filter = { String scopeId, String fileSide, int seq, String field, String operator, String values, String tenant ->
            """<darpan.rule.RuleSetCompareSourceFilter compareScopeId="${scopeId}" fileSide="${fileSide}" sequenceNum="${seq}"
                fieldExpression="\$.records[*].${field}" operator="${operator}" filterValues="${values}" companyUserGroupId="${tenant}"/>"""
        }
        Closure<String> scope = { String scopeId, String tenant ->
            """<darpan.rule.RuleSet ruleSetId="${scopeId}_RS" ruleSetName="${scopeId}" companyUserGroupId="${tenant}"/>
            <darpan.rule.RuleSetCompareScope compareScopeId="${scopeId}" ruleSetId="${scopeId}_RS" objectType="ORDER" companyUserGroupId="${tenant}"/>
            <darpan.rule.RuleSetCompareSource compareScopeId="${scopeId}" fileSide="FILE_1" systemEnumId="ACME" companyUserGroupId="${tenant}"/>
            <darpan.rule.RuleSetCompareSource compareScopeId="${scopeId}" fileSide="FILE_2" systemEnumId="ACME" companyUserGroupId="${tenant}"/>"""
        }
        String keepLinesAttr = ACME_KEEP_LINES.replace("&", "&amp;").replace("\"", "&quot;")
        ec.entity.makeDataLoader().xmlText("""<entity-facade-xml type="seed">
            <darpan.reconciliation.SourceSystemConnector systemEnumId="ACME" extractServiceName="reconciliation.AcmeExtractionServices.extract#Records"
                filterParameterName="excludeFilters" excludedRecordsSidecar="Y" keepLinesJson="${keepLinesAttr}"/>
            <darpan.reconciliation.SourceSystemConnector systemEnumId="ACME_QUIET" extractServiceName="reconciliation.AcmeExtractionServices.extract#Quiet"
                filterParameterName="excludeFilters" excludedRecordsSidecar="N" keepLinesJson="[]"/>
            <darpan.reconciliation.SourceSystemConnector systemEnumId="ACME_DEFINED" extractServiceName="datasource.DataSourceServices.extract#DataDefinedSource"
                filterParameterName="excludeFilters" excludedRecordsSidecar="Y" dataSourceDefinitionId="ACME_DEF" keepLinesJson="${keepLinesAttr}"/>
            <darpan.reconciliation.SourceSystemConnector systemEnumId="ACME_UPLOAD" extractServiceName="reconciliation.AcmeExtractionServices.extract#Upload"
                keepLinesJson="${keepLinesAttr}"/>
            <darpan.reconciliation.SourceSystemConnector systemEnumId="ACME_BAD" extractServiceName="reconciliation.AcmeExtractionServices.extract#Bad"
                filterParameterName="excludeFilters" keepLinesJson='[{"sequenceNum":1,"label":"Has lines","lines":[{"conditionSeqId":"01","conditionTypeId":"RCT_EXISTS","fieldName":"lines"}]}]'/>
            <darpan.reconciliation.SourceSystemConnector systemEnumId="ACME_LIVE" extractServiceName="${NO_LONGER_FILTERS}"
                filterParameterName="sourceFilters" excludedRecordsSidecar="Y" keepLinesJson="${keepLinesAttr}"/>
            <darpan.reconciliation.SourceSystemConnector systemEnumId="ACME_LIVE_BUILT_IN" extractServiceName="${NO_LONGER_FILTERS}"
                filterParameterName="sourceFilters" excludedRecordsSidecar="Y" keepLinesJson='[{"sequenceNum":1,"label":"Unkeyed lines","kinds":{"lineKind":"FVT_TEXT"},"lines":[{"conditionSeqId":"01","sequenceNum":1,"conditionTypeId":"RCT_COMPARE","fieldName":"lineKind","operatorEnumId":"ENTCO_EQUALS","compareValue":"UNKEYED"}]}]'/>
            <darpan.reconciliation.SourceSystemConnector systemEnumId="ACME_LIVE_PLAIN" extractServiceName="${NO_LONGER_FILTERS}"
                filterParameterName="sourceFilters" excludedRecordsSidecar="N" keepLinesJson="[]"/>
            <darpan.reconciliation.SourceSystemConnector systemEnumId="ACME_LIVE_UNLOADED" extractServiceName="${NO_LONGER_FILTERS}"
                filterParameterName="sourceFilters" excludedRecordsSidecar="N"/>
            <darpan.reconciliation.SourceSystemConnector systemEnumId="ACME_LIVE_BLANK" extractServiceName="${NO_LONGER_FILTERS}"
                filterParameterName="sourceFilters" excludedRecordsSidecar="N" keepLinesJson="  "/>
            <darpan.reconciliation.SourceSystemConnector systemEnumId="ACME_FILTERING_UNLOADED" extractServiceName="${STILL_FILTERS}"
                filterParameterName="sourceFilters" excludedRecordsSidecar="N"/>
            <darpan.reconciliation.SourceSystemConnector systemEnumId="ACME_LIVE_ABSENCE" extractServiceName="${NO_LONGER_FILTERS}"
                filterParameterName="sourceFilters" excludedRecordsSidecar="N" keepLinesJson='[{"sequenceNum":1,"label":"Not keyed lines","kinds":{"lineKind":"FVT_TEXT"},"lines":[{"conditionSeqId":"01","sequenceNum":1,"conditionTypeId":"RCT_COMPARE","fieldName":"lineKind","operatorEnumId":"ENTCO_NOT_EQUALS","compareValue":"KEYED"}]}]'/>
            <darpan.reconciliation.SourceSystemConnector systemEnumId="ACME_LIVE_FILTERING" extractServiceName="${STILL_FILTERS}"
                filterParameterName="sourceFilters" excludedRecordsSidecar="Y" keepLinesJson="${keepLinesAttr}"/>
            ${scope('KP_SCOPE_A', A)}
            ${scope('KP_SCOPE_B', B)}
            ${filter('KP_SCOPE_A', 'FILE_1', 2, 'channel', 'INCLUDE_IN', 'WEB,POS', A)}
            ${filter('KP_SCOPE_A', 'FILE_1', 1, 'orderType', 'EXCLUDE_IN', 'TRANSFER', A)}
            ${filter('KP_SCOPE_A', 'FILE_2', 1, 'other', 'EXCLUDE_IN', 'X', A)}
            ${filter('KP_SCOPE_B', 'FILE_1', 1, 'secretField', 'EXCLUDE_IN', 'B_ONLY', B)}
        </entity-facade-xml>""").dummyFks(true).load()
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    /**
     * Task 16e round 2: landedFilteredAtRead is the one answer every path gets. It derives the side's built-in Keep-line fields
     * itself; a side with tenant rows takes only the read's configuredExclusions (a Spark-era file with no flagged record must
     * not skip the tenant's rules); no landed file is never filtered; a missing one is.
     */
    @Test
    void landedFilteredAtReadDerivesTheBuiltInFieldsItself() {
        File builtInOnly = landed("helper-built-in-only", [records: [[orderId: "O1"]], metadata: [filters: [builtInDropCount: 2]]])
        File sparkEra = landed("helper-spark-era", [records: [[orderId: "O1"], [orderId: "O2", lineKind: "UNKEYED"]]])
        Map builtIn = connector("ACME_LIVE_BUILT_IN")
        List<Map<String, Object>> rows = KeepPlanSupport.tenantRuleRows(ec, "KP_SCOPE_A", "FILE_2", A)

        assertTrue(KeepPlanSupport.landedFilteredAtRead(ec, builtInOnly, [], builtIn, [:]))
        assertTrue(KeepPlanSupport.landedFilteredAtRead(ec, builtInOnly, null, builtIn, [:]))
        assertFalse(KeepPlanSupport.landedFilteredAtRead(ec, sparkEra, [], builtIn, [:]))
        assertFalse(KeepPlanSupport.landedFilteredAtRead(ec, builtInOnly, rows, builtIn, [:]), "with tenant rows only configuredExclusions tell")
        assertFalse(KeepPlanSupport.landedFilteredAtRead(ec, builtInOnly, [], connector("ACME_LIVE_PLAIN"), [:]), "no built-in line: no flag to miss")
        assertFalse(KeepPlanSupport.landedFilteredAtRead(ec, null, [], builtIn, [:]), "no landed file: the side is read now")
        assertTrue(KeepPlanSupport.landedFilteredAtRead(ec, new File(builtInOnly.parentFile, "gone.json"), rows, builtIn, [:]))
    }

    /**
     * Final review triage 6 (16e M4): a built-in line that could hold on a missing field (here NE) sets aside records that carry no
     * flag, so a file with no flagged record says nothing about its read; only configuredExclusions tell.
     */
    @Test
    void aLineThatHoldsOnAMissingFieldTakesOnlyTheConfiguredExclusionsSignal() {
        File noFlag = landed("absence-no-flag", [records: [[orderId: "O1"], [orderId: "O2"]], metadata: [filters: [builtInDropCount: 1]]])
        File ruled = landed("absence-ruled", [records: [[orderId: "O1"]], metadata: [filters: [configuredExclusions: [[sequenceNum: 1,
                fieldExpression: "other", operator: "EXCLUDE_IN", values: ["X"], excludedCount: 0, fieldAbsentCount: 0]]]]])
        assertFalse(KeepPlanSupport.landedFilteredAtRead(ec, noFlag, [], connector("ACME_LIVE_ABSENCE"), [:]),
                "an NE line: a file with no lineKind is not read as filtered")
        assertTrue(KeepPlanSupport.landedFilteredAtRead(ec, ruled, [], connector("ACME_LIVE_ABSENCE"), [:]), "configuredExclusions still tell")
        assertTrue(KeepPlanSupport.landedFilteredAtRead(ec, noFlag, [], connector("ACME_LIVE_BUILT_IN"), [:]), "control: an EQ line's flag tells")
    }

    /**
     * Final review m3: a Keep summary beside a landed file means Spark Keep ran over it, so its read did not filter it, whatever
     * the read's signals say. Otherwise a retry would skip Keep and leave the earlier attempt's summary and rows to be read.
     */
    @Test
    void aKeepSummaryBesideALandedFileMeansItWasNotFilteredAtTheRead() {
        File ruled = landed("kept-before", [records: [[orderId: "O1"]], metadata: [filters: [configuredExclusions: [[sequenceNum: 1,
                fieldExpression: "other", operator: "EXCLUDE_IN", values: ["X"], excludedCount: 0, fieldAbsentCount: 0]]]]])
        List<Map<String, Object>> rows = KeepPlanSupport.tenantRuleRows(ec, "KP_SCOPE_A", "FILE_2", A)
        assertTrue(KeepPlanSupport.landedFilteredAtRead(ec, ruled, rows, connector("ACME_LIVE"), [:]), "test precondition")
        KeepArtifacts.writeSummary(ruled, [rules: [], configuredExclusions: [], keptCount: 1, setAsideCount: 0])
        assertFalse(KeepPlanSupport.landedFilteredAtRead(ec, ruled, rows, connector("ACME_LIVE"), [:]))
        assertFalse(KeepPlanSupport.landedFilteredAtRead(ec, ruled, [], connector("ACME_LIVE_BUILT_IN"), [:]))
        assertEquals("SPARK", KeepPlanSupport.effectiveSideKeep(ec, rows, connector("ACME_LIVE"), [:],
                ec.service.getServiceDefinition(NO_LONGER_FILTERS), "records", ['$.records[*].orderId'], true, ruled).placement,
                "the retried side keeps in Spark again")
    }

    /**
     * Ruling 3b2b-29 (final review I2): a side Spark keeps fails when its connector has no Keep lines loaded (null or blank), as
     * after a skipped upgrade-data load; an explicit "[]" or a real line passes, and a side still filtering at the read is
     * unaffected.
     */
    @Test
    void aSideSparkKeepsFailsWhenItsConnectorsKeepLinesAreNotLoaded() {
        Object noLongerFilters = ec.service.getServiceDefinition(NO_LONGER_FILTERS)
        Object stillFilters = ec.service.getServiceDefinition(STILL_FILTERS)
        List<Map<String, Object>> rows = KeepPlanSupport.tenantRuleRows(ec, "KP_SCOPE_A", "FILE_2", A)
        Closure<Map> effective = { List<Map<String, Object>> sideRows, String system, Object definition ->
            KeepPlanSupport.effectiveSideKeep(ec, sideRows, connector(system), [:], definition, "records", ['$.records[*].orderId'], true,
                    null, "FILE_2")
        }
        assertNull(ec.entity.find(SourceSystemConnectorSupport.ENTITY_NAME).condition("systemEnumId", "ACME_LIVE_UNLOADED").one().keepLinesJson,
                "test precondition: no value loaded")

        IllegalArgumentException unloaded = assertThrows(IllegalArgumentException) { effective([], "ACME_LIVE_UNLOADED", noLongerFilters) }
        assertEquals("File 2 (ACME_LIVE_UNLOADED) cannot be filtered: the ACME_LIVE_UNLOADED connector's Keep lines (keepLinesJson) " +
                "are not loaded. Load the upgrade data, then run again.", unloaded.message)
        assertThrows(IllegalArgumentException) { effective(rows, "ACME_LIVE_UNLOADED", noLongerFilters) }
        assertThrows(IllegalArgumentException) { effective([], "ACME_LIVE_BLANK", noLongerFilters) }
        File ruled = landed("unloaded-ruled", [records: [[orderId: "O1"]], metadata: [filters: [configuredExclusions: [[sequenceNum: 1,
                fieldExpression: "other", operator: "EXCLUDE_IN", values: ["X"], excludedCount: 0, fieldAbsentCount: 0]]]]])
        assertThrows(IllegalArgumentException) {
            KeepPlanSupport.effectiveSideKeep(ec, rows, connector("ACME_LIVE_UNLOADED"), [:], noLongerFilters, "records", ["orderId"], true, ruled)
        }

        Map plain = effective([], "ACME_LIVE_PLAIN", noLongerFilters)
        assertEquals("SPARK", plain.placement, "an explicit [] is a loaded value")
        assertEquals([], plain.rules)
        assertEquals(["BUILT_IN:1"], effective([], "ACME_LIVE_BUILT_IN", noLongerFilters).rules*.id, "a real line passes")
        assertEquals(KeepPlanSupport.todaysRead(rows), effective(rows, "ACME_FILTERING_UNLOADED", stillFilters),
                "a side still filtering at the read is unaffected")
    }

    /** A landed extract written to a scratch folder. */
    private static File landed(String name, Map document) {
        File dir = java.nio.file.Files.createTempDirectory("keep-plan-landed").toFile()
        dir.deleteOnExit()
        File file = new File(dir, name + ".json")
        file.setText(JsonOutput.toJson(document), "UTF-8")
        file.deleteOnExit()
        return file
    }

    /**
     * A side placed through the one gate (effectiveSideKeep), as a run whose compare takes Keep, whose extract no longer declares
     * the filter parameter and whose records are top-level.
     */
    private Map placed(List<Map<String, Object>> rows, String system, Map extractOptions = [:]) {
        return KeepPlanSupport.effectiveSideKeep(ec, rows, connector(system), extractOptions, [inParameterNameList: ["keepRuleFields"]],
                "records", ['$.records[*].orderId'], true)
    }

    /** placed with the compare scope's rows for one named tenant. */
    private Map placedFor(String compareScopeId, String fileSide, String tenant, String system, Map extractOptions = [:]) {
        return placed(KeepPlanSupport.tenantRuleRows(ec, compareScopeId, fileSide, tenant), system, extractOptions)
    }

    private Map connector(String systemEnumId) {
        Map resolved = SourceSystemConnectorSupport.resolveBySystemEnumId(ec, systemEnumId)
        assertEquals(systemEnumId, resolved?.systemEnumId, "test precondition: ${systemEnumId} resolves")
        return resolved
    }

    @Test
    void placementIsNoneForAnUploadSparkForACodedConnectorReadForADataDefinedOne() {
        assertEquals("NONE", KeepPlanSupport.placement(null))
        assertEquals("NONE", KeepPlanSupport.placement([:]))
        assertEquals("NONE", KeepPlanSupport.placement([filterParameterName: "  "]))
        assertEquals("NONE", KeepPlanSupport.placement(connector("ACME_UPLOAD")))
        assertEquals("SPARK", KeepPlanSupport.placement(connector("ACME")))
        assertEquals("READ", KeepPlanSupport.placement(connector("ACME_DEFINED")))

        Map none = placedFor("KP_SCOPE_A", "FILE_1", A, "ACME_UPLOAD")
        assertEquals([placement: "NONE", readFilters: [], rules: [], writeSidecar: false, fields: [] as Set], none,
                "an upload gets no filters and no Keep, its declared lines included")

        Map read = placedFor("KP_SCOPE_A", "FILE_1", A, "ACME_DEFINED")
        assertEquals("READ", read.placement)
        assertEquals([], read.rules, "a data-defined side keeps filtering at read (Ruling 3b2b-16): no Spark rules, no built-ins")
        assertEquals([[1, "orderType", "EXCLUDE_IN", "TRANSFER"], [2, "channel", "INCLUDE_IN", "WEB,POS"]],
                read.readFilters.collect { [it.sequenceNum as Integer, it.fieldExpression, it.operator, it.filterValues] },
                "the read gets today's getter-shape rows, in sequence order")
        assertEquals(["orderType", "channel"] as Set, read.fields)
        assertFalse(read.writeSidecar, "the read writes its own side file; Spark writes none")

        Map spark = placedFor("KP_SCOPE_A", "FILE_2", A, "ACME")
        assertEquals("SPARK", spark.placement)
        assertEquals([], spark.readFilters, "a Spark side's read gets no filters")
        assertEquals(["BUILT_IN:1", "BUILT_IN:2", "A:1"], spark.rules*.id)
    }

    /**
     * Ruling 3b2b-23: Spark keeps a SPARK side only once its extract no longer declares the connector's filter parameter; an
     * extract that still takes it filters at the read, and so does any side of another placement or with no extract installed.
     */
    @Test
    void sparkKeepsASideOnlyOnceItsExtractStopsTakingTheFilterParameter() {
        Map stillFilters = [inParameterNameList: ["sourceConfigId", "excludeFilters", "keepFields"]]
        Map noLongerFilters = [inParameterNameList: ["sourceConfigId", "keepRuleFields"]]
        assertFalse(KeepPlanSupport.sparkKeepsSide(connector("ACME"), stillFilters))
        assertTrue(KeepPlanSupport.sparkKeepsSide(connector("ACME"), noLongerFilters))
        assertFalse(KeepPlanSupport.sparkKeepsSide(connector("ACME"), null), "an extract that is not installed")
        assertFalse(KeepPlanSupport.sparkKeepsSide(connector("ACME_DEFINED"), noLongerFilters), "a data-defined side keeps its read")
        assertFalse(KeepPlanSupport.sparkKeepsSide(connector("ACME_UPLOAD"), noLongerFilters), "an upload gets no Keep at all")
        assertEquals([placement: "READ", readFilters: [[sequenceNum: 1]], rules: [], writeSidecar: false, fields: [] as Set],
                KeepPlanSupport.todaysRead([[sequenceNum: 1]]), "today's read: the rows to the read, nothing to Spark, no field added")
    }

    /**
     * Task 13 review I2: effectiveSideKeep is the one decision. A Spark-placed side keeps in Spark only when its compare takes Keep,
     * its extract stopped taking filters and it is keyed on top-level records; otherwise today's read, and its declared Keep lines
     * are never compiled (review M2: ACME_BAD's refused line does not fail a side that reads as today).
     */
    @Test
    void effectiveSideKeepIsTheOneDecisionOnWhereKeepRuns() {
        List<Map<String, Object>> rows = KeepPlanSupport.tenantRuleRows(ec, "KP_SCOPE_A", "FILE_2", A)
        Map stillFilters = [inParameterNameList: ["excludeFilters"]]
        Map noLongerFilters = [inParameterNameList: ["keepRuleFields"]]
        Closure<Map> effective = { String system, Object definition, String root, List<String> keys, boolean keepInSpark ->
            KeepPlanSupport.effectiveSideKeep(ec, rows, connector(system), [:], definition, root, keys, keepInSpark)
        }
        Map spark = effective("ACME", noLongerFilters, "records", ['$.records[*].orderId'], true)
        assertEquals("SPARK", spark.placement)
        assertEquals(["BUILT_IN:1", "BUILT_IN:2", "A:1"], spark.rules*.id)
        assertEquals([], spark.readFilters)
        Map today = KeepPlanSupport.todaysRead(rows)
        assertEquals(today, effective("ACME", stillFilters, "records", ["orderId"], true), "the extract still filters")
        // the compare taking no Keep, a key through a nested array and records under another array fail this side (it has
        // rules and its read no longer filters, Ruling 3b2b-25): aSideNeitherTheReadNorSparkCanFilterFailsBeforeAnyRead
        [[noLongerFilters, "records", ["orderId"], false], [noLongerFilters, null, ['$.records[*].lines[*].lineId'], true],
         [noLongerFilters, "orders", ["orderId"], true]].each { List c ->
            assertThrows(IllegalArgumentException) { effective("ACME", c[0], (String) c[1], (List<String>) c[2], (boolean) c[3]) }
        }
        assertEquals(today, effective("ACME_BAD", stillFilters, null, ["orderId"], true), "a gated side never compiles its Keep lines")
        assertEquals("READ", effective("ACME_DEFINED", noLongerFilters, null, ["orderId"], true).placement)
        assertEquals(["other"] as Set, effective("ACME_DEFINED", noLongerFilters, null, ["orderId"], true).fields,
                "a data-defined side's plan is as before")
        assertEquals("NONE", effective("ACME_UPLOAD", noLongerFilters, null, ["orderId"], true).placement)
        assertTrue(KeepPlanSupport.keyedOnRecords(null, ["orderId", "\$.records[*].lineId|MADE_UP_NORMALIZER"]))
        assertFalse(KeepPlanSupport.keyedOnRecords("\$.records[*]", []), "no key at all")
    }

    /**
     * Ruling 3b2b-25: a side whose extract no longer declares the connector's filter parameter cannot filter at the read (Moqui
     * drops the undeclared parameter). When Spark cannot keep it either, a side with Keep rules fails before any read, naming
     * the side and why; a side with none passes with nothing to filter; a side whose extract still declares the parameter keeps
     * today's read. Real (test-only) extract definitions, as the run paths read them.
     */
    @Test
    void aSideNeitherTheReadNorSparkCanFilterFailsBeforeAnyRead() {
        List<Map<String, Object>> rows = KeepPlanSupport.tenantRuleRows(ec, "KP_SCOPE_A", "FILE_2", A)
        assertEquals(["other"], rows*.fieldExpression, "test precondition: the side has a tenant rule")
        Object noLongerFilters = ec.service.getServiceDefinition(NO_LONGER_FILTERS)
        Object stillFilters = ec.service.getServiceDefinition(STILL_FILTERS)
        assertTrue(KeepPlanSupport.sparkKeepsSide(connector("ACME_LIVE"), noLongerFilters), "test precondition: its read cannot filter")
        assertFalse(KeepPlanSupport.sparkKeepsSide(connector("ACME_LIVE_FILTERING"), stillFilters), "test precondition: its read filters")
        List<String> nested = ['$.records[*].lines[*].lineId']
        File builtInCapture = landed("built-in-only", [records: [[orderId: "O1"], [orderId: "O2"]], metadata: [filters: [builtInDropCount: 1]]])
        File sparkEra = landed("spark-era", [records: [[orderId: "O1", lineKind: "KEYED"], [orderId: "O2", lineKind: "UNKEYED"]], metadata: [:]])
        Closure<Map> effective = { List<Map<String, Object>> sideRows, String system, Object definition, String root, List<String> keys,
                                   boolean keepInSpark ->
            KeepPlanSupport.effectiveSideKeep(ec, sideRows, connector(system), [:], definition, root, keys, keepInSpark, null, "FILE_2")
        }

        // a nested key with rules: the tenant's, or only the connector's built-ins
        IllegalArgumentException nestedKey = assertThrows(IllegalArgumentException) { effective(rows, "ACME_LIVE", noLongerFilters, null, nested, true) }
        assertEquals("File 2 (ACME_LIVE) cannot be filtered: its source no longer filters at read and its records are not top-level, " +
                "so Keep cannot run in Spark.", nestedKey.message)
        // a side whose ONLY Keep rule is a declared built-in drop (keepLinesJson, no tenant filter) fails exactly the same:
        // without the check it would silently stop applying the drop its extractor no longer makes
        assertEquals(["BUILT_IN:1"], placed([], "ACME_LIVE_BUILT_IN").rules*.id,
                "test precondition: the side's one Keep rule is its connector's declared built-in")
        IllegalArgumentException builtInOnly = assertThrows(IllegalArgumentException) { effective([], "ACME_LIVE_BUILT_IN", noLongerFilters, null, nested, true) }
        assertEquals("File 2 (ACME_LIVE_BUILT_IN) cannot be filtered: its source no longer filters at read and its records are not top-level, " +
                "so Keep cannot run in Spark.", builtInOnly.message)
        assertThrows(IllegalArgumentException) { effective(null, "ACME_LIVE_BUILT_IN", noLongerFilters, "records", ["orderId"], false) }
        assertThrows(IllegalArgumentException) { effective([], "ACME_LIVE", noLongerFilters, "orders", ["orderId"], true) }
        assertThrows(IllegalArgumentException) { effective(rows, "ACME_LIVE_PLAIN", noLongerFilters, null, nested, true) }

        // a side with no key: its own reason, and what to do
        IllegalArgumentException keyless = assertThrows(IllegalArgumentException) { effective(rows, "ACME_LIVE", noLongerFilters, "records", [], true) }
        assertEquals("File 2 (ACME_LIVE) cannot be filtered: its source no longer filters at read and it has no key, so Keep cannot " +
                "run in Spark. Give the side a key field in its compare scope.", keyless.message)
        IllegalArgumentException blankKeys = assertThrows(IllegalArgumentException) {
            effective([], "ACME_LIVE_BUILT_IN", noLongerFilters, null, [null, " "], true)
        }
        assertEquals(keyless.message.replace("ACME_LIVE", "ACME_LIVE_BUILT_IN"), blankKeys.message, "blank key fields are no key")
        assertEquals(KeepPlanSupport.todaysRead([]), effective([], "ACME_LIVE_PLAIN", noLongerFilters, "records", [], true), "no rules: nothing to filter")

        // keepInSpark false with rules
        IllegalArgumentException noKeep = assertThrows(IllegalArgumentException) { effective(rows, "ACME_LIVE", noLongerFilters, "records", ["orderId"], false) }
        assertEquals("File 2 (ACME_LIVE) cannot be filtered: its source no longer filters at read and this run does not run Keep, " +
                "so Keep cannot run in Spark.", noKeep.message)
        IllegalArgumentException both = assertThrows(IllegalArgumentException) {
            KeepPlanSupport.effectiveSideKeep(ec, rows, connector("ACME_LIVE"), [:], noLongerFilters, null, nested, false)
        }
        assertEquals("The ACME_LIVE side cannot be filtered: its source no longer filters at read and this run does not run Keep and " +
                "its records are not top-level, so Keep cannot run in Spark.", both.message, "a caller that names no side")

        // no rules: nothing to filter, so it passes and reads as today with no filters
        Map nothing = KeepPlanSupport.todaysRead([])
        assertEquals(nothing, effective([], "ACME_LIVE_PLAIN", noLongerFilters, null, nested, true))
        assertEquals(nothing, effective([], "ACME_LIVE_PLAIN", noLongerFilters, "records", ["orderId"], false))
        assertEquals(nothing, effective(null, "ACME_LIVE_PLAIN", noLongerFilters, "orders", ["orderId"], false))

        // the extract still declares the parameter: today's read, unchanged, whatever Spark could do
        Map today = KeepPlanSupport.todaysRead(rows)
        assertEquals(today, effective(rows, "ACME_LIVE_FILTERING", stillFilters, null, nested, true))
        assertEquals(today, effective(rows, "ACME_LIVE_FILTERING", stillFilters, "records", ["orderId"], false))
        assertEquals(today, effective(rows, "ACME_LIVE", null, null, nested, false), "an extract that is not installed")

        // data its read already filtered (a retried run's landed file, not read again): today's read, never the error
        File ruled = landed("ruled", [metadata: [filters: [configuredExclusions: [[sequenceNum: 1, fieldExpression: "other",
                operator: "EXCLUDE_IN", values: ["X"], excludedCount: 1, fieldAbsentCount: 0]]]], records: [[orderId: "O1"]]])
        assertEquals(today, KeepPlanSupport.effectiveSideKeep(ec, rows, connector("ACME_LIVE"), [:], noLongerFilters, null, nested, false,
                ruled, "FILE_2"))
        // Task 16e round 2 (review 16d-M6): a capture whose read applied only its built-in drops reports no configuredExclusions;
        // its records carry no lineKind (the flag the built-in line reads, which a Spark-era extractor writes), so it was filtered
        assertEquals(KeepPlanSupport.todaysRead([]), KeepPlanSupport.effectiveSideKeep(ec, [], connector("ACME_LIVE_BUILT_IN"), [:],
                noLongerFilters, "records", ["orderId"], true, builtInCapture))
        assertEquals(KeepPlanSupport.todaysRead([]), KeepPlanSupport.effectiveSideKeep(ec, [], connector("ACME_LIVE_BUILT_IN"), [:],
                noLongerFilters, null, nested, true, builtInCapture, "FILE_2"), "a nested side over it does not fail")
        // a Spark-era landed file flags a record: not filtered, so the gate is weighed as for a live read
        assertThrows(IllegalArgumentException) {
            KeepPlanSupport.effectiveSideKeep(ec, [], connector("ACME_LIVE_BUILT_IN"), [:], noLongerFilters, null, nested, true, sparkEra, "FILE_2")
        }
        assertEquals("SPARK", KeepPlanSupport.effectiveSideKeep(ec, [], connector("ACME_LIVE_BUILT_IN"), [:], noLongerFilters, "records",
                ['$.records[*].orderId'], true, sparkEra).placement)

        // control: Spark can keep it, so it keeps in Spark
        Map spark = effective(rows, "ACME_LIVE", noLongerFilters, "records", ['$.records[*].orderId'], true)
        assertEquals("SPARK", spark.placement)
        assertEquals(["BUILT_IN:1", "BUILT_IN:2", "A:1"], spark.rules*.id)
    }

    /** Review Focus 4: a scheduled read for tenant A never sees tenant B's filter rows, with no active tenant. */
    @Test
    void aScopeOfAnotherTenantReadsAsNoRules() {
        assertEquals([], KeepPlanSupport.tenantRuleRows(ec, "KP_SCOPE_B", "FILE_1", A))
        assertEquals(["secretField"], KeepPlanSupport.tenantRuleRows(ec, "KP_SCOPE_B", "FILE_1", B)*.fieldExpression,
                "the owner reads its row (control)")
        assertEquals([], KeepPlanSupport.tenantRuleRows(ec, "KP_SCOPE_A", "FILE_1", B))

        Map plan = placedFor("KP_SCOPE_B", "FILE_1", A, "ACME_QUIET")
        assertEquals([], plan.rules)
        assertEquals([] as Set, plan.fields)
    }

    @Test
    void aBlankTenantIsRefused() {
        [null, "", "  "].each { String tenant ->
            assertThrows(IllegalArgumentException) { KeepPlanSupport.tenantRuleRows(ec, "KP_SCOPE_A", "FILE_1", tenant) }
            assertThrows(IllegalArgumentException) { placedFor("KP_SCOPE_A", "FILE_1", tenant, "ACME") }
            assertThrows(IllegalArgumentException) { placedFor("KP_SCOPE_A", "FILE_1", tenant, "ACME_UPLOAD") }
        }
    }

    @Test
    void builtInsComeBeforeTheTenantsRules() {
        Map plan = placedFor("KP_SCOPE_A", "FILE_1", A, "ACME")
        assertEquals(["BUILT_IN:1", "BUILT_IN:2", "A:1", "A:2"], plan.rules*.id)
        assertEquals(["BUILT_IN", "BUILT_IN", "TENANT", "TENANT"], plan.rules*.source)
        assertEquals(["Cancelled records", "Draft records", "orderType", "channel"], plan.rules*.label,
                "a tenant rule's label is its field, as _excludedBy names it today")
        assertEquals(["isCancelled", "stage", "orderType", "channel"] as Set, plan.fields)
        KeepRules.validate(plan.rules)

        Map include = plan.rules[3]
        assertEquals(["FIELD_ABSENT", "VALUE"], include.tests*.reason, "an include rule rejects no value first, then an unlisted value")
        assertEquals([kind: "compare", field: "channel", text: "GROOVY", op: "NOT_IN", values: ["WEB", "POS"], valueKind: "TEXT", fold: "A_UPPER"],
                include.tests[1].test)

        Map skipped = placedFor("KP_SCOPE_A", "FILE_1", A, "ACME", [onlyCancelled: true])
        assertEquals(["BUILT_IN:2", "A:1", "A:2"], skipped.rules*.id, "an entry is skipped when the side sets its appliesUnless option")

        // rows handed in directly in getter shape (what tenantRuleRows reads) plan the same as rows read from the store
        List<Map<String, Object>> automationRows = [[sequenceNum: 1, fieldExpression: "kind", operator: "INCLUDE_PREFIX", filterValues: "EXC-"]]
        Map automation = placed(automationRows, "ACME")
        assertEquals(["BUILT_IN:1", "BUILT_IN:2", "A:1"], automation.rules*.id)
        assertEquals([kind: "not", of: [kind: "compare", field: "kind", text: "GROOVY", op: "STARTS_WITH_ANY", values: ["EXC-"],
                                        valueKind: "TEXT", fold: "A_UPPER"]], automation.rules[2].tests[1].test)
        Map automationRead = placed(automationRows, "ACME_DEFINED")
        assertEquals(automationRows, automationRead.readFilters)
        assertEquals([], automationRead.rules)
    }

    @Test
    void writeSidecarFollowsTheConnectorRow() {
        assertTrue(placedFor("KP_SCOPE_A", "FILE_1", A, "ACME").writeSidecar as boolean)
        assertFalse(placedFor("KP_SCOPE_A", "FILE_1", A, "ACME_QUIET").writeSidecar as boolean)
        assertFalse(placedFor("KP_SCOPE_A", "FILE_1", A, "ACME_DEFINED").writeSidecar as boolean)
        assertFalse(placedFor("KP_SCOPE_A", "FILE_1", A, "ACME_UPLOAD").writeSidecar as boolean)
    }

    /** Built-ins are data on a connector row (Ruling 3b2b-12): a made-up system's lines compile with no system component. */
    @Test
    void aMadeUpSystemsDeclaredKeepLinesCompile() {
        assertNull(ec.entity.find(SourceSystemConnectorSupport.ENTITY_NAME).condition("systemEnumId", "ACME_ABSENT").one())
        Map plan = placed([], "ACME")
        assertEquals([
                [id: "BUILT_IN:1", sequenceNum: 1, label: "Cancelled records", source: "BUILT_IN", tests: [[reason: "BUILT_IN",
                        test: [kind: "compare", field: "isCancelled", text: "FACT", op: "EQ", values: ["true"], valueKind: "BOOLEAN", fold: "NONE"]]]],
                [id: "BUILT_IN:2", sequenceNum: 2, label: "Draft records", source: "BUILT_IN", tests: [[reason: "BUILT_IN",
                        test: [kind: "compare", field: "stage", text: "FACT", op: "EQ", values: ["DRAFT"], valueKind: "TEXT", fold: "NONE"]]]],
        ], plan.rules)
        assertEquals(["isCancelled", "stage"], KeepRules.injections(plan.rules)*.field)

        IllegalArgumentException refused = assertThrows(IllegalArgumentException) {
            placed([], "ACME_BAD")
        }
        assertTrue(refused.message.contains("Has lines"), "a refused declared line is a data error naming the entry: ${refused.message}")
    }
}
