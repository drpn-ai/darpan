package reconciliation.rule

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

class RuleDiffSupportTests {

    @Test
    void fieldTallyKeepsValuesOnlyWhenEveryDifferenceAgrees() {
        // DAR-BE-155: a status mismatch names its pair; amounts that vary per record name none.
        List<Map> tally = RuleDiffSupport.fieldTally([
                [field: "grand_total = total_amount", file1Value: "89.89", file2Value: "90.32"],
                [field: "isCompleted = isFulfilled", file1Value: "Y", file2Value: "N"],
                [field: "grand_total = total_amount", file1Value: "10.00", file2Value: "10.01"],
                [field: "isCompleted = isFulfilled", file1Value: "Y", file2Value: "N"],
                [field: "grand_total = total_amount", file1Value: "5", file2Value: "6"],
                [diffType: "BUSINESS_RULE_DIFF", message: "no field"],
        ])
        assertEquals([
                [field: "grand_total = total_amount", label: "grand_total = total_amount", ruleId: null, ruleName: null, count: 3,
                 file1Value: null, file2Value: null, sameValues: false],
                [field: "isCompleted = isFulfilled", label: "isCompleted = isFulfilled", ruleId: null, ruleName: null, count: 2,
                 file1Value: "Y", file2Value: "N", sameValues: true],
        ], tally)
    }

    @Test
    void fieldTallyGivesEachRuleItsOwnRowNamedByItsRuleName() {
        // DAR-BE-210: a difference's ruleId is its DRL rule name: the Rule's id, or the name its stored ruleLogic
        // declares (FIELD_COMPARISON_1 when the rule was saved before it had an id).
        List rules = [
                [ruleId: "100500", ruleName: "Fulfillment Sync", ruleText: "isFulfilled = isCompleted",
                 ruleLogic: 'rule "FIELD_COMPARISON_1"\nwhen\n    $m : Map()\nthen\nend'],
                [ruleId: "100501", ruleName: null, ruleText: "amount = amount", ruleLogic: 'rule "100501"\nwhen\nthen\nend'],
                [ruleId: "100502", ruleName: "Transaction Sync", ruleText: "status = status", ruleLogic: null],
        ]
        List<Map> tally = RuleDiffSupport.fieldTally([
                [ruleId: "FIELD_COMPARISON_1", field: "isFulfilled = isCompleted", file1Value: "Y", file2Value: "N"],
                [ruleId: "FIELD_COMPARISON_1", field: "isFulfilled = isCompleted", file1Value: "Y", file2Value: "N"],
                [ruleId: "FIELD_COMPARISON_1", field: "isFulfilled = isCompleted", file1Value: "Y", file2Value: "N"],
                [ruleId: "100501", field: "amount = amount", file1Value: "1", file2Value: "2"],
                [ruleId: "100502", field: "status = status", file1Value: "A", file2Value: "B"],
                [ruleId: "100502", field: "status = status", file1Value: "C", file2Value: "D"],
                [diffType: "BUSINESS_RULE_DIFF", message: "names no rule and no field"],
        ], rules)
        assertEquals(["Fulfillment Sync", "Transaction Sync", "amount = amount"], tally*.label)
        assertEquals(["100500", "100502", "100501"], tally*.ruleId)
        assertEquals([3, 2, 1], tally*.count)
        assertEquals(["Fulfillment Sync", "Transaction Sync", null], tally*.ruleName)
        assertEquals([true, false, true], tally*.sameValues)
        assertEquals("Y", tally[0].file1Value)
    }

    @Test
    void fieldTallySplitsTwoRulesThatShareAFieldLabel() {
        List<Map> tally = RuleDiffSupport.fieldTally([
                [ruleId: "R1", field: "a = b", file1Value: "1", file2Value: "2"],
                [ruleId: "R2", field: "a = b", file1Value: "1", file2Value: "2"],
                [ruleId: "R2", field: "a = b", file1Value: "1", file2Value: "2"],
        ], [[ruleId: "R1", ruleName: "First"], [ruleId: "R2"]])
        assertEquals(["a = b", "First"], tally*.label)
        assertEquals([2, 1], tally*.count)
    }

    @Test
    void applyPreActionsConvertsStringValuesBeforeOperatorComparison() {
        List<String> preActions = ["STRING_TO_INT"]

        Object left = RuleDiffSupport.applyPreActions("10", preActions)
        Object right = RuleDiffSupport.applyPreActions("2", preActions)

        assertEquals(10L, left)
        assertEquals(2L, right)
        assertTrue(RuleDiffSupport.matchesOperator(left, right, ">"))
    }

    @Test
    void applyPreActionsReturnsNullForInvalidIntegerInput() {
        assertNull(RuleDiffSupport.applyPreActions("not-a-number", ["STRING_TO_INT"]))
    }

    @Test
    void normalizePreActionsCanonicalizesAliasesAndFiltersUnsupportedValues() {
        assertEquals(
                ["STRING_TO_INT", "STRING_TO_NUMBER"],
                RuleDiffSupport.normalizePreActions(["to_integer", "TO_NUMBER", "TRIM"])
        )
    }

    @Test
    void aDiffOnAMatchedPairCarriesBothRecordsIdsAndNothingElse() {
        // DAR-BE-117: a FIELD_MISMATCH row names only its key; the finding needs each record's ids to be navigable.
        Map fact = [compareScopeId: "S", primaryId: "80852690",
                    file1: [memoId: "80852690", tranId: "CM7647980", internalId: "80852690", orderId: "M1085548", status: "Open"],
                    file2: [memoId: "80852690", tranId: "SO761841", orderId: "NO_ORDER_ON_REPLACEMENT", dayNumber: "2459269"]]
        RuleDiffSupport.addFieldMismatch(fact, "R13", "yearBeforeDay <= dayNumber", "2460970", "2459269")
        Map row = RuleDiffSupport.extractNormalizedDiffs(fact)[0]
        assertEquals([file1: [tranId: "CM7647980", internalId: "80852690", orderId: "M1085548"],
                      file2: [tranId: "SO761841", orderId: "NO_ORDER_ON_REPLACEMENT"]],
                new groovy.json.JsonSlurper().parseText(row.ids as String))
        assertNull(row.data, "data stays the present record's, which a matched pair has none of")
        assertNull(RuleDiffSupport.pairIds([primaryId: "K", file1: [status: "Open"]]), "no ids, no column")
        // DAR-UI-081: and each record whole, without the rule engine's own keys, for the audit finding.
        Map whole = new groovy.json.JsonSlurper().parseText(row.sideRecords as String) as Map
        assertEquals("Open", whole.file1.status)
        assertEquals("2459269", whole.file2.dayNumber)
        assertFalse(whole.file1.keySet().any { (it as String).startsWith("_") } || whole.file2.keySet().any { (it as String).startsWith("_") })
        assertNull(RuleDiffSupport.pairRecords([primaryId: "K"]))
    }

    // DAR-BE-126: one-way presence. "requires" breaks only when the left value is true and the right is not; "required by"
    // is the mirror. True is Y or true, any case; N, blank, null and anything else are not.
    @Test
    void requiresBreaksOnlyWhenTheLeftIsTrueAndTheRightIsNot() {
        Map<List, Boolean> requires = [["Y", "N"]: true, ["Y", "Y"]: false, ["N", "N"]: false, ["N", "Y"]: false,
                                       ["y", null]: true, [true, "false"]: true, ["true", true]: false, [null, null]: false, ["Y", ""]: true]
        requires.each { List pair, Boolean fires ->
            assertEquals(fires, RuleDiffSupport.violatesOperator(pair[0], pair[1], "requires"), "requires ${pair}")
            assertEquals(fires, RuleDiffSupport.violatesOperator(pair[1], pair[0], "required by"), "required by ${pair.reverse()}")
        }
    }

    // DAR-BE-153: every Shopify payment transaction id is on a HotWax OPP. "all in" breaks when one comma-joined value
    // on the left is not among the right's; "contains all" is the mirror. A right side that was not read (null) claims
    // nothing, while an empty one ("") has no values at all.
    @Test
    void allInBreaksOnlyWhenALeftValueIsMissingFromTheRight() {
        Map<List, Boolean> allIn = [
                ["20851263242371", "20851263242371"]                    : false,
                ["20766330257539,20853219360899", "20766330257539,20853219360899,EPRA-20853219360899"]: false,
                ["20766330257539,20853219360899", "20766330257539"]     : true,
                ["20851263242371", ""]                                  : true,
                ["20851263242371", null]                                : false,
                ["", "20851263242371"]                                  : false,
                [null, ""]                                              : false,
                [" 1 , 2 ", "2,1"]                                      : false,
                [["1", "2"], "1,2"]                                     : false,
                ["1,2", ["1"]]                                          : true,
        ]
        allIn.each { List pair, Boolean fires ->
            assertEquals(fires, RuleDiffSupport.violatesOperator(pair[0], pair[1], "all in"), "all in ${pair}")
            assertEquals(fires, RuleDiffSupport.violatesOperator(pair[1], pair[0], "contains all"), "contains all ${pair.reverse()}")
        }
    }

    /** DAR-BE-192 (gorjana R9: memo/credit cents under 1.00 ignored). */
    @Test
    void anEqualityWithAToleranceFlagsOnlyADifferenceAtLeastThatLarge() {
        assertFalse(RuleDiffSupport.differsByAtLeast(new BigDecimal("62.06"), new BigDecimal("62.20"), "1.00"))
        assertFalse(RuleDiffSupport.differsByAtLeast(new BigDecimal("259.10"), new BigDecimal("260.09"), "1.00"))
        assertTrue(RuleDiffSupport.differsByAtLeast(new BigDecimal("259.10"), new BigDecimal("260.10"), "1.00"), "exactly 1.00 is a finding")
        assertTrue(RuleDiffSupport.differsByAtLeast(new BigDecimal("105.61"), new BigDecimal("98"), "1.00"))
        assertTrue(RuleDiffSupport.differsByAtLeast(new BigDecimal("75.48"), BigDecimal.ZERO, "1.00"))
        assertTrue(RuleDiffSupport.differsByAtLeast(new BigDecimal("75.48"), null, "1.00"), "a missing amount is still a finding")
        assertFalse(RuleDiffSupport.differsByAtLeast(null, null, "1.00"))
    }

    @Test
    void twoWayLabelPutsEachSystemBesideItsField() {
        assertEquals("HotWax isCompleted = Shopify isFulfilled", RuleDiffSupport.twoWayLabel("isCompleted = isFulfilled", "HotWax", "Shopify"))
        assertEquals("HotWax returnId required by Shopify id", RuleDiffSupport.twoWayLabel("returnId required by id", "HotWax", "Shopify"))
        assertEquals("NetSuite total >= HotWax grandTotal", RuleDiffSupport.twoWayLabel("total >= grandTotal", "NetSuite", "HotWax"))
        assertEquals("SKU mismatch", RuleDiffSupport.twoWayLabel("SKU mismatch", "HotWax", "Shopify"))
        assertEquals("a = b", RuleDiffSupport.twoWayLabel("a = b", null, "Shopify"))
    }
}
