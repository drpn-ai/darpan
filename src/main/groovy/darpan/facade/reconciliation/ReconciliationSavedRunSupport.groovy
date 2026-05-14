package darpan.facade.reconciliation

import darpan.common.DarpanEntityConstants
import darpan.facade.common.FacadeSupport
import darpan.facade.common.PaginationSupport
import darpan.facade.common.TenantAccessSupport
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import reconciliation.rule.RuleEngineSupport

import java.sql.Connection
import java.sql.Statement

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.boundedInt
import static darpan.common.ValueSupport.normalizeBool
import static darpan.common.ValueSupport.normalizeInt
import static darpan.common.ValueSupport.normalizeLower
import static darpan.common.ValueSupport.normalizeUpper

class ReconciliationSavedRunSupport {
    protected static final Logger logger = LoggerFactory.getLogger(ReconciliationSavedRunSupport.class)

    static final String RUN_TYPE_MAPPING = "mapping"
    static final String RUN_TYPE_RULESET = "ruleset"
    static final String FILE_SIDE_1 = "FILE_1"
    static final String FILE_SIDE_2 = "FILE_2"
    static final String DEFAULT_FILE_TYPE_ENUM_ID = "DftCsv"
    static final String SOURCE_TYPE_API = "AUT_SRC_API"
    static final String SYSTEM_SHOPIFY = "SHOPIFY"
    static final String SYSTEM_HOTWAX_OMS = "OMS"
    static final String SYSTEM_NETSUITE = "NETSUITE"
    static final String SYSTEM_SAPI = "SAPI"
    static final String SOURCE_CONFIG_TYPE_SHOPIFY_AUTH = "SHOPIFY_AUTH"
    static final String SOURCE_CONFIG_TYPE_HOTWAX_OMS_REST = "HOTWAX_OMS_REST"
    static final String SOURCE_CONFIG_TYPE_NETSUITE_AUTH = "NETSUITE_AUTH"
    static final String HOTWAX_ORDERS_REMOTE_ID = "HOTWAX_ORDERS_API"
    static final String HOTWAX_ORDERS_ENDPOINT_LABEL = "Orders API"
    static final String HOTWAX_OMS_ORDERS_EXTRACT_SERVICE = "reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsOrders"
    static final String SHOPIFY_ORDERS_REMOTE_ID = "SHOPIFY_REMOTE"
    static final String SHOPIFY_ORDERS_ENDPOINT_LABEL = "Admin GraphQL Orders"
    static final String SHOPIFY_GRAPHQL_EXECUTE_SERVICE = "facade.ShopifyFacadeServices.execute#ShopifyGraphql"
    static final int ENTITY_ID_MAX_LENGTH = 40

    static String generateCsvRuleSetId(def ec, String runName) {
        return generateRuleSetId(ec, runName ?: "CSV_RUN")
    }

    static String generateRuleSetId(def ec, String runName) {
        String base = RuleEngineSupport.sanitizeIdToken(runName ?: "CSV_RUN", "RUN")
        if (base.startsWith("RS_")) base = base.substring(3)
        String candidate = buildEntityId("RS", base)
        int suffix = 1

        while (savedRunIdExists(ec, candidate)) {
            candidate = buildEntityId("RS", base, null, "_${suffix}")
            suffix++
        }

        return candidate
    }

    static List<Map<String, Object>> normalizeRuleEntries(Object rawRules) {
        if (!(rawRules instanceof Collection)) return []

        List<Map<String, Object>> normalized = []
        int sequenceNum = 10
        rawRules.each { Object rawRule ->
            if (!(rawRule instanceof Map)) return

            Map rule = (Map) rawRule
            String ruleId = normalize(rule.ruleId)
            String ruleText = normalize(rule.ruleText)
            String ruleLogic = normalize(rule.ruleLogic)
            String ruleType = normalize(rule.ruleType)
            String expression = normalize(rule.expression)
            String enabled = normalizeUpper(rule.enabled) == "N" ? "N" : "Y"
            String severity = normalize(rule.severity)
            Integer providedSequenceNum = toSequenceNum(rule.sequenceNum)

            if (!ruleText && !ruleLogic) return

            normalized.add([
                    ruleId     : ruleId,
                    ruleText   : ruleText,
                    ruleLogic  : ruleLogic,
                    ruleType   : ruleType,
                    expression : expression,
                    enabled    : enabled,
                    severity   : severity,
                    sequenceNum: providedSequenceNum ?: sequenceNum,
            ])
            sequenceNum += 10
        }

        return normalized
    }

    static Integer toSequenceNum(Object rawValue) {
        return normalizeInt(rawValue, null)
    }

    static String normalizeJsonPrimaryIdExpression(Object rawExpression) {
        String normalized = normalize(rawExpression)
        if (!normalized) return null

        int suffixIndex = normalized.indexOf("|")
        String baseExpression = suffixIndex >= 0 ? normalize(normalized.substring(0, suffixIndex)) : normalized
        String suffix = suffixIndex >= 0 ? normalize(normalized.substring(suffixIndex + 1)) : null

        String normalizedBase = ReconciliationMappingSupport.normalizeMappingJsonIdExpression(baseExpression) ?: baseExpression
        return suffix ? "${normalizedBase}|${suffix}" : normalizedBase
    }

    static boolean ensureRuleSetCompareScopeObjectTypeNullable(def ec) {
        String groupName = ec?.entity?.getEntityGroupName(DarpanEntityConstants.RULE_SET_COMPARE_SCOPE) ?: "default"
        def datasourceFactory = ec?.entity?.getDatasourceFactory(groupName)
        datasourceFactory?.checkAndAddTable(DarpanEntityConstants.RULE_SET_COMPARE_SCOPE)

        def entityDefinition = ec?.entity?.getEntityDefinition(DarpanEntityConstants.RULE_SET_COMPARE_SCOPE)
        if (entityDefinition == null) return true

        String tableName = entityDefinition.getFullTableName()
        String schemaName = entityDefinition.getSchemaName()
        String rawTableName = entityDefinition.getTableName()
        String columnName = entityDefinition.getColumnName("objectType")

        Connection connection = null
        Statement statement = null
        try {
            connection = ec.entity.getConnection(groupName)
            def metadata = connection.metaData
            Map<String, Object> columnMetadata = findColumn(metadata, connection?.catalog, schemaName, rawTableName, columnName)
            if (columnMetadata == null) return true

            String isNullable = normalizeUpper(columnMetadata.isNullable)
            if (isNullable == "YES") return true

            String databaseProductName = normalizeLower(metadata?.databaseProductName)
            String typeName = normalize(columnMetadata.typeName) ?: "VARCHAR"
            int columnSize = columnMetadata.columnSize instanceof Number ? ((Number) columnMetadata.columnSize).intValue() : 0
            int decimalDigits = columnMetadata.decimalDigits instanceof Number ? ((Number) columnMetadata.decimalDigits).intValue() : 0
            String alterSql = buildDropNotNullSql(databaseProductName, tableName, columnName, typeName, columnSize, decimalDigits)
            if (!alterSql) {
                ec?.message?.addError("RuleSetCompareScope.objectType must be nullable, but database '${metadata?.databaseProductName}' could not be repaired automatically.")
                return false
            }

            statement = connection.createStatement()
            statement.execute(alterSql)
            return true
        } catch (Exception e) {
            ec?.message?.addError("Could not update RuleSetCompareScope.objectType column contract: ${e.message}")
            return false
        } finally {
            try { statement?.close() } catch (Exception ignored) {}
            try { connection?.close() } catch (Exception ignored) {}
        }
    }

    protected static Map<String, Object> findColumn(def metadata, String catalog, String schemaName, String tableName, String columnName) {
        List<Map<String, String>> attempts = [
                [schema: schemaName, table: tableName, column: columnName],
                [schema: schemaName?.toUpperCase(), table: tableName?.toUpperCase(), column: columnName?.toUpperCase()],
                [schema: schemaName?.toLowerCase(), table: tableName?.toLowerCase(), column: columnName?.toLowerCase()],
                [schema: null, table: tableName, column: columnName],
                [schema: null, table: tableName?.toUpperCase(), column: columnName?.toUpperCase()],
                [schema: null, table: tableName?.toLowerCase(), column: columnName?.toLowerCase()],
        ].findAll { Map<String, String> attempt -> attempt.table && attempt.column }

        for (Map<String, String> attempt : attempts) {
            def rs = metadata.getColumns(catalog, attempt.schema, attempt.table, attempt.column)
            try {
                if (rs != null && rs.next()) {
                    return [
                            isNullable   : rs.getString("IS_NULLABLE"),
                            typeName     : rs.getString("TYPE_NAME"),
                            columnSize   : rs.getInt("COLUMN_SIZE"),
                            decimalDigits: rs.getInt("DECIMAL_DIGITS"),
                    ]
                }
            } finally {
                rs?.close()
            }
        }
        return null
    }

    protected static String buildDropNotNullSql(String databaseProductName, String tableName, String columnName,
            String typeName, int columnSize, int decimalDigits) {
        if (!databaseProductName || !tableName || !columnName) return null

        if (databaseProductName.contains("h2") || databaseProductName.contains("postgres")) {
            return "ALTER TABLE ${tableName} ALTER COLUMN ${columnName} DROP NOT NULL"
        }

        if (databaseProductName.contains("mysql") || databaseProductName.contains("mariadb")) {
            String normalizedTypeName = typeName?.toUpperCase()
            String typeSql = normalizedTypeName
            if (normalizedTypeName in ["CHAR", "VARCHAR", "CHARACTER", "CHARACTER VARYING", "VARBINARY", "BINARY"]) {
                int resolvedSize = columnSize > 0 ? columnSize : 255
                String sqlTypeName = normalizedTypeName == "CHARACTER VARYING" ? "VARCHAR" : normalizedTypeName
                typeSql = "${sqlTypeName}(${resolvedSize})"
            } else if (normalizedTypeName in ["DECIMAL", "NUMERIC"] && columnSize > 0) {
                int resolvedScale = Math.max(0, decimalDigits)
                typeSql = "${normalizedTypeName}(${columnSize},${resolvedScale})"
            }
            return "ALTER TABLE ${tableName} MODIFY COLUMN ${columnName} ${typeSql} NULL"
        }

        return null
    }

    static String generateCompareScopeId(def ec, String ruleSetId) {
        return buildCompareScopeId(ec, ruleSetId, "COMPARE_SCOPE")
    }

    static String generateCompareScopeId(def ec, String ruleSetId, String scopeKind) {
        return buildCompareScopeId(ec, ruleSetId, scopeKind)
    }

    protected static String buildCompareScopeId(def ec, String ruleSetId, String scopeKind) {
        String normalizedRuleSetId = RuleEngineSupport.sanitizeIdToken(normalize(ruleSetId) ?: "RULE_SET", "RS")
        if (!normalizedRuleSetId.startsWith("RS_")) normalizedRuleSetId = "RS_${normalizedRuleSetId}"
        String scopeSuffix = RuleEngineSupport.sanitizeIdToken(normalize(scopeKind) ?: "COMPARE_SCOPE", "COMPARE_SCOPE")
        String candidate = buildEntityId("CS", normalizedRuleSetId, scopeSuffix)
        int suffix = 1

        while (ec.entity.find(DarpanEntityConstants.RULE_SET_COMPARE_SCOPE)
                .condition("compareScopeId", candidate)
                .disableAuthz()
                .useCache(false)
                .one() != null) {
            candidate = buildEntityId("CS", normalizedRuleSetId, scopeSuffix, "_${suffix}")
            suffix++
        }

        return candidate
    }

    protected static String buildEntityId(String prefix, String bodyToken, String trailingToken = null, String collisionSuffix = null) {
        String normalizedPrefix = normalize(prefix) ?: "ID"
        String normalizedBodyToken = normalize(bodyToken) ?: "VALUE"
        String normalizedTrailingToken = normalize(trailingToken)
        String normalizedCollisionSuffix = collisionSuffix ?: ""

        int reservedLength = normalizedPrefix.length() + 1 + normalizedCollisionSuffix.length()
        if (normalizedTrailingToken) reservedLength += 1 + normalizedTrailingToken.length()

        int maxBodyLength = Math.max(1, ENTITY_ID_MAX_LENGTH - reservedLength)
        String trimmedBodyToken = normalizedBodyToken.length() > maxBodyLength ?
                normalizedBodyToken.substring(0, maxBodyLength) : normalizedBodyToken

        return normalizedPrefix +
                "_" +
                trimmedBodyToken +
                (normalizedTrailingToken ? "_${normalizedTrailingToken}" : "") +
                normalizedCollisionSuffix
    }

    static String compareScopeDisplayName(Object compareScopeId, Object compareScopeDescription) {
        String description = normalize(compareScopeDescription)
        if (description) return description
        return normalize(compareScopeId)
    }

    static boolean savedRunIdExists(def ec, String savedRunId) {
        String normalized = normalize(savedRunId)
        if (!normalized) return false

        return ec.entity.find(DarpanEntityConstants.RULE_SET)
                .condition("ruleSetId", normalized)
                .disableAuthz()
                .useCache(false)
                .one() != null ||
                ec.entity.find(DarpanEntityConstants.RECONCILIATION_MAPPING)
                        .condition("reconciliationMappingId", normalized)
                        .disableAuthz()
                        .useCache(false)
                        .one() != null
    }

    static List<Map<String, Object>> collectSavedRunRows(def ec) {
        List<Map<String, Object>> rows = []
        rows.addAll(collectMappingRows(ec))
        rows.addAll(collectRuleSetRows(ec))

        return rows.sort { Map<String, Object> left, Map<String, Object> right ->
            String leftName = normalize(left.runName) ?: normalize(left.savedRunId) ?: ""
            String rightName = normalize(right.runName) ?: normalize(right.savedRunId) ?: ""
            int nameCompare = leftName <=> rightName
            if (nameCompare != 0) return nameCompare
            return (normalize(left.savedRunId) ?: "") <=> (normalize(right.savedRunId) ?: "")
        }
    }

    static Map<String, Object> findSavedRunById(def ec, Object rawSavedRunId) {
        String savedRunId = normalize(rawSavedRunId)
        if (!savedRunId) return null

        def mapping = ec.entity.find(DarpanEntityConstants.RECONCILIATION_MAPPING)
                .condition("reconciliationMappingId", savedRunId)
                .disableAuthz()
                .useCache(false)
                .one()
        if (mapping) return buildMappingSavedRunRow(ec, mapping)

        return (Map<String, Object>) resolveRuleSetRun(ec, savedRunId).savedRun
    }

    static Map<String, Object> listSavedRuns(def ec, Object query, Object pageIndex, Object pageSize) {
        int page = boundedInt(pageIndex, 0, 0, Integer.MAX_VALUE)
        int size = boundedInt(pageSize, 20, 1, 200)
        String search = normalizeLower(query)

        List<Map<String, Object>> rows = collectSavedRunRows(ec)
        if (search) rows = rows.findAll { Map<String, Object> row -> savedRunMatches(row, search) }

        Map<String, Object> pagination = PaginationSupport.pagination(page, size, rows.size())
        List<Map<String, Object>> pagedRows = PaginationSupport.pageRows(rows, page, size)
        Set<String> availableSavedRunIds = rows.collect { it.savedRunId as String }.findAll { it } as Set<String>

        Map<String, Object> envelope = FacadeSupport.envelope(ec)
        return envelope + [
                savedRuns        : pagedRows,
                pinnedSavedRunIds: ReconciliationDashboardPreferenceSupport.listPinnedReconciliationMappingIds(ec, availableSavedRunIds),
                pagination       : pagination,
        ]
    }

    static List<Map<String, Object>> collectMappingRows(def ec) {
        def mappingFinder = ec.entity.find(DarpanEntityConstants.RECONCILIATION_MAPPING)
                .orderBy("mappingName,reconciliationMappingId")
                .disableAuthz()
                .useCache(false)
        List<Map<String, Object>> rows = []

        (mappingFinder.list() ?: []).each { mapping ->
            List mappingMembers = ec.entity.find(DarpanEntityConstants.RECONCILIATION_MAPPING_MEMBER)
                    .condition("reconciliationMappingId", mapping.reconciliationMappingId)
                    .disableAuthz()
                    .useCache(false)
                    .list() ?: []
            Map<String, Object> row = buildMappingSavedRunRow(ec, mapping, mappingMembers)
            if (row) rows.add(row)
        }

        return rows
    }

    protected static Map<String, Object> buildMappingSavedRunRow(def ec, def mapping, List mappingMembers = null) {
        if (!mapping || !TenantAccessSupport.canAccessTenantRecord(ec, mapping)) return null

        List members = mappingMembers
        if (members == null) {
            members = ec.entity.find(DarpanEntityConstants.RECONCILIATION_MAPPING_MEMBER)
                    .condition("reconciliationMappingId", mapping.reconciliationMappingId)
                    .disableAuthz()
                    .useCache(false)
                    .list() ?: []
        }

        List<String> mappingReadinessIssues = ReconciliationMappingSupport.collectReadinessIssues(ec, members)
        if (!mappingReadinessIssues.isEmpty()) return null

        List<Map<String, Object>> systemOptions = members.collect { member ->
            def systemEnum = FacadeSupport.findEnum(ec,member.systemEnumId)
            def fileTypeEnum = FacadeSupport.findEnum(ec,member.fileTypeEnumId)
            [
                    enumId           : normalize(member.systemEnumId),
                    enumCode         : normalize(systemEnum?.enumCode),
                    description      : normalize(systemEnum?.description),
                    label            : resolveEnumLabel(ec, member.systemEnumId, member.systemEnumId as String),
                    sequenceNum      : systemEnum?.sequenceNum ?: Integer.MAX_VALUE,
                    fileTypeEnumId   : normalize(member.fileTypeEnumId),
                    fileTypeLabel    : fileTypeEnum ? FacadeSupport.enumLabel(fileTypeEnum) : null,
                    idFieldExpression: normalize(member.idFieldExpression ?: member.systemFieldName),
                    schemaFileName   : normalize(member.schemaFileName),
            ]
        }.sort { Map<String, Object> left, Map<String, Object> right ->
            (left.sequenceNum <=> right.sequenceNum) ?:
                    ((left.label ?: "") <=> (right.label ?: "")) ?:
                    ((left.enumId ?: "") <=> (right.enumId ?: ""))
        }

        List<String> systemIds = systemOptions.collect { it.enumId as String }.findAll { it }.unique()
        return [
                savedRunId              : mapping.reconciliationMappingId,
                runName                 : mapping.mappingName,
                description             : mapping.description,
                companyUserGroupId      : mapping.companyUserGroupId,
                companyLabel            : TenantAccessSupport.resolveTenantLabelForUserGroupId(ec, mapping.companyUserGroupId),
                runType                 : RUN_TYPE_MAPPING,
                reconciliationMappingId : mapping.reconciliationMappingId,
                ruleSetId               : null,
                compareScopeId          : null,
                requiresSystemSelection : systemIds.size() != 2,
                defaultFile1SystemEnumId: systemIds.size() >= 2 ? systemIds[0] : null,
                defaultFile2SystemEnumId: systemIds.size() >= 2 ? systemIds[1] : null,
                systemOptions           : systemOptions.collect { Map<String, Object> option ->
                    option.findAll { String key, Object value -> key != "sequenceNum" }
                },
        ]
    }

    static List<Map<String, Object>> collectRuleSetRows(def ec) {
        def ruleSetFinder = ec.entity.find(DarpanEntityConstants.RULE_SET)
                .orderBy("ruleSetName,ruleSetId")
                .disableAuthz()
                .useCache(false)
        List<Map<String, Object>> rows = []

        (ruleSetFinder.list() ?: []).each { ruleSet ->
            if (!TenantAccessSupport.canAccessTenantRecord(ec, ruleSet)) return
            Map<String, Object> resolved = resolveRuleSetRun(ec, ruleSet.ruleSetId)
            if (resolved.savedRun) rows.add((Map<String, Object>) resolved.savedRun)
        }

        return rows
    }

    static Map<String, Object> resolveRuleSetRun(def ec, Object rawSavedRunId) {
        String savedRunId = normalize(rawSavedRunId)
        if (!savedRunId) return [savedRun: null, error: null]

        def ruleSet = ec.entity.find(DarpanEntityConstants.RULE_SET)
                .condition("ruleSetId", savedRunId)
                .disableAuthz()
                .useCache(false)
                .one()
        if (!ruleSet) return [savedRun: null, error: null]
        if (!TenantAccessSupport.canAccessTenantRecord(ec, ruleSet)) {
            return [savedRun: null, error: "Saved run '${savedRunId}' is not available in your active tenant."]
        }

        List compareScopes = ec.entity.find(DarpanEntityConstants.RULE_SET_COMPARE_SCOPE)
                .condition("ruleSetId", savedRunId)
                .orderBy("compareScopeId")
                .disableAuthz()
                .useCache(false)
                .list() ?: []
        if (compareScopes.size() != 1) {
            return [savedRun: null, error: "RuleSet ${savedRunId} defines ${compareScopes.size()} compare scopes; saved RuleSet runs currently require exactly one compare scope."]
        }

        def compareScope = compareScopes[0]
        String compareScopeLabel = compareScopeDisplayName(compareScope.compareScopeId, compareScope.description)
        List sources = ec.entity.find("darpan.rule.RuleSetCompareSource")
                .condition("compareScopeId", compareScope.compareScopeId)
                .disableAuthz()
                .useCache(false)
                .list() ?: []
        if (sources.size() != 2) {
            return [savedRun: null, error: "RuleSet ${savedRunId} compare scope '${compareScopeLabel}' must define exactly two file-side sources."]
        }

        Map<String, Object> sourceBySide = [:]
        for (def source in sources) {
            String fileSide = normalizeUpper(source.fileSide)
            if (!(fileSide in [FILE_SIDE_1, FILE_SIDE_2])) {
                return [savedRun: null, error: "RuleSet ${savedRunId} compare scope '${compareScopeLabel}' has unsupported fileSide ${source.fileSide}."]
            }
            if (sourceBySide.containsKey(fileSide)) {
                return [savedRun: null, error: "RuleSet ${savedRunId} compare scope '${compareScopeLabel}' defines ${fileSide} more than once."]
            }
            sourceBySide[fileSide] = source
        }
        if (!sourceBySide[FILE_SIDE_1] || !sourceBySide[FILE_SIDE_2]) {
            return [savedRun: null, error: "RuleSet ${savedRunId} compare scope '${compareScopeLabel}' must define FILE_1 and FILE_2."]
        }

        List<Map<String, Object>> systemOptions = buildRuleSetSystemOptions(ec, sourceBySide)
        return [
                savedRun  : buildRuleSetSavedRunRow(ec, ruleSet, compareScope, sourceBySide, systemOptions),
                ruleSet   : ruleSet,
                compareScope: compareScope,
                sourceBySide : sourceBySide,
                systemOptions: systemOptions,
                error       : null,
        ]
    }

    static Map<String, Object> buildRuleSetSavedRunRow(def ec, def ruleSet, def compareScope,
            Map<String, Object> sourceBySide, List<Map<String, Object>> systemOptions = null) {
        List<Map<String, Object>> resolvedSystemOptions = systemOptions ?: buildRuleSetSystemOptions(ec, sourceBySide)
        return [
                savedRunId              : ruleSet.ruleSetId,
                runName                 : normalize(ruleSet.ruleSetName) ?: normalize(ruleSet.ruleSetId),
                description             : normalize(ruleSet.description),
                companyUserGroupId      : normalize(ruleSet.companyUserGroupId),
                companyLabel            : TenantAccessSupport.resolveTenantLabelForUserGroupId(ec, ruleSet.companyUserGroupId),
                runType                 : RUN_TYPE_RULESET,
                reconciliationMappingId : null,
                ruleSetId               : ruleSet.ruleSetId,
                compareScopeId          : compareScope.compareScopeId,
                compareScopeDescription : compareScopeDisplayName(compareScope.compareScopeId, compareScope.description),
                requiresSystemSelection : false,
                defaultFile1SystemEnumId: canonicalSystemEnumId(sourceBySide[FILE_SIDE_1]?.systemEnumId),
                defaultFile2SystemEnumId: canonicalSystemEnumId(sourceBySide[FILE_SIDE_2]?.systemEnumId),
                systemOptions           : resolvedSystemOptions,
                rules                   : collectRuleRows(ec, ruleSet.ruleSetId),
        ]
    }

    static List<Map<String, Object>> collectRuleRows(def ec, Object rawRuleSetId) {
        String ruleSetId = normalize(rawRuleSetId)
        if (!ruleSetId) return []

        List rules = ec.entity.find("darpan.rule.Rule")
                .condition("ruleSetId", ruleSetId)
                .orderBy("sequenceNum,ruleId")
                .disableAuthz()
                .useCache(false)
                .list() ?: []

        return rules.collect { rule ->
            Map<String, Object> expressionData = parseRuleExpression(rule.expression)
            [
                    ruleId        : normalize(rule.ruleId),
                    sequenceNum   : rule.sequenceNum,
                    ruleText      : normalize(rule.ruleText),
                    ruleLogic     : normalize(rule.ruleLogic),
                    ruleType      : normalize(rule.ruleType),
                    expression    : normalize(rule.expression),
                    enabled       : normalize(rule.enabled),
                    severity      : normalize(rule.severity),
                    file1FieldPath: normalize(expressionData.file1FieldPath),
                    file2FieldPath: normalize(expressionData.file2FieldPath),
                    operator      : normalize(expressionData.operator),
                    preActions    : normalizePreActions(expressionData.preActions ?: expressionData.preAction),
            ].findAll { String key, Object value -> value != null }
        } as List<Map<String, Object>>
    }

    static List<Map<String, String>> normalizePreActions(Object rawPreActions) {
        if (rawPreActions == null) return null

        Collection values = rawPreActions instanceof Collection ? (Collection) rawPreActions : [rawPreActions]
        List<Map<String, String>> normalized = values.collectMany { Object rawValue ->
            if (rawValue instanceof Map) {
                String action = normalizePreAction(((Map) rawValue).action ?: ((Map) rawValue).preAction)
                String fieldSide = normalizePreActionFieldSide(((Map) rawValue).fieldSide ?: ((Map) rawValue).field ?: ((Map) rawValue).side)
                return action && fieldSide ? [[fieldSide: fieldSide, action: action]] : []
            }

            String action = normalizePreAction(rawValue)
            return action ? [[fieldSide: "file1", action: action], [fieldSide: "file2", action: action]] : []
        } as List<Map<String, String>>

        Set<String> seen = [] as Set
        normalized = normalized.findAll { Map<String, String> entry ->
            String key = "${entry.fieldSide}:${entry.action}"
            if (seen.contains(key)) return false
            seen.add(key)
            return true
        }

        return normalized ? normalized : null
    }

    static String normalizePreAction(Object rawPreAction) {
        String value = normalizeUpper(rawPreAction)
        if (value in ["STRING_TO_INTEGER", "TO_INT", "TO_INTEGER"]) return "STRING_TO_INT"
        if (value == "TO_NUMBER") return "STRING_TO_NUMBER"
        return value in ["STRING_TO_INT", "STRING_TO_NUMBER"] ? value : null
    }

    static String normalizePreActionFieldSide(Object rawFieldSide) {
        String value = normalizeLower(rawFieldSide)
        if (value in ["file1", "file_1", "left"]) return "file1"
        if (value in ["file2", "file_2", "right"]) return "file2"
        return null
    }

    static Map<String, Object> parseRuleExpression(Object rawExpression) {
        String expression = normalize(rawExpression)
        if (!expression) return [:]

        try {
            Object parsed = new JsonSlurper().parseText(expression)
            return parsed instanceof Map ? (Map<String, Object>) parsed : [:]
        } catch (Exception e) {
            logger.warn("Failed to parse rule expression JSON", e)
            return [:]
        }
    }

    static Map<String, Object> resolveApiSourceConfig(def ec, String sourceLabel, Object rawSystemEnumId,
            Object rawSourceConfigId, Object rawSourceConfigType, Object rawNsRestletConfig = null) {
        String systemEnumId = canonicalSystemEnumId(rawSystemEnumId)
        String sourceConfigId = normalize(rawSourceConfigId)
        String sourceConfigType = normalize(rawSourceConfigType)
        def nsRestletConfig = rawNsRestletConfig

        if (!sourceConfigId && systemEnumId == SYSTEM_NETSUITE && nsRestletConfig) {
            sourceConfigId = normalize(nsRestletConfig.nsAuthConfigId)
        }

        String expectedType = expectedSourceConfigType(systemEnumId)
        if (expectedType && !sourceConfigId) {
            ec.message.addError("${sourceLabel} API source requires sourceConfigId for ${systemEnumId}.")
            return [sourceConfigId: null, sourceConfigType: null]
        }
        if (!sourceConfigId) return [sourceConfigId: null, sourceConfigType: null]

        if (expectedType && sourceConfigType && sourceConfigType != expectedType) {
            ec.message.addError("${sourceLabel} sourceConfigType '${sourceConfigType}' is not valid for ${systemEnumId}.")
            return [sourceConfigId: sourceConfigId, sourceConfigType: sourceConfigType]
        }
        sourceConfigType = sourceConfigType ?: expectedType

        switch (systemEnumId) {
            case SYSTEM_SHOPIFY:
                validateShopifyAuthConfig(ec, sourceLabel, sourceConfigId)
                break
            case SYSTEM_HOTWAX_OMS:
                validateHotWaxOmsConfig(ec, sourceLabel, sourceConfigId)
                break
            case SYSTEM_NETSUITE:
                validateNetSuiteAuthConfig(ec, sourceLabel, sourceConfigId, nsRestletConfig)
                break
            default:
                break
        }

        return [sourceConfigId: sourceConfigId, sourceConfigType: sourceConfigType]
    }

    static String expectedSourceConfigType(Object rawSystemEnumId) {
        switch (canonicalSystemEnumId(rawSystemEnumId)) {
            case SYSTEM_SHOPIFY:
                return SOURCE_CONFIG_TYPE_SHOPIFY_AUTH
            case SYSTEM_HOTWAX_OMS:
                return SOURCE_CONFIG_TYPE_HOTWAX_OMS_REST
            case SYSTEM_NETSUITE:
                return SOURCE_CONFIG_TYPE_NETSUITE_AUTH
            default:
                return null
        }
    }

    protected static void validateShopifyAuthConfig(def ec, String sourceLabel, String sourceConfigId) {
        def config = ec.entity.find(DarpanEntityConstants.SHOPIFY_AUTH_CONFIG)
                .condition("shopifyAuthConfigId", sourceConfigId)
                .disableAuthz()
                .useCache(false)
                .one()
        TenantAccessSupport.requireTenantRecordAccess(ec, config,
                "${sourceLabel} Shopify auth config '${sourceConfigId}' was not found.",
                "${sourceLabel} Shopify auth config '${sourceConfigId}' is not available in your active tenant.")
        if (config && normalize(config.isActive) == "N") {
            ec.message.addError("${sourceLabel} Shopify auth config '${sourceConfigId}' is inactive.")
        }
        if (config && !normalizeBool(config.canReadOrders)) {
            ec.message.addError("${sourceLabel} Shopify auth config '${sourceConfigId}' cannot read orders.")
        }
    }

    protected static void validateHotWaxOmsConfig(def ec, String sourceLabel, String sourceConfigId) {
        def config = ec.entity.find(DarpanEntityConstants.HOT_WAX_OMS_REST_SOURCE_CONFIG)
                .condition("omsRestSourceConfigId", sourceConfigId)
                .disableAuthz()
                .useCache(false)
                .one()
        TenantAccessSupport.requireTenantRecordAccess(ec, config,
                "${sourceLabel} HotWax source config '${sourceConfigId}' was not found.",
                "${sourceLabel} HotWax source config '${sourceConfigId}' is not available in your active tenant.")
        if (config && normalize(config.isActive) == "N") {
            ec.message.addError("${sourceLabel} HotWax source config '${sourceConfigId}' is inactive.")
        }
        if (config && !normalizeBool(config.canReadOrders)) {
            ec.message.addError("${sourceLabel} HotWax source config '${sourceConfigId}' cannot read orders.")
        }
    }

    protected static void validateNetSuiteAuthConfig(def ec, String sourceLabel, String sourceConfigId, def nsRestletConfig) {
        def config = ec.entity.find(DarpanEntityConstants.NS_AUTH_CONFIG)
                .condition("nsAuthConfigId", sourceConfigId)
                .disableAuthz()
                .useCache(false)
                .one()
        TenantAccessSupport.requireTenantRecordAccess(ec, config,
                "${sourceLabel} NetSuite auth config '${sourceConfigId}' was not found.",
                "${sourceLabel} NetSuite auth config '${sourceConfigId}' is not available in your active tenant.")
        if (config && normalize(config.isActive) == "N") {
            ec.message.addError("${sourceLabel} NetSuite auth config '${sourceConfigId}' is inactive.")
        }
        String endpointAuthConfigId = normalize(nsRestletConfig?.nsAuthConfigId)
        if (endpointAuthConfigId && endpointAuthConfigId != sourceConfigId) {
            ec.message.addError("${sourceLabel} NetSuite endpoint is configured for ${endpointAuthConfigId}, not ${sourceConfigId}.")
        }
    }

    static List<Map<String, Object>> buildRuleSetSystemOptions(def ec, Map<String, Object> sourceBySide) {
        [FILE_SIDE_1, FILE_SIDE_2].collect { String fileSide ->
            def source = sourceBySide[fileSide]
            if (!source) return null

            String systemEnumId = canonicalSystemEnumId(source.systemEnumId)
            def systemEnum = FacadeSupport.findEnum(ec,systemEnumId)
            def fileTypeEnum = FacadeSupport.findEnum(ec,source.fileTypeEnumId)
            def sourceTypeEnum = FacadeSupport.findEnum(ec, source.sourceTypeEnumId)
            def systemMessageRemote = source.systemMessageRemoteId ? ec.entity.find("moqui.service.message.SystemMessageRemote")
                    .condition("systemMessageRemoteId", source.systemMessageRemoteId)
                    .disableAuthz()
                    .useCache(false)
                    .one() : null
            def nsRestletConfig = source.nsRestletConfigId ? ec.entity.find(DarpanEntityConstants.NS_RESTLET_CONFIG)
                    .condition("nsRestletConfigId", source.nsRestletConfigId)
                    .disableAuthz()
                    .useCache(false)
                    .one() : null
            return [
                    fileSide          : fileSide,
                    enumId            : systemEnumId,
                    enumCode          : normalize(systemEnum?.enumCode),
                    description       : normalize(systemEnum?.description),
                    label             : resolveEnumLabel(ec, systemEnumId, source.systemEnumId as String),
                    fileTypeEnumId    : normalize(source.fileTypeEnumId),
                    fileTypeLabel     : fileTypeEnum ? FacadeSupport.enumLabel(fileTypeEnum) : null,
                    idFieldExpression : normalize(source.primaryIdExpression),
                    schemaFileName    : normalize(source.schemaFileName),
                    sourceTypeEnumId  : normalize(source.sourceTypeEnumId),
                    sourceTypeLabel   : sourceTypeEnum ? FacadeSupport.enumLabel(sourceTypeEnum) : null,
                    systemMessageRemoteId   : normalize(source.systemMessageRemoteId),
                    systemMessageRemoteLabel: normalize(systemMessageRemote?.description) ?:
                            virtualSystemRemoteLabel(systemEnumId, source.systemMessageRemoteId, source.sourceConfigType) ?:
                            normalize(systemMessageRemote?.systemMessageRemoteId),
                    nsRestletConfigId       : normalize(source.nsRestletConfigId),
                    nsRestletConfigLabel    : normalize(nsRestletConfig?.description) ?: normalize(nsRestletConfig?.nsRestletConfigId),
                    sourceConfigId          : normalize(source.sourceConfigId),
                    sourceConfigType        : normalize(source.sourceConfigType),
            ]
        }.findAll { it != null } as List<Map<String, Object>>
    }

    static String resolveEnumLabel(def ec, Object enumId, String fallback = null) {
        String normalized = normalize(enumId)
        if (!normalized) return fallback
        def enumValue = FacadeSupport.findEnum(ec, normalized)
        return enumValue ? FacadeSupport.enumLabel(enumValue) : (fallback ?: normalized)
    }

    static boolean isVirtualHotWaxOrdersRemote(Object systemEnumId, Object systemMessageRemoteId, Object sourceConfigType) {
        if (canonicalSystemEnumId(systemEnumId) != SYSTEM_HOTWAX_OMS) return false
        if (normalize(systemMessageRemoteId) != HOTWAX_ORDERS_REMOTE_ID) return false
        String normalizedSourceConfigType = normalize(sourceConfigType)
        return !normalizedSourceConfigType || normalizedSourceConfigType == SOURCE_CONFIG_TYPE_HOTWAX_OMS_REST
    }

    static String virtualSystemRemoteLabel(Object systemEnumId, Object systemMessageRemoteId, Object sourceConfigType) {
        if (isVirtualHotWaxOrdersRemote(systemEnumId, systemMessageRemoteId, sourceConfigType)) {
            return HOTWAX_ORDERS_ENDPOINT_LABEL
        }
        if (isVirtualShopifyOrdersRemote(systemEnumId, systemMessageRemoteId, sourceConfigType)) {
            return SHOPIFY_ORDERS_ENDPOINT_LABEL
        }
        return null
    }

    static boolean isVirtualApiOrdersRemote(Object systemEnumId, Object systemMessageRemoteId, Object sourceConfigType) {
        return isVirtualHotWaxOrdersRemote(systemEnumId, systemMessageRemoteId, sourceConfigType) ||
                isVirtualShopifyOrdersRemote(systemEnumId, systemMessageRemoteId, sourceConfigType)
    }

    static def ensureVirtualHotWaxOrdersRemote(def ec, Object systemEnumId, Object systemMessageRemoteId, Object sourceConfigType) {
        if (!isVirtualHotWaxOrdersRemote(systemEnumId, systemMessageRemoteId, sourceConfigType)) return null
        def existing = ec.entity.find("moqui.service.message.SystemMessageRemote")
                .condition("systemMessageRemoteId", HOTWAX_ORDERS_REMOTE_ID)
                .disableAuthz()
                .useCache(false)
                .one()
        if (existing) return existing

        ec.service.sync()
                .name("store#moqui.service.message.SystemMessageRemote")
                .parameters([
                        systemMessageRemoteId: HOTWAX_ORDERS_REMOTE_ID,
                        description          : HOTWAX_ORDERS_ENDPOINT_LABEL,
                        sendUrl              : "{baseUrl}/rest/s1/oms/orders",
                        sendServiceName      : HOTWAX_OMS_ORDERS_EXTRACT_SERVICE,
                ])
                .disableAuthz()
                .call()
        return ec.entity.find("moqui.service.message.SystemMessageRemote")
                .condition("systemMessageRemoteId", HOTWAX_ORDERS_REMOTE_ID)
                .disableAuthz()
                .useCache(false)
                .one()
    }

    static boolean isVirtualShopifyOrdersRemote(Object systemEnumId, Object systemMessageRemoteId, Object sourceConfigType) {
        if (canonicalSystemEnumId(systemEnumId) != SYSTEM_SHOPIFY) return false
        if (normalize(systemMessageRemoteId) != SHOPIFY_ORDERS_REMOTE_ID) return false
        String normalizedSourceConfigType = normalize(sourceConfigType)
        return !normalizedSourceConfigType || normalizedSourceConfigType == SOURCE_CONFIG_TYPE_SHOPIFY_AUTH
    }

    static def ensureVirtualShopifyOrdersRemote(def ec, Object systemEnumId, Object systemMessageRemoteId, Object sourceConfigType) {
        if (!isVirtualShopifyOrdersRemote(systemEnumId, systemMessageRemoteId, sourceConfigType)) return null
        def existing = ec.entity.find("moqui.service.message.SystemMessageRemote")
                .condition("systemMessageRemoteId", SHOPIFY_ORDERS_REMOTE_ID)
                .disableAuthz()
                .useCache(false)
                .one()
        if (existing) return existing

        ec.service.sync()
                .name("store#moqui.service.message.SystemMessageRemote")
                .parameters([
                        systemMessageRemoteId: SHOPIFY_ORDERS_REMOTE_ID,
                        description          : SHOPIFY_ORDERS_ENDPOINT_LABEL,
                        sendUrl              : "https://{shop}.myshopify.com/admin/api/{apiVersion}/graphql.json",
                        sendServiceName      : SHOPIFY_GRAPHQL_EXECUTE_SERVICE,
                ])
                .disableAuthz()
                .call()
        return ec.entity.find("moqui.service.message.SystemMessageRemote")
                .condition("systemMessageRemoteId", SHOPIFY_ORDERS_REMOTE_ID)
                .disableAuthz()
                .useCache(false)
                .one()
    }

    static def ensureVirtualApiOrdersRemote(def ec, Object systemEnumId, Object systemMessageRemoteId, Object sourceConfigType) {
        return ensureVirtualHotWaxOrdersRemote(ec, systemEnumId, systemMessageRemoteId, sourceConfigType) ?:
                ensureVirtualShopifyOrdersRemote(ec, systemEnumId, systemMessageRemoteId, sourceConfigType)
    }

    /**
     * Non-cached enum lookup used by XML service callers that need fresh reads
     * (e.g. after upgrade-data inserts in tests). FacadeSupport.findEnum is cached.
     */
    static def findEnum(def ec, Object enumId) {
        String normalized = normalize(enumId)
        if (!normalized) return null
        return ec.entity.find("moqui.basic.Enumeration")
                .condition("enumId", normalized)
                .disableAuthz()
                .useCache(false)
                .one()
    }

    static String canonicalSystemEnumId(Object systemEnumId) {
        String normalized = normalize(systemEnumId)
        if (!normalized) return null

        String lookupKey = normalized.replaceAll(/[\s_-]/, "").toUpperCase()
        switch (lookupKey) {
            case "DARSYSOMS":
            case "HOTWAX":
            case "OMS":
                return SYSTEM_HOTWAX_OMS
            case "DARSYSSHOPIFY":
            case "SHOPIFY":
                return SYSTEM_SHOPIFY
            case "DARSYSNETSUITE":
            case "NETSUITE":
                return SYSTEM_NETSUITE
            case "DARSYSSAPI":
            case "SAPI":
                return SYSTEM_SAPI
            default:
                return normalized
        }
    }

    protected static boolean savedRunMatches(Map<String, Object> row, String search) {
        return [
                row.savedRunId,
                row.runName,
                row.description,
                row.companyUserGroupId,
                row.companyLabel,
                row.runType,
                row.reconciliationMappingId,
                row.ruleSetId,
                row.compareScopeId,
                row.compareScopeDescription,
                *(row.systemOptions instanceof Collection ? row.systemOptions.collect { it?.label } : []),
                *(row.systemOptions instanceof Collection ? row.systemOptions.collect { it?.enumCode } : []),
        ].any { Object value ->
            value?.toString()?.toLowerCase()?.contains(search)
        }
    }
}
