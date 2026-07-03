
import org.moqui.context.ExecutionContext

ExecutionContext ec = context.ec

def firstValue = { Object value ->
    if (value instanceof List) return value ? value[0] : null
    return value
}

def firstText = { Object value ->
    Object raw = firstValue(value)
    if (raw == null) return null
    String text = raw.toString().trim()
    return text ? text : null
}

List<Map> fields = []
Map params = ec.web.parameters ?: [:]
boolean usedSessionFallback = false

String requestSchemaName = firstText(params.schemaName)
if (requestSchemaName) ec.context.put("schemaName", requestSchemaName)

String requestSchemaId = firstText(params.jsonSchemaId)
if (requestSchemaId) ec.context.put("jsonSchemaId", requestSchemaId)

if (!context.schemaName && params.schemaName_0) {
    String rowSchemaName = firstText(params.schemaName_0)
    if (rowSchemaName) ec.context.put("schemaName", rowSchemaName)
}

if (!context.jsonSchemaId && params.jsonSchemaId_0) {
    String rowSchemaId = firstText(params.jsonSchemaId_0)
    if (rowSchemaId) ec.context.put("jsonSchemaId", rowSchemaId)
}

int maxIndex = 1000
for (int i = 0; i < maxIndex; i++) {
    String pKey = "fieldPath_${i}"
    if (!params.containsKey(pKey)) break

    String fieldPath = firstText(params[pKey])
    if (!fieldPath) continue

    fields.add([
        fieldPath: fieldPath,
        type: (firstText(params["type_${i}"]) ?: "string"),
        required: (firstText(params["required_${i}"]) ?: "false")
    ])
}

// Fallback: If no suffixed fields found, check for list/array parameters (fieldPath, type, required)
if (fields.isEmpty() && params.fieldPath) {
    def fPaths = params.fieldPath instanceof List ? params.fieldPath : [params.fieldPath]
    def types = params.type instanceof List ? params.type : [params.type]
    def reqs = params.required instanceof List ? params.required : [params.required]

    for (int i = 0; i < fPaths.size(); i++) {
        String fieldPath = firstText(fPaths[i])
        if (!fieldPath) continue

        fields.add([
            fieldPath: fieldPath,
            type: (firstText(i < types.size() ? types[i] : null) ?: "string"),
            required: (firstText(i < reqs.size() ? reqs[i] : null) ?: "false")
        ])
    }
}

// Final Fallback: Use wizardResultList from session if available
// This protects against cases where form parameters aren't submitted but the session state is valid (e.g. after Add/Remove)
if (fields.isEmpty()) {
    def sessionList = ec.web.session.getAttribute("wizardResultList")
    if (sessionList instanceof Collection && !sessionList.isEmpty()) {
        fields = sessionList.collect { row ->
            if (!(row instanceof Map)) return null
            String fieldPath = firstText(row.fieldPath)
            if (!fieldPath) return null
            return [
                fieldPath: fieldPath,
                type: (firstText(row.type) ?: "string"),
                required: (firstText(row.required) ?: "false")
            ]
        }.findAll { it != null }
        usedSessionFallback = true
    }
}

ec.context.put("fieldList", fields)

if (fields.isEmpty()) {
    ec.logger.warn("JsonSchema editor save prepared with no fields (schemaName: ${context.schemaName}, jsonSchemaId: ${context.jsonSchemaId})")
} else {
    ec.logger.info("JsonSchema editor save prepared (schemaName: ${context.schemaName}, jsonSchemaId: ${context.jsonSchemaId}, fieldCount: ${fields.size()}, source: ${usedSessionFallback ? 'session' : 'request'})")
}
