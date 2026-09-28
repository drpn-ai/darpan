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
        assertNull(SourceFilterSupport.evaluate([salesChannelEnumId: "POS_SALES_CHANNEL"], []))
        assertNull(SourceFilterSupport.evaluate([salesChannelEnumId: "POS_SALES_CHANNEL"], null))
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

        assertNotNull(SourceFilterSupport.evaluate([salesChannelEnumId: "pos_sales_channel"], rules))
        assertNotNull(SourceFilterSupport.evaluate([salesChannelEnumId: " POS_SALES_CHANNEL "], rules))
    }

    @Test
    void fieldNameIsCaseSensitiveLikeTheBuiltInFilters() {
        List<Map<String, Object>> rules = channelRule("POS_SALES_CHANNEL")

        // Mirrors OmsRestSourceSupport.isSalesOrder: normalize(key) == fieldName (trim, no case fold).
        assertNull(SourceFilterSupport.evaluate([SALESCHANNELENUMID: "POS_SALES_CHANNEL"], rules))
        assertNotNull(SourceFilterSupport.evaluate([" salesChannelEnumId ": "POS_SALES_CHANNEL"], rules))
    }

    @Test
    void recordMissingTheFieldIsKept() {
        List<Map<String, Object>> rules = channelRule("POS_SALES_CHANNEL")

        assertNull(SourceFilterSupport.evaluate([orderId: "10001"], rules))
        assertNull(SourceFilterSupport.evaluate([salesChannelEnumId: null], rules))
        assertNull(SourceFilterSupport.evaluate([salesChannelEnumId: "  "], rules))
    }

    @Test
    void nonMapRecordNeverMatches() {
        assertNull(SourceFilterSupport.evaluate("not-a-record", channelRule("POS_SALES_CHANNEL")))
        assertNull(SourceFilterSupport.evaluate(null, channelRule("POS_SALES_CHANNEL")))
    }

    @Test
    void firstRejectingRuleWinsSoACountIsAttributedOnce() {
        List<Map<String, Object>> rules = SourceFilterSupport.parseRules([
                [sequenceNum: 1, fieldExpression: "salesChannelEnumId", filterValues: "POS_SALES_CHANNEL"],
                [sequenceNum: 2, fieldExpression: "statusId", filterValues: "ORDER_CANCELLED"],
        ])

        Map<String, Object> verdict = SourceFilterSupport.evaluate(
                [salesChannelEnumId: "POS_SALES_CHANNEL", statusId: "ORDER_CANCELLED"], rules)

        assertEquals(1, ((Map) verdict.get("rule")).sequenceNum)
    }

    @Test
    void secondRuleMatchesWhenFirstDoesNot() {
        List<Map<String, Object>> rules = SourceFilterSupport.parseRules([
                [sequenceNum: 1, fieldExpression: "salesChannelEnumId", filterValues: "POS_SALES_CHANNEL"],
                [sequenceNum: 2, fieldExpression: "statusId", filterValues: "ORDER_CANCELLED"],
        ])

        Map<String, Object> verdict = SourceFilterSupport.evaluate(
                [salesChannelEnumId: "WEB_SALES_CHANNEL", statusId: "ORDER_CANCELLED"], rules)

        assertEquals(2, ((Map) verdict.get("rule")).sequenceNum)
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
        // Was INCLUDE_IN until DAR-BE-054, which is now a supported operator. Swapped for one that
        // is genuinely unsupported rather than deleted: the point of the test is that an operator
        // Darpan cannot honour fails loudly instead of being ignored into a silent no-op.
        IllegalArgumentException error = assertThrows(IllegalArgumentException) {
            SourceFilterSupport.parseRules([[
                    sequenceNum    : 1,
                    fieldExpression: "salesChannelEnumId",
                    operator       : "STARTS_WITH",
                    filterValues   : "POS_SALES_CHANNEL",
            ]])
        }
        assertTrue(error.message.contains("STARTS_WITH"))
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

    @Test
    void storedJsonPathExpressionsAreReducedToTheRecordKeyTheGetterTests() {
        // FINAL-REVIEW CRITICAL 1a: the board stores the field pill's JSONPath; evaluate
        // scans top-level record keys. Everything feeding a getter runs through here first.
        List<Map<String, Object>> reduced = SourceFilterSupport.toRecordFieldRules([
                [sequenceNum: 1, fieldExpression: '$.records[*].salesChannelEnumId',
                 operator   : "EXCLUDE_IN", filterValues: "POS_SALES_CHANNEL"],
                [sequenceNum: 2, fieldExpression: "statusId",
                 operator   : "EXCLUDE_IN", filterValues: "ORDER_CANCELLED"],
        ])

        assertEquals(["salesChannelEnumId", "statusId"], reduced*.fieldExpression)
        // Everything else on the row survives untouched — the reduction is field-name-only.
        assertEquals([1, 2], reduced*.sequenceNum)
        assertEquals(["POS_SALES_CHANNEL", "ORDER_CANCELLED"], reduced*.filterValues)

        List<Map<String, Object>> parsed = SourceFilterSupport.parseRules(reduced)
        assertNotNull(SourceFilterSupport.evaluate([salesChannelEnumId: "POS_SALES_CHANNEL"], parsed))
    }

    @Test
    void reducingRulesIsNullAndEmptySafe() {
        assertEquals([], SourceFilterSupport.toRecordFieldRules(null))
        assertEquals([], SourceFilterSupport.toRecordFieldRules([]))
    }

    @Test
    void anExpressionThatResolvesToNoRecordFieldIsRejectedRatherThanMatchingNothing() {
        assertThrows(IllegalArgumentException) {
            SourceFilterSupport.toRecordFieldRules([[sequenceNum: 1, fieldExpression: '$[*]', filterValues: "X"]])
        }
    }

    // ---- INCLUDE_IN (DAR-BE-054) ----

    private static List<Map<String, Object>> statusRule(String operator, String... values) {
        return SourceFilterSupport.parseRules([[
                sequenceNum    : 1,
                fieldExpression: "status",
                operator       : operator,
                filterValues   : values.join(","),
        ]])
    }

    @Test
    void includeModeKeepsAListedValueAndDropsAnUnlistedOne() {
        List<Map<String, Object>> rules = statusRule("INCLUDE_IN", "A", "B", "G")

        assertNull(SourceFilterSupport.evaluate([status: "A"], rules))
        assertNull(SourceFilterSupport.evaluate([status: "g"], rules))

        Map<String, Object> verdict = SourceFilterSupport.evaluate([status: "X"], rules)
        assertNotNull(verdict)
        assertEquals("VALUE", verdict.get("reason"))
        assertEquals(1, ((Map) verdict.get("rule")).get("sequenceNum"))
    }

    @Test
    void includeModeDropsARecordThatHasNoSuchFieldAndSaysWhy() {
        Map<String, Object> verdict = SourceFilterSupport.evaluate([orderId: "M1"], statusRule("INCLUDE_IN", "A"))
        assertNotNull(verdict)
        assertEquals("FIELD_ABSENT", verdict.get("reason"))
    }

    @Test
    void aBlankValueCountsAsAbsentInBothModes() {
        // normalize() trims to "", which is falsy in Groovy, so a present-but-blank field carries
        // no usable value. It cannot be in an allowlist, and cannot match a denylist entry either.
        assertEquals("FIELD_ABSENT",
                SourceFilterSupport.evaluate([status: "   "], statusRule("INCLUDE_IN", "A")).get("reason"))
        assertEquals("FIELD_ABSENT",
                SourceFilterSupport.evaluate([status: null], statusRule("INCLUDE_IN", "A")).get("reason"))
        assertNull(SourceFilterSupport.evaluate([status: "   "], statusRule("EXCLUDE_IN", "A")))
    }

    @Test
    void excludeModeVerdictsAreUnchangedInMeaning() {
        List<Map<String, Object>> rules = statusRule("EXCLUDE_IN", "A")

        Map<String, Object> verdict = SourceFilterSupport.evaluate([status: "A"], rules)
        assertNotNull(verdict)
        assertEquals("VALUE", verdict.get("reason"))
        assertNull(SourceFilterSupport.evaluate([status: "B"], rules))
        assertNull(SourceFilterSupport.evaluate([orderId: "M1"], rules))
    }

    @Test
    void theFirstRejectingRuleWinsAcrossMixedModes() {
        List<Map<String, Object>> rules = SourceFilterSupport.parseRules([
                [sequenceNum: 1, fieldExpression: "origin", operator: "EXCLUDE_IN", filterValues: "NATIVE"],
                [sequenceNum: 2, fieldExpression: "status", operator: "INCLUDE_IN", filterValues: "A"],
        ])

        // Rejected by both rules; rule 1 owns it because it is first.
        assertEquals(1, ((Map) SourceFilterSupport.evaluate([origin: "NATIVE", status: "X"], rules)
                .get("rule")).get("sequenceNum"))

        // Passes rule 1, rejected by rule 2.
        assertEquals(2, ((Map) SourceFilterSupport.evaluate([origin: "EDI", status: "X"], rules)
                .get("rule")).get("sequenceNum"))

        assertNull(SourceFilterSupport.evaluate([origin: "EDI", status: "A"], rules))
    }

    @Test
    void aNonMapRecordIsKeptInEitherMode() {
        assertNull(SourceFilterSupport.evaluate("not a record", statusRule("INCLUDE_IN", "A")))
        assertNull(SourceFilterSupport.evaluate(null, statusRule("INCLUDE_IN", "A")))
    }

    @Test
    void includeOperatorIsAcceptedCaseInsensitivelyUnderAnyLocale() {
        Locale previous = Locale.getDefault()
        try {
            // tr_TR upper-cases "include_in" to "INCLUDE_\u0130N" unless Locale.ROOT is used.
            Locale.setDefault(new Locale("tr", "TR"))
            assertEquals("INCLUDE_IN", statusRule("include_in", "A")[0].operator)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    void twoOperatorsOnOneFieldAreRejected() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException) {
            SourceFilterSupport.parseRules([
                    [sequenceNum: 1, fieldExpression: "status", operator: "INCLUDE_IN", filterValues: "A"],
                    [sequenceNum: 2, fieldExpression: "status", operator: "EXCLUDE_IN", filterValues: "B"],
            ])
        }
        assertTrue(thrown.message.contains("status"))
    }

    @Test
    void twoRulesOnOneFieldWithTheSameOperatorStayLegal() {
        assertEquals(2, SourceFilterSupport.parseRules([
                [sequenceNum: 1, fieldExpression: "status", operator: "EXCLUDE_IN", filterValues: "A"],
                [sequenceNum: 2, fieldExpression: "status", operator: "EXCLUDE_IN", filterValues: "B"],
        ]).size())
    }

}
