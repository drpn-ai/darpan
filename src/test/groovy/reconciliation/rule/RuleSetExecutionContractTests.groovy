package reconciliation.rule

import groovy.util.XmlParser
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

class RuleSetExecutionContractTests {

    @Test
    void ruleEngineServiceContractStaysFileTypeAgnostic() {
        Path serviceXml = componentRoot().resolve("service/reconciliation/ReconciliationRuleEngineServices.xml")
        def services = new XmlParser(false, false).parse(serviceXml.toFile()).service

        assertNotNull(services.find { it.@verb == "execute" && it.@noun == "RuleSet" })
        assertNotNull(services.find { it.@verb == "execute" && it.@noun == "RuleSetMatchedPairs" })
        assertFalse(services.any { it.@noun == "RuleSetJson" })
        assertFalse(services.any { it.@method == "executeRuleSetJson" })
    }

    @Test
    void legacyRuleSetTestScreenParsesJsonBeforeGenericExecution() {
        Path screenXml = componentRoot().resolve("screen/Reconciliation/RuleEngine/RuleSetDetail.xml")
        def screen = new XmlParser(false, false).parse(screenXml.toFile())
        List<String> serviceNames = screen.depthFirst()
                .findAll { it.name() == "service-call" }
                .collect { it.@name as String }

        assertTrue(serviceNames.contains("reconciliation.ReconciliationRuleEngineServices.execute#RuleSet"))
        assertFalse(serviceNames.contains("reconciliation.ReconciliationRuleEngineServices.execute#RuleSetJson"))

        String screenText = Files.readString(screenXml)
        assertTrue(screenText.contains("new groovy.json.JsonSlurper().parseText(rawJsonData)"))
        assertTrue(screenText.contains("Invalid test data JSON"))
    }

    private static Path componentRoot() {
        Path cwd = Paths.get("").toAbsolutePath().normalize()
        List<Path> candidates = [
                cwd,
                cwd.resolve("runtime/component/darpan"),
                cwd.resolve("darpan-backend/runtime/component/darpan"),
        ]

        Path root = candidates.find {
            Files.exists(it.resolve("service/reconciliation/ReconciliationRuleEngineServices.xml"))
        }
        if (!root) {
            throw new IllegalStateException("Unable to resolve Darpan component root from ${cwd}")
        }
        return root
    }
}
