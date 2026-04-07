import darpan.facade.common.FacadeSupport
import darpan.facade.common.PilotAccessSupport
import darpan.facade.reconciliation.PilotMappingSupport

String mappingNameValue = FacadeSupport.normalize(mappingName)
String schema1IdValue = FacadeSupport.normalize(schema1Id)
String schema2IdValue = FacadeSupport.normalize(schema2Id)
String schema1FieldPathValue = PilotMappingSupport.normalizePilotJsonIdExpression(schema1FieldPath)
String schema2FieldPathValue = PilotMappingSupport.normalizePilotJsonIdExpression(schema2FieldPath)

String jsonSchemaEntityName = "darpan.reconciliation.JsonSchema"
String mappingEntityName = "darpan.mapping.ReconciliationMapping"
String mappingMemberEntityName = "darpan.mapping.ReconciliationMappingMember"

def ensureTable = { String entityName ->
    String groupName = ec.entity.getEntityGroupName(entityName) ?: "default"
    def datasourceFactory = ec.entity.getDatasourceFactory(groupName)
    datasourceFactory?.checkAndAddTable(entityName)
}

[jsonSchemaEntityName, mappingEntityName, mappingMemberEntityName].each(ensureTable)

def buildMappingId = { String rawName ->
    String mappingIdBase = rawName?.replaceAll('[^A-Za-z0-9]', '')
    if (!mappingIdBase) mappingIdBase = "Mapping"
    if (mappingIdBase.length() > 38) mappingIdBase = mappingIdBase.substring(0, 38)

    String mappingIdValue = mappingIdBase
    def existingMapping = ec.entity.find(mappingEntityName)
        .condition("reconciliationMappingId", mappingIdValue)
        .useCache(false)
        .one()
    if (existingMapping != null) {
        String idSuffix = ec.l10n.format(ec.user.nowTimestamp, 'yyMMddHHmmss')
        int baseMax = 38 - idSuffix.length() - 1
        String trimmedBase = mappingIdBase.length() > baseMax ? mappingIdBase.substring(0, baseMax) : mappingIdBase
        mappingIdValue = "${trimmedBase}-${idSuffix}"
    }
    return mappingIdValue
}

if (!mappingNameValue) ec.message.addError("mappingName is required")
if (!schema1IdValue) ec.message.addError("schema1Id is required")
if (!schema2IdValue) ec.message.addError("schema2Id is required")
if (!schema1FieldPathValue) ec.message.addError("schema1FieldPath is required")
if (!schema2FieldPathValue) ec.message.addError("schema2FieldPath is required")
if (schema1IdValue && schema2IdValue && schema1IdValue == schema2IdValue) {
    ec.message.addError("schema1Id and schema2Id must be different")
}

def schema1 = null
def schema2 = null
if (!ec.message.hasError()) {
    schema1 = ec.entity.find(jsonSchemaEntityName)
        .condition("jsonSchemaId", schema1IdValue)
        .useCache(false)
        .one()
    schema2 = ec.entity.find(jsonSchemaEntityName)
        .condition("jsonSchemaId", schema2IdValue)
        .useCache(false)
        .one()

    PilotAccessSupport.requireOwnedRecordAccess(ec, schema1,
        "Schema not found for schema1Id '${schema1IdValue}'",
        "Schema 1 is not available in your customer scope.")
    PilotAccessSupport.requireOwnedRecordAccess(ec, schema2,
        "Schema not found for schema2Id '${schema2IdValue}'",
        "Schema 2 is not available in your customer scope.")
}

if (!ec.message.hasError()) {
    String schema1SystemEnumId = FacadeSupport.normalize(schema1.systemEnumId)
    String schema2SystemEnumId = FacadeSupport.normalize(schema2.systemEnumId)

    if (!schema1SystemEnumId) ec.message.addError("Schema '${schema1.schemaName}' is missing systemEnumId")
    if (!schema2SystemEnumId) ec.message.addError("Schema '${schema2.schemaName}' is missing systemEnumId")
    if (schema1SystemEnumId && schema2SystemEnumId && schema1SystemEnumId == schema2SystemEnumId) {
        ec.message.addError("Selected schemas must use different systems")
    }
}

if (!ec.message.hasError()) {
    def now = ec.user.nowTimestamp
    String mappingIdValue = buildMappingId(mappingNameValue)

    def mappingValue = ec.entity.makeValue(mappingEntityName)
    mappingValue.reconciliationMappingId = mappingIdValue
    mappingValue.mappingName = mappingNameValue
    mappingValue.createdDate = now
    mappingValue.lastUpdatedDate = now
    mappingValue.create()

    [
        [
            mappingMemberId: "${mappingIdValue}-1",
            systemEnumId: schema1.systemEnumId,
            schemaName: schema1.schemaName,
            fieldPath: schema1FieldPathValue,
        ],
        [
            mappingMemberId: "${mappingIdValue}-2",
            systemEnumId: schema2.systemEnumId,
            schemaName: schema2.schemaName,
            fieldPath: schema2FieldPathValue,
        ],
    ].each { Map memberConfig ->
        def mappingMember = ec.entity.makeValue(mappingMemberEntityName)
        mappingMember.mappingMemberId = memberConfig.mappingMemberId
        mappingMember.reconciliationMappingId = mappingIdValue
        mappingMember.systemEnumId = memberConfig.systemEnumId
        mappingMember.fileTypeEnumId = "DftJson"
        mappingMember.idFieldExpression = memberConfig.fieldPath
        mappingMember.systemFieldName = memberConfig.fieldPath
        mappingMember.schemaFileName = memberConfig.schemaName
        mappingMember.createdDate = now
        mappingMember.create()
    }

    savedMapping = [
        reconciliationMappingId: mappingIdValue,
        mappingName: mappingNameValue,
        file1SystemEnumId: FacadeSupport.normalize(schema1.systemEnumId),
        file2SystemEnumId: FacadeSupport.normalize(schema2.systemEnumId),
        file1SchemaName: schema1.schemaName,
        file2SchemaName: schema2.schemaName,
        file1FieldPath: schema1FieldPathValue,
        file2FieldPath: schema2FieldPathValue,
    ]
    ec.message.addMessage("Created reconciliation flow ${mappingNameValue}.")
}

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
