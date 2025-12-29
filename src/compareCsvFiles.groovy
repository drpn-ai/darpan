import org.apache.spark.sql.SparkSession
import org.slf4j.LoggerFactory

def logger = LoggerFactory.getLogger("darpan.CsvFilesCompare")
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

def collectIds = { df ->
    return df.collect().collect { row -> row.get(0)?.toString() }
            .findAll { it != null && it.trim() }
            .sort()
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

String outputBaseLocation = outputLocation ?: "tmp/darpan/csv-diff/output"
def outputLocationRef = ec.resource.getLocationReference(outputBaseLocation)
File outputDir = outputLocationRef?.getFile()
if (outputDir == null) {
    outputDir = outputBaseLocation.startsWith("/") ?
            new File(outputBaseLocation) :
            new File((String) ec.factory.getRuntimePath(), outputBaseLocation)
}
if (!outputDir.exists()) outputDir.mkdirs()

String timestamp = ec.l10n.format(ec.user.nowTimestamp, "yyyyMMdd-HHmmss")
String baseFileName = outputFileName ?: "csv-diff-${timestamp}.csv"
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

logStatus("compare csv: before Spark session")
SparkSession spark = null
try {
    spark = SparkSession.builder()
            .appName(sparkAppName ?: "DarpanCsvCompare")
            .master(sparkMaster ?: "local[*]")
            .getOrCreate()

    def reader = spark.read().option("header", includeHeader.toString()).option("multiLine", "true")
    def loadCsv = { String path, String fieldName, String label ->
        logger.info("loading ${label} from ${path} using ${compareLabel} field '${fieldName}'")
        def raw = reader.csv(path)
        return raw.selectExpr("${fieldName} as compare_id")
                .filter("compare_id IS NOT NULL AND length(trim(compare_id)) > 0")
                .dropDuplicates()
    }

    def csv1Df = loadCsv(csv1Path, csv1Field, "CSV 1")
    logStatus("compare csv: after load CSV 1")
    def csv2Df = loadCsv(csv2Path, csv2Field, "CSV 2")

    if (logInputCounts) {
        logger.info("loaded CSV id sets: csv1UniqueIds=${csv1Df.count()} csv2UniqueIds=${csv2Df.count()}")
    }
    logStatus("compare csv: after load + dedupe")

    def onlyInCsv1Df = csv1Df.join(csv2Df, "compare_id", "left_anti").select("compare_id")
    def onlyInCsv2Df = csv2Df.join(csv1Df, "compare_id", "left_anti").select("compare_id")
    logStatus("compare csv: after anti-joins")

    onlyInCsv1 = collectIds(onlyInCsv1Df)
    onlyInCsv2 = collectIds(onlyInCsv2Df)

    onlyInCsv1Count = onlyInCsv1.size()
    onlyInCsv2Count = onlyInCsv2.size()
    diffRowCount = onlyInCsv1Count + onlyInCsv2Count

    outputFile.withWriter("UTF-8") { writer ->
        writer.write("side,${outputField}\n")
        onlyInCsv1.each { id -> writer.write("csv1_only,\"${safeCsv(id)}\"\n") }
        onlyInCsv2.each { id -> writer.write("csv2_only,\"${safeCsv(id)}\"\n") }
    }

    logger.info("comparison results: onlyInCsv1=${onlyInCsv1Count} onlyInCsv2=${onlyInCsv2Count} diffFile=${diffFileName}")
    logStatus("compare csv: after write diff")
} finally {
    if (spark != null) {
        spark.stop()
        logger.info("compare csv: Spark session stopped")
    }
}
