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
}
