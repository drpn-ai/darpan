package darpan.judge

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-318 plan 2, Task 5. AskSupport with a stubbed CheckAsk read and a stubbed fetch: pure Groovy, no Moqui. */
class AskSupportTests {

    private Closure savedFetcher
    private Closure savedReader
    private List<List> fetchCalls
    private Map<String, Map> answers
    private List<Map> askRows

    @BeforeEach
    void stub() {
        savedFetcher = AskSupport.fetcher
        savedReader = AskSupport.reader
        fetchCalls = []
        answers = [:]
        askRows = []
        AskSupport.reader = { Object ec, String checkId, String tenantId -> askRows }
        AskSupport.fetcher = { Object ec, Map lookup, List<String> ids, String tenantId, List<String> warnings ->
            fetchCalls.add([lookup, ids])
            return answers[lookup.name as String] ?: [:]
        }
    }

    @AfterEach
    void restore() {
        AskSupport.fetcher = savedFetcher
        AskSupport.reader = savedReader
    }

    private static Map row(String name, Map extra = [:]) {
        return [checkId: "C1", askName: name, subjectSide: "FILE_1", idField: "orderId", systemEnumId: "SYS",
                sourceConfigId: "CFG", keyField: "id", many: "N", keepRecordFields: "status,note"] + extra
    }

    @Test
    void fieldsLandAsAskNameField() {
        askRows = [row("nsOrder")]
        answers.nsOrder = ["A1": [id: "A1", status: "OPEN", note: "n1"]]
        Map out = AskSupport.fetchAsks(null, "C1", "T", [["P1", [orderId: "A1"], [:]]], [])
        assertEquals([ask_nsOrder_status: "OPEN", ask_nsOrder_note: "n1"], out["P1"])
        Map lookup = fetchCalls[0][0] as Map
        assertEquals([keepRecordFields: ["status", "note"]], lookup.parameters)
        assertEquals(false, lookup.many)
    }

    @Test
    void oneFetchPerAskForTheWholeBatch() {
        askRows = [row("a"), row("b", [subjectSide: "FILE_2", idField: "ref"])]
        answers.a = ["A1": [status: "S1"], "A2": [status: "S2"]]
        answers.b = ["R9": [status: "S9"]]
        Map out = AskSupport.fetchAsks(null, "C1", "T",
                [["P1", [orderId: "A1"], [ref: "R9"]], ["P2", [orderId: "A2"], null], ["P3", [orderId: "A1"], null]], [])
        assertEquals(2, fetchCalls.size())
        assertEquals(["A1", "A2"], fetchCalls[0][1])
        assertEquals(["R9"], fetchCalls[1][1])
        assertEquals([ask_a_status: "S1", ask_b_status: "S9"], out["P1"])
        assertEquals([ask_a_status: "S2"], out["P2"])
        assertEquals([ask_a_status: "S1"], out["P3"])
    }

    @Test
    void manyJoinsValuesWithAPipe() {
        askRows = [row("items", [many: "Y", keepRecordFields: "sku"])]
        answers.items = ["A1": [[sku: "X"], [sku: "Y"], [sku: "Z"]]]
        Map out = AskSupport.fetchAsks(null, "C1", "T", [["P1", [orderId: "A1"], null]], [])
        assertEquals([ask_items_sku: "X|Y|Z"], out["P1"])
        assertTrue((fetchCalls[0][0] as Map).many as boolean)
    }

    @Test
    void aFailedFetchIsAWarningAndNoFields() {
        askRows = [row("nsOrder")]
        AskSupport.fetcher = { Object ec, Map lookup, List<String> ids, String tenantId, List<String> warnings ->
            warnings.add("Lookup ${lookup.name} not answered: boom".toString())
            return [:]
        }
        List<String> warnings = []
        Map out = AskSupport.fetchAsks(null, "C1", "T", [["P1", [orderId: "A1"], null]], warnings)
        assertEquals([:], out)
        assertEquals(["Lookup nsOrder not answered: boom"], warnings)
    }

    @Test
    void aPairWhoseSubjectSideIsAbsentOrBlankIsSkipped() {
        askRows = [row("nsOrder")]
        answers.nsOrder = ["A1": [status: "OPEN"]]
        Map out = AskSupport.fetchAsks(null, "C1", "T",
                [["P1", null, [orderId: "A1"]], ["P2", [orderId: "  "], null], ["P3", [other: "x"], null], ["P4", [orderId: "A1"], null]], [])
        assertEquals(["P4"], out.keySet() as List)
        assertEquals([["A1"]], fetchCalls*.get(1))
    }

    @Test
    void noSubjectIdsMeansNoFetch() {
        askRows = [row("nsOrder")]
        assertEquals([:], AskSupport.fetchAsks(null, "C1", "T", [["P1", null, null]], []))
        assertEquals(0, fetchCalls.size())
    }

    @Test
    void noAskRowsMeansNothing() {
        assertEquals([:], AskSupport.fetchAsks(null, "C1", "T", [["P1", [orderId: "A1"], null]], []))
        assertEquals(0, fetchCalls.size())
    }

    @Test
    void withoutKeepFieldsEveryRecordFieldIsExposed() {
        askRows = [row("nsOrder", [keepRecordFields: null])]
        answers.nsOrder = ["A1": [status: "OPEN", id: "A1"]]
        Map out = AskSupport.fetchAsks(null, "C1", "T", [["P1", [orderId: "A1"], null]], [])
        assertEquals([ask_nsOrder_status: "OPEN", ask_nsOrder_id: "A1"], out["P1"])
        assertEquals(null, (fetchCalls[0][0] as Map).parameters)
    }
}
