package darpan.reconciliation.core

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Row
import org.apache.spark.sql.RowFactory
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.types.DataTypes
import org.apache.spark.sql.types.StructType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals

/**
 * DAR-BE-050 D1. The compare core is two left_anti joins followed by .distinct(), i.e. SET
 * semantics, so a LINE-grain key matches whether OMS holds three units or one.
 *
 * These two tests are a matched pair on purpose: the first pins the blindness that motivates the
 * unit ordinal, the second pins the fix. Deleting either one makes the other look like an
 * arbitrary choice, and the unit ordinal is exactly the kind of thing a later reader "simplifies"
 * away. Both are expected to pass against the SHIPPED engine — this class characterises existing
 * behaviour rather than driving new code. If the first one ever fails, the design's D1 rests on a
 * false premise and the build should stop rather than proceed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderLineUnitKeyBlindnessTests {
    /** ASCII Unit Separator, the shipped composite-key delimiter. Never hardcode a visible one. */
    private static final String SEP = ReconciliationServices.COMPOSITE_KEY_DELIMITER

    private ExecutionContext ec
    private SparkSession spark

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "order-line-unit-key")
        // One session for the whole class: per-test create/stop races the next test's getOrCreate
        // against the previous stop and intermittently hands out a dead session.
        spark = SparkSession.builder().appName("OrderLineUnitKeyBlindnessTests").master("local[1]")
                .config("spark.ui.enabled", "false").getOrCreate()
    }

    @AfterAll
    void cleanup() {
        if (spark != null) spark.stop()
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void clearErrors() {
        ec.message.clearErrors()
    }

    private Dataset<Row> frameOf(List<String> compareIds) {
        StructType schema = new StructType().add("compare_id", DataTypes.StringType, true)
        List<Row> rows = compareIds.collect { String id -> RowFactory.create(id) }
        return spark.createDataFrame(rows, schema)
    }

    /**
     * Drives the real public entry point rather than a test-only shim: reconcileIdDataFrames reads
     * its inputs from ec.contextStack, which is the same object ec.context returns.
     */
    private Map<String, Object> reconcile(Dataset<Row> df1, Dataset<Row> df2) {
        ec.context.putAll([df1: df1, df2: df2, idColumnName: "compare_id",
                           df1Label: "Shopify", df2Label: "OMS"])
        return ReconciliationServices.reconcileIdDataFrames(ec)
    }

    private static String lineKey(String orderId, String lineId) {
        return orderId + SEP + lineId
    }

    private static String unitKey(String orderId, String lineId, int ordinal) {
        return orderId + SEP + lineId + SEP + ordinal
    }

    @Test
    void lineGrainKeyIsBlindToAQuantityShortfall() {
        // Shopify sold line 15210699161731 with quantity 3; OMS imported only two of the units.
        // Real shapes: 5.33% of measured (order, line) keys carry 2+ OMS units, longest 40.
        Dataset<Row> shopify = frameOf([lineKey("6678687481987", "15210699161731")])
        Dataset<Row> oms = frameOf([lineKey("6678687481987", "15210699161731"),
                                    lineKey("6678687481987", "15210699161731")])

        Map<String, Object> result = reconcile(shopify, oms)

        assertEquals(0L, result.differenceCount as long,
                "line grain reports CLEAN on a real shortfall - this is why the unit ordinal exists")
    }

    @Test
    void unitOrdinalKeyReportsExactlyTheMissingUnits() {
        Dataset<Row> shopify = frameOf([unitKey("6678687481987", "15210699161731", 1),
                                        unitKey("6678687481987", "15210699161731", 2),
                                        unitKey("6678687481987", "15210699161731", 3)])
        Dataset<Row> oms = frameOf([unitKey("6678687481987", "15210699161731", 1),
                                    unitKey("6678687481987", "15210699161731", 2)])

        Map<String, Object> result = reconcile(shopify, oms)

        assertEquals(1L, result.onlyInDf1Count as long, "exactly the third unit is missing in OMS")
        assertEquals(0L, result.onlyInDf2Count as long)
        assertEquals(1L, result.differenceCount as long)
    }

    @Test
    void aLineOmsNeverReceivedReportsOncePerUnit() {
        // The other half of the grain argument: an absent line costs as many diff rows as it has
        // units, which is what makes differenceCount a count of UNITS rather than of lines.
        Dataset<Row> shopify = frameOf([unitKey("6678687481987", "15210699161731", 1),
                                        unitKey("6678687481987", "15210699161731", 2),
                                        unitKey("6678687481987", "15210699161731", 3)])
        Dataset<Row> oms = frameOf([])

        Map<String, Object> result = reconcile(shopify, oms)

        assertEquals(3L, result.onlyInDf1Count as long)
        assertEquals(3L, result.differenceCount as long)
    }

    @Test
    void anOverImportedUnitIsReportedOnTheOmsSide() {
        Dataset<Row> shopify = frameOf([unitKey("6678687481987", "15210699161731", 1)])
        Dataset<Row> oms = frameOf([unitKey("6678687481987", "15210699161731", 1),
                                    unitKey("6678687481987", "15210699161731", 2)])

        Map<String, Object> result = reconcile(shopify, oms)

        assertEquals(0L, result.onlyInDf1Count as long)
        assertEquals(1L, result.onlyInDf2Count as long, "OMS holds a unit Shopify never sold")
    }
}
