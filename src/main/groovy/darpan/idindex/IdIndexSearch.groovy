package darpan.idindex

import org.moqui.entity.EntityCondition

/**
 * DAR-BE-374: an ID search. One read of the normalised value finds every record that holds it, in any system and role; then
 * a breadth-first walk inside the index follows the values those records hold (a REF to the record that owns it, an OWN value
 * to the records that name it, a value two systems share) up to maxHops, maxRecords records, and never through a value more
 * than maxRowsPerValue rows share (a hub, such as a store id indexed by mistake). Reads are seams: tests pass an in-memory index.
 * Every row list is walked in ROW_ORDER (OC101), whatever order the reads hand it over in.
 */
class IdIndexSearch {

    static final int MAX_RECORDS = 200, MAX_HOPS = 4, MAX_ROWS_PER_VALUE = 500

    /**
     * deps: byIdNorm(String) -> rows holding the value; byRecord(List<[connectionId, recordKind, recordKey]>) -> every row of
     * those records; maxRecords, maxHops, maxRowsPerValue. Returns [value, idNorm, records, capped, cappedReason].
     */
    static Map search(Map deps, String value) {
        String norm = IdNormalizer.normalize(value)
        Map out = [value: value, idNorm: norm, records: [], capped: false, cappedReason: null]
        if (!norm) return out
        int maxRecords = (deps.maxRecords ?: MAX_RECORDS) as int
        int maxHops = deps.maxHops != null ? deps.maxHops as int : MAX_HOPS
        int maxRows = (deps.maxRowsPerValue ?: MAX_ROWS_PER_VALUE) as int
        Closure<List<Map>> byIdNorm = (Closure<List<Map>>) deps.byIdNorm
        Closure<List<Map>> byRecord = (Closure<List<Map>>) deps.byRecord

        Map<String, Map> records = new LinkedHashMap<>()
        Set<String> followed = new HashSet<>([norm])
        Closure<Boolean> add = { Map row, Map foundBy ->
            String key = keyOf(row)
            if (records.containsKey(key)) return true
            if (records.size() >= maxRecords) { out.capped = true; out.cappedReason = out.cappedReason ?: "at most ${maxRecords} records".toString(); return false }
            records.put(key, [systemCode: row.systemCode, systemEnumId: row.systemEnumId, connectionId: row.connectionId,
                              recordKind: row.recordKind, recordKey: row.recordKey, ids: [], foundBy: foundBy, indexedAt: null])
            return true
        }

        List<Map> hits = inOrder(byIdNorm.call(norm))
        if (hits.size() > maxRows) { out.capped = true; out.cappedReason = "hub value ${value}".toString() }
        List<String> frontier = []
        for (Map hit : hits) {
            if (!add(hit, [idRaw: hit.idRaw, fieldPath: hit.fieldPath, viaRecordKey: null, viaFieldPath: null])) break
            if (!(keyOf(hit) in frontier)) frontier << keyOf(hit)
        }
        int hop = 0
        while (frontier) {
            List<Map> rows = inOrder(byRecord.call(frontier.collect { refOf(records.get(it)) }))
            Map<String, List<Map>> rowsByRecord = rows.groupBy { keyOf(it) }
            frontier.each { String key ->
                Map rec = records.get(key)
                List<Map> own = rowsByRecord.get(key) ?: []
                rec.ids = own.collect { [fieldPath: it.fieldPath, idRaw: it.idRaw, role: it.role] }
                rec.indexedAt = own*.lastSeenAt.findAll { it != null }.max()
            }
            if (hop >= maxHops || out.capped) break
            List<String> next = []
            for (String key : frontier) {
                if (out.capped && records.size() >= maxRecords) break
                for (Map row : (rowsByRecord.get(key) ?: [])) {
                    String v = row.idNorm as String
                    if (!followed.add(v)) continue
                    List<Map> sharing = inOrder(byIdNorm.call(v))
                    if (sharing.size() > maxRows) { out.capped = true; out.cappedReason = out.cappedReason ?: "hub value ${row.idRaw}".toString(); continue }
                    for (Map other : sharing) {
                        String otherKey = keyOf(other)
                        if (records.containsKey(otherKey)) continue
                        if (!add(other, [idRaw: other.idRaw, fieldPath: other.fieldPath, viaRecordKey: records.get(key).recordKey, viaFieldPath: row.fieldPath])) break
                        next << otherKey
                    }
                }
            }
            frontier = next.unique()
            hop++
        }
        out.records = new ArrayList<Map>(records.values())
        return out
    }

    /** The live reads for one tenant: every condition carries companyUserGroupId (the tenant filter is skipped with authz off). */
    static Map liveDeps(def ec, String tenantId) {
        return [byIdNorm: { String v ->
                    ec.entity.find(IdIndexer.ENTITY).condition("companyUserGroupId", tenantId).condition("idNorm", v).orderBy(DB_ORDER)
                            .limit(MAX_ROWS_PER_VALUE + 1).useCache(false).disableAuthz().list().collect { it.getMap() }
                },
                byRecord: { List<Map> refs ->
                    List<Map> out = []
                    refs.groupBy { [it.connectionId, it.recordKind] }.each { List k, List<Map> ofKind ->
                        out.addAll(ec.entity.find(IdIndexer.ENTITY).condition("companyUserGroupId", tenantId).condition("connectionId", k[0])
                                .condition("recordKind", k[1]).condition("recordKey", EntityCondition.IN, ofKind*.recordKey).orderBy(DB_ORDER)
                                .useCache(false).disableAuthz().list().collect { it.getMap() })
                    }
                    return out
                }]
    }

    /**
     * get#IdIndexStatus' rows: one per kept copy of a system the index reads, whether its current version is indexed (behind
     * until the next good pass or the nightly catch-up), when it last was, and the last failure (kept until a pass succeeds).
     */
    static List<Map> statusRows(List<Map> datasets, List<Map> cursors) {
        Map<String, Map> cursorOf = cursors.collectEntries { [(it.datasetId as String): it] }
        return datasets.collect { Map d ->
            Map c = cursorOf.get(d.datasetId as String) ?: [:]
            boolean behind = d.currentVersion != null && c.indexedVersion != d.currentVersion
            [datasetId: d.datasetId, connectionId: d.sourceConfigId, systemEnumId: d.systemEnumId, lastReadAt: d.lastReadAt,
             lastIndexedAt: c.lastIndexedAt, rowsLastPass: c.rowsLastPass, lastStatus: c.lastStatus ?: "NOT_INDEXED",
             lastError: c.lastError, behind: behind, stale: behind || c.lastStatus == "FAILED"]
        }
    }

    /** OC101: the one order every row list is walked in, so one index state gives one answer (records, their ids, a capped set). */
    static final List<String> ROW_ORDER = ["systemCode", "connectionId", "recordKind", "recordKey", "fieldPath", "idNorm"].asImmutable()
    /** The reads' order: ROW_ORDER, then the row's unique key, so the hub-limited read is the same rows under any collation. */
    static final List<String> DB_ORDER = (ROW_ORDER + ["rowKey"]).asImmutable()

    private static List<Map> inOrder(List<Map> rows) {
        return (rows ?: []).sort(false) { Map a, Map b ->
            for (String f : ROW_ORDER) {
                int c = ((a.get(f) ?: "") as String) <=> ((b.get(f) ?: "") as String)
                if (c != 0) return c
            }
            return 0
        }
    }

    private static String keyOf(Map row) { return "${row.systemCode}\u001F${row.connectionId}\u001F${row.recordKind}\u001F${row.recordKey}".toString() }

    private static Map refOf(Map rec) { return [connectionId: rec.connectionId, recordKind: rec.recordKind, recordKey: rec.recordKey] }
}
