package darpan.reconciliation.notification

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-138. The astronomy facts on badge and side-quest messages. Pure: the unitTest pool. */
class AstronomyFactsTests {

    @Test
    void thereAreEnoughFactsForYearsOfBadgesAndEachHasItsOwnId() {
        assertTrue(AstronomyFacts.FACTS.size() >= 300, "${AstronomyFacts.FACTS.size()} facts".toString())
        assertEquals(AstronomyFacts.FACTS.size(), AstronomyFacts.FACTS*.toLowerCase().toSet().size(), "a fact is listed twice")
        assertEquals(AstronomyFacts.FACTS.size(), AstronomyFacts.BY_ID.size(), "two facts share an id")
        AstronomyFacts.FACTS.each { String fact -> assertEquals(fact, AstronomyFacts.text(AstronomyFacts.idOf(fact))) }
    }

    @Test
    void anIdIsTheTextNotThePositionSoReorderingKeepsWhatATenantHeard() {
        String fact = AstronomyFacts.FACTS[0]
        assertEquals(AstronomyFacts.idOf(fact), AstronomyFacts.idOf(new String(fact.toCharArray())))
        assertTrue(AstronomyFacts.idOf(fact) ==~ /AF_[0-9a-f]{16}/, AstronomyFacts.idOf(fact))
        assertNull(AstronomyFacts.text("AF_0000000000000000"))
        assertNull(AstronomyFacts.text(null))
    }

    @Test
    void everyFactIsOnePlainSentenceOnOneChatLine() {
        AstronomyFacts.FACTS.each { String fact ->
            assertTrue(fact.length() >= 50 && fact.length() <= 150, "length ${fact.length()}: ${fact}".toString())
            assertEquals(1, fact.count("."), "one sentence, no decimals or abbreviations: ${fact}".toString())
            assertTrue(fact.endsWith("."), fact)
            assertFalse(fact.contains("!") || fact.contains("?") || fact.contains(";"), fact)
            assertFalse(fact.contains("\n"), fact)
            String lower = fact.toLowerCase()
            assertFalse(lower.contains("clean") || lower.contains("agree"), "banned wording: ${fact}".toString())
            fact.toCharArray().each { char c -> assertTrue(((int) c) < 128, "non-plain character in: ${fact}".toString()) }
        }
    }

    @Test
    void aPickIsNeverAFactAlreadyHeardAndNothingIsLeftOnceAllAre() {
        Random random = new Random(7)
        Set<String> heard = [] as Set<String>
        AstronomyFacts.FACTS.size().times {
            String id = AstronomyFacts.pickUnheard(heard, random)
            assertTrue(id != null && heard.add(id), "picked ${id} again".toString())
        }
        assertEquals(AstronomyFacts.BY_ID.keySet(), heard)
        assertNull(AstronomyFacts.pickUnheard(heard, random))
    }
}
