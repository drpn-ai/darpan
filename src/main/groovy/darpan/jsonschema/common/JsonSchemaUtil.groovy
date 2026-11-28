package jsonschema.common

import com.fasterxml.jackson.databind.ObjectMapper
import darpan.facade.common.DataManagerSupport
import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import org.moqui.context.ExecutionContext

class JsonSchemaUtil {
    private static final ObjectMapper mapper = new ObjectMapper()
    private static final String JSON_SCHEMA_ENTITY_NAME = JsonSchemaConstants.JSON_SCHEMA_ENTITY_NAME
    private static final String SYSTEM_ENUM_TYPE_ID = JsonSchemaConstants.SYSTEM_ENUM_TYPE_ID

    /**
     * True when every field this schema flattens to is a top-level scalar column.
     *
     * A CSV side can only carry flat columns. Offering it a nested schema produces dotted paths
     * like customer.email, which persist happily and then match nothing at run time — so the CSV
     * schema picker filters on this. Unparseable or empty text is treated as NOT flat: fail
     * closed rather than offer a schema nobody can vouch for.
     */
    static boolean isFlatFieldList(String schemaText) {
        if (schemaText == null || schemaText.trim().isEmpty()) return false

        try {
            Map<String, Object> schemaMap = (Map<String, Object>) mapper.readValue(schemaText, Map.class)
            List<Map<String, Object>> fieldList = SchemaFlattener.flatten(schemaMap)
            if (fieldList.isEmpty()) return false

            return fieldList.every { Map<String, Object> field ->
                String fieldPath = (String) field.fieldPath
                fieldPath != null && !fieldPath.contains('.') && !fieldPath.contains('[') &&
                        field.type != 'object' && field.type != 'array'
            }
        } catch (Exception ignored) {
            return false
        }
    }

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
     * Loads schema text from saved schemas first, then falls back to schema files.
     *
     * <p>DAR-BE-281: schema names are unique per tenant, not globally, and runs call this with authz
     * disabled (no entity filter). So a saved schema counts only when it is the active tenant's own or
     * shared (no tenant); the tenant's own row wins. A file is read from the tenant's own folder or from
     * the product's runtime schemas, never from the shared data-manager folder (whose files any tenant
     * may have written).</p>
     * @param ec ExecutionContext
     * @param id Optional jsonSchemaId
     * @param name Optional schemaName (or filename)
     * @return Schema JSON String or null if not found
     */
    static String loadSchemaText(ExecutionContext ec, Object id, Object name) {
        String tenant = TenantAccessSupport.currentActiveTenantUserGroupId(ec)

        // 1. Try DB by ID
        if (id) {
            def schema = ec.entity.find(JSON_SCHEMA_ENTITY_NAME)
                .condition("jsonSchemaId", id).useCache(true).one()
            if (schema?.schemaText && isOwnOrShared(schema, tenant)) return schema.schemaText
        }
        
        // 2. Try DB by Name
        def nameKey = (name ?: id)?.toString()
        if (!nameKey) return null
        
        List namedSchemas = (ec.entity.find(JSON_SCHEMA_ENTITY_NAME)
            .condition("schemaName", nameKey).useCache(true).list() ?: []) as List
        def schema = namedSchemas.find { it.schemaText && tenant && it.companyUserGroupId == tenant } ?:
                namedSchemas.find { it.schemaText && !it.companyUserGroupId }
        if (schema?.schemaText) return schema.schemaText

        // 3. Fallback to the tenant's own schema file, then the runtime schema files shipped with the product.
        // The shared data-manager folder (schemas/json/<name>) is never read: any tenant's save wrote there,
        // and a delete left the file behind, so its owner can't be known from the file.
        String safeName = cleanFileName(nameKey)
        if (!safeName) return null

        String tenantSchemaText = loadSchemaTextFromLocation(ec, DataManagerSupport.resolveTenantSchemaLocation(ec, tenant, safeName))
        if (tenantSchemaText) return tenantSchemaText

        // Shipped with the product (runtime://), so no tenant ever wrote it.
        def schemaRef = ec.resource.getLocationReference("runtime://schemas/json/${safeName}")
        if (schemaRef == null) return null
        if (schemaRef.supportsExists() && !schemaRef.getExists()) return null

        String schemaText = schemaRef.getText()
        return schemaText?.trim() ? schemaText : null
    }

    protected static boolean isOwnOrShared(def schema, String tenant) {
        String owner = schema?.companyUserGroupId?.toString()
        return !owner || (tenant && owner == tenant)
    }

    /** DAR-BE-281: writes into the active tenant's own schema folder; with no active tenant nothing is written. */
    static String persistSchemaText(ExecutionContext ec, Object rawSchemaName, Object rawSchemaText) {
        String schemaName = ((rawSchemaName)?.toString()?.trim())
        String schemaText = rawSchemaText?.toString()
        if (!schemaName || !((schemaText)?.toString()?.trim())) return null

        String location = DataManagerSupport.resolveTenantSchemaLocation(ec,
                TenantAccessSupport.currentActiveTenantUserGroupId(ec), schemaName)
        if (!location) return null
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

    /**
     * DAR-OPS-029 S31. The active tenant's schema by id, else by name ((companyUserGroupId, schemaName) is unique), or null
     * with "Schema not found" on ec.message: another tenant's schema answers exactly as a missing one. Both reads carry the
     * tenant themselves, so this holds with authz off too, where a bare find by schemaName can match several tenants' rows.
     * flatten#JsonSchema and delete#JsonSchema each repeated this as XML.
     */
    static def findTenantSchema(def ec, Object rawJsonSchemaId, Object rawSchemaName) {
        String jsonSchemaId = ((rawJsonSchemaId)?.toString()?.trim())
        String schemaName = ((rawSchemaName)?.toString()?.trim())
        // A given id decides alone: one that is not the active tenant's is refused, never swapped for a schema found by
        // name (save#RefinedSchema passes the caller's id on to the write; a fallback checked one schema and wrote another).
        def schema = jsonSchemaId ? TenantScopedFinder.findTenantScopedByIdQuiet(ec, JSON_SCHEMA_ENTITY_NAME, "jsonSchemaId", jsonSchemaId) :
                (schemaName ? tenantSchemaByName(ec, schemaName) : null)
        if (schema == null) ec.message.addError("Schema not found")
        return schema
    }

    /** The active tenant's schema of this name ((companyUserGroupId, schemaName) is unique), or null. */
    private static def tenantSchemaByName(def ec, String schemaName) {
        return TenantScopedFinder.findTenantScoped(ec, JSON_SCHEMA_ENTITY_NAME).condition("schemaName", schemaName).useCache(false).one()
    }

    static def resolveSchemaRecord(def ec, Object rawJsonSchemaId, Object rawSchemaName = null,
            Object rawFilename = null, boolean useCache = false) {
        String jsonSchemaId = ((rawJsonSchemaId)?.toString()?.trim())
        String schemaName = ((rawSchemaName)?.toString()?.trim())
        String filename = ((rawFilename)?.toString()?.trim())

        // DAR-OPS-029 S31: the active tenant's schema only, read with an explicit tenant condition. A bare find by
        // schemaName matched another tenant's same-named schema (or threw on several) wherever the entity filter was off.
        if (jsonSchemaId) return TenantScopedFinder.findTenantScopedByIdQuiet(ec, JSON_SCHEMA_ENTITY_NAME, "jsonSchemaId", jsonSchemaId)
        String lookupName = schemaName ?: filename
        return lookupName ? tenantSchemaByName(ec, lookupName) : null
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

        // DAR-OPS-029 S31: names are unique per tenant (JSON_SCHEMA_TENANT_NAME), so only the active tenant's schemas can
        // clash; counted, so another tenant's same name never matches (a bare find matched it with authz off, or threw).
        Closure<Boolean> taken = { String candidate ->
            TenantScopedFinder.findTenantScoped(ec, JSON_SCHEMA_ENTITY_NAME).condition("schemaName", candidate).useCache(false).count() > 0
        }
        String nameToSave = baseName
        boolean existing = taken(nameToSave)
        if (!existing) return nameToSave

        int suffix = 1
        while (existing && suffix <= JsonSchemaConstants.UNIQUE_NAME_ATTEMPT_LIMIT) {
            nameToSave = "${baseName} (${suffix})"
            existing = taken(nameToSave)
            suffix++
        }
        if (existing) {
            nameToSave = "${baseName} (${System.currentTimeMillis()})"
        }
        return nameToSave
    }
}
