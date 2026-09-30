package darpan.reconciliation.conclusion

/**
 * DAR-BE-063. Conclusion rows to DRL, the FieldComparisonRuleLogicGenerator way: structured rows in,
 * server-generated DRL out. Stricter than that precedent: the DRL carries NO row text at all — only
 * generated ids (N<index>), integers and fixed text. Every label, condition, value and bucket reaches
 * Drools through the `nodes` global, so there is nothing to escape and nothing to inject.
 *
 * One rule per node. It fires for a fact sitting on the node's parent (ROOT for a root) whose
 * ConclusionTreeSupport.matches holds, moves the fact onto the node and update()s it — which retracts
 * the fact from every sibling's match, so the highest-salience sibling wins PER FACT. activation-group
 * is deliberately not used: it is session-global, and one finding's match would cancel every other
 * finding's activations in the same session.
 */
class ConclusionRuleLogicGenerator {

    static final String PACKAGE = "darpan.conclusion.tree"
    static final int MAX_DEPTH = 3
    static final int MAX_SEQUENCE = 999_999

    /** Rows (RunConclusionStep.buildRules shape) to nodes with ids, resolved parents and checked depth. */
    static List<Map> assignNodes(List<Map> rules) {
        List<Map> nodes = []
        (rules ?: []).eachWithIndex { Map rule, int i ->
            Map node = new LinkedHashMap(rule)
            Object seq = rule.get("sequenceNum")
            node.put("sequenceNum", seq == null ? (i + 1) * 10 : (seq as int))
            node.put("nodeId", "N${i}".toString())
            nodes.add(node)
        }
        Map<Integer, Map> bySeq = [:]
        nodes.each { Map n ->
            int seq = n.sequenceNum as int
            if (seq < 0 || seq > MAX_SEQUENCE) {
                throw new IllegalArgumentException("Conclusion rule sequenceNum ${seq} is outside 0..${MAX_SEQUENCE}")
            }
            if (bySeq.put(seq, n) != null) throw new IllegalArgumentException("Two conclusion rules share sequenceNum ${seq}")
        }
        nodes.each { Map n ->
            Object parentSeq = n.get("parentSequenceNum")
            if (parentSeq == null) {
                n.put("parentNodeId", null)
                return
            }
            Map parent = bySeq.get(parentSeq as int)
            if (parent == null) {
                throw new IllegalArgumentException("Conclusion rule ${n.sequenceNum} names parent ${parentSeq}, which does not exist")
            }
            n.put("parentNodeId", parent.nodeId)
        }
        nodes.each { Map n -> checkDepth(n, bySeq) }
        return nodes
    }

    static String generate(List<Map> nodes) {
        StringBuilder drl = new StringBuilder()
        drl.append("package ${PACKAGE}\n")
        drl.append("import java.util.Map\n")
        drl.append("global java.util.Map nodes\n")
        (nodes ?: []).each { Map n ->
            String id = (String) n.nodeId
            String parent = (String) (n.parentNodeId ?: ConclusionTreeSupport.ROOT)
            int salience = MAX_SEQUENCE + 1 - (n.sequenceNum as int)
            drl.append("\nrule \"${id}\"\n")
            drl.append("    salience ${salience}\n")
            drl.append("when\n")
            drl.append("    \$f : Map(this[\"_node\"] == \"${parent}\")\n")
            drl.append("    eval(darpan.reconciliation.conclusion.ConclusionTreeSupport.matches(\$f, (Map) nodes.get(\"${id}\")))\n")
            drl.append("then\n")
            drl.append("    darpan.reconciliation.conclusion.ConclusionTreeSupport.advance(\$f, (Map) nodes.get(\"${id}\"));\n")
            drl.append("    update(\$f);\n")
            drl.append("end\n")
        }
        return drl.toString()
    }

    /** One walk up the parents catches both a tree too deep and a cycle (which is infinitely deep). */
    private static void checkDepth(Map node, Map<Integer, Map> bySeq) {
        int depth = 1
        Map current = node
        while (current.get("parentSequenceNum") != null) {
            current = bySeq.get(current.parentSequenceNum as int)
            depth++
            if (depth > MAX_DEPTH) {
                throw new IllegalArgumentException("Conclusion rule ${node.sequenceNum} is deeper than ${MAX_DEPTH} levels, " +
                        "or its parents form a cycle")
            }
        }
    }
}
