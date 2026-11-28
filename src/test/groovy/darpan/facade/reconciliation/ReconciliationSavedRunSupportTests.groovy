package darpan.facade.reconciliation

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Characterization tests for the pure/branch-heavy helpers of ReconciliationSavedRunSupport
 * (MACH P1: pin current behavior before the decomposition of the large facade files).
 * No ExecutionContext — every method under test here is ec-free.
 */
class ReconciliationSavedRunSupportTests {

    // ─── canonicalSystemEnumId: alias collapse per system ───────────────────────

    @Test
    void canonicalSystemEnumIdCollapsesAliases() {
        ["DAR_SYS_OMS", "Hot-Wax", "oms", "HOTWAX", "hot wax"].each {
            assertEquals("OMS", ReconciliationSavedRunSupport.canonicalSystemEnumId(it), "alias ${it}")
        }
        ["DarSysShopify", "shopify", "SHOPIFY"].each {
            assertEquals("SHOPIFY", ReconciliationSavedRunSupport.canonicalSystemEnumId(it), "alias ${it}")
        }
        assertEquals("NETSUITE", ReconciliationSavedRunSupport.canonicalSystemEnumId("Dar_Sys_NetSuite"))
        assertEquals("SAPI", ReconciliationSavedRunSupport.canonicalSystemEnumId("sapi"))
    }

    @Test
    void canonicalSystemEnumIdPassesThroughUnknownAndRejectsBlank() {
        assertEquals("CUSTOM_X", ReconciliationSavedRunSupport.canonicalSystemEnumId("CUSTOM_X"))
        assertNull(ReconciliationSavedRunSupport.canonicalSystemEnumId(null))
        assertNull(ReconciliationSavedRunSupport.canonicalSystemEnumId("   "))
    }

    // ─── normalizeRuleEntries ────────────────────────────────────────────────────

    @Test
    void normalizeRuleEntriesAssignsSequenceAndDefaultsEnabled() {
        List<Map<String, Object>> entries = ReconciliationSavedRunSupport.normalizeRuleEntries([
                [ruleText: "status is 'Pending'"],
                [ruleLogic: "logic-2", enabled: "n"],
                [ruleText: "third", sequenceNum: "77"],
        ])
        assertEquals(3, entries.size())
        assertEquals(10, entries[0].sequenceNum)
        assertEquals("Y", entries[0].enabled)
        assertEquals(20, entries[1].sequenceNum)   // auto-sequence advances only on added entries
        assertEquals("N", entries[1].enabled)
        assertEquals(77, entries[2].sequenceNum)   // explicit sequence wins
    }

    @Test
    void normalizeRuleEntriesDropsEmptyAndNonMapEntries() {
        List<Map<String, Object>> entries = ReconciliationSavedRunSupport.normalizeRuleEntries([
                [severity: "HIGH"],          // no ruleText/ruleLogic -> dropped
                "not-a-map",                 // dropped
                [ruleText: "kept"],
        ])
        assertEquals(1, entries.size())
        assertEquals("kept", entries[0].ruleText)
        assertEquals([], ReconciliationSavedRunSupport.normalizeRuleEntries("not-a-collection"))
        assertEquals([], ReconciliationSavedRunSupport.normalizeRuleEntries(null))
    }

    @Test
    void toSequenceNumParsesNumbersAndRejectsGarbage() {
        assertEquals(5, ReconciliationSavedRunSupport.toSequenceNum(5))
        assertEquals(7, ReconciliationSavedRunSupport.toSequenceNum("7"))
        assertNull(ReconciliationSavedRunSupport.toSequenceNum("x"))
        assertNull(ReconciliationSavedRunSupport.toSequenceNum(null))
    }

    // ─── FIELD_COMPARISON server-side regeneration (left-as-is branches) ─────────

    @Test
    void regenerateFieldComparisonLeavesNonFieldComparisonAndMalformedEntriesAlone() {
        // NB: entries reach this method AFTER normalizeRuleEntries, so they are always Maps —
        // the non-Map passthrough branch is unreachable in the real pipeline (and its typed-list
        // cast would throw for a String, which this characterization deliberately does not pin).
        List entries = [
                [ruleType: "OTHER", expression: '{"a":1}', ruleLogic: "client-logic"],
                [ruleType: "FIELD_COMPARISON", expression: "", ruleLogic: "kept-when-no-expression"],
        ]
        List result = ReconciliationSavedRunSupport.regenerateFieldComparisonRuleLogic(entries, '$.id', '$.id')
        assertEquals("client-logic", result[0].ruleLogic)
        assertEquals("kept-when-no-expression", result[1].ruleLogic)
        // null/empty input passes through
        assertNull(ReconciliationSavedRunSupport.regenerateFieldComparisonRuleLogic(null, null, null))
    }

    // ─── preAction normalization ─────────────────────────────────────────────────

    @Test
    void normalizePreActionMapsLegacyNamesAndRejectsUnknown() {
        assertEquals("STRING_TO_INT", ReconciliationSavedRunSupport.normalizePreAction("TO_INT"))
        assertEquals("STRING_TO_INT", ReconciliationSavedRunSupport.normalizePreAction("string_to_integer"))
        assertEquals("STRING_TO_NUMBER", ReconciliationSavedRunSupport.normalizePreAction("to_number"))
        assertEquals("STRING_TO_NUMBER", ReconciliationSavedRunSupport.normalizePreAction("STRING_TO_NUMBER"))
        assertNull(ReconciliationSavedRunSupport.normalizePreAction("DROP_TABLES"))
        assertNull(ReconciliationSavedRunSupport.normalizePreAction(null))
    }

    @Test
    void normalizePreActionFieldSideAcceptsAliases() {
        assertEquals("file1", ReconciliationSavedRunSupport.normalizePreActionFieldSide("LEFT"))
        assertEquals("file1", ReconciliationSavedRunSupport.normalizePreActionFieldSide("file_1"))
        assertEquals("file2", ReconciliationSavedRunSupport.normalizePreActionFieldSide("Right"))
        assertNull(ReconciliationSavedRunSupport.normalizePreActionFieldSide("middle"))
    }

    @Test
    void normalizePreActionsExpandsScalarsToBothSidesAndDedupes() {
        List<Map<String, String>> fromScalar = ReconciliationSavedRunSupport.normalizePreActions("TO_INT")
        assertEquals([[fieldSide: "file1", action: "STRING_TO_INT"], [fieldSide: "file2", action: "STRING_TO_INT"]], fromScalar)

        List<Map<String, String>> mixed = ReconciliationSavedRunSupport.normalizePreActions([
                [action: "TO_NUMBER", fieldSide: "LEFT"],
                [preAction: "TO_NUMBER", side: "left"],   // duplicate after normalization
                [action: "BOGUS", fieldSide: "file2"],    // invalid action -> dropped
        ])
        assertEquals([[fieldSide: "file1", action: "STRING_TO_NUMBER"]], mixed)

        assertNull(ReconciliationSavedRunSupport.normalizePreActions(null))
        assertNull(ReconciliationSavedRunSupport.normalizePreActions(["NOT_AN_ACTION"]))
    }

    // ─── misc pure helpers ───────────────────────────────────────────────────────

    @Test
    void parseRuleExpressionReturnsEmptyMapOnAnythingButAJsonObject() {
        assertEquals([field: "status"], ReconciliationSavedRunSupport.parseRuleExpression('{"field":"status"}'))
        assertEquals([:], ReconciliationSavedRunSupport.parseRuleExpression('[1,2]'))
        assertEquals([:], ReconciliationSavedRunSupport.parseRuleExpression('not json'))
        assertEquals([:], ReconciliationSavedRunSupport.parseRuleExpression("   "))
    }

    @Test
    void compareScopeDisplayNamePrefersDescription() {
        assertEquals("Orders scope", ReconciliationSavedRunSupport.compareScopeDisplayName("CS_1", "Orders scope"))
        assertEquals("CS_1", ReconciliationSavedRunSupport.compareScopeDisplayName("CS_1", "  "))
    }

    @Test
    void savedRunMatchesSearchesNamesIdsAndSystemOptionLabels() {
        Map<String, Object> row = [
                savedRunId   : "RS_ORDERS",
                runName      : "Daily Orders",
                systemOptions: [[label: "Shopify Orders", enumCode: "SHOPIFY"]],
        ]
        assertTrue(ReconciliationSavedRunSupport.savedRunMatches(row, "daily"))
        assertTrue(ReconciliationSavedRunSupport.savedRunMatches(row, "rs_orders"))
        assertTrue(ReconciliationSavedRunSupport.savedRunMatches(row, "shopify"))
        assertFalse(ReconciliationSavedRunSupport.savedRunMatches(row, "netsuite"))
    }

    @Test
    void normalizeJsonPrimaryIdExpressionPreservesNormalizerSuffix() {
        String withSuffix = ReconciliationSavedRunSupport.normalizeJsonPrimaryIdExpression('$.data.orders[0].id|SHOPIFY_GID_TAIL')
        assertTrue(withSuffix.endsWith("|SHOPIFY_GID_TAIL"), withSuffix)
        assertFalse(withSuffix.startsWith("|"), withSuffix)
        assertNull(ReconciliationSavedRunSupport.normalizeJsonPrimaryIdExpression("  "))
    }

    @Test
    void topLevelRecordFieldDerivesTheFieldAnExpressionReads() {
        assertEquals("externalId", ReconciliationSavedRunSupport.topLevelRecordField('$.records[*].externalId'))
        assertEquals("externalId", ReconciliationSavedRunSupport.topLevelRecordField('$.records[*].externalId|SHOPIFY_GID_TAIL'))
        assertEquals("orderItems", ReconciliationSavedRunSupport.topLevelRecordField('$.records[*].orderItems[*].sku'))
        assertEquals("id", ReconciliationSavedRunSupport.topLevelRecordField("id"))
        assertEquals("externalId", ReconciliationSavedRunSupport.topLevelRecordField('$.externalId'))
        assertNull(ReconciliationSavedRunSupport.topLevelRecordField(null))
        assertNull(ReconciliationSavedRunSupport.topLevelRecordField("   "))
    }

    // ─── ruleReadFieldsForSide (DAR-BE-239): what a side's rules read, never a projection ────

    @Test
    void ruleReadFieldsNameWhatThisSidesEnabledRulesRead() {
        List<Map<String, Object>> ruleRows = [
                [file1FieldPath: '$.records[*].isFulfilled', file2FieldPath: '$.records[*].isCompleted', enabled: 'Y'],
                [file1FieldPath: '$.records[*].paymentTransactionIds', file2FieldPath: '$.records[*].paymentRefNums'],
                [file1FieldPath: '$.records[*].hasPaymentTransaction', file2FieldPath: '$.records[*].hasPaymentPreference', enabled: 'N'],
                // A rule naming no path on this side, or a nested one, never turns the read off the way a projection would.
                [ruleLogic: 'rule "opaque" when then end'],
                [file1FieldPath: '$.records[*].lines[*].sku', file2FieldPath: '$.records[*].items[*].sku'],
                [file2FieldPath: '$.records[*].isCompleted'],
        ]
        assertEquals(['isCompleted', 'paymentRefNums', 'items'],
                ReconciliationSavedRunSupport.ruleReadFieldsForSide(ruleRows, 'FILE_2'))
        assertEquals(['isFulfilled', 'paymentTransactionIds', 'lines'],
                ReconciliationSavedRunSupport.ruleReadFieldsForSide(ruleRows, 'file_1'))
        assertEquals([], ReconciliationSavedRunSupport.ruleReadFieldsForSide(ruleRows, 'BOTH'))
    }

    // ─── ruleKeepFieldsForSide: rule-referenced fields for extract projection ────
    // Review fix (task-7-report.md Fix Report): the gate is the side's own primaryIdExpression
    // record-array prefix (e.g. "records[*]" from "$.records[*].orderId"), not merely "did
    // topLevelRecordField return something" — that helper strips up to the FIRST "[*]"
    // unconditionally and cannot tell a genuinely nested per-record wildcard from the outer
    // records-array wildcard. '$.records[*].orderId' is the standard OMS-side primary used below
    // wherever the test needs a real prefix to check a wildcarded path against.

    private static final String TEST_PRIMARY_ID_EXPRESSION = '$.records[*].orderId'

    @Test
    void ruleKeepFieldsCollectTopLevelFieldsForTheRequestedSide() {
        List<Map<String, Object>> ruleRows = [
                [ruleId: "R1", file1FieldPath: '$.statusId', file2FieldPath: '$.status'],
                [ruleId: "R2", file1FieldPath: '$.grandTotal', file2FieldPath: '$.totalAmount'],
        ]

        assertEquals(["statusId", "grandTotal"],
                ReconciliationSavedRunSupport.ruleKeepFieldsForSide(ruleRows, "FILE_1", TEST_PRIMARY_ID_EXPRESSION))
        assertEquals(["status", "totalAmount"],
                ReconciliationSavedRunSupport.ruleKeepFieldsForSide(ruleRows, "FILE_2", TEST_PRIMARY_ID_EXPRESSION))
    }

    @Test
    void ruleKeepFieldsReturnNullWhenAPathCannotBeReducedToATopLevelField() {
        List<Map<String, Object>> ruleRows = [
                [ruleId: "R1", file1FieldPath: '$.statusId', file2FieldPath: '$.status'],
                // $.shipGroups[*].facilityId is a genuinely nested per-record wildcard, not the outer
                // records[*] boundary TEST_PRIMARY_ID_EXPRESSION establishes — the prefix gate rejects
                // it even though topLevelRecordField('$.shipGroups[*].facilityId') alone would return
                // "facilityId" (a plausible-looking but wrong top-level field name).
                [ruleId: "R2", file1FieldPath: '$.shipGroups[*].facilityId', file2FieldPath: '$.facility'],
        ]

        // Projection must never drop a field a rule reads: a rule evaluating against an absent field
        // reports false differences, which is worse than a large extract.
        assertNull(ReconciliationSavedRunSupport.ruleKeepFieldsForSide(ruleRows, "FILE_1", TEST_PRIMARY_ID_EXPRESSION))
        assertEquals(["status", "facility"],
                ReconciliationSavedRunSupport.ruleKeepFieldsForSide(ruleRows, "FILE_2", TEST_PRIMARY_ID_EXPRESSION))
    }

    @Test
    void ruleKeepFieldsPinsRealNestedWildcardShapesToNull() {
        // Regression lock: both shapes are real, not hypothetical. $[*].orderItems[*].sku is the
        // file-2 convention used throughout src/test/resources/reconciliation/rule/rulelogic-goldens.json —
        // normalizeSparkPath consumes the leading "$[*]" record root first, so the FIRST *remaining*
        // wildcard is always the nested one, and topLevelRecordField alone would return "sku".
        List<Map<String, Object>> ruleRows = [
                [ruleId: "R1", file1FieldPath: '$.shipGroups[*].facilityId'],
                [ruleId: "R2", file2FieldPath: '$[*].orderItems[*].sku'],
        ]

        assertNull(ReconciliationSavedRunSupport.ruleKeepFieldsForSide(ruleRows, "FILE_1", TEST_PRIMARY_ID_EXPRESSION))
        assertNull(ReconciliationSavedRunSupport.ruleKeepFieldsForSide(ruleRows, "FILE_2", TEST_PRIMARY_ID_EXPRESSION))
    }

    @Test
    void ruleKeepFieldsIgnoreRowsWithNoPathForThatSide() {
        List<Map<String, Object>> ruleRows = [
                [ruleId: "R1", file1FieldPath: '$.statusId'],
        ]

        assertEquals(["statusId"], ReconciliationSavedRunSupport.ruleKeepFieldsForSide(ruleRows, "FILE_1", TEST_PRIMARY_ID_EXPRESSION))
        assertEquals([], ReconciliationSavedRunSupport.ruleKeepFieldsForSide(ruleRows, "FILE_2", TEST_PRIMARY_ID_EXPRESSION))
    }

    @Test
    void ruleKeepFieldsReturnNullWhenARuleCarriesNoPathForEitherSide() {
        // parseRuleExpression returns [:] for a blank/unparseable expression (e.g. a bare-condition
        // ruleLogic with no structured file1FieldPath/file2FieldPath), so collectRuleRows can hand
        // back a row with neither path. Its safety can't be verified, so it disables projection —
        // unlike a row naming only the OTHER side (ruleKeepFieldsIgnoreRowsWithNoPathForThatSide
        // above), which makes no claim about this side and is skipped.
        List<Map<String, Object>> ruleRows = [
                [ruleId: "R1", file1FieldPath: '$.statusId', file2FieldPath: '$.status'],
                [ruleId: "R2"],
        ]

        assertNull(ReconciliationSavedRunSupport.ruleKeepFieldsForSide(ruleRows, "FILE_1", TEST_PRIMARY_ID_EXPRESSION))
        assertNull(ReconciliationSavedRunSupport.ruleKeepFieldsForSide(ruleRows, "FILE_2", TEST_PRIMARY_ID_EXPRESSION))
    }

    @Test
    void ruleKeepFieldsReturnNullForAnUnrecognizedFileSide() {
        List<Map<String, Object>> ruleRows = [
                [ruleId: "R1", file1FieldPath: '$.statusId', file2FieldPath: '$.status'],
        ]

        assertNull(ReconciliationSavedRunSupport.ruleKeepFieldsForSide(ruleRows, "FILE_3", TEST_PRIMARY_ID_EXPRESSION))
        assertNull(ReconciliationSavedRunSupport.ruleKeepFieldsForSide(ruleRows, null, TEST_PRIMARY_ID_EXPRESSION))
    }
    /**
     * DAR-BE-115: save#RuleSetRun drops a connector source's record root, so a seeded root "records" + bare
     * "tranId" was saved back as bare "tranId" and Spark compared the {metadata, records} envelope. The root
     * is folded into the id instead, the form app-created runs already store.
     */
    @Test
    void aConnectorRootIsFoldedIntoABareIdExpression() {
        assertEquals('$.records[*].tranId', ReconciliationSavedRunSupport.foldConnectorRecordRoot("records", "tranId"))
        assertEquals('$.records[*].tranId|trim', ReconciliationSavedRunSupport.foldConnectorRecordRoot("records", "tranId|trim"))
    }

    @Test
    void anAbsoluteIdOrAMissingRootIsLeftAsItIs() {
        assertEquals('$.records[*].tranId', ReconciliationSavedRunSupport.foldConnectorRecordRoot("records", '$.records[*].tranId'))
        assertEquals("tranId", ReconciliationSavedRunSupport.foldConnectorRecordRoot(null, "tranId"))
        assertNull(ReconciliationSavedRunSupport.foldConnectorRecordRoot("records", null))
    }

    // ─── OC18: a verify check that did not finish (DAR-BE-362, errors-warnings-to-alerts-room-only) ──────

    @Test
    void anUnfinishedCheckTellsTheRunAWarningWithNoErrorTextInIt() {
        String warning = ReconciliationSavedRunSupport.checkIncompleteWarning("Exchange presence check")
        assertEquals("Exchange presence check did not finish; this run's results may be incomplete.", warning)
    }

    @Test
    void theAlertNamesTheTenantRunAndCheckAndCarriesTheRawErrorCapped() {
        String raw = "Connection refused: oms.example.com:443 " + ("x" * 400)
        String text = ReconciliationSavedRunSupport.checkFailureAlertText(
                [companyUserGroupId: "GORJANA", runName: "API Order Sync", reconciliationRunResultId: "RR_100"],
                "Return presence check", [raw, "second error"])
        assertTrue(text.startsWith("Return presence check did not finish · GORJANA · API Order Sync · run RR_100: Connection refused"), text)
        assertTrue(text.contains("Connection refused: oms.example.com:443"), "the raw error goes to Darpan's own room")
        assertTrue(text.length() <= text.indexOf(": Connection") + 2 + darpan.ops.ErrorChatSupport.ERROR_MAX_CHARS, "capped: ${text.length()}")
    }

    @Test
    void noRawErrorsMeansNoAlertText() {
        assertNull(ReconciliationSavedRunSupport.checkFailureAlertText([:], "Exchange presence check", []))
        assertNull(ReconciliationSavedRunSupport.checkFailureAlertText([:], "Exchange presence check", null))
    }

    /** The manual run path folds no ec.message error and no exception text into the warnings a client's room is sent. */
    @Test
    void runSavedRunDiffKeepsRawCheckErrorsOutOfItsWarnings() {
        String code = ReconciliationSmokeTestSupport.resolveBackendRoot()
                .resolve("runtime/component/darpan/src/main/groovy/darpan/facade/reconciliation/runSavedRunDiff.groovy").toFile().text
                .replaceAll(/(?s)\/\*.*?\*\//, " ").split("\n").findAll { !it.trim().startsWith("//") }.join("\n")
        assertFalse((code =~ /warnings[^\n]*getErrors\(\)/).find(), "ec.message errors folded into warnings")
        assertFalse((code =~ /warnings[^\n]*\bt\.message\b|check failed: \$\{/).find(), "exception text folded into warnings")
        assertEquals(2, (code =~ /ReconciliationSavedRunSupport\.alertCheckFailure\(/).size(), "both checks send their raw errors to Darpan's alerts room")
    }

    // ─── OC19: a manual run that compared nothing closes as the scheduled path does (DAR-BE-390/393) ──────────────

    @Test
    void bothSidesReadNothingIsAQuietDay() {
        Map close = ReconciliationSavedRunSupport.manualNoDataClose("hotwax", "shopify", [recordCount: 0], [recordCount: 0], null, false)
        assertNotNull(close, "a run that read nothing closes as no data")
        assertNull(close.finding, "both sides empty is a quiet day, not a run error")
    }

    @Test
    void oneSideReadNothingIsARunErrorNotASuccess() {
        Map close = ReconciliationSavedRunSupport.manualNoDataClose("hotwax", "shopify", [recordCount: 0], [recordCount: 412], null, false)
        assertNotNull(close, "one empty side closes the run before the compare, as the scheduled path does")
        assertEquals("ONE_SIDE_EMPTY", close.finding.kind)
        assertEquals("The hotwax side read no records.", close.finding.reason)
    }

    @Test
    void twoSidesWithRecordsCompare() {
        assertNull(ReconciliationSavedRunSupport.manualNoDataClose("hotwax", "shopify", [recordCount: 3], [recordCount: 5], null, false))
        assertNull(ReconciliationSavedRunSupport.manualNoDataClose("hotwax", "shopify", [recordCount: null], [recordCount: 5], null, false),
                "an unknown count is not an empty side")
    }

    @Test
    void aSideWhoseKeepKeptNothingClosesAfterTheCompare() {
        Map compare = [keepSummaryBySide: [file1: [keptCount: 3, setAsideCount: 0], file2: [keptCount: 0, setAsideCount: 7]]]
        Map close = ReconciliationSavedRunSupport.manualNoDataClose("hotwax", "shopify", [recordCount: 3], [recordCount: 7], compare, false)
        assertNotNull(close)
        assertEquals("KEPT_NOTHING", close.finding.kind)
        assertEquals("The shopify side kept 0 of 7 records.", close.finding.reason)
        assertNull(ReconciliationSavedRunSupport.manualNoDataClose("hotwax", "shopify", [recordCount: 3], [recordCount: 7],
                [keepSummaryBySide: [file1: [keptCount: 3], file2: [keptCount: 2, setAsideCount: 5]]], false), "both sides kept records")
    }

    @Test
    void anEvaluateRunJudgesItsOneSide() {
        Map empty = ReconciliationSavedRunSupport.manualNoDataClose("hotwax", null, [recordCount: 0], null, null, true)
        assertNotNull(empty)
        assertNull(empty.finding, "an evaluate that read nothing is a quiet day")
        Map keptNothing = ReconciliationSavedRunSupport.manualNoDataClose("hotwax", null, [recordCount: 4], null,
                [keepSummaryBySide: [file1: [keptCount: 0, setAsideCount: 4]]], true)
        assertEquals("KEPT_NOTHING", keptNothing.finding.kind)
        assertNull(ReconciliationSavedRunSupport.manualNoDataClose("hotwax", null, [recordCount: 4], null, null, true))
    }

    /** A no-data manual run is closed NO_DATA before anyone is told, and is told by notifySavedRunNoData, never the completion message. */
    @Test
    void runSavedRunDiffClosesNoDataBeforeItNotifies() {
        String code = ReconciliationSmokeTestSupport.resolveBackendRoot()
                .resolve("runtime/component/darpan/src/main/groovy/darpan/facade/reconciliation/runSavedRunDiff.groovy").toFile().text
                .replaceAll(/(?s)\/\*.*?\*\//, " ").split("\n").findAll { !it.trim().startsWith("//") }.join("\n")
        int complete = code.indexOf("RunObservability.STATUS_NO_DATA")
        int notify = code.indexOf("AutomationExecutionSupport.notifySavedRunNoData(")
        assertTrue(complete > 0 && notify > complete, "completeRun(NO_DATA) comes before notifySavedRunNoData")
        assertTrue((code =~ /ReconciliationSavedRunSupport\.manualNoDataClose\(/).size() >= 2, "the API and evaluate branches both ask the judge")
        assertTrue(code.contains("!obsNoData || obsNoDataRulesFailed"), "the completion notify skips a no-data run whose rules did not fail")
    }
}
