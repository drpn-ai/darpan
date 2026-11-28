package reconciliation.rule

import groovy.xml.XmlParser
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue
import static darpan.reconciliation.support.TestHelpers.componentRoot

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

        // save#RuleSet (and its create#darpan.rule.RuleSet) was deleted in DAR-OPS-029 S99-027 (d8931ccf); save#Rule stays.
        String serviceText = Files.readString(serviceXml)
        assertTrue(serviceText.contains('name="create#darpan.rule.Rule"'))
    }
}
