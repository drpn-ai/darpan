package darpan.judge

import darpan.reconciliation.conclusion.ExcludedRecordsSidecar

/**
 * DAR-BE-318 plan 2, Task 6 (D7). One fact per compare id. A pair row is [compareId, file1: Map or null, file2: Map or null]
 * (null = the side holds no record; the shape AskSupport.fetchAsks takes). The fact carries each side's presence, each
 * side's fields under the side's prefix, the check's parameters (param_<name>) and the asks (ask_<name>_<field>).
 *
 * Presence: KEPT when the side holds the record. SET_ASIDE when the side does not and the side's ".excluded.json" sidecar
 * names the compare id (a source filter dropped it). ABSENT otherwise, including when no sidecar can prove the drop.
 * The sidecar is read by setAsideIds, once per side, never per pair. No Moqui, no entity access here.
 */
class PairFactSupport {

    static final String KEPT = "KEPT"
    static final String SET_ASIDE = "SET_ASIDE"
    static final String ABSENT = "ABSENT"
    /** rules.FactField.fieldName is type id (VARCHAR 40). */
    static final int MAX_FIELD_NAME = 40

    /** The root system, lower-cased: OMS and HotWax systems are hotwax. */
    static String sidePrefix(String systemEnumId) {
        String id = (systemEnumId ?: "").trim().toUpperCase()
        if (!id) return "file"
        if (id == "OMS" || id.startsWith("HOTWAX") || id.startsWith("OMS_")) return "hotwax"
        String root = id.split("[^A-Z0-9]+")[0]
        return (root ?: "file").toLowerCase()
    }

    /** [prefix1, prefix2]. A shared prefix becomes <prefix>1 and <prefix>2 so no field collides. */
    static List<String> sidePrefixes(String system1, String system2) {
        String p1 = sidePrefix(system1)
        String p2 = sidePrefix(system2)
        return p1 == p2 ? [p1 + "1", p2 + "2"] : [p1, p2]
    }

    /** <prefix>_<key with every character outside [A-Za-z0-9_] replaced by _>, cut to the field name limit. */
    static String fieldName(String prefix, String key) {
        String name = prefix + "_" + (key ?: "").replaceAll("[^A-Za-z0-9_]", "_")
        return name.length() > MAX_FIELD_NAME ? name.substring(0, MAX_FIELD_NAME) : name
    }

    /**
     * key -> field name for a side. The tenant's FactType is the one source of truth: every existing key -> name pair is kept as
     * is (taken from the FactField descriptions), and only keys not seen before are named, in sorted order, with a numeric suffix
     * (_2, _3 ...) when the name is taken, ignoring case (the base is cut so the suffix still fits the limit). Nothing here depends on which
     * other keys a run happens to hold, and buildFact never derives a name itself.
     */
    static Map<String, String> nameKeys(String prefix, Map<String, String> existing, Collection<String> keys) {
        Map<String, String> names = new LinkedHashMap<>(existing ?: [:])
        // Names clash ignoring case: prod MySQL compares the FactField key case-insensitively (utf8_general_ci), H2 does not.
        Set<String> used = new HashSet<>(names.values().collect { it.toLowerCase() })
        used.add((prefix + "_presence").toLowerCase())   // a side key can never take the presence field's name
        (keys ?: []).collect { it as String }.toSet().sort().each { String key ->
            if (names.containsKey(key)) return
            String base = fieldName(prefix, key)
            String name = base
            int n = 2
            while (!used.add(name.toLowerCase())) {
                String suffix = "_" + n++
                name = (base.length() + suffix.length() > MAX_FIELD_NAME ? base.substring(0, MAX_FIELD_NAME - suffix.length()) : base) + suffix
            }
            names.put(key, name)
        }
        return names
    }

    /**
     * pairRow: [compareId, file1, file2]. sideState: [factTypeId, file1: [prefix, setAside: Set, keyNames: Map key->field name], file2: [...]]
     * (keyNames come from generate#PairFactType). A side key with no name is refused: the caller generates from every key first.
     * Returns [factTypeId, recordId: compareId, fact: [...]], the shape moqui-rules evaluate takes as a fact.
     */
    static Map<String, Object> buildFact(Map pairRow, Map sideState, Map params, Map askFields) {
        String compareId = pairRow.compareId as String
        Map<String, Object> fact = new LinkedHashMap<>()
        fact.put("compareId", compareId)
        ["file1", "file2"].each { String side ->
            Map record = pairRow.get(side) as Map
            Map state = (sideState.get(side) ?: [:]) as Map
            String prefix = (state.prefix ?: side) as String
            Collection<String> setAside = (state.setAside ?: []) as Collection<String>
            String presence = record != null ? KEPT : (setAside.contains(compareId) ? SET_ASIDE : ABSENT)
            fact.put(prefix + "_presence", presence)
            if (record != null) {
                Map<String, String> names = (state.keyNames ?: [:]) as Map<String, String>
                record.each { Object key, Object value ->
                    String name = names.get(key as String)
                    if (name == null) throw new IllegalStateException("key ${key} of ${side} has no field name in the pair fact type".toString())
                    fact.put(name, value)
                }
            }
        }
        (params ?: [:]).each { Object name, Object value -> fact.put("param_" + name, value) }
        (askFields ?: [:]).each { Object name, Object value -> fact.put(name as String, value) }
        return [factTypeId: sideState.factTypeId, recordId: compareId, fact: fact] as Map<String, Object>
    }

    /**
     * Pairs in batches of size. A compare id seen twice in the whole judging is refused when the second is read, never merged
     * (one fact per compare id). The ids seen are held as a set of strings for the judging.
     */
    static Iterator<List<Map>> batches(Iterator<Map> pairs, int size = 500) {
        return new BatchIterator(pairs, size)
    }

    private static class BatchIterator implements Iterator<List<Map>> {
        private final Iterator<Map> pairs
        private final int size
        private final Set<String> seen = new HashSet<>()

        BatchIterator(Iterator<Map> pairs, int size) {
            this.pairs = pairs
            this.size = size
        }

        boolean hasNext() { return pairs.hasNext() }

        List<Map> next() {
            if (!pairs.hasNext()) throw new NoSuchElementException()
            List<Map> batch = new ArrayList<>(size)
            while (batch.size() < size && pairs.hasNext()) {
                Map row = pairs.next()
                String id = row.compareId as String
                if (!id?.trim()) throw new IllegalStateException("a pair has no compare id".toString())
                if (!seen.add(id)) throw new IllegalStateException("compare id ${id} appears twice in one judging".toString())
                batch.add(row)
            }
            return batch
        }
    }

    /**
     * The compare ids a side's source filters set aside: streams the extract's ".excluded.json" sidecar and keys each record
     * by the side's compare id expression (compareIdOf). Returns [ids: Set, present, truncated]. A missing or truncated sidecar
     * cannot prove a drop, so the caller reads those ids as ABSENT and may warn.
     */
    static Map setAsideIds(File extractFile, Closure<String> compareIdOf) {
        Set<String> ids = new HashSet<>()
        Map head = ExcludedRecordsSidecar.forEachRecord(extractFile) { Map record ->
            String id = compareIdOf.call(record)
            if (id) ids.add(id)
        }
        return [ids: ids, present: head.present == true, truncated: head.truncated == true]
    }
}
