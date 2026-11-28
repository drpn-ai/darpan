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

    // DAR-OPS-029 S03b: an ask's @mayEnable codes set the rank above which the ask planner drops it (BrainQuestions); a code
    // no rule @concludes reads as rank 0, so a typo would silently drop the question. Every code must be a concluded cause.
    @Test
    void everyCodeAnAskMayEnableIsACauseThePackConcludes() {
        List<String> rs = rules(pack())
        Set<String> concluded = rs.collect { (it =~ /@concludes\("([^"]+)"\)/) }.findAll { it.find() }.collect { it.group(1) } as Set<String>
        Set<String> enabled = rs.collectMany { String r ->
            def m = (r =~ /@mayEnable\("([^"]+)"\)/)
            m.find() ? m.group(1).split(",").collect { it.trim() } : []
        } as Set<String>
        assertFalse(enabled.isEmpty())
        assertEquals([] as Set<String>, enabled - concluded, "@mayEnable names causes no rule concludes")
    }

    // DAR-OPS-029 S03b (QA): the ask planner reads a cause's rank from @rank, BestCause from the rank the rule inserts; they
    // must be one number, and the code inserted must be the code @concludes names.
    @Test
    void everyCauseInsertsTheCodeAndRankItsRuleDeclares() {
        List<String> causes = rules(pack()).findAll { it.contains("@concludes(") }
        assertTrue(causes.size() >= 20, "${causes.size()}")
        causes.each { String r ->
            String name = r.readLines()[0]
            String code = ((r =~ /@concludes\("([A-Z0-9_]+)"\)/)[0] as List)[1]
            String rank = ((r =~ /@rank\((\d+)\)/)[0] as List)[1]
            def inserted = (r =~ /Cause\.(?:fix|expected)\(\s*\$\w+,\s*"([A-Z0-9_]+)",\s*(\d+)/)
            assertTrue(inserted.find(), name)
            assertEquals([code, rank], [inserted.group(1), inserted.group(2)], name)
        }
    }

    // DAR-OPS-029 S03b OC197 (B7): paymentsComplete gates more causes than partial payment (a voided authorization's
    // PAY_EXPIRED at 95, POS_UNPAID at 85, ...), so the payments question stays worth asking until a cause at 95 holds.
    @Test
    void thePaymentsQuestionCanUnlockEveryCauseItsCompletenessGates() {
        assertEquals(95, BrainQuestions.mayEnableRanks(BrainEngine.forPacks(["oms-netsuite"])).orderPayments)
    }

    // DAR-BE-229: a tunable number is a GraphParam fact, never a decimal literal in a rule's when-part.
    @Test
    void noRuleWhenPartHoldsADecimalLiteral() {
        List<String> offenders = rules(pack()).findAll { String r ->
            String when = r.contains("\nthen\n") ? r.substring(0, r.indexOf("\nthen\n")) : r
            (when =~ /(?<![\w.])\d+\.\d+(?![\w.])/).find()
        }.collect { it.readLines()[0] }
        assertTrue(offenders.isEmpty(), "decimal literal in the when-part of: ${offenders}")
    }

    @Test
    void theDecimalLiteralCheckSeesALiteralInAWhenPart() {
        String drl = 'rule "x"\n    @produces("A")\nwhen\n    Order( total > 0.05 )\nthen\n    f();\nend\n'
        String when = rules(drl)[0].substring(0, rules(drl)[0].indexOf("\nthen\n"))
        assertTrue((when =~ /(?<![\w.])\d+\.\d+(?![\w.])/).find())
    }

    // DAR-BE-151: the conclude seam hands the brain only rows of BrainConclude.BUCKETS (so another bucket cannot use up its
    // cap); every bucket the pack reads must be one of them, or its rules would never see their rows.
    @Test
    void everyBucketThePackReadsIsOneTheBrainIsHanded() {
        Set<String> read = ((pack() =~ /bucket == "([A-Z_0-9]+)"/).collect { it[1] } as Set<String>)
        assertFalse(read.isEmpty())
        assertTrue(BrainConclude.BUCKETS.containsAll(read), "the pack reads ${read}; the brain is handed ${BrainConclude.BUCKETS}")
    }
}
