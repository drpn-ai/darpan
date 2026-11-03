package darpan.agent

/**
 * DAR-CLI-003. The one GraphQL introspection query and the trim that turns its answer into a compact schema an agent
 * can read: only the types reachable from the Query root. Shared by every system's schema service (HotWax OMS, Shopify).
 */
class GraphqlSchemaTrim {

    static final String INTROSPECTION_QUERY = '''query IntrospectionQuery {
  __schema {
    queryType { name }
    types {
      kind name description
      fields(includeDeprecated: true) { name description args { name description defaultValue type { ...TypeRef } } type { ...TypeRef } }
      inputFields { name description defaultValue type { ...TypeRef } }
      interfaces { ...TypeRef }
      enumValues(includeDeprecated: true) { name description }
      possibleTypes { ...TypeRef }
    }
  }
}
fragment TypeRef on __Type { kind name ofType { kind name ofType { kind name ofType { kind name ofType { kind name ofType { kind name ofType { kind name ofType { kind name } } } } } } } }
'''

    /**
     * From the raw introspection data, only the types reachable from the Query root (through field types, argument
     * types, input-field types, interfaces and possible types), as compact maps. Mutation and Subscription roots are
     * not followed; a type reachable only through them is dropped.
     */
    static Map trim(Map data) {
        Map schema = (Map) data?.get("__schema")
        if (schema == null) throw new IllegalStateException("The system answered without a schema.")
        String root = ((Map) schema.queryType)?.name
        Map<String, Map> byName = [:]
        ((schema.types ?: []) as List<Map>).each { Map t -> if (t.name) byName[t.name as String] = t }
        LinkedHashSet<String> seen = new LinkedHashSet<>()
        ArrayDeque<String> todo = new ArrayDeque<>()
        if (root) todo.add(root)
        while (!todo.isEmpty()) {
            String name = todo.poll()
            if (!seen.add(name)) continue
            Map t = byName[name]
            if (t == null) continue
            List<Object> refs = []
            ((t.fields ?: []) as List<Map>).each { Map f ->
                refs << f.type
                ((f.args ?: []) as List<Map>).each { Map a -> refs << a.type }
            }
            ((t.inputFields ?: []) as List<Map>).each { Map f -> refs << f.type }
            ((t.interfaces ?: []) as List).each { refs << it }
            ((t.possibleTypes ?: []) as List).each { refs << it }
            refs.each { String n = namedType(it); if (n && !seen.contains(n)) todo.add(n) }
        }
        List<Map> types = seen.findAll { byName[it] != null }.collect { String n -> trimType(byName[n]) }
        return [queryType: root, types: types]
    }

    private static Map trimType(Map t) {
        Map out = [name: t.name, kind: t.kind]
        if (t.description) out.description = t.description
        if (t.fields) out.fields = ((List<Map>) t.fields).collect { Map f ->
            Map o = [name: f.name, type: typeString(f.type)]
            if (f.description) o.description = f.description
            if (f.args) o.args = ((List<Map>) f.args).collect { Map a -> argument(a) }
            o
        }
        if (t.inputFields) out.inputFields = ((List<Map>) t.inputFields).collect { Map a -> argument(a) }
        if (t.enumValues) out.enumValues = ((List<Map>) t.enumValues).collect { Map e ->
            e.description ? [name: e.name, description: e.description] : [name: e.name]
        }
        if (t.possibleTypes) out.possibleTypes = ((List<Map>) t.possibleTypes).collect { it.name }
        if (t.interfaces) out.interfaces = ((List<Map>) t.interfaces).collect { it.name }
        return out
    }

    private static Map argument(Map a) {
        Map o = [name: a.name, type: typeString(a.type)]
        if (a.description) o.description = a.description
        if (a.defaultValue != null) o.defaultValue = a.defaultValue
        return o
    }

    /** The named type under any NON_NULL / LIST wrapping. */
    static String namedType(Object ref) {
        Object cur = ref
        while (cur instanceof Map && ((Map) cur).name == null && ((Map) cur).ofType != null) cur = ((Map) cur).ofType
        return cur instanceof Map ? ((Map) cur).name as String : null
    }

    /** [Order!]! style. */
    static String typeString(Object ref) {
        if (!(ref instanceof Map)) return "?"
        Map m = (Map) ref
        if (m.kind == "NON_NULL") return typeString(m.ofType) + "!"
        if (m.kind == "LIST") return "[" + typeString(m.ofType) + "]"
        return m.name as String
    }

    /**
     * {@link #trim} for an answer that will be returned to an agent: the 5 MB cap applies to the TRIMMED schema (the raw
     * introspection answer is never capped). Over the cap, the call is refused with advice fit for a schema.
     */
    static Map trimWithinCap(Map data) {
        Map trimmed = trim(data)
        if (groovy.json.JsonOutput.toJson(trimmed).getBytes(java.nio.charset.StandardCharsets.UTF_8).length > AgentQuerySupport.MAX_BODY_BYTES - 64) {
            throw new AgentQuerySupport.AgentQueryRefused("The trimmed schema is larger than 5 MB, which is more than can be returned; " +
                    "ask the Darpan team to narrow this connection's schema.")
        }
        return trimmed
    }
}
