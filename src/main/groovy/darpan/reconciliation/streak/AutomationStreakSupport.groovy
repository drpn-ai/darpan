package darpan.reconciliation.streak

import darpan.common.DarpanEntityConstants
import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.automation.AutomationRuntimeSupport
import darpan.reconciliation.flowchart.RunFlowchartSupport
import darpan.reconciliation.notification.StreakCelebrationSupport
import darpan.reconciliation.notification.StreakMilestoneVoice
import darpan.reconciliation.notification.TenantNotificationSupport
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneId

/**
 * DAR-BE-107 / DAR-BE-108: the Moqui side of the stored streak. Loads an automation's finished slots newer than its
 * cursor, folds them with AutomationStreakFold, records the badges and side quests they earn (StreakAwards), and answers
 * what a run's message should say about them.
 *
 * <p>The fold runs when an execution row reaches a terminal status (AutomationRuntimeSupport.updateAutomationExecution,
 * which every run path writes through, and StuckRunReaper), so it has finished before that run's message is built. An
 * automation with no stored row yet folds its whole history the first time: that is the backfill. History earns its
 * badges and quests silently; only a slot folded as it finishes can carry one on its message.</p>
 */
class AutomationStreakSupport {

    static final String STREAK = "darpan.reconciliation.AutomationStreak"
    static final String BADGE = "darpan.reconciliation.AutomationStreakBadge"
    static final String QUEST = "darpan.reconciliation.TenantStreakQuest"
    static final String AUTOMATION = "darpan.reconciliation.ReconciliationAutomation"
    static final String EXECUTION = DarpanEntityConstants.RECONCILIATION_AUTOMATION_EXECUTION
    static final String RUN_RESULT = DarpanEntityConstants.RECONCILIATION_RUN_RESULT

    private static final Set<String> UNFINISHED = ["AUT_STAT_PENDING", "AUT_STAT_RUNNING"] as Set<String>
    // Decision 7 (2026-10-22): a duplicate fire of a slot already counted neither adds nor breaks.
    private static final String SKIPPED_DUPLICATE = "AUT_STAT_SKIP_DUP"

    private static final Logger logger = LoggerFactory.getLogger(AutomationStreakSupport.class)

    static boolean isTerminal(Object statusEnumId) {
        String status = statusEnumId?.toString()?.trim()
        return status && !UNFINISHED.contains(status)
    }

    /** Folds the automation's finished slots. Best-effort: a streak must never cost a run or its message. */
    static void recordSlots(def ec, Object automationId) {
        String id = automationId?.toString()?.trim()
        if (!id) return
        Map questPost = null
        try {
            questPost = (Map) AutomationRuntimeSupport.runInTransaction(ec, "Error folding the automation streak") { ->
                return foldAndStore(ec, id)
            }
        } catch (Throwable t) {
            logger.warn("Streak fold failed for automation {}: {}", id, t.message)
        }
        if (questPost) postTenantQuest(ec, questPost)
    }

    /** Pausing carries the streak (Aditi, 2026-10-23): drop the gap anchor so the paused days are not misses. */
    static void pause(def ec, Object automationId) {
        String id = automationId?.toString()?.trim()
        if (!id) return
        try {
            def row = TenantScopedFinder.findGlobalUnscoped(ec, STREAK,
                    "streak pause keyed by automationId — the caller already proved the automation is the tenant's")
                    .condition("automationId", id).useCache(false).one()
            if (row == null) return
            Map state = AutomationStreakFold.pause(AutomationStreakFold.stateFromRow(row))
            row.set("lastScheduledSlotDate", AutomationStreakFold.rowFromState(state).lastScheduledSlotDate)
            row.update()
        } catch (Throwable t) {
            logger.warn("Streak pause failed for automation {}: {}", id, t.message)
        }
    }

    /** delete#Automation: the streak and its badges go with the automation; a quest it found stays the tenant's. */
    static void deleteFor(def ec, Object automationId) {
        String id = automationId?.toString()?.trim()
        if (!id) return
        [BADGE, STREAK].each { String entity ->
            TenantScopedFinder.findGlobalUnscoped(ec, entity,
                    "delete#Automation already proved the automation is the active tenant's; its streak rows go with it")
                    .condition("automationId", id).useCache(false).deleteAll()
        }
    }

    /**
     * What the message of the run behind {@code resultId} says about the streak: the stored day count, and the badge
     * or side quest its slot earned (lines + the GIF to show). Empty for a run no automation scheduled.
     */
    static Map<String, Object> notificationFacts(def ec, Object tenantId, Object resultId) {
        String tenant = tenantId?.toString()?.trim()
        String result = resultId?.toString()?.trim()
        if (!tenant || !result) return [:]
        try {
            List executions = TenantScopedFinder.findGlobalUnscoped(ec, EXECUTION,
                    "streak facts for a run message — explicit companyUserGroupId condition applied")
                    .condition("reconciliationRunResultId", result).condition("companyUserGroupId", tenant)
                    .limit(1).useCache(false).list() ?: []
            if (!executions) return [:]
            String automationId = executions[0].automationId
            Timestamp slotDate = executions[0].scheduledDate as Timestamp
            def automation = loadAutomation(ec, automationId, tenant)
            def streakRow = loadStreakRow(ec, automationId, tenant)
            if (automation == null || streakRow == null) return [:]

            Map state = AutomationStreakFold.stateFromRow(streakRow)
            int days = AutomationStreakFold.currentDays(state, Instant.now(), zoneOf(automation), nextFireAfter(automation))
            Map<String, Object> facts = [streakDays: days]
            if (slotDate == null) return facts

            def badge = (loadBadgeRows(ec, automationId, tenant)
                    .findAll { (it.lastEarnedSlotDate as Timestamp) == slotDate }
                    .max { ladderDays(it.badgeKey as String) })
            if (badge != null) {
                int times = toInt(badge.timesEarned)
                facts.badgeLine = StreakMilestoneVoice.badgeLine(badge.badgeKey as String, times)
                // The GIF is for the first earn only (DAR-BE-108).
                if (times == 1) facts.badgeGifKey = badge.badgeKey
            }
            def quest = loadQuestRows(ec, tenant).find {
                it.automationId == automationId && (it.earnedSlotDate as Timestamp) == slotDate
            }
            if (quest != null) {
                facts.questLine = StreakMilestoneVoice.questLine(quest.questKey as String, slotRecords(ec, automationId, tenant, slotDate))
                facts.questGifKey = quest.questKey
            }
            // DAR-BE-138: one astronomy fact per celebrating message, for the quest when there is one.
            String award = (quest != null ? quest.questKey : (facts.badgeLine ? badge.badgeKey : null)) as String
            if (award) facts.factLine = StreakMilestoneVoice.factLine(StreakFactSupport.factFor(ec, tenant, award, automationId, slotDate))
            return facts
        } catch (Throwable t) {
            logger.warn("Streak facts failed for result {}: {}", result, t.message)
            return [:]
        }
    }

    /** The celebration a clean run's message carries: a quest's GIF over a badge's; null for neither. */
    static Map<String, Object> celebrationFor(def ec, Map facts) {
        String key = (facts?.questGifKey ?: facts?.badgeGifKey) as String
        Map<String, Object> celebration = StreakCelebrationSupport.celebration(TenantNotificationSupport.resolveAppBaseUrl(ec), key)
        if (celebration == null) return null
        // Badge GIFs are capped at one per space per day; quests are once per tenant and are not.
        return celebration + [kind: facts.questGifKey ? StreakCelebrationSupport.KIND_QUEST : StreakCelebrationSupport.KIND_BADGE]
    }

    /**
     * DAR-UI-059's read: every automation of the active tenant with its live and best streak and its badges, plus the
     * tenant's side quests. Folds unstored slots in memory so it is current before an automation's next run; it never
     * writes.
     */
    static Map<String, Object> tenantStreaks(def ec) {
        String tenant = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (!tenant) return [automations: [], quests: [], tenantStreakDays: 0]
        Instant now = Instant.now()
        List automations = TenantScopedFinder.findGlobalUnscoped(ec, AUTOMATION,
                "streak board for the active tenant — explicit companyUserGroupId condition applied")
                .condition("companyUserGroupId", tenant).orderBy("automationName").useCache(false).list() ?: []
        List badgeRows = TenantScopedFinder.findGlobalUnscoped(ec, BADGE,
                "streak board badges — explicit companyUserGroupId condition applied")
                .condition("companyUserGroupId", tenant).useCache(false).list() ?: []
        List<Map<String, Object>> rows = []
        automations.findAll { it.isArchived != "Y" }.each { def automation ->
            String automationId = automation.automationId
            Map state = foldFor(ec, automation, tenant, loadStreakRow(ec, automationId, tenant), null).state as Map
            ZoneId zone = zoneOf(automation)
            Closure<Instant> next = nextFireAfter(automation)
            rows << [automationId    : automationId,
                     automationName  : automation.automationName,
                     isActive        : automation.isActive != "N",
                     scheduled       : (automation.scheduleExpr?.toString()?.trim()) ? true : false,
                     windowTimeZone  : zone.id,
                     currentDays     : AutomationStreakFold.currentDays(state, now, zone, next),
                     bestDays        : AutomationStreakFold.bestDays(state, now, zone, next),
                     streakStartDate : toTimestamp(state.streakStartDate),
                     lastBreakDate   : toTimestamp(state.lastBreakDate),
                     lastBreakReason : state.lastBreakReason,
                     badges          : badgeRows.findAll { it.automationId == automationId }.collect { def badge ->
                         Map ladder = StreakMilestoneVoice.badge(badge.badgeKey as String)
                         [badgeKey: badge.badgeKey, title: ladder?.title, days: ladder?.days,
                          timesEarned: toInt(badge.timesEarned), firstEarnedDate: badge.firstEarnedDate,
                          lastEarnedDate: badge.lastEarnedDate]
                     }.sort { it.days }]
        }
        List live = rows.findAll { it.isActive && it.scheduled }
        List quests = loadQuestRows(ec, tenant).collect { def quest ->
            [questKey: quest.questKey, title: StreakCelebrationSupport.CATALOG.get(quest.questKey)?.title,
             automationId: quest.automationId, earnedDate: quest.earnedDate]
        }
        return [automations: rows, quests: quests,
                tenantStreakDays: live ? live.collect { it.currentDays as int }.min() : 0]
    }

    // ---- the fold ---------------------------------------------------------------------------------------------------

    /** Folds and stores one automation. Returns a Black Friday post to make after the transaction, or null. */
    private static Map foldAndStore(def ec, String automationId) {
        // Locked first, so two windows finishing together cannot fold the same slots twice.
        def streakRow = TenantScopedFinder.findGlobalUnscoped(ec, STREAK,
                "streak fold keyed by automationId — tenant re-checked against the automation below")
                .condition("automationId", automationId).forUpdate(true).useCache(false).one()
        def automation = TenantScopedFinder.findGlobalUnscoped(ec, AUTOMATION,
                "streak fold on the system run path — the automation's own tenant pins every read and write")
                .condition("automationId", automationId).useCache(false).one()
        if (automation == null) return null
        String tenant = automation.companyUserGroupId
        if (streakRow != null && streakRow.companyUserGroupId != tenant) return null

        Map<String, Map> badges = loadBadgeRows(ec, automationId, tenant).collectEntries { [(it.badgeKey): it] }
        Set<String> quests = loadQuestRows(ec, tenant).collect { it.questKey as String } as Set<String>
        Map folded = foldFor(ec, automation, tenant, streakRow, { Map slot, Map state ->
            Instant at = (slot.completedDate ?: slot.scheduledDate) as Instant
            Timestamp slotTs = toTimestamp(slot.scheduledDate)
            Timestamp atTs = toTimestamp(at)
            // On-schedule clean slots grow the streak; an off-schedule one (Run now) does not move the anchor.
            boolean grew = state.streakStartDate != null && state.lastScheduledSlotDate == slot.scheduledDate &&
                    AutomationStreakFold.classifySlot(slot).outcome == AutomationStreakFold.CLEAN
            if (grew) {
                Instant start = state.streakStartDate as Instant
                int days = StreakAwards.daysAt(start, at, zoneOf(automation))
                StreakAwards.badgesEarned(days, start, badges.collectEntries { k, v ->
                    [(k): [timesEarned: v.timesEarned, lastEarnedStreakStartDate: (v.lastEarnedStreakStartDate as Timestamp)?.toInstant()]]
                }).each { Map earned ->
                    def row = badges.get(earned.key)
                    if (row == null) {
                        row = ec.entity.makeValue(BADGE)
                        row.set("automationId", automationId)
                        row.set("badgeKey", earned.key)
                        row.set("companyUserGroupId", tenant)
                        row.set("firstEarnedDate", atTs)
                    }
                    row.set("timesEarned", (Long) earned.timesEarned)
                    row.set("lastEarnedDate", atTs)
                    row.set("lastEarnedSlotDate", slotTs)
                    row.set("lastEarnedStreakStartDate", toTimestamp(start))
                    if (badges.containsKey(earned.key)) row.update() else row.create()
                    badges.put(earned.key as String, row)
                }
            }
            if (!quests.contains(StreakAwards.BIG_HAUL) && StreakAwards.isBigHaul(slot)) {
                createQuest(ec, tenant, StreakAwards.BIG_HAUL, automationId, atTs, slotTs)
                quests << StreakAwards.BIG_HAUL
            }
        })
        Map state = folded.state as Map
        Map<String, Object> values = AutomationStreakFold.rowFromState(state)
        if (streakRow == null) {
            streakRow = ec.entity.makeValue(STREAK)
            streakRow.set("automationId", automationId)
            streakRow.set("companyUserGroupId", tenant)
            values.each { k, v -> streakRow.set(k, v) }
            streakRow.create()
        } else if (folded.foldedCount) {
            values.each { k, v -> streakRow.set(k, v) }
            streakRow.update()
        }

        Instant reached = state.lastSlotDate as Instant
        Integer year = folded.foldedCount && reached ? StreakAwards.blackFridayYearToCheck(reached.atZone(zoneOf(automation)).toLocalDate()) : null
        if (year == null || quests.contains(StreakAwards.BLACK_FRIDAY_SURVIVOR)) return null
        if (!allSurvivedBlackFriday(ec, tenant, automationId, state, year)) return null
        def quest = createQuest(ec, tenant, StreakAwards.BLACK_FRIDAY_SURVIVOR, null, toTimestamp(Instant.now()), null)
        return [tenant: tenant, quest: quest]
    }

    /**
     * Folds the slots after the stored cursor, one at a time so {@code onSlot} sees the state each slot left. Returns
     * [state, foldedCount]. A pending slot ends the fold: it and everything after it wait for the next call.
     */
    private static Map foldFor(def ec, def automation, String tenant, def streakRow, Closure onSlot) {
        Map state = AutomationStreakFold.stateFromRow(streakRow)
        String scheduleKey = "${automation.scheduleExpr ?: ''}|${automation.windowTimeZone ?: ''}".toString()
        ZoneId zone = zoneOf(automation)
        Closure<Instant> next = nextFireAfter(automation)
        int folded = 0
        for (Map slot : loadSlots(ec, automation, tenant, state.lastSlotDate as Instant)) {
            Map after = AutomationStreakFold.fold(state, [slot], next, scheduleKey, zone)
            if (after.lastSlotDate == state.lastSlotDate) break
            state = after
            folded++
            if (onSlot != null) onSlot.call(slot, state)
        }
        return [state: state, foldedCount: folded]
    }

    /** Execution rows after the cursor, grouped into slots by scheduledDate, oldest first. */
    private static List<Map> loadSlots(def ec, def automation, String tenant, Instant cursor) {
        def find = TenantScopedFinder.findGlobalUnscoped(ec, EXECUTION,
                "streak fold reads one automation's executions — explicit companyUserGroupId condition applied")
                .condition("automationId", automation.automationId)
                .condition("companyUserGroupId", tenant)
        if (cursor != null) find.condition("scheduledDate", "greater", toTimestamp(cursor))
        List rows = find.orderBy("scheduledDate").useCache(false).list() ?: []
        boolean flowchart = automation.savedRunType?.toString()?.trim()?.toLowerCase() == RunFlowchartSupport.SAVED_RUN_TYPE
        Map<Instant, Map> slots = new LinkedHashMap<>()
        rows.each { def row ->
            Timestamp scheduled = row.scheduledDate as Timestamp
            if (scheduled == null || row.statusEnumId == SKIPPED_DUPLICATE) return
            Map slot = slots.computeIfAbsent(scheduled.toInstant()) { Instant at -> [scheduledDate: at, executions: []] }
            Timestamp completed = row.completedDate as Timestamp
            if (completed != null && (slot.completedDate == null || completed.toInstant().isAfter(slot.completedDate as Instant))) {
                slot.completedDate = completed.toInstant()
            }
            Object differences = row.differenceCount
            // Decision d (2026-10-23): a flowchart automation's differences are its questions' "no" counts.
            if (differences == null && flowchart && row.statusEnumId == "AUT_STAT_SUCCESS") {
                differences = flowchartNoCount(ec, tenant, row.reconciliationRunResultId as String)
            }
            (slot.executions as List) << [statusEnumId     : row.statusEnumId, differenceCount: differences,
                                          file1RecordCount : row.file1RecordCount, file2RecordCount: row.file2RecordCount]
        }
        return slots.values() as List<Map>
    }

    /** The sum of noCount over every question row of the flowchart walk that wrote {@code firstRunResultId}. */
    private static Long flowchartNoCount(def ec, String tenant, String firstRunResultId) {
        if (!firstRunResultId) return null
        def first = TenantScopedFinder.findOneOwnedByTenant(ec, RUN_RESULT, "reconciliationRunResultId", firstRunResultId,
                tenant, "flowchart streak count — owner re-checked against the automation tenant")
        String walk = first?.reconciliationExecutionId
        if (!walk) return null
        List questions = TenantScopedFinder.findGlobalUnscoped(ec, RUN_RESULT,
                "flowchart streak count — explicit companyUserGroupId condition applied")
                .condition("reconciliationExecutionId", walk).condition("companyUserGroupId", tenant)
                .useCache(false).list() ?: []
        return questions.sum(0L) { toInt(it.noCount) as long } as Long
    }

    private static boolean allSurvivedBlackFriday(def ec, String tenant, String foldedId, Map foldedState, int year) {
        List automations = (TenantScopedFinder.findGlobalUnscoped(ec, AUTOMATION,
                "Black Friday quest — explicit companyUserGroupId condition applied")
                .condition("companyUserGroupId", tenant).useCache(false).list() ?: [])
                .findAll { it.isActive != "N" && it.isArchived != "Y" && it.scheduleExpr?.toString()?.trim() }
        if (!automations) return false
        return automations.every { def automation ->
            Map state = automation.automationId == foldedId ? foldedState :
                    AutomationStreakFold.stateFromRow(loadStreakRow(ec, automation.automationId as String, tenant))
            StreakAwards.survivedBlackFriday(state, zoneOf(automation), year)
        }
    }

    private static def createQuest(def ec, String tenant, String questKey, String automationId, Timestamp at, Timestamp slot) {
        def quest = ec.entity.makeValue(QUEST)
        quest.set("companyUserGroupId", tenant)
        quest.set("questKey", questKey)
        quest.set("automationId", automationId)
        quest.set("earnedDate", at)
        quest.set("earnedSlotDate", slot)
        quest.create()
        return quest
    }

    /** An all-automations quest posts on its own, to the tenant's streaks space (DAR-BE-108). */
    private static void postTenantQuest(def ec, Map post) {
        String tenant = post.tenant
        def quest = post.quest
        try {
            def setting = TenantScopedFinder.findGlobalUnscoped(ec, DarpanEntityConstants.TENANT_SETTING,
                    "streaks space for a quest post — explicit companyUserGroupId condition applied")
                    .condition("companyUserGroupId", tenant).useCache(false).one()
            String spaceId = setting?.streaksChatSpaceId?.toString()?.trim()
            if (!spaceId) return
            String label = TenantAccessSupport.resolveTenantLabelForUserGroupId(ec, tenant)
            List<String> lines = [StreakMilestoneVoice.questLine(quest.questKey as String, 0L),
                                  StreakMilestoneVoice.factLine(StreakFactSupport.factFor(ec, tenant, quest.questKey as String, null, null))]
            if (label) lines << "Tenant: ${label}".toString()
            Map<String, Object> payload = [text: lines.findAll { it }.join("\n")]
            Map<String, Object> celebration = StreakCelebrationSupport.celebration(TenantNotificationSupport.resolveAppBaseUrl(ec), quest.questKey as String)
            if (celebration) payload.put(StreakCelebrationSupport.CELEBRATION_KEY, celebration)
            Map delivery = TenantNotificationSupport.postToChatSpace(ec, tenant, spaceId, payload)
            if (delivery?.ok == true) {
                AutomationRuntimeSupport.runInTransaction(ec, "Error stamping the quest post") { ->
                    def stored = TenantScopedFinder.findGlobalUnscoped(ec, QUEST, "quest post stamp — keyed by its tenant")
                            .condition("companyUserGroupId", tenant).condition("questKey", quest.questKey).useCache(false).one()
                    stored?.set("notifiedDate", new Timestamp(System.currentTimeMillis()))
                    stored?.update()
                    return null
                }
            }
        } catch (Throwable t) {
            logger.warn("Quest post failed for tenant {} quest {}: {}", tenant, quest?.questKey, t.message)
        }
    }

    // ---- reads ------------------------------------------------------------------------------------------------------

    private static def loadAutomation(def ec, String automationId, String tenant) {
        return TenantScopedFinder.findOneOwnedByTenant(ec, AUTOMATION, "automationId", automationId, tenant,
                "streak read — owner re-checked against the run tenant")
    }

    private static def loadStreakRow(def ec, String automationId, String tenant) {
        return TenantScopedFinder.findOneOwnedByTenant(ec, STREAK, "automationId", automationId, tenant,
                "streak read — owner re-checked against the automation tenant")
    }

    private static List loadBadgeRows(def ec, String automationId, String tenant) {
        return TenantScopedFinder.findGlobalUnscoped(ec, BADGE,
                "automation badges — explicit companyUserGroupId condition applied")
                .condition("automationId", automationId).condition("companyUserGroupId", tenant)
                .useCache(false).list() ?: []
    }

    private static List loadQuestRows(def ec, String tenant) {
        return TenantScopedFinder.findGlobalUnscoped(ec, QUEST,
                "tenant side quests — explicit companyUserGroupId condition applied")
                .condition("companyUserGroupId", tenant).useCache(false).list() ?: []
    }

    private static long slotRecords(def ec, String automationId, String tenant, Timestamp slotDate) {
        List rows = TenantScopedFinder.findGlobalUnscoped(ec, EXECUTION,
                "Big Haul record count — explicit companyUserGroupId condition applied")
                .condition("automationId", automationId).condition("companyUserGroupId", tenant)
                .condition("scheduledDate", slotDate).useCache(false).list() ?: []
        return StreakAwards.slotRecords([executions: rows.collect { [file1RecordCount: it.file1RecordCount, file2RecordCount: it.file2RecordCount] }])
    }

    // ---- schedule ---------------------------------------------------------------------------------------------------

    /** The same resolver the scanner fires with: the first fire strictly after an instant, or null. */
    static Closure<Instant> nextFireAfter(def automation) {
        return { Instant after ->
            Timestamp base = toTimestamp(after)
            return AutomationExecutionSupport.resolveNextScheduledFireTime(automation, base, base)?.toInstant()
        }
    }

    static ZoneId zoneOf(def automation) {
        String zone = automation?.windowTimeZone?.toString()?.trim()
        try {
            return zone ? ZoneId.of(zone) : ZoneId.of("UTC")
        } catch (Throwable ignored) {
            return ZoneId.of("UTC")
        }
    }

    private static int ladderDays(String badgeKey) {
        return (StreakMilestoneVoice.badge(badgeKey)?.days ?: 0) as int
    }

    private static Timestamp toTimestamp(Object value) {
        if (value == null) return null
        if (value instanceof Timestamp) return (Timestamp) value
        return new Timestamp(((Instant) value).toEpochMilli())
    }

    private static int toInt(Object value) {
        if (value instanceof Number) return ((Number) value).intValue()
        String text = value?.toString()?.trim()
        return (text && text.isInteger()) ? text.toInteger() : 0
    }
}
