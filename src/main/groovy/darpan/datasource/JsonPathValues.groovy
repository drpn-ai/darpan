package darpan.datasource

/**
 * DAR-BE-176. Reads a dotted path out of an answer or a record, where `name[*]` steps into every element of a list:
 * `edges[*].node` on a connection gives every node; `shipGroups[*].facilityId` on an order gives each group's facility.
 * A missing step gives nothing, never an error. Pure.
 */
class JsonPathValues {

    /** Every value the path reaches, lists flattened at each [*]. */
    static List<Object> values(Object root, String path) {
        List<Object> current = [root]
        for (String raw : (path ?: "").split(/\./)) {
            if (!raw) continue
            boolean each = raw.endsWith("[*]")
            String name = each ? raw.substring(0, raw.length() - 3) : raw
            List<Object> next = []
            for (Object o : current) {
                if (!(o instanceof Map)) continue
                Object v = ((Map) o).get(name)
                if (each) { if (v instanceof List) next.addAll((List) v) }
                else if (v != null) next.add(v)
            }
            current = next
        }
        return current
    }

    /** The one value a path without [*] reaches, or null. */
    static Object value(Object root, String path) {
        List<Object> found = values(root, path)
        return found ? found[0] : null
    }
}
