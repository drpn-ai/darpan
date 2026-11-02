package darpan.reconciliation.core

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.parser.CatalystSqlParser$
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-185: what the classpath must hold after the Spark 4 move. Spark 4.2.1 parses SQL with its own
 * ANTLR 4.13 runtime (the 4.10+ serialized-parser format), which is the one Drools 10 also needs, so a stale
 * ANTLR from anywhere else breaks every expression we hand Spark. ANSI mode is Spark 4's default and stays on.
 * Spark but no Moqui, so this lands in the fast unitTest pool.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SparkClasspathGuardTests {

    private SparkSession spark

    @BeforeAll
    void setup() {
        spark = SparkSession.builder().appName("SparkClasspathGuardTests").master("local[1]")
                .config("spark.ui.enabled", "false").getOrCreate()
    }

    @AfterAll
    void cleanup() { if (spark != null) spark.stop() }

    @Test
    void sparkIsFourTwoOne() {
        assertEquals("4.2.1", spark.version())
    }

    @Test
    void antlr4IsSparksOwn() {
        String v = org.antlr.v4.runtime.RuntimeMetaData.VERSION
        assertTrue(v.startsWith("4.13."), "antlr4-runtime on the classpath is ${v}".toString())
    }

    @Test
    void ansiModeIsOn() {
        assertEquals("true", spark.conf().get("spark.sql.ansi.enabled"))
    }

    @Test
    void everyFilterStringWeUseParses() {
        List<String> filters = [
                "compare_id IS NOT NULL AND length(trim(compare_id)) > 0",
                "length(trim(compare_id)) > 0",
                "ns_item_id IS NOT NULL AND length(trim(ns_item_id)) > 0 AND ns_location_id IS NOT NULL AND length(trim(ns_location_id)) > 0",
                "oms_item_id IS NOT NULL AND length(trim(oms_item_id)) > 0 AND oms_location_id IS NOT NULL AND length(trim(oms_location_id)) > 0",
        ]
        filters.each { String f -> assertNotNull(CatalystSqlParser$.MODULE$.parseExpression(f), f) }
        assertEquals(1L, spark.range(1).selectExpr("explode(array(1)) as exploded_item").count())
    }
}
