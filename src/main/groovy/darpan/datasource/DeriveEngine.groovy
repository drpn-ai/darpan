package darpan.datasource

import org.kie.api.KieServices
import org.kie.api.builder.KieBuilder
import org.kie.api.builder.KieFileSystem
import org.kie.api.builder.Message
import org.kie.api.builder.ReleaseId
import org.kie.api.runtime.KieContainer
import org.kie.api.runtime.KieSession
import reconciliation.rule.RuleEngineSupport

/**
 * DAR-BE-176. A derive step's rows (DeriveRules) as a Drools rule base: one rule per row from a fixed template whose
 * only variable parts are the row's number and its salience, so no definition text ever reaches the DRL. Rows fire in
 * order on each record; a rule does not update() its fact, so conditions are read once, on the record as it entered.
 *
 * Built once per run and closed after it, with its own ReleaseId (the ConclusionTreeEngine pattern: no container
 * outlives its run, and two builds never swap modules). Every fire goes through RuleEngineSupport.fireAllRulesBounded;
 * a halted batch throws, so partial records are never written.
 */
class DeriveEngine implements Closeable {

    static final String PACKAGE = "darpan.datasource.derive"

    final ReleaseId releaseId
    private final KieContainer container
    private final List<Map> rules

    private DeriveEngine(ReleaseId releaseId, KieContainer container, List<Map> rules) {
        this.releaseId = releaseId
        this.container = container
        this.rules = rules
    }

    /**
     * The whole DRL. `eval(...)` here is Drools' condition element calling DeriveRules.matches, not dynamic code: the only
     * generated parts are the row number and salience (integers), so no definition text is ever compiled.
     */
    static String drl(int count) {
        StringBuilder drl = new StringBuilder()
        drl.append("package ${PACKAGE}\n")
        drl.append("import java.util.Map\n")
        drl.append("global java.util.List rules\n")
        for (int i = 0; i < count; i++) {
            drl.append("\nrule \"row${i}\"\n")
            drl.append("    salience ${count - i}\n")
            drl.append("when\n")
            drl.append("    \$r : Map()\n")
            drl.append("    eval(darpan.datasource.DeriveRules.matches(\$r, (Map) rules.get(${i})))\n")
            drl.append("then\n")
            drl.append("    darpan.datasource.DeriveRules.apply(\$r, (Map) rules.get(${i}));\n")
            drl.append("end\n")
        }
        return drl.toString()
    }

    static DeriveEngine compile(List<Map> rules) {
        String drl = drl(rules.size())
        ClassLoader loader = DeriveEngine.class.getClassLoader()
        ClassLoader previous = Thread.currentThread().getContextClassLoader()
        try {
            // Drools 8 resolves evaluators on the thread-context loader during buildAll() (see RuleEngineSupport).
            Thread.currentThread().setContextClassLoader(loader)
            KieServices ks = KieServices.Factory.get()
            ReleaseId rid = ks.newReleaseId(PACKAGE, "derive-" + UUID.randomUUID().toString().replace("-", ""), "1.0.0")
            KieFileSystem kfs = ks.newKieFileSystem()
            kfs.generateAndWritePomXML(rid)
            kfs.write("src/main/resources/darpan/datasource/derive/derive.drl", drl)
            KieBuilder kb = ks.newKieBuilder(kfs)
            kb.buildAll()
            if (kb.getResults().hasMessages(Message.Level.ERROR)) {
                String errors = kb.getResults().getMessages(Message.Level.ERROR).collect { Message m -> m.text }.join(" | ")
                ks.getRepository().removeKieModule(rid)
                throw new IllegalStateException("Derive rules did not compile: ${errors}")
            }
            return new DeriveEngine(rid, ks.newKieContainer(rid, loader), new ArrayList<Map>(rules))
        } finally {
            Thread.currentThread().setContextClassLoader(previous)
        }
    }

    /** Fires every row on every record, in place. */
    void derive(List<Map> records, String label) {
        if (!records) return
        KieSession session = container.newKieSession()
        try {
            session.setGlobal("rules", rules)
            records.each { session.insert(it) }
            Map<String, Object> fired = RuleEngineSupport.fireAllRulesBounded(session, label)
            if (fired.halted) {
                throw new IllegalStateException("Derive rules ran past ${RuleEngineSupport.MAX_RULE_EVAL_MILLIS}ms and were halted; no records were written")
            }
        } finally {
            session.dispose()
        }
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
