package darpan.facade.reconciliation

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
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
}
