package darpan.conclusion.brain

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-151 (design D12). The pack is code nobody types on a form, and it stays small, typed and side-effect free. */
class BrainPackGuardTests {

    private static String pack() {
        InputStream in = BrainPackGuardTests.classLoader.getResourceAsStream("darpan/conclusion/brain/oms-netsuite.drl")
        assert in: "oms-netsuite.drl is not on the classpath"
        return in.getText("UTF-8")
    }

    private static List<String> rules(String drl) { (drl =~ /(?s)rule\s+"[^"]+"(.*?)\nend/).collect { (it as List)[0] as String } }

    @Test
    void thePackHasNoEvalAndNeverChangesAnInputFact() {
        String drl = pack()
        assertFalse(drl.contains("eval("))
        assertFalse((drl =~ /\bmodify\s*\(/).find())
        assertFalse((drl =~ /\bupdate\s*\(/).find())
    }

    @Test
    void thePackImportsOnlyTheFactsAndJava() {
        List<String> imports = pack().readLines().findAll { it.startsWith("import ") }*.trim()
        assertTrue(imports.every { it ==~ /import (darpan\.conclusion\.brain\.fact\.\S+|java\.math\.\S+|java\.util\.\S+)/ }, "${imports}")
    }

    @Test
    void everyRuleSaysWhatItProducesAsksOrConcludes() {
        List<String> rs = rules(pack())
        assertTrue(rs.size() >= 8, "${rs.size()} rules")
        rs.each { assertTrue((it =~ /@(produces|asks|concludes|states)\(/).find(), it.readLines()[0]) }
    }

    @Test
    void causeRanksAreUniquePerSubjectKind() {
        List<String> ranks = rules(pack()).findAll { it.contains("@concludes(") }.collect { ((it =~ /@rank\((\d+)\)/)[0] as List)[1] as String }
        assertEquals(ranks.size(), ranks.unique(false).size())
    }
}
