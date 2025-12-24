import org.apache.spark.sql.SparkSession
import static org.apache.spark.sql.functions.lit
import org.slf4j.LoggerFactory

def logger = LoggerFactory.getLogger("darpan.SampleOrderIds")
def toMb = { long bytes -> (bytes / (1024L * 1024L)) }
def toPercent = { val ->
    if (val == null) return "n/a"
    try {
        return ((val as BigDecimal).setScale(1, BigDecimal.ROUND_HALF_UP)).toString()
    } catch (Exception e) {
        return val.toString()
    }
}
def getUtilization = {
    def smap = ec.factory.getStatusMap(true)
    def util = smap?.Utilization ?: [:]
    return [load: toPercent(util.LoadPercent), heap: toPercent(util.HeapPercent), disk: toPercent(util.DiskPercent)]
}
def logStatus = { String stage ->
    Runtime rt = Runtime.getRuntime()
    long total = rt.totalMemory()
    long free = rt.freeMemory()
    long used = total - free
    long max = rt.maxMemory()
    def util = getUtilization()
    logger.info("${stage} heap used=${toMb(used)}MB total=${toMb(total)}MB max=${toMb(max)}MB heapPct=${util.heap}% load=${util.load}% disk=${util.disk}%")
}

String omsField = omsOrderIdField ?: orderIdField ?: "shopify_order_id"
String shopifyField = shopifyOrderIdField ?: orderIdField ?: "shopify_order_id"

def resolvePath = { String location ->
    def rr = ec.resource.getLocationReference(location)
    if (rr != null && rr.supportsUrl()) {
        def url = rr.getUrl()
        if (url != null) return url.toString()
    }
    return location
}

String omsPath = resolvePath(omsLocation)
String shopifyPath = resolvePath(shopifyLocation)

logStatus("compare bulk orders: before Spark session")
SparkSession spark = null
try {
    spark = SparkSession.builder()
            .appName(sparkAppName ?: "DarpanSampleOrderCompare")
            .master(sparkMaster ?: "local[*]")
            .getOrCreate()

    def reader = spark.read().option("multiLine", "true")
    logger.info("loading OMS bulk data from ${omsPath} using id field '${omsField}'")
    def omsRaw = reader.json(omsPath)
    def omsDf = omsRaw.selectExpr("${omsField} as order_id")
            .filter("order_id IS NOT NULL AND length(trim(order_id)) > 0")
            .dropDuplicates()

    logger.info("loading Shopify bulk data from ${shopifyPath} using id field '${shopifyField}'")
    logStatus("compare bulk orders: after load OMS")
    def shopifyRaw = reader.json(shopifyPath)
    def shopifyDf
    if (shopifyRaw.schema().fieldNames().contains("data")) {
        shopifyDf = shopifyRaw.selectExpr("explode(data.orders.edges) as edge")
                .selectExpr("edge.node.${shopifyField} as order_id")
    } else {
        shopifyDf = shopifyRaw.selectExpr("${shopifyField} as order_id")
    }
    shopifyDf = shopifyDf.filter("order_id IS NOT NULL AND length(trim(order_id)) > 0")
            .dropDuplicates()
    if (logInputCounts) {
        logger.info("loaded bulk order id sets: omsUniqueIds=${omsDf.count()} shopifyUniqueIds=${shopifyDf.count()}")
    }
    logStatus("compare bulk orders: after load + dedupe")

    def onlyInOmsDf = omsDf.join(shopifyDf, "order_id", "left_anti").select("order_id")
    def onlyInShopifyDf = shopifyDf.join(omsDf, "order_id", "left_anti").select("order_id")
    logStatus("compare bulk orders: after anti-joins")

    onlyInOms = onlyInOmsDf.collect().collect { row -> row.get(0)?.toString() }
    onlyInShopify = onlyInShopifyDf.collect().collect { row -> row.get(0)?.toString() }

    onlyInOmsCount = onlyInOms.size()
    onlyInShopifyCount = onlyInShopify.size()
    logger.info("comparison results: onlyInOms=${onlyInOmsCount} onlyInShopify=${onlyInShopifyCount}")
    logStatus("compare bulk orders: after collect results")
} finally {
    if (spark != null) {
        spark.stop()
        logger.info("compare bulk orders: Spark session stopped")
    }
}
