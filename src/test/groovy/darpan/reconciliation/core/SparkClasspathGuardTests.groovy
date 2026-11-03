package darpan.reconciliation.core

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.parser.CatalystSqlParser$
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

import groovy.io.FileType

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

    /**
     * Every production SparkSession builder turns the Spark UI off. Embedded in Moqui, the UI would bind an
     * unauthenticated port (4040), and on Spark 4 it needs jakarta.servlet, which this build excludes from Spark
     * (the Moqui container supplies the API); the UI-on path is never exercised by any test session.
     * Whichever builder runs first after a JVM start creates the shared session, so one builder left on is enough.
     */
    @Test
    void everySparkSessionBuilderTurnsTheUiOff() {
        File root = [new File('.'), new File('runtime/component/darpan')].find { new File(it, 'src/main/groovy/darpan').isDirectory() }
        assertNotNull(root, "darpan component root not found from ${new File('.').absolutePath}")
        List<String> missing = []
        int builders = 0
        [new File(root, 'src/main/groovy'), new File(root, 'screen')].each { File dir ->
            dir.eachFileRecurse(FileType.FILES) { File f ->
                if (!f.name.endsWith('.groovy')) return
                String text = f.text
                int at = text.indexOf('SparkSession.builder()')
                while (at >= 0) {
                    // A mention inside a comment line is not a builder (source scans must not match comments).
                    String linePrefix = text.substring(text.lastIndexOf('\n', at) + 1, at).trim()
                    if (linePrefix.startsWith('//') || linePrefix.startsWith('*') || linePrefix.startsWith('/*')) {
                        at = text.indexOf('SparkSession.builder()', at + 1)
                        continue
                    }
                    builders++
                    int end = text.indexOf('getOrCreate()', at)
                    String chain = end > 0 ? text.substring(at, end) : text.substring(at)
                    if (!chain.contains('"spark.ui.enabled", "false"')) missing << "${f.path}:${text.substring(0, at).count('\n') + 1}".toString()
                    at = text.indexOf('SparkSession.builder()', at + 1)
                }
            }
        }
        assertTrue(builders >= 3, "expected the production SparkSession builders to be found, saw ${builders}")
        assertTrue(missing.isEmpty(), "SparkSession builders that leave the Spark UI on: ${missing}")
    }
}
