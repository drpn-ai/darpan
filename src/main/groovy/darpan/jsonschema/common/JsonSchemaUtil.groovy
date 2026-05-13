package jsonschema.common

import com.fasterxml.jackson.databind.ObjectMapper
import darpan.facade.common.DataManagerSupport
import darpan.facade.common.FacadeSupport
import org.moqui.context.ExecutionContext

class JsonSchemaUtil {
    private static final ObjectMapper mapper = new ObjectMapper()
    private static final String JSON_SCHEMA_ENTITY_NAME = JsonSchemaConstants.JSON_SCHEMA_ENTITY_NAME
    private static final String SYSTEM_ENUM_TYPE_ID = JsonSchemaConstants.SYSTEM_ENUM_TYPE_ID

    /**
     * Sanitizes a filename to prevent path traversal/injection.
     * Returns null for tokens that contain path separators or traversal sequences so callers
     * can safely skip filesystem fallbacks without aborting the overall lookup.
     */
    static String cleanFileName(String rawName) {
        if (!rawName) return null
        String name = rawName.toString().trim()
        if (!name) return null
        if (name.contains("..") || name.contains("/") || name.contains("\\")) return null
        return name
    }

    /**
     * Loads schema text from saved schemas first, then falls back to legacy runtime schema files.
     * @param ec ExecutionContext
     * @param id Optional jsonSchemaId
     * @param name Optional schemaName (or filename)
     * @return Schema JSON String or null if not found
     */
    static String loadSchemaText(ExecutionContext ec, Object id, Object name) {
        // 1. Try DB by ID
        if (id) {
            def schema = ec.entity.find(JSON_SCHEMA_ENTITY_NAME)
                .condition("jsonSchemaId", id).useCache(true).one()
            if (schema?.schemaText) return schema.schemaText
        }
        
        // 2. Try DB by Name
        def nameKey = (name ?: id)?.toString()
        if (!nameKey) return null
        
        def schema = ec.entity.find(JSON_SCHEMA_ENTITY_NAME)
            .condition("schemaName", nameKey).useCache(true).one()
        if (schema?.schemaText) return schema.schemaText

        // 3. Fallback to data-manager and legacy runtime schema files still referenced by seeded mappings.
        String safeName = cleanFileName(nameKey)
        if (!safeName) return null

        String dataManagerSchemaText = loadSchemaTextFromLocation(ec, DataManagerSupport.resolveSchemaLocation(ec, safeName))
        if (dataManagerSchemaText) return dataManagerSchemaText

        String legacyDataManagerText = loadSchemaTextFromLocation(ec,
                DataManagerSupport.childLocation(DataManagerSupport.childLocation(DataManagerSupport.resolveDataManagerLocation(ec), DataManagerSupport.SCHEMA_PATH), safeName))
        if (legacyDataManagerText) return legacyDataManagerText

        def schemaRef = ec.resource.getLocationReference("runtime://schemas/json/${safeName}")
        if (schemaRef == null) return null
        if (schemaRef.supportsExists() && !schemaRef.getExists()) return null

        String schemaText = schemaRef.getText()
        return schemaText?.trim() ? schemaText : null
    }

    static String persistSchemaText(ExecutionContext ec, Object rawSchemaName, Object rawSchemaText) {
        String schemaName = ((rawSchemaName)?.toString()?.trim())
        String schemaText = rawSchemaText?.toString()
        if (!schemaName || !((schemaText)?.toString()?.trim())) return null

        String location = DataManagerSupport.resolveSchemaLocation(ec, schemaName)
        DataManagerSupport.writeText(ec, location, schemaText)
        return location
    }

    protected static String loadSchemaTextFromLocation(ExecutionContext ec, String location) {
        if (!location) return null
        def schemaRef = ec.resource.getLocationReference(location)
        if (schemaRef == null) return null
        if (schemaRef.supportsExists() && !schemaRef.getExists()) return null
        String schemaText = schemaRef.getText()
        return schemaText?.trim() ? schemaText : null
    }

    /**
     * Resolves a location string to an absolute file path.
     */
    static String resolveFilePath(ExecutionContext ec, String location) {
        if (!location) return null
        def rr = ec.resource.getLocationReference(location)
        if (rr != null && rr.supportsUrl()) {
            def url = rr.getUrl()
            if (url != null) {
                if ("file".equalsIgnoreCase(url.protocol)) {
                    try {
                        return new File(url.toURI()).getAbsolutePath()
                    } catch (Exception e) {
                        return url.getPath()
                    }
                }
                return url.toString()
            }
        }
        return location
    }

    static boolean ensureJsonSchemaTable(def ec) {
        String groupName = ec?.entity?.getEntityGroupName(JSON_SCHEMA_ENTITY_NAME) ?: "default"
        def datasourceFactory = ec?.entity?.getDatasourceFactory(groupName)
        datasourceFactory?.checkAndAddTable(JSON_SCHEMA_ENTITY_NAME)
        return true
    }

    static def findSystemEnum(def ec, Object rawSystemId, boolean useCache = true) {
        String normalized = ((rawSystemId)?.toString()?.trim())
        if (!normalized) return null

        return ec?.entity?.find("moqui.basic.Enumeration")
                ?.condition("enumTypeId", SYSTEM_ENUM_TYPE_ID)
                ?.condition("enumId", normalized)
                ?.useCache(useCache)
                ?.one()
    }

    static String resolveSystemLabel(def ec, Object rawSystemId, String fallback = null, boolean useCache = true) {
        String normalized = ((rawSystemId)?.toString()?.trim())
        if (!normalized) return fallback

        def systemEnum = findSystemEnum(ec, normalized, useCache)
        if (systemEnum == null) return fallback ?: normalized
        return FacadeSupport.enumLabel(systemEnum)
    }

    static boolean deleteSchemaRecord(def ec, def schema) {
        if (schema == null) {
            ec?.message?.addError("Schema not found in database to delete")
            return false
        }

        try {
            String schemaName = ((schema.schemaName)?.toString()?.trim())
            schema.delete()
            ec?.message?.addMessage("Deleted schema: ${schemaName}")
            return true
        } catch (Exception e) {
            ec?.message?.addError("Error deleting schema: ${e.message}")
            return false
        }
    }

    static String validateJsonText(Object rawJsonText, String fieldName = "jsonText") {
        String normalized = ((rawJsonText)?.toString()?.trim())
        if (!normalized) return "${fieldName} is required"

        try {
            mapper.readTree(normalized)
            return null
        } catch (Exception e) {
            return "${fieldName} is invalid JSON: ${e.message}"
        }
    }

    static Map<String, Object> readUploadedText(def fileItem, String fieldLabel = "schema file") {
        if (fileItem == null) {
            return [fileName: null, text: null, error: "Uploaded ${fieldLabel} is empty"]
        }

        long size = 0L
        try {
            size = (fileItem.getSize() ?: 0L) as long
        } catch (Exception ignored) {
            size = 0L
        }

        if (size <= 0L) {
            return [fileName: fileItem?.getName()?.toString()?.trim(), text: null, error: "Uploaded ${fieldLabel} is empty"]
        }

        String text = fileItem.getString("UTF-8")
        if (!((text)?.toString()?.trim())) {
            return [fileName: fileItem?.getName()?.toString()?.trim(), text: null, error: "Uploaded ${fieldLabel} is empty"]
        }

        return [
                fileName : fileItem?.getName()?.toString()?.trim(),
                text     : text,
                error    : null
        ]
    }

    static def resolveSchemaRecord(def ec, Object rawJsonSchemaId, Object rawSchemaName = null,
            Object rawFilename = null, boolean useCache = false) {
        String jsonSchemaId = ((rawJsonSchemaId)?.toString()?.trim())
        String schemaName = ((rawSchemaName)?.toString()?.trim())
        String filename = ((rawFilename)?.toString()?.trim())

        if (jsonSchemaId) {
            def schema = ec?.entity?.find(JSON_SCHEMA_ENTITY_NAME)
                    ?.condition("jsonSchemaId", jsonSchemaId)
                    ?.useCache(useCache)
                    ?.one()
            if (schema != null) return schema
        }

        String lookupName = schemaName ?: filename
        if (!lookupName) return null

        return ec?.entity?.find(JSON_SCHEMA_ENTITY_NAME)
                ?.condition("schemaName", lookupName)
                ?.useCache(useCache)
                ?.one()
    }

    /**
     * Generates a unique schema name by appending (1), (2), etc. if the name already exists.
     * @param ec ExecutionContext
     * @param baseName The desired name
     * @param overwrite If true, returns baseName as is (assuming caller handles overwrite)
     * @return Unique schema name
     */
    static String generateUniqueSchemaName(ExecutionContext ec, String baseName, boolean overwrite) {
        if (!baseName) return null
        if (overwrite) return baseName

        String nameToSave = baseName
        def existingSchema = ec.entity.find(JSON_SCHEMA_ENTITY_NAME)
            .condition("schemaName", nameToSave)
            .one()
        if (existingSchema == null) return nameToSave

        int suffix = 1
        while (existingSchema != null && suffix <= JsonSchemaConstants.UNIQUE_NAME_ATTEMPT_LIMIT) {
            nameToSave = "${baseName} (${suffix})"
            existingSchema = ec.entity.find(JSON_SCHEMA_ENTITY_NAME)
                .condition("schemaName", nameToSave)
                .one()
            suffix++
        }
        if (existingSchema != null) {
            nameToSave = "${baseName} (${System.currentTimeMillis()})"
        }
        return nameToSave
    }
}
