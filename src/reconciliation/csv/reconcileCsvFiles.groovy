import org.apache.spark.sql.SparkSession
import org.slf4j.LoggerFactory
import static org.apache.spark.sql.functions.*

def logger = LoggerFactory.getLogger("reconciliation.csv.CsvReconciliation")
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

def resolvePath = { String location ->
    def rr = ec.resource.getLocationReference(location)
    if (rr != null && rr.supportsUrl()) {
        def url = rr.getUrl()
        if (url != null) return url.toString()
    }
    return location
}

def normalizeBool = { val, boolean defaultValue ->
    if (val == null) return defaultValue
    return val.toString().equalsIgnoreCase("true")
}

def safeCsv = { String value ->
    if (value == null) return ""
    return value.replace("\"", "\"\"")
}

def resolveMappingField = { String mappingId, String systemId, String label ->
    def member = ec.entity.find("darpan.mapping.ReconciliationMappingMember")
            .condition("reconciliationMappingId", mappingId)
            .condition("systemEnumId", systemId)
            .one()
    if (!member?.systemFieldName) {
        throw new IllegalArgumentException("Mapping member not found for mapping ${mappingId} and ${label} system ${systemId}")
    }
    return member.systemFieldName
}



if (!csv1Location || !csv2Location) {
    throw new IllegalArgumentException("csv1Location and csv2Location are required")
}

String mappingId = reconciliationMappingId?.toString()?.trim()
String csv1Field = null
String csv2Field = null
String outputField = null
String compareLabel = "id field"

if (mappingId) {
    String csv1SystemId = csv1SystemEnumId?.toString()?.trim()
    String csv2SystemId = csv2SystemEnumId?.toString()?.trim()
    if (!csv1SystemId || !csv2SystemId) {
        throw new IllegalArgumentException("csv1SystemEnumId and csv2SystemEnumId are required when reconciliationMappingId is provided")
    }

    def mapping = ec.entity.find("darpan.mapping.ReconciliationMapping")
            .condition("reconciliationMappingId", mappingId)
            .one()
    if (!mapping) {
        throw new IllegalArgumentException("ReconciliationMapping ${mappingId} not found")
    }

    csv1Field = resolveMappingField(mappingId, csv1SystemId, "CSV 1")
    csv2Field = resolveMappingField(mappingId, csv2SystemId, "CSV 2")
    outputField = mapping.mappingName ?: mappingId
    compareLabel = "mapping ${mappingId}"
} else {
    csv1Field = csv1IdField ?: idField ?: "order_id"
    csv2Field = csv2IdField ?: idField ?: "order_id"
    outputField = idField ?: csv1Field
}

csv1Field = csv1Field?.toString()?.trim()
csv2Field = csv2Field?.toString()?.trim()
outputField = outputField?.toString()?.trim()
if (!csv1Field || !csv2Field) {
    throw new IllegalArgumentException("Unable to resolve compare field names for CSV 1 and CSV 2")
}
if (!outputField) outputField = csv1Field
boolean includeHeader = normalizeBool(hasHeader, true)

String csv1Path = resolvePath(csv1Location)
String csv2Path = resolvePath(csv2Location)

String outputBaseLocation = outputLocation ?: "tmp/reconciliation/csv-diff/output"
def outputLocationRef = ec.resource.getLocationReference(outputBaseLocation)
File outputDir = outputLocationRef?.getFile()
if (outputDir == null) {
    outputDir = outputBaseLocation.startsWith("/") ?
            new File(outputBaseLocation) :
            new File((String) ec.factory.getRuntimePath(), outputBaseLocation)
}
if (!outputDir.exists()) outputDir.mkdirs()

String timestamp = ec.l10n.format(ec.user.nowTimestamp, "yyyyMMdd-HHmmss")
String mappingSlug = (mappingId ?: "csv").toString().replaceAll("[^A-Za-z0-9._-]", "_")
String baseFileName = outputFileName ?: "${mappingSlug}-diff-${timestamp}.csv"
if (!baseFileName.endsWith(".csv")) baseFileName = baseFileName + ".csv"
String diffFileName = baseFileName
String nameRoot = baseFileName.endsWith(".csv") ? baseFileName[0..-5] : baseFileName
File outputFile = new File(outputDir, diffFileName)
int suffix = 1
while (outputFile.exists()) {
    diffFileName = "${nameRoot}-${suffix}.csv"
    outputFile = new File(outputDir, diffFileName)
    suffix++
}

def outputLocationBase = outputBaseLocation.endsWith("/") ? outputBaseLocation[0..-2] : outputBaseLocation

diffLocation = "${outputLocationBase}/${diffFileName}"

logStatus("reconcile csv: before Spark session")
SparkSession spark = null
try {
    spark = SparkSession.builder()
            .appName(sparkAppName ?: "ReconciliationCsvCompare")
            .master(sparkMaster ?: "local[*]")
            .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
            .config("spark.sql.adaptive.enabled", "true")
            .config("spark.sql.adaptive.coalescePartitions.enabled", "true")
            .config("spark.sql.files.maxPartitionBytes", "134217728")
            .getOrCreate()

    def reader = spark.read().option("header", includeHeader.toString()).option("multiLine", "true")
    def loadCsv = { String path, String fieldName, String label ->
        logger.info("loading ${label} from ${path} using ${compareLabel} field '${fieldName}'")
        def raw = reader.csv(path)
        return raw.selectExpr("${fieldName} as compare_id")
                .filter("compare_id IS NOT NULL AND length(trim(compare_id)) > 0")
                .dropDuplicates()
    }

    def csv1Df = loadCsv(csv1Path, csv1Field, "CSV 1").cache()
    logStatus("reconcile csv: after load CSV 1")
    def csv2Df = loadCsv(csv2Path, csv2Field, "CSV 2").cache()

    if (logInputCounts) {
        logger.info("loaded CSV id sets: csv1UniqueIds=${csv1Df.count()} csv2UniqueIds=${csv2Df.count()}")
    }
    logStatus("reconcile csv: after load + dedupe")

    def onlyInCsv1Df = csv1Df.join(csv2Df, "compare_id", "left_anti").select("compare_id")
    def onlyInCsv2Df = csv2Df.join(csv1Df, "compare_id", "left_anti").select("compare_id")
    logStatus("reconcile csv: after anti-joins")

    // Get counts only - don't collect actual data
    onlyInCsv1Count = onlyInCsv1Df.count()
    onlyInCsv2Count = onlyInCsv2Df.count()
    diffRowCount = onlyInCsv1Count + onlyInCsv2Count

    String csv1SystemLabel = csv1SystemEnumId?.toString()?.trim()
    String csv2SystemLabel = csv2SystemEnumId?.toString()?.trim()
    String csv1SideLabel = csv1SystemLabel ? "Only in ${csv1SystemLabel}" : "Only in CSV 1"
    String csv2SideLabel = csv2SystemLabel ? "Only in ${csv2SystemLabel}" : "Only in CSV 2"

    // Use Spark distributed write instead of collecting to memory
    // Add source labels as columns
    def csv1WithSource = onlyInCsv1Df
            .withColumnRenamed("compare_id", outputField)
            .withColumn("source", lit(csv1SideLabel))
            .select("source", outputField)

    def csv2WithSource = onlyInCsv2Df
            .withColumnRenamed("compare_id", outputField)
            .withColumn("source", lit(csv2SideLabel))
            .select("source", outputField)

    // Union and write in distributed fashion to temp directory
    String tempOutputPath = "${outputDir.absolutePath}/_temp_${timestamp}"
    csv1WithSource.union(csv2WithSource)
            .coalesce(1)  // Single output file
            .write()
            .mode("overwrite")
            .option("header", "true")
            .csv(tempOutputPath)

    // Move the part file to final location
    def tempDir = new File(tempOutputPath)
    def partFiles = tempDir.listFiles()?.findAll { it.name.startsWith("part-") && it.name.endsWith(".csv") }
    if (partFiles && partFiles.size() > 0) {
        partFiles[0].renameTo(outputFile)
        logger.info("Moved output file to ${outputFile.absolutePath}")
    } else {
        logger.warn("No part files found in temp directory, output may be empty")
    }
    
    // Clean up temp directory
    tempDir.deleteDir()

    // Unpersist cached DataFrames
    csv1Df.unpersist()
    csv2Df.unpersist()

    logger.info("comparison results: onlyInCsv1=${onlyInCsv1Count} onlyInCsv2=${onlyInCsv2Count} diffFile=${diffFileName}")
    logStatus("reconcile csv: after write diff + cleanup")
} catch (Exception e) {
    logger.error("CSV reconciliation failed", e)
    throw e
}
// DON'T call spark.stop() - keep session alive for reuse
logger.info("reconcile csv: Spark session preserved for reuse (not stopped)")
