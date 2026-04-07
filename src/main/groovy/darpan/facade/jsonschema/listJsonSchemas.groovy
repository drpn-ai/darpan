import darpan.facade.common.FacadeSupport
import darpan.facade.common.PilotAccessSupport

int page = Math.max(0, FacadeSupport.normalizeInt(pageIndex, 0))
int size = Math.max(1, Math.min(200, FacadeSupport.normalizeInt(pageSize, 20)))
String search = FacadeSupport.normalize(query)?.toLowerCase()
String systemEnumTypeId = "DarpanSystemSource"

String jsonSchemaEntityName = "darpan.reconciliation.JsonSchema"
String jsonSchemaGroupName = ec.entity.getEntityGroupName(jsonSchemaEntityName) ?: "default"
def jsonSchemaDatasourceFactory = ec.entity.getDatasourceFactory(jsonSchemaGroupName)
jsonSchemaDatasourceFactory?.checkAndAddTable(jsonSchemaEntityName)

def findSystemEnum = { Object rawSystemId, boolean useCache = true ->
    String normalized = FacadeSupport.normalize(rawSystemId)
    if (!normalized) return null

    return ec.entity.find("moqui.basic.Enumeration")
        .condition("enumTypeId", systemEnumTypeId)
        .condition("enumId", normalized)
        .useCache(useCache)
        .one()
}

def resolveSystemLabel = { Object rawSystemId, String fallback = null, boolean useCache = true ->
    String normalized = FacadeSupport.normalize(rawSystemId)
    if (!normalized) return fallback

    def systemEnum = findSystemEnum(rawSystemId, useCache)
    if (systemEnum == null) return fallback ?: normalized

    return FacadeSupport.enumLabel(systemEnum)
}

List<Map> rows = []
def finder = ec.entity.find("darpan.reconciliation.JsonSchema")
finder.condition("statusId", "Active")
PilotAccessSupport.applyOwnerFilter(finder, ec)
(finder.orderBy("-lastUpdatedStamp")
    .useCache(false)
    .list() ?: []).each { schema ->
    String systemEnumId = FacadeSupport.normalize(schema.systemEnumId)
    Map row = [
        jsonSchemaId: schema.jsonSchemaId,
        schemaName: schema.schemaName,
        description: schema.description,
        systemEnumId: systemEnumId,
        systemLabel: resolveSystemLabel(systemEnumId, null, true),
        statusId: schema.statusId,
        createdDate: schema.createdDate,
        lastUpdatedStamp: schema.lastUpdatedStamp,
    ]
    if (!search || [row.schemaName, row.description].any { it?.toString()?.toLowerCase()?.contains(search) }) {
        rows.add(row)
    }
}

int totalCount = rows.size()
int fromIndex = Math.min(page * size, totalCount)
int toIndex = Math.min(fromIndex + size, totalCount)
schemas = rows.subList(fromIndex, toIndex)

pagination = [
    pageIndex: page,
    pageSize: size,
    totalCount: totalCount,
    pageCount: Math.max(1, Math.ceil(totalCount / (double) size) as int)
]

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
