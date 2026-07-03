package darpan.facade.reconciliation

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Audit 2026-06-11 #21 — locks the server-side diff classifier to the darpan-ui behaviour it replaces
 * (ReconciliationRunResultPage.vue + reconciliationDisplay.ts). Each expectation below is hand-derived
 * from the UI helpers; if the UI logic changes, re-check parity here and in the run-result page.
 */
class DiffDetailClassifierTests {

    private static final String F1 = "System A"
    private static final String F2 = "System B"

    // --- normalize helpers ---

    @Test
    void normalizeTextTrimsStringsAndDropsNonStrings() {
        assertEquals("hello", DiffDetailClassifier.normalizeText("  hello  "))
        assertEquals("", DiffDetailClassifier.normalizeText(42))
        assertEquals("", DiffDetailClassifier.normalizeText(null))
    }

    @Test
    void normalizeTokenLowercasesAndCollapsesSeparators() {
        assertEquals("system_a", DiffDetailClassifier.normalizeToken(" System A "))
        assertEquals("missing_in_system_b", DiffDetailClassifier.normalizeToken("Missing in System-B!!"))
        assertEquals("", DiffDetailClassifier.normalizeToken("***"))
    }

    // --- bucket classification ---

    @Test
    void missingInLabelMapsToBuckets() {
        assertEquals("file-1", DiffDetailClassifier.resolveDiffBucket([missingIn: "System A"], F1, F2))
        assertEquals("file-2", DiffDetailClassifier.resolveDiffBucket([missingIn: "System B"], F1, F2))
    }

    @Test
    void presentInLabelMapsToOppositeBucket() {
        assertEquals("file-2", DiffDetailClassifier.resolveDiffBucket([presentIn: "System A"], F1, F2))
        assertEquals("file-1", DiffDetailClassifier.resolveDiffBucket([presentIn: "System B"], F1, F2))
    }

    @Test
    void missingInTypeTokenMapsToBucket() {
        assertEquals("file-2", DiffDetailClassifier.resolveDiffBucket([type: "missing_in_system_b"], F1, F2))
        assertEquals("file-1", DiffDetailClassifier.resolveDiffBucket([diffType: "MISSING_IN_System_A"], F1, F2))
    }

    @Test
    void nonMissingRecordsAreRuleBucket() {
        assertFalse(DiffDetailClassifier.isMissingDiffRecord([diffType: "VALUE_MISMATCH"]))
        assertEquals("rule", DiffDetailClassifier.resolveDiffBucket([diffType: "VALUE_MISMATCH", ruleId: "rule_1"], F1, F2))
    }

    // --- record id resolution ---

    @Test
    void recordIdPrefersIdThenPrimaryIdThenData() {
        assertEquals("X1", DiffDetailClassifier.resolveDiffRecordId([id: "X1", primaryId: "P1"], 0))
        assertEquals("P1", DiffDetailClassifier.resolveDiffRecordId([primaryId: "P1"], 0))
        assertEquals("CID", DiffDetailClassifier.resolveDiffRecordId([data: [compare_id: "CID"]], 0))
        assertEquals("row-3", DiffDetailClassifier.resolveDiffRecordId([:], 2))
        // classifyRow parses string data before id resolution (mirrors normalizeDiffDetailRows).
        assertEquals("JID", DiffDetailClassifier.classifyRow(
                [diffType: "VALUE_MISMATCH", ruleId: "rule_1", data: '{"recordId":"JID"}'], 0, F1, F2).recordId)
    }

    // --- rule descriptors / labels ---

    @Test
    void ruleDescriptorForBaseBucket() {
        Map descriptor = DiffDetailClassifier.resolveRuleDescriptor([missingIn: "System A"], "file-1", 0)
        assertEquals(DiffDetailClassifier.BASE_RULE_FILTER_KEY, descriptor.ruleFilterKey)
        assertEquals("Base comparison", descriptor.ruleLabel)
    }

    @Test
    void ruleDescriptorForRuleBucketUsesRuleIdAndDetail() {
        Map descriptor = DiffDetailClassifier.resolveRuleDescriptor([ruleId: "rule_2", field: "price"], "rule", 0)
        assertEquals("rule_2", descriptor.ruleFilterKey)
        assertEquals("rule_2", descriptor.ruleId)
        assertEquals("price", descriptor.ruleLabel)
    }

    @Test
    void ruleOptionLabelDerivation() {
        assertEquals("Rule 2", DiffDetailClassifier.resolveRuleOptionLabel("rule_2", 5))
        assertEquals("Rule 4", DiffDetailClassifier.resolveRuleOptionLabel("step-04", 9))
        assertEquals("Rule 7", DiffDetailClassifier.resolveRuleOptionLabel("anything", 7))
    }

    @Test
    void humanizeRuleIdentifierTitleCases() {
        assertEquals("Discount Check", DiffDetailClassifier.humanizeRuleIdentifier("discount_check"))
        assertEquals("Rule difference", DiffDetailClassifier.humanizeRuleIdentifier(""))
    }

    // --- facets ---

    @Test
    void bucketCountsAndRuleOptions() {
        List<Map> rows = [
                DiffDetailClassifier.classifyRow([missingIn: "System A"], 0, F1, F2),
                DiffDetailClassifier.classifyRow([diffType: "VALUE_MISMATCH", ruleId: "rule_2", field: "price"], 1, F1, F2),
                DiffDetailClassifier.classifyRow([diffType: "VALUE_MISMATCH", ruleId: "rule_2", field: "qty"], 2, F1, F2),
                DiffDetailClassifier.classifyRow([diffType: "VALUE_MISMATCH", ruleId: "discount_check", field: "total"], 3, F1, F2),
        ]

        Map counts = DiffDetailClassifier.buildBucketCounts(rows)
        assertEquals(1, counts.get("file-1"))
        assertEquals(0, counts.get("file-2"))
        assertEquals(3, counts.get("rule"))

        List<Map> options = DiffDetailClassifier.buildRuleOptions(rows)
        assertEquals(3, options.size())
        assertEquals(DiffDetailClassifier.BASE_RULE_FILTER_KEY, options[0].key)
        assertEquals("Rule 0", options[0].label)
        assertEquals(1, options[0].count)
        assertEquals("rule_2", options[1].key)
        assertEquals("Rule 2", options[1].label)
        assertEquals(2, options[1].count)
        assertEquals("price", options[1].detail)
        assertEquals("discount_check", options[2].key)
        assertEquals(1, options[2].count)
    }

    // --- effective summary ---

    @Test
    void effectiveSummaryFallsBackToComputedMissingCounts() {
        Map document = [differences: [
                [missingIn: "System A"],
                [missingIn: "System B"],
                [missingIn: "System B"],
                [diffType: "VALUE_MISMATCH", ruleId: "rule_1"],
        ]]
        Map summary = DiffDetailClassifier.buildEffectiveSummary(document, F1, F2)
        // missing-from-A -> onlyInFile2; missing-from-B -> onlyInFile1
        assertEquals(2, summary.onlyInFile1Count)
        assertEquals(1, summary.onlyInFile2Count)
        assertEquals(4, summary.totalDifferences)
    }

    @Test
    void effectiveSummaryPrefersFileSummary() {
        Map document = [
                summary    : [totalDifferences: 99, onlyInFile1Count: 7, onlyInFile2Count: 3, ruleDifferenceCount: 12],
                differences: [[missingIn: "System A"]],
        ]
        Map summary = DiffDetailClassifier.buildEffectiveSummary(document, F1, F2)
        assertEquals(99, summary.totalDifferences)
        assertEquals(7, summary.onlyInFile1Count)
        assertEquals(3, summary.onlyInFile2Count)
        assertEquals(12, summary.ruleDifferenceCount)
    }

    // --- paging + filtering ---

    @Test
    void pagingSlicesAndReportsTotals() {
        List differences = (0..<12).collect { int i -> [id: "ID-${i}".toString(), diffType: "VALUE_MISMATCH", ruleId: "rule_1"] }
        Map document = [differences: differences]

        Map page0 = DiffDetailClassifier.buildDifferencesPage(document, F1, F2, null, "all", null, 0, 5, true)
        assertEquals(12, page0.totalDifferences)
        assertEquals(12, page0.totalFiltered)
        assertEquals(3, page0.pageCount)
        assertEquals(5, ((List) page0.differences).size())
        assertEquals("ID-0", ((List) page0.differences)[0].recordId)

        Map page2 = DiffDetailClassifier.buildDifferencesPage(document, F1, F2, null, "all", null, 2, 5, false)
        assertEquals(2, ((List) page2.differences).size())
        assertEquals("ID-10", ((List) page2.differences)[0].recordId)
        assertFalse(page2.containsKey("bucketCounts"))
    }

    @Test
    void pagingClampsOutOfRangeIndex() {
        Map document = [differences: [[id: "A", diffType: "X", ruleId: "rule_1"], [id: "B", diffType: "X", ruleId: "rule_1"]]]
        Map page = DiffDetailClassifier.buildDifferencesPage(document, F1, F2, null, "all", null, 99, 5, false)
        assertEquals(0, page.pageIndex)
        assertEquals(2, ((List) page.differences).size())
    }

    @Test
    void bucketAndRuleAndSearchFiltersNarrowTheFilteredSet() {
        Map document = [differences: [
                [id: "M1", missingIn: "System A"],
                [id: "R1", diffType: "VALUE_MISMATCH", ruleId: "rule_2"],
                [id: "R2", diffType: "VALUE_MISMATCH", ruleId: "rule_2"],
                [id: "R3", diffType: "VALUE_MISMATCH", ruleId: "rule_3"],
        ]]

        Map ruleOnly = DiffDetailClassifier.buildDifferencesPage(document, F1, F2, ["rule"], "all", null, 0, 50, false)
        assertEquals(3, ruleOnly.totalFiltered)
        assertEquals(4, ruleOnly.totalDifferences)

        Map rule2 = DiffDetailClassifier.buildDifferencesPage(document, F1, F2, null, "rule_2", null, 0, 50, false)
        assertEquals(2, rule2.totalFiltered)

        Map searched = DiffDetailClassifier.buildDifferencesPage(document, F1, F2, null, "all", "r1", 0, 50, false)
        assertEquals(1, searched.totalFiltered)
        assertEquals("R1", ((List) searched.differences)[0].recordId)
    }

    @Test
    void pageRowsCarryClassificationAndRawRecord() {
        Map document = [differences: [[id: "R1", diffType: "VALUE_MISMATCH", ruleId: "rule_2", field: "price", data: '{"x":1}']]]
        Map page = DiffDetailClassifier.buildDifferencesPage(document, F1, F2, null, "all", null, 0, 50, true)
        Map row = ((List) page.differences)[0]
        assertEquals("rule", row.bucket)
        assertEquals("rule_2", row.ruleFilterKey)
        assertEquals("R1", row.recordId)
        assertTrue(row.containsKey("record"))
        assertEquals('{"x":1}', ((Map) row.record).get("data"))
    }
}
