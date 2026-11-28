package darpan.judge

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-318 plan 2, Task 6 (D7). The pair fact: presence per side, prefixed side fields, parameters and asks. Pure Groovy. */
class PairFactSupportTests {

    private static Map state(String p1, String p2, Set<String> aside1 = [] as Set, Set<String> aside2 = [] as Set,
                             Collection<String> keys1 = ["orderId", "status", "a", "items[0].status", "presence", "id"],
                             Collection<String> keys2 = ["id", "b", "tranId"]) {
        return [factTypeId: "FT1",
                file1: [prefix: p1, setAside: aside1, keyNames: PairFactSupport.nameKeys(p1, [:], keys1)],
                file2: [prefix: p2, setAside: aside2, keyNames: PairFactSupport.nameKeys(p2, [:], keys2)]]
    }

    @Test
    void aMatchedPairIsKeptOnBothSides() {
        Map out = PairFactSupport.buildFact([compareId: "P1", file1: [orderId: "O1", status: "OPEN"], file2: [id: "O1"]],
                state("hotwax", "netsuite"), [threshold: "5"], [ask_a_status: "S"])
        assertEquals("FT1", out.factTypeId)
        assertEquals("P1", out.recordId)
        Map fact = out.fact as Map
        assertEquals("P1", fact.compareId)
        assertEquals("KEPT", fact.hotwax_presence)
        assertEquals("KEPT", fact.netsuite_presence)
        assertEquals("OPEN", fact.hotwax_status)
        assertEquals("O1", fact.netsuite_id)
        assertEquals("5", fact.param_threshold)
        assertEquals("S", fact.ask_a_status)
    }

    @Test
    void aMissingInFile2RowIsKeptAndAbsent() {
        Map fact = PairFactSupport.buildFact([compareId: "P2", file1: [orderId: "O2"], file2: null],
                state("hotwax", "netsuite"), [:], [:]).fact as Map
        assertEquals("KEPT", fact.hotwax_presence)
        assertEquals("ABSENT", fact.netsuite_presence)
        assertFalse(fact.keySet().any { it.startsWith("netsuite_") && it != "netsuite_presence" })
    }

    /**
     * DAR-OPS-029 S04, invariant 4. Missing is a presence state, never a null field. moqui-rules reads a field that is not on the
     * fact as null, and two nulls compare equal, so an absent side must put none of its fields on the fact: its presence says it
     * is missing. A blank value on a kept side is a value, not an absence.
     */
    @Test
    void anAbsentSideIsAPresenceStateAndABlankValueStays() {
        Map fact = PairFactSupport.buildFact([compareId: "M1", file1: [orderId: "O1", status: ""], file2: null],
                state("hotwax", "netsuite"), [:], [:]).fact as Map
        assertEquals("ABSENT", fact.netsuite_presence)
        assertEquals(["compareId", "hotwax_presence", "hotwax_orderId", "hotwax_status", "netsuite_presence"], fact.keySet() as List,
                "the absent side's fields are not on the fact at all, not even as null")
        assertTrue(fact.containsKey("hotwax_status"))
        assertEquals("", fact.hotwax_status, "blank is kept as a value")
    }

    @Test
    void aRecordInFile2sSideFileIsSetAsideNotAbsent() {
        Map fact = PairFactSupport.buildFact([compareId: "P3", file1: [orderId: "O3"], file2: null],
                state("hotwax", "netsuite", [] as Set, ["P3"] as Set), [:], [:]).fact as Map
        assertEquals("KEPT", fact.hotwax_presence)
        assertEquals("SET_ASIDE", fact.netsuite_presence)
    }

    @Test
    void setAsideOnOneSideAndAbsentOnTheOtherSaysExactlyThat() {
        Map fact = PairFactSupport.buildFact([compareId: "P4", file1: null, file2: null],
                state("hotwax", "netsuite", ["P4"] as Set, [] as Set), [:], [:]).fact as Map
        assertEquals("SET_ASIDE", fact.hotwax_presence)
        assertEquals("ABSENT", fact.netsuite_presence)
    }

    @Test
    void aSideThatHoldsTheRecordIsKeptEvenIfTheSidecarNamesTheId() {
        Map fact = PairFactSupport.buildFact([compareId: "P5", file1: [a: "1"], file2: [b: "2"]],
                state("hotwax", "netsuite", ["P5"] as Set, ["P5"] as Set), [:], [:]).fact as Map
        assertEquals("KEPT", fact.hotwax_presence)
        assertEquals("KEPT", fact.netsuite_presence)
    }

    @Test
    void twoHotwaxSidesGetPrefixesHotwax1AndHotwax2() {
        assertEquals(["hotwax1", "hotwax2"], PairFactSupport.sidePrefixes("OMS", "OMS"))
        assertEquals(["hotwax", "netsuite"], PairFactSupport.sidePrefixes("OMS", "NETSUITE"))
        assertEquals(["shopify", "database"], PairFactSupport.sidePrefixes("SHOPIFY", "DATABASE"))
        Map fact = PairFactSupport.buildFact([compareId: "P6", file1: [id: "A"], file2: [id: "B"]],
                state("hotwax1", "hotwax2"), [:], [:]).fact as Map
        assertEquals("A", fact.hotwax1_id)
        assertEquals("B", fact.hotwax2_id)
        assertEquals("KEPT", fact.hotwax1_presence)
        assertEquals("KEPT", fact.hotwax2_presence)
    }

    @Test
    void sidePrefixIsTheRootSystemLowerCased() {
        assertEquals("hotwax", PairFactSupport.sidePrefix("OMS"))
        assertEquals("hotwax", PairFactSupport.sidePrefix("HOTWAX_OMS"))
        assertEquals("netsuite", PairFactSupport.sidePrefix("NETSUITE"))
        assertEquals("shopify", PairFactSupport.sidePrefix("SHOPIFY"))
        assertEquals("database", PairFactSupport.sidePrefix("DATABASE"))
        assertEquals("file", PairFactSupport.sidePrefix(null))
    }

    @Test
    void aBracketedKeyGivesAnUnderscoreNameAndTheOriginalSurvivesInTheFieldMap() {
        assertEquals("hotwax_items_0__status", PairFactSupport.fieldName("hotwax", "items[0].status"))
        Map names = PairFactSupport.nameKeys("hotwax", [:], ["items[0].status"])
        assertEquals(["items[0].status": "hotwax_items_0__status"], names)
        Map fact = PairFactSupport.buildFact([compareId: "P7", file1: ["items[0].status": "OPEN"], file2: null],
                state("hotwax", "netsuite"), [:], [:]).fact as Map
        assertEquals("OPEN", fact.hotwax_items_0__status)
    }

    // DAR-BE-318 final review M1: prod MySQL compares the FactField name case-insensitively, so keys that differ only in case need distinct names.
    @Test
    void keysThatDifferOnlyInCaseGetDistinctNamesIgnoringCaseAndExistingNamesStay() {
        Map names = PairFactSupport.nameKeys("hotwax", [:], ["status", "Status"])
        assertEquals(["Status": "hotwax_Status", "status": "hotwax_status_2"], names, "sorted order: Status first")
        assertEquals(2, names.values().collect { it.toLowerCase() }.toSet().size())
        Map again = PairFactSupport.nameKeys("hotwax", [status: "hotwax_status"], ["status", "Status"])
        assertEquals("hotwax_status", again.status, "an existing name is kept")
        assertEquals("hotwax_Status_2", again.Status)
    }

    @Test
    void collidingNamesGetANumericSuffixInKeyOrder() {
        Map names = PairFactSupport.nameKeys("hotwax", [:], ["a-b", "a_b", "a b"])
        assertEquals(3, names.values().toSet().size())
        assertEquals("hotwax_a_b", names["a b"])
        assertEquals("hotwax_a_b_2", names["a-b"])
        assertEquals("hotwax_a_b_3", names["a_b"])
    }

    @Test
    void aSideKeyNamedPresenceNeverTakesThePresenceFieldsName() {
        assertEquals("hotwax_presence_2", PairFactSupport.nameKeys("hotwax", [:], ["presence"])["presence"])
        Map fact = PairFactSupport.buildFact([compareId: "P8", file1: [presence: "x"], file2: null],
                state("hotwax", "netsuite"), [:], [:]).fact as Map
        assertEquals("KEPT", fact.hotwax_presence)
        assertEquals("x", fact.hotwax_presence_2)
    }

    @Test
    void sparsePairsGetIdenticalNamesForSharedKeys() {
        List<String> all = ["a-b", "a_b", "c"]
        Map st = state("hotwax", "netsuite", [] as Set, [] as Set, all, [])
        Map f1 = PairFactSupport.buildFact([compareId: "S1", file1: ["a_b": "1", c: "x"], file2: null], st, [:], [:]).fact as Map
        Map f2 = PairFactSupport.buildFact([compareId: "S2", file1: ["a-b": "2", "a_b": "3"], file2: null], st, [:], [:]).fact as Map
        assertEquals("1", f1.hotwax_a_b_2)
        assertEquals("3", f2.hotwax_a_b_2)
        assertEquals("2", f2.hotwax_a_b)
    }

    @Test
    void aRerunKeepsEveryExistingNameAndNamesOnlyTheNewKeys() {
        Map first = PairFactSupport.nameKeys("hotwax", [:], ["a-b", "a_b"])
        Map again = PairFactSupport.nameKeys("hotwax", first, ["a-b", "a_b", "a b"])
        first.each { k, v -> assertEquals(v, again[k]) }
        assertEquals(3, again.values().toSet().size())
        assertEquals("hotwax_a_b_3", again["a b"])
    }

    @Test
    void keysCollidingAfterTheCutKeepDistinctNamesAcrossARerun() {
        String k1 = "y" * 60 + "1"
        String k2 = "y" * 60 + "2"
        Map first = PairFactSupport.nameKeys("hotwax", [:], [k1])
        Map again = PairFactSupport.nameKeys("hotwax", first, [k1, k2])
        assertEquals(first[k1], again[k1])
        assertNotEquals(again[k1], again[k2])
        assertTrue(again.values().every { it.length() <= 40 })
    }

    @Test
    void anUnmappedKeyIsRefusedLoudly() {
        IllegalStateException e = assertThrows(IllegalStateException) {
            PairFactSupport.buildFact([compareId: "U1", file1: [mystery: "1"], file2: null], state("hotwax", "netsuite"), [:], [:])
        }
        assertEquals("key mystery of file1 has no field name in the pair fact type", e.message)
    }

    @Test
    void aBlankCompareIdIsRefusedLikeARepeat() {
        Iterator<List<Map>> batchIt = PairFactSupport.batches([[compareId: " "]].iterator(), 2)
        assertThrows(IllegalStateException) { batchIt.next() }
    }

    @Test
    void aLongNameIsCutToTheFieldNameLimit() {
        String name = PairFactSupport.fieldName("hotwax", "x" * 80)
        assertTrue(name.length() <= 40)
        Map names = PairFactSupport.nameKeys("hotwax", [:], ["x" * 80, "x" * 81])
        assertEquals(2, names.values().toSet().size())
        assertTrue(names.values().every { it.length() <= 40 })
    }

    @Test
    void aRepeatedCompareIdAcrossTwoBatchesIsRefused() {
        List<Map> rows = [[compareId: "D1"], [compareId: "D2"], [compareId: "D3"], [compareId: "D1"]]
        Iterator<List<Map>> batchIt = PairFactSupport.batches(rows.iterator(), 2)
        assertEquals(["D1", "D2"], batchIt.next()*.compareId)
        IllegalStateException e = assertThrows(IllegalStateException) { while (batchIt.hasNext()) batchIt.next() }
        assertEquals("compare id D1 appears twice in one judging", e.message)
    }

    @Test
    void aRepeatedCompareIdInOneBatchIsRefusedToo() {
        Iterator<List<Map>> batchIt = PairFactSupport.batches([[compareId: "D1"], [compareId: "D1"]].iterator(), 500)
        IllegalStateException e = assertThrows(IllegalStateException) { batchIt.next() }
        assertEquals("compare id D1 appears twice in one judging", e.message)
    }

    @Test
    void batchesSplitBySizeAndKeepOrder() {
        List<Map> rows = (1..5).collect { [compareId: "C${it}".toString()] }
        List<List<Map>> all = PairFactSupport.batches(rows.iterator(), 2).collect { it }
        assertEquals([2, 2, 1], all*.size())
        assertEquals((1..5).collect { "C${it}".toString() }, all.flatten()*.compareId)
    }
}
