package darpan.pulse

import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.peak.PeakPeriodLoader
import darpan.peak.PeakWindow
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.core.ReconciliationServices
import darpan.facade.reconciliation.RunConclusionStep

import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.normalizeInt

/**
 * DAR-BE-328 plan 3. What SyncPulseFacadeServices checks and shows: the error sentences the UI prints verbatim, the
 * daily-check picker, the pair list and the pair page. Validation is pure; the ec helpers read one tenant's rows with an
 * explicit companyUserGroupId condition (TenantScopedFinder), never the active-tenant filter.
 */
class SyncPulseFacadeSupport {
    private static final String WHY = "sync pulse facade reads the active tenant's pairs and automations: explicit companyUserGroupId condition applied"
    static final Set<Integer> CADENCES = [60, 120, 180, 240, 360] as Set<Integer>
    static final int MIN_ALLOWANCE = 15
    static final int MAX_ALLOWANCE = 2880

    static final String E_DAILY = "Choose a daily check."
    static final String E_DUPLICATE = "That run already has a sync pair."
    static final String E_HOTWAX = "A pulse needs HotWax as the upstream side."
    static final String E_ALLOWANCE = "Allowance must be 15 minutes to 48 hours."
    static final String E_CADENCE = "Pulse must be every 1, 2, 3, 4 or 6 hours, or off."
    static final String E_KEY = "A pulse needs a check that pairs orders by one plain key field on both sides."
    static final String E_STATUS = "A pulse needs a check that reads a day, not one that reads by status."
    static final String E_CHAT = "Choose a chat space of this tenant."

    /** A check that reads one day: previous day, or last N days with N = 1 (a blank window is last 1 day). */
    static boolean isDailyWindow(Map automation) {
        String type = normalize(automation?.get("relativeWindowTypeEnumId"))
        if (type == AutomationExecutionSupport.WINDOW_PREVIOUS_DAY) return true
        if (type != null && type != AutomationExecutionSupport.WINDOW_LAST_DAYS) return false
        return (darpan.common.ValueSupport.normalizeInt(automation?.get("relativeWindowCount")) ?: 1) == 1
    }

    /**
     * Error sentences for a pair about to be saved; empty when valid.
     * @param p allowanceMinutes, pulseEveryMinutes, upstreamSide (the caller's input)
     * @param automation the tenant's active, unarchived automation named by the caller, or null when there is none
     * @param upstreamSystemEnumId systemEnumId of the automation's source on the chosen side
     * @param duplicate another pair of the tenant already watches this automation
     * @param chatSpaceOk the chat space is blank or belongs to the tenant
     * @param pulseBlock why the check cannot be pulsed (pulseBlockFor), or null; refused only when a cadence is set
     */
    static List<String> validate(Map p, Map automation, String upstreamSystemEnumId, boolean duplicate, boolean chatSpaceOk,
                                 String pulseBlock = null) {
        List<String> errors = []
        String side = normalize(p?.get("upstreamSide"))
        if (automation == null || !isDailyWindow(automation) || !(side in ["FILE_1", "FILE_2"])) {
            errors << E_DAILY
        } else if (duplicate) {
            errors << E_DUPLICATE
        }
        Integer allowance = p?.get("allowanceMinutes") as Integer
        if (allowance == null || allowance < MIN_ALLOWANCE || allowance > MAX_ALLOWANCE) errors << E_ALLOWANCE
        Integer cadence = p?.get("pulseEveryMinutes") as Integer
        if (cadence != null && !CADENCES.contains(cadence)) {
            errors << E_CADENCE
        } else if (cadence != null && automation != null && upstreamSystemEnumId != SyncPulseSupport.HOTWAX_ORDERS) {
            errors << E_HOTWAX
        } else if (cadence != null && automation != null && pulseBlock) {
            errors << pulseBlock
        }
        if (!chatSpaceOk) errors << E_CHAT
        return errors
    }

    /**
     * Why a pulse cannot read this check, as the sentence to show; null when it can. The reader pairs by the first key field of
     * each side and throws every pulse on a check that reads by status, a key with an id normalizer, or a composite key. Same
     * rule as SyncPulseReader.sides + AuditRecheckSupport.firstKeyField, from the sides' key specs (RunConclusionStep.keySpec).
     */
    static String pulseBlockFor(Map automation, Map upstreamKey, Map targetKey) {
        if (AutomationExecutionSupport.isStateWindowMode(automation)) return E_STATUS
        boolean plain = [upstreamKey, targetKey].every { Map k ->
            k != null && k.keyReliable && !k.idNormalizer && ((k.keyFields ?: []) as List).size() == 1
        }
        return plain ? null : E_KEY
    }

    /** Reads for save#SyncPair, then validate(). existingPairId: the pair being edited (not its own duplicate). */
    static List<String> validateSave(def ec, String tenantId, Map p, String existingPairId) {
        String automationId = normalize(p.get("automationId"))
        String side = normalize(p.get("upstreamSide"))
        Map automation = automationId ? automationOf(ec, tenantId, automationId) : null
        if (automation != null && (normalize(automation.get("isActive")) == "N" || normalize(automation.get("isArchived")) == "Y")) automation = null
        String system = (automation != null && side) ? systemOf(ec, tenantId, automationId, side) : null
        boolean duplicate = automation != null && (rows(ec, "darpan.pulse.SyncPair", tenantId).condition("automationId", automationId).list() ?: [])
                .any { normalize(it.get("syncPairId")) != existingPairId }
        String chatSpaceId = normalize(p.get("chatSpaceId"))
        boolean chatOk = !chatSpaceId || TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.reconciliation.TenantChatSpace",
                "chatSpaceId", chatSpaceId, tenantId, WHY) != null
        String block = null
        if (automation != null && normalizeInt(p.get("pulseEveryMinutes")) != null) {
            String scopeId = normalize(automation.get("compareScopeId"))
            block = pulseBlockFor(automation, RunConclusionStep.keySpecFor(ec, scopeId, "FILE_1"), RunConclusionStep.keySpecFor(ec, scopeId, "FILE_2"))
        }
        return validate(p, automation, system, duplicate, chatOk, block)
    }

    // ------------------------------------------------------------------------------------------- reads

    static def rows(def ec, String entityName, String tenantId) {
        return TenantScopedFinder.findGlobalUnscopedPerRequest(ec, entityName, WHY).condition("companyUserGroupId", tenantId).useCache(false)
    }

    /** One of the tenant's automations as a map; null when absent or another tenant's. */
    static Map automationOf(def ec, String tenantId, String automationId) {
        def row = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.reconciliation.ReconciliationAutomation", "automationId",
                automationId, tenantId, WHY)
        return row == null ? null : new LinkedHashMap(row.getMap())
    }

    static String systemOf(def ec, String tenantId, String automationId, String side) {
        def source = rows(ec, "darpan.reconciliation.ReconciliationAutomationSource", tenantId)
                .condition("automationId", automationId).condition("fileSide", side).list()?.find()
        return normalize(source?.get("systemEnumId"))
    }

    /** The tenant's daily-window automations (active, not archived) with the sides a pair can start on. */
    static List<Map> automationOptions(def ec, String tenantId) {
        Map<String, List> sourcesByAutomation = [:]
        (rows(ec, "darpan.reconciliation.ReconciliationAutomationSource", tenantId).orderBy("fileSide").list() ?: []).each { s ->
            sourcesByAutomation.computeIfAbsent(normalize(s.get("automationId"))) { [] } << s
        }
        List<Map> out = []
        (rows(ec, "darpan.reconciliation.ReconciliationAutomation", tenantId).orderBy("automationName").list() ?: []).each { row ->
            Map a = row.getMap()
            if (normalize(a.get("isActive")) == "N" || normalize(a.get("isArchived")) == "Y" || !isDailyWindow(a)) return
            String id = normalize(a.get("automationId"))
            List<Map> options = (sourcesByAutomation[id] ?: []).collect { s ->
                String side = normalize(s.get("fileSide"))
                String system = normalize(s.get("systemEnumId"))
                [upstreamSide: side, label: system ? ReconciliationServices.resolveEnumLabel(ec, system, system) : side] as Map
            }.findAll { it.upstreamSide in ["FILE_1", "FILE_2"] }
            out << [automationId: id, label: normalize(a.get("automationName")) ?: id, upstreamOptions: options]
        }
        return out
    }

    static List<Map> chatSpaceOptions(def ec, String tenantId) {
        return (rows(ec, "darpan.reconciliation.TenantChatSpace", tenantId).orderBy("spaceName").list() ?: []).collect { r ->
            [chatSpaceId: normalize(r.get("chatSpaceId")), label: normalize(r.get("spaceName")) ?: normalize(r.get("chatSpaceId"))] as Map
        }
    }

    private static String iso(Object stamp) {
        if (stamp == null) return null
        return stamp instanceof Timestamp ? ((Timestamp) stamp).toInstant().toString() : stamp.toString()
    }

    /** list#SyncPairs rows, ordered by label. */
    static List<Map> listRows(def ec, String tenantId) {
        Map<String, Map> automations = [:]
        (rows(ec, "darpan.reconciliation.ReconciliationAutomation", tenantId).list() ?: []).each {
            automations[normalize(it.get("automationId"))] = it.getMap()
        }
        List<Map> out = (rows(ec, "darpan.pulse.SyncPair", tenantId).list() ?: []).collect { row ->
            Map pair = row.getMap()
            String id = normalize(pair.get("syncPairId"))
            Map automation = automations[normalize(pair.get("automationId"))]
            Map counts = SyncPulseSupport.pairCounts(ec, tenantId, id)
            [syncPairId: id, label: SyncPulseSupport.pairLabel(ec, pair), automationId: normalize(pair.get("automationId")),
             automationName: normalize(automation?.get("automationName")), allowanceMinutes: pair.get("allowanceMinutes"),
             pulseEveryMinutes: pair.get("pulseEveryMinutes"), isActive: normalize(pair.get("isActive")) ?: "Y",
             lastPulseStamp: iso(pair.get("lastPulseStamp")), waitingCount: counts.waitingCount,
             pastAllowanceCount: counts.pastAllowanceCount] as Map
        }
        return out.sort { Map r -> r.label as String }
    }

    /** get#SyncPair pieces for one of the tenant's pairs (the row map): syncPair, samplesToday, pastOrders. */
    static Map detail(def ec, String tenantId, Map pair, Instant now = Instant.now()) {
        String id = normalize(pair.get("syncPairId"))
        ZoneId zone = TenantAccessSupport.tenantTimeZoneFor(ec, tenantId)
        LocalDate today = now.atZone(zone).toLocalDate()
        List<PeakWindow> windows = PeakPeriodLoader.load(ec, tenantId, today)
        Map<String, Map> periodRows = (rows(ec, "darpan.pulse.PeakPeriodSync", tenantId).condition("syncPairId", id).list() ?: [])
                .collectEntries { [(normalize(it.get("peakPeriodId"))): it.getMap()] }
        Map eff = SyncPulseSupport.effectiveValues(pair, windows, periodRows, today)
        Map periodRow = eff.peakPeriodId ? periodRows[eff.peakPeriodId as String] : null
        boolean overrides = periodRow != null && (periodRow.get("allowanceMinutes") != null || periodRow.get("pulseEveryMinutes") != null)
        def periodWindow = overrides ? windows.find { it.peakPeriodId == eff.peakPeriodId } : null
        String periodName = periodWindow?.periodName
        String periodThruDate = periodWindow?.thruDate?.toString()
        Map automation = automationOf(ec, tenantId, normalize(pair.get("automationId")))

        Map counts = SyncPulseSupport.pairCounts(ec, tenantId, id)
        Map syncPair = [syncPairId: id, waitingCount: counts.waitingCount, pastAllowanceCount: counts.pastAllowanceCount, label: SyncPulseSupport.pairLabel(ec, pair), automationId: normalize(pair.get("automationId")),
                        automationName: normalize(automation?.get("automationName")), upstreamSide: normalize(pair.get("upstreamSide")),
                        allowanceMinutes: eff.allowanceMinutes, pulseEveryMinutes: eff.pulseEveryMinutes,
                        normalAllowanceMinutes: pair.get("allowanceMinutes"), normalPulseEveryMinutes: pair.get("pulseEveryMinutes"),
                        periodName: periodName, periodThruDate: periodThruDate, chatSpaceId: normalize(pair.get("chatSpaceId")),
                        hourlySummary: normalize(pair.get("hourlySummary")) ?: "N", isActive: normalize(pair.get("isActive")) ?: "Y",
                        lastPulseStamp: iso(pair.get("lastPulseStamp")), lastPulseStatusEnumId: normalize(pair.get("lastPulseStatusEnumId"))]

        Timestamp dayStart = Timestamp.from(today.atStartOfDay(zone).toInstant())
        Timestamp dayEnd = Timestamp.from(today.plusDays(1).atStartOfDay(zone).toInstant())
        List<Map> samples = (rows(ec, "darpan.pulse.SyncPulseSample", tenantId).condition("syncPairId", id)
                .condition("sampleStamp", org.moqui.entity.EntityCondition.GREATER_THAN_EQUAL_TO, dayStart)
                .condition("sampleStamp", org.moqui.entity.EntityCondition.LESS_THAN, dayEnd).orderBy("sampleStamp").list() ?: []).collect { s ->
            [sampleStamp: iso(s.get("sampleStamp")), waitingCount: s.get("waitingCount"), oldestWaitingMinutes: s.get("oldestWaitingMinutes"),
             pastAllowanceCount: s.get("pastAllowanceCount"), statusEnumId: s.get("statusEnumId")] as Map
        }
        List<Map> openOrders = (rows(ec, "darpan.pulse.SyncPulseOrder", tenantId).condition("syncPairId", id).condition("closedStamp", null)
                .orderBy("sourceCreatedStamp").list() ?: [])
        Instant oldestCreated = openOrders.collect { instantOf(it.get("sourceCreatedStamp")) }.findAll { it != null }.min()
        // no open orders: no run is looked up; an order with no created stamp is never read by a window, so bound by the oldest flag then
        Instant oldestBound = oldestCreated ?: openOrders.collect { instantOf(it.get("firstPastStamp")) }.findAll { it != null }.min()
        List<Map> runs = openOrders.isEmpty() ? [] : pairRuns(ec, tenantId, normalize(pair.get("automationId")), oldestBound)
        List<Map> past = openOrders.collect { o ->
            Object created = o.get("sourceCreatedStamp") ?: o.get("firstPastStamp")
            String reportedId = reportedInRun(runs, o.get("firstPastStamp"), o.get("sourceCreatedStamp"))
            long minutes = created instanceof Timestamp ? Math.max(0L, Duration.between(((Timestamp) created).toInstant(), now).toMinutes()) : 0L
            [recordKey: o.get("recordKey"), orderName: o.get("orderName"), otherSystemId: o.get("otherSystemId"),
             sourceCreatedStamp: iso(o.get("sourceCreatedStamp")), waitingMinutes: minutes,
             reportedInRunId: reportedId, reportedInSavedRunId: reportedId ? normalize(automation?.get("savedRunId")) : null] as Map
        }
        return [syncPair: syncPair, samplesToday: samples, pastOrders: past]
    }

    // ------------------------------------------------------------------------------------------- run links

    private static Instant instantOf(Object v) {
        return v instanceof Timestamp ? ((Timestamp) v).toInstant() : (v instanceof java.util.Date ? ((java.util.Date) v).toInstant() : null)
    }

    /** A run's start: when it began, else when it was created. */
    private static Instant runStart(Map run) { return instantOf(run?.get("startedDate")) ?: instantOf(run?.get("createdDate")) }

    /**
     * The automation's run results the tenant owns, newest start first: [reconciliationRunResultId, start, windowStart, windowEnd]
     * (Instants; the window is what the run's execution read, null when unrecorded). With oldest, only runs whose window
     * ends after it (or has no recorded end): a run that read nothing from that point on cannot have reported an order created then.
     */
    static List<Map> pairRuns(def ec, String tenantId, String automationId, Instant oldest = null) {
        if (!automationId) return []
        Map<String, Map> windows = [:]
        def execFind = rows(ec, "darpan.reconciliation.ReconciliationAutomationExecution", tenantId).condition("automationId", automationId)
        if (oldest != null) {
            def cf = ec.entity.conditionFactory
            execFind.condition(cf.makeCondition([cf.makeCondition("windowEndDate", org.moqui.entity.EntityCondition.GREATER_THAN, Timestamp.from(oldest)),
                                                 cf.makeCondition("windowEndDate", org.moqui.entity.EntityCondition.EQUALS, null)], org.moqui.entity.EntityCondition.OR))
        }
        (execFind.list() ?: []).each { e ->
            String runId = normalize(e.get("reconciliationRunResultId"))
            if (runId) windows[runId] = [from: instantOf(e.get("windowStartDate")), thru: instantOf(e.get("windowEndDate"))]
        }
        if (windows.isEmpty()) return []
        List<Map> out = (rows(ec, "darpan.reconciliation.ReconciliationRunResult", tenantId)
                .condition("reconciliationRunResultId", org.moqui.entity.EntityCondition.IN, windows.keySet() as List).list() ?: []).collect { r ->
            String id = normalize(r.get("reconciliationRunResultId"))
            [reconciliationRunResultId: id, start: runStart(r.getMap()), windowStart: windows[id]?.from, windowEnd: windows[id]?.thru] as Map
        }.findAll { it.start != null }
        return out.sort { Map a, Map b -> (b.start as Instant) <=> (a.start as Instant) }
    }

    /**
     * The run that reported an order: the newest run (runs newest first) whose read window contains the order's
     * sourceCreatedStamp, whenever it started (an order flagged after its own day's run began is still in that run's day);
     * only when no window holds it, the first run that started after the flag. Null when none.
     */
    static String reportedInRun(List<Map> runs, Object firstPastStamp, Object sourceCreatedStamp = null) {
        Instant flagged = instantOf(firstPastStamp)
        if (flagged == null) return null
        List<Map> after = (runs ?: []).findAll { (it.start as Instant).isAfter(flagged) }
        Instant created = instantOf(sourceCreatedStamp)
        Map hit = created == null ? null : (runs ?: []).find { Instant f = it.windowStart as Instant; Instant t = it.windowEnd as Instant
            f != null && t != null && !created.isBefore(f) && created.isBefore(t) }
        return ((hit ?: (after ? after.last() : null))?.get("reconciliationRunResultId")) as String
    }

    /** Orders the pulse had flagged before the run started and that were still open at its start: recordKey -> ISO firstPastStamp. */
    static Map<String, String> flagsFor(List<Map> orders, Instant runStart) {
        Map<String, String> flags = [:]
        if (runStart == null) return flags
        (orders ?: []).each { o ->
            Instant first = instantOf(o.get("firstPastStamp"))
            Instant closed = instantOf(o.get("closedStamp"))
            String key = normalize(o.get("recordKey"))
            if (key && first != null && first.isBefore(runStart) && (closed == null || closed.isAfter(runStart))) flags[key] = first.toString()
        }
        return flags
    }

    /**
     * get#RunPulseFlags: null when the run is not the tenant's; else [syncPairId, flags] ([null, [:]] when its automation has no pair).
     */
    static Map runFlags(def ec, String tenantId, String runResultId) {
        def run = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.reconciliation.ReconciliationRunResult", "reconciliationRunResultId",
                runResultId, tenantId, WHY)
        if (run == null) return null
        Instant start = runStart(run.getMap())
        String automationId = normalize((rows(ec, "darpan.reconciliation.ReconciliationAutomationExecution", tenantId)
                .condition("reconciliationRunResultId", runResultId).list() ?: []).find()?.get("automationId"))
        def pair = automationId ? (rows(ec, "darpan.pulse.SyncPair", tenantId).condition("automationId", automationId).list() ?: []).find() : null
        if (pair == null) return [syncPairId: null, flags: [:]]
        String pairId = normalize(pair.get("syncPairId"))
        List<Map> orders = (rows(ec, "darpan.pulse.SyncPulseOrder", tenantId).condition("syncPairId", pairId).list() ?: []).collect { it.getMap() }
        return [syncPairId: pairId, flags: flagsFor(orders, start)]
    }
}
