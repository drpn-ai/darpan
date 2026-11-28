package darpan.facade.reconciliation

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Matcher

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-163: a retry reuses the failed run's extracts only when nothing that shapes them has been edited since the run
 * started. The edit check lists those entities by hand, so this reads the entity definitions instead: every rule-set child
 * keyed by compareScopeId shapes the run (keys, filters, and the conclusion conditions that pick the fields an extract
 * asks for: ReconciliationSavedRunSupport.conclusionReadFieldsForSide), and a new one has to join the list.
 * No Moqui boot.
 */
class RunRetrySupportTests {

    @Test
    void theEditCheckCoversEveryRuleSetChildKeyedByCompareScope() {
        Path entityDir = ReconciliationSmokeTestSupport.resolveBackendRoot().resolve("runtime/component/darpan/entity")
        Set<String> keyedByScope = [] as Set
        Files.list(entityDir).withCloseable { stream ->
            stream.filter { Path p -> p.toString().endsWith(".xml") }.forEach { Path p ->
                Matcher m = (p.toFile().text =~ /(?s)<entity entity-name="(\w+)" package="([\w.]+)"(.*?)<\/entity>/)
                while (m.find()) {
                    if (m.group(2) == "darpan.rule" && m.group(3).contains('name="compareScopeId"')) {
                        keyedByScope.add("${m.group(2)}.${m.group(1)}".toString())
                    }
                }
            }
        }

        assertTrue(keyedByScope.contains("darpan.rule.RuleSetConclusionCondition"), "fixture sanity: ${keyedByScope}")
        assertEquals(keyedByScope.sort(), (RunRetrySupport.COMPARE_SCOPE_ENTITIES as Set).sort(),
                "a rule-set child the retry's edit check does not read lets an older extract answer a newer question")
    }
}
