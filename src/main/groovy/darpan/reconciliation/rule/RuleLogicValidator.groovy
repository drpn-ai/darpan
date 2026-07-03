package reconciliation.rule

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Audit 2026-06-11 #1/#6 — tenant rule-logic gate at the save#Rule boundary.
 *
 * {@code Rule.ruleLogic} flows verbatim into generated DRL whose {@code when}/{@code eval} constraints
 * are compiled by MVEL and whose {@code then} consequence is compiled by ECJ (Drools Java dialect) and
 * fired in the shared Moqui JVM (see {@link RuleEngineSupport#buildRuleSetDrl}). An authenticated tenant
 * who bypasses the UI and calls save#Rule / create#RuleSetRun directly could otherwise submit arbitrary
 * Java/MVEL — RCE plus cross-tenant exfiltration (reconciliation entity access runs disableAuthz).
 *
 * IMPORTANT — this validator is a constrained ALLOW-LIST, not a blacklist. Three adversarial review
 * rounds proved that a token denylist over source a full compiler then executes is NOT soundly
 * defensible (Unicode-escape desync, comment-vs-string desync, {@code new java.util.Formatter} file
 * write, {@code java . io . FileWriter} via whitespace-between-dots, {@code java.nio.file.Files},
 * {@code java.beans.Statement} reflection, {@code Timer}/{@code TimerTask} threads, MVEL {@code while}
 * DoS). So this gate now: (1) decodes Unicode escapes first (matching ECJ's first lexer step); (2)
 * skeletonizes comments/strings with a single-pass JLS-precedence lexer (not order-dependent regexes);
 * (3) canonicalizes whitespace/comments around '.' so spaced fully-qualified names cannot dodge the
 * scan; (4) rejects {@code new}, lambdas, method refs, loops, and class/function declarations outright
 * (legitimate UI/field-comparison rules use none of these); (5) permits fully-qualified references ONLY
 * to a curated safe set (RuleDiffSupport + a few pure java.util helpers), not the whole java.util.*
 * namespace; and (6) keeps the class/reflection denylists as defense-in-depth.
 *
 * This is still defense-in-depth, NOT a complete sandbox. The durable fix is to not compile
 * tenant-authored DRL in-process at all (regenerate the DRL server-side from the structured
 * {@code expression}, or evaluate rules out-of-process with no filesystem/reflection). See the audit.
 *
 * Platform/system seed rules load through the entity data facade (data/*.xml), not save#Rule, so they
 * are deliberately unaffected by this gate.
 */
class RuleLogicValidator {

    // Curated fully-qualified references legitimate rule logic may use (compared lower-cased, after
    // whitespace-around-dots canonicalization). Deliberately NOT the whole java.util.* namespace —
    // that includes I/O-capable types (Formatter, Scanner, Properties, ...). Only the pure helpers the
    // UI generator and the parsed-ruleText template actually emit.
    private static final List<String> ALLOWED_FQ_PREFIXES = [
            "reconciliation.rule.rulediffsupport",
            "java.util.objects",
            "java.util.arrays",
    ].asImmutable()

    // First segment of a dotted reference that marks it as a package/class path we must vet. Anything
    // starting with one of these must match ALLOWED_FQ_PREFIXES; identifiers whose root is not here
    // (e.g. $m, kcontext, results, _file1Value) are local references and ignored.
    private static final Set<String> KNOWN_PACKAGE_ROOTS = [
            "java", "javax", "jakarta", "sun", "jdk", "groovy", "org", "com", "net",
            "io", "kotlin", "scala", "mvel", "mvel2", "ognl", "drools", "kie",
            "moqui", "darpan", "reconciliation"
    ] as Set

    // Constructs that let a constrained rule reach arbitrary code and that NO legitimate field-comparison
    // rule uses: object construction (new java.util.Formatter / new TimerTask(){...}), lambdas/method
    // refs (custom Comparator/Runnable bodies), and loops / function & class declarations (DoS, custom
    // method bodies). Rejecting these removes the gadget surface a class allow-list alone cannot.
    private static final Pattern FORBIDDEN_CONSTRUCT =
            ~/(?i)\b(new|while|for|do|switch|function|def|class|interface|enum|extends|implements|synchronized|try|catch|finally|throw|throws|instanceof|assert|return|volatile|native|transient)\b/
    private static final Pattern LAMBDA_OR_METHODREF = Pattern.compile('->|::')

    // Whole-identifier class names that grant code execution / process control (defense-in-depth; the
    // allow-list + FORBIDDEN_CONSTRUCT are the primary controls).
    private static final Pattern FORBIDDEN_CLASS =
            ~/(?i)\b(Runtime|ProcessBuilder|Process|System|Thread|ThreadGroup|ClassLoader|SecurityManager|Compiler|Unsafe|GroovyShell|GroovyClassLoader|ScriptEngineManager|ScriptEngine|ProcessHandle|Formatter|Scanner|FileWriter|FileReader|FileOutputStream|FileInputStream|RandomAccessFile|Files|Paths|Statement|Expression|URLClassLoader|DriverManager|InitialContext|MethodHandles|VarHandle|Timer|TimerTask)\b/

    // Reflection / dynamic-execution method and property accessors, plus the system-property/env
    // readers reachable on default-imported java.lang types (Integer.getInteger / Boolean.getBoolean /
    // Long.getLong / System.getProperty / getenv) — an info leak a field-comparison rule never needs.
    private static final Pattern FORBIDDEN_ACCESS =
            ~/(?i)(\.\s*(forName|newInstance|getClass|getMethod|getDeclaredMethod|getDeclaredMethods|getMethods|getConstructor|getDeclaredConstructor|invoke|loadClass|defineClass|getClassLoader|getKieRuntime|getKnowledgeRuntime|metaClass|getInteger|getBoolean|getLong|getProperty|getProperties|getenv)\s*\()|(\bexec\s*\()|(\.\s*class\b)|(\.\s*&)/

    // Denial-of-service sinks usable with pure allow-listed tokens — defense-in-depth (a single hung
    // regex / unbounded allocation cannot be soundly bounded in-process; see the class header and the
    // bounded fireAllRules in RuleEngineSupport). Legitimate field-comparison rules use NONE of these:
    //  - regex methods (catastrophic backtracking ReDoS): matches/replaceAll/replaceFirst/split, java.util.regex
    //  - unbounded allocation: String.repeat / String.format / Collections.nCopies
    //  - Drools agenda mutation that re-fires forever: update/modify/insert/insertLogical/retract/delete/halt
    private static final Pattern FORBIDDEN_DOS =
            ~/(?i)(\.\s*(matches|replaceall|replacefirst|split|repeat|format)\s*\()|(\bncopies\b)|(java\.util\.regex)|(\b(update|modify|insert|insertlogical|retract|delete|halt)\s*[({])/

    private static final Pattern IMPORT_DECL = ~/(?m)^\s*import\s+/

    private static final Pattern DOTTED_REF = Pattern.compile(
            '[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+')

    // Audit self-review (CRITICAL): ECJ applies JLS 3.3 Unicode-escape translation BEFORE tokenizing, so
    // a raw-string scan is bypassable ("Runtime" -> Runtime). Match backslash + one-or-more 'u' + 4 hex.
    private static final Pattern UNICODE_ESCAPE = Pattern.compile('\\\\u+([0-9a-fA-F]{4})')

    // Collapse whitespace and (already-blanked) comment runs that sit around a '.', so a spaced or
    // comment-interrupted fully-qualified name (java . io . File / java./*c*/io./*c*/File) canonicalizes
    // to java.io.File before DOTTED_REF runs — ECJ/MVEL treat these as the same class.
    private static final Pattern DOT_WHITESPACE = Pattern.compile('\\s*\\.\\s*')

    /** Returns the first violation message, or null if the rule logic is acceptable. */
    static String firstViolation(String ruleLogic) {
        List<String> v = validate(ruleLogic)
        return v ? v[0] : null
    }

    /** Returns all violation messages (empty list = acceptable). */
    static List<String> validate(String ruleLogic) {
        List<String> violations = []
        if (ruleLogic == null) return violations
        String raw = ruleLogic.trim()
        if (!raw) return violations

        // 1) Decode Unicode escapes on the RAW input first — JLS 3.3 decoding is ECJ's first lexer step,
        //    before comment/string recognition. Decoding here makes our comment/string spans match the
        //    compiler's, closing the escaped-quote / escaped-newline desync bypasses.
        String decoded = decodeUnicodeEscapes(raw)

        // 2) Skeletonize with a single left-to-right JLS-precedence lexer: a // or /* starts a comment
        //    only when NOT inside a string/char literal; inside a literal, / and * are content. This is
        //    not expressible as independently-ordered regex passes (which desynced on "/*" ... "*/").
        String code = stripCommentsAndStrings(decoded)

        // 3) Any Unicode escape still present in code is an evasion our decoder did not handle — reject.
        if (UNICODE_ESCAPE.matcher(code).find()) {
            violations.add("Rule logic must not contain Unicode escape sequences (\\uXXXX) in code.")
        }

        // 4) Canonicalize whitespace/comment runs around dots so spaced FQNs cannot dodge DOTTED_REF.
        code = DOT_WHITESPACE.matcher(code).replaceAll('.')

        if (IMPORT_DECL.matcher(code).find()) {
            violations.add("Rule logic must not declare imports; reference helpers by fully-qualified name (reconciliation.rule.RuleDiffSupport, java.util.Objects/Arrays).")
        }

        Matcher construct = FORBIDDEN_CONSTRUCT.matcher(code)
        if (construct.find()) {
            violations.add("Rule logic must not use '${construct.group(1)}'. Tenant rules are limited to field comparisons and RuleDiffSupport helper calls — no object construction, loops, or declarations.")
        }
        if (LAMBDA_OR_METHODREF.matcher(code).find()) {
            violations.add("Rule logic must not use lambdas or method references.")
        }

        Matcher fc = FORBIDDEN_CLASS.matcher(code)
        if (fc.find()) {
            violations.add("Rule logic references a forbidden class '${fc.group(1)}'.")
        }

        if (FORBIDDEN_ACCESS.matcher(code).find()) {
            violations.add("Rule logic uses a forbidden reflection or dynamic-execution construct.")
        }

        if (FORBIDDEN_DOS.matcher(code).find()) {
            violations.add("Rule logic uses a construct that can exhaust shared resources (regex matching, unbounded allocation, or Drools agenda mutation). Field-comparison rules do not need these.")
        }

        Matcher m = DOTTED_REF.matcher(code)
        while (m.find()) {
            String ref = m.group()
            String refLower = ref.toLowerCase(Locale.ROOT)
            String root = refLower.substring(0, refLower.indexOf('.'))
            if (!KNOWN_PACKAGE_ROOTS.contains(root)) continue
            if (!ALLOWED_FQ_PREFIXES.any { refLower.startsWith(it) }) {
                violations.add("Rule logic references a disallowed class '${ref}'. Only reconciliation.rule.RuleDiffSupport and java.util.Objects/Arrays are permitted.")
            }
        }

        return violations.collect { it.toString() }.unique()
    }

    // Single-pass lexer that blanks comment bodies (to a space) and string/char-literal CONTENTS (keeping
    // the delimiters so token boundaries stay visible), in JLS precedence. Strings are recognized before
    // comments, so a "/*" or "//" inside a string cannot start a comment, and a /* ... */ cannot consume
    // a quote — matching exactly what ECJ/javac tokenize.
    private static String stripCommentsAndStrings(String src) {
        StringBuilder out = new StringBuilder(src.length())
        int n = src.length()
        int i = 0
        while (i < n) {
            char c = src.charAt(i)
            if (c == ('"' as char) || c == ("'" as char)) {
                char q = c
                out.append(q)
                i++
                while (i < n) {
                    char d = src.charAt(i)
                    if (d == ('\\' as char) && i + 1 < n) { i += 2; continue }
                    if (d == q) { i++; break }
                    if (d == ('\n' as char)) { break } // defensive: unterminated literal (ECJ would error)
                    i++
                }
                out.append(q)
                continue
            }
            if (c == ('/' as char) && i + 1 < n && src.charAt(i + 1) == ('/' as char)) {
                i += 2
                while (i < n && src.charAt(i) != ('\n' as char)) i++
                out.append(' ')
                continue
            }
            if (c == ('/' as char) && i + 1 < n && src.charAt(i + 1) == ('*' as char)) {
                i += 2
                while (i + 1 < n && !(src.charAt(i) == ('*' as char) && src.charAt(i + 1) == ('/' as char))) i++
                i = Math.min(i + 2, n)
                out.append(' ')
                continue
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    // Decode JLS 3.3 Unicode escapes (\\uXXXX, incl. the multi-'u' form) the way the compiler does, so we
    // scan the same source ECJ produces. Over-decoding here only makes the scan stricter.
    private static String decodeUnicodeEscapes(String s) {
        Matcher m = UNICODE_ESCAPE.matcher(s)
        StringBuffer sb = new StringBuffer()
        while (m.find()) {
            try {
                int cp = Integer.parseInt(m.group(1), 16)
                m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(cp))))
            } catch (Exception ignored) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()))
            }
        }
        m.appendTail(sb)
        return sb.toString()
    }
}
