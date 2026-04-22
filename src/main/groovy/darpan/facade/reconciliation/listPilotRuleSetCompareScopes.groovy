import darpan.facade.common.FacadeSupport

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

List<Map<String, Object>> rows = []
(ec.entity.find("darpan.rule.RuleSetCompareScope")
        .orderBy("ruleSetId,compareScopeId")
        .useCache(false)
        .list() ?: []).each { compareScope ->
    def ruleSet = ec.entity.find("darpan.rule.RuleSet")
            .condition("ruleSetId", compareScope.ruleSetId)
            .useCache(false)
            .one()
    if (ruleSet == null) return

    List sources = ec.entity.find("darpan.rule.RuleSetCompareSource")
            .condition("compareScopeId", compareScope.compareScopeId)
            .useCache(false)
            .list() ?: []
    Map sourceBySide = [:]
    sources.each { source ->
        sourceBySide[FacadeSupport.normalize(source.fileSide)] = source
    }

    def file1Source = sourceBySide["FILE_1"]
    def file2Source = sourceBySide["FILE_2"]
    if (file1Source == null || file2Source == null) return

    def file1SystemEnum = findEnum(file1Source.systemEnumId as String)
    def file2SystemEnum = findEnum(file2Source.systemEnumId as String)
    def file1FileTypeEnum = findEnum(file1Source.fileTypeEnumId as String)
    def file2FileTypeEnum = findEnum(file2Source.fileTypeEnumId as String)

    Map<String, Object> row = [
            ruleSetId               : ruleSet.ruleSetId,
            ruleSetName             : FacadeSupport.normalize(ruleSet.ruleSetName) ?: FacadeSupport.normalize(ruleSet.ruleSetId),
            ruleSetDescription      : FacadeSupport.normalize(ruleSet.description),
            compareScopeId          : compareScope.compareScopeId,
            compareScopeDescription : FacadeSupport.normalize(compareScope.description),
            objectType              : FacadeSupport.normalize(compareScope.objectType),
            file1SystemEnumId       : FacadeSupport.normalize(file1Source.systemEnumId),
            file1SystemLabel        : file1SystemEnum ? FacadeSupport.enumLabel(file1SystemEnum) : FacadeSupport.normalize(file1Source.systemEnumId),
            file1FileTypeEnumId     : FacadeSupport.normalize(file1Source.fileTypeEnumId),
            file1FileTypeLabel      : file1FileTypeEnum ? FacadeSupport.enumLabel(file1FileTypeEnum) : null,
            file1PrimaryIdExpression: FacadeSupport.normalize(file1Source.primaryIdExpression),
            file1SchemaFileName     : FacadeSupport.normalize(file1Source.schemaFileName),
            file2SystemEnumId       : FacadeSupport.normalize(file2Source.systemEnumId),
            file2SystemLabel        : file2SystemEnum ? FacadeSupport.enumLabel(file2SystemEnum) : FacadeSupport.normalize(file2Source.systemEnumId),
            file2FileTypeEnumId     : FacadeSupport.normalize(file2Source.fileTypeEnumId),
            file2FileTypeLabel      : file2FileTypeEnum ? FacadeSupport.enumLabel(file2FileTypeEnum) : null,
            file2PrimaryIdExpression: FacadeSupport.normalize(file2Source.primaryIdExpression),
            file2SchemaFileName     : FacadeSupport.normalize(file2Source.schemaFileName),
    ]

    boolean matches = !search || [
            row.ruleSetId,
            row.ruleSetName,
            row.ruleSetDescription,
            row.compareScopeId,
            row.compareScopeDescription,
            row.objectType,
            row.file1SystemEnumId,
            row.file1SystemLabel,
            row.file1FileTypeLabel,
            row.file1PrimaryIdExpression,
            row.file1SchemaFileName,
            row.file2SystemEnumId,
            row.file2SystemLabel,
            row.file2FileTypeLabel,
            row.file2PrimaryIdExpression,
            row.file2SchemaFileName,
    ].any { value ->
        value?.toString()?.toLowerCase()?.contains(search)
    }

    if (matches) rows.add(row)
}

int totalCount = rows.size()
int fromIndex = Math.min(page * size, totalCount)
int toIndex = Math.min(fromIndex + size, totalCount)
compareScopes = rows.subList(fromIndex, toIndex)
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
