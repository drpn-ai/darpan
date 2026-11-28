package darpan.judge

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-318 plan 2, Task 5 (D6). A check's asks are declared as data (darpan.judge.CheckAsk) and fetched once per ask
 * for a whole batch of pairs, never per pair. Each fetched field lands on the pair fact as ask_<askName>_<field>.
 *
 * No entity access and no system here: the CheckAsk rows come from judge.JudgeConfigServices.get#CheckJudgeConfig (read once
 * per judging, tenant-scoped there), and every answer comes from the lookup call the caller passes. There is no default, so a
 * caller always says where answers come from: a replay passes JudgeReplaySupport.capturedFetcher (ruling P2-24).
 */
class AskSupport {

    /**
     * askRows: the check's CheckAsk rows, as get#CheckJudgeConfig returns them. pairFacts: rows [compareId, file1 Map or null,
     * file2 Map or null] (null = side absent), before the fact is built. fetchWith: the lookup call, with
     * RunConclusionStep.fetchLookup's signature (ec, lookup, ids, tenantId, warnings); required.
     * Returns compareId -> [ask_<askName>_<field>: value] for every pair whose subject side has the id field. A pair
     * whose subject side is absent or whose id is blank is skipped. A failed fetch adds a warning and no fields.
     */
    static Map<String, Map<String, Object>> fetchAsks(def ec, List<Map> askRows, String tenantId, List<Map> pairFacts,
                                                      List<String> warnings, Closure fetchWith) {
        if (fetchWith == null) throw new IllegalArgumentException("Asks need a lookup call: there is no default, so nothing is fetched live by accident")
        Map<String, Map<String, Object>> result = [:]
        (askRows ?: []).collect { toLookup(it) }.each { Map ask ->
            Map<String, List<String>> compareIdsById = [:]   // subject id -> the pairs that carry it
            (pairFacts ?: []).each { Object raw ->
                List row = raw as List
                Map side = (ask.subject == "FILE_1" ? row[1] : row[2]) as Map
                String id = side == null ? null : normalize(side.get(ask.idField))
                if (id) compareIdsById.computeIfAbsent(id) { [] }.add(row[0] as String)
            }
            if (compareIdsById.isEmpty()) return
            Map<String, Object> found = (fetchWith.call(ec, ask, new ArrayList<String>(compareIdsById.keySet()), tenantId, warnings) ?: [:]) as Map
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
    private static Map toLookup(Map row) {
        List<String> keep = (normalize(row.keepRecordFields) ?: "").split(",").collect { it.trim() }.findAll { it } as List<String>
        Map lookup = [name: row.askName, subject: row.subjectSide, idField: row.idField, systemEnumId: row.systemEnumId,
                      sourceConfigId: row.sourceConfigId, keyField: row.keyField, many: normalize(row.many) == "Y"]
        if (keep) lookup.parameters = [keepRecordFields: keep]
        return lookup
    }

    /** ask_<name>_<field> for each kept field (every field when none are named); a many ask joins values with |. */
    private static Map<String, Object> fieldsOf(Map ask, Object answer) {
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
}
