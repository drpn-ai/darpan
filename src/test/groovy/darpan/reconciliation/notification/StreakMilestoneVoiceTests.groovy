package darpan.reconciliation.notification

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-121. The badge and long-streak lines for a stored streak (DAR-BE-107). Pure: the unitTest pool. */
class StreakMilestoneVoiceTests {

    /** Ten days of one busy tenant's space, as RunNotificationVoiceTests sizes the streak pool. */
    private static final int TEN_DAYS_OF_MESSAGES = 200

    @AfterEach
    void resetPicker() {
        RunNotificationVoice.resetLinePicker()
    }

    @Test
    void theLadderIsTheSevenDecidedBadgesInOrder() {
        assertEquals([5, 15, 30, 60, 90, 180, 365], StreakMilestoneVoice.BADGE_LADDER*.days)
        assertEquals(["First Light", "Full Moon", "Lunar Lap", "Twin Moons", "Solstice",
                      "Far Side of the Sun", "Full Orbit"], StreakMilestoneVoice.BADGE_LADDER*.title)
        assertEquals([false, false, true, true, true, true, true], StreakMilestoneVoice.BADGE_LADDER*.reEarnable)
        // The badges with a GIF share their key with the celebration catalogue.
        StreakCelebrationSupport.CATALOG.keySet().findAll { !(it in ["BIG_HAUL", "BLACK_FRIDAY_SURVIVOR"]) }.each {
            assertTrue(StreakMilestoneVoice.badge(it) != null, "no ladder rung for catalogue key ${it}".toString())
        }
    }

    @Test
    void theNextBadgeIsTheFirstRungNotYetReached() {
        assertEquals("Lunar Lap", StreakMilestoneVoice.nextBadge(21).title)
        assertEquals("Twin Moons", StreakMilestoneVoice.nextBadge(30).title)
        assertEquals("Full Orbit", StreakMilestoneVoice.nextBadge(364).title)
        assertNull(StreakMilestoneVoice.nextBadge(365))
        assertNull(StreakMilestoneVoice.nextBadge(1200))
    }

    @Test
    void countsAndOrdinalsReadRightAtTheAwkwardNumbers() {
        assertEquals("1 day", StreakMilestoneVoice.dayCount(1))
        assertEquals("12 days", StreakMilestoneVoice.dayCount(12))
        assertEquals(["2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "22nd", "23rd", "101st", "111th", "112th"],
                [2, 3, 4, 11, 12, 13, 21, 22, 23, 101, 111, 112].collect { StreakMilestoneVoice.ordinal(it) })
    }

    @Test
    void everyBadgeHasItsOwnFirstEarnPoolAndItRendersTheTitle() {
        StreakMilestoneVoice.BADGE_LADDER.each { Map badge ->
            List<String> pool = StreakMilestoneVoice.FIRST_EARN_LINES.get(badge.key)
            assertTrue(pool != null && pool.size() >= 12, "${badge.key} has ${pool?.size()} first-earn lines".toString())
            pool.each { String template ->
                assertEquals(1, template.count("{badge}"), template)
                RunNotificationVoice.setLinePicker { List<String> p, String slot -> template }
                String line = StreakMilestoneVoice.badgeLine(badge.key as String, 1)
                assertTrue(line.contains(badge.title as String), line)
                assertFalse(line.contains("{"), line)
            }
        }
    }

    @Test
    void aReEarnNamesTheBadgeAndWhichTimeAndOnceOnlyBadgesStaySilent() {
        assertTrue(StreakMilestoneVoice.RE_EARN_LINES.size() >= 30, "${StreakMilestoneVoice.RE_EARN_LINES.size()} re-earn lines".toString())
        StreakMilestoneVoice.RE_EARN_LINES.each { String template ->
            assertEquals(1, template.count("{badge}"), template)
            assertEquals(1, template.count("{ordinal}"), template)
            RunNotificationVoice.setLinePicker { List<String> p, String slot -> template }
            String line = StreakMilestoneVoice.badgeLine("QUARTER_CLEAN", 3)
            assertTrue(line.contains("Solstice") && line.contains("3rd"), line)
        }
        RunNotificationVoice.resetLinePicker()
        assertNull(StreakMilestoneVoice.badgeLine("HIGH_FIVE", 2), "First Light is earned once per automation, ever")
        assertNull(StreakMilestoneVoice.badgeLine("FIFTEEN_DAYS_OF_FAME", 2))
        assertNull(StreakMilestoneVoice.badgeLine("NOT_A_BADGE", 1))
        assertNull(StreakMilestoneVoice.badgeLine("MONTH_DOZE", 0))
    }

    @Test
    void aStreakLineStartsAtThreeDaysAndNeverLeavesAPlaceholder() {
        assertNull(StreakMilestoneVoice.streakLine(0))
        assertNull(StreakMilestoneVoice.streakLine(2), "two days is not a streak worth a line")
        [3, 4, 20, 21, 29, 89, 364, 365, 400, 1500].each { int days ->
            StreakMilestoneVoice.STREAK_WITH_NEXT_LINES.size().times {
                String line = StreakMilestoneVoice.streakLine(days)
                assertTrue(line.contains(Integer.toString(days)), line)
                assertFalse(line.contains("{"), line)
                if (days >= 365) assertFalse(line.contains("Full Orbit"), "past the top rung there is no next badge: ${line}".toString())
            }
        }
    }

    @Test
    void theNextBadgeLineNamesTheRightBadgeAndDistance() {
        StreakMilestoneVoice.NEXT_BADGE_LINES.each { String template ->
            assertEquals(1, template.count("{next}"), template)
            assertEquals(1, template.count("{left}"), template)
            assertFalse(template.contains("{left} day"), "the distance carries its own unit: ${template}".toString())
            RunNotificationVoice.setLinePicker { List<String> p, String slot -> template }
            String line = StreakMilestoneVoice.streakLine(89)
            assertTrue(line.contains("Solstice") && line.contains("1 day") && !line.contains("1 days"), line)
        }
    }

    @Test
    void tenDaysOfStreaksNeverRepeatALine() {
        assertTrue(StreakMilestoneVoice.STREAK_WITH_NEXT_LINES.size() >= TEN_DAYS_OF_MESSAGES,
                "streak pool is ${StreakMilestoneVoice.STREAK_WITH_NEXT_LINES.size()}".toString())
        List<String> lines = (1..TEN_DAYS_OF_MESSAGES).collect { StreakMilestoneVoice.streakLine(45) }
        assertEquals(TEN_DAYS_OF_MESSAGES, lines.toSet().size(),
                "a streak line repeated within ten days: ${lines.countBy { it }.findAll { it.value > 1 }.keySet()}".toString())
    }

    @Test
    void streakLinesDoNotMostlyOpenOnTheCount() {
        List<String> pool = StreakMilestoneVoice.STREAK_WITH_NEXT_LINES
        int countFirst = pool.count { it.startsWith("{n}") || it.startsWith("Day {n}") } as int
        assertTrue(countFirst * 3 <= pool.size(), "${countFirst} of ${pool.size()} open on the count".toString())
    }

    @Test
    void everyLineIsQuietPlainTextAndOnlyAFirstEarnGetsMoreThanOneSentence() {
        List<String> firstEarn = StreakMilestoneVoice.FIRST_EARN_LINES.values().flatten() as List<String>
        List<String> all = firstEarn + StreakMilestoneVoice.RE_EARN_LINES +
                StreakMilestoneVoice.STREAK_LINES + StreakMilestoneVoice.NEXT_BADGE_LINES
        assertTrue(all.size() > 300, "corpus is ${all.size()} lines".toString())
        all.each { String line ->
            // DAR-BE-138: a first earn is rare enough for a joke, so up to three short sentences; the rest stay at one.
            int sentences = line.count(".")
            if (line in firstEarn) assertTrue(sentences >= 1 && sentences <= 3, "one to three sentences: ${line}".toString())
            else assertEquals(1, sentences, "one sentence: ${line}".toString())
            assertTrue(line.endsWith("."), line)
            assertFalse(line.contains("!"), "copy shouts: ${line}".toString())
            String lower = line.toLowerCase()
            assertFalse(lower.contains("clean"), "the verdict already said it: ${line}".toString())
            assertFalse(lower.contains("agree"), "banned wording: ${line}".toString())
            line.toCharArray().each { char c ->
                assertTrue(((int) c) < 128 || ((int) c) == 0x2014, "non-plain character in: ${line}".toString())
            }
        }
        (StreakMilestoneVoice.STREAK_LINES + StreakMilestoneVoice.NEXT_BADGE_LINES).each { String line ->
            assertFalse((line.toLowerCase() =~ /\bruns?\b/).find(), "a stored streak counts days, not runs: ${line}".toString())
        }
        [StreakMilestoneVoice.RE_EARN_LINES, StreakMilestoneVoice.STREAK_WITH_NEXT_LINES].each { List<String> pool ->
            assertEquals(pool.size(), pool.toSet().size(), "duplicate entry in a pool")
        }
        StreakMilestoneVoice.FIRST_EARN_LINES.each { String key, List<String> pool ->
            assertEquals(pool.size(), pool.toSet().size(), "duplicate entry in ${key}".toString())
        }
    }

    @Test
    void theFactLineLabelsTheFactAndSaysNothingWithoutOne() {
        assertEquals("Space fact: Io is volcanic.", StreakMilestoneVoice.factLine(" Io is volcanic. "))
        assertNull(StreakMilestoneVoice.factLine(null))
        assertNull(StreakMilestoneVoice.factLine("  "))
    }

    @Test
    void theQuestLinesUseTheAstronomyNames() {
        RunNotificationVoice.setLinePicker { List<String> p, String slot -> p[0] }
        assertTrue(StreakMilestoneVoice.questLine("BIG_HAUL", 12345L).contains("Meteor Shower"))
        assertTrue(StreakMilestoneVoice.questLine("BIG_HAUL", 12345L).contains("12,345"))
        assertTrue(StreakMilestoneVoice.questLine("BLACK_FRIDAY_SURVIVOR", 0L).contains("Black Hole Survivor"))
    }
}
