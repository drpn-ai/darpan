package darpan.conclusion.brain

import darpan.conclusion.brain.fact.Ask
import darpan.conclusion.brain.fact.BestCause
import darpan.conclusion.brain.fact.Cause
import darpan.conclusion.brain.fact.Conclusion
import org.kie.api.KieServices
import org.kie.api.builder.KieBuilder
import org.kie.api.builder.KieFileSystem
import org.kie.api.builder.Message
import org.kie.api.builder.ReleaseId
import org.kie.api.runtime.ClassObjectFilter
import org.kie.api.runtime.KieContainer
import org.kie.api.runtime.KieSession
import reconciliation.rule.RuleEngineSupport

import java.util.concurrent.ConcurrentHashMap

/**
 * DAR-BE-151. The conclusion brain: checked-in DRL packs (classpath darpan/conclusion/brain/<pack>.drl) compiled once
 * per JVM per pack list. The packs hold no tenant data, so the container is cached and never disposed; a session per
 * fire is. Same TCCL pinning and own ReleaseId as ConclusionTreeEngine. Every fire is bounded; a halted one throws.
 */
class BrainEngine {

    static final String RESOURCE_DIR = "darpan/conclusion/brain/"
    private static final Map<String, BrainEngine> CACHE = new ConcurrentHashMap<>()

    final List<String> packs
    private final KieContainer container

    private BrainEngine(List<String> packs, KieContainer container) {
        this.packs = packs
        this.container = container
    }

    static BrainEngine forPacks(List<String> packNames) {
        List<String> packs = (packNames ?: []).collect { it.toString() }.sort()
        return CACHE.computeIfAbsent(packs.join(","), { String k -> compile(packs) })
    }

    static String packText(String pack) {
        InputStream in = BrainEngine.classLoader.getResourceAsStream(RESOURCE_DIR + pack + ".drl")
        if (in == null) throw new IllegalArgumentException("No brain pack ${pack} on the classpath")
        return in.withCloseable { it.getText("UTF-8") }
    }

    private static BrainEngine compile(List<String> packs) {
        ClassLoader loader = BrainEngine.class.getClassLoader()
        ClassLoader previous = Thread.currentThread().getContextClassLoader()
        try {
            Thread.currentThread().setContextClassLoader(loader)
            KieServices ks = KieServices.Factory.get()
            ReleaseId rid = ks.newReleaseId("darpan.conclusion", "brain-" + UUID.randomUUID().toString().replace("-", ""), "1.0.0")
            KieFileSystem kfs = ks.newKieFileSystem()
            kfs.generateAndWritePomXML(rid)
            packs.each { String pack -> kfs.write("src/main/resources/${RESOURCE_DIR}${pack}.drl".toString(), packText(pack)) }
            KieBuilder kb = ks.newKieBuilder(kfs)
            kb.buildAll()
            if (kb.getResults().hasMessages(Message.Level.ERROR)) {
                String errors = kb.getResults().getMessages(Message.Level.ERROR).collect { Message m -> m.text }.join(" | ")
                ks.getRepository().removeKieModule(rid)
                throw new IllegalStateException("Brain packs ${packs} did not compile: ${errors}")
            }
            return new BrainEngine(packs, ks.newKieContainer(rid, loader))
        } finally {
            Thread.currentThread().setContextClassLoader(previous)
        }
    }

    /** Facts in; the statements' conclusions, the asks still open, and each subject's best cause out. */
    Map fire(List<Object> facts) {
        KieSession session = container.newKieSession()
        try {
            (facts ?: []).each { session.insert(it) }
            Map<String, Object> fired = RuleEngineSupport.fireAllRulesBounded(session, "conclusion-brain")
            if (fired.halted) {
                throw new IllegalStateException("Brain evaluation exceeded ${RuleEngineSupport.MAX_RULE_EVAL_MILLIS}ms " +
                        "and was halted; no conclusions were written")
            }
            Map<Object, Cause> best = [:]
            session.getObjects(new ClassObjectFilter(BestCause)).each { Object b -> best.put(((BestCause) b).subject, ((BestCause) b).cause) }
            return [conclusions: session.getObjects(new ClassObjectFilter(Conclusion)) as List<Conclusion>,
                    asks       : session.getObjects(new ClassObjectFilter(Ask)) as List<Ask>,
                    bestCauses : best]
        } finally {
            session.dispose()
        }
    }

    /** A declared fact's @label and @value template (by its simple class name), or null for a type the packs did not declare. */
    Map<String, String> factMetadata(Object fact) {
        if (fact == null) return null
        for (def pkg : container.kieBase.kiePackages) {
            def type = container.kieBase.getFactType(pkg.name, fact.getClass().simpleName)
            if (type != null && type.factClass == fact.getClass()) {
                Map meta = type.metaData ?: [:]
                return [label: text(meta.label), value: text(meta.value)]
            }
        }
        return null
    }

    private static String text(Object v) { v?.toString()?.replaceAll(/^"|"$/, "")?.trim() }

    /** Rule name -> its metadata (@reads, @produces, ...), for the guard and web tests. */
    Map<String, Map<String, Object>> ruleMetadata() {
        Map<String, Map<String, Object>> out = [:]
        container.kieBase.kiePackages.each { pkg -> pkg.rules.each { r -> out.put(r.name, new LinkedHashMap<String, Object>(r.metaData)) } }
        return out
    }
}
