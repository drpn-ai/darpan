package darpan.dataset

import darpan.facade.common.DataManagerSupport
import darpan.reconciliation.core.JsonRecordLines
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.compress.CompressionCodec
import org.apache.hadoop.io.compress.CompressionCodecFactory
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Row
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.api.java.UDF1
import org.apache.spark.sql.functions
import org.apache.spark.sql.types.DataTypes
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.lang.management.ManagementFactory
import java.sql.Timestamp
import java.time.Instant

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 plan 3b-2b, Task 1. Measurement only: how long does Spark take to count one 31-day window of a kept copy by
 * (slice) KeptCopySupport.slice to a run folder, then JsonRecordLines and spark.read().json, against (direct) one
 * spark.read().json over the copy's records.jsonl.gz with the window placed by KeptCopyStore.parseInstant in a UDF?
 * 260,000 synthetic Shopify-shaped records (17 top-level fields, nested line items and transactions, about 700 bytes each:
 * Steve Madden's month of 125,602 pairs over 62 days) go through KeptCopyStore.replace. Each route is warmed once on a
 * 1,000-record copy. Records wall ms, peak heap MB (MemoryMXBean sampled every 50 ms, a GC before each route), the Spark
 * partition count and the gzip codec. Decides nothing; writes build/kept-copy-spark-read.txt.
 *
 * Tagged perf: run with `./gradlew :runtime:component:darpan:perfTest`.
 */
@Tag("perf")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeptCopySparkReadProbe {

    private static final int RECORDS = 260000
    private static final int WARM_RECORDS = 1000
    private static final long DAY = 86_400_000L
    private static final Instant BASE = Instant.parse("2026-09-01T00:00:00Z")
    private static final int SPAN_DAYS = 62
    private static final int WINDOW_DAYS = 31
    private static final Timestamp READ_THRU = Timestamp.from(BASE.plusMillis(70 * DAY))

    private ExecutionContext ec
    private SparkSession spark

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "kept-copy-spark-read-probe")
        spark = SparkSession.builder().appName("KeptCopySparkReadProbe").master("local[*]")
                .config("spark.ui.enabled", "false").getOrCreate()
    }

    @AfterAll
    void cleanup() {
        File root = null
        try { root = DataManagerSupport.resolveDirectoryFile(ec, DataManagerSupport.resolveDataManagerLocation(ec), false) } catch (Throwable ignored) { }
        try { spark?.stop() } finally { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }
        // the probe's ~240 MB of copies and slices: only a folder this probe owns is ever removed
        if (root != null && root.path.contains("test-data-manager") && root.name == "kept-copy-spark-read-probe") root.deleteDir()
    }

    /** Serializable: Spark ships it to tasks, so it holds only the window's millis. */
    static class InWindow implements UDF1<String, Boolean>, Serializable {
        final long fromMillis, toMillis
        InWindow(long fromMillis, long toMillis) { this.fromMillis = fromMillis; this.toMillis = toMillis }
        @Override Boolean call(String value) {
            Instant at = KeptCopyStore.parseInstant(value)
            return at != null && at.toEpochMilli() >= fromMillis && at.toEpochMilli() < toMillis
        }
    }

    /** One Shopify-shaped order: 17 top-level fields, 2 line items, 1 transaction, about 700 bytes of JSON. */
    private static Iterator<Map> orders(int count) {
        long stepMillis = (SPAN_DAYS * DAY) / count
        int[] i = [0] as int[]
        return new Iterator<Map>() {
            boolean hasNext() { return i[0] < count }
            Map next() {
                int n = i[0]++
                Instant created = BASE.plusMillis(n * stepMillis)
                String ts = created.toString()
                return [id: "gid://shopify/Order/${n}".toString(), name: "#SM${100000 + n}".toString(), createdAt: ts,
                        updatedAt: created.plusSeconds(600).toString(), displayFinancialStatus: n % 7 == 0 ? "PARTIALLY_REFUNDED" : "PAID",
                        displayFulfillmentStatus: n % 5 == 0 ? "UNFULFILLED" : "FULFILLED", sourceName: n % 3 == 0 ? "pos" : "web",
                        currencyCode: "USD", email: "buyer${n}@example.com".toString(), test: false, cancelledAt: null,
                        totalPrice: String.format("%.2f", 40 + (n % 500) / 3.0d), subtotalPrice: String.format("%.2f", 35 + (n % 500) / 3.0d),
                        totalTax: "3.25", tags: ["retail", "q4"], note: null,
                        lineItems: [[id: "gid://shopify/LineItem/${n}1".toString(), sku: "SKU-${n % 900}".toString(), quantity: 1 + n % 3,
                                     title: "Leather loafer", variantTitle: "9 / Black", price: "35.00"],
                                    [id: "gid://shopify/LineItem/${n}2".toString(), sku: "SKU-${(n + 7) % 900}".toString(), quantity: 1,
                                     title: "Canvas sneaker", variantTitle: "8 / White", price: "20.00"]],
                        transactions: [[id: "gid://shopify/OrderTransaction/${n}".toString(), kind: "SALE", status: "SUCCESS",
                                        gateway: "shopify_payments", amount: "55.00", processedAt: created.plusSeconds(5).toString()]]]
            }
        }
    }

    private File copyDir(String name, int count) {
        File dir = DatasetRefreshSupport.datasetDir(ec, "KCPROBE", name)
        dir.deleteDir()
        KeptCopyStore.replace(dir, orders(count), "id", "updatedAt", "createdAt", new Timestamp(BASE.toEpochMilli()), READ_THRU,
                READ_THRU, 2)
        return dir
    }

    private Map sliceRoute(File dir, Instant from, Instant to, String token) {
        Map manifest = KeptCopyStore.manifest(dir)
        String location = DataManagerSupport.resolveReconciliationRunLocation(ec, "kcprobe-${token}", "20261120-000000000")
        Map side = [windowStart: Timestamp.from(from), windowEnd: Timestamp.from(to), windowMode: KeptCopySupport.EXACT_INSTANTS,
                    systemEnumId: "SHOPIFY", sourceConfigId: "probe", outputLocation: location, fileName: "slice.json"]
        Map served = KeptCopySupport.slice(ec, side, [datasetId: "probe"], dir, manifest, "createdAt", [field: "createdAt"])
        assertTrue(served.served as boolean, served.reason as String)
        File extract = served.extractFile as File
        File besideUnder = DataManagerSupport.resolveDirectoryFile(ec, DataManagerSupport.resolveDataManagerLocation(ec), false)
        File lines = JsonRecordLines.forRecordArray(extract.absolutePath, [[needsExplode: true, arrayPath: "records"]], besideUnder)
        Dataset<Row> df = spark.read().json(lines.absolutePath)
        int partitions = df.javaRDD().getNumPartitions()
        long count = df.count()
        return [count: count, partitions: partitions]
    }

    private Map directRoute(File dir, Instant from, Instant to) {
        File records = new File(new File(dir, KeptCopyStore.manifest(dir).version as String), KeptCopyStore.RECORDS_FILE)
        spark.udf().register("kcInWindow", new InWindow(from.toEpochMilli(), to.toEpochMilli()), DataTypes.BooleanType)
        Dataset<Row> df = spark.read().json(records.absolutePath).select("record.*")
                .filter(functions.callUDF("kcInWindow", functions.col("createdAt")))
        int partitions = df.javaRDD().getNumPartitions()
        long count = df.count()
        return [count: count, partitions: partitions]
    }

    /** Runs the route, a GC first, sampling the used heap every 50 ms; [wallMs, peakHeapMb] plus the route's own result. */
    private static Map measured(Closure<Map> route) {
        System.gc()
        def memory = ManagementFactory.memoryMXBean
        long baseline = memory.heapMemoryUsage.used
        long[] peak = [baseline] as long[]
        boolean[] running = [true] as boolean[]
        Thread sampler = new Thread({
            while (running[0]) {
                long used = memory.heapMemoryUsage.used
                if (used > peak[0]) peak[0] = used
                try { Thread.sleep(50) } catch (InterruptedException ignored) { return }
            }
        } as Runnable, "heap-sampler")
        sampler.daemon = true
        sampler.start()
        long t0 = System.nanoTime()
        Map result
        long t1
        try { result = route.call() } finally { t1 = System.nanoTime(); running[0] = false; sampler.join() }
        long wallMs = (t1 - t0).intdiv(1_000_000L)
        long used = memory.heapMemoryUsage.used
        if (used > peak[0]) peak[0] = used
        return result + [wallMs: wallMs, peakHeapMb: peak[0].intdiv(1024 * 1024), baselineMb: baseline.intdiv(1024 * 1024),
                          peakOverBaselineMb: (peak[0] - baseline).intdiv(1024 * 1024)]
    }

    @Test
    void measureSliceAgainstDirect() {
        Instant from = BASE.plusMillis(15 * DAY), to = from.plusMillis(WINDOW_DAYS * DAY)

        // warm each route once on a small copy: Spark, Jackson, the JIT and the codec are not charged to a route
        File warm = copyDir("warm", WARM_RECORDS)
        sliceRoute(warm, from, to, "warm")
        directRoute(warm, from, to)

        File big = copyDir("big", RECORDS)
        File gz = new File(new File(big, KeptCopyStore.manifest(big).version as String), KeptCopyStore.RECORDS_FILE)
        CompressionCodec codec = new CompressionCodecFactory(new Configuration()).getCodec(new Path(gz.absolutePath))

        // alternate the order so neither route always inherits the other's warm caches: slice-direct, direct-slice, slice-direct
        List<Map> slices = [], directs = []
        [["slice", "direct"], ["direct", "slice"], ["slice", "direct"]].eachWithIndex { List<String> order, int r ->
            order.each { String route ->
                if (route == "slice") slices << measured { sliceRoute(big, from, to, "big${r}") }
                else directs << measured { directRoute(big, from, to) }
            }
        }
        (slices + directs).each { assertEquals(slices[0].count, it.count, "every run counts the same records") }
        assertTrue((slices[0].count as long) > 0)
        Closure median = { List<Map> runs, String key -> (runs*.get(key) as List<Long>).sort()[1] }
        Closure runsText = { List<Map> runs ->
            runs.collect { "${it.wallMs}ms/+${it.peakOverBaselineMb}MB(base ${it.baselineMb})" }.join(" ")
        }
        long sliceMs = median(slices, "wallMs"), directMs = median(directs, "wallMs")
        long slicePeak = median(slices, "peakOverBaselineMb"), directPeak = median(directs, "peakOverBaselineMb")
        double saved = 100.0d * (1 - (directMs as double) / (sliceMs as double))
        String line = ("records=${RECORDS} window=${WINDOW_DAYS}d counted=${slices[0].count} gzBytes=${gz.length()} " +
                "gzipCodec=${codec?.class?.name ?: 'NOT LOADED'} runs=3 medians " +
                "slice[wallMs=${sliceMs} peakOverBaselineMb=${slicePeak} partitions=${slices[0].partitions}] " +
                "direct[wallMs=${directMs} peakOverBaselineMb=${directPeak} partitions=${directs[0].partitions}] " +
                "directSavesWallPct=${String.format('%.1f', saved)} heapIsIndicativeOnly " +
                "sliceRuns[${runsText(slices)}] directRuns[${runsText(directs)}] " +
                "cores=${Runtime.runtime.availableProcessors()} maxHeapMb=${Runtime.runtime.maxMemory().intdiv(1024 * 1024)}").toString()
        File out = new File("build/kept-copy-spark-read.txt")
        out.parentFile.mkdirs()
        out.text = line + "\n"
        println "KEPT-COPY-SPARK-READ " + line
    }
}
