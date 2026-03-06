import groovy.json.JsonSlurper
import groovy.transform.Field
import org.slf4j.LoggerFactory

import java.sql.Connection
import java.sql.Date
import java.sql.Driver
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.LocalDate
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

@Field Set<String> SEEDED_SQL_RULESETS = ConcurrentHashMap.newKeySet()

def logger = LoggerFactory.getLogger("darpan.reconciliation.inventory.fetchHcInventoryAdjustments")

def normalize = { Object value -> value?.toString()?.trim() }
def extractErrorText = {
    try {
        List errs = (ec.message?.errors ?: []) as List
        String joined = errs.findAll { it != null && it.toString().trim() }.collect { it.toString().trim() }.join(" | ")
        return joined ?: null
    } catch (Exception ignored) {
        return null
    }
}
def clearErrors = {
    try {
        if (ec.message?.hasError()) ec.message.clearErrors()
    } catch (Exception ignored) {}
}
def warningList = []

String hcConfigId = normalize(readDbConfigId ?: hcReadDbConfigId)
String itemIdStr = normalize(itemId)
String locationIdStr = normalize(locationId)
String fromDateStr = normalize(from)
String toDateStr = normalize(to)
String sqlRuleSetIdToUse = normalize(sqlRuleSetId) ?: "INV_ADJ_HC_SQL_RS"

if (!hcConfigId) throw new IllegalArgumentException("readDbConfigId (or hcReadDbConfigId) is required")
if (!itemIdStr) throw new IllegalArgumentException("itemId is required")
if (!locationIdStr) throw new IllegalArgumentException("locationId is required")
if (!fromDateStr) throw new IllegalArgumentException("from is required in yyyy-MM-dd format")
if (!toDateStr) throw new IllegalArgumentException("to is required in yyyy-MM-dd format")
if (!(fromDateStr ==~ /\d{4}-\d{2}-\d{2}/)) throw new IllegalArgumentException("from must be yyyy-MM-dd")
if (!(toDateStr ==~ /\d{4}-\d{2}-\d{2}/)) throw new IllegalArgumentException("to must be yyyy-MM-dd")

LocalDate fromDate = LocalDate.parse(fromDateStr)
LocalDate toDate = LocalDate.parse(toDateStr)
if (toDate.isBefore(fromDate)) throw new IllegalArgumentException("to date must be greater than or equal to from date")
LocalDate toDateExclusive = toDate.plusDays(1)

def hcConfig = ec.entity.find("darpan.reconciliation.HcReadDbConfig")
        .condition("hcReadDbConfigId", hcConfigId)
        .useCache(false)
        .one()
if (!hcConfig) throw new IllegalArgumentException("ReadDbConfig ${hcConfigId} not found")
if ((normalize(hcConfig.isActive) ?: "Y").equalsIgnoreCase("N")) {
    throw new IllegalArgumentException("ReadDbConfig ${hcConfigId} is inactive")
}

String jdbcUrl = normalize(hcConfig.jdbcUrl)
String host = normalize(hcConfig.host)
String databaseName = normalize(hcConfig.databaseName)
String additionalParameters = normalize(hcConfig.additionalParameters)
int port = (hcConfig.port ?: 3306) as int
String username = normalize(hcConfig.username)
String password = hcConfig.password?.toString()
String dbDriver = normalize(hcConfig.dbDriver) ?: "com.mysql.cj.jdbc.Driver"
String requestTableName = normalize(tableName)
String requestItemIdColumn = normalize(itemIdColumn)
String requestLocationIdColumn = normalize(locationIdColumn)
String requestTxnDateColumn = normalize(transactionDateColumn)
String requestInventoryItemTable = normalize(inventoryItemTable)

String configDefaultTableName = normalize(hcConfig.defaultTableName)
String configItemIdColumn = normalize(hcConfig.itemIdColumn)
String configLocationIdColumn = normalize(hcConfig.locationIdColumn)
String configTxnDateColumn = normalize(hcConfig.transactionDateColumn)

String tableNameToUse = requestTableName ?: configDefaultTableName ?: "inventory_item_detail"
String itemIdColumnToUse = requestItemIdColumn ?: configItemIdColumn ?: "product_id"
String locationIdColumnToUse = requestLocationIdColumn ?: configLocationIdColumn ?: "facility_id"
String txnDateColumnToUse = requestTxnDateColumn ?: configTxnDateColumn ?: "effective_date"
String inventoryItemTableToUse = requestInventoryItemTable ?: "inventory_item"

if (!requestTableName && "inventory_adjustment".equalsIgnoreCase(configDefaultTableName)) {
    tableNameToUse = "inventory_item_detail"
    if (!requestItemIdColumn && (!configItemIdColumn || "item_id".equalsIgnoreCase(configItemIdColumn))) {
        itemIdColumnToUse = "product_id"
    }
    if (!requestLocationIdColumn && (!configLocationIdColumn || "location_id".equalsIgnoreCase(configLocationIdColumn))) {
        locationIdColumnToUse = "facility_id"
    }
    if (!requestTxnDateColumn && (!configTxnDateColumn || "transaction_date".equalsIgnoreCase(configTxnDateColumn))) {
        txnDateColumnToUse = "effective_date"
    }
    if (!requestInventoryItemTable && !"inventory_item".equalsIgnoreCase(inventoryItemTableToUse)) {
        inventoryItemTableToUse = "inventory_item"
    }
    warningList.add("ReadDbConfig ${hcConfigId} uses legacy default table inventory_adjustment; auto-switched to inventory_item_detail defaults.")
}
if (!requestTxnDateColumn &&
        (tableNameToUse?.equalsIgnoreCase("inventory_item_detail") || tableNameToUse?.toLowerCase()?.endsWith(".inventory_item_detail")) &&
        "adjustment_date".equalsIgnoreCase(configTxnDateColumn)) {
    txnDateColumnToUse = "effective_date"
    warningList.add("ReadDbConfig ${hcConfigId} uses legacy date column adjustment_date for inventory_item_detail; auto-switched to effective_date.")
}

if (!jdbcUrl && host && databaseName) {
    String cleanAdditional = additionalParameters?.replaceFirst(/^\?/, "")
    jdbcUrl = "jdbc:mysql://${host}:${port}/${databaseName}" + (cleanAdditional ? "?${cleanAdditional}" : "")
    warningList.add("JDBC URL was generated from host/port/databaseName for config ${hcConfigId}.")
}

if (!jdbcUrl) throw new IllegalArgumentException("ReadDbConfig ${hcConfigId} is missing jdbcUrl and host/databaseName")
if (!username) throw new IllegalArgumentException("ReadDbConfig ${hcConfigId} is missing username")
if (password == null) throw new IllegalArgumentException("ReadDbConfig ${hcConfigId} is missing password")

def assertSqlIdentifier = { String value, String label ->
    if (!value) throw new IllegalArgumentException("${label} is required")
    if (!(value ==~ /[A-Za-z0-9_$.]+/)) {
        throw new IllegalArgumentException("${label} contains invalid characters: ${value}")
    }
}
assertSqlIdentifier(tableNameToUse, "tableName")
assertSqlIdentifier(itemIdColumnToUse, "itemIdColumn")
assertSqlIdentifier(locationIdColumnToUse, "locationIdColumn")
assertSqlIdentifier(txnDateColumnToUse, "transactionDateColumn")
assertSqlIdentifier(inventoryItemTableToUse, "inventoryItemTable")

def ensureDefaultHcSqlRuleSet = { String ruleSetId ->
    if (!ruleSetId || !"INV_ADJ_HC_SQL_RS".equals(ruleSetId)) return
    Set<String> seededRuleSets = SEEDED_SQL_RULESETS
    if (seededRuleSets == null) {
        seededRuleSets = ConcurrentHashMap.newKeySet()
        SEEDED_SQL_RULESETS = seededRuleSets
    }
    if (seededRuleSets.contains(ruleSetId)) return

    synchronized (seededRuleSets) {
        if (seededRuleSets.contains(ruleSetId)) return

        def existingRuleSet = ec.entity.find("darpan.rule.RuleSet")
                .condition("ruleSetId", ruleSetId)
                .useCache(false)
                .one()
        if (!existingRuleSet) {
            ec.entity.makeValue("darpan.rule.RuleSet")
                    .set("ruleSetId", ruleSetId)
                    .set("ruleSetName", "Inventory Adjustment Read DB SQL Resolver")
                    .set("description", "Default Drools SQL template resolver for inventory adjustment read DB queries")
                    .set("version", "1.0")
                    .set("createdDate", new Timestamp(System.currentTimeMillis()))
                    .create()
        }

        List<Map> defaultRules = [
                [
                        ruleId     : "INV_ADJ_HC_SQL_JOIN",
                        sequenceNum: 10L,
                        ruleText   : "Use JOIN SQL for inventory_item_detail + product_id/facility_id",
                        ruleLogic  : '''rule "INV_ADJ_HC_SQL_JOIN"
activation-group "INV_ADJ_HC_SQL_TEMPLATE"
salience 100
when
    $m : Map(this["sqlStatementTemplate"] == null)
    eval(
        (String.valueOf($m.get("tableName")) != null &&
            (
                "inventory_item_detail".equalsIgnoreCase(String.valueOf($m.get("tableName")).trim()) ||
                String.valueOf($m.get("tableName")).trim().toLowerCase().endsWith(".inventory_item_detail")
            )) &&
        "product_id".equalsIgnoreCase(String.valueOf($m.get("itemIdColumn")).trim()) &&
        "facility_id".equalsIgnoreCase(String.valueOf($m.get("locationIdColumn")).trim())
    )
then
    if ($m.get("sqlStatementTemplate") == null) {
        $m.put("sqlTemplateName", "JOIN_BY_INVENTORY_ITEM");
        $m.put("sqlStatementTemplate", "SELECT iid.*, ii.${ITEM_COLUMN} AS _join_${ITEM_COLUMN}, ii.${LOCATION_COLUMN} AS _join_${LOCATION_COLUMN} FROM ${DETAIL_TABLE} iid JOIN ${ITEM_TABLE} ii ON ii.inventory_item_id = iid.inventory_item_id WHERE ii.${ITEM_COLUMN} = ? AND ii.${LOCATION_COLUMN} = ? AND iid.${DATE_COLUMN} >= ? AND iid.${DATE_COLUMN} < ?");
        if (!results.contains($m)) results.add($m);
    }
end'''
                ],
                [
                        ruleId     : "INV_ADJ_HC_SQL_DIRECT",
                        sequenceNum: 20L,
                        ruleText   : "Fallback direct table SQL template",
                        ruleLogic  : '''rule "INV_ADJ_HC_SQL_DIRECT"
activation-group "INV_ADJ_HC_SQL_TEMPLATE"
salience 10
when
    $m : Map(this["sqlStatementTemplate"] == null)
then
    if ($m.get("sqlStatementTemplate") == null) {
        $m.put("sqlTemplateName", "DIRECT_TABLE");
        $m.put("sqlStatementTemplate", "SELECT * FROM ${DETAIL_TABLE} WHERE ${ITEM_COLUMN} = ? AND ${LOCATION_COLUMN} = ? AND ${DATE_COLUMN} >= ? AND ${DATE_COLUMN} < ?");
        if (!results.contains($m)) results.add($m);
    }
end'''
                ]
        ]

        defaultRules.each { Map defRule ->
            def existingRule = ec.entity.find("darpan.rule.Rule")
                    .condition("ruleId", defRule.ruleId)
                    .useCache(false)
                    .one()
            if (!existingRule) {
                ec.entity.makeValue("darpan.rule.Rule")
                        .set("ruleId", defRule.ruleId)
                        .set("ruleSetId", ruleSetId)
                        .set("sequenceNum", defRule.sequenceNum)
                        .set("ruleText", defRule.ruleText)
                        .set("ruleLogic", defRule.ruleLogic)
                        .set("enabled", "Y")
                        .set("createdDate", new Timestamp(System.currentTimeMillis()))
                        .create()
            } else if (ruleSetId == existingRule.ruleSetId) {
                String existingLogic = normalize(existingRule.ruleLogic) ?: ""
                boolean hasLegacyHardcodedSql = existingLogic.toLowerCase().contains("inventory_adjustment")
                boolean hasLegacyColumnPattern = existingLogic.toLowerCase().contains(" item_id = ?") ||
                        existingLogic.toLowerCase().contains(" location_id = ?") ||
                        existingLogic.toLowerCase().contains(" transaction_date ")
                boolean hasStrictDetailTableMatch = existingLogic.contains('"inventory_item_detail".equalsIgnoreCase(String.valueOf($m.get("tableName")))')
                boolean missingActivationGroup = !existingLogic.contains('activation-group "INV_ADJ_HC_SQL_TEMPLATE"')
                boolean missingConsequenceGuard = !existingLogic.contains('if ($m.get("sqlStatementTemplate") == null)')
                boolean shouldRefreshLegacyRule = hasLegacyHardcodedSql || hasLegacyColumnPattern ||
                        hasStrictDetailTableMatch || missingActivationGroup || missingConsequenceGuard
                if (shouldRefreshLegacyRule) {
                    existingRule.set("sequenceNum", defRule.sequenceNum)
                    existingRule.set("ruleText", defRule.ruleText)
                    existingRule.set("ruleLogic", defRule.ruleLogic)
                    if (!normalize(existingRule.enabled)) existingRule.set("enabled", "Y")
                    existingRule.set("lastUpdatedDate", new Timestamp(System.currentTimeMillis()))
                    existingRule.update()
                }
            }
        }

        seededRuleSets.add(ruleSetId)
    }
}

ensureDefaultHcSqlRuleSet(sqlRuleSetIdToUse)

Properties props = new Properties()
props.setProperty("user", username)
props.setProperty("password", password)

String connectionPropertiesJson = normalize(hcConfig.connectionPropertiesJson)
if (connectionPropertiesJson) {
    def parsedProps = new JsonSlurper().parseText(connectionPropertiesJson)
    if (!(parsedProps instanceof Map)) {
        throw new IllegalArgumentException("ReadDbConfig ${hcConfigId} connectionPropertiesJson must be a JSON object")
    }
    parsedProps.each { key, value ->
        if (key != null && value != null) props.setProperty(key.toString(), value.toString())
    }
}

String safeJdbcUrl = jdbcUrl.replaceAll("(?i)(password=)[^&;]+", "\$1***")
logger.info("Querying read-only DB config={} itemId={} locationId={} from={} to={} table={} url={}",
        hcConfigId, itemIdStr, locationIdStr, fromDateStr, toDateStr, tableNameToUse, safeJdbcUrl)

Class.forName(dbDriver)

clearErrors()
Map sqlFact = [
        tableName     : tableNameToUse,
        itemIdColumn  : itemIdColumnToUse,
        locationIdColumn: locationIdColumnToUse,
        transactionDateColumn: txnDateColumnToUse,
        inventoryItemTable  : inventoryItemTableToUse,
        itemId        : itemIdStr,
        locationId    : locationIdStr
]
Map sqlRulesOut = ec.service.sync()
        .name("reconciliation.ReconciliationSampleServices.execute#Rules")
        .parameters([
                ruleSetId     : sqlRuleSetIdToUse,
                dataList      : [sqlFact],
                returnAllFacts: true
        ])
        .ignorePreviousError(true)
        .ignoreTransaction(true)
        .call()

if (ec.message?.hasError()) {
    String errText = extractErrorText() ?: normalize(ec.message?.errorsString) ?: "Unknown SQL rule resolution error"
    clearErrors()
    throw new IllegalArgumentException("Failed resolving HC SQL via ruleSet ${sqlRuleSetIdToUse}: ${errText}")
}
if (sqlRulesOut == null) {
    String errText = extractErrorText()
    clearErrors()
    throw new IllegalArgumentException("HC SQL ruleSet ${sqlRuleSetIdToUse} returned no result${errText ? ': ' + errText : ''}")
}
if (sqlRulesOut.error) {
    throw new IllegalArgumentException("HC SQL ruleSet ${sqlRuleSetIdToUse} failed: ${normalize(sqlRulesOut.error)}")
}

List sqlRows = (sqlRulesOut.results ?: []) as List
Map sqlResolved = (sqlRows && sqlRows[0] instanceof Map) ? ((Map) sqlRows[0]) : sqlFact
String sqlTemplateName = normalize(sqlResolved.sqlTemplateName) ?: "UNNAMED_TEMPLATE"
String sqlTemplate = normalize(sqlResolved.sqlStatementTemplate ?: sqlResolved.sqlStatement)
if (!sqlTemplate) {
    throw new IllegalArgumentException("HC SQL ruleSet ${sqlRuleSetIdToUse} did not produce sqlStatementTemplate")
}

Map<String, String> sqlTokens = [
        "DETAIL_TABLE"  : tableNameToUse,
        "ITEM_TABLE"    : inventoryItemTableToUse,
        "ITEM_COLUMN"   : itemIdColumnToUse,
        "LOCATION_COLUMN": locationIdColumnToUse,
        "DATE_COLUMN"   : txnDateColumnToUse
]
String sql = sqlTemplate
sqlTokens.each { String token, String value ->
    sql = sql.replace('${' + token + '}', value)
}
if (sql.contains('${')) {
    throw new IllegalArgumentException("HC SQL template ${sqlTemplateName} has unresolved placeholders: ${sql}")
}
if (sql.contains(";")) {
    throw new IllegalArgumentException("HC SQL template ${sqlTemplateName} contains unsupported ';' delimiter")
}
int placeholderCount = (sql.findAll(/\?/)?.size() ?: 0) as int
if (placeholderCount != 4) {
    throw new IllegalArgumentException("HC SQL template ${sqlTemplateName} must contain exactly 4 '?' parameters (itemId, locationId, from, to); found ${placeholderCount}")
}
logger.info("Read-only DB query resolved via Drools ruleSet={} template={} detailTable={} itemTable={} itemColumn={} locationColumn={} dateColumn={}",
        sqlRuleSetIdToUse, sqlTemplateName, tableNameToUse, inventoryItemTableToUse, itemIdColumnToUse, locationIdColumnToUse, txnDateColumnToUse)

def toTypedDbValue = { String rawValue ->
    if (rawValue ==~ /-?\d+/) {
        try {
            return Long.valueOf(rawValue)
        } catch (Exception ignored) {
            return rawValue
        }
    }
    if (rawValue ==~ /-?\d+\.\d+/) {
        try {
            return new BigDecimal(rawValue)
        } catch (Exception ignored) {
            return rawValue
        }
    }
    return rawValue
}

Connection connection = null
PreparedStatement statement = null
ResultSet resultSet = null
try {
    try {
        connection = DriverManager.getConnection(jdbcUrl, props)
    } catch (SQLException sqlEx) {
        String errMsg = normalize(sqlEx.message) ?: ""
        if (!errMsg.toLowerCase().contains("no suitable driver")) throw sqlEx

        // Some runtime classloader arrangements can prevent DriverManager from using
        // a loaded JDBC driver. Fall back to explicit driver.connect().
        Class driverClass = Class.forName(dbDriver)
        Object driverObj = driverClass.getDeclaredConstructor().newInstance()
        if (!(driverObj instanceof Driver)) {
            throw new IllegalArgumentException("Configured dbDriver ${dbDriver} is not a java.sql.Driver")
        }
        connection = ((Driver) driverObj).connect(jdbcUrl, props)
        if (connection == null) {
            throw new IllegalArgumentException("No suitable driver found for ${jdbcUrl} using configured dbDriver ${dbDriver}")
        }
        warningList.add("Used explicit JDBC driver connect fallback for config ${hcConfigId}.")
    }
    statement = connection.prepareStatement(sql)
    statement.setObject(1, toTypedDbValue(itemIdStr))
    statement.setObject(2, toTypedDbValue(locationIdStr))
    statement.setDate(3, Date.valueOf(fromDate))
    statement.setDate(4, Date.valueOf(toDateExclusive))

    resultSet = statement.executeQuery()
    def rowList = []
    def md = resultSet.metaData
    int colCount = md.columnCount
    while (resultSet.next()) {
        Map row = [:]
        (1..colCount).each { int colIdx ->
            String key = md.getColumnLabel(colIdx) ?: md.getColumnName(colIdx) ?: "col_${colIdx}"
            Object value = resultSet.getObject(colIdx)
            if (value instanceof java.sql.Date) value = value.toLocalDate().toString()
            else if (value instanceof java.sql.Timestamp) value = value.toInstant().toString()
            row[key] = value
        }
        rowList.add([record: row])
    }
    records = rowList
    recordCount = rowList.size()
} finally {
    try { resultSet?.close() } catch (Exception ignored) {}
    try { statement?.close() } catch (Exception ignored) {}
    try { connection?.close() } catch (Exception ignored) {}
}

processingWarnings = warningList.collect { [warningMessage: it] }
logger.info("Read-only DB query success config={} itemId={} locationId={} records={}",
        hcConfigId, itemIdStr, locationIdStr, recordCount ?: 0)
