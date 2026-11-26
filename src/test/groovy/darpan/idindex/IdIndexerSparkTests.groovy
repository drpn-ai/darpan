package darpan.idindex

import org.apache.spark.sql.SparkSession
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir

import static org.junit.jupiter.api.Assertions.*

/** DAR-BE-374: Spark pulls each record's key, kind, stamp and configured field values out of a dataset read's JSON lines. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdIndexerSparkTests {

    private SparkSession spark
    @TempDir
    File dir

    private static final Map SHOPIFY = [systemEnumId: "SHOPIFY", systemCode: "SHOPIFY", recordKind: "ORDER", idField: "id", stampField: "updatedAt"]
    private static final List<Map> FIELDS = [
            [fieldId: "NAME", fieldPath: "name", role: "OWN"],
            [fieldId: "ID", fieldPath: "id", role: "OWN"],
            [fieldId: "LINE", fieldPath: "lineItems[*].id", role: "OWN"],
            [fieldId: "REFUND", fieldPath: "refunds[*].id", role: "OWN"],
            [fieldId: "TXN", fieldPath: "refunds[*].transactions[*].id", role: "OWN"],
    ]

    @BeforeAll
    void setup() {
        spark = SparkSession.builder().appName("IdIndexerSparkTests").master("local[1]").config("spark.ui.enabled", "false").getOrCreate()
    }

    @AfterAll
    void cleanup() { if (spark != null) spark.stop() }

    @Test
    void "nested and list paths are extracted per record"() {
        File f = lines(
                '{"id":"gid://shopify/Order/1","name":"#GOR1","updatedAt":"2026-11-20T10:00:00Z","lineItems":[{"id":"L1"},{"id":"L2"}],"refunds":[{"id":"R1","transactions":[{"id":"T1"},{"id":"T2"}]}]}',
                '{"id":"gid://shopify/Order/2","name":"#GOR2","lineItems":[{"id":"L3"}],"refunds":[]}',
                '{"id":3,"name":"#GOR3"}')
        List<Map> got = IdIndexer.extract(spark, f, SHOPIFY, FIELDS).toList().sort { it.key }
        assertEquals(["3", "gid://shopify/Order/1", "gid://shopify/Order/2"], got*.key)
        Map one = got.find { it.key == "gid://shopify/Order/1" }
        assertEquals(["#GOR1"], one.values["name"])
        assertEquals(["L1", "L2"], one.values["lineItems[*].id"])
        assertEquals(["R1"], one.values["refunds[*].id"], "a one-element list is still a list")
        assertEquals(["T1", "T2"], one.values["refunds[*].transactions[*].id"])
        Map two = got.find { it.key == "gid://shopify/Order/2" }
        assertEquals(["L3"], two.values["lineItems[*].id"])
        assertEquals([], two.values["refunds[*].id"])
        assertEquals(["3"], got.find { it.key == "3" }.values["id"], "a number comes back as its text")
    }

    @Test
    void "extracted values build the same rows as the record itself"() {
        String json = '{"id":"gid://shopify/Order/1","name":"#GOR1","updatedAt":"t","lineItems":[{"id":"L1"},{"id":"L2"}],"refunds":[{"id":"R1","transactions":[{"id":"T1"}]}]}'
        Map extracted = IdIndexer.extract(spark, lines(json), SHOPIFY, FIELDS).next()
        Map record = new groovy.json.JsonSlurper().parseText(json) as Map
        assertEquals(IdIndexRows.rows(SHOPIFY, FIELDS, record, "C"), IdIndexRows.rowsFromValues(SHOPIFY, FIELDS, extracted, "C"))
    }

    @Test
    void "a malformed line is skipped, not fatal"() {
        File f = lines('{"id":"1","name":"#A"}', '{not json', '{"id":"2","name":"#B"}')
        assertEquals(["1", "2"], IdIndexer.extract(spark, f, SHOPIFY, FIELDS).toList()*.key.sort())
    }

    @Test
    void "a NetSuite record's kind field comes back"() {
        Map ns = [systemEnumId: "NETSUITE_SUITEQL", systemCode: "NETSUITE", recordKindField: "type", recordKindMapJson: '{"ItemShip":"FULFILLMENT"}',
                  idField: "id", stampField: "lastmodifieddate"]
        Map got = IdIndexer.extract(spark, lines('{"id":"501","type":"ItemShip","createdfrom":"400"}'), ns,
                [[fieldId: "CF", fieldPath: "createdfrom", role: "REF"]]).next()
        assertEquals(["501", "ItemShip", ["400"]], [got.key, got.kind, got.values["createdfrom"]])
    }

    @Test
    void "a dataset read's file (records under one JSON document, or a bare array) becomes one record per line"() {
        File doc = new File(dir, "doc.json"); doc.text = '{"metadata":{"x":1},"records":[{"id":"1","name":"#A"},{"id":"2","name":"#B"}]}'
        File arr = new File(dir, "arr.json"); arr.text = '[{"id":"3","name":"#C"}]'
        File docLines = IdIndexer.toJsonLines(doc)
        assertEquals(2, docLines.readLines().size())
        assertEquals(["1", "2"], IdIndexer.extract(spark, docLines, SHOPIFY, FIELDS).toList()*.key.sort())
        assertEquals(["3"], IdIndexer.extract(spark, IdIndexer.toJsonLines(arr), SHOPIFY, FIELDS).toList()*.key)
        assertNotEquals(doc, docLines, "the read's own file is left as it was")
    }

    private File lines(String... json) {
        File f = new File(dir, "read-${System.nanoTime()}.json")
        f.text = json.join("\n") + "\n"
        return f
    }
}
