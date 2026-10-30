package darpan.reconciliation.core

import com.fasterxml.jackson.core.JsonEncoding
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.io.SerializedString
import darpan.facade.common.DataManagerSupport
import groovy.transform.CompileStatic
import org.apache.spark.sql.types.ArrayType
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
        File dataManager = null
        try {
            dataManager = DataManagerSupport.resolveDirectoryFile(ec, DataManagerSupport.resolveDataManagerLocation(ec), false)
        } catch (Exception ignored) {
        }
        return forRecordArray(path, pathInfos, dataManager)
    }

    /**
     * The lines file for the records the key paths explode, or null when the paths do not name one top-level array or the
     * document holds no record under it; the caller then reads the document whole. Written beside the extract only when
     * it sits under besideUnder (a source or fixture file elsewhere never gains a sibling), else in the temp directory.
     */
    static File forRecordArray(String path, List<Map> pathInfos, File besideUnder) {
        String arrayField = topLevelArray(pathInfos)
        File source = path ? new File(path) : null
        if (arrayField == null || source == null || !source.isFile()) return null
        File work = workFile(source, besideUnder)
        boolean handedOver = false
        try {
            if (write(source, arrayField, work) <= 0) return null
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
        String arrayField = topLevelArray(pathInfos)
        if (documentSchema == null || arrayField == null) return null
        StructField field = documentSchema.fields().find { StructField f -> f.name() == arrayField }
        if (!(field?.dataType() instanceof ArrayType)) return null
        def element = ((ArrayType) field.dataType()).elementType()
        return element instanceof StructType ? (StructType) element : null
    }

    /** Copies each object under `arrayField` to `target`, one per line; answers how many, or -1 when there is no such array. */
    static int write(File source, String arrayField, File target) {
        JsonParser parser = FACTORY.createParser(source)
        try {
            if (parser.nextToken() != JsonToken.START_OBJECT) return -1
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String name = parser.currentName()
                JsonToken value = parser.nextToken()
                if (name == arrayField && value == JsonToken.START_ARRAY) return copyRecords(parser, target)
                parser.skipChildren()
            }
            return -1
        } finally {
            parser.close()
        }
    }

    private static int copyRecords(JsonParser parser, File target) {
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
                    copyRecord(parser, generator)
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
     */
    private static void copyRecord(JsonParser parser, JsonGenerator generator) {
        int depth = 0
        while (true) {
            JsonToken token = parser.currentToken()
            if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
                generator.writeNumber(parser.getText())
            } else {
                generator.copyCurrentEvent(parser)
            }
            if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) depth++
            else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) depth--
            if (depth == 0) return
            parser.nextToken()
        }
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
