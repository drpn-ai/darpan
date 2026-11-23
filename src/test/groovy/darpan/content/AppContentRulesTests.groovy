package darpan.content

import groovy.xml.XmlSlurper
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

/** DAR-BE-313: the pure rules every mascot entry obeys (no database; the DB-backed ones are in AppContentServicesSmokeTests). */
class AppContentRulesTests {
    private static List<String> words(String kind, String source, String title, String body, String dt = null, String db = null) {
        return AppContentRules.wordProblems(kind, source, title, body, dt, db)
    }

    @Test
    void normalizeMatchesTheAppVectors() {
        assertEquals("timeout", AppContentRules.normalize("  Timeout (seconds): "))
        assertEquals("schema", AppContentRules.normalize("Source 2 Schema"))
        assertEquals("stop run", AppContentRules.normalize("Stop   Run"))
        assertEquals("stop run", AppContentRules.normalize("\u00A0Stop\u00A0\u00A0Run:\uFEFF"), "JS \\s covers NBSP and the BOM")
        assertEquals("i", AppContentRules.normalize("I"), "lower-cased with the root locale")
    }

    @Test
    void acceptsTheShippedWords() {
        File seed = new File(AppContentRulesTests.class.protectionDomain.codeSource.location.toURI()).parentFile
        // Walk up to the component dir that holds data/AppContentSeedData.xml.
        while (seed != null && !new File(seed, "data/AppContentSeedData.xml").exists()) seed = seed.parentFile
        assertNotNull(seed, "component dir not found")
        def xml = new XmlSlurper().parse(new File(seed, "data/AppContentSeedData.xml"))
        def rows = xml.'darpan.content.AppContentEntry'
        assertTrue(rows.size() > 400, "the seed has its entries")
        List<String> bad = []
        int checked = 0
        rows.each { def r ->
            String kind = r.@contentKind.text()
            String title = kind == AppContentRules.HINT ? null : r.@defaultTitle.text()
            List<String> p = words(kind, AppContentRules.SEED, title, r.@defaultBody.text(), r.@defaultTitle.text(), r.@defaultBody.text())
            checked++
            if (p) bad << "${r.@contentKey.text()}: ${p}".toString()
        }
        assertEquals([], bad)
        assertTrue(checked > 400)
    }

    @Test
    void refusesMarkupNewlineOverBudgetUppercase() {
        assertTrue(words("MASCOT_GLOSSARY", "ADMIN", "Term", "has <b>markup</b>").any { it.contains("< or >") })
        assertTrue(words("MASCOT_GLOSSARY", "ADMIN", "Term", "two\nlines").any { it.contains("one line") })
        assertTrue(words("MASCOT_GLOSSARY", "ADMIN", "Term", "starts lowercase ").any { it.contains("space") })
        assertTrue(words("MASCOT_GLOSSARY", "ADMIN", "Term", "").any { it.contains("body is required") })
        assertTrue(words("MASCOT_GLOSSARY", "ADMIN", "Term", "Starts upper").any { it.contains("lowercase") })
        assertTrue(words("MASCOT_GLOSSARY", "ADMIN", "T" * 61, "ok").any { it.contains("title is over") })
        assertTrue(words("MASCOT_GLOSSARY", "ADMIN", "Term", "x" * 200).any { it.contains("together") })
        assertTrue(words("MASCOT_HINT", "ADMIN", null, "x" * 161).any { it.contains("over 160") })
        assertEquals([], words("MASCOT_HINT", "ADMIN", null, "Press the key. " + "x" * 140), "a hint may start uppercase")
        assertTrue(words("MASCOT_HINT", "ADMIN", "Title", "text").any { it.contains("no title") })
    }

    @Test
    void refusesATokenMismatch() {
        assertTrue(words("MASCOT_GLOSSARY", "SEED", "Window", "shown in {other}.", "Window", "shown in {detail}.").any { it.contains("{token}") })
        assertEquals([], words("MASCOT_GLOSSARY", "SEED", "Window", "now shown in {detail}.", "Window", "shown in {detail}."))
        assertTrue(words("MASCOT_GLOSSARY", "ADMIN", "Term", "shows {detail}").any { it.contains("token") })
    }

    @Test
    void refusesAFlavourOrBuzzword() {
        assertTrue(words("MASCOT_GLOSSARY", "ADMIN", "Term", "sorry about that").any { it.contains("flavour") })
        assertTrue(words("MASCOT_GLOSSARY", "ADMIN", "Term", "an effortless way").any { it.contains("buzzword") })
        assertTrue(words("MASCOT_HINT", "ADMIN", null, "We Leverage this").any { it.contains("buzzword") })
        assertTrue(words("MASCOT_HINT", "ADMIN", null, "I’ll know when it ends.").any { it.contains("flavour") })
    }

    @Test
    void refusesAMalformedPattern() {
        assertEquals([], AppContentRules.patternProblems("missing from *"))
        assertEquals([], AppContentRules.patternProblems("retry * not run"))
        assertEquals([], AppContentRules.patternProblems("abcd*efg*hij"), "two * are allowed")
        assertTrue(AppContentRules.patternProblems("abcd*efg*hij*klm").any { it.contains("at most 2") }, "exactly three * are refused")
        assertTrue(AppContentRules.patternProblems("stop  run *").any { it.contains("normalised") }, "a double space can never match")
        assertTrue(AppContentRules.patternProblems("run note: *").isEmpty(), "an inner colon is not stripped by normalize")
        assertTrue(AppContentRules.patternProblems("stop run *:").any { it.contains("normalised") }, "a trailing colon can never match")
        assertTrue(AppContentRules.patternProblems("some thing** more").any { it.contains("**") })
        assertTrue(AppContentRules.patternProblems("a*b").any { it.contains("at least") }, "under 4 literal characters")
        assertTrue(AppContentRules.patternProblems("word " + "x" * 60 + " *").any { it.contains("over 60") })
        assertTrue(AppContentRules.patternProblems("Missing from *").any { it.contains("lowercase") })
        assertTrue(AppContentRules.patternProblems("a*" * 1024).any { it.contains("over 60") }, "a 2 KB string")
        assertTrue(AppContentRules.patternProblems("<b>missing</b> *").any { it.contains("< or >") })
    }

    @Test
    void refusesAMalformedAlias() {
        assertEquals([], AppContentRules.aliasProblems(["good alias", "Another One"]))
        assertTrue(AppContentRules.aliasProblems(["fine", ""]).any { it.contains("empty") })
        assertTrue(AppContentRules.aliasProblems(["a" * 61]).any { it.contains("over 60") })
        assertTrue(AppContentRules.aliasProblems(["<b>x</b>"]).any { it.contains("< or >") })
        assertTrue(AppContentRules.aliasProblems((1..11).collect { "alias ${it}".toString() }).any { it.contains("at most 10") })
        assertEquals([], AppContentRules.aliasProblems((1..10).collect { "alias ${it}".toString() }))
        assertTrue(AppContentRules.aliasProblems(["differences|foo"]).any { it.contains("cannot contain |") }, "the separator never rides inside an alias")
        assertTrue(AppContentRules.aliasProblems("one|ONE").any { it.contains("twice") })
        assertEquals(["one", "two"], AppContentRules.normalizedAliases("One| Two |one|"))
    }

    @Test
    void servableRefusesARowWrittenAroundTheRules() {
        assertTrue(AppContentRules.servable([contentKind: "MASCOT_GLOSSARY", source: "ADMIN", title: "Term", body: "is fine.", aliases: "my term"]))
        assertFalse(AppContentRules.servable([contentKind: "MASCOT_GLOSSARY", source: "ADMIN", title: "Term", body: "has <b>markup</b>"]))
        assertFalse(AppContentRules.servable([contentKind: "MASCOT_GLOSSARY", source: "SEED", defaultTitle: "T", defaultBody: "ok.", body: "<b>x</b>"]))
        assertFalse(AppContentRules.servable([contentKind: "MASCOT_GLOSSARY", source: "SEED", defaultTitle: "T", defaultBody: "ok.", aliases: "a|" + "x" * 61]))
        assertTrue(AppContentRules.servable([contentKind: "MASCOT_GLOSSARY", source: "SEED", defaultTitle: "T", defaultBody: "ok.", hidden: "Y"]))
        assertFalse(AppContentRules.servable([contentKind: "MASCOT_GLOSSARY", source: "ADMIN", title: "Term", body: "is fine.", matchPattern: "a**b"]))
        assertTrue(AppContentRules.retired([source: "SEED", seedMark: "old"], "new"))
        assertFalse(AppContentRules.retired([source: "ADMIN"], "new"))
        assertFalse(AppContentRules.retired([source: "SEED", seedMark: "x"], null), "no seed loaded: nothing is retired")
    }

    /** One sample label per bundled regex source: the same table as darpan-ui's mascotSeed.spec.ts, run through java.util.regex here. */
    private static final Map<String, String> REGEX_SAMPLES = [
            '^open sync pair .+$': 'open sync pair hotwax → netsuite orders',
            '^every: .+$': 'every: 2 hours',
            '^allowance: .+$': 'allowance: 6 hours',
            '^run: .+$': 'run: daily order reconciliation',
            '^upstream side: .+$': 'upstream side: hotwax',
            '^chat space: .+$': 'chat space: none',
            '^hourly summary: .+$': 'hourly summary: off',
            '^missing (from|in) .+$': 'missing from netsuite',
            '^request access in( .+)?$': 'request access in shopify',
            '^revoke .+$': 'revoke token abc',
            '^download .+$': 'download results.csv',
            '^remove passkey( .+)?$': 'remove passkey laptop',
            '^remove .+$': 'remove ledger row',
            '^move .+ (up|down)$': 'move rule 3 up',
            '^change (rule|exception) .+$': 'change rule 4',
            '^.+ is a finding$': 'orders is a finding',
            '^check statement .+$': 'check statement 2',
            '^open rules for .+$': 'open rules for shopify',
            '^stop showing .+$': 'stop showing tips',
            '^edit chat space( .+)?$': 'edit chat space ops',
            '^send test to( .+)?$': 'send test to ops',
            '^post .+ (in .+|nowhere)$': 'post digest nowhere',
            '^edit rule .+$': 'edit rule 7',
            '^notifying — .+$': 'notifying — ops',
            '^retry (\\d+ )?not run$': 'retry 3 not run',
            '^open query .+$': 'open query backorders',
            '^schedule: .+$': 'schedule: daily',
            '^sends to: .+$': 'sends to: ops',
            '^export .+ extract$': 'export orders extract',
            '^(expand|collapse) .+ (object|array)$': 'expand items array',
            '^open statement .+$': 'open statement 4',
            '^audit schedule: .+$': 'audit schedule: daily',
            '^after each run: .+$': 'after each run: email',
            '^audit posts to: .+$': 'audit posts to: ops',
            '^open conclusion .+$': 'open conclusion 2',
            '^restore version .+$': 'restore version 3',
            '^open peak period .+$': 'open peak period 3 pm',
            '^change this line: .+$': 'change this line: netsuite billing_status is one of pending billing',
            '^change name: .+$': 'change name: shipped, not billed',
            '^change what it explains: .+$': 'change what it explains: the rules hold',
            '^change the question: .+$': 'change the question: is netsuite waiting to bill it?',
            '^change how it shows: .+$': 'change how it shows: shown',
            '^change the fix: .+$': 'change the fix: none',
    ]

    @Test
    void everyBundledRegexInTheSeedCompilesAndMatchesItsSample() {
        File seed = new File(AppContentRulesTests.class.protectionDomain.codeSource.location.toURI()).parentFile
        while (seed != null && !new File(seed, "data/AppContentSeedData.xml").exists()) seed = seed.parentFile
        assertNotNull(seed, "component dir not found")
        def xml = new XmlSlurper().parse(new File(seed, "data/AppContentSeedData.xml"))
        List<String> sources = []
        xml.'darpan.content.AppContentEntry'.each { def r ->
            String regex = r.@matchRegex.text()
            if (!regex) return
            regex.split(/\n/).each { String source ->
                sources << source
                assertEquals(1, AppContentRules.bundledPatterns(source).size(), "${source} compiles under java.util.regex")
                String sample = REGEX_SAMPLES[source]
                assertNotNull(sample, "no sample for ${source}: add it here and in mascotSeed.spec.ts")
                assertTrue(AppContentRules.bundledPatternAnswers(source, sample), "${source} matches '${sample}'")
            }
        }
        assertEquals(REGEX_SAMPLES.keySet().sort(), sources.sort())
        assertFalse(AppContentRules.bundledPatternAnswers("^download .+\$", "zzz fresh label"))
        assertFalse(AppContentRules.bundledPatternAnswers("", "anything"))
        assertFalse(AppContentRules.bundledPatternAnswers("([unclosed", "anything"), "a source that does not compile is skipped")
    }

    @Test
    void wildcardMatches() {
        assertTrue(AppContentRules.matchWildcard("missing from *", "missing from shopify"))
        assertFalse(AppContentRules.matchWildcard("missing from *", "missing from"))
        assertFalse(AppContentRules.matchWildcard("missing from *", "missing from "), "a * is one or more characters")
        assertTrue(AppContentRules.matchWildcard("retry * not run", "retry 3 not run"))
        assertFalse(AppContentRules.matchWildcard("retry * not run", "retry not run"))
        assertFalse(AppContentRules.matchWildcard("retry * not run", "retry 3 not run again"))
        assertTrue(AppContentRules.matchWildcard("exact", "exact"))
        assertFalse(AppContentRules.matchWildcard("exact", "exactly"))
        assertTrue(AppContentRules.matchWildcard("a*b*c", "a1b2c"))
        assertFalse(AppContentRules.matchWildcard("a*b*c", "abc"))
    }

    @Test
    void wildcardIsLinear() {
        String text = "a" * 100000
        long t0 = System.nanoTime()
        boolean hit = AppContentRules.matchWildcard("*a*", text)
        long ms = (System.nanoTime() - t0) / 1_000_000L
        assertTrue(hit)
        assertTrue(ms < 50, "took ${ms} ms")
    }
}
