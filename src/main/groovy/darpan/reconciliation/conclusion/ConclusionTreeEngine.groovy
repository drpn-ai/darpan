package darpan.reconciliation.conclusion

import org.kie.api.KieServices
import org.kie.api.builder.KieBuilder
import org.kie.api.builder.KieFileSystem
import org.kie.api.builder.Message
import org.kie.api.builder.ReleaseId
import org.kie.api.runtime.KieContainer
import org.kie.api.runtime.KieSession
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

    final ReleaseId releaseId
    private final KieContainer container
    private final Map<String, Map> nodesById

    private ConclusionTreeEngine(ReleaseId releaseId, KieContainer container, Map<String, Map> nodesById) {
        this.releaseId = releaseId
        this.container = container
        this.nodesById = nodesById
    }

    static ConclusionTreeEngine compile(List<Map> rules) {
        List<Map> nodes = ConclusionRuleLogicGenerator.assignNodes(rules)
        String drl = ConclusionRuleLogicGenerator.generate(nodes)
        Map<String, Map> byId = nodes.collectEntries { Map n -> [(n.nodeId): n] }

        // Same TCCL pinning as RuleEngineSupport: Drools 8 resolves evaluators on the thread-context
        // loader during buildAll(), and under Moqui's component loader they would otherwise split.
        ClassLoader loader = ConclusionTreeEngine.class.getClassLoader()
        ClassLoader previous = Thread.currentThread().getContextClassLoader()
        try {
            Thread.currentThread().setContextClassLoader(loader)
            KieServices ks = KieServices.Factory.get()
            ReleaseId rid = ks.newReleaseId("darpan.conclusion", "tree-" + UUID.randomUUID().toString().replace("-", ""), "1.0.0")
            KieFileSystem kfs = ks.newKieFileSystem()
            kfs.generateAndWritePomXML(rid)
            kfs.write("src/main/resources/darpan/conclusion/tree/tree.drl", drl)
            KieBuilder kb = ks.newKieBuilder(kfs)
            kb.buildAll()
            if (kb.getResults().hasMessages(Message.Level.ERROR)) {
                String errors = kb.getResults().getMessages(Message.Level.ERROR).collect { Message m -> m.text }.join(" | ")
                ks.getRepository().removeKieModule(rid)
                throw new IllegalStateException("Conclusion tree did not compile: ${errors}")
            }
            return new ConclusionTreeEngine(rid, ks.newKieContainer(rid, loader), byId)
        } finally {
            Thread.currentThread().setContextClassLoader(previous)
        }
    }

    List<Map> conclude(List<Map> findings) {
        List<Map> facts = (findings ?: []).collect { Map f -> ConclusionTreeSupport.newFact((String) f.bucket, (Map) f.sides) }
        if (facts.isEmpty()) return []
        KieSession session = container.newKieSession()
        try {
            session.setGlobal("nodes", nodesById)
            facts.each { Map fact -> session.insert(fact) }
            Map<String, Object> fired = RuleEngineSupport.fireAllRulesBounded(session, "conclusion-tree")
            if (fired.halted) {
                throw new IllegalStateException("Conclusion evaluation exceeded ${RuleEngineSupport.MAX_RULE_EVAL_MILLIS}ms " +
                        "and was halted; no conclusions were written")
            }
        } finally {
            session.dispose()
        }
        return facts.collect { Map fact -> ConclusionTreeSupport.result(fact) }
    }

    @Override
    void close() {
        try {
            container.dispose()
        } finally {
            KieServices.Factory.get().getRepository().removeKieModule(releaseId)
        }
    }
}
