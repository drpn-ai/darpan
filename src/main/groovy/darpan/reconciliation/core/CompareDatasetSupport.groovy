package darpan.reconciliation.core

import org.apache.spark.sql.Column
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Row
import org.apache.spark.sql.RowFactory
import org.apache.spark.sql.SparkSession
import org.apache.spark.storage.StorageLevel
import org.apache.spark.sql.types.DataType
import org.apache.spark.sql.types.DataTypes
import org.apache.spark.sql.types.DoubleType
import org.apache.spark.sql.types.FloatType

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

    /**
     * A key field's text: trimmed, then normalized. sourceType is the field's Spark type when the caller knows it (the
     * JSON key builders): a double or float column holds every number of a field whose values include one decimal, so
     * an integral value is written as its whole number ("7", "12345678901"), not Spark's "7.0" / "1.2345678901E10".
     * That is the text the conclude pass reads from the parsed record (ConclusionKeySupport), and the same key whatever
     * other records hold (S01b I5 parity).
     */
    static Column applyIdNormalizer(Column sourceCol, String idNormalizer, DataType sourceType = null) {
        Column normalized = trimControl(keyText(sourceCol, sourceType))
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

    /**
     * OC140: a key is trimmed as Java's String.trim trims (every leading/trailing char <= U+0020: spaces, tabs, CR, LF),
     * the way the keys file is read back (QuestionKeyFiles) and the conclude pass reads a record (ConclusionKeySupport).
     * Spark's trim takes spaces only, so "K1\t" stayed "K1\t" here but read "K1" there, and was never asked about.
     */
    private static Column trimControl(Column text) {
        return regexp_replace(text, '^[\\x00-\\x20]+|[\\x00-\\x20]+$', '')
    }

    /**
     * A double holds every integer up to 2^53 exactly, so below it the long cast is lossless (a 16-digit id stays itself).
     * At or above it the value was already rounded when the JSON was read, so it keeps Spark's text rather than print a
     * number the source never held; reading such ids exactly is DAR-BE-416.
     */
    private static final double EXACT_INTEGRAL_LIMIT = 9007199254740992d

    private static Column keyText(Column sourceCol, DataType sourceType) {
        if (!(sourceType instanceof DoubleType || sourceType instanceof FloatType)) return sourceCol.cast("string")
        Column integral = not(isnan(sourceCol)).and(abs(sourceCol).lt(EXACT_INTEGRAL_LIMIT)).and(sourceCol.equalTo(floor(sourceCol)))
        return when(integral, sourceCol.cast("long").cast("string")).otherwise(sourceCol.cast("string"))
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

    /** Default row type for an EVALUATE scope's findings; see buildEvaluationFindingRows. */
    static final String EVALUATION_FINDING_TYPE = "FINDING"

    /**
     * Findings from a single-sided EVALUATE compare scope (DAR-BE-049).
     *
     * An evaluate scope has no second side, so its findings cannot come from buildMissingDiffRows —
     * there is no missing-id frame to join against. The PREDICATE ALREADY RAN IN THE EXTRACTOR, so
     * every row it emitted is a finding and none are filtered here.
     *
     * THE SCHEMA IS DELIBERATELY IDENTICAL to buildMissingDiffRows above, column for column and type
     * for type. Everything downstream — writeDiffDatasetOutput, the differences UI, runResultDiffDetails,
     * and the verification passes that re-read the document — is written against that shape, and none
     * of them switch on the row type, so a drifted schema would render as blanks with nothing to warn
     * on. EvaluationFindingRowsTests asserts the parity against the real builder rather than a copied
     * column list.
     *
     * `missingIn` is EMPTY rather than null on purpose: nothing is missing on an evaluate run, but a
     * lit(null) would be a NullType column, breaking both the schema parity and any union with diff
     * rows.
     */
    static Dataset buildEvaluationFindingRows(Dataset dataDf, String diffType, String sourceLabel, String note) {
        if (dataDf == null) return null
        return dataDf.select(
                lit(diffType ?: EVALUATION_FINDING_TYPE).alias("type"),
                col("compare_id").alias("id"),
                lit(sourceLabel ?: "").alias("presentIn"),
                lit("").alias("missingIn"),
                to_json(col("data")).alias("data"),
                lit(note ?: "").alias("note")
        )
    }

    /** DAR-BE-063: the compare_ids that appear on more than one row (blank ids are not keys). */
    static Dataset duplicateCompareIds(Dataset dataDf) {
        return dataDf.filter("compare_id IS NOT NULL AND length(trim(compare_id)) > 0")
                .groupBy("compare_id").count().filter(col("count").gt(1)).select("compare_id")
    }

    /** DAR-BE-063: df without the rows whose compare_id is in ids. */
    static Dataset excludeCompareIds(Dataset df, Dataset ids) {
        if (df == null || ids == null) return df
        return df.join(ids, df.col("compare_id").equalTo(ids.col("compare_id")), "left_anti")
    }

    /** DAR-UI-048: the rows whose compare_id is in {@code ids}; the semi-join keeps df's own columns. */
    static Dataset keepCompareIds(Dataset df, Dataset ids) {
        if (df == null || ids == null) return df
        return df.join(ids, df.col("compare_id").equalTo(ids.col("compare_id")), "left_semi")
    }

    /** OC14: set by RuleSetCompareScopeAdapter.splitDuplicateKeys on a partner record (its key repeats only on the other side). */
    static final String DUPLICATE_ROW_TYPE_COLUMN = "duplicate_row_type"
    static final String DUPLICATE_ROW_NOTE_COLUMN = "duplicate_row_note"

    /**
     * DAR-BE-063: one finding row per record under a duplicated key, in buildMissingDiffRows' exact row
     * shape. missingIn is EMPTY (the evaluate-row precedent): nothing is missing, and the verify pass
     * selects rows by missingIn, so it leaves these alone. OC14: a record the split tagged as a partner
     * carries its own type and note (DUPLICATE_IN_FILE_n_PAIRED); every other record takes diffType and note.
     */
    static Dataset buildDuplicateRows(Dataset duplicateDataDf, String diffType, String sideLabel, String note) {
        if (duplicateDataDf == null) return null
        boolean tagged = (duplicateDataDf.columns() as List).contains(DUPLICATE_ROW_TYPE_COLUMN)
        Column type = tagged ? coalesce(col(DUPLICATE_ROW_TYPE_COLUMN), lit(diffType)) : lit(diffType)
        Column rowNote = tagged ? coalesce(col(DUPLICATE_ROW_NOTE_COLUMN), lit(note)) : lit(note)
        return duplicateDataDf.select(
                type.alias("type"),
                col("compare_id").alias("id"),
                lit(sideLabel).alias("presentIn"),
                lit("").alias("missingIn"),
                to_json(col("data")).alias("data"),
                rowNote.alias("note")
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
                lit(null).cast(DataTypes.StringType).alias("ids"),
                lit(null).cast(DataTypes.StringType).alias("sideRecords"),
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
                    normalize(diff.ids),
                    normalize(diff.sideRecords),
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

    /** A side's distinct compare ids: the keys of its data frame. */
    static Dataset buildJsonIdDf(Dataset rawDf, List<Map> pathInfos, String pathLabel, List<String> idNormalizers, List<String> fieldExpressions) {
        return distinctKeys(buildJsonDataDf(rawDf, pathInfos, pathLabel, idNormalizers, fieldExpressions))
    }

    /**
     * One row per keyed record: compare_id plus the record as `data` (the array item when the key paths sit under one
     * array, else the whole record). A record whose single-field key is null or blank is left out (S01b F-E3).
     */
    static Dataset buildJsonDataDf(Dataset rawDf, List<Map> pathInfos, String pathLabel, List<String> idNormalizers, List<String> fieldExpressions) {
        return jsonRecords(rawDf, pathInfos, pathLabel, idNormalizers, fieldExpressions).filter(HAS_KEY)
    }

    /** OC15: a JSON side's [idDf, dataDf, keylessCount]: the records left out for a null or blank single-field key, counted. */
    static Map<String, Object> buildJsonFrames(Dataset rawDf, List<Map> pathInfos, String label, List<String> idNormalizers, List<String> fieldExpressions) {
        return framesOf(jsonRecords(rawDf, pathInfos, label, idNormalizers, fieldExpressions))
    }

    /** OC15: the run warning for a side's keyless records. */
    static String keylessRecordWarning(Object sideRef, long count) {
        return "${sideRef} has ${count} record(s) with a blank key; they are left out of the compare.".toString()
    }

    /** Every record with its compare_id, keyless ones included (a blank composite field has already failed loudly). */
    private static Dataset jsonRecords(Dataset rawDf, List<Map> pathInfos, String pathLabel, List<String> idNormalizers, List<String> fieldExpressions) {
        Map sharedPathInfo = validateSharedRecordRoot(pathInfos, pathLabel)
        if (!sharedPathInfo.needsExplode) {
            return keyedRecords(rawDf, recordKeyFieldColumns(rawDf, pathInfos, idNormalizers), struct(col("*")), fieldExpressions, pathLabel)
        }
        String arrayPath = CompareIdExpressionSupport.safeSparkColumnPath(CompareIdExpressionSupport.normalizeSparkPath(sharedPathInfo.arrayPath as String))
        Dataset explodedDf = rawDf.selectExpr("explode(${arrayPath}) as exploded_item")
        // An empty array's item type is unknown, so its key paths cannot be resolved; it simply has no records.
        if (!datasetHasRows(explodedDf)) return emptyCompareDataDataset(rawDf.sparkSession())
        return keyedRecords(explodedDf, explodedKeyFieldColumns(explodedDf, pathInfos, idNormalizers), col("exploded_item"), fieldExpressions, pathLabel)
    }

    private static final String HAS_KEY = "compare_id IS NOT NULL AND length(trim(compare_id)) > 0"

    private static Dataset keyedRecords(Dataset source, List<Column> fieldCols, Column data, List<String> fieldExpressions, String label) {
        validateNoBlankCompositeKeyFields(source, fieldCols, fieldExpressions, label)
        return source.select(buildCompositeCompareIdColumn(fieldCols).alias("compare_id"), data.alias("data"))
    }

    private static Map<String, Object> framesOf(Dataset records) {
        Dataset dataDf = records.filter(HAS_KEY)
        return [idDf: distinctKeys(dataDf), dataDf: dataDf, keylessCount: records.filter("NOT (${HAS_KEY})").count()] as Map<String, Object>
    }

    private static Dataset distinctKeys(Dataset dataDf) {
        return dataDf.select("compare_id").distinct()
    }

    /** One normalized key column per path, read from the exploded array item. */
    private static List<Column> explodedKeyFieldColumns(Dataset explodedDf, List<Map> pathInfos, List<String> idNormalizers) {
        return [pathInfos, idNormalizers].transpose().collect { List pair ->
            Map pathInfo = (Map) pair[0]; String normalizer = (String) pair[1]
            String fieldPath = CompareIdExpressionSupport.safeSparkColumnPath(CompareIdExpressionSupport.normalizeSparkPath(pathInfo.fieldPath as String))
            keyFieldColumn(explodedDf, col("exploded_item.${fieldPath}"), normalizer)
        }
    }

    /** One normalized key column per path, read from the record root. */
    private static List<Column> recordKeyFieldColumns(Dataset rawDf, List<Map> pathInfos, List<String> idNormalizers) {
        return [pathInfos, idNormalizers].transpose().collect { List pair ->
            Map pathInfo = (Map) pair[0]; String normalizer = (String) pair[1]
            String safePath = CompareIdExpressionSupport.safeSparkColumnPath(CompareIdExpressionSupport.normalizeSparkPath(pathInfo.path as String))
            keyFieldColumn(rawDf, col(safePath), normalizer)
        }
    }

    /** The key column, normalized with the type Spark gave the field in this frame (I5 parity). */
    private static Column keyFieldColumn(Dataset source, Column field, String normalizer) {
        return applyIdNormalizer(field, normalizer, source.select(field).schema().fields()[0].dataType())
    }

    private static boolean datasetHasRows(Dataset df) {
        return df != null && df.limit(1).count() > 0
    }

    private static Dataset emptyCompareDataDataset(SparkSession spark) {
        return spark.createDataFrame(new ArrayList<Row>(), ReconciliationServices.EMPTY_COMPARE_DATA_SCHEMA)
    }

    // hasHeader is never read (the header decision is made at read time); it stays so ingestFile's calls keep working.
    static Dataset buildCsvIdDf(Dataset rawDf, List<Map> idSpecs, String label = null, boolean hasHeader = true) {
        return distinctKeys(buildCsvDataDf(rawDf, idSpecs, label))
    }

    static Dataset buildCsvDataDf(Dataset rawDf, List<Map> idSpecs, String label = null, boolean hasHeader = true) {
        return csvRecords(rawDf, idSpecs, label).filter(HAS_KEY)
    }

    /** OC15: a CSV side's [idDf, dataDf, keylessCount], as buildJsonFrames. */
    static Map<String, Object> buildCsvFrames(Dataset rawDf, List<Map> idSpecs, String label = null) {
        return framesOf(csvRecords(rawDf, idSpecs, label))
    }

    private static Dataset csvRecords(Dataset rawDf, List<Map> idSpecs, String label) {
        return keyedRecords(rawDf, buildCsvFieldColumns(rawDf, idSpecs, label), struct(col("*")), idSpecs.collect { (String) it.idExpr }, label)
    }

    private static List<Column> buildCsvFieldColumns(Dataset rawDf, List<Map> idSpecs, String label) {
        return idSpecs.collect { Map idSpec ->
            String fieldExpr = buildValidatedCsvFieldExpr(rawDf, (String) idSpec.idExpr, label)
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
        // A field holding the delimiter itself would forge another record's key ("X\u001FY" + "Z" == "X" + "Y\u001FZ").
        Column anyDelimiter = normalizedFieldColumns.inject((Column) lit(false)) { Column acc, Column fieldCol ->
            acc.or(coalesce(fieldCol.cast("string").contains(ReconciliationServices.COMPOSITE_KEY_DELIMITER), lit(false)))
        } as Column
        if (dfWithFieldCols.filter(anyDelimiter).limit(1).count() > 0) {
            throw new IllegalArgumentException(
                    "${pathLabel}: composite key fields [${fieldExpressions.join(', ')}] must not contain the key delimiter (U+001F), but at least one row does."
            )
        }
    }

    private static String buildValidatedCsvFieldExpr(Dataset rawDf, String fieldName, String label) {
        String normalizedField = normalize(fieldName) ?: "id"
        validateCsvFieldExists(rawDf, normalizedField, label)
        return "`" + normalizedField.replace("`", "``") + "`"
    }

    private static void validateCsvFieldExists(Dataset rawDf, String fieldName, String label) {
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
