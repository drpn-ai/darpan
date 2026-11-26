package darpan.idindex

import darpan.dataset.DatasetRefreshSupport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.apache.spark.sql.Column
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Row
import org.apache.spark.sql.SparkSession
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp

import static org.apache.spark.sql.functions.col
import static org.apache.spark.sql.functions.get_json_object

/**
 * DAR-BE-374: indexes one Darpan read (a dataset read, a run's extract, a kept copy). Spark pulls each JSON line's key, kind
 * and the tenant's ID field values ({@link #extract}); Groovy normalises them ({@link IdIndexRows}) and adds or touches rows
 * ({@link #upsertRows}),
 * so the one normalisation search uses is the one the rows were written with.
 */
class IdIndexer {

    private static final Logger logger = LoggerFactory.getLogger(IdIndexer)
    static final int BATCH_ROWS = 500
    static final String ENTITY = "darpan.idindex.RecordIdIndex"

    /**
     * A dataset read's file (records under one JSON document's "records", or a bare array) as one record per line, in a temp
     * file of the index's own (the caller deletes it) so Spark reads it line by line; a file already ending .jsonl is returned
     * as it is.
     */
    static File toJsonLines(File readFile) {
        if (readFile.name.endsWith(".jsonl")) return readFile
        // DAR-BE-383: a temp file of the index's own, never "<read>.records.jsonl" beside the read. That name is the run's own
        // lines file (JsonRecordLines.SUFFIX), which this background index used to overwrite and then delete while the run's
        // Spark read (Keep) was reading it: FILE_NOT_EXIST on prod runs and sync pulses from 3.5.0. The caller deletes it.
        File out = File.createTempFile("idindex-", ".jsonl")
        boolean written = false
        DatasetRefreshSupport.RecordFileIterator records = null
        try {
            records = new DatasetRefreshSupport.RecordFileIterator(readFile)
            out.withWriter("UTF-8") { Writer w -> while (records.hasNext()) { w.write(JsonOutput.toJson(records.next())); w.write("\n") } }
            written = true
        } finally {
            records?.close()
            if (!written) out.delete()
        }
        return out
    }

    /** Indexes a read's JSON-lines file for one tenant connection: [records: n, rows: n]. kindHint: see IdIndexRows.recordKindOf. */
    static Map indexFile(def ec, String tenantId, Map source, String connectionId, File jsonLines, String kindHint = null) {
        List<Map> fields = IdIndexCatalog.fields(ec, tenantId, source.systemEnumId as String)
        int records = 0, rows = 0
        List<Map> batch = []
        Iterator<Map> it = extract(sharedSpark(), jsonLines, source, fields)
        while (it.hasNext()) {
            List<Map> recordRows = IdIndexRows.rowsFromValues(source, fields, it.next(), connectionId, kindHint)
            if (!recordRows) continue
            records++
            batch.addAll(recordRows)
            if (batch.size() >= BATCH_ROWS) { rows += upsertRows(ec, tenantId, connectionId, batch); batch = [] }
        }
        if (batch) rows += upsertRows(ec, tenantId, connectionId, batch)
        return [records: records, rows: rows]
    }

    /** Indexes records already in hand (a kept copy's current version) through the same path: written as JSON lines, then indexFile. */
    static Map indexRecords(def ec, String tenantId, Map source, String connectionId, Iterator<Map> records, File workDir, String kindHint = null) {
        workDir.mkdirs()
        File lines = new File(workDir, "idindex-${System.nanoTime()}.records.jsonl")
        try {
            lines.withWriter("UTF-8") { Writer w -> while (records.hasNext()) { w.write(JsonOutput.toJson(records.next())); w.write("\n") } }
            return indexFile(ec, tenantId, source, connectionId, lines, kindHint)
        } finally {
            lines.delete()
        }
    }

    /**
     * Each JSON line as [key, kind, values: {fieldPath: [raw ...]}]. A line that is not JSON, or has no key, is left
     * out. A [*] path's matches are flattened into the list, nested lists included.
     */
    static Iterator<Map> extract(SparkSession spark, File jsonLines, Map source, List<Map> fields) {
        List<String> paths = fields.collect { it.fieldPath as String }.unique()
        List<Column> cols = [get_json_object(col("value"), jsonPath(source.idField as String)).as("k"),
                             get_json_object(col("value"), jsonPath((source.recordKindField ?: source.idField) as String)).as("t")]
        paths.eachWithIndex { String p, int i -> cols << get_json_object(col("value"), jsonPath(p)).as("f" + i) }
        Dataset<Row> rows = spark.read().text(jsonLines.absolutePath).select(cols as Column[]).where(col("k").isNotNull())
        Iterator<Row> source0 = rows.toLocalIterator()
        boolean hasKindField = source.recordKindField as boolean
        return new Iterator<Map>() {
            boolean hasNext() { return source0.hasNext() }
            Map next() {
                Row r = source0.next()
                Map values = [:]
                paths.eachWithIndex { String p, int i -> values.put(p, scalars(r.getString(2 + i), p.contains("[*]"))) }
                return [key: r.getString(0), kind: hasKindField ? r.getString(1) : null, values: values]
            }
        }
    }

    /**
     * Adds or touches rows: a row already written (same rowKey) gets lastSeenAt = now, a new one is inserted. Never deletes:
     * a read may carry only some of a record's fields. One transaction per 500 rows. Returns rows written or touched.
     */
    static int upsertRows(def ec, String tenantId, String connectionId, List<Map> rows) {
        Map<String, Map> byKey = new LinkedHashMap<>()
        rows.each { Map row -> byKey.put(rowKey(tenantId, connectionId, row), row) }
        int written = 0
        byKey.keySet().toList().collate(BATCH_ROWS).each { List<String> keys ->
            ec.transaction.runRequireNew(null, "ID index write failed") {
                boolean was = ec.artifactExecution.disableAuthz()
                try {
                    Timestamp now = ec.user.nowTimestamp
                    Set<String> existing = ec.entity.find(ENTITY).condition("rowKey", EntityCondition.IN, keys).selectField("rowKey")
                            .useCache(false).list()*.rowKey as Set<String>
                    if (existing) ec.entity.find(ENTITY).condition("rowKey", EntityCondition.IN, existing).updateAll([lastSeenAt: now])
                    List<EntityValue> values = keys.findAll { !existing.contains(it) }.collect { String k ->
                        Map row = byKey.get(k)
                        ec.entity.makeValue(ENTITY).setAll([rowKey: k, companyUserGroupId: tenantId, connectionId: connectionId,
                                idNorm: row.idNorm, idRaw: row.idRaw, systemCode: row.systemCode, systemEnumId: row.systemEnumId,
                                recordKind: row.recordKind, recordKey: row.recordKey, fieldPath: row.fieldPath, role: row.role,
                                refRecordKind: row.refRecordKind, lastSeenAt: now])
                    }
                    if (values) ec.entity.createBulk(values)
                    written += keys.size()
                } finally {
                    if (!was) ec.artifactExecution.enableAuthz()
                }
            }
        }
        return written
    }

    /** One row per tenant, connection, kind, record, field and value: the same id written twice lands on the same key. */
    static String rowKey(String tenantId, String connectionId, Map row) {
        String identity = [tenantId, connectionId, row.recordKind, row.recordKey, row.fieldPath, row.idNorm].join("\u001F")
        return java.security.MessageDigest.getInstance("SHA-1").digest(identity.getBytes("UTF-8")).encodeHex().toString()
    }

    /** The JVM's embedded Spark session (getOrCreate returns the one runs already started; never stopped). */
    static SparkSession sharedSpark() {
        return SparkSession.builder().appName("darpan").master("local[*]").config("spark.ui.enabled", "false").getOrCreate()
    }

    static String jsonPath(String fieldPath) { return '$.' + fieldPath }

    /**
     * get_json_object's answer as a list of scalar texts: a JSON array (flattened), or one scalar; objects are dropped. A
     * [*] path that matched one value comes back JSON-encoded ("R1"), so a list path's answer is always parsed as JSON.
     */
    static List<String> scalars(String answer, boolean listPath) {
        if (answer == null) return []
        String t = answer.trim()
        if (t.startsWith("[") || (listPath && t.startsWith('"'))) {
            try {
                Object parsed = new JsonSlurper().parseText(t)
                List<String> out = []
                flatten(parsed, out)
                return out
            } catch (Exception ignored) {
                return [t]
            }
        }
        if (t.startsWith("{") || !t) return []
        return [t]
    }

    private static void flatten(Object node, List<String> out) {
        if (node instanceof Collection) ((Collection) node).each { flatten(it, out) }
        else if (node != null && !(node instanceof Map)) out << node.toString()
    }

}
