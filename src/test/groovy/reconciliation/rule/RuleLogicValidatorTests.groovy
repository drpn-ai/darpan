package reconciliation.rule

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Audit 2026-06-11 #1/#6 — tenant-authored rule logic must not be an unsandboxed
 * code-execution sink. The validator gates the save#Rule boundary: it accepts the
 * field-comparison DRL the UI generates (and the platform's RuleDiffSupport-based
 * rules) while rejecting arbitrary Java/MVEL, reflection, and custom imports.
 */
class RuleLogicValidatorTests {

    // ---- Legitimate shapes that MUST pass (mirror the UI generator + facade smoke fixtures) ----

    @Test
    void uiFieldComparisonRuleIsAccepted() {
        String ruleLogic = '''rule "FIELD_COMPARISON_1"
when
    $m : Map(this["file1"] != null, this["file2"] != null)
    eval(reconciliation.rule.RuleDiffSupport.violatesOperator(reconciliation.rule.RuleDiffSupport.applyPreActions(((Map) $m.get("file1")).get("total"), java.util.Arrays.asList("STRING_TO_INT")), ((Map) $m.get("file2")).get("order_total"), ">"))
then
    Object _file1Value = ((Map) $m.get("file1")).get("total");
    Object _file2Value = ((Map) $m.get("file2")).get("order_total");
    reconciliation.rule.RuleDiffSupport.addFieldMismatch($m, kcontext.getRule().getName(), "total > order_total", _file1Value, _file2Value, "WARN", "Field comparison failed: total > order_total");
end'''
        assertNull(RuleLogicValidator.firstViolation(ruleLogic))
    }

    @Test
    void valuesDifferRuleIsAccepted() {
        String ruleLogic = '''rule "ORDER_STATUS_MISMATCH"
when
    $m : Map(this["file1"] != null, this["file2"] != null)
    eval(reconciliation.rule.RuleDiffSupport.valuesDiffer(((Map) $m.get("file1")).get("status"), ((Map) $m.get("file2")).get("status")))
then
    reconciliation.rule.RuleDiffSupport.addFieldMismatch($m, kcontext.getRule().getName(), "status", ((Map) $m.get("file1")).get("status"), ((Map) $m.get("file2")).get("status"), "WARN", "Status mismatch");
end'''
        assertNull(RuleLogicValidator.firstViolation(ruleLogic))
    }

    @Test
    void objectsEqualsRuleIsAccepted() {
        String ruleLogic = '''rule "TOTAL_MISMATCH"
when
    $m : Map(this["file1"] != null, this["file2"] != null)
    eval(!java.util.Objects.equals(((Map) $m.get("file1")).get("total"), ((Map) $m.get("file2")).get("order_total")))
then
end'''
        assertNull(RuleLogicValidator.firstViolation(ruleLogic))
    }

    @Test
    void emptyNoopRuleIsAccepted() {
        assertNull(RuleLogicValidator.firstViolation('rule "NOOP" when $m : Map() then end'))
    }

    @Test
    void parsedConditionRuleTextStyleLogicIsAccepted() {
        // ruleLogic carrying a bare constraint expression (non-"rule " path) is also UI/legacy-valid
        assertNull(RuleLogicValidator.firstViolation('this["status"] == "Pending"'))
    }

    @Test
    void blankLogicIsAccepted() {
        assertNull(RuleLogicValidator.firstViolation(null))
        assertNull(RuleLogicValidator.firstViolation("   "))
    }

    @Test
    void dangerousWordInsideStringLiteralDoesNotFalsePositive() {
        // A field label / message that happens to contain "Runtime" or a dotted path is data, not code.
        String ruleLogic = '''rule "OK"
when
    $m : Map(this["file1"] != null)
then
    reconciliation.rule.RuleDiffSupport.addFieldMismatch($m, kcontext.getRule().getName(), "java.lang.Runtime.exec note", ((Map) $m.get("file1")).get("x"), null, "WARN", "value was Runtime.getRuntime()");
end'''
        assertNull(RuleLogicValidator.firstViolation(ruleLogic))
    }

    // ---- RCE / injection payloads that MUST be rejected ----

    @Test
    void runtimeExecIsRejected() {
        String ruleLogic = '''rule "x" when $m : Map() then java.lang.Runtime.getRuntime().exec("id"); end'''
        assertNotNull(RuleLogicValidator.firstViolation(ruleLogic))
    }

    @Test
    void bareRuntimeIsRejected() {
        // Drools default-imports java.lang.*, so unqualified Runtime resolves.
        assertNotNull(RuleLogicValidator.firstViolation('rule "x" when $m : Map() then Runtime.getRuntime().exec("id"); end'))
    }

    @Test
    void processBuilderIsRejected() {
        assertNotNull(RuleLogicValidator.firstViolation('rule "x" when $m : Map() then new ProcessBuilder("sh").start(); end'))
    }

    @Test
    void customImportIsRejected() {
        String ruleLogic = '''import java.io.File
rule "x" when $m : Map() then new File("/etc/passwd").delete(); end'''
        assertNotNull(RuleLogicValidator.firstViolation(ruleLogic))
    }

    @Test
    void reflectionForNameIsRejected() {
        assertNotNull(RuleLogicValidator.firstViolation('rule "x" when $m : Map() then Class.forName("java.lang.Runtime"); end'))
    }

    @Test
    void disallowedFqcnPackageIsRejected() {
        assertNotNull(RuleLogicValidator.firstViolation('rule "x" when $m : Map() then java.io.File f = new java.io.File("/tmp/x"); end'))
    }

    @Test
    void groovyShellIsRejected() {
        assertNotNull(RuleLogicValidator.firstViolation('rule "x" when $m : Map() then new groovy.lang.GroovyShell().evaluate("1"); end'))
    }

    @Test
    void systemExitIsRejected() {
        assertNotNull(RuleLogicValidator.firstViolation('rule "x" when $m : Map() then System.exit(1); end'))
    }

    @Test
    void reflectiveInvokeIsRejected() {
        assertNotNull(RuleLogicValidator.firstViolation('rule "x" when $m : Map() then $m.getClass().getMethod("x").invoke($m); end'))
    }

    @Test
    void unicodeEscapedRuntimeIsRejected() {
        // Self-review CRITICAL: ECJ decodes \\uXXXX before tokenizing, so a raw-string denylist that
        // scans pre-decode bytes would miss this. "\\u0052untime" decodes to "Runtime".
        String ruleLogic = 'rule "x" when $m : Map() then \\u0052untime.getRuntime().\\u0065xec("id"); end'
        assertNotNull(RuleLogicValidator.firstViolation(ruleLogic))
    }

    @Test
    void unicodeEscapedExecIsRejected() {
        String ruleLogic = 'rule "x" when $m : Map() then java.lang.\\u0052untime.getRuntime().exec("id"); end'
        assertNotNull(RuleLogicValidator.firstViolation(ruleLogic))
    }

    @Test
    void multiUUnicodeEscapeIsRejected() {
        // JLS allows extra 'u's in the escape: \\uu0052 is still 'R'.
        String ruleLogic = 'rule "x" when $m : Map() then \\uu0052untime.getRuntime(); end'
        assertNotNull(RuleLogicValidator.firstViolation(ruleLogic))
    }

    @Test
    void escapedQuoteDesyncBypassIsRejected() {
        // Self-review-2 CRITICAL: \\u0022 decodes to a real quote that closes the string early, exposing
        // the forbidden code that a string-blank-before-decode would have swallowed.
        String ruleLogic = 'rule "x" when $m : Map() then String s = "a"; java.lang.\\u0052untime.getRuntime().exec("id"); x="b"; end'
        assertNotNull(RuleLogicValidator.firstViolation(ruleLogic))
    }

    @Test
    void escapedNewlineCommentBypassIsRejected() {
        // Self-review-2 CRITICAL: \\u000a decodes to a newline that terminates the // comment, exposing
        // the forbidden code on the next physical line.
        String ruleLogic = 'rule "x" when $m : Map() then // x\\u000a java.lang.Runtime.getRuntime().exec("id"); end'
        assertNotNull(RuleLogicValidator.firstViolation(ruleLogic))
    }

    @Test
    void legitRuleWithAccentedStringStillAccepted() {
        // A legit field label / message with accented characters must still pass (no false reject).
        String ruleLogic = '''rule "x"
when
    $m : Map(this["file1"] != null, this["file2"] != null)
then
    reconciliation.rule.RuleDiffSupport.addFieldMismatch($m, kcontext.getRule().getName(), "café résumé", ((Map) $m.get("file1")).get("x"), null, "WARN", "Mismatch café");
end'''
        assertNull(RuleLogicValidator.firstViolation(ruleLogic))
    }

    // ---- Pen-test confirmed bypasses (review round 3) that MUST now be rejected ----

    @Test
    void commentStringDesyncBlockBypassIsRejected() {
        // "/*" and "*/" are string literals to the compiler, so the code between them is LIVE.
        String ruleLogic = 'rule "x" when $m : Map() then String a="/*"; java.lang.Runtime.getRuntime().exec("id"); String b="*/"; end'
        assertNotNull(RuleLogicValidator.firstViolation(ruleLogic))
    }

    @Test
    void commentStringDesyncLineBypassIsRejected() {
        String ruleLogic = 'rule "x" when $m : Map() then String a="//"; java.lang.Runtime.getRuntime().exec("id"); end'
        assertNotNull(RuleLogicValidator.firstViolation(ruleLogic))
    }

    @Test
    void newFormatterFileWriteIsRejected() {
        // java.util.Formatter(path) writes/truncates an arbitrary file — the java.util.* prefix must NOT be trusted wholesale.
        assertNotNull(RuleLogicValidator.firstViolation('rule "x" when $m : Map() then new java.util.Formatter("/tmp/x").format("y").flush(); end'))
    }

    @Test
    void formatterInEvalConstraintIsRejected() {
        assertNotNull(RuleLogicValidator.firstViolation('rule "x" when $m : Map( eval( new java.util.Formatter("/tmp/x").format("y").flush() == null ) ) then results.add($m); end'))
    }

    @Test
    void spacedFqcnFileWriterIsRejected() {
        // Whitespace between dots must not dodge the FQCN allow-list.
        assertNotNull(RuleLogicValidator.firstViolation('rule "x" when $m : Map() then java . io . FileWriter _w = new java . io . FileWriter("/tmp/x"); _w.write("p"); _w.close(); end'))
    }

    @Test
    void commentBetweenDotsFqcnIsRejected() {
        assertNotNull(RuleLogicValidator.firstViolation('rule "x" when $m : Map() then new java./*c*/io./*c*/FileWriter("/tmp/x"); end'))
    }

    @Test
    void filesStaticNoNewIsRejected() {
        // java.nio.file.Files needs no `new` — the curated allow-list + dot canonicalization must catch it.
        assertNotNull(RuleLogicValidator.firstViolation('rule "x" when $m : Map() then java . nio . file . Files.write(null, null); end'))
    }

    @Test
    void timerTaskAnonymousClassIsRejected() {
        assertNotNull(RuleLogicValidator.firstViolation('rule "x" when $m : Map() then new java.util.Timer(true).schedule(new java.util.TimerTask(){ public void run(){} }, 1L); end'))
    }

    @Test
    void beansStatementReflectionIsRejected() {
        assertNotNull(RuleLogicValidator.firstViolation('rule "x" when $m : Map() then new java.beans.Statement(new java.io.File("/tmp/x"), "createNewFile", new Object[]{}).execute(); end'))
    }

    @Test
    void bareNewIsRejected() {
        assertNotNull(RuleLogicValidator.firstViolation('rule "x" when $m : Map() then new java.util.ArrayList(); end'))
    }

    @Test
    void loopConstructIsRejected() {
        assertNotNull(RuleLogicValidator.firstViolation('rule "x" when $m : Map( eval( ({ while(true){} } ) == null ) ) then end'))
    }

    @Test
    void lambdaIsRejected() {
        assertNotNull(RuleLogicValidator.firstViolation('rule "x" when $m : Map() then java.util.Arrays.asList(1).forEach(z -> z); end'))
    }

    @Test
    void disallowedJavaUtilTypeIsRejected() {
        // Even without `new`, a non-curated java.util reference is rejected (only Objects/Arrays allowed).
        assertNotNull(RuleLogicValidator.firstViolation('rule "x" when $m : Map() then java.util.Scanner s; end'))
    }

    // ---- DoS-sink rejections (pen-test round 4: defense-in-depth) ----

    @Test
    void regexMatchesReDoSIsRejected() {
        assertNotNull(RuleLogicValidator.firstViolation('rule "x" when $m : Map() then boolean b = "aaaa".matches("(.*a){25}"); end'))
    }

    @Test
    void stringRepeatAllocationIsRejected() {
        assertNotNull(RuleLogicValidator.firstViolation('rule "x" when $m : Map() then String z = "0123456789".repeat(900000000); end'))
    }

    @Test
    void stringFormatWidthAllocationIsRejected() {
        assertNotNull(RuleLogicValidator.firstViolation('rule "x" when $m : Map() then String.format("%2147483640d", 1); end'))
    }

    @Test
    void droolsUpdateAgendaLoopIsRejected() {
        assertNotNull(RuleLogicValidator.firstViolation('rule "x" when $m : Map(this["file1"] != null) then update($m); end'))
    }

    @Test
    void droolsModifyAgendaLoopIsRejected() {
        assertNotNull(RuleLogicValidator.firstViolation('rule "x" when $m : Map(this["file1"] != null) then modify($m){ put("z","z") }; end'))
    }

    @Test
    void systemPropertyReadIsRejected() {
        assertNotNull(RuleLogicValidator.firstViolation('rule "x" when $m : Map() then results.add(Integer.getInteger("os.arch")); end'))
        assertNotNull(RuleLogicValidator.firstViolation('rule "x" when $m : Map() then Boolean.getBoolean("x"); end'))
    }

    @Test
    void validateReturnsListOfViolations() {
        List<String> v = RuleLogicValidator.validate('rule "x" when $m : Map() then Runtime.getRuntime(); end')
        assertTrue(v.size() >= 1)
        assertTrue(v.every { it instanceof String && it })
    }
}
