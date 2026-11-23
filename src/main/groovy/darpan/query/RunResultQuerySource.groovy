package darpan.query

import darpan.facade.reconciliation.ReconciliationOutputSupport
import darpan.script.ScriptDeploymentSupport
import darpan.script.TenantScriptSupport

/**
 * DAR-BE-334. The "runResult" read source: the differences of a saved run's newest result, read through the same
 * tenant-checked reader as the result page. A row is a difference's own values, its record's values and the record's
 * data fields, flat.
 */
class RunResultQuerySource {
    private static final Closure<String> DEFAULT_RESULT_FILE = { def ec, String savedRunId -> ScriptDeploymentSupport.newestResultFileName(ec, savedRunId) }
    private static final Closure<Map> DEFAULT_PAGE = { def ec, String fileName, int pageIndex, int pageSize ->
        ReconciliationOutputSupport.getGeneratedOutputDifferences(ec, fileName, pageIndex, pageSize, null, "all", null, false, null)
    }
    /** Seams over the tenant-checked result reader; replaced in tests */
    static Closure<String> resultFileOf = DEFAULT_RESULT_FILE
    static Closure<Map> differencesPage = DEFAULT_PAGE

    static void resetForTests() { resultFileOf = DEFAULT_RESULT_FILE; differencesPage = DEFAULT_PAGE }

    static Map<String, Object> read(def ec, Map params) {
        Map input = (Map) params.input
        int maxRows = (params.maxRows ?: 20_000) as int
        return QuerySourceScope.withTenant(ec, params) { String tenantId ->
            String savedRunId = input.savedRunId as String
            String fileName = resultFileOf.call(ec, savedRunId)
            if (ec.message.hasError()) {
                String errors = ec.message.errorsString
                ec.message.clearErrors()
                return QuerySourceScope.error("READ_NOT_ALLOWED", "Saved run ${savedRunId}: ${errors}")
            }
            if (!fileName) return QuerySourceScope.error("READ_ERROR", "Saved run ${savedRunId} has no finished result yet.")
            int pageSize = ReconciliationOutputSupport.MAX_DIFF_PAGE_SIZE
            // NOTE: an anonymous class in a static method cannot reach the outer class's statics; capture them
            Closure<Map> pageOf = differencesPage
            Closure<Map> flat = { Object d -> RunResultQuerySource.flatten(TenantScriptSupport.withParsedData(d)) }
            Iterator<Map> records = new Iterator<Map>() {
                int pageIndex = 0
                List<Map> page = null
                int at = 0
                boolean done = false
                boolean hasNext() {
                    if (page != null && at < page.size()) return true
                    if (done) return false
                    Map out = pageOf.call(ec, fileName, pageIndex, pageSize)
                    List rows = out?.get("differences") instanceof List ? (List) out.get("differences") : []
                    page = rows.collect { Object d -> flat.call(d) }
                    at = 0
                    pageIndex++
                    if (rows.size() < pageSize || pageIndex >= ((out?.get("pageCount") ?: 0) as int)) done = true
                    return !page.isEmpty()
                }
                Map next() { return page[at++] }
            }
            return QueryRows.collect(records, input, null, maxRows)
        }
    }

    /** A difference's scalar values, its record's scalar values, and the record's data fields, flat (data wins). */
    static Map flatten(Object difference) {
        Map out = new LinkedHashMap()
        if (!(difference instanceof Map)) return out
        Map d = (Map) difference
        d.each { k, v -> if (!(v instanceof Map) && !(v instanceof Collection)) out.put(k, v) }
        if (d.record instanceof Map) {
            Map record = (Map) d.record
            record.each { k, v -> if (!(v instanceof Map) && !(v instanceof Collection)) out.put(k, v) }
            if (record.data instanceof Map) out.putAll((Map) record.data)
        }
        return out
    }
}
