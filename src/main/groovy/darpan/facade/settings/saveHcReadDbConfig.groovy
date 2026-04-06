import darpan.facade.common.FacadeSupport
import darpan.facade.common.PilotAccessSupport
import groovy.json.JsonSlurper

String inputConfigId = FacadeSupport.normalize(hcReadDbConfigId)
String displayName = FacadeSupport.normalize(displayName)
String host = FacadeSupport.normalize(host)
String databaseName = FacadeSupport.normalize(databaseName)
String username = FacadeSupport.normalize(username)
String password = FacadeSupport.normalize(password)
String additionalParameters = FacadeSupport.normalize(additionalParameters)
String dbDriver = FacadeSupport.normalize(dbDriver) ?: "com.mysql.cj.jdbc.Driver"
String defaultTableName = FacadeSupport.normalize(defaultTableName) ?: "inventory_item_detail"
String itemIdColumn = FacadeSupport.normalize(itemIdColumn) ?: "product_id"
String locationIdColumn = FacadeSupport.normalize(locationIdColumn) ?: "facility_id"
String transactionDateColumn = FacadeSupport.normalize(transactionDateColumn) ?: "effective_date"
String connectionPropertiesJson = FacadeSupport.normalize(connectionPropertiesJson)
String isActive = FacadeSupport.normalizeBool(isActive, true) ? "Y" : "N"

PilotAccessSupport.requireSuperAdmin(ec, "Pilot settings are restricted to super-admin users.")

Integer port = FacadeSupport.normalizeInt(port, 3306)
if (port <= 0) ec.message.addError("Port must be greater than 0.")
if (!host) ec.message.addError("Host is required.")
if (!databaseName) ec.message.addError("Database Name is required.")
if (!username) ec.message.addError("Username is required.")

if (connectionPropertiesJson) {
    try {
        def parsed = new JsonSlurper().parseText(connectionPropertiesJson)
        if (!(parsed instanceof Map)) {
            ec.message.addError("Connection Properties JSON must be a JSON object.")
        }
    } catch (Exception e) {
        ec.message.addError("Connection Properties JSON is invalid: ${e.message}")
    }
}

def normalizeId = { String rawValue ->
    rawValue?.toLowerCase()?.replaceAll(/[^a-z0-9_-]+/, "_")?.replaceAll(/^_+|_+$/, "")
}

String cleanAdditional = additionalParameters
if (cleanAdditional?.startsWith("?")) cleanAdditional = cleanAdditional.substring(1)

String resolvedConfigId = inputConfigId
if (!ec.message.hasError()) {
    if (resolvedConfigId) {
        String normalized = normalizeId(resolvedConfigId)
        if (!normalized) {
            ec.message.addError("Config ID must contain letters or numbers.")
        } else {
            resolvedConfigId = normalized
        }
    } else {
        String seed = displayName ?: "${host ?: 'hc'}_${databaseName ?: 'db'}"
        String baseId = normalizeId(seed) ?: "hc_db"
        if (baseId.length() > 40) baseId = baseId.substring(0, 40)

        String candidateId = baseId
        int suffix = 1
        while (ec.entity.find("darpan.reconciliation.HcReadDbConfig")
            .condition("hcReadDbConfigId", candidateId)
            .useCache(false)
            .one()) {
            suffix++
            String suffixPart = "_${suffix}"
            int maxBaseLength = Math.max(1, 40 - suffixPart.length())
            String trimmedBase = baseId.length() > maxBaseLength ? baseId.substring(0, maxBaseLength) : baseId
            candidateId = trimmedBase + suffixPart
        }
        resolvedConfigId = candidateId
    }
}

if (resolvedConfigId?.length() > 40) {
    resolvedConfigId = resolvedConfigId.substring(0, 40)
}

String jdbcUrl = "jdbc:mysql://${host}:${port}/${databaseName}"
if (cleanAdditional) jdbcUrl += "?${cleanAdditional}"

if (!ec.message.hasError()) {
    def existing = ec.entity.find("darpan.reconciliation.HcReadDbConfig")
        .condition("hcReadDbConfigId", resolvedConfigId)
        .useCache(false)
        .one()

    Map hcMap = [
        hcReadDbConfigId: resolvedConfigId,
        description: displayName,
        displayName: displayName,
        host: host,
        port: port,
        databaseName: databaseName,
        additionalParameters: cleanAdditional,
        jdbcUrl: jdbcUrl,
        username: username,
        dbDriver: dbDriver,
        defaultTableName: defaultTableName,
        itemIdColumn: itemIdColumn,
        locationIdColumn: locationIdColumn,
        transactionDateColumn: transactionDateColumn,
        connectionPropertiesJson: connectionPropertiesJson,
        isActive: isActive,
    ]

    if (password) hcMap.password = password
    else if (existing?.password) hcMap.password = existing.password

    ec.service.sync().name("store#darpan.reconciliation.HcReadDbConfig").parameters(hcMap).call()

    savedConfig = [
        hcReadDbConfigId: resolvedConfigId,
        displayName: displayName,
        host: host,
        port: port,
        databaseName: databaseName,
        additionalParameters: cleanAdditional,
        jdbcUrl: jdbcUrl,
        username: username,
        dbDriver: dbDriver,
        defaultTableName: defaultTableName,
        itemIdColumn: itemIdColumn,
        locationIdColumn: locationIdColumn,
        transactionDateColumn: transactionDateColumn,
        connectionPropertiesJson: connectionPropertiesJson,
        isActive: isActive,
        hasPassword: !!FacadeSupport.normalize(hcMap.password),
    ]

    if (!ec.message.hasError()) ec.message.addMessage("Saved Read DB config ${resolvedConfigId}.")
}

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
