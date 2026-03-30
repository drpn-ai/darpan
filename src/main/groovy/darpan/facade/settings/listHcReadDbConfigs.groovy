import darpan.facade.common.FacadeSupport
import darpan.facade.common.PilotAccessSupport

int page = Math.max(0, FacadeSupport.normalizeInt(pageIndex, 0))
int size = Math.max(1, Math.min(200, FacadeSupport.normalizeInt(pageSize, 20)))

PilotAccessSupport.requireSuperAdmin(ec, "Pilot settings are restricted to super-admin users.")

List<Map> rows = []
pagination = [pageIndex: page, pageSize: size, totalCount: 0, pageCount: 1]
configs = []

if (!ec.message.hasError()) {
    (ec.entity.find("darpan.reconciliation.HcReadDbConfig")
        .useCache(false)
        .orderBy("displayName,hcReadDbConfigId")
        .list() ?: []).each { cfg ->
        String safeJdbcUrl = cfg.jdbcUrl?.toString()?.replaceAll("(?i)(password=)[^&;]+", '$1***')
        rows.add([
            hcReadDbConfigId: cfg.hcReadDbConfigId,
            displayName: cfg.displayName ?: cfg.description,
            host: cfg.host,
            port: cfg.port ?: 3306,
            databaseName: cfg.databaseName,
            additionalParameters: cfg.additionalParameters,
            jdbcUrl: safeJdbcUrl,
            username: cfg.username,
            dbDriver: cfg.dbDriver ?: "com.mysql.cj.jdbc.Driver",
            defaultTableName: cfg.defaultTableName ?: "inventory_item_detail",
            itemIdColumn: cfg.itemIdColumn ?: "product_id",
            locationIdColumn: cfg.locationIdColumn ?: "facility_id",
            transactionDateColumn: cfg.transactionDateColumn ?: "effective_date",
            connectionPropertiesJson: cfg.connectionPropertiesJson,
            isActive: cfg.isActive ?: "Y",
            hasPassword: !!cfg.password,
        ])
    }

    String search = FacadeSupport.normalize(query)?.toLowerCase()
    List<Map> filtered = search ? rows.findAll { row ->
        [row.hcReadDbConfigId, row.displayName, row.host, row.databaseName, row.username].any { it?.toString()?.toLowerCase()?.contains(search) }
    } : rows

    int totalCount = filtered.size()
    int fromIndex = Math.min(page * size, totalCount)
    int toIndex = Math.min(fromIndex + size, totalCount)
    configs = filtered.subList(fromIndex, toIndex)

    pagination = [
        pageIndex: page,
        pageSize: size,
        totalCount: totalCount,
        pageCount: Math.max(1, Math.ceil(totalCount / (double) size) as int)
    ]
}

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
