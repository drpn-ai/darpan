import darpan.facade.common.FacadeSupport

int page = Math.max(0, FacadeSupport.normalizeInt(pageIndex, 0))
int size = Math.max(1, Math.min(200, FacadeSupport.normalizeInt(pageSize, 20)))

List<Map> rows = []
(ec.entity.find("darpan.reconciliation.SftpServer")
    .useCache(false)
    .orderBy("description,sftpServerId")
    .list() ?: []).each { s ->
    rows.add([
        sftpServerId: s.sftpServerId,
        description: s.description,
        host: s.host,
        port: s.port,
        username: s.username,
        remoteAttributes: s.remoteAttributes ?: "Y",
        hasPassword: !!s.password,
        hasPrivateKey: !!s.privateKey,
    ])
}

String search = FacadeSupport.normalize(query)?.toLowerCase()
List<Map> filtered = search ? rows.findAll { row ->
    [row.sftpServerId, row.description, row.host, row.username].any { it?.toString()?.toLowerCase()?.contains(search) }
} : rows

int totalCount = filtered.size()
int fromIndex = Math.min(page * size, totalCount)
int toIndex = Math.min(fromIndex + size, totalCount)
servers = filtered.subList(fromIndex, toIndex)

pagination = [
    pageIndex: page,
    pageSize: size,
    totalCount: totalCount,
    pageCount: Math.max(1, Math.ceil(totalCount / (double) size) as int)
]

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
