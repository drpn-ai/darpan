package darpan.facade.reconciliation

import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import groovy.json.JsonSlurper
import reconciliation.rule.RuleEngineSupport

import java.sql.Connection
import java.sql.Statement

class ReconciliationSavedRunSupport {
    static final String RULE_SET_COMPARE_SCOPE_ENTITY_NAME = "darpan.rule.RuleSetCompareScope"
    static final String RUN_TYPE_MAPPING = "mapping"
    static final String RUN_TYPE_RULESET = "ruleset"
    static final String FILE_SIDE_1 = "FILE_1"
    static final String FILE_SIDE_2 = "FILE_2"
    static final String DEFAULT_FILE_TYPE_ENUM_ID = "DftCsv"
    static final String SOURCE_TYPE_API = "AUT_SRC_API"
    static final String SYSTEM_SHOPIFY = "SHOPIFY"
    static final String SYSTEM_HOTWAX_OMS = "OMS"
    static final String SYSTEM_NETSUITE = "NETSUITE"
    static final String SOURCE_CONFIG_TYPE_SHOPIFY_AUTH = "SHOPIFY_AUTH"
    static final String SOURCE_CONFIG_TYPE_HOTWAX_OMS_REST = "HOTWAX_OMS_REST"
    static final String SOURCE_CONFIG_TYPE_NETSUITE_AUTH = "NETSUITE_AUTH"
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
            String ruleId = FacadeSupport.normalize(rule.ruleId)
            String ruleText = FacadeSupport.normalize(rule.ruleText)
            String ruleLogic = FacadeSupport.normalize(rule.ruleLogic)
            String ruleType = FacadeSupport.normalize(rule.ruleType)
            String expression = FacadeSupport.normalize(rule.expression)
            String enabled = FacadeSupport.normalize(rule.enabled)?.toUpperCase() == "N" ? "N" : "Y"
            String severity = FacadeSupport.normalize(rule.severity)
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
        if (rawValue == null) return null
        if (rawValue instanceof Number) {
            return ((Number) rawValue).intValue()
        }

        String normalized = FacadeSupport.normalize(rawValue)
        if (!normalized) return null
        try {
            return Integer.valueOf(normalized)
        } catch (Exception ignored) {
            return null
        }
    }

    static String normalizeJsonPrimaryIdExpression(Object rawExpression) {
        String normalized = FacadeSupport.normalize(rawExpression)
        if (!normalized) return null

        int suffixIndex = normalized.indexOf("|")
        String baseExpression = suffixIndex >= 0 ? FacadeSupport.normalize(normalized.substring(0, suffixIndex)) : normalized
        String suffix = suffixIndex >= 0 ? FacadeSupport.normalize(normalized.substring(suffixIndex + 1)) : null

        String normalizedBase = ReconciliationMappingSupport.normalizeMappingJsonIdExpression(baseExpression) ?: baseExpression
        return suffix ? "${normalizedBase}|${suffix}" : normalizedBase
    }

    static boolean ensureRuleSetCompareScopeObjectTypeNullable(def ec) {
        String groupName = ec?.entity?.getEntityGroupName(RULE_SET_COMPARE_SCOPE_ENTITY_NAME) ?: "default"
        def datasourceFactory = ec?.entity?.getDatasourceFactory(groupName)
        datasourceFactory?.checkAndAddTable(RULE_SET_COMPARE_SCOPE_ENTITY_NAME)

        def entityDefinition = ec?.entity?.getEntityDefinition(RULE_SET_COMPARE_SCOPE_ENTITY_NAME)
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

            String isNullable = FacadeSupport.normalize(columnMetadata.isNullable)?.toUpperCase()
            if (isNullable == "YES") return true

            String databaseProductName = FacadeSupport.normalize(metadata?.databaseProductName)?.toLowerCase()
            String typeName = FacadeSupport.normalize(columnMetadata.typeName) ?: "VARCHAR"
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
        String normalizedRuleSetId = RuleEngineSupport.sanitizeIdToken(FacadeSupport.normalize(ruleSetId) ?: "RULE_SET", "RS")
        if (!normalizedRuleSetId.startsWith("RS_")) normalizedRuleSetId = "RS_${normalizedRuleSetId}"
        String scopeSuffix = RuleEngineSupport.sanitizeIdToken(FacadeSupport.normalize(scopeKind) ?: "COMPARE_SCOPE", "COMPARE_SCOPE")
        String candidate = buildEntityId("CS", normalizedRuleSetId, scopeSuffix)
        int suffix = 1

        while (ec.entity.find("darpan.rule.RuleSetCompareScope")
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
        String normalizedPrefix = FacadeSupport.normalize(prefix) ?: "ID"
        String normalizedBodyToken = FacadeSupport.normalize(bodyToken) ?: "VALUE"
        String normalizedTrailingToken = FacadeSupport.normalize(trailingToken)
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
        String description = FacadeSupport.normalize(compareScopeDescription)
        if (description) return description
        return FacadeSupport.normalize(compareScopeId)
    }

    static boolean savedRunIdExists(def ec, String savedRunId) {
        String normalized = FacadeSupport.normalize(savedRunId)
        if (!normalized) return false

        return ec.entity.find("darpan.rule.RuleSet")
                .condition("ruleSetId", normalized)
                .disableAuthz()
                .useCache(false)
                .one() != null ||
                ec.entity.find("darpan.mapping.ReconciliationMapping")
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
            String leftName = FacadeSupport.normalize(left.runName) ?: FacadeSupport.normalize(left.savedRunId) ?: ""
            String rightName = FacadeSupport.normalize(right.runName) ?: FacadeSupport.normalize(right.savedRunId) ?: ""
            int nameCompare = leftName <=> rightName
            if (nameCompare != 0) return nameCompare
            return (FacadeSupport.normalize(left.savedRunId) ?: "") <=> (FacadeSupport.normalize(right.savedRunId) ?: "")
        }
    }

    static Map<String, Object> listSavedRuns(def ec, Object query, Object pageIndex, Object pageSize) {
        int page = Math.max(0, FacadeSupport.normalizeInt(pageIndex, 0))
        int size = Math.max(1, Math.min(200, FacadeSupport.normalizeInt(pageSize, 20)))
        String search = FacadeSupport.normalize(query)?.toLowerCase()

        List<Map<String, Object>> rows = collectSavedRunRows(ec)
        if (search) rows = rows.findAll { Map<String, Object> row -> savedRunMatches(row, search) }

        Map<String, Object> pagination = pagination(page, size, rows.size())
        List<Map<String, Object>> pagedRows = pageRows(rows, page, size)
        Set<String> availableSavedRunIds = rows.collect { it.savedRunId as String }.findAll { it } as Set<String>

        Map<String, Object> envelope = FacadeSupport.envelope(ec)
        return envelope + [
                savedRuns        : pagedRows,
                pinnedSavedRunIds: ReconciliationDashboardPreferenceSupport.listPinnedSavedRunIds(ec, availableSavedRunIds),
                pagination       : pagination,
        ]
    }

    static List<Map<String, Object>> collectMappingRows(def ec) {
        def mappingFinder = ec.entity.find("darpan.mapping.ReconciliationMapping")
                .orderBy("mappingName,reconciliationMappingId")
                .disableAuthz()
                .useCache(false)
        List<Map<String, Object>> rows = []

        (mappingFinder.list() ?: []).each { mapping ->
            if (!TenantAccessSupport.canAccessTenantRecord(ec, mapping)) return

            List mappingMembers = ec.entity.find("darpan.mapping.ReconciliationMappingMember")
                    .condition("reconciliationMappingId", mapping.reconciliationMappingId)
                    .disableAuthz()
                    .useCache(false)
                    .list() ?: []
            List<String> mappingReadinessIssues = ReconciliationMappingSupport.collectReadinessIssues(ec, mappingMembers)
            if (!mappingReadinessIssues.isEmpty()) return

            List<Map<String, Object>> systemOptions = mappingMembers.collect { member ->
                def systemEnum = findEnum(ec, member.systemEnumId)
                def fileTypeEnum = findEnum(ec, member.fileTypeEnumId)
                [
                        enumId           : FacadeSupport.normalize(member.systemEnumId),
                        enumCode         : FacadeSupport.normalize(systemEnum?.enumCode),
                        description      : FacadeSupport.normalize(systemEnum?.description),
                        label            : resolveEnumLabel(ec, member.systemEnumId, member.systemEnumId as String),
                        sequenceNum      : systemEnum?.sequenceNum ?: Integer.MAX_VALUE,
                        fileTypeEnumId   : FacadeSupport.normalize(member.fileTypeEnumId),
                        fileTypeLabel    : fileTypeEnum ? FacadeSupport.enumLabel(fileTypeEnum) : null,
                        idFieldExpression: FacadeSupport.normalize(member.idFieldExpression ?: member.systemFieldName),
                        schemaFileName   : FacadeSupport.normalize(member.schemaFileName),
                ]
            }.sort { Map<String, Object> left, Map<String, Object> right ->
                (left.sequenceNum <=> right.sequenceNum) ?:
                        ((left.label ?: "") <=> (right.label ?: "")) ?:
                        ((left.enumId ?: "") <=> (right.enumId ?: ""))
            }

            List<String> systemIds = systemOptions.collect { it.enumId as String }.findAll { it }.unique()
            rows.add([
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
            ])
        }

        return rows
    }

    static List<Map<String, Object>> collectRuleSetRows(def ec) {
        def ruleSetFinder = ec.entity.find("darpan.rule.RuleSet")
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
        String savedRunId = FacadeSupport.normalize(rawSavedRunId)
        if (!savedRunId) return [savedRun: null, error: null]

        def ruleSet = ec.entity.find("darpan.rule.RuleSet")
                .condition("ruleSetId", savedRunId)
                .disableAuthz()
                .useCache(false)
                .one()
        if (!ruleSet) return [savedRun: null, error: null]
        if (!TenantAccessSupport.canAccessTenantRecord(ec, ruleSet)) {
            return [savedRun: null, error: "Saved run '${savedRunId}' is not available in your active tenant."]
        }

        List compareScopes = ec.entity.find("darpan.rule.RuleSetCompareScope")
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
            String fileSide = FacadeSupport.normalize(source.fileSide)?.toUpperCase()
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
                runName                 : FacadeSupport.normalize(ruleSet.ruleSetName) ?: FacadeSupport.normalize(ruleSet.ruleSetId),
                description             : FacadeSupport.normalize(ruleSet.description),
                companyUserGroupId      : FacadeSupport.normalize(ruleSet.companyUserGroupId),
                companyLabel            : TenantAccessSupport.resolveTenantLabelForUserGroupId(ec, ruleSet.companyUserGroupId),
                runType                 : RUN_TYPE_RULESET,
                reconciliationMappingId : null,
                ruleSetId               : ruleSet.ruleSetId,
                compareScopeId          : compareScope.compareScopeId,
                compareScopeDescription : compareScopeDisplayName(compareScope.compareScopeId, compareScope.description),
                requiresSystemSelection : false,
                defaultFile1SystemEnumId: FacadeSupport.normalize(sourceBySide[FILE_SIDE_1]?.systemEnumId),
                defaultFile2SystemEnumId: FacadeSupport.normalize(sourceBySide[FILE_SIDE_2]?.systemEnumId),
                systemOptions           : resolvedSystemOptions,
                rules                   : collectRuleRows(ec, ruleSet.ruleSetId),
        ]
    }

    static List<Map<String, Object>> collectRuleRows(def ec, Object rawRuleSetId) {
        String ruleSetId = FacadeSupport.normalize(rawRuleSetId)
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
                    ruleId        : FacadeSupport.normalize(rule.ruleId),
                    sequenceNum   : rule.sequenceNum,
                    ruleText      : FacadeSupport.normalize(rule.ruleText),
                    ruleLogic     : FacadeSupport.normalize(rule.ruleLogic),
                    ruleType      : FacadeSupport.normalize(rule.ruleType),
                    expression    : FacadeSupport.normalize(rule.expression),
                    enabled       : FacadeSupport.normalize(rule.enabled),
                    severity      : FacadeSupport.normalize(rule.severity),
                    file1FieldPath: FacadeSupport.normalize(expressionData.file1FieldPath),
                    file2FieldPath: FacadeSupport.normalize(expressionData.file2FieldPath),
                    operator      : FacadeSupport.normalize(expressionData.operator),
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
        String value = FacadeSupport.normalize(rawPreAction)?.toUpperCase()
        if (value in ["STRING_TO_INTEGER", "TO_INT", "TO_INTEGER"]) return "STRING_TO_INT"
        if (value == "TO_NUMBER") return "STRING_TO_NUMBER"
        return value in ["STRING_TO_INT", "STRING_TO_NUMBER"] ? value : null
    }

    static String normalizePreActionFieldSide(Object rawFieldSide) {
        String value = FacadeSupport.normalize(rawFieldSide)?.toLowerCase()
        if (value in ["file1", "file_1", "left"]) return "file1"
        if (value in ["file2", "file_2", "right"]) return "file2"
        return null
    }

    static Map<String, Object> parseRuleExpression(Object rawExpression) {
        String expression = FacadeSupport.normalize(rawExpression)
        if (!expression) return [:]

        try {
            Object parsed = new JsonSlurper().parseText(expression)
            return parsed instanceof Map ? (Map<String, Object>) parsed : [:]
        } catch (Exception ignored) {
            return [:]
        }
    }

    static Map<String, Object> resolveApiSourceConfig(def ec, String sourceLabel, Object rawSystemEnumId,
            Object rawSourceConfigId, Object rawSourceConfigType, Object rawNsRestletConfig = null) {
        String systemEnumId = FacadeSupport.normalize(rawSystemEnumId)
        String sourceConfigId = FacadeSupport.normalize(rawSourceConfigId)
        String sourceConfigType = FacadeSupport.normalize(rawSourceConfigType)
        def nsRestletConfig = rawNsRestletConfig

        if (!sourceConfigId && systemEnumId == SYSTEM_NETSUITE && nsRestletConfig) {
            sourceConfigId = FacadeSupport.normalize(nsRestletConfig.nsAuthConfigId)
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
        switch (FacadeSupport.normalize(rawSystemEnumId)) {
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
        def config = ec.entity.find("darpan.shopify.ShopifyAuthConfig")
                .condition("shopifyAuthConfigId", sourceConfigId)
                .disableAuthz()
                .useCache(false)
                .one()
        TenantAccessSupport.requireTenantRecordAccess(ec, config,
                "${sourceLabel} Shopify auth config '${sourceConfigId}' was not found.",
                "${sourceLabel} Shopify auth config '${sourceConfigId}' is not available in your active tenant.")
        if (config && FacadeSupport.normalize(config.isActive) == "N") {
            ec.message.addError("${sourceLabel} Shopify auth config '${sourceConfigId}' is inactive.")
        }
        if (config && !FacadeSupport.normalizeBool(config.canReadOrders, false)) {
            ec.message.addError("${sourceLabel} Shopify auth config '${sourceConfigId}' cannot read orders.")
        }
    }

    protected static void validateHotWaxOmsConfig(def ec, String sourceLabel, String sourceConfigId) {
        def config = ec.entity.find("darpan.hotwax.HotWaxOmsRestSourceConfig")
                .condition("omsRestSourceConfigId", sourceConfigId)
                .disableAuthz()
                .useCache(false)
                .one()
        TenantAccessSupport.requireTenantRecordAccess(ec, config,
                "${sourceLabel} HotWax source config '${sourceConfigId}' was not found.",
                "${sourceLabel} HotWax source config '${sourceConfigId}' is not available in your active tenant.")
        if (config && FacadeSupport.normalize(config.isActive) == "N") {
            ec.message.addError("${sourceLabel} HotWax source config '${sourceConfigId}' is inactive.")
        }
        if (config && !FacadeSupport.normalizeBool(config.canReadOrders, false)) {
            ec.message.addError("${sourceLabel} HotWax source config '${sourceConfigId}' cannot read orders.")
        }
    }

    protected static void validateNetSuiteAuthConfig(def ec, String sourceLabel, String sourceConfigId, def nsRestletConfig) {
        def config = ec.entity.find("darpan.reconciliation.NsAuthConfig")
                .condition("nsAuthConfigId", sourceConfigId)
                .disableAuthz()
                .useCache(false)
                .one()
        TenantAccessSupport.requireTenantRecordAccess(ec, config,
                "${sourceLabel} NetSuite auth config '${sourceConfigId}' was not found.",
                "${sourceLabel} NetSuite auth config '${sourceConfigId}' is not available in your active tenant.")
        if (config && FacadeSupport.normalize(config.isActive) == "N") {
            ec.message.addError("${sourceLabel} NetSuite auth config '${sourceConfigId}' is inactive.")
        }
        String endpointAuthConfigId = FacadeSupport.normalize(nsRestletConfig?.nsAuthConfigId)
        if (endpointAuthConfigId && endpointAuthConfigId != sourceConfigId) {
            ec.message.addError("${sourceLabel} NetSuite endpoint is configured for ${endpointAuthConfigId}, not ${sourceConfigId}.")
        }
    }

    static List<Map<String, Object>> buildRuleSetSystemOptions(def ec, Map<String, Object> sourceBySide) {
        [FILE_SIDE_1, FILE_SIDE_2].collect { String fileSide ->
            def source = sourceBySide[fileSide]
            if (!source) return null

            def systemEnum = findEnum(ec, source.systemEnumId)
            def fileTypeEnum = findEnum(ec, source.fileTypeEnumId)
            def sourceTypeEnum = findEnum(ec, source.sourceTypeEnumId)
            def systemMessageRemote = source.systemMessageRemoteId ? ec.entity.find("moqui.service.message.SystemMessageRemote")
                    .condition("systemMessageRemoteId", source.systemMessageRemoteId)
                    .disableAuthz()
                    .useCache(false)
                    .one() : null
            def nsRestletConfig = source.nsRestletConfigId ? ec.entity.find("darpan.reconciliation.NsRestletConfig")
                    .condition("nsRestletConfigId", source.nsRestletConfigId)
                    .disableAuthz()
                    .useCache(false)
                    .one() : null
            return [
                    fileSide          : fileSide,
                    enumId            : FacadeSupport.normalize(source.systemEnumId),
                    enumCode          : FacadeSupport.normalize(systemEnum?.enumCode),
                    description       : FacadeSupport.normalize(systemEnum?.description),
                    label             : resolveEnumLabel(ec, source.systemEnumId, source.systemEnumId as String),
                    fileTypeEnumId    : FacadeSupport.normalize(source.fileTypeEnumId),
                    fileTypeLabel     : fileTypeEnum ? FacadeSupport.enumLabel(fileTypeEnum) : null,
                    idFieldExpression : FacadeSupport.normalize(source.primaryIdExpression),
                    schemaFileName    : FacadeSupport.normalize(source.schemaFileName),
                    sourceTypeEnumId  : FacadeSupport.normalize(source.sourceTypeEnumId),
                    sourceTypeLabel   : sourceTypeEnum ? FacadeSupport.enumLabel(sourceTypeEnum) : null,
                    systemMessageRemoteId   : FacadeSupport.normalize(source.systemMessageRemoteId),
                    systemMessageRemoteLabel: FacadeSupport.normalize(systemMessageRemote?.description) ?: FacadeSupport.normalize(systemMessageRemote?.systemMessageRemoteId),
                    nsRestletConfigId       : FacadeSupport.normalize(source.nsRestletConfigId),
                    nsRestletConfigLabel    : FacadeSupport.normalize(nsRestletConfig?.description) ?: FacadeSupport.normalize(nsRestletConfig?.nsRestletConfigId),
                    sourceConfigId          : FacadeSupport.normalize(source.sourceConfigId),
                    sourceConfigType        : FacadeSupport.normalize(source.sourceConfigType),
            ]
        }.findAll { it != null } as List<Map<String, Object>>
    }

    static String resolveEnumLabel(def ec, Object enumId, String fallback = null) {
        String normalized = FacadeSupport.normalize(enumId)
        if (!normalized) return fallback
        def enumValue = findEnum(ec, normalized)
        return enumValue ? FacadeSupport.enumLabel(enumValue) : (fallback ?: normalized)
    }

    static def findEnum(def ec, Object enumId) {
        String normalized = FacadeSupport.normalize(enumId)
        if (!normalized) return null
        return ec.entity.find("moqui.basic.Enumeration")
                .condition("enumId", normalized)
                .disableAuthz()
                .useCache(true)
                .one()
    }

    static Map<String, Object> pagination(int page, int size, int totalCount) {
        return [
                pageIndex : page,
                pageSize  : size,
                totalCount: totalCount,
                pageCount : Math.max(1, Math.ceil(totalCount / (double) size) as int),
        ]
    }

    static List<Map<String, Object>> pageRows(List<Map<String, Object>> rows, int page, int size) {
        int totalCount = rows.size()
        int fromIndex = Math.min(page * size, totalCount)
        int toIndex = Math.min(fromIndex + size, totalCount)
        return rows.subList(fromIndex, toIndex)
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
