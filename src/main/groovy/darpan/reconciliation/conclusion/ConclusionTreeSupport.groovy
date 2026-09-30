package darpan.reconciliation.conclusion

/**
 * DAR-BE-063. The fact a finding becomes inside the conclusion engine, and the three calls the
 * generated DRL makes on it. Pure: no Moqui, no Drools types, no system names.
 *
 * A fact starts on ROOT. A node's rule moves it onto that node when the node matches (bucket, then
 * every condition); its children are then tried. The deepest node reached is the conclusion; a fact
 * that reached nothing is Unexplained. The path records every node reached, with the checks that
 * held, so "How Darpan concluded" can only ever show conditions that actually ran.
 */
class ConclusionTreeSupport {

    static final String ROOT = "ROOT"
    static final String UNEXPLAINED = "UNEXPLAINED"
    static final String UNEXPLAINED_LABEL = "Unexplained"

    static Map newFact(String bucket, Map sides) {
        Map fact = new HashMap()
        fact.put("_node", ROOT)
        fact.put("_bucket", bucket)
        fact.put("_sides", sides ?: [:])
        fact.put("_path", [])
        fact.put("_reached", null)
        return fact
    }

    static boolean matches(Map fact, Map node) {
        String applies = text(node.get("appliesToBucket"))
        if (applies && applies != fact.get("_bucket")) return false
        for (Map condition : ((List<Map>) (node.get("conditions") ?: []))) {
            if (!ConclusionConditionSupport.check(condition, sideFor(fact, condition)).passed) return false
        }
        return true
    }

    static void advance(Map fact, Map node) {
        List<Map> checks = ((List<Map>) (node.get("conditions") ?: [])).collect { Map condition ->
            Map r = ConclusionConditionSupport.check(condition, sideFor(fact, condition))
            [label: condition.get("checkLabel"), value: r.value, passed: true]
        }
        ((List) fact.get("_path")).add([code: node.get("conclusionEnumId"), label: node.get("label"), checks: checks])
        fact.put("_node", node.get("nodeId"))
        fact.put("_reached", node)
    }

    static Map result(Map fact) {
        Map node = (Map) fact.get("_reached")
        if (node == null) return [code: UNEXPLAINED, label: UNEXPLAINED_LABEL, checks: [], question: null, path: []]
        List<Map> path = (List<Map>) fact.get("_path")
        String questionText = text(node.get("questionText"))
        return [code    : node.get("conclusionEnumId"),
                label   : node.get("label"),
                checks  : path.collectMany { Map step -> (List) step.checks },
                question: questionText ? [text: questionText, suggestedFilter: node.get("suggestedFilter")] : null,
                path    : path]
    }

    private static Map sideFor(Map fact, Map condition) {
        return (Map) (((Map) fact.get("_sides")).get(condition.get("subject")) ?: [:])
    }

    private static String text(Object value) {
        String s = value?.toString()?.trim()
        return s ? s : null
    }
}
