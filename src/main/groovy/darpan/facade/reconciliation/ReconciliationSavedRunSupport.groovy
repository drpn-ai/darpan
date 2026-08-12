package darpan.facade.reconciliation

import darpan.common.DarpanEntityConstants
import darpan.reconciliation.automation.SourceSystemConnectorSupport
import darpan.reconciliation.core.CompareIdExpressionSupport
import darpan.reconciliation.source.SourceFilterSupport
import darpan.facade.common.FacadeSupport
import darpan.facade.common.PaginationSupport
import darpan.facade.common.SharedConfigAccessSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import reconciliation.rule.FieldComparisonRuleLogicGenerator
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

    /**
     * Run a long-lived saved-run body OUTSIDE any caller transaction. The JSON-RPC/screen request
     * path can wrap the whole service call in the request's JTA transaction, whose default 60s
     * timeout is far shorter than a real extract; when Bitronix marks that transaction
     * rollback-only mid-run, every later entity touch fails and the run's completed work rolls
     * back invisibly (UAT 2026-07-27: every run died exactly at the EXTRACT_FILE2 boundary with
     * the run + step rows erased). Suspending first lets the run own its transactional life —
     * observability writes commit live in their own short transactions — and the caller's
     * transaction is ALWAYS resumed for the response path. Mirrors the suspend/resume pattern in
     * the TransactionFacade javadoc. A failed suspend is non-fatal: the work still runs, at worst
     * inside the caller's transaction exactly as before this guard existed.
     */
    static Object runDetachedFromCallerTransaction(def ec, Closure<?> work) {
        return darpan.common.TransactionDetachSupport.runDetachedFromCallerTransaction(ec, work)
    }

    static final String RUN_TYPE_MAPPING = "mapping"
    static final String RUN_TYPE_RULESET = "ruleset"
    static final String FILE_SIDE_1 = "FILE_1"
    static final String FILE_SIDE_2 = "FILE_2"
    static final String DEFAULT_FILE_TYPE_ENUM_ID = "DftCsv"
    static final String SOURCE_TYPE_API = "AUT_SRC_API"
    static final String SOURCE_TYPE_DB = "AUT_SRC_DB"
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
    // DAR-BE-018: the same OMS sales orders served by /rest/s1/oms/reconciliationOrders. A second
    // connector row rather than a repoint, so the shipped OMS path stays available as a fallback
    // while parity is proven. Its system id and config type must both differ from the OMS row's —
    // the registry resolvers take the first enabled match on each (see SourceSystemConnectorSeedData).
    static final String SYSTEM_HOTWAX_OMS_RECON = "OMS_RECON_ORDERS"
    static final String SOURCE_CONFIG_TYPE_HOTWAX_OMS_REST_RECON = "HOTWAX_OMS_REST_RECON"
    static final String HOTWAX_RECON_ORDERS_ENDPOINT_LABEL = "Reconciliation Orders API"
    static final String HOTWAX_OMS_RECON_ORDERS_EXTRACT_SERVICE = "reconciliation.HotWaxOmsExtractionServices.extract#HotWaxOmsReconciliationOrders"
    static final String SHOPIFY_ORDERS_REMOTE_ID = "SHOPIFY_REMOTE"
    static final String SHOPIFY_ORDERS_ENDPOINT_LABEL = "Admin GraphQL Orders"
    static final String SHOPIFY_GRAPHQL_EXECUTE_SERVICE = "facade.ShopifyFacadeServices.execute#ShopifyGraphql"
    static final String SHOPIFY_ORDERS_EXTRACT_SERVICE = "reconciliation.ShopifyOrderExtractionServices.extract#ShopifyOrders"
    static final String SHOPIFY_ORDER_IDS_LOOKUP_SERVICE = "reconciliation.ShopifyOrderExtractionServices.lookup#ShopifyOrderIds"
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

    // Audit 2026-06-11 #1 (durable fix): for FIELD_COMPARISON rules, regenerate ruleLogic SERVER-SIDE
    // from the structured expression + the run's (raw, UI-equivalent) primary-ID expressions, replacing
    // whatever the client sent. The output is byte-identical to the UI generator (locked by
    // FieldComparisonRuleLogicGeneratorTests), so behaviour is unchanged — but a client cannot smuggle
    // arbitrary DRL/MVEL through ruleLogic for these rules; only escaped field segments/operator/severity
    // from the expression survive. Non-FIELD_COMPARISON rules (and any malformed expression) are left
    // as-is and remain gated by RuleLogicValidator at save#Rule.
    static List<Map<String, Object>> regenerateFieldComparisonRuleLogic(List ruleEntries,
            Object file1PrimaryIdExpression, Object file2PrimaryIdExpression) {
        if (!ruleEntries) return ruleEntries as List<Map<String, Object>>
        String p1 = normalize(file1PrimaryIdExpression)
        String p2 = normalize(file2PrimaryIdExpression)
        List<Map<String, Object>> result = []
        ruleEntries.eachWithIndex { Object rawEntry, int idx ->
            if (!(rawEntry instanceof Map)) { result.add((Map<String, Object>) rawEntry); return }
            Map<String, Object> entry = new LinkedHashMap<String, Object>((Map) rawEntry)
            if (normalize(entry.ruleType) == FieldComparisonRuleLogicGenerator.FIELD_COMPARISON_TYPE && normalize(entry.expression)) {
                String regenerated = FieldComparisonRuleLogicGenerator.generate(
                        (String) normalize(entry.expression), p1, p2,
                        (String) normalize(entry.ruleId), (String) normalize(entry.severity), idx)
                if (regenerated) entry.ruleLogic = regenerated
            }
            result.add(entry)
        }
        return result
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

        while (TenantScopedFinder.findGlobalUnscoped(ec, DarpanEntityConstants.RULE_SET_COMPARE_SCOPE,
                        "ID uniqueness check — must scan all tenants to avoid PK collision")
                .condition("compareScopeId", candidate)
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

        // ID uniqueness check — must scan all tenants to avoid PK collision across tenant spaces.
        return TenantScopedFinder.findGlobalUnscoped(ec, DarpanEntityConstants.RULE_SET,
                        "ID uniqueness check — must scan all tenants to avoid PK collision")
                .condition("ruleSetId", normalized)
                .useCache(false)
                .one() != null ||
                TenantScopedFinder.findGlobalUnscoped(ec, DarpanEntityConstants.RECONCILIATION_MAPPING,
                        "ID uniqueness check — must scan all tenants to avoid PK collision")
                        .condition("reconciliationMappingId", normalized)
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

        def mapping = TenantScopedFinder.findTenantScoped(ec, DarpanEntityConstants.RECONCILIATION_MAPPING)
                .condition("reconciliationMappingId", savedRunId)
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
        def mappingFinder = TenantScopedFinder.findTenantScoped(ec, DarpanEntityConstants.RECONCILIATION_MAPPING)
                .orderBy("mappingName,reconciliationMappingId")
                .useCache(false)
        List<Map<String, Object>> rows = []

        (mappingFinder.list() ?: []).each { mapping ->
            List mappingMembers = TenantScopedFinder.findGlobalUnscoped(ec, DarpanEntityConstants.RECONCILIATION_MAPPING_MEMBER,
                            "transitively-owned mapping members; parent mapping already tenant-gated by findTenantScoped above")
                    .condition("reconciliationMappingId", mapping.reconciliationMappingId)
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
            members = TenantScopedFinder.findGlobalUnscoped(ec, DarpanEntityConstants.RECONCILIATION_MAPPING_MEMBER,
                            "transitively-owned mapping members; parent mapping access verified by canAccessTenantRecord caller")
                    .condition("reconciliationMappingId", mapping.reconciliationMappingId)
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
        def ruleSetFinder = TenantScopedFinder.findTenantScoped(ec, DarpanEntityConstants.RULE_SET)
                .orderBy("ruleSetName,ruleSetId")
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

        def ruleSet = TenantScopedFinder.findTenantScoped(ec, DarpanEntityConstants.RULE_SET)
                .condition("ruleSetId", savedRunId)
                .useCache(false)
                .one()
        if (!ruleSet) return [savedRun: null, error: null]
        if (!TenantAccessSupport.canAccessTenantRecord(ec, ruleSet)) {
            return [savedRun: null, error: "Saved run '${savedRunId}' is not available in your active tenant."]
        }

        // RuleSetCompareScope is transitively-owned via ruleSetId; gate via findTenantScopedChildren.
        def compareScopeFinder = TenantScopedFinder.findTenantScopedChildren(
                ec, DarpanEntityConstants.RULE_SET_COMPARE_SCOPE,
                DarpanEntityConstants.RULE_SET, "ruleSetId", savedRunId, "ruleSetId")
        List compareScopes = compareScopeFinder ?
                compareScopeFinder.orderBy("compareScopeId").useCache(false).list() ?: [] : []
        if (compareScopes.size() != 1) {
            return [savedRun: null, error: "RuleSet ${savedRunId} defines ${compareScopes.size()} compare scopes; saved RuleSet runs currently require exactly one compare scope."]
        }

        def compareScope = compareScopes[0]
        String compareScopeLabel = compareScopeDisplayName(compareScope.compareScopeId, compareScope.description)
        // RuleSetCompareSource is transitively tenant-owned (compareScopeId → RuleSetCompareScope → RuleSet).
        // The compareScope above is already gated to the active-tenant RuleSet (lines 455-472), and
        // RuleSetCompareScope has NO companyUserGroupId of its own, so it cannot be by-id gated directly
        // (findTenantScopedChildren on it would read a non-existent companyUserGroupId field). Read the
        // sources by the already-validated compareScopeId; the upstream RuleSet gate is the tenant boundary.
        def sourceFinder = TenantScopedFinder.findGlobalUnscoped(
                ec, "darpan.rule.RuleSetCompareSource",
                "transitively tenant-owned; compareScopeId pre-gated via the active-tenant RuleSet above")
                .condition("compareScopeId", compareScope.compareScopeId)
        List sources = sourceFinder.useCache(false).list() ?: []
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
                // Wire-shape exclusion filters (values as a List) for both sides, sourced from
                // whatever RuleSetCompareSourceFilter rows currently exist — this Map builder is the
                // one shared response path for create#RuleSetRun, save#RuleSetRun, list#SavedRuns, and
                // resolveRuleSetRun/findSavedRunById, so wiring it here covers every "load" surface.
                file1ExcludeFilters     : buildExcludeFilterResponse(ec, sourceBySide[FILE_SIDE_1]),
                file2ExcludeFilters     : buildExcludeFilterResponse(ec, sourceBySide[FILE_SIDE_2]),
        ]
    }

    /**
     * Top-level record fields the rule set reads on one side, for the extract projection.
     *
     * A rule's path is accepted ONLY when it is provably relative to a single record: either it
     * carries no "[*]" wildcard at all, or it begins with the SAME "&lt;recordArray&gt;[*]" prefix the
     * side's own {@code primaryIdExpression} uses (e.g. "records[*]" from "$.records[*].orderId") —
     * mirroring {@link reconciliation.rule.FieldComparisonRuleLogicGenerator#fieldPathRelativeToPrimary}.
     * Anything else returns null for the WHOLE side. {@code topLevelRecordField} alone is NOT this
     * gate: it strips up to the FIRST "[*]" unconditionally and returns whatever follows, so it cannot
     * tell a genuinely nested per-record wildcard (e.g. {@code $.shipGroups[*].facilityId}, or the
     * file-2 {@code $[*].orderItems[*].sku} convention) apart from a safe "$.records[*].field" path —
     * both shapes make it return a plausible-but-wrong field name instead of null. This method gates
     * on the primary-ID prefix FIRST and only delegates to {@code topLevelRecordField} to name the
     * field once a path has already been proven safe.
     *
     * Null means "do not project this side" — a rule silently evaluating against a field the
     * projection dropped would report false differences, which is a far worse outcome than
     * transferring full records. The conservative answer is per-side: a nested path on file 2 must
     * not disable projection on file 1. A row that carries a path for neither side — an
     * unparsed/opaque expression, e.g. a bare-condition {@code ruleLogic} with no structured
     * file1FieldPath/file2FieldPath — can't be verified safe either, so it disables projection too.
     * A row that carries a path for only the OTHER side makes no claim about this one and is skipped.
     * An unrecognized {@code fileSide} returns null rather than silently defaulting to file 1.
     */
    static List<String> ruleKeepFieldsForSide(List<Map<String, Object>> ruleRows, String fileSide, Object primaryIdExpression) {
        String side = normalize(fileSide)
        boolean isFile1 = "FILE_1".equalsIgnoreCase(side)
        boolean isFile2 = "FILE_2".equalsIgnoreCase(side)
        if (!isFile1 && !isFile2) return null

        String pathKey = isFile2 ? "file2FieldPath" : "file1FieldPath"
        String otherPathKey = isFile2 ? "file1FieldPath" : "file2FieldPath"
        String recordArrayPrefix = ruleFieldPathRecordArrayPrefix(primaryIdExpression)

        Set<String> fields = new LinkedHashSet<String>()
        for (Map<String, Object> row : (ruleRows ?: [])) {
            String rawPath = normalize(row?.get(pathKey))
            if (!rawPath) {
                if (normalize(row?.get(otherPathKey))) continue
                return null
            }
            if (!isRuleFieldPathSafeForProjection(rawPath, recordArrayPrefix)) return null
            String fieldName = topLevelRecordField(rawPath)
            if (!fieldName) return null
            fields.add(fieldName)
        }
        return new ArrayList<String>(fields)
    }

    /**
     * The "&lt;recordArray&gt;[*]" prefix a side's primaryIdExpression establishes (e.g. "records[*]"
     * from "$.records[*].orderId"), normalized the same way {@code topLevelRecordField} normalizes
     * internally so the comparison in {@link #isRuleFieldPathSafeForProjection} is apples to apples.
     * Null when the expression is blank or carries no wildcard — every wildcarded rule path is then
     * rejected for that side, since there is nothing to check it against.
     */
    private static String ruleFieldPathRecordArrayPrefix(Object primaryIdExpression) {
        String normalized = normalizedSparkPathForRuleGate(normalize(primaryIdExpression))
        if (!normalized) return null
        int starIndex = normalized.indexOf("[*]")
        return starIndex >= 0 ? normalized.substring(0, starIndex + 3) : null
    }

    /** True when a rule field path is provably relative to a single record — see {@link #ruleKeepFieldsForSide}. */
    private static boolean isRuleFieldPathSafeForProjection(String rawPath, String recordArrayPrefix) {
        String normalized = normalizedSparkPathForRuleGate(rawPath)
        if (normalized == null) return false
        if (!normalized.contains("[*]")) return true
        if (!recordArrayPrefix) return false
        return normalized.startsWith(recordArrayPrefix + ".")
    }

    /**
     * Mirrors the normalization {@code topLevelRecordField} applies internally (strip a trailing
     * |NORMALIZER, expand to full JSONPath form, then strip the "$."/"$[*]." root).
     */
    private static String normalizedSparkPathForRuleGate(String rawExpression) {
        if (!rawExpression) return null
        try {
            String idExpr = (String) CompareIdExpressionSupport.splitIdExpression(rawExpression).idExpr
            if (!idExpr) return null
            return CompareIdExpressionSupport.normalizeSparkPath(CompareIdExpressionSupport.normalizeJsonIdExpr(idExpr))
        } catch (Exception ignored) {
            return null
        }
    }

    /**
     * Fields the extract file must keep for one compare source: the connector's base
     * identity/display fields, every join-key field configured for the side, and every top-level
     * field the rule set's rules read on that side. Returns null (no projection — full records)
     * when any rule's path on this side cannot be reduced to a top-level record field — see
     * {@link #ruleKeepFieldsForSide}. A rule that carries no structured field path on either side
     * (a raw-DRL rule, or a presence-only rule) falls into that same case and disables projection
     * for both sides. Rule sets whose rules all resolve to top-level fields on this side (the
     * common shape) still get the size win.
     */
    static List<String> resolveExtractKeepFields(def ec, Object source, Object baseFieldsCsv) {
        String compareScopeId = normalize(source?.compareScopeId)
        String fileSide = normalize(source?.fileSide)
        if (!compareScopeId || !fileSide) return null

        // Tenant-gated reads: a scope outside the active tenant yields null → no projection
        // (full records), never a cross-tenant read.
        def scope = TenantScopedFinder.findTenantScopedByIdQuiet(
                ec, "darpan.rule.RuleSetCompareScope", "compareScopeId", compareScopeId)
        if (scope == null) return null
        String ruleSetId = normalize(scope.ruleSetId)
        List<String> ruleKeepFields = []
        if (ruleSetId) {
            List<Map<String, Object>> ruleRows = collectRuleRows(ec, ruleSetId)
            if (ruleRows) {
                ruleKeepFields = ruleKeepFieldsForSide(ruleRows, fileSide, source?.primaryIdExpression)
                // Null means at least one rule reads a path projection cannot preserve.
                if (ruleKeepFields == null) return null
            }
        }

        Set<String> keepFields = new LinkedHashSet<String>()
        normalize(baseFieldsCsv)?.tokenize(",")?.each { Object field ->
            String name = normalize(field)
            if (name) keepFields.add(name)
        }

        List keyFieldRows = TenantScopedFinder.findTenantScoped(ec, "darpan.rule.RuleSetCompareSourceKeyField")
                .condition("compareScopeId", compareScopeId).condition("fileSide", fileSide)
                .orderBy("sequenceNum").useCache(false).list() ?: []
        keyFieldRows.each { Object row ->
            String fieldName = topLevelRecordField(normalize(row.fieldExpression))
            if (fieldName) keepFields.add(fieldName)
        }
        if (!keyFieldRows) {
            String fieldName = topLevelRecordField(normalize(source?.primaryIdExpression))
            if (fieldName) keepFields.add(fieldName)
        }
        keepFields.addAll(ruleKeepFields)
        return keepFields ? new ArrayList<String>(keepFields) : null
    }

    /**
     * Stored exclusion rows for one compare source, ordered by sequenceNum, with {@code
     * fieldExpression} exactly as the operator configured it (a JSONPath from the rules board).
     * Returns an empty list when the source has no rows, which is what keeps pre-feature rule sets
     * extracting exactly as they did before this feature. RuleSetCompareSourceFilter carries its own
     * denormalized companyUserGroupId (same shape as RuleSetCompareSourceKeyField above), so it is
     * read the same way — directly tenant-scoped, not via the transitively-owned findGlobalUnscoped
     * pattern used for RuleSetCompareSource itself.
     *
     * This is the LOAD shape (round-tripped back onto the board). Anything handing rules to a getter
     * wants {@link #resolveExtractExcludeFilters} instead.
     */
    static List<Map<String, Object>> readExcludeFilterRows(def ec, Object source) {
        String compareScopeId = normalize(source?.compareScopeId)
        String fileSide = normalize(source?.fileSide)
        if (!compareScopeId || !fileSide) return []

        List filterRows = TenantScopedFinder.findTenantScoped(ec, "darpan.rule.RuleSetCompareSourceFilter")
                .condition("compareScopeId", compareScopeId).condition("fileSide", fileSide)
                .orderBy("sequenceNum").useCache(false).list() ?: []
        return filterRows.collect { Object row ->
            [
                    sequenceNum    : row.sequenceNum,
                    fieldExpression: normalize(row.fieldExpression),
                    operator       : normalize(row.operator),
                    filterValues   : normalize(row.filterValues),
            ] as Map<String, Object>
        }
    }

    /**
     * The same rules in GETTER shape: {@code fieldExpression} reduced from the stored JSONPath to the
     * top-level record key SourceFilterSupport.firstMatchingRule actually tests. Without this step the
     * board's {@code $.records[*].salesChannelEnumId} never matches a raw record key and every
     * configured exclusion is a silent no-op. Mirrors what resolveExtractKeepFields already does for
     * the sibling key-field expressions a hundred lines above.
     */
    static List<Map<String, Object>> resolveExtractExcludeFilters(def ec, Object source) {
        return SourceFilterSupport.toRecordFieldRules(readExcludeFilterRows(ec, source))
    }

    /**
     * Validate a submitted exclusion-filter payload and flatten each rule's value list into the
     * comma-separated form the entity stores. Throws rather than dropping a bad rule: a filter the
     * operator configured but Darpan silently ignored is the confusion this feature removes. The
     * stored shape produced here is finally run through SourceFilterSupport.parseRules — the single
     * source of truth for rule validity — so a save can never persist rows the getter would later
     * reject mid-run.
     */
    static List<Map<String, Object>> validateExcludeFilterPayload(Object rawFilters, String sourceLabel) {
        if (rawFilters == null) return []
        if (!(rawFilters instanceof Collection)) {
            throw new IllegalArgumentException("${sourceLabel} exclusion filters must be a list.".toString())
        }
        Collection rawList = (Collection) rawFilters
        if (rawList.size() > SourceFilterSupport.MAX_RULES_PER_SOURCE) {
            throw new IllegalArgumentException(
                    "${sourceLabel} defines ${rawList.size()} exclusion filters; the maximum is ${SourceFilterSupport.MAX_RULES_PER_SOURCE}.".toString())
        }
        List<Map<String, Object>> rows = []
        int position = 0
        for (Object raw : rawList) {
            position++
            if (!(raw instanceof Map)) {
                throw new IllegalArgumentException("${sourceLabel} exclusion filter ${position} is not a rule.".toString())
            }
            Map row = (Map) raw
            String fieldExpression = normalize(row.get("fieldExpression"))
            if (!fieldExpression) {
                throw new IllegalArgumentException("${sourceLabel} exclusion filter ${position} needs a field.".toString())
            }
            // Reject at SAVE what the getter would have to reject mid-run: the stored expression is
            // reduced to a top-level record key at dispatch (SourceFilterSupport.toRecordFieldRules),
            // and one that reduces to nothing could only ever match nothing.
            if (!topLevelRecordField(fieldExpression)) {
                throw new IllegalArgumentException(
                        "${sourceLabel} exclusion filter ${position} names field '${fieldExpression}', which does not resolve to a record field.".toString())
            }
            // Locale.ROOT, not the default locale: on a tr_TR JVM "exclude_in" upper-cases to
            // "EXCLUDE_İN" and the parseRules gate below rejects a rule the operator entered
            // correctly. Same fix already applied in SourceFilterSupport.parseRules.
            String operator = (normalize(row.get("operator")) ?: SourceFilterSupport.OPERATOR_EXCLUDE_IN).toUpperCase(Locale.ROOT)
            List<String> values = SourceFilterSupport.splitValues(
                    row.containsKey("values") ? row.get("values") : row.get("filterValues"))
            if (!values) {
                throw new IllegalArgumentException(
                        "${sourceLabel} exclusion filter ${position} needs at least one value to exclude.".toString())
            }
            if (values.size() > SourceFilterSupport.MAX_VALUES_PER_RULE) {
                throw new IllegalArgumentException(
                        "${sourceLabel} exclusion filter ${position} lists ${values.size()} values; the maximum is ${SourceFilterSupport.MAX_VALUES_PER_RULE}.".toString())
            }
            if (values.any { String value -> value.contains(",") }) {
                throw new IllegalArgumentException(
                        "${sourceLabel} exclusion filter ${position} contains a comma inside a value, which is not supported.".toString())
            }
            rows.add([fieldExpression: fieldExpression, operator: operator, filterValues: values.join(",")] as Map<String, Object>)
        }
        // parseRules is the single source of truth for rule validity — run the stored shape through
        // it now so a save can never persist rows the getter would later reject mid-run.
        SourceFilterSupport.parseRules(rows)
        return rows
    }

    /**
     * Loaded rules in wire shape: values as a List, not the stored comma-separated string, and
     * {@code fieldExpression} left as the STORED expression. The board matches this against the field
     * pill's own path, so reducing it to a bare record key here would orphan the mark.
     */
    static List<Map<String, Object>> buildExcludeFilterResponse(def ec, def source) {
        return readExcludeFilterRows(ec, source).collect { Map<String, Object> row ->
            [
                    sequenceNum    : row.sequenceNum,
                    fieldExpression: row.fieldExpression,
                    operator       : row.operator,
                    values         : SourceFilterSupport.splitValues(row.filterValues),
            ] as Map<String, Object>
        }
    }

    /**
     * Derives the top-level record field a JSONPath-style compare expression reads, e.g.
     * {@code $.records[*].externalId} → {@code externalId}, {@code $.records[*].items[*].sku} →
     * {@code items} (keeping the whole nested field), plain {@code id} → {@code id}.
     * Returns null when no safe field name can be derived (caller then skips that expression).
     *
     * Delegates to {@link CompareIdExpressionSupport#topLevelRecordField} — the implementation moved
     * there so the scheduled-automation dispatch path can share it without depending on this facade
     * package. This name stays as the facade-side entry point its existing callers and tests use.
     */
    static String topLevelRecordField(Object expression) {
        return CompareIdExpressionSupport.topLevelRecordField(expression)
    }

    static List<Map<String, Object>> collectRuleRows(def ec, Object rawRuleSetId) {
        String ruleSetId = normalize(rawRuleSetId)
        if (!ruleSetId) return []

        // Rule has no companyUserGroupId — gate transitively via parent RuleSet (directly-owned).
        def ruleFinder = TenantScopedFinder.findTenantScopedChildren(
                ec, "darpan.rule.Rule", "darpan.rule.RuleSet", "ruleSetId", ruleSetId, "ruleSetId")
        List rules = ruleFinder ? ruleFinder.orderBy("sequenceNum,ruleId").useCache(false).list() ?: [] : []

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

        String expectedType = expectedSourceConfigType(ec, systemEnumId)
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

    static String expectedSourceConfigType(def ec, Object rawSystemEnumId) {
        // Config over code: the expected source-config type now comes from the SourceSystemConnector
        // registry (the same source of truth the automation path reads), not a hardcoded switch.
        return SourceSystemConnectorSupport.resolve(ec, rawSystemEnumId)?.expectedSourceConfigType
    }

    protected static void validateShopifyAuthConfig(def ec, String sourceLabel, String sourceConfigId) {
        // DAR-BE-005 seam B: a config reachable in the settings list must also be referenceable.
        // findGlobalUnscoped loads by explicit PK; canActiveTenantUseConfig gates it immediately —
        // owner OR peer-group member. With zero grants this is exactly the old tenant-only behavior.
        def config = TenantScopedFinder.findGlobalUnscoped(ec, DarpanEntityConstants.SHOPIFY_AUTH_CONFIG,
                        "source config resolved by explicit PK; SharedConfigAccessSupport gates " +
                        "owner-or-shared access on the next line (DAR-BE-005)")
                .condition("shopifyAuthConfigId", sourceConfigId)
                .useCache(false)
                .one()
        // Deliberately ONE message for both "no such row" and "row exists but this tenant has no
        // standing over it" (not owner, not a peer). This is not a downgrade — it's required.
        // findTenantScoped (pre-DAR-BE-005) pre-filtered by tenant at the QUERY level, so a
        // foreign-owned row was simply invisible and this exact text was the only message a
        // zero-grant caller could ever see; splitting it into a more specific "is not available"
        // message would both break that byte-identical behavior AND reopen a cross-tenant
        // existence oracle (sourceConfigId is caller-supplied via saved runs and rule sets, so a
        // caller with no standing must not be able to learn whether an id exists ANYWHERE by which
        // message comes back — see SharedConfigGrantSupport.resolveAndAuthorize, d272fd7, for the
        // same ruling applied to the grant/revoke path).
        if (config == null || !SharedConfigAccessSupport.canActiveTenantUseConfig(ec,
                SharedConfigAccessSupport.CONFIG_TYPE_SHOPIFY_AUTH, config)) {
            ec.message.addError("${sourceLabel} Shopify auth config '${sourceConfigId}' was not found.")
            return
        }
        if (normalize(config.isActive) == "N") {
            ec.message.addError("${sourceLabel} Shopify auth config '${sourceConfigId}' is inactive.")
        }
        if (!normalizeBool(config.canReadOrders)) {
            ec.message.addError("${sourceLabel} Shopify auth config '${sourceConfigId}' cannot read orders.")
        }
    }

    protected static void validateHotWaxOmsConfig(def ec, String sourceLabel, String sourceConfigId) {
        // DAR-BE-005 seam B: a config reachable in the settings list must also be referenceable.
        // findGlobalUnscoped loads by explicit PK; canActiveTenantUseConfig gates it immediately —
        // owner OR peer-group member. With zero grants this is exactly the old tenant-only behavior.
        def config = TenantScopedFinder.findGlobalUnscoped(ec, DarpanEntityConstants.HOT_WAX_OMS_REST_SOURCE_CONFIG,
                        "source config resolved by explicit PK; SharedConfigAccessSupport gates " +
                        "owner-or-shared access on the next line (DAR-BE-005)")
                .condition("omsRestSourceConfigId", sourceConfigId)
                .useCache(false)
                .one()
        // Deliberately ONE message for both "no such row" and "row exists but this tenant has no
        // standing over it" (not owner, not a peer). This is not a downgrade — it's required.
        // findTenantScoped (pre-DAR-BE-005) pre-filtered by tenant at the QUERY level, so a
        // foreign-owned row was simply invisible and this exact text was the only message a
        // zero-grant caller could ever see; splitting it into a more specific "is not available"
        // message would both break that byte-identical behavior AND reopen a cross-tenant
        // existence oracle (sourceConfigId is caller-supplied via saved runs and rule sets, so a
        // caller with no standing must not be able to learn whether an id exists ANYWHERE by which
        // message comes back — see SharedConfigGrantSupport.resolveAndAuthorize, d272fd7, for the
        // same ruling applied to the grant/revoke path).
        if (config == null || !SharedConfigAccessSupport.canActiveTenantUseConfig(ec,
                SharedConfigAccessSupport.CONFIG_TYPE_HOTWAX_OMS, config)) {
            ec.message.addError("${sourceLabel} HotWax source config '${sourceConfigId}' was not found.")
            return
        }
        if (normalize(config.isActive) == "N") {
            ec.message.addError("${sourceLabel} HotWax source config '${sourceConfigId}' is inactive.")
        }
        if (!normalizeBool(config.canReadOrders)) {
            ec.message.addError("${sourceLabel} HotWax source config '${sourceConfigId}' cannot read orders.")
        }
    }

    protected static void validateNetSuiteAuthConfig(def ec, String sourceLabel, String sourceConfigId, def nsRestletConfig) {
        // DAR-BE-005 seam B: a config reachable in the settings list must also be referenceable.
        // findGlobalUnscoped loads by explicit PK; canActiveTenantUseConfig gates it immediately —
        // owner OR peer-group member. With zero grants this is exactly the old tenant-only behavior.
        def config = TenantScopedFinder.findGlobalUnscoped(ec, DarpanEntityConstants.NS_AUTH_CONFIG,
                        "source config resolved by explicit PK; SharedConfigAccessSupport gates " +
                        "owner-or-shared access on the next line (DAR-BE-005)")
                .condition("nsAuthConfigId", sourceConfigId)
                .useCache(false)
                .one()
        // Deliberately ONE message for both "no such row" and "row exists but this tenant has no
        // standing over it" (not owner, not a peer). This is not a downgrade — it's required.
        // findTenantScoped (pre-DAR-BE-005) pre-filtered by tenant at the QUERY level, so a
        // foreign-owned row was simply invisible and this exact text was the only message a
        // zero-grant caller could ever see; splitting it into a more specific "is not available"
        // message would both break that byte-identical behavior AND reopen a cross-tenant
        // existence oracle (sourceConfigId is caller-supplied via saved runs and rule sets, so a
        // caller with no standing must not be able to learn whether an id exists ANYWHERE by which
        // message comes back — see SharedConfigGrantSupport.resolveAndAuthorize, d272fd7, for the
        // same ruling applied to the grant/revoke path).
        if (config == null || !SharedConfigAccessSupport.canActiveTenantUseConfig(ec,
                SharedConfigAccessSupport.CONFIG_TYPE_NS_AUTH, config)) {
            ec.message.addError("${sourceLabel} NetSuite auth config '${sourceConfigId}' was not found.")
            return
        }
        if (normalize(config.isActive) == "N") {
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
            def systemMessageRemote = source.systemMessageRemoteId ? TenantScopedFinder.findGlobalUnscoped(
                    ec, "moqui.service.message.SystemMessageRemote",
                    "framework global remote config — not tenant-owned")
                    .condition("systemMessageRemoteId", source.systemMessageRemoteId)
                    .useCache(false)
                    .one() : null
            // NsRestletConfig is directly-owned (has companyUserGroupId); use quiet variant for display label.
            def nsRestletConfig = source.nsRestletConfigId ? TenantScopedFinder.findTenantScopedByIdQuiet(
                    ec, DarpanEntityConstants.NS_RESTLET_CONFIG, "nsRestletConfigId", source.nsRestletConfigId) : null
            return [
                    fileSide          : fileSide,
                    enumId            : systemEnumId,
                    enumCode          : normalize(systemEnum?.enumCode),
                    description       : normalize(systemEnum?.description),
                    label             : resolveEnumLabel(ec, systemEnumId, source.systemEnumId as String),
                    fileTypeEnumId    : normalize(source.fileTypeEnumId),
                    fileTypeLabel     : fileTypeEnum ? FacadeSupport.enumLabel(fileTypeEnum) : null,
                    idFieldExpression : normalize(source.primaryIdExpression),
                    idFieldExpressions: resolveKeyFieldExpressions(source),
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

    // Composite-key sides store their ordered key fields as RuleSetCompareSourceKeyField rows (via the
    // `keyFields` relationship on RuleSetCompareSource) rather than in primaryIdExpression, which is
    // left null for those sides. This surfaces the ordered field list either way so the saved-run load
    // path (list#SavedRuns) can hand the UI an editable composite draft.
    private static List<String> resolveKeyFieldExpressions(def source) {
        // The real load path passes a RuleSetCompareSource EntityValue (which supports the keyFields
        // relationship); some unit tests stub `source` as a plain Map, which has no findRelated — only
        // traverse the relationship for genuine entities, and read scalars via Map.get for both shapes.
        List keyFields = (source instanceof org.moqui.entity.EntityValue)
                ? (source.findRelated("keyFields", null, ["sequenceNum"], false, false) ?: [])
                : []
        if (keyFields) return keyFields.collect { normalize(it.get("fieldExpression")) }.findAll { it }
        String single = normalize(source.get("primaryIdExpression"))
        return single ? [single] : []
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

    static boolean isVirtualShopifyOrdersRemote(Object systemEnumId, Object systemMessageRemoteId, Object sourceConfigType) {
        if (canonicalSystemEnumId(systemEnumId) != SYSTEM_SHOPIFY) return false
        if (normalize(systemMessageRemoteId) != SHOPIFY_ORDERS_REMOTE_ID) return false
        String normalizedSourceConfigType = normalize(sourceConfigType)
        return !normalizedSourceConfigType || normalizedSourceConfigType == SOURCE_CONFIG_TYPE_SHOPIFY_AUTH
    }

    // One registry-driven virtual-remote builder replacing the per-system ensureVirtual{HotWax,Shopify}
    // builders: the SystemMessageRemote's id / label / sendUrl / sendServiceName come from the resolved
    // SourceSystemConnector row (config over code). The isVirtual* guards keep the current activation
    // rules (canonical system + known remoteId + matching/blank sourceConfigType).
    static def ensureVirtualApiOrdersRemote(def ec, Object systemEnumId, Object systemMessageRemoteId, Object sourceConfigType) {
        if (!isVirtualApiOrdersRemote(systemEnumId, systemMessageRemoteId, sourceConfigType)) return null
        Map<String, Object> connector = SourceSystemConnectorSupport.resolve(ec, canonicalSystemEnumId(systemEnumId))
        String remoteId = normalize(connector?.remoteId)
        if (!remoteId) return null

        def existing = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.service.message.SystemMessageRemote",
                        "virtual framework remote config — not tenant-owned")
                .condition("systemMessageRemoteId", remoteId)
                .useCache(false)
                .one()
        if (existing) return existing

        ec.service.sync()
                .name("store#moqui.service.message.SystemMessageRemote")
                .parameters([
                        systemMessageRemoteId: remoteId,
                        description          : connector.endpointLabel,
                        sendUrl              : connector.sendUrlTemplate,
                        sendServiceName      : connector.remoteSendServiceName,
                ])
                .disableAuthz()
                .call()
        return TenantScopedFinder.findGlobalUnscoped(ec, "moqui.service.message.SystemMessageRemote",
                        "virtual framework remote config — not tenant-owned")
                .condition("systemMessageRemoteId", remoteId)
                .useCache(false)
                .one()
    }

    /**
     * Non-cached enum lookup used by XML service callers that need fresh reads
     * (e.g. after upgrade-data inserts in tests). FacadeSupport.findEnum is cached.
     */
    static def findEnum(def ec, Object enumId) {
        String normalized = normalize(enumId)
        if (!normalized) return null
        return TenantScopedFinder.findGlobalUnscoped(ec, "moqui.basic.Enumeration",
                        "framework enum reference data — not tenant-owned")
                .condition("enumId", normalized)
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
