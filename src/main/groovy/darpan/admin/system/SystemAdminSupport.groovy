package darpan.admin.system

import groovy.transform.CompileDynamic

import java.sql.Connection
import java.util.concurrent.ThreadPoolExecutor

/**
 * DAR-ADMIN-017. The Moqui side of admin.SystemAdminServices: the read-only datasource (with its test seam) and the
 * server facts the closed Moqui tools app's System dashboard used to show. The guards themselves are in
 * {@link SqlReadOnlyRunner}, which needs no Moqui.
 */
@CompileDynamic
class SystemAdminSupport {
    /** Test seam: a connection to use instead of the darpan_sql_readonly datasource. Null in production. */
    static Closure<Connection> readOnlyConnection = null

    static void resetForTests() { readOnlyConnection = null }

    /** Null when SQL can run here, else why not. */
    static String readOnlyProblem(def ec) {
        if (readOnlyConnection != null) return null
        return SqlReadOnlyRunner.readOnlySourceProblem((List<Map>) ec.entity.getDataSourcesInfo())
    }

    /** Runs a checked statement on the read-only datasource. Refuses (throws) if it is not set up: never the app's own. */
    static Map query(def ec, String sql, int maxRows) {
        String problem = readOnlyProblem(ec)
        if (problem) throw new IllegalStateException(problem)
        Connection con = readOnlyConnection != null ? readOnlyConnection.call() : ec.entity.getConnection(SqlReadOnlyRunner.READ_ONLY_GROUP)
        try {
            return SqlReadOnlyRunner.run(con, sql, maxRows, SqlReadOnlyRunner.TIMEOUT_SECONDS)
        } finally {
            con.close()
        }
    }

    static Map serverInfo(def ec) {
        def factory = ec.factory
        Map statusMap = factory.getStatusMap(true)
        List dataSources = []
        for (Object ds in (statusMap.DataSources ?: [])) {
            dataSources.add(ds instanceof Map ? new LinkedHashMap((Map) ds) + [detail: withoutUrlQuery(((Map) ds).get("detail"))] : ds)
        }
        statusMap.DataSources = dataSources
        return [
            moquiVersion         : statusMap.MoquiFramework,
            versionMap           : factory.versionMap ?: [:],
            components           : factory.getComponentInfoList().collect { [name: it.name, version: it.version, location: it.location] }
                                           .sort { it.name },
            statusMap            : statusMap,
            workerPool           : pool(factory.workerPool),
            jobWorkerPool        : pool(ec.service.jobWorkerPool),
            sqlReadOnlyConfigured: readOnlyProblem(ec) == null,
        ]
    }

    /** A JDBC URL's query string can carry a password; the server facts never show it. */
    static Map withoutUrlQuery(Object detail) {
        if (!(detail instanceof Map)) return null
        Map out = new LinkedHashMap()
        for (Map.Entry entry in ((Map) detail).entrySet()) {
            Object v = entry.value
            out.put(entry.key, (entry.key in ["uri", "url"] && v) ? v.toString().replaceFirst('\\?.*$', '') : v)
        }
        return out
    }

    private static Map pool(ThreadPoolExecutor executor) {
        if (executor == null) return null
        return [poolSize: executor.poolSize, activeCount: executor.activeCount, maxPoolSize: executor.maximumPoolSize,
                queueSize: executor.queue.size()]
    }
}
