package darpan.reconciliation.flowchart

import groovy.xml.XmlParser
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-UI-048. The walker's inputs name server files. No service a browser can call may accept them:
 * the internal entry point is not remote, and the facade services do not declare them.
 */
class RunFlowchartServiceContractTests {

    static final List<String> WALKER_ONLY = ["file1IncludeIdsLocation", "file1KeysOutLocation"]

    @Test
    void walkerOnlyInputsAreNotRemoteCallable() {
        def question = service("service/reconciliation/ReconciliationFlowchartServices.xml", "run", "FlowchartQuestion")
        assertNotNull(question)
        assertFalse(question.attributes().get("allow-remote") == "true", "run#FlowchartQuestion must not be remote")
        assertEquals("component://darpan/src/main/groovy/darpan/facade/reconciliation/runSavedRunDiff.groovy",
                question.attributes().get("location"))

        // Every service file in the component, not only the facade: a remote service anywhere that
        // declares a walker-only input would let a browser name a server file.
        int checked = 0
        Files.walk(root().resolve("service")).filter { it.toString().endsWith(".xml") }.each { Path file ->
            new XmlParser(false, false).parse(file.toFile()).service.findAll { it.attributes().get("allow-remote") == "true" }.each { svc ->
                checked++
                List<String> names = svc."in-parameters".parameter.collect { it.attributes().get("name") as String }
                WALKER_ONLY.each { String p ->
                    assertFalse(names.contains(p), "${file.fileName} ${svc.attributes().get('verb')}#${svc.attributes().get('noun')} must not accept ${p}")
                }
            }
        }
        assertTrue(checked > 50, "expected to check the facade's remote services, checked ${checked}")
    }

    private static def service(String path, String verb, String noun) {
        return parse(path).service.find { it.attributes().get("verb") == verb && it.attributes().get("noun") == noun }
    }

    private static def parse(String relativePath) {
        return new XmlParser(false, false).parse(root().resolve(relativePath).toFile())
    }

    private static Path root() {
        Path cwd = Paths.get("").toAbsolutePath().normalize()
        return [cwd, cwd.resolve("runtime/component/darpan"), cwd.resolve("darpan-backend/runtime/component/darpan")]
                .find { Files.exists(it.resolve("entity/ReconciliationEntities.xml")) }
    }
}
