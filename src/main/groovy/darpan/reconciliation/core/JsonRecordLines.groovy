package darpan.reconciliation.core

import com.fasterxml.jackson.core.JsonEncoding
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.io.SerializedString
import darpan.facade.common.DataManagerSupport
import darpan.common.FactText
import darpan.reconciliation.source.KeepRules
import groovy.json.JsonParserType
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import org.apache.spark.sql.types.ArrayType
import org.apache.spark.sql.types.DataTypes
import org.apache.spark.sql.types.StructField
import org.apache.spark.sql.types.StructType
import org.moqui.context.ExecutionContext

import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * DAR-BE-166. A JSON extract's records, one per line, so Spark reads them in partitions. Every extract is one document,
 * {metadata, records:[...]}, and Spark's multiLine reader makes each FILE a single row: the compare parsed a whole
 * extract in one task, then exploded it, and a month of Steve Madden orders (125k, 50 MB, nested) ran a 2 GB JVM out of
 * heap. Jackson copies each record's tokens straight to the lines file, so no record set is ever held in memory.
 *
 * Used only when every key field sits under the same top-level array; anything else is read whole, as before.
 */
@CompileStatic
class JsonRecordLines {

    private static final JsonFactory FACTORY = new JsonFactory()
    static final String SUFFIX = ".records.jsonl"

    /** As below, beside an extract that sits under the data manager (a run's own folder), else in the temp directory. */
    static File forRecordArray(ExecutionContext ec, String path, List<Map> pathInfos) {
        return forRecordArray(ec, path, pathInfos, (List<Map>) null)
    }

    /** As above, and each record also carries `injections` (KeepRules.injections): its Keep field text columns and its row. */
    static File forRecordArray(ExecutionContext ec, String path, List<Map> pathInfos, List<Map> injections) {
        File dataManager = null
        try {
            dataManager = DataManagerSupport.resolveDirectoryFile(ec, DataManagerSupport.resolveDataManagerLocation(ec), false)
        } catch (Exception ignored) {
        }
        return forRecordArray(path, pathInfos, dataManager, injections)
    }

    /**
     * The lines file for the records the key paths explode, or null when the paths do not name one top-level array or the
     * document holds no record under it; the caller then reads the document whole. Written beside the extract only when
     * it sits under besideUnder (a source or fixture file elsewhere never gains a sibling), else in the temp directory.
     */
    static File forRecordArray(String path, List<Map> pathInfos, File besideUnder) {
        return forRecordArray(path, pathInfos, besideUnder, (List<Map>) null)
    }

    static File forRecordArray(String path, List<Map> pathInfos, File besideUnder, List<Map> injections) {
        String arrayField = topLevelArray(pathInfos)
        File source = path ? new File(path) : null
        if (arrayField == null || source == null || !source.isFile()) return null
        File work = workFile(source, besideUnder)
        boolean handedOver = false
        try {
            if (write(source, arrayField, work, injections) <= 0) return null
            handedOver = true
            if (work.parentFile == source.parentFile) {
                File target = new File(source.parentFile, source.name + SUFFIX)
                Files.move(work.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                return target
            }
            // Spark reads lazily, so the file outlives this call; outside the run folder it goes with the JVM.
            work.deleteOnExit()
            return work
        } finally {
            if (!handedOver) work.delete()
        }
    }

    /** The key paths as the lines file sees them: from the record root, with nothing to explode. */
    static List<Map> recordRootPathInfos(List<Map> pathInfos) {
        return pathInfos.collect { Map info -> [needsExplode: false, path: info.fieldPath] as Map }
    }

    /** The record schema inside a saved document schema (the element of its records array), or null to infer one. */
    static StructType recordSchema(StructType documentSchema, List<Map> pathInfos) {
        return recordSchema(documentSchema, pathInfos, (List<Map>) null)
    }

    /** As above, with a string column per injection and the long row column appended when there are injections. */
    static StructType recordSchema(StructType documentSchema, List<Map> pathInfos, List<Map> injections) {
        StructType base = baseRecordSchema(documentSchema, pathInfos)
        if (base == null || !injections) return base
        StructType out = base
        for (Map injection : injections) out = out.add(injection.get("column") as String, DataTypes.StringType, true)
        return out.add(KeepRules.ROW_COLUMN, DataTypes.LongType, true)
    }

    private static StructType baseRecordSchema(StructType documentSchema, List<Map> pathInfos) {
        String arrayField = topLevelArray(pathInfos)
        if (documentSchema == null || arrayField == null) return null
        StructField field = documentSchema.fields().find { StructField f -> f.name() == arrayField }
        if (!(field?.dataType() instanceof ArrayType)) return null
        def element = ((ArrayType) field.dataType()).elementType()
        return element instanceof StructType ? (StructType) element : null
    }

    /** Copies each object under `arrayField` to `target`, one per line; answers how many, or -1 when there is no such array. */
    static int write(File source, String arrayField, File target) {
        return write(source, arrayField, target, (List<Map>) null)
    }

    static int write(File source, String arrayField, File target, List<Map> injections) {
        Map<String, List<Map>> byField = null
        if (injections) {
            byField = new LinkedHashMap<String, List<Map>>()
            for (Map injection : injections) {
                String field = injection.get("field") as String
                if (!byField.containsKey(field)) byField.put(field, new ArrayList<Map>())
                byField.get(field).add(injection)
            }
        }
        JsonParser parser = FACTORY.createParser(source)
        try {
            if (parser.nextToken() != JsonToken.START_OBJECT) return -1
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String name = parser.currentName()
                JsonToken value = parser.nextToken()
                if (name == arrayField && value == JsonToken.START_ARRAY) return copyRecords(parser, target, byField)
                parser.skipChildren()
            }
            return -1
        } finally {
            parser.close()
        }
    }

    private static int copyRecords(JsonParser parser, File target, Map<String, List<Map>> byField) {
        int count = 0
        target.withOutputStream { OutputStream out ->
            JsonGenerator generator = FACTORY.createGenerator(out, JsonEncoding.UTF8)
            // Root values are separated by a newline and records hold none of their own: Spark splits on it.
            generator.setRootValueSeparator(new SerializedString("\n"))
            try {
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    if (parser.currentToken() != JsonToken.START_OBJECT) {
                        parser.skipChildren()
                        continue
                    }
                    copyRecord(parser, generator, byField, count)
                    count++
                }
                generator.writeRaw('\n')
            } finally {
                generator.close()
            }
        }
        return count
    }

    /**
     * One record, token by token, from its START_OBJECT to its END_OBJECT. A number is written from its own text, so a
     * decimal keeps its digits instead of passing through a double, as the whole-document read saw it.
     *
     * With `byField` (field to its injections), the record's own keys are also scanned for those fields: the first key
     * whose trimmed text equals one, as SourceFilterSupport.evaluate scans, has its value's text written as the
     * injection's string column, together with the record's row ordinal. Only a matching container's tokens are
     * buffered. An absent key or a JSON null leaves the column out.
     */
    private static void copyRecord(JsonParser parser, JsonGenerator generator, Map<String, List<Map>> byField, long row) {
        int depth = 0
        Set<String> matched = byField != null ? new HashSet<String>() : null
        Map<String, String> texts = byField != null ? new LinkedHashMap<String, String>() : null
        String pending = null
        String capturing = null
        StringWriter captureText = null
        JsonGenerator capture = null
        while (true) {
            JsonToken token = parser.currentToken()
            if (byField != null) {
                if (pending != null) {
                    String field = pending
                    pending = null
                    if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                        capturing = field
                        captureText = new StringWriter()
                        capture = FACTORY.createGenerator(captureText)
                    } else if (token != JsonToken.VALUE_NULL) {
                        Object value = token == JsonToken.VALUE_STRING ? parser.getText() :
                                token == JsonToken.VALUE_TRUE ? Boolean.TRUE :
                                token == JsonToken.VALUE_FALSE ? Boolean.FALSE : null
                        if (value == null) {   // a number: its own text
                            record(byField.get(field), parser.getText(), new BigDecimalText(parser.getText()), texts)
                        } else {
                            record(byField.get(field), String.valueOf(value), value, texts)
                        }
                    }
                } else if (depth == 1 && token == JsonToken.FIELD_NAME) {
                    String name = parser.currentName().trim()
                    if (byField.containsKey(name) && matched.add(name)) pending = name
                }
                if (token == JsonToken.END_OBJECT && depth == 1) writeInjected(generator, texts, row)
            }
            if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
                generator.writeNumber(parser.getText())
                if (capture != null) capture.writeNumber(parser.getText())
            } else {
                generator.copyCurrentEvent(parser)
                if (capture != null) capture.copyCurrentEvent(parser)
            }
            if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) depth++
            else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) depth--
            if (capture != null && depth == 1) {
                capture.close()
                Object value = new JsonSlurper().setType(JsonParserType.CHARACTER_SOURCE).parseText(captureText.toString())
                record(byField.get(capturing), String.valueOf(value), value, texts)
                capture = null
                captureText = null
                capturing = null
            }
            if (depth == 0) return
            parser.nextToken()
        }
    }

    /** A number as Groovy saw it for FACT text: its value, held with its raw text for GROOVY. */
    private static class BigDecimalText {
        final String raw
        BigDecimalText(String raw) { this.raw = raw }
        Object value() {
            try { return new BigDecimal(raw) } catch (NumberFormatException ignored) { return raw }
        }
    }

    private static void record(List<Map> injections, String groovyText, Object value, Map<String, String> texts) {
        for (Map injection : injections) {
            String column = injection.get("column") as String
            if ("FACT" == injection.get("text")) {
                Object fact = value instanceof BigDecimalText ? ((BigDecimalText) value).value() : value
                texts.put(column, FactText.flat([(injection.get("field")): fact] as Map).values().first())
            } else {
                texts.put(column, groovyText)
            }
        }
    }

    private static void writeInjected(JsonGenerator generator, Map<String, String> texts, long row) {
        texts.each { String column, String text -> generator.writeStringField(column, text) }
        generator.writeNumberField(KeepRules.ROW_COLUMN, row)
    }

    /** The one top-level array every key path explodes, or null. */
    private static String topLevelArray(List<Map> pathInfos) {
        if (!pathInfos) return null
        Set<String> arrays = pathInfos.collect { Map info ->
            info.needsExplode ? CompareIdExpressionSupport.normalizeSparkPath(info.arrayPath as String) : null
        } as Set<String>
        if (arrays.size() != 1) return null
        String array = arrays.first()
        return array && !array.contains(".") ? array : null
    }

    private static File workFile(File source, File besideUnder) {
        File folder = source.parentFile
        if (folder != null && folder.canWrite() && besideUnder != null &&
                folder.canonicalPath.startsWith(besideUnder.canonicalPath + File.separator)) {
            return Files.createTempFile(folder.toPath(), source.name + ".", ".partial").toFile()
        }
        return Files.createTempFile("darpan-records-", SUFFIX).toFile()
    }
}
