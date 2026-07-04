package darpan.reconciliation.core

import org.apache.spark.sql.Column
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Row
import org.apache.spark.sql.RowFactory
import org.apache.spark.sql.SparkSession
import org.apache.spark.storage.StorageLevel
import org.apache.spark.sql.types.DataTypes

import static darpan.common.ValueSupport.normalize
import static org.apache.spark.sql.functions.*

/**
 * Spark Dataset/Column reconciliation primitives extracted from ReconciliationServices
 * (decomposition 2026-07-02, MACH P1). Orchestration (SparkSession lifecycle, ingestion,
 * rule batch execution, output writing) stays on ReconciliationServices; the shared diff
 * schemas intentionally stay there too and are referenced from here. JSON/CSV id builders
 * lean on CompareIdExpressionSupport for path normalization and injection-safe column paths.
 */
class CompareDatasetSupport {

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
        // Audit 2026-06-11 #17: case-insensitive compare_id matching.
        if ("CASE_FOLD".equals(idNormalizer)) {
            return lower(normalized)
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

    static List<Row> findDuplicateCompareIdRows(Dataset dataDf, int sampleLimit = 5) {
        if (dataDf == null) return []

        int safeLimit = sampleLimit > 0 ? sampleLimit : 5
        return dataDf.groupBy("compare_id")
                .count()
                .filter(col("count").gt(1))
                .orderBy(col("compare_id"))
                .limit(safeLimit)
                .collectAsList()
    }

    static Dataset collapseDuplicateCompareIds(Dataset dataDf) {
        if (dataDf == null) return null

        // Base-diff-only runs only need one representative row per compare_id.
        return dataDf.groupBy("compare_id")
                .agg(first(col("data"), true).alias("data"))
    }

    static String buildDuplicateCompareIdExamples(List<Row> duplicateRows) {
        if (!duplicateRows) return ""

        return duplicateRows.collect { row ->
            String compareId = row.getAs("compare_id")?.toString()
            long duplicateCount = ((Number) row.getAs("count")).longValue()
            return "${compareId} (${duplicateCount} rows)"
        }.join(", ")
    }

    static void validateUniqueCompareIds(Dataset dataDf, String compareScopeLabel, String fileSide, String fileLabel) {
        if (dataDf == null) return

        List<Row> duplicateRows = findDuplicateCompareIdRows(dataDf)
        if (!duplicateRows) return

        String scopeLabel = normalize(compareScopeLabel) ?: "compare scope"
        String sideCode = normalize(fileSide) ?: "FILE"
        String label = normalize(fileLabel) ?: sideCode
        String examples = buildDuplicateCompareIdExamples(duplicateRows)

        throw new IllegalArgumentException(
                "Compare scope '${scopeLabel}' ${sideCode} (${label}) produced duplicate primaryId values after normalization: ${examples}. primaryId must identify exactly one object per file side."
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
        return referenceDf.sparkSession().createDataFrame(new ArrayList<Row>(), ReconciliationServices.MISSING_DIFF_SCHEMA)
    }

    static Dataset buildMatchedIdDataset(Dataset file1IdDf, Dataset file2IdDf) {
        return file1IdDf.join(file2IdDf, "compare_id", "inner")
                .select("compare_id")
                .distinct()
    }

    static long countDataset(Dataset df) {
        return df == null ? 0L : df.count()
    }

    // Audit 2026-06-11 #3: append a batch of rule diffs to the accumulator without exceeding `cap`,
    // returning how many rows were dropped (0 if all fit). Bounds driver-heap growth on a run where a
    // large fraction of records fail a rule. Pure helper so the cap behaviour is unit-testable.
    static long appendBoundedRuleDiffs(List<Map<String, Object>> accumulated, List<Map<String, Object>> batchDiffs, int cap) {
        if (!batchDiffs) return 0L
        int remaining = cap - accumulated.size()
        if (remaining <= 0) return (long) batchDiffs.size()
        if (batchDiffs.size() > remaining) {
            accumulated.addAll(batchDiffs.subList(0, remaining))
            return (long) (batchDiffs.size() - remaining)
        }
        accumulated.addAll(batchDiffs)
        return 0L
    }

    // Audit 2026-06-11 #16: persist a derived Dataset to disk and materialize it (count) so the
    // RuleSet path can release the upstream source frames while this result is reused downstream
    // (e.g. matched pairs streamed by the rule batches, missing diffs unioned into the final output).
    static Dataset persistAndMaterialize(Dataset df) {
        if (df == null) return null
        Dataset persisted = df.persist(StorageLevel.DISK_ONLY())
        persisted.count()
        return persisted
    }

    // Best-effort unpersist of every Spark Dataset in the list. Called from the outermost owner's
    // finally so no persisted block survives a run (success or failure).
    static void unpersistDatasets(List datasets) {
        (datasets ?: []).each { Object df ->
            try { if (df instanceof Dataset) ((Dataset) df).unpersist() } catch (Throwable ignored) { /* best-effort cleanup */ }
        }
    }

    static Dataset convertMissingDiffToRuleSetDiffDataset(Dataset missingDiffDf, String compareScopeId, String objectType) {
        if (missingDiffDf == null) return null
        return missingDiffDf.select(
                col("type").alias("diffType"),
                lit(compareScopeId).alias("compareScopeId"),
                lit(objectType).cast(DataTypes.StringType).alias("objectType"),
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
        return referenceDf.sparkSession().createDataFrame(new ArrayList<Row>(), ReconciliationServices.RULESET_DIFF_SCHEMA)
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
        return referenceDf.sparkSession().createDataFrame(rows, ReconciliationServices.RULESET_DIFF_SCHEMA)
    }

    static Dataset unionByNameDatasets(Dataset firstDf, Dataset secondDf) {
        if (firstDf != null && secondDf != null) return firstDf.unionByName(secondDf)
        if (firstDf != null) return firstDf
        return secondDf
    }

    static Dataset buildMatchedPairDataset(Dataset file1DataDf, Dataset file2DataDf, Dataset matchedIdDf,
                                           String compareScopeId, String objectType) {
        return matchedIdDf.alias("matched")
                .join(file1DataDf.alias("file1"), col("matched.compare_id").equalTo(col("file1.compare_id")), "inner")
                .join(file2DataDf.alias("file2"), col("matched.compare_id").equalTo(col("file2.compare_id")), "inner")
                .select(
                        lit(compareScopeId).alias("compareScopeId"),
                        lit(objectType).cast(DataTypes.StringType).alias("objectType"),
                        col("matched.compare_id").alias("primaryId"),
                        col("file1.data").alias("file1"),
                        col("file2.data").alias("file2")
                )
    }

    static Dataset buildJsonIdDf(Dataset rawDf, List<Map> pathInfos, String pathLabel, List<String> idNormalizers, List<String> fieldExpressions) {
        Map sharedPathInfo = validateSharedRecordRoot(pathInfos, pathLabel)
        if (sharedPathInfo.needsExplode) {
            String arrayPath = CompareIdExpressionSupport.safeSparkColumnPath(CompareIdExpressionSupport.normalizeSparkPath(sharedPathInfo.arrayPath as String))
            Dataset explodedDf = rawDf.selectExpr("explode(${arrayPath}) as exploded_item")
            if (!datasetHasRows(explodedDf)) return emptyCompareIdDataset(rawDf.sparkSession())
            List<Column> fieldCols = [pathInfos, idNormalizers].transpose().collect { List pair ->
                Map pathInfo = (Map) pair[0]; String normalizer = (String) pair[1]
                String fieldPath = CompareIdExpressionSupport.safeSparkColumnPath(CompareIdExpressionSupport.normalizeSparkPath(pathInfo.fieldPath as String))
                applyIdNormalizer(col("exploded_item.${fieldPath}"), normalizer)
            }
            validateNoBlankCompositeKeyFields(explodedDf, fieldCols, fieldExpressions, pathLabel)
            Dataset idDf = explodedDf.select(buildCompositeCompareIdColumn(fieldCols).alias("compare_id"))
            return idDf.filter("compare_id IS NOT NULL AND length(trim(compare_id)) > 0").distinct()
        }
        List<Column> fieldCols = [pathInfos, idNormalizers].transpose().collect { List pair ->
            Map pathInfo = (Map) pair[0]; String normalizer = (String) pair[1]
            String safePath = CompareIdExpressionSupport.safeSparkColumnPath(CompareIdExpressionSupport.normalizeSparkPath(pathInfo.path as String))
            applyIdNormalizer(col(safePath), normalizer)
        }
        validateNoBlankCompositeKeyFields(rawDf, fieldCols, fieldExpressions, pathLabel)
        Dataset idDf = rawDf.select(buildCompositeCompareIdColumn(fieldCols).alias("compare_id"))
        return idDf.filter("compare_id IS NOT NULL AND length(trim(compare_id)) > 0").distinct()
    }

    static Dataset buildJsonDataDf(Dataset rawDf, List<Map> pathInfos, String pathLabel, List<String> idNormalizers, List<String> fieldExpressions) {
         Map sharedPathInfo = validateSharedRecordRoot(pathInfos, pathLabel)
         if (sharedPathInfo.needsExplode) {
             String arrayPath = CompareIdExpressionSupport.safeSparkColumnPath(CompareIdExpressionSupport.normalizeSparkPath(sharedPathInfo.arrayPath as String))
             Dataset explodedDf = rawDf.selectExpr("explode(${arrayPath}) as exploded_item")
             if (!datasetHasRows(explodedDf)) return emptyCompareDataDataset(rawDf.sparkSession())
             List<Column> fieldCols = [pathInfos, idNormalizers].transpose().collect { List pair ->
                 Map pathInfo = (Map) pair[0]; String normalizer = (String) pair[1]
                 String fieldPath = CompareIdExpressionSupport.safeSparkColumnPath(CompareIdExpressionSupport.normalizeSparkPath(pathInfo.fieldPath as String))
                 applyIdNormalizer(col("exploded_item.${fieldPath}"), normalizer)
             }
             validateNoBlankCompositeKeyFields(explodedDf, fieldCols, fieldExpressions, pathLabel)
             Dataset dataDf = explodedDf.select(buildCompositeCompareIdColumn(fieldCols).alias("compare_id"), col("exploded_item").alias("data"))
             return dataDf.filter("compare_id IS NOT NULL AND length(trim(compare_id)) > 0")
         }
         List<Column> fieldCols = [pathInfos, idNormalizers].transpose().collect { List pair ->
             Map pathInfo = (Map) pair[0]; String normalizer = (String) pair[1]
             String safePath = CompareIdExpressionSupport.safeSparkColumnPath(CompareIdExpressionSupport.normalizeSparkPath(pathInfo.path as String))
             applyIdNormalizer(col(safePath), normalizer)
         }
         validateNoBlankCompositeKeyFields(rawDf, fieldCols, fieldExpressions, pathLabel)
         Dataset dataDf = rawDf.select(buildCompositeCompareIdColumn(fieldCols).alias("compare_id"), struct(col("*")).alias("data"))
         return dataDf.filter("compare_id IS NOT NULL AND length(trim(compare_id)) > 0")
    }

    private static boolean datasetHasRows(Dataset df) {
        return df != null && df.limit(1).count() > 0
    }

    private static Dataset emptyCompareIdDataset(SparkSession spark) {
        return spark.createDataFrame(new ArrayList<Row>(), ReconciliationServices.EMPTY_COMPARE_ID_SCHEMA)
    }

    private static Dataset emptyCompareDataDataset(SparkSession spark) {
        return spark.createDataFrame(new ArrayList<Row>(), ReconciliationServices.EMPTY_COMPARE_DATA_SCHEMA)
    }

    static Dataset buildCsvIdDf(Dataset rawDf, List<Map> idSpecs, String label = null, boolean hasHeader = true) {
         List<Column> fieldCols = buildCsvFieldColumns(rawDf, idSpecs, label, hasHeader)
         List<String> fieldExpressions = idSpecs.collect { (String) it.idExpr }
         validateNoBlankCompositeKeyFields(rawDf, fieldCols, fieldExpressions, label)
         Dataset idDf = rawDf.select(buildCompositeCompareIdColumn(fieldCols).alias("compare_id"))
         return idDf.filter("compare_id IS NOT NULL AND length(trim(compare_id)) > 0").distinct()
    }

    static Dataset buildCsvDataDf(Dataset rawDf, List<Map> idSpecs, String label = null, boolean hasHeader = true) {
         List<Column> fieldCols = buildCsvFieldColumns(rawDf, idSpecs, label, hasHeader)
         List<String> fieldExpressions = idSpecs.collect { (String) it.idExpr }
         validateNoBlankCompositeKeyFields(rawDf, fieldCols, fieldExpressions, label)
         Dataset dataDf = rawDf.select(buildCompositeCompareIdColumn(fieldCols).alias("compare_id"), struct(col("*")).alias("data"))
         return dataDf.filter("compare_id IS NOT NULL AND length(trim(compare_id)) > 0")
    }

    private static List<Column> buildCsvFieldColumns(Dataset rawDf, List<Map> idSpecs, String label, boolean hasHeader) {
        return idSpecs.collect { Map idSpec ->
            String fieldExpr = buildValidatedCsvFieldExpr(rawDf, (String) idSpec.idExpr, label, hasHeader)
            Column rawColumn = expr(fieldExpr)
            String normalizerValue = (String) idSpec.idNormalizer
            return applyIdNormalizer(rawColumn, normalizerValue)
        }
    }

    // Composite keys (>=2 fields) can only be built from JSON records that share a single array/record
    // root — mixing key fields resolved under different array paths would explode into a cross product
    // rather than a per-record composite, so reject that configuration up front rather than silently
    // producing nonsense compare_id values.
    private static Map validateSharedRecordRoot(List<Map> pathInfos, String pathLabel) {
        Map first = (Map) pathInfos[0]
        boolean needsExplode = first.needsExplode as boolean
        boolean allMatch = pathInfos.every { Map info ->
            (info.needsExplode as boolean) == needsExplode &&
                    (!needsExplode || info.arrayPath == first.arrayPath)
        }
        if (!allMatch) {
            throw new IllegalArgumentException(
                    "${pathLabel}: composite key fields must all resolve to the same JSON record level; found key fields under different array paths."
            )
        }
        return first
    }

    private static Column buildCompositeCompareIdColumn(List<Column> normalizedFieldColumns) {
        return concat_ws(ReconciliationServices.COMPOSITE_KEY_DELIMITER, normalizedFieldColumns as Column[])
    }

    // Spark's concat_ws silently SKIPS null columns instead of nulling the whole result, so a missing
    // composite field would otherwise produce a shorter, silently-collidable compare_id rather than
    // failing loudly. Only applies to genuinely composite (>=2 field) keys — single-field sources keep
    // their existing behavior of silently dropping null-id rows via the compare_id IS NOT NULL filter.
    private static void validateNoBlankCompositeKeyFields(Dataset dfWithFieldCols, List<Column> normalizedFieldColumns, List<String> fieldExpressions, String pathLabel) {
        if (normalizedFieldColumns.size() < 2) return
        Column anyBlank = normalizedFieldColumns.inject((Column) lit(false)) { Column acc, Column fieldCol ->
            acc.or(fieldCol.isNull().or(length(trim(fieldCol.cast("string"))).equalTo(0)))
        } as Column
        boolean hasBlankRows = dfWithFieldCols.filter(anyBlank).limit(1).count() > 0
        if (hasBlankRows) {
            throw new IllegalArgumentException(
                    "${pathLabel}: composite key fields [${fieldExpressions.join(', ')}] must all be present on every row, but at least one row has a null or blank value for one of them."
            )
        }
    }

    private static String buildValidatedCsvFieldExpr(Dataset rawDf, String fieldName, String label, boolean hasHeader) {
        String normalizedField = normalize(fieldName) ?: "id"
        validateCsvFieldExists(rawDf, normalizedField, label, hasHeader)
        return "`" + normalizedField.replace("`", "``") + "`"
    }

    private static void validateCsvFieldExists(Dataset rawDf, String fieldName, String label, boolean hasHeader) {
        List<String> availableColumns = ((rawDf?.columns() ?: [] as String[]) as List<String>)
        if (availableColumns.contains(fieldName)) return

        String columnSummary = availableColumns.isEmpty() ?
                "(none)" :
                availableColumns.collect { String column ->
                    String normalized = normalize(column)
                    normalized ?: "(blank)"
                }.join(", ")

        String labelPrefix = normalize(label) ? "${normalize(label)}: " : ""
        List<String> hints = []
        if (availableColumns.any { String column ->
            String normalized = normalize(column)
            normalized == "{" || normalized == "[" || normalized?.startsWith("{") || normalized?.startsWith("[")
        }) {
            hints.add("The staged file looks like JSON, so either the compare scope file type is wrong or the uploaded file does not match this saved run.")
        }
        if (availableColumns && availableColumns.every { String column ->
            String normalized = normalize(column)
            normalized ==~ /_c\d+/
        }) {
            hints.add("The staged CSV does not expose named headers; check hasHeader and the uploaded file structure.")
        }

        String hintText = hints.isEmpty() ? "" : " " + hints.join(" ")
        throw new IllegalArgumentException("${labelPrefix}CSV compare source expected column '${fieldName}' but available columns were [${columnSummary}].${hintText}")
    }
}
