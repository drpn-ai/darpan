package darpan.reconciliation.core

import groovy.json.JsonOutput
import org.apache.spark.sql.Column
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Row
import org.apache.spark.sql.RowFactory
import org.apache.spark.sql.SparkSession
import org.apache.spark.storage.StorageLevel
import org.apache.spark.sql.types.DataTypes
import org.apache.spark.sql.types.StructType
import org.moqui.context.ExecutionContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import static org.apache.spark.sql.functions.*

class ReconciliationServices {
    private static final Logger logger = LoggerFactory.getLogger(ReconciliationServices.class)
    private static final StructType MISSING_DIFF_SCHEMA = new StructType()
            .add("type", DataTypes.StringType, true)
            .add("id", DataTypes.StringType, true)
            .add("presentIn", DataTypes.StringType, true)
            .add("missingIn", DataTypes.StringType, true)
            .add("data", DataTypes.StringType, true)
            .add("note", DataTypes.StringType, true)
    private static final StructType RULESET_DIFF_SCHEMA = new StructType()
            .add("diffType", DataTypes.StringType, true)
            .add("compareScopeId", DataTypes.StringType, true)
            .add("objectType", DataTypes.StringType, true)
            .add("primaryId", DataTypes.StringType, true)
            .add("field", DataTypes.StringType, true)
            .add("file1Value", DataTypes.StringType, true)
            .add("file2Value", DataTypes.StringType, true)
            .add("presentIn", DataTypes.StringType, true)
            .add("missingIn", DataTypes.StringType, true)
            .add("data", DataTypes.StringType, true)
            .add("ruleId", DataTypes.StringType, true)
            .add("severity", DataTypes.StringType, true)
            .add("message", DataTypes.StringType, true)

    /**
     * Core Spark-based reconciliation of two ID DataFrames.
     */
    static Map<String, Object> reconcileIdDataFrames(ExecutionContext ec) {
        return reconcileIdDataFramesInternal(ec, (Map<String, Object>) ec.contextStack)
    }

    private static Map<String, Object> reconcileIdDataFramesInternal(ExecutionContext ec, Map<String, Object> context) {
        Dataset df1 = (Dataset) context.get("df1")
        Dataset df2 = (Dataset) context.get("df2")
        String idCol = (String) context.get("idColumnName") ?: "compare_id"
        String df1LabelStr = (String) context.get("df1Label") ?: "DataFrame 1"
        String df2LabelStr = (String) context.get("df2Label") ?: "DataFrame 2"

        if (df1 == null || df2 == null) {
            ec.message.addError("df1 and df2 DataFrames are required")
            return [:]
        }

        logger.info("Starting DataFrame reconciliation: df1Label=${df1LabelStr} df2Label=${df2LabelStr} idColumn=${idCol}")

        // Perform anti-joins to find differences
        Dataset onlyInDf1Temp = df1.join(df2, df1.col(idCol).equalTo(df2.col(idCol)), "left_anti")
                                   .select(df1.col(idCol).as(idCol))
                                   .distinct()

        Dataset onlyInDf2Temp = df2.join(df1, df1.col(idCol).equalTo(df2.col(idCol)), "left_anti")
                                   .select(df2.col(idCol).as(idCol))
                                   .distinct()

        // Get counts
        long count1 = onlyInDf1Temp.count()
        long count2 = onlyInDf2Temp.count()
        long differenceCount = count1 + count2

        logger.info("DataFrame reconciliation complete: onlyIn${df1LabelStr}=${count1} onlyIn${df2LabelStr}=${count2} total=${differenceCount}")

        Map<String, Object> result = [:]
        result.put("onlyInDf1", onlyInDf1Temp)
        result.put("onlyInDf2", onlyInDf2Temp)
        result.put("onlyInDf1Count", count1)
        result.put("onlyInDf2Count", count2)
        result.put("differenceCount", differenceCount)
        return result
    }

    /**
     * Normalize CSV/JSON file inputs, perform reconciliation, and emit a JSON diff output.
     */
    static Map<String, Object> reconcileUnifiedFiles(ExecutionContext ec) {
        return reconcileUnifiedFilesInternal(ec, (Map<String, Object>) ec.contextStack)
    }

    private static Map<String, Object> reconcileUnifiedFilesInternal(ExecutionContext ec, Map<String, Object> context) {
        Dataset onlyIn1Df = null
        Dataset onlyIn2Df = null
        Dataset idDf1 = null
        Dataset idDf2 = null
        
        try {
            String file1Location = (String) context.get("file1Location")
            String file2Location = (String) context.get("file2Location")
            String file1Type = (String) context.get("file1Type")
            String file2Type = (String) context.get("file2Type")
            String file1IdField = (String) context.get("file1IdField")
            String file2IdField = (String) context.get("file2IdField")
            String file1IdExpression = (String) context.get("file1IdExpression")
            String file2IdExpression = (String) context.get("file2IdExpression")
            String file1SchemaFileName = (String) context.get("file1SchemaFileName")
            String file2SchemaFileName = (String) context.get("file2SchemaFileName")
            String file1LabelParam = (String) context.get("file1Label")
            String file2LabelParam = (String) context.get("file2Label")
            String reconciliationMappingId = (String) context.get("reconciliationMappingId")
            String reconciliationMappingName = (String) context.get("reconciliationMappingName")
            Boolean hasHeader = (Boolean) context.get("hasHeader")
            String outputLocation = (String) context.get("outputLocation") ?: "runtime://tmp/reconciliation/unified/output"
            String outputFileName = (String) context.get("outputFileName")
            String sparkMaster = (String) context.get("sparkMaster") ?: "local[*]"
            String sparkAppName = (String) context.get("sparkAppName") ?: "UnifiedReconciliation"
            
            List<String> processingWarnings = (List<String>) context.get("processingWarnings") ?: []
            List<String> validationErrors = (List<String>) context.get("validationErrors") ?: []


            if (!file1Location || !file2Location || !file1Type || !file2Type) {
                 ec.message.addError("Required parameters missing: file1Location, file2Location, file1Type, file2Type")
                 return [:]
            }
            
            String label1 = normalize(file1LabelParam) ?: "File 1"
            String label2 = normalize(file2LabelParam) ?: "File 2"
            String type1 = normalize(file1Type)?.toUpperCase()
            String type2 = normalize(file2Type)?.toUpperCase()
            
            Map id1Spec = parseIdSpec(file1IdExpression ?: file1IdField, "CSV".equals(type1))
            Map id2Spec = parseIdSpec(file2IdExpression ?: file2IdField, "CSV".equals(type2))
            
            String reconType = "${type1}_${type2}"
            String reconciliationType = (reconType == "CSV_CSV") ? "CSV" : (reconType == "JSON_JSON" ? "JSON" : "MIXED")

            SparkSession spark = SparkSession.builder()
                    .appName(sparkAppName)
                    .master(sparkMaster)
                    .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
                     .config("spark.sql.adaptive.enabled", "true")
                    .getOrCreate()

            // Ingestion
            Map ingest1 = ingestFile(ec, spark, file1Location, type1, id1Spec, hasHeader ?: true, label1, validationErrors, file1SchemaFileName)
            Map ingest2 = ingestFile(ec, spark, file2Location, type2, id2Spec, hasHeader ?: true, label2, validationErrors, file2SchemaFileName)
            
            idDf1 = (Dataset) ingest1.idDf
            Dataset dataDf1 = (Dataset) ingest1.dataDf
            idDf2 = (Dataset) ingest2.idDf
            Dataset dataDf2 = (Dataset) ingest2.dataDf

            idDf1 = idDf1.persist(StorageLevel.DISK_ONLY())
            idDf2 = idDf2.persist(StorageLevel.DISK_ONLY())

            // Delegation to Core Logic
            Map<String, Object> coreInput = [
                df1: idDf1,
                df2: idDf2,
                idColumnName: "compare_id",
                df1Label: label1,
                df2Label: label2
            ]
            Map<String, Object> coreResult = reconcileIdDataFramesInternal(ec, coreInput)

            onlyIn1Df = (Dataset) coreResult.get("onlyInDf1")
            onlyIn2Df = (Dataset) coreResult.get("onlyInDf2")
            Long count1 = (Long) coreResult.get("onlyInDf1Count")
            Long count2 = (Long) coreResult.get("onlyInDf2Count")
            Long differenceCount = (Long) coreResult.get("differenceCount")
            
             // Output Formatting
             Dataset diffDf = null
             if (differenceCount > 0) {
                String label1Norm = label1?.replaceAll(/[^A-Za-z0-9_]/, "") ?: "file1"
                String label2Norm = label2?.replaceAll(/[^A-Za-z0-9_]/, "") ?: "file2"
                String missingInTypeForFile1 = "missing_in_" + label2Norm
                String missingInTypeForFile2 = "missing_in_" + label1Norm
                String noteForFile1 = "Present in " + label1 + ", missing in " + label2
                String noteForFile2 = "Present in " + label2 + ", missing in " + label1
                
                Dataset diffs1 = null
                if (count1 > 0) {
                    Dataset joined1 = dataDf1.join(onlyIn1Df, "compare_id", "inner")
                    diffs1 = joined1.select(
                            lit(missingInTypeForFile1).alias("type"),
                            col("compare_id").alias("id"),
                            lit(label1).alias("presentIn"),
                            lit(label2).alias("missingIn"),
                            to_json(col("data")).alias("data"),
                            lit(noteForFile1).alias("note")
                    )
                }
                Dataset diffs2 = null
                if (count2 > 0) {
                    Dataset joined2 = dataDf2.join(onlyIn2Df, "compare_id", "inner")
                    diffs2 = joined2.select(
                            lit(missingInTypeForFile2).alias("type"),
                            col("compare_id").alias("id"),
                            lit(label2).alias("presentIn"),
                            lit(label1).alias("missingIn"),
                            to_json(col("data")).alias("data"),
                            lit(noteForFile2).alias("note")
                    )
                }
                if (diffs1 != null && diffs2 != null) diffDf = diffs1.union(diffs2)
                else if (diffs1 != null) diffDf = diffs1
                else diffDf = diffs2
             }

            // Write Output
             String diffLocation = null
             String diffFileName = null
             
             String outputBaseLocation = outputLocation
             def outputRef = ec.resource.getLocationReference(outputBaseLocation)
             File outputDir = outputRef?.getFile()
             if (outputDir == null) {
                  String runtimePath = ec.factory.getRuntimePath()
                  outputDir = new File(runtimePath, outputBaseLocation.replace("runtime://", ""))
             }
             if (!outputDir.exists()) outputDir.mkdirs()
             
             String timestamp = ec.l10n.format(ec.user.nowTimestamp, "yyyyMMdd-HHmmss")
             String mappingSlug = reconciliationMappingName ? reconciliationMappingName.replaceAll(/[^A-Za-z0-9_-]/, "-") : null
             String basePrefix = (reconciliationType == "CSV") ? "csv" : (reconciliationType == "JSON" ? "json" : "mixed")
             String defaultBase = mappingSlug ? "${mappingSlug}-diff-${timestamp}.json" : "${basePrefix}-diff-${timestamp}.json"
             String baseFileName = outputFileName ?: defaultBase
             if (!baseFileName.toLowerCase().endsWith(".json")) baseFileName = baseFileName + ".json"
             
             File outputFile = new File(outputDir, baseFileName)
             int suffix = 1
             String nameRoot = baseFileName.indexOf(".") > 0 ? baseFileName.substring(0, baseFileName.lastIndexOf(".")) : baseFileName
             while (outputFile.exists()) {
                 outputFile = new File(outputDir, "${nameRoot}-${suffix}.json")
                 suffix++
             }
             baseFileName = outputFile.getName()
             
            diffLocation = outputFile.getAbsolutePath()
            diffFileName = baseFileName
             
             // Write JSON
            Map outputMetadata = [
                timestamp: ec.user.nowTimestamp?.toString(),
                file1Label: label1,
                file2Label: label2,
                file1Type: type1,
                file2Type: type2,
                reconciliation: reconciliationType,
                reconciliationMappingId: reconciliationMappingId,
                reconciliationMappingName: reconciliationMappingName
            ]
            Map outputSummary = [
                totalDifferences: differenceCount,
                onlyInFile1Count: count1,
                onlyInFile2Count: count2
            ]

            outputFile.withWriter("UTF-8") { writer ->
                writer << "{\n"
                writer << "\"metadata\":" + JsonOutput.toJson(outputMetadata) + ",\n"
                writer << "\"summary\":" + JsonOutput.toJson(outputSummary) + ",\n"
                writer << "\"validationErrors\":" + JsonOutput.toJson(validationErrors) + ",\n"
                writer << "\"differences\":["
                boolean first = true
                if (differenceCount > 0 && diffDf != null) {
                    def iter = diffDf.toJSON().toLocalIterator()
                    while (iter.hasNext()) {
                        def rowJson = iter.next()
                        if (!first) writer << ","
                        writer << "\n" << rowJson
                        first = false
                    }
                }
                writer << "]\n}"
            }
            
            // Clean up persisted dfs
            if (onlyIn1Df) onlyIn1Df.unpersist()
            if (onlyIn2Df) onlyIn2Df.unpersist()
            if (idDf1) idDf1.unpersist()
            if (idDf2) idDf2.unpersist()

            Map<String, Object> result = [:]
            result.put("reconciliationType", reconciliationType)
            result.put("diffLocation", diffLocation)
            result.put("diffFileName", diffFileName)
            result.put("differenceCount", differenceCount)
            result.put("onlyInFile1Count", count1)
            result.put("onlyInFile2Count", count2)
            result.put("validationErrors", validationErrors)
            result.put("processingWarnings", processingWarnings)
            return result
        } catch (Exception e) {
             logger.error("Unified reconciliation failed", e)
             ec.message.addError(e.getMessage())
             return [:]
        }
    }

    // --- Private Helpers ---

    static Map ingestFile(ExecutionContext ec, SparkSession spark, String loc, String type, Map idSpec, boolean hasHeader, String label, List validationErrors, String schemaFile) {
        String path = resolvePath(ec, loc)
        String idExpr = normalize(idSpec?.idExpr)
        String idNormalizer = normalize(idSpec?.idNormalizer)
        Dataset df = null
        Dataset idDf = null
        Dataset dataDf = null
        
        if (type == "JSON") {
            // Validate if needed
             if (schemaFile) {
                try {
                    def result = ec.service.sync().name("jsonschema.JsonSchemaServices.validate#JsonLocationAgainstSchema")
                        .parameters([jsonLocation: loc, schemaFileName: schemaFile]).call()
                    if (!result.valid) {
                         ((List)result.errorMessages).each { validationErrors.add("${label}: ${it}") }
                    }
                } catch (Exception e) { validationErrors.add("${label}: Validation check failed: ${e.message}") }
             }
             
            df = spark.read().option("multiLine", "true").json(path)
            Map pathInfo = convertJsonPathToSpark(idExpr)
            idDf = buildJsonIdDf(df, pathInfo, label, idNormalizer)
            dataDf = buildJsonDataDf(df, pathInfo, label, idNormalizer)
        } else {
             // CSV
             df = spark.read().option("header", hasHeader.toString()).option("multiLine", "true").csv(path)
             idDf = buildCsvIdDf(df, idExpr, idNormalizer)
             dataDf = buildCsvDataDf(df, idExpr, idNormalizer)
        }
        return [idDf: idDf, dataDf: dataDf]
    }
    
    static String resolvePath(ExecutionContext ec, String location) {
         def rr = ec.resource.getLocationReference(location)
         if (rr != null && rr.supportsUrl()) {
             def url = rr.getUrl()
             if ("file".equalsIgnoreCase(url.protocol)) {
                 try { return new File(url.toURI()).getAbsolutePath() } catch (Exception e) { return url.getPath() }
             }
             return url.toString()
         }
         return location
    }

    static String normalize(Object val) { return val?.toString()?.trim() }

    static String resolveEnumLabel(ExecutionContext ec, String enumId, String fallback) {
        String normalized = normalize(enumId)
        if (!normalized) return fallback
        def enumValue = ec.entity.find("moqui.basic.Enumeration")
                .condition("enumId", normalized)
                .disableAuthz()
                .useCache(true)
                .one()
        String code = normalize(enumValue?.enumCode)
        if (code) return code
        String description = normalize(enumValue?.description)
        if (description) return description
        return normalized
    }

    static String determineReconciliationType(String file1Type, String file2Type) {
        String left = normalize(file1Type)?.toUpperCase()
        String right = normalize(file2Type)?.toUpperCase()
        if (left == "CSV" && right == "CSV") return "CSV"
        if (left == "JSON" && right == "JSON") return "JSON"
        return "MIXED"
    }

    static Map<String, Object> resolveRuleSetCompareScopeConfig(ExecutionContext ec, String ruleSetIdValue,
                                                                String compareScopeIdValue,
                                                                String requestedFile1SystemEnumId,
                                                                String requestedFile2SystemEnumId) {
        String normalizedRuleSetId = normalize(ruleSetIdValue)
        if (!normalizedRuleSetId) {
            throw new IllegalArgumentException("ruleSetId is required")
        }

        def compareScope = null
        def ruleSet = ec.entity.find("darpan.rule.RuleSet")
                .condition("ruleSetId", normalizedRuleSetId)
                .disableAuthz()
                .useCache(false)
                .one()
        if (ruleSet == null) {
            throw new IllegalArgumentException("RuleSet ${normalizedRuleSetId} was not found")
        }

        if (compareScopeIdValue) {
            compareScope = ec.entity.find("darpan.rule.RuleSetCompareScope")
                    .condition("compareScopeId", compareScopeIdValue)
                    .condition("ruleSetId", normalizedRuleSetId)
                    .disableAuthz()
                    .useCache(false)
                    .one()
            if (compareScope == null) {
                throw new IllegalArgumentException("Compare scope ${compareScopeIdValue} was not found for RuleSet ${normalizedRuleSetId}")
            }
        } else {
            List compareScopes = ec.entity.find("darpan.rule.RuleSetCompareScope")
                    .condition("ruleSetId", normalizedRuleSetId)
                    .orderBy("compareScopeId")
                    .disableAuthz()
                    .useCache(false)
                    .list() ?: []
            if (compareScopes.isEmpty()) {
                throw new IllegalArgumentException("RuleSet ${normalizedRuleSetId} does not define any compare scopes")
            }
            if (compareScopes.size() > 1) {
                throw new IllegalArgumentException("RuleSet ${normalizedRuleSetId} defines ${compareScopes.size()} compare scopes; compareScopeId is required")
            }
            compareScope = compareScopes[0]
        }

        List sources = ec.entity.find("darpan.rule.RuleSetCompareSource")
                .condition("compareScopeId", compareScope.compareScopeId)
                .disableAuthz()
                .useCache(false)
                .list() ?: []
        Map sourceBySide = [:]
        sources.each { source ->
            sourceBySide[normalize(source.fileSide)] = source
        }
        def file1Source = sourceBySide["FILE_1"]
        def file2Source = sourceBySide["FILE_2"]
        if (file1Source == null || file2Source == null) {
            throw new IllegalArgumentException("Compare scope ${compareScope.compareScopeId} must define both FILE_1 and FILE_2 sources")
        }

        String scopeFile1SystemEnumId = normalize(file1Source.systemEnumId)
        String scopeFile2SystemEnumId = normalize(file2Source.systemEnumId)
        if (requestedFile1SystemEnumId && requestedFile1SystemEnumId != scopeFile1SystemEnumId) {
            throw new IllegalArgumentException("file1SystemEnumId ${requestedFile1SystemEnumId} does not match compare scope ${compareScope.compareScopeId} FILE_1 system ${scopeFile1SystemEnumId}")
        }
        if (requestedFile2SystemEnumId && requestedFile2SystemEnumId != scopeFile2SystemEnumId) {
            throw new IllegalArgumentException("file2SystemEnumId ${requestedFile2SystemEnumId} does not match compare scope ${compareScope.compareScopeId} FILE_2 system ${scopeFile2SystemEnumId}")
        }

        return [
                ruleSetId         : normalizedRuleSetId,
                ruleSetName       : normalize(ruleSet.ruleSetName) ?: normalizedRuleSetId,
                compareScopeId    : normalize(compareScope.compareScopeId),
                compareScopeDescription: normalize(compareScope.description),
                objectType        : normalize(compareScope.objectType),
                file1SystemEnumId : scopeFile1SystemEnumId,
                file2SystemEnumId : scopeFile2SystemEnumId,
                file1Label        : resolveEnumLabel(ec, scopeFile1SystemEnumId, "File 1"),
                file2Label        : resolveEnumLabel(ec, scopeFile2SystemEnumId, "File 2")
        ]
    }

    static Map parseIdSpec(String expr, boolean isCsv) {
        Map split = splitIdExpression(expr)
        String baseExpr = (String) split.idExpr
        String rawNormalizer = (String) split.normalizer
        String normalizedExpr = isCsv ? normalizeCsvId(baseExpr) : normalizeJsonIdExpr(baseExpr)
        String normalizedIdNormalizer = resolveIdNormalizer(rawNormalizer)
        return [idExpr: normalizedExpr, idNormalizer: normalizedIdNormalizer]
    }

    static Map splitIdExpression(String expr) {
        String raw = normalize(expr)
        if (!raw) return [idExpr: null, normalizer: null]
        int separatorIndex = raw.indexOf("|")
        if (separatorIndex < 0) return [idExpr: raw, normalizer: null]
        String idExpr = raw.substring(0, separatorIndex)?.trim()
        String normalizer = raw.substring(separatorIndex + 1)?.trim()
        return [idExpr: idExpr, normalizer: normalizer]
    }

    static String resolveIdNormalizer(String rawNormalizer) {
        String code = normalize(rawNormalizer)
        if (!code) return null
        String normalized = code.replace("-", "_").replace(" ", "_").toUpperCase()
        switch (normalized) {
            case "SHOPIFY_GID_TAIL":
            case "SHOPIFY_GID":
            case "SHOPIFY_GID_NUMERIC":
            case "GID_TAIL":
                return "SHOPIFY_GID_TAIL"
            case "TRAILING_DIGITS":
            case "DIGITS":
                return "TRAILING_DIGITS"
            default:
                throw new IllegalArgumentException("Unsupported ID normalizer '${rawNormalizer}'. Supported values: SHOPIFY_GID_TAIL, TRAILING_DIGITS")
        }
    }
    
    // JSON & CSV Helpers
    static String normalizeCsvId(String expr) {
        def raw = normalize(expr)
        if (!raw) return "id"
        if (raw.startsWith("\$")) {
            def parts = raw.tokenize(".")
            return parts ? parts[-1].replaceAll(/\[.*\]/, "") : "id"
        }
        return raw
    }
    static String normalizeJsonIdExpr(String expr) {
        def raw = normalize(expr)
        if (!raw) return "\$.id"
        String normalized = raw
                .replaceAll(/\[(\d+)\]/, "[*]")
                .replace(".[*]", "[*]")
        if (normalized.startsWith("\$")) return normalized
        if (normalized.startsWith("[")) return '$' + normalized
        if (normalized.contains("[") || normalized.contains(".")) return '$.' + normalized
        return "\$[*].${normalized}"
    }

    static Map convertJsonPathToSpark(String jsonPath) {
        def path = normalizeSparkPath(jsonPath)
        if (!path) throw new IllegalArgumentException("JSONPath ${jsonPath} resolves to an empty field path.")
        if (path.contains("[*]")) {
            def parts = path.split(/\[\*\]/)
            if (parts.length == 2) {
                return [needsExplode: true, arrayPath: parts[0], fieldPath: parts[1].replaceFirst(/^\./, "")]
            }
        }
        return [needsExplode: false, path: path]
    }
    static String normalizeSparkPath(String jsonPath) {
        if (!jsonPath) return jsonPath
        def path = jsonPath.toString().trim()
        path = path.replaceFirst(/^\$\[\*\]/, "")
        path = path.replaceFirst(/^\$\./, "")
        if (path.startsWith(".")) path = path.substring(1)
        return path
    }

    static Column applyIdNormalizer(Column sourceCol, String idNormalizer) {
        Column normalized = trim(sourceCol.cast("string"))
        if (!idNormalizer) return normalized

        if ("SHOPIFY_GID_TAIL".equals(idNormalizer)) {
            Column shopifyTail = regexp_extract(normalized, 'gid://shopify/[^/]+/(\\d+)(?:\\?.*)?$', 1)
            Column trailingDigits = regexp_extract(normalized, '(\\d+)$', 1)
            return when(length(shopifyTail).gt(0), shopifyTail)
                    .when(length(trailingDigits).gt(0), trailingDigits)
                    .otherwise(normalized)
        }
        if ("TRAILING_DIGITS".equals(idNormalizer)) {
            Column trailingDigits = regexp_extract(normalized, '(\\d+)$', 1)
            return when(length(trailingDigits).gt(0), trailingDigits).otherwise(normalized)
        }

        throw new IllegalArgumentException("Unsupported ID normalizer '${idNormalizer}'")
    }

    static Dataset normalizeCompareIdDataset(Dataset sourceDf, String idNormalizer) {
        return sourceDf
                .withColumn("compare_id", applyIdNormalizer(col("compare_id"), idNormalizer))
                .filter("compare_id IS NOT NULL AND length(trim(compare_id)) > 0")
    }

    static Dataset buildMissingDiffRows(Dataset presentDataDf, Dataset missingIdDf, String diffType,
                                        String presentLabel, String missingLabel, String note) {
        if (presentDataDf == null || missingIdDf == null) return null
        return presentDataDf.join(missingIdDf, "compare_id", "inner")
                .select(
                        lit(diffType).alias("type"),
                        col("compare_id").alias("id"),
                        lit(presentLabel).alias("presentIn"),
                        lit(missingLabel).alias("missingIn"),
                        to_json(col("data")).alias("data"),
                        lit(note).alias("note")
                )
    }

    static void validateUniqueCompareIds(Dataset dataDf, String compareScopeId, String fileSide, String fileLabel) {
        if (dataDf == null) return

        List duplicateRows = dataDf.groupBy("compare_id")
                .count()
                .filter(col("count").gt(1))
                .orderBy(col("compare_id"))
                .limit(5)
                .collectAsList()
        if (!duplicateRows) return

        String sideCode = normalize(fileSide) ?: "FILE"
        String label = normalize(fileLabel) ?: sideCode
        String examples = duplicateRows.collect { row ->
            String compareId = row.getAs("compare_id")?.toString()
            long duplicateCount = ((Number) row.getAs("count")).longValue()
            return "${compareId} (${duplicateCount} rows)"
        }.join(", ")

        throw new IllegalArgumentException(
                "Compare scope ${compareScopeId} ${sideCode} (${label}) produced duplicate primaryId values after normalization: ${examples}. primaryId must identify exactly one object per file side."
        )
    }

    static Dataset unionDatasets(Dataset firstDf, Dataset secondDf) {
        if (firstDf != null && secondDf != null) return firstDf.union(secondDf)
        if (firstDf != null) return firstDf
        return secondDf
    }

    static Dataset emptyMissingDiffDataset(Dataset referenceDf) {
        if (referenceDf == null) {
            throw new IllegalArgumentException("referenceDf is required to create an empty missingDiffDf")
        }
        return referenceDf.sparkSession().createDataFrame(new ArrayList<Row>(), MISSING_DIFF_SCHEMA)
    }

    static Dataset convertMissingDiffToRuleSetDiffDataset(Dataset missingDiffDf, String compareScopeId, String objectType) {
        if (missingDiffDf == null) return null
        return missingDiffDf.select(
                col("type").alias("diffType"),
                lit(compareScopeId).alias("compareScopeId"),
                lit(objectType).alias("objectType"),
                col("id").alias("primaryId"),
                lit(null).cast(DataTypes.StringType).alias("field"),
                lit(null).cast(DataTypes.StringType).alias("file1Value"),
                lit(null).cast(DataTypes.StringType).alias("file2Value"),
                col("presentIn").alias("presentIn"),
                col("missingIn").alias("missingIn"),
                col("data").alias("data"),
                lit(null).cast(DataTypes.StringType).alias("ruleId"),
                lit(null).cast(DataTypes.StringType).alias("severity"),
                col("note").alias("message")
        )
    }

    static Dataset emptyRuleSetDiffDataset(Dataset referenceDf) {
        if (referenceDf == null) {
            throw new IllegalArgumentException("referenceDf is required to create an empty ruleSet diff dataset")
        }
        return referenceDf.sparkSession().createDataFrame(new ArrayList<Row>(), RULESET_DIFF_SCHEMA)
    }

    static Dataset buildRuleSetDiffDataset(Dataset referenceDf, List<Map<String, Object>> diffRows) {
        if (referenceDf == null) {
            throw new IllegalArgumentException("referenceDf is required to create a ruleSet diff dataset")
        }
        List<Map<String, Object>> safeDiffRows = diffRows ?: []
        if (!safeDiffRows) return emptyRuleSetDiffDataset(referenceDf)

        List<Row> rows = safeDiffRows.collect { Map<String, Object> diff ->
            RowFactory.create(
                    normalize(diff.diffType),
                    normalize(diff.compareScopeId),
                    normalize(diff.objectType),
                    normalize(diff.primaryId),
                    normalize(diff.field),
                    normalize(diff.file1Value),
                    normalize(diff.file2Value),
                    normalize(diff.presentIn),
                    normalize(diff.missingIn),
                    normalize(diff.data),
                    normalize(diff.ruleId),
                    normalize(diff.severity),
                    normalize(diff.message)
            )
        }
        return referenceDf.sparkSession().createDataFrame(rows, RULESET_DIFF_SCHEMA)
    }

    static Dataset unionByNameDatasets(Dataset firstDf, Dataset secondDf) {
        if (firstDf != null && secondDf != null) return firstDf.unionByName(secondDf)
        if (firstDf != null) return firstDf
        return secondDf
    }

    static Map<String, Object> writeDiffDatasetOutput(ExecutionContext ec, Dataset diffDf, String outputLocation,
                                                      String outputFileName, String defaultBaseName,
                                                      Map<String, Object> outputMetadata, Map<String, Object> outputSummary,
                                                      List validationErrors, List processingWarnings) {
        if (ec == null) throw new IllegalArgumentException("ec is required")
        if (diffDf == null) throw new IllegalArgumentException("diffDf is required")

        String outputBaseLocation = normalize(outputLocation) ?: "runtime://tmp/reconciliation/output"
        def outputRef = ec.resource.getLocationReference(outputBaseLocation)
        File outputDir = outputRef?.getFile()
        if (outputDir == null) {
            String runtimePath = ec.factory.getRuntimePath()
            outputDir = new File(runtimePath, outputBaseLocation.replace("runtime://", ""))
        }
        if (!outputDir.exists()) outputDir.mkdirs()

        String timestamp = ec.l10n.format(ec.user.nowTimestamp, "yyyyMMdd-HHmmss")
        String baseFileName = normalize(outputFileName) ?: normalize(defaultBaseName) ?: "diff-${timestamp}.json"
        if (!baseFileName.toLowerCase().endsWith(".json")) baseFileName = baseFileName + ".json"

        File outputFile = new File(outputDir, baseFileName)
        int suffix = 1
        String nameRoot = baseFileName.indexOf(".") > 0 ? baseFileName.substring(0, baseFileName.lastIndexOf(".")) : baseFileName
        while (outputFile.exists()) {
            outputFile = new File(outputDir, "${nameRoot}-${suffix}.json")
            suffix++
        }

        outputFile.withWriter("UTF-8") { writer ->
            writer << "{\n"
            writer << "\"metadata\":" + JsonOutput.toJson(outputMetadata ?: [:]) + ",\n"
            writer << "\"summary\":" + JsonOutput.toJson(outputSummary ?: [:]) + ",\n"
            writer << "\"validationErrors\":" + JsonOutput.toJson(validationErrors ?: []) + ",\n"
            writer << "\"processingWarnings\":" + JsonOutput.toJson(processingWarnings ?: []) + ",\n"
            writer << "\"differences\":["
            boolean first = true
            def iter = diffDf.toJSON().toLocalIterator()
            while (iter.hasNext()) {
                String rowJson = iter.next()
                if (!first) writer << ","
                writer << "\n" << rowJson
                first = false
            }
            writer << "]\n}"
        }

        return [
                diffLocation: outputFile.getAbsolutePath(),
                diffFileName: outputFile.getName()
        ]
    }

    static Dataset buildMatchedPairDataset(Dataset file1DataDf, Dataset file2DataDf, Dataset matchedIdDf,
                                           String compareScopeId, String objectType) {
        return matchedIdDf.alias("matched")
                .join(file1DataDf.alias("file1"), col("matched.compare_id").equalTo(col("file1.compare_id")), "inner")
                .join(file2DataDf.alias("file2"), col("matched.compare_id").equalTo(col("file2.compare_id")), "inner")
                .select(
                        lit(compareScopeId).alias("compareScopeId"),
                        lit(objectType).alias("objectType"),
                        col("matched.compare_id").alias("primaryId"),
                        col("file1.data").alias("file1"),
                        col("file2.data").alias("file2")
                )
    }
    
    static Dataset buildJsonIdDf(Dataset rawDf, Map pathInfo, String pathLabel, String idNormalizer) {
        if (pathInfo.needsExplode) {
             String arrayPath = normalizeSparkPath(pathInfo.arrayPath as String)
             String fieldPath = normalizeSparkPath(pathInfo.fieldPath as String)
             Dataset idDf = rawDf.selectExpr("explode(${arrayPath}) as exploded_item")
                .selectExpr("exploded_item.${fieldPath} as compare_id")
             return normalizeCompareIdDataset(idDf, idNormalizer).distinct()
        }
        String safePath = normalizeSparkPath(pathInfo.path as String)
        Dataset idDf = rawDf.selectExpr("${safePath} as compare_id")
        return normalizeCompareIdDataset(idDf, idNormalizer).distinct()
    }
    
    static Dataset buildJsonDataDf(Dataset rawDf, Map pathInfo, String pathLabel, String idNormalizer) {
         if (pathInfo.needsExplode) {
             String arrayPath = normalizeSparkPath(pathInfo.arrayPath as String)
             String fieldPath = normalizeSparkPath(pathInfo.fieldPath as String)
             Dataset dataDf = rawDf.selectExpr("explode(${arrayPath}) as exploded_item")
                 .selectExpr("exploded_item.${fieldPath} as compare_id", "exploded_item as data")
             return normalizeCompareIdDataset(dataDf, idNormalizer)
         }
         String safePath = normalizeSparkPath(pathInfo.path as String)
         Dataset dataDf = rawDf.selectExpr("${safePath} as compare_id", "struct(*) as data")
         return normalizeCompareIdDataset(dataDf, idNormalizer)
    }
    
    static Dataset buildCsvIdDf(Dataset rawDf, String fieldName, String idNormalizer) {
         String fieldExpr = (fieldName?.replace("`", "``") ?: "id")
         fieldExpr = "`" + fieldExpr + "`"
         Dataset idDf = rawDf.selectExpr("${fieldExpr} as compare_id")
         return normalizeCompareIdDataset(idDf, idNormalizer).distinct()
    }
     static Dataset buildCsvDataDf(Dataset rawDf, String fieldName, String idNormalizer) {
         String fieldExpr = (fieldName?.replace("`", "``") ?: "id")
         fieldExpr = "`" + fieldExpr + "`"
         Dataset dataDf = rawDf.selectExpr("${fieldExpr} as compare_id", "struct(*) as data")
         return normalizeCompareIdDataset(dataDf, idNormalizer)
    }
}
