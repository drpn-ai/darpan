package darpan.reconciliation.conclusion

import groovy.json.JsonSlurper

/**
 * DAR-BE-118. A conclusion lookup: a finding's record names a record in another system (an OMS return names its order's
 * NetSuite id), and a rule reads that record, attached under the lookup's name (orderInNetsuite.backordered).
 * Configured on the compare scope (RuleSetCompareScope.conclusionLookupsJson) as
 * [{name, subject (FILE_1|FILE_2), idField, systemEnumId, sourceConfigId, keyField, parameters?}]; parameters are extract
 * options the connector reads, e.g. {keepRecordFields: [orderId, notes]} to ask OMS for an order's notes.
 *
 * Asked as little as possible (Aditi 2026-10-23): only for findings whose first evaluation REACHED a rule reading the
 * name, each id once. Siblings are tried in sequence and the first match wins, so a finding reached rule L when L's
 * parent is on its path (or L is a root) and it matched no sibling of L before L. A finding that matched L itself is
 * asked too: it matched on a blank read, and the answer may change it. Pure: no Moqui.
 */
class ConclusionLookupSupport {

    static final List<String> REQUIRED = ["name", "subject", "idField", "systemEnumId", "sourceConfigId", "keyField"].asImmutable()

    static List<Map> parse(Object raw) {
        String text = raw?.toString()?.trim()
        if (!text) return []
        Object parsed = new JsonSlurper().parseText(text)
        if (!(parsed instanceof List)) throw new IllegalArgumentException("conclusionLookupsJson must be a list")
        return ((List) parsed).collect { Object o ->
            if (!(o instanceof Map)) throw new IllegalArgumentException("A conclusion lookup must be an object, got: ${o}")
            Map lookup = (Map) o
            List<String> missing = REQUIRED.findAll { !lookup.get(it)?.toString()?.trim() }
            if (missing) throw new IllegalArgumentException("Conclusion lookup ${lookup.name ?: ''} needs ${missing.join(', ')}")
            if (!(lookup.subject in ["FILE_1", "FILE_2"])) throw new IllegalArgumentException("A lookup's subject is FILE_1 or FILE_2, got: ${lookup.subject}")
            if (!(lookup.name ==~ /[A-Za-z][A-Za-z0-9_]{0,59}/)) throw new IllegalArgumentException("A lookup's name is one word, got: ${lookup.name}")
            if (lookup.parameters != null && !(lookup.parameters instanceof Map)) {
                throw new IllegalArgumentException("A lookup's parameters are an object of extract options, got: ${lookup.parameters}")
            }
            return lookup
        }
    }

    /** Per lookup name, the nodes whose conditions read it, and every node by id. */
    static Map plan(List<Map> nodes, List<Map> lookups) {
        Map<String, Set<String>> readers = [:]
        (lookups ?: []).each { Map lookup ->
            readers.put((String) lookup.name, nodes.findAll { Map n ->
                ((List<Map>) (n.conditions ?: [])).any { Map c -> c.subject == lookup.subject && reads(c, (String) lookup.name) }
            }.collect { (String) it.nodeId } as Set<String>)
        }
        return [nodes: nodes.collectEntries { [(it.nodeId): it] }, readers: readers]
    }

    /**
     * Could the answer change a finding concluded at `reachedNodeId` (null: unexplained)? Only if it tried a rule reading
     * lookup `name` whose other conditions already hold on its `sides`.
     */
    static boolean reaches(Map plan, String name, String reachedNodeId, String bucket, Map sides) {
        Map<String, Map> nodes = (Map<String, Map>) plan.nodes
        List<Map> path = []
        for (Map n = reachedNodeId ? nodes.get(reachedNodeId) : null; n != null; n = n.parentNodeId ? nodes.get(n.parentNodeId) : null) path.add(n)
        Set<String> onPath = path*.nodeId as Set<String>
        return ((Set<String>) ((Map) plan.readers).get(name) ?: []).any { String readerId ->
            Map reader = nodes.get(readerId)
            if (reader.appliesToBucket && reader.appliesToBucket != bucket) return false
            if (reader.parentNodeId && !onPath.contains(reader.parentNodeId)) return false
            Map matchedSibling = path.find { it.parentNodeId == reader.parentNodeId }
            if (matchedSibling != null && (matchedSibling.sequenceNum as int) < (reader.sequenceNum as int)) return false
            return ((List<Map>) (reader.conditions ?: [])).every { Map c ->
                reads(c, name) || ConclusionConditionSupport.check(c, (Map) (sides?.get(c.subject) ?: [:])).passed
            }
        }
    }

    private static boolean reads(Map condition, String name) {
        String field = condition.fieldExpression?.toString()
        return field == name || field?.startsWith("${name}.")
    }

    /** A copy of a side's state with each record carrying the looked-up record its idField names. */
    static Map attach(Map sideState, Map lookup, Map<String, Map> found) {
        Closure<Map> withAnswer = { Map record ->
            String id = record?.get(lookup.idField)?.toString()?.trim()
            Map answer = id ? found.get(id) : null
            return answer == null ? record : (record + [(lookup.name): answer])
        }
        Map copy = new LinkedHashMap(sideState)
        if (sideState.record instanceof Map) copy.record = withAnswer((Map) sideState.record)
        if (sideState.records instanceof List) copy.records = ((List<Map>) sideState.records).collect { withAnswer(it) }
        return copy
    }

    /** The ids a finding's subject side names for a lookup, from every record under its key. */
    static Set<String> idsOf(Map sideState, Map lookup) {
        List<Map> records = (sideState?.records instanceof List) ? (List<Map>) sideState.records :
                (sideState?.record instanceof Map ? [(Map) sideState.record] : [])
        return records.collect { it?.get(lookup.idField)?.toString()?.trim() }.findAll { it } as Set<String>
    }
}
