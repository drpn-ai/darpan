package darpan.reconciliation.conclusion

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Reads a record document — {records: [...], metadata: {...}} or a bare [...] — one record at a time, so memory holds
 * one record, never the file (production runs on a small JVM; an extract or sidecar parsed whole is several times its
 * size on disk). Every other top-level field is read whole and returned: they are the small ones (metadata, counts).
 *
 * Numbers read as JsonSlurper reads them (decimals as BigDecimal), so a record streamed here equals one parsed whole,
 * except an integer beyond the Long range, which JsonSlurper corrupts and this keeps exactly as a BigInteger.
 */
class JsonRecordStream {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    /**
     * One JSON value from text, read as the stream reads a record: a decimal as BigDecimal, an integer as Integer, Long or,
     * beyond the Long range, an exact BigInteger. JsonSlurper turns such an integer into a wrapped negative Long or null
     * (DAR-BE-318), so a record reader must not use it.
     */
    static Object parseText(String text) {
        return MAPPER.readValue(text, Object)
    }

    /**
     * Calls visit(Map) for each object in the array named arrayField (or in a top-level array) and returns the document's
     * other top-level fields. An element that is not an object is skipped.
     */
    static Map<String, Object> forEachRecord(File file, String arrayField = "records", Closure visit) {
        Map<String, Object> others = new LinkedHashMap<>()
        JsonParser parser = MAPPER.getFactory().createParser(file)
        try {
            JsonToken first = parser.nextToken()
            if (first == JsonToken.START_ARRAY) {
                readArray(parser, visit)
                return others
            }
            if (first != JsonToken.START_OBJECT) return others
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String name = parser.getCurrentName()
                JsonToken value = parser.nextToken()
                if (name == arrayField && value == JsonToken.START_ARRAY) readArray(parser, visit)
                else others.put(name, MAPPER.readValue(parser, Object))
            }
            return others
        } finally {
            parser.close()
        }
    }

    private static void readArray(JsonParser parser, Closure visit) {
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            Object element = MAPPER.readValue(parser, Object)
            if (element instanceof Map) visit.call((Map) element)
        }
    }
}
