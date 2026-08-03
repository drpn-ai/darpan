package darpan.reconciliation.source

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

class SourceFilterSupportTests {

    private static List<Map<String, Object>> channelRule(String... values) {
        return SourceFilterSupport.parseRules([[
                sequenceNum    : 1,
                fieldExpression: "salesChannelEnumId",
                operator       : "EXCLUDE_IN",
                filterValues   : values.join(","),
        ]])
    }

    @Test
    void noRulesMeansNoFiltering() {
        assertEquals([], SourceFilterSupport.parseRules(null))
        assertEquals([], SourceFilterSupport.parseRules([]))
        assertNull(SourceFilterSupport.firstMatchingRule([salesChannelEnumId: "POS_SALES_CHANNEL"], []))
        assertNull(SourceFilterSupport.firstMatchingRule([salesChannelEnumId: "POS_SALES_CHANNEL"], null))
    }

    @Test
    void parsesCommaSeparatedValuesAndKeepsOriginalCasingForMetadata() {
        List<Map<String, Object>> rules = channelRule("POS_SALES_CHANNEL", "DRAFT_SALES_CHANNEL")

        assertEquals(1, rules.size())
        assertEquals(1, rules[0].sequenceNum)
        assertEquals("salesChannelEnumId", rules[0].fieldExpression)
        assertEquals("EXCLUDE_IN", rules[0].operator)
        assertEquals(["POS_SALES_CHANNEL", "DRAFT_SALES_CHANNEL"], rules[0].values)
        assertTrue(((Set) rules[0].matchValues).contains("POS_SALES_CHANNEL"))
    }

    @Test
    void acceptsValuesAsListAndTrimsBlanks() {
        List<Map<String, Object>> rules = SourceFilterSupport.parseRules([[
                sequenceNum    : 1,
                fieldExpression: " salesChannelEnumId ",
                values         : ["  POS_SALES_CHANNEL ", "", "  "],
        ]])

        assertEquals("salesChannelEnumId", rules[0].fieldExpression)
        assertEquals(["POS_SALES_CHANNEL"], rules[0].values)
    }

    @Test
    void matchesConfiguredValueCaseInsensitively() {
        List<Map<String, Object>> rules = channelRule("POS_SALES_CHANNEL")

        assertNotNull(SourceFilterSupport.firstMatchingRule([salesChannelEnumId: "pos_sales_channel"], rules))
        assertNotNull(SourceFilterSupport.firstMatchingRule([salesChannelEnumId: " POS_SALES_CHANNEL "], rules))
    }

    @Test
    void fieldNameIsCaseSensitiveLikeTheBuiltInFilters() {
        List<Map<String, Object>> rules = channelRule("POS_SALES_CHANNEL")

        // Mirrors OmsRestSourceSupport.isSalesOrder: normalize(key) == fieldName (trim, no case fold).
        assertNull(SourceFilterSupport.firstMatchingRule([SALESCHANNELENUMID: "POS_SALES_CHANNEL"], rules))
        assertNotNull(SourceFilterSupport.firstMatchingRule([" salesChannelEnumId ": "POS_SALES_CHANNEL"], rules))
    }

    @Test
    void recordMissingTheFieldIsKept() {
        List<Map<String, Object>> rules = channelRule("POS_SALES_CHANNEL")

        assertNull(SourceFilterSupport.firstMatchingRule([orderId: "10001"], rules))
        assertNull(SourceFilterSupport.firstMatchingRule([salesChannelEnumId: null], rules))
        assertNull(SourceFilterSupport.firstMatchingRule([salesChannelEnumId: "  "], rules))
    }

    @Test
    void nonMapRecordNeverMatches() {
        assertNull(SourceFilterSupport.firstMatchingRule("not-a-record", channelRule("POS_SALES_CHANNEL")))
        assertNull(SourceFilterSupport.firstMatchingRule(null, channelRule("POS_SALES_CHANNEL")))
    }

    @Test
    void firstMatchingRuleWinsSoACountIsAttributedOnce() {
        List<Map<String, Object>> rules = SourceFilterSupport.parseRules([
                [sequenceNum: 1, fieldExpression: "salesChannelEnumId", filterValues: "POS_SALES_CHANNEL"],
                [sequenceNum: 2, fieldExpression: "statusId", filterValues: "ORDER_CANCELLED"],
        ])

        Map<String, Object> matched = SourceFilterSupport.firstMatchingRule(
                [salesChannelEnumId: "POS_SALES_CHANNEL", statusId: "ORDER_CANCELLED"], rules)

        assertEquals(1, matched.sequenceNum)
    }

    @Test
    void secondRuleMatchesWhenFirstDoesNot() {
        List<Map<String, Object>> rules = SourceFilterSupport.parseRules([
                [sequenceNum: 1, fieldExpression: "salesChannelEnumId", filterValues: "POS_SALES_CHANNEL"],
                [sequenceNum: 2, fieldExpression: "statusId", filterValues: "ORDER_CANCELLED"],
        ])

        Map<String, Object> matched = SourceFilterSupport.firstMatchingRule(
                [salesChannelEnumId: "WEB_SALES_CHANNEL", statusId: "ORDER_CANCELLED"], rules)

        assertEquals(2, matched.sequenceNum)
    }

    @Test
    void parsedRulesAreImmutable() {
        List<Map<String, Object>> rules = channelRule("POS_SALES_CHANNEL")

        assertThrows(UnsupportedOperationException) { rules.add([:]) }
        assertThrows(UnsupportedOperationException) { rules[0].put("operator", "OTHER") }
    }

    @Test
    void blankFieldExpressionIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException) {
            SourceFilterSupport.parseRules([[sequenceNum: 3, fieldExpression: "  ", filterValues: "X"]])
        }
        assertTrue(error.message.contains("3"))
    }

    @Test
    void emptyValueListIsRejected() {
        assertThrows(IllegalArgumentException) {
            SourceFilterSupport.parseRules([[sequenceNum: 1, fieldExpression: "salesChannelEnumId", filterValues: " , "]])
        }
    }

    @Test
    void unknownOperatorIsRejectedRatherThanIgnored() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException) {
            SourceFilterSupport.parseRules([[
                    sequenceNum    : 1,
                    fieldExpression: "salesChannelEnumId",
                    operator       : "INCLUDE_IN",
                    filterValues   : "POS_SALES_CHANNEL",
            ]])
        }
        assertTrue(error.message.contains("INCLUDE_IN"))
    }

    @Test
    void missingOperatorDefaultsToExcludeIn() {
        List<Map<String, Object>> rules = SourceFilterSupport.parseRules([[
                sequenceNum    : 1,
                fieldExpression: "salesChannelEnumId",
                filterValues   : "POS_SALES_CHANNEL",
        ]])

        assertEquals("EXCLUDE_IN", rules[0].operator)
    }

    @Test
    void nonListInputIsRejected() {
        assertThrows(IllegalArgumentException) { SourceFilterSupport.parseRules("salesChannelEnumId") }
    }

    @Test
    void nonMapRuleEntryIsRejected() {
        assertThrows(IllegalArgumentException) { SourceFilterSupport.parseRules(["salesChannelEnumId"]) }
    }

    @Test
    void ruleCountBoundIsEnforced() {
        List oversized = (1..(SourceFilterSupport.MAX_RULES_PER_SOURCE + 1)).collect { int index ->
            [sequenceNum: index, fieldExpression: "field${index}".toString(), filterValues: "VALUE"]
        }
        assertThrows(IllegalArgumentException) { SourceFilterSupport.parseRules(oversized) }
    }

    @Test
    void valueCountBoundIsEnforced() {
        String values = (1..(SourceFilterSupport.MAX_VALUES_PER_RULE + 1)).collect { "V${it}" }.join(",")
        assertThrows(IllegalArgumentException) {
            SourceFilterSupport.parseRules([[sequenceNum: 1, fieldExpression: "salesChannelEnumId", filterValues: values]])
        }
    }

    @Test
    void missingSequenceNumFallsBackToPosition() {
        List<Map<String, Object>> rules = SourceFilterSupport.parseRules([
                [fieldExpression: "salesChannelEnumId", filterValues: "POS_SALES_CHANNEL"],
                [fieldExpression: "statusId", filterValues: "ORDER_CANCELLED"],
        ])

        assertEquals(1, rules[0].sequenceNum)
        assertEquals(2, rules[1].sequenceNum)
    }
}
