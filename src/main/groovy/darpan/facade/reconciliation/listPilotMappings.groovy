import darpan.facade.common.FacadeSupport
import darpan.facade.reconciliation.PilotMappingSupport

int page = Math.max(0, FacadeSupport.normalizeInt(pageIndex, 0))
int size = Math.max(1, Math.min(200, FacadeSupport.normalizeInt(pageSize, 20)))
String search = FacadeSupport.normalize(query)?.toLowerCase()

def findEnum = { String enumId ->
    if (!enumId) return null
    return ec.entity.find("moqui.basic.Enumeration")
            .condition("enumId", enumId)
            .useCache(true)
            .one()
}

List<Map> rows = []
(ec.entity.find("darpan.mapping.ReconciliationMapping")
        .orderBy("mappingName,reconciliationMappingId")
        .useCache(false)
        .list() ?: []).each { mapping ->
    List mappingMembers = ec.entity.find("darpan.mapping.ReconciliationMappingMember")
            .condition("reconciliationMappingId", mapping.reconciliationMappingId)
            .useCache(false)
            .list() ?: []
    List<String> pilotReadinessIssues = PilotMappingSupport.collectPilotReadinessIssues(ec, mappingMembers)
    if (pilotReadinessIssues.isEmpty()) {
        List<Map> systemOptions = mappingMembers.collect { member ->
            def systemEnum = findEnum(member.systemEnumId as String)
            def fileTypeEnum = findEnum(member.fileTypeEnumId as String)
            [
                    enumId           : member.systemEnumId,
                    enumCode         : systemEnum?.enumCode,
                    description      : systemEnum?.description,
                    label            : FacadeSupport.enumLabel(systemEnum ?: [enumId: member.systemEnumId]),
                    sequenceNum      : systemEnum?.sequenceNum ?: Integer.MAX_VALUE,
                    fileTypeEnumId   : member.fileTypeEnumId,
                    fileTypeLabel    : fileTypeEnum ? FacadeSupport.enumLabel(fileTypeEnum) : null,
                    idFieldExpression: member.idFieldExpression ?: member.systemFieldName,
                    schemaFileName   : member.schemaFileName,
            ]
        }.sort { Map left, Map right ->
            (left.sequenceNum <=> right.sequenceNum) ?:
                    ((left.label ?: "") <=> (right.label ?: "")) ?:
                    ((left.enumId ?: "") <=> (right.enumId ?: ""))
        }

        List<String> systemIds = systemOptions.collect { it.enumId as String }.findAll { it }.unique()
        Map row = [
                reconciliationMappingId : mapping.reconciliationMappingId,
                mappingName             : mapping.mappingName,
                description             : mapping.description,
                requiresSystemSelection : systemIds.size() != 2,
                defaultFile1SystemEnumId: systemIds.size() >= 2 ? systemIds[0] : null,
                defaultFile2SystemEnumId: systemIds.size() >= 2 ? systemIds[1] : null,
                systemOptions           : systemOptions.collect { Map option -> option.findAll { String key, Object value -> key != "sequenceNum" } },
        ]

        boolean matches = !search || [
                row.reconciliationMappingId,
                row.mappingName,
                row.description,
                *(systemOptions.collect { it.label }),
                *(systemOptions.collect { it.enumCode })
        ].any { it?.toString()?.toLowerCase()?.contains(search) }

        if (matches) rows.add(row)
    }
}

int totalCount = rows.size()
int fromIndex = Math.min(page * size, totalCount)
int toIndex = Math.min(fromIndex + size, totalCount)
mappings = rows.subList(fromIndex, toIndex)
pagination = [
        pageIndex : page,
        pageSize  : size,
        totalCount: totalCount,
        pageCount : Math.max(1, Math.ceil(totalCount / (double) size) as int)
]

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
