package darpan.judge

import darpan.facade.reconciliation.RunConclusionStep

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-318 plan 2, Task 5 (D6). A check's asks are declared as data (darpan.judge.CheckAsk) and fetched once per ask
 * for a whole batch of pairs, never per pair. Each fetched field lands on the pair fact as ask_<askName>_<field>.
 *
 * Runs on the request thread as the tenant user (no RunAdmission, no withSystemTenant, no disableAuthz): the CheckAsk read
 * rides the DARPAN_ACTIVE_COMPANY_SCOPE filter and also names the tenant, so a foreign checkId gives no asks.
 */
class AskSupport {

    /** The lookup call, injectable so tests need no connector. Signature of RunConclusionStep.fetchLookup. */
    static Closure fetcher = RunConclusionStep.&fetchLookup

    /** The CheckAsk read, injectable for pure tests: (ec, checkId, tenantId) -> List of row maps. */
    static Closure reader = { Object ec, String checkId, String tenantId -> readRows(ec, checkId, tenantId) }

    /** Restores the process-global seams to their defaults (DAR-OPS-026 shared-Moqui harness). */
    static void resetForTests() {
        fetcher = RunConclusionStep.&fetchLookup
        reader = { Object ec, String checkId, String tenantId -> readRows(ec, checkId, tenantId) }
    }

    /**
     * pairFacts: rows [compareId, file1 Map or null, file2 Map or null] (null = side absent), before the fact is built.
     * Returns compareId -> [ask_<askName>_<field>: value] for every pair whose subject side has the id field. A pair
     * whose subject side is absent or whose id is blank is skipped. A failed fetch adds a warning and no fields.
     * fetchWith: the lookup call for this judging only (a replay's, which answers from captured lookups, ruling P2-24);
     * none uses fetcher. Passed per call, so a replay never changes what a live judging in the same JVM fetches with.
     */
    static Map<String, Map<String, Object>> fetchAsks(def ec, String checkId, String tenantId, List<Map> pairFacts,
                                                      List<String> warnings, Closure fetchWith = null) {
        Closure fetch = fetchWith ?: fetcher
        Map<String, Map<String, Object>> result = [:]
        if (!normalize(checkId) || !normalize(tenantId)) return result
        List<Map> asks = ((reader.call(ec, checkId, tenantId) as List) ?: []).collect { toLookup(it as Map) }
        asks.each { Map ask ->
            Map<String, List<String>> compareIdsById = [:]   // subject id -> the pairs that carry it
            (pairFacts ?: []).each { Object raw ->
                List row = raw as List
                Map side = (ask.subject == "FILE_1" ? row[1] : row[2]) as Map
                String id = side == null ? null : normalize(side.get(ask.idField))
                if (id) compareIdsById.computeIfAbsent(id) { [] }.add(row[0] as String)
            }
            if (compareIdsById.isEmpty()) return
            Map<String, Object> found = (fetch.call(ec, ask, new ArrayList<String>(compareIdsById.keySet()), tenantId, warnings) ?: [:]) as Map
            compareIdsById.each { String id, List<String> compareIds ->
                Object answer = found.get(id)
                if (answer == null) return
                Map<String, Object> fields = fieldsOf(ask, answer)
                compareIds.each { String compareId -> result.computeIfAbsent(compareId) { [:] }.putAll(fields) }
            }
        }
        return result
    }

    /** One CheckAsk row to the lookup map ConclusionLookupSupport.parse produces. */
    static Map toLookup(Map row) {
        List<String> keep = (normalize(row.keepRecordFields) ?: "").split(",").collect { it.trim() }.findAll { it } as List<String>
        Map lookup = [name: row.askName, subject: row.subjectSide, idField: row.idField, systemEnumId: row.systemEnumId,
                      sourceConfigId: row.sourceConfigId, keyField: row.keyField, many: normalize(row.many) == "Y"]
        if (keep) lookup.parameters = [keepRecordFields: keep]
        return lookup
    }

    /** ask_<name>_<field> for each kept field (every field when none are named); a many ask joins values with |. */
    static Map<String, Object> fieldsOf(Map ask, Object answer) {
        List<Map> records = (answer instanceof List ? answer : [answer]).findAll { it instanceof Map } as List<Map>
        List<String> keep = ((ask.parameters as Map)?.get("keepRecordFields") ?: []) as List<String>
        List<String> names = keep ?: (records.collectMany { it.keySet().collect { k -> k.toString() } }.unique() as List<String>)
        Map<String, Object> out = [:]
        names.each { String field ->
            List<String> values = records.collect { it.get(field) }.findAll { it != null }.collect { it.toString() }
            if (!values) return
            out.put("ask_${ask.name}_${field}".toString(), ask.many == true ? values.join("|") : values.first())
        }
        return out
    }

    private static List<Map> readRows(def ec, String checkId, String tenantId) {
        return ec.entity.find("darpan.judge.CheckAsk").condition("checkId", checkId).condition("companyUserGroupId", tenantId)
                .orderBy("askName").useCache(false).list().collect { it.getMap() as Map }
    }
}
