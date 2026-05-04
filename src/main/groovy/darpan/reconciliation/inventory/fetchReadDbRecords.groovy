import groovy.json.JsonSlurper
import org.slf4j.LoggerFactory
import darpan.reconciliation.inventory.InventoryWarningSupport

import java.sql.Connection
import java.sql.Date
import java.sql.Driver
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.LocalDate
import java.util.Properties

def logger = LoggerFactory.getLogger("darpan.reconciliation.inventory.fetchReadDbRecords")

def normalize = { Object value -> value?.toString()?.trim() }
def normalizeBool = { Object value, boolean defaultValue ->
    if (value == null) return defaultValue
    if (value instanceof Boolean) return (boolean) value
    String raw = normalize(value)?.toLowerCase()
    if (["y", "yes", "true", "1"].contains(raw)) return true
    if (["n", "no", "false", "0"].contains(raw)) return false
    return defaultValue
}
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

String jdbcUrlToUse = normalize(jdbcUrl)
String usernameToUse = normalize(username)
String passwordToUse = password != null ? password.toString() : null
String dbDriverToUse = normalize(dbDriver) ?: "com.mysql.cj.jdbc.Driver"
String connectionPropertiesJsonToUse = normalize(connectionPropertiesJson)
String itemIdStr = normalize(itemId)
String locationIdStr = normalize(locationId)
String fromDateStr = normalize(from)
String toDateStr = normalize(to)
String sqlRuleSetIdToUse = normalize(sqlRuleSetId)
String inlineSqlTemplate = normalize(sqlStatementTemplate)
String inlineSqlTemplateName = normalize(sqlTemplateName) ?: "INLINE_SQL_TEMPLATE"
boolean useInventoryItemJoinFlag = normalizeBool(useInventoryItemJoin, false)

if (!jdbcUrlToUse) throw new IllegalArgumentException("jdbcUrl is required")
if (!usernameToUse) throw new IllegalArgumentException("username is required")
if (passwordToUse == null) throw new IllegalArgumentException("password is required")
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

String tableNameToUse = normalize(tableName)
String itemIdColumnToUse = normalize(itemIdColumn)
String locationIdColumnToUse = normalize(locationIdColumn)
String txnDateColumnToUse = normalize(transactionDateColumn)
String inventoryItemTableToUse = normalize(inventoryItemTable) ?: tableNameToUse

if (!tableNameToUse) throw new IllegalArgumentException("tableName is required")
if (!itemIdColumnToUse) throw new IllegalArgumentException("itemIdColumn is required")
if (!locationIdColumnToUse) throw new IllegalArgumentException("locationIdColumn is required")
if (!txnDateColumnToUse) throw new IllegalArgumentException("transactionDateColumn is required")
if (!inventoryItemTableToUse) inventoryItemTableToUse = tableNameToUse

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
if (!inlineSqlTemplate && sqlRuleSetIdToUse) {
    def sqlRuleSetExists = ec.entity.find("darpan.rule.RuleSet")
            .condition("ruleSetId", sqlRuleSetIdToUse)
            .useCache(false)
            .one()
    if (!sqlRuleSetExists) {
        throw new IllegalArgumentException("sqlRuleSetId ${sqlRuleSetIdToUse} not found. Create it in Rule Engine or provide sqlStatementTemplate.")
    }
}

Properties props = new Properties()
props.setProperty("user", usernameToUse)
props.setProperty("password", passwordToUse)

if (connectionPropertiesJsonToUse) {
    def parsedProps = new JsonSlurper().parseText(connectionPropertiesJsonToUse)
    if (!(parsedProps instanceof Map)) {
        throw new IllegalArgumentException("connectionPropertiesJson must be a JSON object")
    }
    parsedProps.each { key, value ->
        if (key != null && value != null) props.setProperty(key.toString(), value.toString())
    }
}

String safeJdbcUrl = jdbcUrlToUse.replaceAll("(?i)(password=)[^&;]+", "\$1***")
logger.info("Querying read-only DB itemId={} locationId={} from={} to={} table={} url={}",
        itemIdStr, locationIdStr, fromDateStr, toDateStr, tableNameToUse, safeJdbcUrl)

Class.forName(dbDriverToUse)

clearErrors()
Map sqlFact = [
        tableName     : tableNameToUse,
        itemIdColumn  : itemIdColumnToUse,
        locationIdColumn: locationIdColumnToUse,
        transactionDateColumn: txnDateColumnToUse,
        inventoryItemTable  : inventoryItemTableToUse,
        useInventoryItemJoin: useInventoryItemJoinFlag,
        itemId        : itemIdStr,
        locationId    : locationIdStr
]
String sqlTemplateName
String sqlTemplate
if (inlineSqlTemplate) {
    sqlTemplateName = inlineSqlTemplateName
    sqlTemplate = inlineSqlTemplate
} else {
    if (!sqlRuleSetIdToUse) {
        throw new IllegalArgumentException("Either sqlStatementTemplate or sqlRuleSetId is required for read DB SQL resolution")
    }
    Map sqlRulesOut = ec.service.sync()
            .name("reconciliation.ReconciliationRuleEngineServices.execute#RuleSet")
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
        throw new IllegalArgumentException("Failed resolving read DB SQL via ruleSet ${sqlRuleSetIdToUse}: ${errText}")
    }
    if (sqlRulesOut == null) {
        String errText = extractErrorText()
        clearErrors()
        throw new IllegalArgumentException("Read DB SQL ruleSet ${sqlRuleSetIdToUse} returned no result${errText ? ': ' + errText : ''}")
    }
    if (sqlRulesOut.error) {
        throw new IllegalArgumentException("Read DB SQL ruleSet ${sqlRuleSetIdToUse} failed: ${normalize(sqlRulesOut.error)}")
    }

    List sqlRows = (sqlRulesOut.results ?: []) as List
    Map sqlResolved = (sqlRows && sqlRows[0] instanceof Map) ? ((Map) sqlRows[0]) : sqlFact
    sqlTemplateName = normalize(sqlResolved.sqlTemplateName) ?: "RULESET_TEMPLATE"
    sqlTemplate = normalize(sqlResolved.sqlStatementTemplate)
    if (!sqlTemplate) {
        throw new IllegalArgumentException("Read DB SQL ruleSet ${sqlRuleSetIdToUse} did not produce sqlStatementTemplate")
    }
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
    throw new IllegalArgumentException("Read DB SQL template ${sqlTemplateName} has unresolved placeholders: ${sql}")
}
if (sql.contains(";")) {
    throw new IllegalArgumentException("Read DB SQL template ${sqlTemplateName} contains unsupported ';' delimiter")
}
int placeholderCount = (sql.findAll(/\?/)?.size() ?: 0) as int
if (placeholderCount != 4) {
    throw new IllegalArgumentException("Read DB SQL template ${sqlTemplateName} must contain exactly 4 '?' parameters (itemId, locationId, from, to); found ${placeholderCount}")
}
logger.info("Read-only DB query resolved template={} ruleSet={} detailTable={} itemTable={} itemColumn={} locationColumn={} dateColumn={} useInventoryItemJoin={}",
        sqlTemplateName, sqlRuleSetIdToUse ?: "(inline)", tableNameToUse, inventoryItemTableToUse, itemIdColumnToUse, locationIdColumnToUse, txnDateColumnToUse, useInventoryItemJoinFlag)

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
        connection = DriverManager.getConnection(jdbcUrlToUse, props)
    } catch (SQLException sqlEx) {
        String errMsg = normalize(sqlEx.message) ?: ""
        if (!errMsg.toLowerCase().contains("no suitable driver")) throw sqlEx

        // Some runtime classloader arrangements can prevent DriverManager from using
        // a loaded JDBC driver. Fall back to explicit driver.connect().
        Class driverClass = Class.forName(dbDriverToUse)
        Object driverObj = driverClass.getDeclaredConstructor().newInstance()
        if (!(driverObj instanceof Driver)) {
            throw new IllegalArgumentException("Configured dbDriver ${dbDriverToUse} is not a java.sql.Driver")
        }
        connection = ((Driver) driverObj).connect(jdbcUrlToUse, props)
        if (connection == null) {
            throw new IllegalArgumentException("No suitable driver found for ${jdbcUrlToUse} using configured dbDriver ${dbDriverToUse}")
        }
        warningList.add("Used explicit JDBC driver connect fallback.")
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

processingWarnings = InventoryWarningSupport.normalizeWarningTexts(warningList)
logger.info("Read-only DB query success itemId={} locationId={} records={}",
        itemIdStr, locationIdStr, recordCount ?: 0)
