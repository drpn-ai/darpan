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
        assertFalse(AppContentRules.patternProblems("a*b*c*d* eeee").isEmpty(), "three or more *")
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
        assertTrue(AppContentRules.aliasProblems("one|ONE").any { it.contains("twice") })
        assertEquals(["one", "two"], AppContentRules.normalizedAliases("One| Two |one|"))
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
