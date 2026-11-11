package darpan.conclusion.brain

import groovy.json.JsonOutput

/**
 * DAR-BE-230. The brain describes its own graph: BrainEngine.ruleMetadata() (@reads, @asks, @produces, @concludes,
 * @states, @mayEnable, @rank) as plain maps and JSON. Nodes are rules, reads (fact types no rule produces), questions,
 * pieces, causes and statements; edges join them. Everything is sorted, so two runs print the same text.
 */
class BrainPackDescriber {

    private static String text(Object v) { v?.toString()?.replaceAll(/^"|"$/, "")?.trim() }

    private static List<String> names(Object v) {
        String t = text(v)
        return t ? t.split(",").collect { it.trim() }.findAll { it } : []
    }

    static Map describe(List<String> packs) {
        return describe(BrainEngine.forPacks(packs).ruleMetadata())
    }

    static String toJson(List<String> packs) { JsonOutput.toJson(describe(packs)) }

    static Map describe(Map<String, Map<String, Object>> rules) {
        List<String> ruleNames = rules.keySet().collect { it.toString() }.sort()
        Set<String> pieces = [] as Set
        Set<String> questions = [] as Set
        Set<String> causes = [] as Set
        Set<String> statements = [] as Set
        Set<String> readNames = [] as Set
        ruleNames.each { String r ->
            Map m = rules.get(r)
            names(m.get("produces")).each { pieces.add(it) }
            names(m.get("asks")).each { questions.add(it) }
            names(m.get("concludes")).each { causes.add(it) }
            names(m.get("states")).each { statements.add(it) }
            names(m.get("reads")).each { readNames.add(it) }
        }
        Set<String> reads = readNames.findAll { !(it in pieces) } as Set

        List<Map> nodes = []
        ruleNames.each { String r ->
            Map m = rules.get(r)
            Map node = [id: "rule:" + r, kind: "rule", name: r]
            if (m.get("rank") != null) node.put("rank", rank(m.get("rank")))
            nodes.add(node)
        }
        Map<String, Integer> causeRank = [:]
        ruleNames.each { String r ->
            Map m = rules.get(r)
            if (m.get("rank") != null) names(m.get("concludes")).each { causeRank.put(it, rank(m.get("rank"))) }
        }
        reads.sort().each { nodes.add([id: "read:" + it, kind: "read", name: it]) }
        questions.sort().each { nodes.add([id: "question:" + it, kind: "question", name: it]) }
        pieces.sort().each { nodes.add([id: "piece:" + it, kind: "piece", name: it]) }
        causes.sort().each { String c ->
            Map node = [id: "cause:" + c, kind: "cause", name: c]
            if (causeRank.containsKey(c)) node.put("rank", causeRank.get(c))
            nodes.add(node)
        }
        statements.sort().each { nodes.add([id: "statement:" + it, kind: "statement", name: it]) }

        List<Map> edges = []
        ruleNames.each { String r ->
            Map m = rules.get(r)
            String rid = "rule:" + r
            names(m.get("reads")).each { edges.add([from: (it in pieces ? "piece:" : "read:") + it, to: rid, kind: "reads"]) }
            names(m.get("produces")).each { edges.add([from: rid, to: "piece:" + it, kind: "produces"]) }
            names(m.get("asks")).each { String q ->
                edges.add([from: rid, to: "question:" + q, kind: "asks"])
                names(m.get("mayEnable")).each { edges.add([from: "question:" + q, to: "cause:" + it, kind: "mayEnable"]) }
            }
            names(m.get("concludes")).each { edges.add([from: rid, to: "cause:" + it, kind: "concludes"]) }
            names(m.get("states")).each { edges.add([from: rid, to: "statement:" + it, kind: "states"]) }
        }
        edges = edges.unique(false).sort(false) { Map a, Map b -> "${a.kind}|${a.from}|${a.to}".toString() <=> "${b.kind}|${b.from}|${b.to}".toString() }
        return [nodes: nodes, edges: edges]
    }

    private static Integer rank(Object v) { v instanceof Number ? ((Number) v).intValue() : Integer.valueOf(text(v)) }
}
