package darpan.reconciliation.streak

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.notification.TenantNotificationSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-107 / DAR-BE-108 against a real Moqui: slots folded into the stored streak, the badges and side quests they
 * earn, the run message that carries them, the badge GIF cap, and the read the Streaks page calls.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutomationStreakSupportSmokeTests {
    private static final String KREWE = "KREWE"
    private static final String DAILY = "0 0 2 * * ?"
    private static final String WEBHOOK = "https://chat.googleapis.com/v1/spaces/AAAA1234ZZZZ/messages?key=k&token=t"
    private static final String BASE_URL_PROPERTY = TenantNotificationSupport.APP_BASE_URL_PROPERTY

    private ExecutionContext ec
    private int seq = 0
    private List<Map> posted = []

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "automation-streak-support-smoke")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ["GORJANA", "STRK_BF", "STRK_BF_BROKEN", "STRK_CAP"].each { String tenant ->
            ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: tenant, description: tenant,
                    groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID]).createOrUpdate()
        }
        System.setProperty(BASE_URL_PROPERTY, "https://app.streaks.test")
        TenantNotificationSupport.setDeliveryHook { String ref, Map payload ->
            posted << payload
            return [ok: true, statusCode: 200]
        }
    }

    @AfterEach
    void clearPosts() {
        posted.clear()
    }

    @AfterAll
    void cleanup() {
        TenantNotificationSupport.resetDeliveryHook()
        System.clearProperty(BASE_URL_PROPERTY)
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    // ---- counting and badges ---------------------------------------------------------------------

    @Test
    void aMonthOfCleanDaysEarnsEachBadgeOnTheSlotThatCrossedIt() {
        automation("STRK_MONTH", KREWE)
        List<Instant> fires = dailyFires(31)
        List<String> executions = fires.collect { slot("STRK_MONTH", KREWE, it) }
        String crossing = result(executions[29], KREWE)
        String after = result(executions[30], KREWE)

        AutomationStreakSupport.recordSlots(ec, "STRK_MONTH")

        def streak = row("darpan.reconciliation.AutomationStreak", [automationId: "STRK_MONTH"])
        assertEquals(ts(fires[0].plus(Duration.ofMinutes(20))), streak.streakStartDate)
        assertEquals(ts(fires[30]), streak.lastSlotDate)
        assertEquals(ts(fires[4]), badge("STRK_MONTH", "HIGH_FIVE").lastEarnedSlotDate)
        assertEquals(ts(fires[14]), badge("STRK_MONTH", "FIFTEEN_DAYS_OF_FAME").lastEarnedSlotDate)
        assertEquals(ts(fires[29]), badge("STRK_MONTH", "MONTH_DOZE").lastEarnedSlotDate)
        assertNull(badge("STRK_MONTH", "DOUBLE_DOZE"))

        Map facts = AutomationStreakSupport.notificationFacts(ec, KREWE, crossing)
        assertEquals(daysSince(fires[0]), facts.streakDays, "no 20 cap: the stored streak counts every day")
        assertTrue((facts.streakDays as int) >= 31)
        assertTrue((facts.badgeLine as String).contains("Month Doze"), facts.badgeLine as String)
        Map celebration = AutomationStreakSupport.celebrationFor(ec, facts)
        assertTrue((celebration.imageUrl as String).endsWith("/streaks/darpan-doze.gif"), celebration.imageUrl as String)
        assertEquals("badge", celebration.kind)

        Map next = AutomationStreakSupport.notificationFacts(ec, KREWE, after)
        assertNull(next.badgeLine, "the slot after the crossing earns nothing")
        assertNull(AutomationStreakSupport.celebrationFor(ec, next))

        // Folding again never counts a slot twice.
        AutomationStreakSupport.recordSlots(ec, "STRK_MONTH")
        assertEquals(1L, badge("STRK_MONTH", "MONTH_DOZE").timesEarned)
        assertEquals(ts(fires[0].plus(Duration.ofMinutes(20))),
                row("darpan.reconciliation.AutomationStreak", [automationId: "STRK_MONTH"]).streakStartDate)
    }

    @Test
    void aValueMismatchOnTheExecutionBreaksTheStoredStreak() {
        automation("STRK_DIFF", KREWE)
        List<Instant> fires = dailyFires(6)
        fires.eachWithIndex { Instant fire, int i -> slot("STRK_DIFF", KREWE, fire, i == 3 ? [differenceCount: 2L] : [:]) }

        AutomationStreakSupport.recordSlots(ec, "STRK_DIFF")

        def streak = row("darpan.reconciliation.AutomationStreak", [automationId: "STRK_DIFF"])
        assertEquals("DIFFERENCES", streak.lastBreakReason)
        assertEquals(ts(fires[4].plus(Duration.ofMinutes(20))), streak.streakStartDate)
        assertEquals(3L, streak.bestDays)
    }

    @Test
    void aPauseCarriesTheStreakOverTheDaysItSkipped() {
        // Control: the same history without a pause is a missed slot.
        ["STRK_PAUSED", "STRK_UNPAUSED"].each { String id ->
            automation(id, KREWE)
            dailyFires(11).take(5).each { slot(id, KREWE, it) }
            AutomationStreakSupport.recordSlots(ec, id)
        }
        Map paused = (Map) ec.service.sync().name("facade.ReconciliationFacadeServices.pause#Automation")
                .parameters([automationId: "STRK_PAUSED"]).disableAuthz().call()
        assertTrue(paused.ok as boolean, paused.errors?.toString())

        ["STRK_PAUSED", "STRK_UNPAUSED"].each { String id ->
            slot(id, KREWE, dailyFires(1)[0])
            AutomationStreakSupport.recordSlots(ec, id)
        }

        assertNull(row("darpan.reconciliation.AutomationStreak", [automationId: "STRK_PAUSED"]).lastBreakReason)
        assertEquals("MISSED", row("darpan.reconciliation.AutomationStreak", [automationId: "STRK_UNPAUSED"]).lastBreakReason)
    }

    @Test
    void aFlowchartAutomationCountsItsQuestionsNoArrows() {
        automation("STRK_FLOW", KREWE, [savedRunType: "reconciliation"])
        List<Instant> fires = dailyFires(3)
        fires.eachWithIndex { Instant fire, int i ->
            String execution = slot("STRK_FLOW", KREWE, fire, [differenceCount: null])
            String walk = "WALK_${i}".toString()
            String first = result(execution, KREWE, [reconciliationExecutionId: walk, noCount: 0L])
            // A second question in the same walk: its "no" arrow is a difference on day 2 only.
            insert("darpan.reconciliation.ReconciliationRunResult", [reconciliationRunResultId: "${first}_Q2".toString(),
                    companyUserGroupId: KREWE, reconciliationExecutionId: walk, noCount: i == 1 ? 4L : 0L,
                    statusEnumId: "AUT_STAT_SUCCESS"])
        }

        AutomationStreakSupport.recordSlots(ec, "STRK_FLOW")

        def streak = row("darpan.reconciliation.AutomationStreak", [automationId: "STRK_FLOW"])
        assertEquals("DIFFERENCES", streak.lastBreakReason)
        assertEquals(ts(fires[2].plus(Duration.ofMinutes(20))), streak.streakStartDate)
    }

    // ---- side quests -----------------------------------------------------------------------------

    @Test
    void oneBigCleanRunFindsBigHaulOnceForTheTenant() {
        automation("STRK_HAUL", KREWE)
        List<Instant> fires = dailyFires(2)
        slot("STRK_HAUL", KREWE, fires[0])
        String big = result(slot("STRK_HAUL", KREWE, fires[1], [file1RecordCount: 12_000L, file2RecordCount: 11_990L]), KREWE)

        AutomationStreakSupport.recordSlots(ec, "STRK_HAUL")

        def quest = row("darpan.reconciliation.TenantStreakQuest", [companyUserGroupId: KREWE, questKey: "BIG_HAUL"])
        assertEquals("STRK_HAUL", quest.automationId)
        assertEquals(ts(fires[1]), quest.earnedSlotDate)
        Map facts = AutomationStreakSupport.notificationFacts(ec, KREWE, big)
        assertTrue((facts.questLine as String).contains("12,000"), facts.questLine as String)
        Map celebration = AutomationStreakSupport.celebrationFor(ec, facts)
        assertEquals("quest", celebration.kind)
        assertTrue((celebration.imageUrl as String).endsWith("/streaks/darpan-haul.gif"))

        // Once per tenant: a second haul by another automation finds nothing new.
        automation("STRK_HAUL_2", KREWE)
        slot("STRK_HAUL_2", KREWE, fires[1], [file1RecordCount: 50_000L])
        AutomationStreakSupport.recordSlots(ec, "STRK_HAUL_2")
        assertEquals(1, ec.entity.find("darpan.reconciliation.TenantStreakQuest").condition("companyUserGroupId", KREWE)
                .condition("questKey", "BIG_HAUL").disableAuthz().count())
        assertEquals("STRK_HAUL", row("darpan.reconciliation.TenantStreakQuest", [companyUserGroupId: KREWE, questKey: "BIG_HAUL"]).automationId)
    }

    @Test
    void everyAutomationCleanThroughCyberMondayPostsBlackFridaySurvivorToTheStreaksSpace() {
        // Black Friday 2025 was 28 November; Cyber Monday 1 December.
        List<Instant> fires = (0..12).collect { utcFire(LocalDate.of(2025, 11, 20).plusDays(it)) }
        ["STRK_BF", "STRK_BF_BROKEN"].each { String tenant ->
            insert("darpan.reconciliation.TenantChatSpace", [chatSpaceId: "${tenant}_SPACE".toString(),
                    companyUserGroupId: tenant, spaceName: "Streaks", webhookUrl: WEBHOOK, isActive: "Y"])
            insert("darpan.auth.TenantSetting", [companyUserGroupId: tenant, streaksChatSpaceId: "${tenant}_SPACE".toString()])
            automation("${tenant}_A".toString(), tenant)
            fires.eachWithIndex { Instant fire, int i ->
                // The control tenant's automation finds a difference on the Sunday.
                boolean dirty = tenant == "STRK_BF_BROKEN" && i == 10
                slot("${tenant}_A".toString(), tenant, fire, dirty ? [differenceCount: 1L] : [:])
            }
            AutomationStreakSupport.recordSlots(ec, "${tenant}_A".toString())
        }

        def quest = row("darpan.reconciliation.TenantStreakQuest", [companyUserGroupId: "STRK_BF", questKey: "BLACK_FRIDAY_SURVIVOR"])
        assertNotNull(quest)
        assertNull(quest.automationId)
        assertNotNull(quest.notifiedDate)
        assertEquals(1, posted.size())
        assertTrue((posted[0].text as String).contains("Black Friday Survivor"))
        assertTrue(((posted[0].cardsV2 as List)[0].card.sections[0].widgets[0].image.imageUrl as String).endsWith("/streaks/darpan-bfcm.gif"))

        assertNull(row("darpan.reconciliation.TenantStreakQuest", [companyUserGroupId: "STRK_BF_BROKEN", questKey: "BLACK_FRIDAY_SURVIVOR"]))
    }

    // ---- the run message -------------------------------------------------------------------------

    @Test
    void aSpaceGetsOneBadgeGifADayAndALaterBadgeKeepsItsLine() {
        insert("darpan.reconciliation.TenantChatSpace", [chatSpaceId: "STRK_CAP_SPACE", companyUserGroupId: "STRK_CAP",
                spaceName: "Ops", webhookUrl: WEBHOOK, isActive: "Y"])
        Map<String, String> crossing = [:]
        ["CAP_A", "CAP_B"].each { String id ->
            automation(id, "STRK_CAP", [chatSpaceId: "STRK_CAP_SPACE"])
            List<String> executions = dailyFires(30).collect { slot(id, "STRK_CAP", it) }
            crossing[id] = result(executions[29], "STRK_CAP", [savedRunId: "SR_${id}".toString()])
            AutomationStreakSupport.recordSlots(ec, id)
        }

        ["CAP_A", "CAP_B"].each { String id -> notify(id, crossing[id]) }

        assertEquals(2, posted.size())
        assertTrue(posted[0].cardsV2 != null, "the first badge of the day carries its GIF")
        assertTrue((posted[0].text as String).contains("Month Doze"), posted[0].text as String)
        assertNull(posted[1].cardsV2, "a second badge GIF to the same space that day is dropped")
        assertTrue((posted[1].text as String).contains("Month Doze"), "the line still goes out")
        assertFalse(posted.any { it.containsKey("celebration") }, "the provider-neutral key is never posted")
        assertNotNull(row("darpan.reconciliation.TenantChatSpace", [chatSpaceId: "STRK_CAP_SPACE"]).lastBadgeGifDate)
    }

    // ---- the read --------------------------------------------------------------------------------

    @Test
    void theStreakBoardIsCurrentBeforeAnyFoldAndShowsOnlyTheActiveTenant() {
        automation("STRK_READ", KREWE)
        dailyFires(6).each { slot("STRK_READ", KREWE, it) }
        automation("STRK_OTHER", "GORJANA")
        dailyFires(6).each { slot("STRK_OTHER", "GORJANA", it) }

        Map board = (Map) ec.service.sync().name("facade.ReconciliationFacadeServices.get#AutomationStreaks")
                .parameters([:]).disableAuthz().call()

        assertTrue(board.ok as boolean, board.errors?.toString())
        Map read = (board.automations as List<Map>).find { it.automationId == "STRK_READ" }
        assertNotNull(read, "positive control: the active tenant's automation is listed")
        assertEquals(daysSince(dailyFires(6)[0]), read.currentDays)
        assertNull((board.automations as List<Map>).find { it.automationId == "STRK_OTHER" }, "another tenant's automation is not")
        assertNull(row("darpan.reconciliation.AutomationStreak", [automationId: "STRK_READ"]), "the read never writes")
    }

    // ---- fixtures --------------------------------------------------------------------------------

    /** The last {@code count} daily 02:00 UTC fires up to now, oldest first. */
    private static List<Instant> dailyFires(int count) {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC)
        ZonedDateTime last = now.toLocalDate().atTime(2, 0).atZone(ZoneOffset.UTC)
        if (last.isAfter(now)) last = last.minusDays(1)
        return (count - 1..0).collect { last.minusDays(it).toInstant() }
    }

    /** Days of a streak that started at {@code firstFire}, counted through today (UTC, the fixtures' zone). */
    private static int daysSince(Instant firstFire) {
        return (int) (java.time.temporal.ChronoUnit.DAYS.between(firstFire.atZone(ZoneOffset.UTC).toLocalDate(),
                LocalDate.now(ZoneOffset.UTC)) + 1)
    }

    private static Instant utcFire(LocalDate day) {
        return day.atTime(2, 0).atZone(ZoneOffset.UTC).toInstant()
    }

    private void automation(String id, String tenant, Map extra = [:]) {
        insert("darpan.reconciliation.ReconciliationAutomation", [automationId: id, automationName: id,
                companyUserGroupId: tenant, inputModeEnumId: "AUT_IN_API_RANGE", savedRunId: "SR_${id}".toString(),
                scheduleExpr: DAILY, windowTimeZone: "UTC", isActive: "Y"] + extra)
    }

    private String slot(String automationId, String tenant, Instant scheduled, Map extra = [:]) {
        String id = "STRK_EX_${seq++}".toString()
        insert("darpan.reconciliation.ReconciliationAutomationExecution", [automationExecutionId: id,
                automationId: automationId, companyUserGroupId: tenant, statusEnumId: "AUT_STAT_SUCCESS",
                scheduledDate: ts(scheduled), completedDate: ts(scheduled.plus(Duration.ofMinutes(20))),
                differenceCount: 0L] + extra)
        return id
    }

    private String result(String executionId, String tenant, Map extra = [:]) {
        String id = "STRK_RR_${seq++}".toString()
        insert("darpan.reconciliation.ReconciliationRunResult", [reconciliationRunResultId: id, companyUserGroupId: tenant,
                statusEnumId: "AUT_STAT_SUCCESS", differenceCount: 0L, completedDate: new Timestamp(System.currentTimeMillis()),
                resultDataManagerPath: "streaks/${id}.json".toString()] + extra)
        ec.entity.find("darpan.reconciliation.ReconciliationAutomationExecution").condition("automationExecutionId", executionId)
                .disableAuthz().one().set("reconciliationRunResultId", id).update()
        return id
    }

    private void notify(String automationId, String resultId) {
        TenantNotificationSupport.notifyRunCompleted(ec, [reconciliationRunResultId: resultId, companyUserGroupId: "STRK_CAP",
                chatSpaceId: "STRK_CAP_SPACE", runName: automationId, savedRunId: "SR_${automationId}".toString(),
                resultDataManagerPath: "streaks/${resultId}.json".toString(), differenceCount: 0, onlyInFile1Count: 0,
                onlyInFile2Count: 0, ruleDifferenceCount: 0, statusEnumId: "AUT_STAT_SUCCESS", processingWarnings: []])
    }

    private def badge(String automationId, String key) {
        return row("darpan.reconciliation.AutomationStreakBadge", [automationId: automationId, badgeKey: key])
    }

    private def row(String entity, Map conditions) {
        def find = ec.entity.find(entity).disableAuthz().useCache(false)
        conditions.each { k, v -> find.condition(k as String, v) }
        return find.one()
    }

    private void insert(String entity, Map fields) {
        ec.entity.makeValue(entity).setAll(fields).createOrUpdate()
    }

    private static Timestamp ts(Instant instant) {
        return new Timestamp(instant.toEpochMilli())
    }
}
