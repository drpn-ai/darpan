import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Row
import org.apache.spark.storage.StorageLevel
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import org.slf4j.LoggerFactory
import static org.apache.spark.sql.functions.*

def logger = LoggerFactory.getLogger("darpan.reconciliation.mixed.MixedReconciliation")

// --------------------------------------------------------------------------------
// Helper Functions (duplicated for now to avoid cross-script dependencies,
// but should ideally be in a shared Groovy class/script)
// --------------------------------------------------------------------------------

def resolvePath = { String location ->
    def rr = ec.resource.getLocationReference(location)
    if (rr != null && rr.supportsUrl()) {
        def url = rr.getUrl()
        if (url != null) return url.toString()
    }
    return location
}

def normalizeSparkPath = { String jsonPath ->
    if (!jsonPath) return jsonPath
    def path = jsonPath.toString().trim()
    path = path.replaceFirst(/^\$\[\*\]/, "")
    path = path.replaceFirst(/^\$\./, "")
    if (path.startsWith(".")) path = path.substring(1)
    return path
}

def convertJsonPathToSparkSql = { String jsonPath ->
    def path = normalizeSparkPath(jsonPath)
    if (!path) {
        throw new IllegalArgumentException("JSONPath ${jsonPath} resolves to an empty field path.")
    }
    if (path.contains("[*]")) {
        def parts = path.split(/\[\*\]/)
        if (parts.length == 2) {
            def arrayPath = parts[0]
            def afterArray = parts[1].replaceFirst(/^\./, "")
            return [needsExplode: true, arrayPath: arrayPath, fieldPath: afterArray]
        }
    }
    return [needsExplode: false, path: path]
}

def quoteField = { String name ->
    def safe = name?.replace("`", "``")
    return safe ? "`" + safe + "`" : null
}

def assertRootFieldExists = { Dataset df, String rootField, String pathLabel ->
    def rootFields = (df.columns() ?: []) as List
    if (!rootFields.contains(rootField)) {
        throw new IllegalArgumentException("JSONPath ${pathLabel} does not match root fields. Available root fields: ${rootFields.join(', ')}")
    }
}

// --------------------------------------------------------------------------------
// DataFrame Builders
// --------------------------------------------------------------------------------

def buildCsvIdDf = { Dataset rawDf, String fieldName ->
    def fieldExpr = quoteField(fieldName)
    if (!fieldExpr) throw new IllegalArgumentException("CSV id field is required")
    return rawDf
            .selectExpr("${fieldExpr} as compare_id")
            .filter("compare_id IS NOT NULL AND length(trim(compare_id)) > 0")
            .withColumn("compare_id", col("compare_id").cast("string"))
            .dropDuplicates()
}

def buildCsvDataDf = { Dataset rawDf, String fieldName ->
    def fieldExpr = quoteField(fieldName)
    return rawDf
            .selectExpr("${fieldExpr} as compare_id", "struct(*) as data")
            .filter("compare_id IS NOT NULL AND length(trim(compare_id)) > 0")
            .withColumn("compare_id", col("compare_id").cast("string"))
}

def buildJsonIdDf = { Dataset rawDf, Map pathInfo, String pathLabel ->
    if (pathInfo.needsExplode) {
        def arrayPath = normalizeSparkPath(pathInfo.arrayPath)
        def fieldPath = normalizeSparkPath(pathInfo.fieldPath)
        def rootField = arrayPath?.split(/\./)[0]
        if (rootField) assertRootFieldExists(rawDf, rootField, pathLabel)
        
        return rawDf
                .selectExpr("explode(${arrayPath}) as exploded_item")
                .selectExpr("exploded_item.${fieldPath} as compare_id")
                .filter("compare_id IS NOT NULL AND length(trim(cast(compare_id as string))) > 0")
                .withColumn("compare_id", col("compare_id").cast("string"))
                .distinct()
    }
    def safePath = normalizeSparkPath(pathInfo.path)
    def rootField = safePath?.split(/\./)[0]
    if (rootField) assertRootFieldExists(rawDf, rootField, pathLabel)
    
    return rawDf
            .selectExpr("${safePath} as compare_id")
            .filter("compare_id IS NOT NULL AND length(trim(cast(compare_id as string))) > 0")
            .withColumn("compare_id", col("compare_id").cast("string"))
            .distinct()
}

def buildJsonDataDf = { Dataset rawDf, Map pathInfo, String pathLabel ->
    if (pathInfo.needsExplode) {
        def arrayPath = normalizeSparkPath(pathInfo.arrayPath)
        def fieldPath = normalizeSparkPath(pathInfo.fieldPath)
        return rawDf
                .selectExpr("explode(${arrayPath}) as exploded_item")
                .selectExpr("exploded_item.${fieldPath} as compare_id", "exploded_item as data")
                .filter("compare_id IS NOT NULL AND length(trim(cast(compare_id as string))) > 0")
                .withColumn("compare_id", col("compare_id").cast("string"))
    }
    def safePath = normalizeSparkPath(pathInfo.path)
    return rawDf
            .selectExpr("${safePath} as compare_id", "struct(*) as data")
            .filter("compare_id IS NOT NULL AND length(trim(cast(compare_id as string))) > 0")
            .withColumn("compare_id", col("compare_id").cast("string"))
}


// --------------------------------------------------------------------------------
// Main Logic
// --------------------------------------------------------------------------------

if (!file1Location || !file2Location) {
    throw new IllegalArgumentException("Both file locations are required")
}

// Identify which file is CSV and which is JSON
String csvLocation
String jsonLocation
String csvId = csvIdField ?: "id"
// For CSV, normalize ID (strip JSONPath chars if passed by mistake)
if (csvId.startsWith('$')) {
    csvId = csvId.tokenize(".")[-1].replaceAll(/\[.*\]$/, "")
}

String jsonIdPath = jsonIdField ?: '$.id'
// Normalize JSONPath if it's just a key name
if (!jsonIdPath.startsWith('$')) jsonIdPath = "\$.${jsonIdPath}"

// If file1 is CSV
boolean isFile1Csv = (file1Type == 'CSV')
if (isFile1Csv) {
    csvLocation = file1Location
    jsonLocation = file2Location
} else {
    csvLocation = file2Location
    jsonLocation = file1Location
}

logger.info("Mixed Reconciliation: CSV=${csvLocation} (ID=${csvId}) vs JSON=${jsonLocation} (ID=${jsonIdPath})")

SparkSession spark = SparkSession.builder()
        .appName(sparkAppName ?: "MixedReconciliation")
        .master(sparkMaster ?: (ec.resource.properties['spark.master'] ?: "local[*]"))
        .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
        .getOrCreate()

try {
    // 1. Load Data
    String csvPathResolved = resolvePath(csvLocation)
    String jsonPathResolved = resolvePath(jsonLocation)
    
    // Check if JSON file exists effectively before spark reads it
    // (Spark might throw error on read)
    
    Dataset csvRawDf = spark.read()
        .option("header", (hasHeader != null ? hasHeader : true).toString())
        .option("multiLine", "true")
        .csv(csvPathResolved)
        
    Dataset jsonRawDf = spark.read()
        .option("multiLine", "true")
        .json(jsonPathResolved)

    // 2. Prepare ID DataFrames
    Dataset csvIdDf = buildCsvIdDf(csvRawDf, csvId).persist(StorageLevel.DISK_ONLY())
    
    Map jsonPathInfo = convertJsonPathToSparkSql(jsonIdPath)
    Dataset jsonIdDf = buildJsonIdDf(jsonRawDf, jsonPathInfo, jsonIdPath).persist(StorageLevel.DISK_ONLY())
    
    // 3. Compare (Anti-Join)
    Dataset onlyInCsvDf = csvIdDf.join(jsonIdDf, "compare_id", "left_anti")
    Dataset onlyInJsonDf = jsonIdDf.join(csvIdDf, "compare_id", "left_anti")
    
    // 4. Map back to File 1 / File 2
    Dataset onlyInFile1Df = isFile1Csv ? onlyInCsvDf : onlyInJsonDf
    Dataset onlyInFile2Df = isFile1Csv ? onlyInJsonDf : onlyInCsvDf
    
    long count1 = onlyInFile1Df.count()
    long count2 = onlyInFile2Df.count()
    long totalDiff = count1 + count2
    
    // 5. Build Result DataFrames with full data (for the diff file)
    // We only construct the heavy dataframes IF there are differences
    
    Dataset finalDiffDf = null
    
    def normalizeLabel = { str -> str?.replaceAll(/[^A-Za-z0-9_]/, "") ?: "file" }
    String label1 = file1Label ?: "File 1"
    String label2 = file2Label ?: "File 2"
    String type1 = normalizeLabel(label1)
    String type2 = normalizeLabel(label2)
    
    if (totalDiff > 0) {
        // Hydrate CSV differences
        Dataset diffs1 = null
        if (count1 > 0) {
            Dataset dataDf = isFile1Csv ? buildCsvDataDf(csvRawDf, csvId) : buildJsonDataDf(jsonRawDf, jsonPathInfo, jsonIdPath)
            Dataset joined = dataDf.join(onlyInFile1Df, "compare_id", "inner")
            
            diffs1 = joined.select(
                lit("missing_in_${type2}").alias("type"),
                col("compare_id").alias("id"),
                lit(label1).alias("presentIn"),
                lit(label2).alias("missingIn"),
                col("data").alias("data"),
                lit("Present in ${label1}, missing in ${label2}").alias("note")
            )
        }
        
        Dataset diffs2 = null
        if (count2 > 0) {
            Dataset dataDf = isFile1Csv ? buildJsonDataDf(jsonRawDf, jsonPathInfo, jsonIdPath) : buildCsvDataDf(csvRawDf, csvId)
            Dataset joined = dataDf.join(onlyInFile2Df, "compare_id", "inner")
            
            diffs2 = joined.select(
                lit("missing_in_${type1}").alias("type"),
                col("compare_id").alias("id"),
                lit(label2).alias("presentIn"),
                lit(label1).alias("missingIn"),
                col("data").alias("data"),
                lit("Present in ${label2}, missing in ${label1}").alias("note")
            )
        }
        
        if (diffs1 != null && diffs2 != null) {
            finalDiffDf = diffs1.union(diffs2)
        } else if (diffs1 != null) {
            finalDiffDf = diffs1
        } else {
            finalDiffDf = diffs2
        }
    }
    
    // 6. Write Output using Spark (Directory of JSONL)
    String outDirStr = outputLocation ?: "tmp/reconciliation/mixed/output"
    // Use Spark to write directly. 
    // We need to resolve the local path for return values
    
    // Note: Spark writers expect a path relative to FS.
    // If we use Moqui resource logic, we get a java.io.File, getAbsolutePath() works best for local Spark.
    def outRef = ec.resource.getLocationReference(outDirStr)
    File baseDir = outRef.getFile() 
    if (!baseDir.exists()) baseDir.mkdirs()
    
    String safeName = (outputFileName ?: "mixed-diff").replace(".json", "")
    File targetDir = new File(baseDir, safeName)
    
    // If target exists, delete or version? 
    // Spark 'overwrite' mode deletes it.
    
    String finalOutputPath = targetDir.getAbsolutePath()
    
    if (finalDiffDf != null) {
        logger.info("Writing differences to ${finalOutputPath}")
        finalDiffDf.write().mode("overwrite").json(finalOutputPath)
    } else {
        // Create empty dir marker?
        targetDir.mkdirs()
        new File(targetDir, "_SUCCESS").createNewFile()
    }
    
    // 6. Return values
    differenceCount = totalDiff
    onlyInFile1Count = count1
    onlyInFile2Count = count2
    
    diffLocation = outDirStr + "/" + safeName
    diffFileName = safeName // It's a directory name now

} catch (Exception e) {
    logger.error("Mixed Reconciliation Failed", e)
    throw e
}
// Do not stop Spark session
