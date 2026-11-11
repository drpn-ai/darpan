package darpan.conclusion.brain

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-225. The brain's questions are one declared catalogue, and the pack and the catalogue agree both ways. */
class BrainQuestionCatalogTests {

    @BeforeEach
    void forget() { BrainAnswerStore.clear() }

    private static Set<String> askedByPack() {
        BrainEngine.forPacks(BrainConclude.PILOT_PACKS).ruleMetadata().values().findAll { it.asks != null }
                .collect { it.asks.toString().replaceAll(/^"|"$/, "").trim() } as Set<String>
    }

    @Test
    void everyQuestionThePackAsksIsDeclaredAndEveryDeclaredQuestionIsAsked() {
        Set<String> declared = BrainQuestionCatalog.ENTRIES*.name as Set<String>
        assertEquals(declared.size(), BrainQuestionCatalog.ENTRIES.size(), "a question is declared once")
        assertEquals([] as Set, askedByPack() - declared, "asked by a rule but not in the catalogue")
        assertEquals([] as Set, declared - askedByPack(), "in the catalogue but asked by no rule")
    }

    @Test
    void everyEntryNamesASubjectTheAskPlannerKnowsAndAUsableShape() {
        BrainQuestionCatalog.ENTRIES.each { BrainQuestionCatalog.Entry e ->
            assertTrue(e.subject in ["Order", "Product", "ReplacementReturn", "ShopifyOrder"], "${e.name}: subject ${e.subject}")
            assertTrue(e.batchSize > 0, "${e.name}: batch size")
            assertTrue(BrainAnswerStore.TTL_MILLIS.containsKey(e.freshness), "${e.name}: freshness ${e.freshness} has a time to live")
            assertTrue(e.depth >= 1, "${e.name}: depth")
            // DAR-BE-227: a record can only answer for its own order or product.
            if (e.answeredBy) assertTrue(e.subject in ["Order", "Product"], "${e.name}: answeredBy needs an Order or Product subject")
        }
    }

    @Test
    void aDefaultLookupIsTheQuestionsOwnAndNeedsAHotwaxConfig() {
        assertEquals("productNetsuiteId", BrainQuestions.defaultLookup("productNetsuiteId", "cfg").name)
        assertEquals("cfg", BrainQuestions.defaultLookup("orderPayments", "cfg").sourceConfigId)
        assertEquals(null, BrainQuestions.defaultLookup("orderPayments", null))
        assertEquals(null, BrainQuestions.defaultLookup("returnRma", "cfg"), "NetSuite questions have no default")
        assertEquals(null, BrainQuestions.defaultLookup("noSuchQuestion", "cfg"))
    }

    // A replacement return's RMA comes two answers after a Shopify order: its HotWax order, then its replacement's return.
    @Test
    void theDeepestDeclaredDepthIsThree() {
        assertEquals(3, BrainQuestionCatalog.deepest())
        assertEquals(3, BrainQuestionCatalog.entry("returnRma").depth)
    }

    private static Map exchangeLine() {
        return [key: "M1223508/01", bucket: "MISSING_FROM_FILE_2", sides: [
                FILE_1: [presence: "KEPT", record: [omsOrderId: "M1223508", orderItemSeqId: "01", orderGrandTotal: 50,
                                                    orderName: "EXC-#GOR197410604-1"]],
                FILE_2: [presence: "ABSENT", firstFieldPresence: "ABSENT"]]]
    }

    private static List<String> questionsAsked(List<BrainQuestionCatalog.Entry> catalog) {
        List<String> asked = []
        BrainConclude.run([kind: "orderLine", tenantId: "T1", findings: [exchangeLine()], catalog: catalog,
                fetchQuestion: { String q, List<String> ids ->
                    asked << q
                    q == "replacementReturn" ? [M1223508: [returnId: "M270001", orderId: "M1191688", statusId: "RETURN_COMPLETED",
                                                           returnChannelEnumId: "AFTSHIP_RTN_CHANNEL", returnCredit: 50]] : [:]
                }])
        return asked
    }

    @Test
    void askRoundsStopAtTheDeepestDeclaredDepth() {
        List<String> all = questionsAsked(BrainQuestionCatalog.ENTRIES)
        assertTrue(all.contains("replacementReturn"), all.toString())
        assertTrue(all.containsAll(["returnRma", "returnMemo"]), "the second round asks the return's RMA and memo: ${all}")

        BrainAnswerStore.clear()
        List<BrainQuestionCatalog.Entry> shallow = BrainQuestionCatalog.ENTRIES.collect { BrainQuestionCatalog.Entry e ->
            new BrainQuestionCatalog.Entry(name: e.name, subject: e.subject, batchSize: e.batchSize, freshness: e.freshness, depth: 1,
                    defaultLookup: e.defaultLookup)
        }
        List<String> one = questionsAsked(shallow)
        assertTrue(one.contains("replacementReturn"), one.toString())
        assertFalse(one.contains("returnRma") || one.contains("returnMemo"), "one declared round asks nothing the answer raises: ${one}")
    }

    @Test
    void aQuestionMissingFromTheCatalogueIsNotAskedAndSaysSo() {
        List<String> asked = []
        Map out = BrainConclude.run([kind: "orderLine", tenantId: "T1", findings: [exchangeLine()],
                catalog: BrainQuestionCatalog.ENTRIES.findAll { it.name != "replacementReturn" },
                fetchQuestion: { String q, List<String> ids -> asked << q; [:] }])
        assertFalse(asked.contains("replacementReturn"), asked.toString())
        assertTrue((out.warnings as List<String>).any { it.contains("replacementReturn is not in the catalogue") }, out.warnings.toString())
    }
}
