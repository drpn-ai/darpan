package darpan.facade.search

import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.reconciliation.ReconciliationOutputSupport
import darpan.facade.reconciliation.ReconciliationSavedRunSupport
import jsonschema.common.JsonSchemaUtil

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.sql.Timestamp

class NavigationSearchSupport {
    static final String TYPE_SCHEMA = "schema"
    static final String TYPE_SFTP_SERVER = "sftp-server"
    static final String TYPE_NETSUITE_AUTH = "netsuite-auth"
    static final String TYPE_NETSUITE_ENDPOINT = "netsuite-endpoint"
    static final String TYPE_SAVED_RUN = "saved-run"
    static final String TYPE_RUN_RESULT = "run-result"
    static final List<String> DEFAULT_TYPES = [
            TYPE_RUN_RESULT,
            TYPE_SAVED_RUN,
            TYPE_SFTP_SERVER,
            TYPE_NETSUITE_AUTH,
            TYPE_NETSUITE_ENDPOINT,
            TYPE_SCHEMA,
    ]
    static final int MAX_PAGE_SIZE = 50
    static final Set<String> INTENT_TOKENS = [
            "ask", "darpan", "open", "show", "view", "go", "to", "find", "search", "for", "the",
            "a", "an", "edit", "editing", "manage", "launch", "start", "data", "record", "records",
            "sftp", "server", "servers", "file", "files", "netsuite", "net", "suite", "auth",
            "authentication", "endpoint", "endpoints", "restlet", "config", "configuration",
            "schema", "schemas", "run", "runs", "result", "results", "diff", "history", "saved"
    ] as Set<String>

    static Map<String, Object> search(def ec, Object rawQuery, Object rawTypes, Object rawPageIndex, Object rawPageSize) {
        int page = Math.max(0, FacadeSupport.normalizeInt(rawPageIndex, 0))
        int size = Math.max(1, Math.min(MAX_PAGE_SIZE, FacadeSupport.normalizeInt(rawPageSize, 20)))
        String query = FacadeSupport.normalize(rawQuery)
        List<String> queryTokens = tokenize(query)

        Map<String, Object> emptyResult = [
                results   : [],
                pagination: pagination(page, size, 0),
        ]
        if (!query || queryTokens.isEmpty()) return emptyResult
        if (!TenantAccessSupport.currentActiveTenantUserGroupId(ec)) return emptyResult

        Set<String> selectedTypes = normalizeTypes(rawTypes)
        List<Map<String, Object>> candidates = []

        if (selectedTypes.contains(TYPE_SFTP_SERVER)) candidates.addAll(collectSftpServerTargets(ec))
        if (selectedTypes.contains(TYPE_NETSUITE_AUTH)) candidates.addAll(collectNetSuiteAuthTargets(ec))
        if (selectedTypes.contains(TYPE_NETSUITE_ENDPOINT)) candidates.addAll(collectNetSuiteEndpointTargets(ec))
        if (selectedTypes.contains(TYPE_SCHEMA)) candidates.addAll(collectSchemaTargets(ec))
        if (selectedTypes.contains(TYPE_SAVED_RUN)) candidates.addAll(collectSavedRunTargets(ec))
        if (selectedTypes.contains(TYPE_RUN_RESULT)) candidates.addAll(collectGeneratedOutputTargets(ec))

        List<Map<String, Object>> scoredResults = candidates.collectMany { Map<String, Object> candidate ->
            int score = scoreCandidate(candidate, query, queryTokens)
            if (score <= 0) return []
            Map<String, Object> result = new LinkedHashMap<>(candidate)
            result.score = score
            return [result]
        }.sort { Map<String, Object> left, Map<String, Object> right ->
            int scoreCompare = ((right.score ?: 0) as int) <=> ((left.score ?: 0) as int)
            if (scoreCompare != 0) return scoreCompare
            int typeCompare = typeOrder(left.type as String) <=> typeOrder(right.type as String)
            if (typeCompare != 0) return typeCompare
            String leftLabel = FacadeSupport.normalize(left.label) ?: ""
            String rightLabel = FacadeSupport.normalize(right.label) ?: ""
            int labelCompare = leftLabel <=> rightLabel
            if (labelCompare != 0) return labelCompare
            return (FacadeSupport.normalize(left.resultId) ?: "") <=> (FacadeSupport.normalize(right.resultId) ?: "")
        } as List<Map<String, Object>>

        int totalCount = scoredResults.size()
        int fromIndex = Math.min(page * size, totalCount)
        int toIndex = Math.min(fromIndex + size, totalCount)
        return [
                results   : scoredResults.subList(fromIndex, toIndex).collect { Map<String, Object> result ->
                    result.findAll { String key, Object value -> value != null && !key.startsWith("_") }
                },
                pagination: pagination(page, size, totalCount),
        ]
    }

    protected static List<Map<String, Object>> collectSftpServerTargets(def ec) {
        String activeTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (!activeTenantUserGroupId) return []

        List rows = ec.entity.find("darpan.reconciliation.SftpServer")
                .condition("companyUserGroupId", activeTenantUserGroupId)
                .orderBy("description,sftpServerId")
                .useCache(false)
                .list() ?: []

        return rows.collect { item ->
            String id = FacadeSupport.normalize(item.sftpServerId)
            String label = FacadeSupport.normalize(item.description) ?: id
            target(
                    "data:sftp-server:${id}",
                    TYPE_SFTP_SERVER,
                    "Edit SFTP: ${label}",
                    [item.host, item.username ? "as ${item.username}" : null].findAll { it }.join(" ") ?: "Open the SFTP server editor.",
                    "settings-sftp-edit",
                    "/settings/sftp/edit/${encodePathSegment(id)}",
                    [sftpServerId: id],
                    [:],
                    id,
                    "darpan.reconciliation.SftpServer",
                    [
                            label,
                            id,
                            item.description,
                            item.host,
                            item.username,
                            "sftp",
                            "sftp server",
                            "file server",
                            "edit sftp",
                    ],
                    [
                            id,
                            item.description,
                            item.host,
                            item.username,
                    ]
            )
        }
    }

    protected static List<Map<String, Object>> collectNetSuiteAuthTargets(def ec) {
        String activeTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (!activeTenantUserGroupId) return []

        List rows = ec.entity.find("darpan.reconciliation.NsAuthConfig")
                .condition("companyUserGroupId", activeTenantUserGroupId)
                .orderBy("description,nsAuthConfigId")
                .useCache(false)
                .list() ?: []

        return rows.collect { item ->
            String id = FacadeSupport.normalize(item.nsAuthConfigId)
            String label = FacadeSupport.normalize(item.description) ?: id
            String authType = FacadeSupport.normalize(item.authType) ?: "NONE"
            target(
                    "data:netsuite-auth:${id}",
                    TYPE_NETSUITE_AUTH,
                    "Edit NetSuite Auth: ${label}",
                    "${authType} auth${item.username ? " for ${item.username}" : ""}.",
                    "settings-netsuite-auth-edit",
                    "/settings/netsuite/auth/edit/${encodePathSegment(id)}",
                    [nsAuthConfigId: id],
                    [:],
                    id,
                    "darpan.reconciliation.NsAuthConfig",
                    [
                            label,
                            id,
                            item.description,
                            authType,
                            item.username,
                            "netsuite",
                            "net suite",
                            "netsuite auth",
                            "authentication",
                            "edit netsuite auth",
                    ],
                    [
                            id,
                            item.description,
                            authType,
                            item.username,
                    ]
            )
        }
    }

    protected static List<Map<String, Object>> collectNetSuiteEndpointTargets(def ec) {
        String activeTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (!activeTenantUserGroupId) return []

        Map<String, Map<String, Object>> authById = (ec.entity.find("darpan.reconciliation.NsAuthConfig")
                .condition("companyUserGroupId", activeTenantUserGroupId)
                .useCache(false)
                .list() ?: []).collectEntries { auth ->
            [(FacadeSupport.normalize(auth.nsAuthConfigId)): [
                    description: FacadeSupport.normalize(auth.description),
                    authType   : FacadeSupport.normalize(auth.authType) ?: "NONE",
            ]]
        } as Map<String, Map<String, Object>>

        List rows = ec.entity.find("darpan.reconciliation.NsRestletConfig")
                .condition("companyUserGroupId", activeTenantUserGroupId)
                .orderBy("description,nsRestletConfigId")
                .useCache(false)
                .list() ?: []

        return rows.collect { item ->
            String id = FacadeSupport.normalize(item.nsRestletConfigId)
            String authId = FacadeSupport.normalize(item.nsAuthConfigId)
            Map<String, Object> authMeta = authById[authId] ?: [:]
            String label = FacadeSupport.normalize(item.description) ?: id
            String method = FacadeSupport.normalize(item.httpMethod) ?: "POST"
            target(
                    "data:netsuite-endpoint:${id}",
                    TYPE_NETSUITE_ENDPOINT,
                    "Edit NetSuite Endpoint: ${label}",
                    "${method} ${FacadeSupport.normalize(item.endpointUrl) ?: "NetSuite endpoint"}",
                    "settings-netsuite-endpoints-edit",
                    "/settings/netsuite/endpoints/edit/${encodePathSegment(id)}",
                    [nsRestletConfigId: id],
                    [:],
                    id,
                    "darpan.reconciliation.NsRestletConfig",
                    [
                            label,
                            id,
                            item.description,
                            item.endpointUrl,
                            method,
                            authId,
                            authMeta.description,
                            authMeta.authType,
                            "netsuite",
                            "net suite",
                            "netsuite endpoint",
                            "restlet",
                            "edit netsuite endpoint",
                    ],
                    [
                            id,
                            item.description,
                            item.endpointUrl,
                            method,
                            authId,
                            authMeta.description,
                            authMeta.authType,
                    ]
            )
        }
    }

    protected static List<Map<String, Object>> collectSchemaTargets(def ec) {
        String activeTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (!activeTenantUserGroupId) return []

        JsonSchemaUtil.ensureJsonSchemaTable(ec)
        List rows = ec.entity.find("darpan.reconciliation.JsonSchema")
                .condition("companyUserGroupId", activeTenantUserGroupId)
                .condition("statusId", "Active")
                .orderBy("schemaName,jsonSchemaId")
                .useCache(false)
                .list() ?: []

        return rows.collect { item ->
            String id = FacadeSupport.normalize(item.jsonSchemaId)
            String label = FacadeSupport.normalize(item.schemaName) ?: id
            String systemLabel = JsonSchemaUtil.resolveSystemLabel(ec, item.systemEnumId, null, true)
            target(
                    "data:schema:${id}",
                    TYPE_SCHEMA,
                    "Open Schema: ${label}",
                    FacadeSupport.normalize(item.description) ?: (systemLabel ? "${systemLabel} schema." : "Open the schema editor."),
                    "schemas-editor",
                    "/schemas/editor/${encodePathSegment(id)}",
                    [jsonSchemaId: id],
                    [:],
                    id,
                    "darpan.reconciliation.JsonSchema",
                    [
                            label,
                            id,
                            item.description,
                            item.systemEnumId,
                            systemLabel,
                            "schema",
                            "json schema",
                            "schema editor",
                            "open schema",
                    ],
                    [
                            id,
                            label,
                            item.description,
                            item.systemEnumId,
                            systemLabel,
                    ]
            )
        }
    }

    protected static List<Map<String, Object>> collectSavedRunTargets(def ec) {
        return ReconciliationSavedRunSupport.collectSavedRunRows(ec).collect { Map<String, Object> row ->
            String id = FacadeSupport.normalize(row.savedRunId)
            String label = FacadeSupport.normalize(row.runName) ?: id
            List systemLabels = row.systemOptions instanceof Collection ? row.systemOptions.collect { it?.label } : []
            List systemCodes = row.systemOptions instanceof Collection ? row.systemOptions.collect { it?.enumCode ?: it?.enumId } : []
            target(
                    "data:saved-run:${id}",
                    TYPE_SAVED_RUN,
                    "Open Run: ${label}",
                    FacadeSupport.normalize(row.description) ?:
                            (FacadeSupport.normalize(row.compareScopeDescription) ?: "Open saved run history."),
                    "reconciliation-run-history",
                    "/reconciliation/run-history/${encodePathSegment(id)}",
                    [savedRunId: id],
                    [:],
                    id,
                    row.runType == ReconciliationSavedRunSupport.RUN_TYPE_RULESET ? "darpan.rule.RuleSet" : "darpan.mapping.ReconciliationMapping",
                    [
                            label,
                            id,
                            row.description,
                            row.runType,
                            row.reconciliationMappingId,
                            row.ruleSetId,
                            row.compareScopeId,
                            row.compareScopeDescription,
                            systemLabels,
                            systemCodes,
                            "saved run",
                            "run history",
                            "reconciliation run",
                            "open run",
                    ].flatten(),
                    [
                            id,
                            label,
                            row.description,
                            row.runType,
                            row.reconciliationMappingId,
                            row.ruleSetId,
                            row.compareScopeId,
                            row.compareScopeDescription,
                            systemLabels,
                            systemCodes,
                    ].flatten()
            )
        }
    }

    protected static List<Map<String, Object>> collectGeneratedOutputTargets(def ec) {
        String activeTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (!activeTenantUserGroupId) return []

        String outputLocation = TenantAccessSupport.resolveGenericOutputLocation(ec)
        File outputDir = ec.resource.getLocationReference(outputLocation)?.getFile()
        if (outputDir == null || !outputDir.exists()) return []

        return (outputDir.listFiles() ?: [] as File[])
                .findAll { File file -> file.isFile() && ReconciliationOutputSupport.isSupportedOutputFile(file.name) }
                .collectMany { File file ->
                    Map outputDocument = [:]
                    if (ReconciliationOutputSupport.sourceFormatForFile(file.name) == "json") {
                        try {
                            outputDocument = ReconciliationOutputSupport.parseGeneratedOutputText(file.getText("UTF-8"))
                        } catch (Exception ignored) {
                            outputDocument = [:]
                        }
                    }

                    Map<String, Object> descriptor = ReconciliationOutputSupport.buildGeneratedOutputDescriptor(
                            file.name,
                            outputDocument,
                            file.length(),
                            new Timestamp(file.lastModified())
                    )
                    String descriptorCompany = FacadeSupport.normalize(descriptor.companyUserGroupId)
                    if (descriptorCompany && descriptorCompany != activeTenantUserGroupId) return []

                    String savedRunId = FacadeSupport.normalize(descriptor.savedRunId ?: descriptor.reconciliationMappingId ?: descriptor.ruleSetId)
                    String fileName = FacadeSupport.normalize(descriptor.fileName)
                    if (!savedRunId || !fileName) return []

                    String label = FacadeSupport.normalize(descriptor.savedRunName ?: descriptor.mappingName) ?: fileName
                    String diffText = descriptor.totalDifferences instanceof Number ? "${descriptor.totalDifferences} differences" : "Saved result"
                    String file1Label = FacadeSupport.normalize(descriptor.file1Label)
                    String file2Label = FacadeSupport.normalize(descriptor.file2Label)
                    List<String> systemLabelParts = [file1Label, file2Label].findAll { it } as List<String>
                    String description = systemLabelParts.size() == 2 ?
                            "${diffText} for ${systemLabelParts[0]} and ${systemLabelParts[1]}." :
                            (systemLabelParts ? "${diffText} for ${systemLabelParts[0]}." : "${diffText}.")

                    return [target(
                            "data:run-result:${savedRunId}:${fileName}",
                            TYPE_RUN_RESULT,
                            "Open Result: ${label}",
                            description,
                            "reconciliation-run-result",
                            "/reconciliation/run-result/${encodePathSegment(savedRunId)}/${encodePathSegment(fileName)}",
                            [savedRunId: savedRunId, outputFileName: fileName],
                            [
                                    runName         : FacadeSupport.normalize(descriptor.savedRunName ?: descriptor.mappingName),
                                    file1SystemLabel: file1Label,
                                    file2SystemLabel: file2Label,
                            ].findAll { String key, Object value -> value },
                            fileName,
                            "generated-output",
                            [
                                    label,
                                    fileName,
                                    savedRunId,
                                    descriptor.savedRunName,
                                    descriptor.mappingName,
                                    descriptor.savedRunType,
                                    descriptor.reconciliationMappingId,
                                    descriptor.ruleSetId,
                                    descriptor.compareScopeId,
                                    descriptor.compareScopeDescription,
                                    descriptor.reconciliationType,
                                    file1Label,
                                    file2Label,
                                    "run result",
                                    "saved result",
                                    "diff result",
                                    "open result",
                            ],
                            [
                                    label,
                                    fileName,
                                    savedRunId,
                                    descriptor.savedRunName,
                                    descriptor.mappingName,
                                    descriptor.savedRunType,
                                    descriptor.reconciliationMappingId,
                                    descriptor.ruleSetId,
                                    descriptor.compareScopeId,
                                    descriptor.compareScopeDescription,
                                    descriptor.reconciliationType,
                                    file1Label,
                                    file2Label,
                            ]
                    )]
                } as List<Map<String, Object>>
    }

    protected static Map<String, Object> target(String resultId, String type, String label, String description,
            String routeName, String routePath, Map<String, Object> routeParams, Map<String, Object> routeQuery,
            String sourceId, String sourceType, List aliases, List contentValues) {
        return [
                resultId     : resultId,
                type         : type,
                label        : label,
                description  : description,
                routeName    : routeName,
                routePath    : routePath,
                routeParams  : routeParams ?: [:],
                routeQuery   : routeQuery ?: [:],
                sourceId     : sourceId,
                sourceType   : sourceType,
                _aliases     : compactStrings(aliases),
                _contentText : compactStrings(contentValues).join(" "),
        ]
    }

    protected static int scoreCandidate(Map<String, Object> candidate, String query, List<String> queryTokens) {
        String phrase = normalizeSearchText(query)
        String labelText = normalizeSearchText(candidate.label)
        String descriptionText = normalizeSearchText(candidate.description)
        String aliasText = normalizeSearchText(candidate._aliases)
        String contentText = normalizeSearchText(candidate._contentText)
        String searchableText = [labelText, descriptionText, aliasText, contentText].findAll { it }.join(" ")
        if (!searchableText) return 0

        List<String> contentTokens = queryTokens.findAll { String token -> !INTENT_TOKENS.contains(token) }
        if (contentTokens && !contentTokens.any { String token -> contentText.contains(token) || labelText.contains(token) || descriptionText.contains(token) }) {
            return 0
        }

        int score = 0
        if (phrase && labelText == phrase) score += 160
        if (phrase && labelText.contains(phrase)) score += 100
        if (phrase && descriptionText.contains(phrase)) score += 55
        if (phrase && aliasText.contains(phrase)) score += 35
        if (phrase && contentText.contains(phrase)) score += 45

        queryTokens.each { String token ->
            if (labelText.contains(token)) score += 30
            if (descriptionText.contains(token)) score += 16
            if (contentText.contains(token)) score += 14
            if (aliasText.contains(token)) score += 8
        }
        contentTokens.each { String token ->
            if (labelText.contains(token) || contentText.contains(token)) score += 20
        }

        return score
    }

    protected static Set<String> normalizeTypes(Object rawTypes) {
        Collection rawCollection
        if (rawTypes instanceof Collection) {
            rawCollection = (Collection) rawTypes
        } else {
            String rawString = FacadeSupport.normalize(rawTypes)
            rawCollection = rawString ? rawString.split(/\s*,\s*/).toList() : []
        }

        Set<String> selected = rawCollection.collect { Object rawType ->
            canonicalType(rawType)
        }.findAll { it } as Set<String>
        return selected ? selected : (DEFAULT_TYPES as Set<String>)
    }

    protected static String canonicalType(Object rawType) {
        String value = FacadeSupport.normalize(rawType)?.toLowerCase()?.replaceAll(/[_\s]+/, "-")
        switch (value) {
            case TYPE_SCHEMA:
            case "json-schema":
            case "schemas":
                return TYPE_SCHEMA
            case TYPE_SFTP_SERVER:
            case "sftp":
            case "sftp-config":
            case "sftp-servers":
                return TYPE_SFTP_SERVER
            case TYPE_NETSUITE_AUTH:
            case "ns-auth":
            case "netsuite-auth-config":
                return TYPE_NETSUITE_AUTH
            case TYPE_NETSUITE_ENDPOINT:
            case "ns-endpoint":
            case "netsuite-restlet":
            case "restlet":
                return TYPE_NETSUITE_ENDPOINT
            case TYPE_SAVED_RUN:
            case "run":
            case "saved-runs":
                return TYPE_SAVED_RUN
            case TYPE_RUN_RESULT:
            case "result":
            case "results":
            case "generated-output":
            case "generated-outputs":
                return TYPE_RUN_RESULT
            default:
                return null
        }
    }

    protected static List<String> tokenize(String value) {
        String normalized = normalizeSearchText(value)
        if (!normalized) return []
        return normalized.split(/\s+/).findAll { String token -> token.size() >= 2 }.unique()
    }

    protected static String normalizeSearchText(Object value) {
        if (value == null) return ""
        Collection values = value instanceof Collection ? (Collection) value : [value]
        return values.collectMany { Object item ->
            if (item instanceof Collection) return (Collection) item
            return [item]
        }.collect { Object item ->
            FacadeSupport.normalize(item)
        }.findAll { it }.join(" ")
                .toLowerCase()
                .replaceAll(/[^a-z0-9]+/, " ")
                .replaceAll(/\s+/, " ")
                .trim()
    }

    protected static List<String> compactStrings(Collection values) {
        Set<String> seen = [] as Set<String>
        List<String> normalized = []
        (values ?: []).flatten().each { Object value ->
            String text = FacadeSupport.normalize(value)
            if (!text) return
            String key = text.toLowerCase()
            if (seen.contains(key)) return
            seen.add(key)
            normalized.add(text)
        }
        return normalized
    }

    protected static String encodePathSegment(String value) {
        return URLEncoder.encode(value ?: "", StandardCharsets.UTF_8.toString()).replace("+", "%20")
    }

    protected static int typeOrder(String type) {
        int index = DEFAULT_TYPES.indexOf(type)
        return index >= 0 ? index : DEFAULT_TYPES.size()
    }

    protected static Map<String, Object> pagination(int page, int size, int totalCount) {
        return [
                pageIndex : page,
                pageSize  : size,
                totalCount: totalCount,
                pageCount : Math.max(1, Math.ceil(totalCount / (double) size) as int),
        ]
    }
}
