package darpan.datasource

import org.kie.api.builder.ReleaseId
import org.kie.api.runtime.KieSession
import reconciliation.rule.EngineModule
import reconciliation.rule.RuleEngineSupport

/**
 * DAR-BE-176. A derive step's rows (DeriveRules) as a Drools rule base: one rule per row from a fixed template whose
 * only variable parts are the row's number and its salience, so no definition text ever reaches the DRL. Rows fire in
 * order on each record, and a row's conditions see what the rows before it set (exactly as applying the rows in order
 * does: DeriveRulesTests.aRowsWhenSeesWhatAnEarlierRowOfTheStepSet, droolsWritesWhatTheRowsAppliedInOrderWrite).
 *
 * Built once per run and closed after it, with its own ReleaseId (the ConclusionTreeEngine pattern: no container
 * outlives its run, and two builds never swap modules). Every fire goes through RuleEngineSupport.fireAllRulesBounded;
 * a halted batch throws, so partial records are never written.
 */
class DeriveEngine implements Closeable {

    static final String PACKAGE = "darpan.datasource.derive"

    private final EngineModule module
    private final List<Map> rules

    private DeriveEngine(EngineModule module, List<Map> rules) {
        this.module = module
        this.rules = rules
    }

    ReleaseId getReleaseId() { module.releaseId }

    /**
     * The whole DRL. `eval(...)` here is Drools' condition element calling DeriveRules.matches, not dynamic code: the only
     * generated parts are the row number and salience (integers), so no definition text is ever compiled.
     */
    static String drl(int count) {
        StringBuilder drl = new StringBuilder()
        drl.append("package ${PACKAGE}\n")
        drl.append("import java.util.Map\n")
        drl.append("global java.util.List rules\n")
        drl.append("global java.lang.Long referenceMillis\n")
        for (int i = 0; i < count; i++) {
            drl.append("\nrule \"row${i}\"\n")
            drl.append("    salience ${count - i}\n")
            drl.append("when\n")
            drl.append("    \$r : Map()\n")
            drl.append("    eval(darpan.datasource.DeriveRules.matches(\$r, (Map) rules.get(${i}), referenceMillis))\n")
            drl.append("then\n")
            drl.append("    darpan.datasource.DeriveRules.apply(\$r, (Map) rules.get(${i}), referenceMillis);\n")
            drl.append("end\n")
        }
        return drl.toString()
    }

    static DeriveEngine compile(List<Map> rules) {
        EngineModule module = EngineModule.open(PACKAGE, "derive-",
                ["src/main/resources/darpan/datasource/derive/derive.drl": drl(rules.size())], DeriveEngine.class.getClassLoader(), "Derive rules")
        return new DeriveEngine(module, new ArrayList<Map>(rules))
    }

    /**
     * Fires every row on every record, in place. referenceMillis is the run's reference time (its window end), which
     * AFTER_NOW conditions compare against (OC178); a caller with no run leaves it null and gets now, read once per batch.
     */
    void derive(List<Map> records, String label, Long referenceMillis = null) {
        if (!records) return
        KieSession session = module.container.newKieSession()
        try {
            session.setGlobal("rules", rules)
            session.setGlobal("referenceMillis", referenceMillis != null ? referenceMillis : System.currentTimeMillis())
            records.each { session.insert(it) }
            requireComplete(RuleEngineSupport.fireAllRulesBounded(session, label))
        } finally {
            session.dispose()
        }
    }

    /**
     * A batch that was halted, or that reached the rule-fire cap with rows possibly left unfired, wrote partial records:
     * fail, so nothing is written (OC173: the cap used to return quietly and leave the later records underived).
     */
    static void requireComplete(Map<String, Object> fired) {
        if ((fired.fired as int) >= RuleEngineSupport.MAX_RULE_FIRES) {
            throw new IllegalStateException("Derive rules reached the ${RuleEngineSupport.MAX_RULE_FIRES}-fire cap, so some rows may not have run; no records were written")
        }
        if (fired.halted) {
            throw new IllegalStateException("Derive rules ran past ${RuleEngineSupport.MAX_RULE_EVAL_MILLIS}ms and were halted; no records were written")
        }
    }

    @Override
    void close() {
        module.close()
    }
}
