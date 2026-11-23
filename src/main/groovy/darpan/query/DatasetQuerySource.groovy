package darpan.query

import darpan.dataset.DatasetRefreshSupport
import darpan.dataset.DatasetSupport
import darpan.dataset.KeptCopyStore
import org.moqui.jexl.script.ReadConditions

import java.sql.Timestamp

/**
 * DAR-BE-334. The "dataset" read source: a tenant's kept copy of a source read (darpan.dataset.Dataset), streamed from
 * its current version. A query reads only what audits already keep (DatasetSupport.keptFields); a field no longer kept
 * is a READ_ERROR naming it. The input's window is the last N days on the dataset's own window field.
 */
class DatasetQuerySource {
    private static final Closure<Set<String>> DEFAULT_KEPT_FIELDS = { def ec, Map dataset -> DatasetSupport.keptFields(ec, dataset) }
    /** The fields a dataset's copy keeps (null = whole records); replaced in tests */
    static Closure<Set<String>> keptFieldsOf = DEFAULT_KEPT_FIELDS

    static void resetForTests() { keptFieldsOf = DEFAULT_KEPT_FIELDS }

    static Map<String, Object> read(def ec, Map params) {
        Map input = (Map) params.input
        int maxRows = (params.maxRows ?: 20_000) as int
        Timestamp runDate = (params.runDate ?: ec.user.nowTimestamp) as Timestamp
        return QuerySourceScope.withTenant(ec, params) { String tenantId ->
            String datasetId = input.datasetId as String
            def dataset = datasetId ? ec.entity.find("darpan.dataset.Dataset").condition("datasetId", datasetId).useCache(false).one() : null
            if (dataset == null || dataset.companyUserGroupId != tenantId) {
                return QuerySourceScope.error("READ_NOT_ALLOWED", "Dataset ${datasetId} is not one of this tenant's.")
            }
            Set<String> kept = keptFieldsOf.call(ec, dataset.getMap())
            if (kept != null) {
                String gone = QueryRows.namedFields(input).find { String f -> !kept.contains(f) }
                if (gone) return QuerySourceScope.error("READ_ERROR", "${gone} is no longer kept for dataset ${datasetId}: no audit reads it now.")
            }
            File dir = DatasetRefreshSupport.datasetDir(ec, tenantId, datasetId)
            if (KeptCopyStore.manifest(dir) == null) return QuerySourceScope.error("READ_ERROR", "Dataset ${datasetId} has no kept copy yet.")
            Map windowCondition = input.window ? ReadConditions.windowCondition([field: dataset.windowField, lastDays: ((Map) input.window).lastDays],
                    runDate, params.timeZone as String) : null
            return KeptCopyStore.withRecords(dir) { Iterator<Map> entries ->
                Iterator<Map> records = [hasNext: { -> entries.hasNext() }, next: { -> (Map) entries.next()?.get("record") }] as Iterator<Map>
                return QueryRows.collect(records, input, windowCondition, maxRows)
            }
        }
    }
}
