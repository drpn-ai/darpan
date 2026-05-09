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
    void ruleEngineRuntimeServicesStayXmlBacked() {
        Path serviceXml = componentRoot().resolve("service/reconciliation/ReconciliationRuleEngineServices.xml")
        def services = new XmlParser(false, false).parse(serviceXml.toFile()).service

        ["compile:RuleSet", "execute:RuleSet", "execute:RuleSetMatchedPairs"].each { String serviceName ->
            List<String> parts = serviceName.split(":") as List<String>
            def service = services.find { it.@verb == parts[0] && it.@noun == parts[1] }

            assertNotNull(service)
            assertFalse(service.attributes().containsKey("type"), "${serviceName} should be XML-backed")
            assertFalse(service.attributes().containsKey("method"), "${serviceName} should not be a script method")
            assertFalse(service.attributes().containsKey("location"), "${serviceName} should not point at a Groovy script file")
            assertTrue(service.actions.size() == 1)
        }

        String serviceText = Files.readString(serviceXml)
        String deletedScriptName = ["RuleEngine", "Services.groovy"].join("")
        assertFalse(serviceText.contains(deletedScriptName))
        assertTrue(serviceText.contains("RuleEngineSupport.compileRuleSet"))
        assertTrue(serviceText.contains("RuleEngineSupport.executeRuleSetFacts"))
        assertTrue(serviceText.contains("RuleEngineSupport.executeMatchedPairFacts"))
        assertFalse(serviceText.contains("RuleEngineSupport.normalizeBool"))
        assertFalse(serviceText.contains("RuleEngineSupport.normalizeEnabled"))
        assertFalse(serviceText.contains("RuleEngineSupport.toLong"))
        assertFalse(serviceText.contains("RuleEngineSupport.validateProvidedId"))
        assertFalse(serviceText.contains("RuleEngineSupport.resolveNextSequence"))
    }

    @Test
    void ruleSaveContractStaysXmlBacked() {
        Path serviceXml = componentRoot().resolve("service/reconciliation/ReconciliationRuleEngineServices.xml")
        def services = new XmlParser(false, false).parse(serviceXml.toFile()).service
        def saveRule = services.find { it.@verb == "save" && it.@noun == "Rule" }

        assertNotNull(saveRule)
        assertFalse(saveRule.attributes().containsKey("type"))
        assertFalse(saveRule.attributes().containsKey("method"))
        assertFalse(saveRule.attributes().containsKey("location"))
        assertTrue(saveRule.actions.size() == 1)

        String serviceText = Files.readString(serviceXml)
        assertTrue(serviceText.contains('name="create#darpan.rule.RuleSet"'))
        assertTrue(serviceText.contains('name="create#darpan.rule.Rule"'))
        assertFalse(serviceText.contains("resolveRuleSetId"))
        assertFalse(serviceText.contains("nextAvailableId"))
        assertFalse(serviceText.contains("generateRuleId"))
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
