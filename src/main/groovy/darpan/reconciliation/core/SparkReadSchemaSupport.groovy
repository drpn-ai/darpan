package darpan.reconciliation.core

import groovy.json.JsonSlurper
import jsonschema.common.JsonSchemaUtil
import org.apache.spark.sql.types.ArrayType
import org.apache.spark.sql.types.DataType
import org.apache.spark.sql.types.DataTypes
import org.apache.spark.sql.types.StructField
import org.apache.spark.sql.types.StructType
import org.moqui.context.ExecutionContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * Audit 2026-06-11 #25 — supply the saved JSON Schema as the Spark read schema to skip per-run
 * schema inference (the extra full-file scan {@code spark.read().json()} performs to discover types).
 *
 * <p><b>Opt-in, default OFF.</b> Gated by the {@code darpan.reconciliation.spark.useSavedReadSchema}
 * system property. When unset/false the ingest path is unchanged (inference) — there is no behaviour
 * or correctness change. Operators enable it only for sources whose schema is authoritative and stable.
 *
 * <p><b>Why opt-in.</b> A provided read schema makes Spark read ONLY the declared columns: a field that
 * appears in newer data but is absent from the saved schema is silently omitted from the per-record
 * {@code struct(*)} data payload (schema drift). And a type that disagrees with the data is coerced.
 * That correctness trade-off is acceptable only as an explicit operator choice, never by default.
 *
 * <p><b>Safety net.</b> If the converted schema does not resolve the id / rule field paths,
 * {@link ReconciliationServices#ingestFile} catches the Spark {@code AnalysisException} and transparently
 * falls back to inference — so a stale schema degrades to "slower but correct", never a failed run.
 *
 * <p>The converter accepts the Draft-07 documents produced by the schema inferrer (the same shape
 * {@code SchemaFlattener} walks): {@code type}/{@code properties}/{@code items}/{@code $ref}+definitions
 * and {@code anyOf}/{@code oneOf}/{@code allOf}. Anything it cannot turn into a usable record
 * {@code StructType} yields {@code null} → caller infers. Pure (no Spark session needed to build types).
 */
class SparkReadSchemaSupport {

    private static final Logger logger = LoggerFactory.getLogger(SparkReadSchemaSupport.class)
    static final String USE_SAVED_READ_SCHEMA_PROPERTY = "darpan.reconciliation.spark.useSavedReadSchema"
    private static final int MAX_SCHEMA_DEPTH = 60

    /** True only when an operator has explicitly opted into saved-schema reads. Default false. */
    static boolean savedReadSchemaEnabled() {
        return Boolean.parseBoolean(System.getProperty(USE_SAVED_READ_SCHEMA_PROPERTY, "false"))
    }

    /**
     * Load the saved schema named {@code schemaFileName} and convert it to a Spark read schema (the
     * {@code StructType} of one top-level record). Returns null on any problem so the caller infers.
     */
    static StructType buildReadSchema(ExecutionContext ec, Object schemaFileName) {
        String name = schemaFileName?.toString()?.trim()
        if (!name) return null
        try {
            String schemaText = JsonSchemaUtil.loadSchemaText(ec, null, name)
            if (!(schemaText?.trim())) return null
            return toReadSchema(schemaText)
        } catch (Exception e) {
            logger.warn("Could not build a Spark read schema from saved schema '${name}'; will infer. ${e.message}")
            return null
        }
    }

    /** Convert a JSON Schema document string into the record {@code StructType}, or null if unusable. */
    static StructType toReadSchema(String schemaText) {
        Object parsed
        try {
            parsed = new JsonSlurper().parseText(schemaText)
        } catch (Exception ignored) {
            return null
        }
        if (!(parsed instanceof Map)) return null
        Map root = (Map) parsed
        Map definitions = resolveDefinitions(root)

        DataType dataType = toDataType(root, definitions, 0)
        if (dataType instanceof StructType) {
            return ((StructType) dataType).fields().length > 0 ? (StructType) dataType : null
        }
        // Array-root file: spark.read().json() yields one row per element, so the read schema is the
        // element struct.
        if (dataType instanceof ArrayType) {
            DataType element = ((ArrayType) dataType).elementType()
            if (element instanceof StructType && ((StructType) element).fields().length > 0) {
                return (StructType) element
            }
        }
        return null
    }

    private static Map resolveDefinitions(Map root) {
        Object defs = root.get('definitions')
        if (defs instanceof Map) return (Map) defs
        Object alt = root.get('$defs')
        return (alt instanceof Map) ? (Map) alt : [:]
    }

    private static DataType toDataType(Map node, Map definitions, int depth) {
        if (node == null || depth > MAX_SCHEMA_DEPTH) return DataTypes.StringType

        Object ref = node.get('$ref')
        if (ref instanceof String) {
            Map resolved = resolveRef((String) ref, definitions)
            if (resolved != null) return toDataType(resolved, definitions, depth + 1)
        }

        Map propertyHolder = pickPropertyHolder(node)
        Object items = node.get('items')
        String type = resolveTypeString(node)

        if (type == 'object' || propertyHolder != null) {
            return buildStruct(propertyHolder, definitions, depth)
        }
        if (type == 'array' || items != null) {
            DataType elementType = elementDataType(items, definitions, depth)
            return DataTypes.createArrayType(elementType, true)
        }
        return primitiveDataType(type)
    }

    private static DataType elementDataType(Object items, Map definitions, int depth) {
        Map itemNode = null
        if (items instanceof Map) {
            itemNode = (Map) items
        } else if (items instanceof List && !((List) items).isEmpty() && ((List) items).get(0) instanceof Map) {
            itemNode = (Map) ((List) items).get(0)
        }
        return itemNode != null ? toDataType(itemNode, definitions, depth + 1) : DataTypes.StringType
    }

    private static StructType buildStruct(Map propertyHolder, Map definitions, int depth) {
        List<StructField> fields = []
        if (propertyHolder != null) {
            propertyHolder.each { Object key, Object value ->
                if (value instanceof Map) {
                    DataType fieldType = toDataType((Map) value, definitions, depth + 1)
                    fields.add(DataTypes.createStructField(key.toString(), fieldType, true))
                }
            }
        }
        return DataTypes.createStructType(fields)
    }

    /** Mirror SchemaFlattener.pickProperties: prefer `properties`, else the first combinator option's. */
    private static Map pickPropertyHolder(Map node) {
        Object props = node.get('properties')
        if (props instanceof Map) return (Map) props

        for (String combinator : ['anyOf', 'oneOf', 'allOf']) {
            Object option = node.get(combinator)
            if (option instanceof List) {
                Object match = ((List) option).find { it instanceof Map && ((Map) it).get('properties') instanceof Map }
                if (match != null) return (Map) ((Map) match).get('properties')
            }
        }
        return null
    }

    /** JSON Schema `type` may be a string or a `["string","null"]` union; pick the first non-null type. */
    private static String resolveTypeString(Map node) {
        Object type = node.get('type')
        if (type instanceof String) return (String) type
        if (type instanceof List) {
            Object first = ((List) type).find { it instanceof String && it != 'null' }
            if (first != null) return (String) first
        }
        return null
    }

    private static Map resolveRef(String ref, Map definitions) {
        if (!ref) return null
        int idx = ref.lastIndexOf('/')
        String key = idx >= 0 ? ref.substring(idx + 1) : ref
        Object resolved = definitions?.get(key)
        return (resolved instanceof Map) ? (Map) resolved : null
    }

    private static DataType primitiveDataType(String type) {
        switch (type) {
            case 'integer': return DataTypes.LongType
            case 'number': return DataTypes.DoubleType
            case 'boolean': return DataTypes.BooleanType
            case 'string': return DataTypes.StringType
            default: return DataTypes.StringType
        }
    }
}
