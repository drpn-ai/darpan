package darpan.reconciliation.core

import darpan.common.DarpanEntityConstants
import darpan.facade.common.TenantScopedFinder
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.PackageScope
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Row
import org.apache.spark.sql.SparkSession
import org.apache.spark.storage.StorageLevel
import org.apache.spark.sql.types.DataTypes
import org.apache.spark.sql.types.StructType
import org.moqui.context.ExecutionContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.regex.Pattern

import static darpan.common.ValueSupport.normalize
import static org.apache.spark.sql.functions.*

class ReconciliationServices {
    private static final Logger logger = LoggerFactory.getLogger(ReconciliationServices.class)
    private static final JsonSlurper JSON_SLURPER = new JsonSlurper()

    // Audit 2026-06-11 #3: rule-generated diffs are accumulated into a driver-side List before being
    // turned back into a Dataset. On a catastrophically broken sync (a large fraction of records
    // failing a rule) that list can exhaust the Moqui/Spark driver heap and OOM the JVM — taking down
    // every tenant's runs, not just the bad one. Bound the accumulation: past this many rule-diff rows
    // we stop collecting, record how many were dropped, and emit a loud warning (surfaced in the
    // completion alert) so the run is reported as truncated rather than crashing the process. The cap
    // is high enough that normal runs are unaffected; override via the system property if needed.
    static final int MAX_RULE_DIFF_ROWS =
            (System.getProperty("darpan.reconciliation.rule.maxRuleDiffRows") ?: "1000000").isInteger() ?
                    (System.getProperty("darpan.reconciliation.rule.maxRuleDiffRows") ?: "1000000").toInteger() : 1_000_000
    // Non-printable delimiter (ASCII Unit Separator, U+001F) for composed composite compare_id values —
    // avoids collisions with ordinary field values the way a printable delimiter like '-' or '::' could.
    static final String COMPOSITE_KEY_DELIMITER = "\u001F"
    // Decomposition 2026-07-02: shared constants stay here (single source of truth); the extracted
    // CompareIdExpressionSupport / CompareDatasetSupport units in this package reference them, so the
    // ones they need are @PackageScope instead of private.
    @PackageScope static final Pattern SIMPLE_JSON_FIELD_NAME = Pattern.compile(/[A-Za-z_][A-Za-z0-9_]*/)
    @PackageScope static final StructType MISSING_DIFF_SCHEMA = new StructType()
            .add("type", DataTypes.StringType, true)
            .add("id", DataTypes.StringType, true)
            .add("presentIn", DataTypes.StringType, true)
            .add("missingIn", DataTypes.StringType, true)
            .add("data", DataTypes.StringType, true)
            .add("note", DataTypes.StringType, true)
    @PackageScope static final StructType RULESET_DIFF_SCHEMA = new StructType()
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
    @PackageScope static final StructType EMPTY_COMPARE_ID_SCHEMA = new StructType()
            .add("compare_id", DataTypes.StringType, true)
    @PackageScope static final StructType EMPTY_COMPARE_DATA_SCHEMA = new StructType()
            .add("compare_id", DataTypes.StringType, true)
            .add("data", DataTypes.createMapType(DataTypes.StringType, DataTypes.StringType), true)
    // Strict identifier pattern for the Spark SQL injection defense — see
    // CompareIdExpressionSupport.safeSparkColumnPath for the full rationale.
    @PackageScope static final Pattern SAFE_PATH_SEGMENT = ~/^[A-Za-z_][A-Za-z0-9_]*$/

    static String normalize(Object value) {
        return darpan.common.ValueSupport.normalize(value)
    }

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
            String companyUserGroupId = (String) context.get("companyUserGroupId")
            Boolean hasHeader = (Boolean) context.get("hasHeader")
            String outputLocation = (String) context.get("outputLocation") ?: "runtime://tmp/reconciliation/unified/output"
            String outputFileName = (String) context.get("outputFileName")
            // Security (HIGH gaps 3,5, defense-in-depth 2026-06-30): IGNORE any caller-supplied
            // sparkMaster. Resolve the Spark master server-side only (resource property, else local[*])
            // so a future allow-remote exposure of this service cannot let a caller redirect the tenant's
            // Spark job to an attacker-controlled cluster. Operators set spark.master via server property.
            String sparkMaster = (String) (ec.resource.properties["spark.master"] ?: "local[*]")
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

            Map id1Spec = CompareIdExpressionSupport.parseIdSpec(file1IdExpression ?: file1IdField, "CSV".equals(type1))
            Map id2Spec = CompareIdExpressionSupport.parseIdSpec(file2IdExpression ?: file2IdField, "CSV".equals(type2))

            String reconType = "${type1}_${type2}"
            String reconciliationType = (reconType == "CSV_CSV") ? "CSV" : (reconType == "JSON_JSON" ? "JSON" : "MIXED")

            SparkSession spark = SparkSession.builder()
                    .appName(sparkAppName)
                    .master(sparkMaster)
                    .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
                     .config("spark.sql.adaptive.enabled", "true")
                    .config("spark.ui.enabled", "false") // embedded batch use; Spark UI needs legacy javax.servlet, absent under Moqui 4 jakarta
                    .getOrCreate()
            // Audit 2026-06-11 #18: getOrCreate returns a JVM singleton, so per-call builder .config()
            // is ignored after the first session. Set shuffle partitions at runtime so embedded local[*]
            // batches avoid the 200-partition default overhead on small datasets. Overridable.
            spark.conf().set("spark.sql.shuffle.partitions",
                    (System.getProperty("darpan.reconciliation.spark.shufflePartitions") ?: "16"))

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
                companyUserGroupId: companyUserGroupId,
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
             // Audit 2026-06-11 #9: do NOT swallow a Spark/ingest/join failure into an empty,
             // success-shaped map. Downstream code reads [:] as "0 differences, clean run" and the
             // run is recorded SUCCEEDED, hiding a broken sync — the exact failure this platform
             // exists to surface. Re-raise so the run fails loudly. The finally block below still
             // releases persisted Datasets on this path.
             logger.error("Unified reconciliation failed", e)
             throw new RuntimeException("Unified reconciliation failed: ${e.message}", e)
        } finally {
            // Audit H2.1 / H9.8 — Dataset.unpersist() ran only on the happy path; the catch block
            // returned without releasing DISK_ONLY blockmgr files, so every failed reconciliation
            // leaked driver-side blocks. Move all unpersist calls into a finally that runs on success
            // and on failure. spark.stop() is intentionally NOT called: SparkSession.builder()
            // .getOrCreate() returns a JVM singleton and stopping it would prevent every subsequent
            // reconciliation from reusing the session.
            try { if (onlyIn1Df != null) onlyIn1Df.unpersist() } catch (Throwable ignored) { /* best-effort cleanup */ }
            try { if (onlyIn2Df != null) onlyIn2Df.unpersist() } catch (Throwable ignored) { /* best-effort cleanup */ }
            try { if (idDf1 != null) idDf1.unpersist() } catch (Throwable ignored) { /* best-effort cleanup */ }
            try { if (idDf2 != null) idDf2.unpersist() } catch (Throwable ignored) { /* best-effort cleanup */ }
        }
    }

    // --- Private Helpers ---

    static Map ingestFile(ExecutionContext ec, SparkSession spark, String loc, String type, Object idSpecOrList, boolean hasHeader, String label, List validationErrors, String schemaFile) {
        String path = resolvePath(ec, loc)
        List<Map> idSpecs = normalizeIdSpecs(idSpecOrList)
        Dataset df = null
        Dataset idDf = null
        Dataset dataDf = null
        validateStagedPayloadShape(path, type, label)

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

            // Audit #25: when an operator opts in (darpan.reconciliation.spark.useSavedReadSchema),
            // supply the saved schema as the read schema to skip Spark's per-run inference scan. If the
            // saved schema does not resolve the id/rule paths, degrade transparently to inference so the
            // optimization can never fail a run. Default (no property) keeps the inference behaviour.
            List<Map> pathInfos = idSpecs.collect { Map spec -> CompareIdExpressionSupport.convertJsonPathToSpark(normalize((String) spec.idExpr)) }
            List<String> idNormalizers = idSpecs.collect { (String) it.idNormalizer }
            List<String> fieldExpressions = idSpecs.collect { (String) it.idExpr }
            StructType readSchema = (schemaFile && SparkReadSchemaSupport.savedReadSchemaEnabled()) ?
                    SparkReadSchemaSupport.buildReadSchema(ec, schemaFile) : null
            try {
                df = readSchema != null ?
                        spark.read().schema(readSchema).option("multiLine", "true").json(path) :
                        spark.read().option("multiLine", "true").json(path)
                idDf = CompareDatasetSupport.buildJsonIdDf(df, pathInfos, label, idNormalizers, fieldExpressions)
                dataDf = CompareDatasetSupport.buildJsonDataDf(df, pathInfos, label, idNormalizers, fieldExpressions)
            } catch (Exception readFailure) {
                if (readSchema == null) throw readFailure
                logger.warn("Saved read schema for '${label}' did not cover the data (${readFailure.message}); re-reading with inference.")
                df = spark.read().option("multiLine", "true").json(path)
                idDf = CompareDatasetSupport.buildJsonIdDf(df, pathInfos, label, idNormalizers, fieldExpressions)
                dataDf = CompareDatasetSupport.buildJsonDataDf(df, pathInfos, label, idNormalizers, fieldExpressions)
            }
        } else {
             // CSV
             df = spark.read().option("header", hasHeader.toString()).option("multiLine", "true").csv(path)
             idDf = CompareDatasetSupport.buildCsvIdDf(df, idSpecs, label, hasHeader)
             dataDf = CompareDatasetSupport.buildCsvDataDf(df, idSpecs, label, hasHeader)
        }
        return [idDf: idDf, dataDf: dataDf]
    }

    // Task 3 (composite compare keys): the RuleSet compare-scope pipeline always resolves an ordered
    // List<Map> of idSpecs (Task 2), but the legacy reconcileUnifiedFilesInternal pipeline still calls
    // this with a single Map idSpec — accept both shapes rather than forcing that separate legacy path
    // to wrap its Map in a List.
    private static List<Map> normalizeIdSpecs(Object idSpecOrList) {
        List<Map> rawSpecs
        if (idSpecOrList instanceof List) rawSpecs = ((List) idSpecOrList) as List<Map>
        else if (idSpecOrList instanceof Map) rawSpecs = [(Map) idSpecOrList]
        else throw new IllegalArgumentException("idSpec must be a Map or a List of Maps, got ${idSpecOrList?.getClass()}")
        // Trim-to-null idExpr and idNormalizer here, at the single entry point, so no downstream
        // consumer (JSON or CSV path) can see an untrimmed value — the pre-composite code normalized
        // both, and applyIdNormalizer matches normalizer names by exact string equality.
        return rawSpecs.collect { Map spec ->
            [idExpr: normalize(spec?.get('idExpr')), idNormalizer: normalize(spec?.get('idNormalizer'))] as Map
        }
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

    private static void validateStagedPayloadShape(String path, String type, String label) {
        String normalizedType = normalize(type)?.toUpperCase()
        if (!(normalizedType in ["CSV", "JSON"])) return

        File stagedFile = path ? new File(path) : null
        if (stagedFile == null || !stagedFile.exists() || !stagedFile.isFile()) return

        String preview = readFilePreview(stagedFile, 8192)
        String trimmedPreview = preview?.trim()
        if (!trimmedPreview) return

        boolean looksLikeJson = trimmedPreview.startsWith("{") || trimmedPreview.startsWith("[")
        boolean looksLikeJsonSchema = looksLikeJson && looksLikeJsonSchemaPreview(trimmedPreview)
        String fileName = stagedFile.name ?: "uploaded file"
        String inputLabel = normalize(label) ?: fileName

        if (normalizedType == "CSV" && looksLikeJson) {
            String actualType = looksLikeJsonSchema ? "a JSON Schema document" : "JSON"
            throw new IllegalArgumentException("${inputLabel} expects CSV data, but ${fileName} looks like ${actualType}. Upload the source data file for this saved run instead.")
        }

        if (normalizedType == "JSON" && !looksLikeJson) {
            throw new IllegalArgumentException("${inputLabel} expects JSON data, but ${fileName} does not look like JSON. Upload the source data file for this saved run instead.")
        }

        if (normalizedType == "JSON" && looksLikeJsonSchema) {
            throw new IllegalArgumentException("${inputLabel} expects JSON records, but ${fileName} looks like a JSON Schema document. Upload the source data file for this saved run instead of the schema definition.")
        }
    }

    private static String readFilePreview(File stagedFile, int maxChars) {
        if (stagedFile == null || maxChars <= 0) return null

        stagedFile.withReader("UTF-8") { reader ->
            char[] buffer = new char[maxChars]
            int readCount = reader.read(buffer, 0, maxChars)
            return readCount > 0 ? new String(buffer, 0, readCount) : null
        }
    }

    private static boolean looksLikeJsonSchemaPreview(String preview) {
        String normalizedPreview = preview?.toLowerCase()
        if (!normalizedPreview) return false

        return normalizedPreview.contains('"$schema"') &&
                (normalizedPreview.contains('"properties"') ||
                        normalizedPreview.contains('"items"') ||
                        normalizedPreview.contains('"definitions"') ||
                        normalizedPreview.contains('"$defs"'))
    }

    static String resolveEnumLabel(ExecutionContext ec, String enumId, String fallback) {
        String normalized = normalize(enumId)
        if (!normalized) return fallback
        def enumValue = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.basic.Enumeration",
                        "framework reference data: enumeration label lookup")
                .condition("enumId", normalized)
                .useCache(true)
                .one()
        String description = normalize(enumValue?.description)
        if (normalize(enumValue?.enumTypeId) == "DarpanSystemSource" && normalized == "OMS") {
            return description ?: "HotWax"
        }
        String code = normalize(enumValue?.enumCode)
        if (code) return code
        if (description) return description
        return normalized
    }

    static Map<String, Object> resolveRuleSetCompareScopeConfig(ExecutionContext ec, String ruleSetIdValue,
                                                                String compareScopeIdValue,
                                                                String requestedFile1SystemEnumId,
                                                                String requestedFile2SystemEnumId) {
        String normalizedRuleSetId = normalize(ruleSetIdValue)
        if (!normalizedRuleSetId) {
            throw new IllegalArgumentException("ruleSetId is required")
        }

        // RuleSetCompareScope has no companyUserGroupId — gate via parent RuleSet (directly-owned).
        // findTenantScopedChildren gates the RuleSet and returns a pre-scoped EntityFind for its compare scopes.
        def compareScopeBaseFinder = TenantScopedFinder.findTenantScopedChildren(
                ec, DarpanEntityConstants.RULE_SET_COMPARE_SCOPE,
                DarpanEntityConstants.RULE_SET, "ruleSetId", normalizedRuleSetId, "ruleSetId")
        if (compareScopeBaseFinder == null) {
            throw new IllegalArgumentException("RuleSet ${normalizedRuleSetId} was not found or is not accessible in your active tenant")
        }

        def compareScope = null
        if (compareScopeIdValue) {
            compareScope = compareScopeBaseFinder
                    .condition("compareScopeId", compareScopeIdValue)
                    .useCache(false)
                    .one()
            if (compareScope == null) {
                throw new IllegalArgumentException("Compare scope ${compareScopeIdValue} was not found for RuleSet ${normalizedRuleSetId}")
            }
        } else {
            List compareScopes = compareScopeBaseFinder
                    .orderBy("compareScopeId")
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

        String compareScopeLabel = CompareIdExpressionSupport.compareScopeDisplayName(compareScope.compareScopeId, compareScope.description)
        // RuleSetCompareSource has no companyUserGroupId; ownership is transitively established via
        // the tenant-owned RuleSet (normalizedRuleSetId) → RuleSetCompareScope (compareScope.compareScopeId) chain above.
        List sources = TenantScopedFinder.findGlobalUnscoped(ec, "darpan.rule.RuleSetCompareSource",
                "RuleSetCompareSource has no companyUserGroupId; compareScopeId scoped to tenant-owned RuleSet ${normalizedRuleSetId}")
                .condition("compareScopeId", compareScope.compareScopeId)
                .useCache(false)
                .list() ?: []
        Map sourceBySide = [:]
        sources.each { source ->
            sourceBySide[normalize(source.fileSide)] = source
        }
        def file1Source = sourceBySide["FILE_1"]
        def file2Source = sourceBySide["FILE_2"]
        if (file1Source == null || file2Source == null) {
            throw new IllegalArgumentException("Compare scope '${compareScopeLabel}' must define both FILE_1 and FILE_2 sources")
        }

        String scopeFile1SystemEnumId = normalize(file1Source.systemEnumId)
        String scopeFile2SystemEnumId = normalize(file2Source.systemEnumId)
        if (requestedFile1SystemEnumId && requestedFile1SystemEnumId != scopeFile1SystemEnumId) {
            throw new IllegalArgumentException("file1SystemEnumId ${requestedFile1SystemEnumId} does not match compare scope '${compareScopeLabel}' FILE_1 system ${scopeFile1SystemEnumId}")
        }
        if (requestedFile2SystemEnumId && requestedFile2SystemEnumId != scopeFile2SystemEnumId) {
            throw new IllegalArgumentException("file2SystemEnumId ${requestedFile2SystemEnumId} does not match compare scope '${compareScopeLabel}' FILE_2 system ${scopeFile2SystemEnumId}")
        }

        return [
                compareScopeId    : normalize(compareScope.compareScopeId),
                compareScopeDescription: compareScopeLabel,
                objectType        : normalize(compareScope.objectType),
                file1SystemEnumId : scopeFile1SystemEnumId,
                file2SystemEnumId : scopeFile2SystemEnumId,
                file1Label        : resolveEnumLabel(ec, scopeFile1SystemEnumId, "File 1"),
                file2Label        : resolveEnumLabel(ec, scopeFile2SystemEnumId, "File 2")
        ]
    }

    static Map<String, Object> executeRuleSetMatchedPairBatches(ExecutionContext ec, Dataset matchedPairDf,
                                                                String ruleSetId, String compareScopeId,
                                                                String compareScopeDescription, int ruleBatchSize) {
        List<Map<String, Object>> ruleDiffRows = []
        List<String> processingWarnings = []
        List<String> ruleErrors = []
        boolean ruleExecutionFailed = false
        boolean ruleDiffTruncated = false
        long droppedRuleDiffCount = 0L
        int safeMaxRuleDiffRows = MAX_RULE_DIFF_ROWS > 0 ? MAX_RULE_DIFF_ROWS : 1_000_000
        int firedRuleCount = 0
        int safeRuleBatchSize = ruleBatchSize > 0 ? ruleBatchSize : 500

        if (matchedPairDf == null) {
            return [
                    ruleDiffRows       : ruleDiffRows,
                    ruleDifferenceCount: 0L,
                    firedRuleCount     : firedRuleCount,
                    processingWarnings : processingWarnings,
                    ruleErrors         : ruleErrors,
                    ruleExecutionFailed: ruleExecutionFailed
            ]
        }

        Iterator<String> rowIterator = matchedPairDf.toJSON().toLocalIterator()
        while (rowIterator.hasNext()) {
            List<Map<String, Object>> batch = nextMatchedPairBatch(rowIterator, safeRuleBatchSize)
            if (!batch) break

            Map<String, Object> ruleExec = ec.service.sync()
                    .name("reconciliation.ReconciliationRuleEngineServices.execute#RuleSetMatchedPairs")
                    .parameters([
                            ruleSetId      : ruleSetId,
                            dataList       : batch,
                            returnAllFacts : false
                    ])
                    .call()

            processingWarnings.addAll(toNormalizedStringList(ruleExec?.warnings))
            if (ruleExec?.error) {
                // Audit 2026-06-11 #4: a rule build/eval failure was downgraded to a warning and the
                // remaining batches silently skipped, yet the run still reported success. Record it as
                // a hard rule error and flag the run as failed so the status + completion notification
                // can surface that the ruleset did not fully evaluate.
                ruleExecutionFailed = true
                String ruleError = normalize(ruleExec.error)
                if (ruleError) ruleErrors.add(ruleError)
                processingWarnings.add(buildRuleStageWarning(ruleSetId, compareScopeDescription, batch, ruleExec.error))
                logger.error("RuleSet compare stage failed; remaining matched-pair batches skipped ruleSet={} compareScope={} error={}",
                        ruleSetId, compareScopeId, ruleExec.error)
                break
            }

            firedRuleCount += ((Number) (ruleExec?.firedRuleCount ?: 0)).intValue()
            List<Map<String, Object>> batchDiffs = (List<Map<String, Object>>) (ruleExec?.diffResults ?: [])
            if (batchDiffs) {
                // Audit 2026-06-11 #3: bound driver-side diff accumulation so a pathological run cannot
                // OOM the JVM. Once the cap is reached, keep counting dropped rows but stop collecting.
                long dropped = CompareDatasetSupport.appendBoundedRuleDiffs(ruleDiffRows, batchDiffs, safeMaxRuleDiffRows)
                if (dropped > 0) {
                    ruleDiffTruncated = true
                    droppedRuleDiffCount += dropped
                }
            }
        }

        if (ruleDiffTruncated) {
            // Self-review #3: a truncated run is incomplete — flag it as failed so it is recorded
            // FAILED (not a silent SUCCESS) and the completion alert surfaces it via the same wiring as
            // a rule error. The warning below states the true magnitude (how many diffs were dropped).
            ruleExecutionFailed = true
            String truncationWarning = "RuleSet ${ruleSetId} produced more than ${safeMaxRuleDiffRows} rule differences; ${droppedRuleDiffCount} were dropped to protect the driver from running out of memory. The reconciliation output is incomplete — investigate the sync immediately, then re-run with a narrower window."
            processingWarnings.add(truncationWarning.toString())
            ruleErrors.add(truncationWarning.toString())
            logger.error("Rule diff output truncated ruleSet={} cap={} dropped={}", ruleSetId, safeMaxRuleDiffRows, droppedRuleDiffCount)
        }

        return [
                ruleDiffRows       : ruleDiffRows,
                ruleDifferenceCount: ruleDiffRows.size(),
                firedRuleCount     : firedRuleCount,
                processingWarnings : processingWarnings,
                ruleErrors         : ruleErrors,
                ruleExecutionFailed: ruleExecutionFailed,
                ruleDiffTruncated  : ruleDiffTruncated,
                droppedRuleDiffCount: droppedRuleDiffCount
        ]
    }

    private static List<String> toNormalizedStringList(Object rawValue) {
        if (!(rawValue instanceof List)) return []
        return ((List) rawValue).collect { Object value -> normalize(value) }.findAll { it }
    }

    private static List<Map<String, Object>> nextMatchedPairBatch(Iterator<String> rowIterator, int ruleBatchSize) {
        List<Map<String, Object>> batch = []
        while (rowIterator.hasNext() && batch.size() < ruleBatchSize) {
            String rowJson = rowIterator.next()
            batch.add((Map<String, Object>) JSON_SLURPER.parseText(rowJson))
        }
        return batch
    }

    private static String buildRuleStageWarning(String ruleSetId, String compareScopeDescription,
                                                List<Map<String, Object>> batch, Object error) {
        String primaryIds = batch.collect { Map<String, Object> row -> normalize(row.primaryId) }
                .findAll { it }
                .take(5)
                .join(", ")
        String batchToken = primaryIds ? " primaryIds=${primaryIds}" : ""
        String compareScopeLabel = normalize(compareScopeDescription) ?: "compare scope"
        return "RuleSet ${ruleSetId} compare scope '${compareScopeLabel}' rule execution failed; preserved base missing-object diffs.${batchToken} Error: ${normalize(error)}"
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

    // --- Compatibility delegates (decomposition 2026-07-02) ---
    // The implementations moved to CompareIdExpressionSupport (pure id-expression parsing) and
    // CompareDatasetSupport (Spark Dataset primitives). These exact-signature delegates keep every
    // existing caller — service XML, adapters, automation scripts, and tests — working unchanged.

    static Map parseIdSpec(String expr, boolean isCsv) { return CompareIdExpressionSupport.parseIdSpec(expr, isCsv) }

    static Map splitIdExpression(String expr) { return CompareIdExpressionSupport.splitIdExpression(expr) }

    static String resolveIdNormalizer(String rawNormalizer) { return CompareIdExpressionSupport.resolveIdNormalizer(rawNormalizer) }

    static String determineReconciliationType(String file1Type, String file2Type) { return CompareIdExpressionSupport.determineReconciliationType(file1Type, file2Type) }

    static String compareScopeDisplayName(Object compareScopeId, Object compareScopeDescription) { return CompareIdExpressionSupport.compareScopeDisplayName(compareScopeId, compareScopeDescription) }

    static Dataset buildMissingDiffRows(Dataset presentDataDf, Dataset missingIdDf, String diffType,
                                        String presentLabel, String missingLabel, String note) { return CompareDatasetSupport.buildMissingDiffRows(presentDataDf, missingIdDf, diffType, presentLabel, missingLabel, note) }

    static List<Row> findDuplicateCompareIdRows(Dataset dataDf, int sampleLimit = 5) { return CompareDatasetSupport.findDuplicateCompareIdRows(dataDf, sampleLimit) }

    static Dataset collapseDuplicateCompareIds(Dataset dataDf) { return CompareDatasetSupport.collapseDuplicateCompareIds(dataDf) }

    static String buildDuplicateCompareIdExamples(List<Row> duplicateRows) { return CompareDatasetSupport.buildDuplicateCompareIdExamples(duplicateRows) }

    static void validateUniqueCompareIds(Dataset dataDf, String compareScopeLabel, String fileSide, String fileLabel) { CompareDatasetSupport.validateUniqueCompareIds(dataDf, compareScopeLabel, fileSide, fileLabel) }

    static Dataset unionDatasets(Dataset firstDf, Dataset secondDf) { return CompareDatasetSupport.unionDatasets(firstDf, secondDf) }

    static Dataset emptyMissingDiffDataset(Dataset referenceDf) { return CompareDatasetSupport.emptyMissingDiffDataset(referenceDf) }

    static Dataset buildMatchedIdDataset(Dataset file1IdDf, Dataset file2IdDf) { return CompareDatasetSupport.buildMatchedIdDataset(file1IdDf, file2IdDf) }

    static long countDataset(Dataset df) { return CompareDatasetSupport.countDataset(df) }

    static long appendBoundedRuleDiffs(List<Map<String, Object>> accumulated, List<Map<String, Object>> batchDiffs, int cap) { return CompareDatasetSupport.appendBoundedRuleDiffs(accumulated, batchDiffs, cap) }

    static Dataset persistAndMaterialize(Dataset df) { return CompareDatasetSupport.persistAndMaterialize(df) }

    static void unpersistDatasets(List datasets) { CompareDatasetSupport.unpersistDatasets(datasets) }

    static Dataset convertMissingDiffToRuleSetDiffDataset(Dataset missingDiffDf, String compareScopeId, String objectType) { return CompareDatasetSupport.convertMissingDiffToRuleSetDiffDataset(missingDiffDf, compareScopeId, objectType) }

    static Dataset emptyRuleSetDiffDataset(Dataset referenceDf) { return CompareDatasetSupport.emptyRuleSetDiffDataset(referenceDf) }

    static Dataset buildRuleSetDiffDataset(Dataset referenceDf, List<Map<String, Object>> diffRows) { return CompareDatasetSupport.buildRuleSetDiffDataset(referenceDf, diffRows) }

    static Dataset unionByNameDatasets(Dataset firstDf, Dataset secondDf) { return CompareDatasetSupport.unionByNameDatasets(firstDf, secondDf) }

    static Dataset buildMatchedPairDataset(Dataset file1DataDf, Dataset file2DataDf, Dataset matchedIdDf,
                                           String compareScopeId, String objectType) { return CompareDatasetSupport.buildMatchedPairDataset(file1DataDf, file2DataDf, matchedIdDf, compareScopeId, objectType) }
}
