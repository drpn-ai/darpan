package reconciliation.rule

import org.kie.api.KieServices
import org.kie.api.builder.KieBuilder
import org.kie.api.builder.KieFileSystem
import org.kie.api.builder.Message
import org.kie.api.builder.ReleaseId
import org.kie.api.runtime.KieContainer

/**
 * DAR-OPS-029 S99-059. The one Drools module build, for the tenant rule sets (RuleEngineSupport.buildRuleModule) and
 * for the engines whose DRL is generated or checked in (DeriveEngine, ConclusionTreeEngine, BrainEngine).
 *
 * Every module gets a release id of its own: KieBuilder registers each module in the process-wide KieRepository, and
 * two builds under one id swap each other's module between buildAll() and newKieContainer() (DAR-BE-289). A build
 * that fails is removed from the repository again, so failed builds do not pile up there.
 */
class EngineModule implements Closeable {

    final ReleaseId releaseId
    final KieContainer container

    private EngineModule(ReleaseId releaseId, KieContainer container) {
        this.releaseId = releaseId
        this.container = container
    }

    /**
     * Writes each DRL (resource path -> text) under releaseId and builds it. Answers the build's error texts, empty when
     * it built; on errors the module is removed from the repository before this returns.
     */
    static List<String> build(KieServices ks, ReleaseId releaseId, Map<String, String> drlByPath) {
        KieFileSystem kfs = ks.newKieFileSystem()
        kfs.generateAndWritePomXML(releaseId)
        drlByPath.each { String path, String drl -> kfs.write(path, drl) }
        KieBuilder kb = ks.newKieBuilder(kfs)
        kb.buildAll()
        if (!kb.getResults().hasMessages(Message.Level.ERROR)) return []
        List<String> errors = (kb.getResults().getMessages(Message.Level.ERROR) ?: [])
                .collect { Message m -> m?.text ?: m?.toString() }
                .findAll { it } as List<String>
        ks.getRepository().removeKieModule(releaseId)
        return errors ?: [kb.getResults().toString()]
    }

    /**
     * An engine's own module: a fresh release id (group, artifactPrefix + a random suffix), built with the thread-context
     * loader pinned to {@code loader} and opened on it. Drools 8 resolves evaluators on the thread-context loader during
     * buildAll(), and under Moqui's component loader they would otherwise split (see RuleEngineSupport). A build error
     * throws IllegalStateException "<what> did not compile: <errors>". close() disposes the container and the module.
     */
    static EngineModule open(String group, String artifactPrefix, Map<String, String> drlByPath, ClassLoader loader, String what) {
        ClassLoader previous = Thread.currentThread().getContextClassLoader()
        try {
            Thread.currentThread().setContextClassLoader(loader)
            KieServices ks = KieServices.Factory.get()
            ReleaseId rid = ks.newReleaseId(group, artifactPrefix + UUID.randomUUID().toString().replace("-", ""), "1.0.0")
            List<String> errors = build(ks, rid, drlByPath)
            if (errors) throw new IllegalStateException("${what} did not compile: ${errors.join(' | ')}".toString())
            return new EngineModule(rid, ks.newKieContainer(rid, loader))
        } finally {
            Thread.currentThread().setContextClassLoader(previous)
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
