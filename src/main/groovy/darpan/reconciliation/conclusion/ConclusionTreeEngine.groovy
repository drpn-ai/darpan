package darpan.reconciliation.conclusion

import org.kie.api.builder.ReleaseId
import org.kie.api.runtime.KieSession
import reconciliation.rule.EngineModule
import reconciliation.rule.RuleEngineSupport

/**
 * DAR-BE-063. Compiles one compare scope's conclusion tree to Drools and concludes findings with it.
 *
 * Compiled once per CONCLUDE pass and closed after it — no cache, so no container outlives its run and
 * nothing is disposed under a concurrent reader. Each compile gets its OWN ReleaseId: KieBuilder
 * registers every module in the process-wide KieRepository, and two compiles on the default ReleaseId
 * could swap each other's module between buildAll() and newKieContainer(). close() removes the module
 * again, so repeated runs do not grow the repository.
 *
 * Every fire goes through RuleEngineSupport.fireAllRulesBounded (fire cap + wall-clock watchdog). A
 * halted evaluation throws: a partial set of conclusions must never be written.
 */
class ConclusionTreeEngine implements Closeable {

    private final EngineModule module
    private final Map<String, Map> nodesById

    private ConclusionTreeEngine(EngineModule module, Map<String, Map> nodesById) {
        this.module = module
        this.nodesById = nodesById
    }

    ReleaseId getReleaseId() { module.releaseId }

    static ConclusionTreeEngine compile(List<Map> rules) {
        List<Map> nodes = ConclusionRuleLogicGenerator.assignNodes(rules)
        String drl = ConclusionRuleLogicGenerator.generate(nodes)
        Map<String, Map> byId = nodes.collectEntries { Map n -> [(n.nodeId): n] }
        EngineModule module = EngineModule.open("darpan.conclusion", "tree-",
                ["src/main/resources/darpan/conclusion/tree/tree.drl": drl], ConclusionTreeEngine.class.getClassLoader(), "Conclusion tree")
        return new ConclusionTreeEngine(module, byId)
    }

    /** asOfMillis: the pass's one instant for WITHIN_HOURS (ConclusionConditionSupport); null reads the clock. */
    List<Map> conclude(List<Map> findings, Long asOfMillis = null) {
        List<Map> facts = (findings ?: []).collect { Map f -> ConclusionTreeSupport.newFact((String) f.bucket, (Map) f.sides, asOfMillis) }
        if (facts.isEmpty()) return []
        KieSession session = module.container.newKieSession()
        try {
            session.setGlobal("nodes", nodesById)
            facts.each { Map fact -> session.insert(fact) }
            Map<String, Object> fired = RuleEngineSupport.fireAllRulesBounded(session, "conclusion-tree")
            if (fired.halted) {
                throw new IllegalStateException("Conclusion evaluation ${RuleEngineSupport.incompleteReason(fired)}; no conclusions were written")
            }
        } finally {
            session.dispose()
        }
        return facts.collect { Map fact -> ConclusionTreeSupport.result(fact) }
    }

    @Override
    void close() {
        module.close()
    }
}
