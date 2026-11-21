package darpan.pulse

import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.peak.PeakPeriodLoader
import darpan.peak.PeakWindow
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.core.ReconciliationServices

import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

import static darpan.common.ValueSupport.normalize

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
    static final String E_CHAT = "Choose a chat space of this tenant."

    /** A check that reads one day: previous day, or last N days with N = 1 (a blank window is last 1 day). */
    static boolean isDailyWindow(Map automation) {
        String type = normalize(automation?.get("relativeWindowTypeEnumId"))
        if (type == AutomationExecutionSupport.WINDOW_PREVIOUS_DAY) return true
        if (type != null && type != AutomationExecutionSupport.WINDOW_LAST_DAYS) return false
        Object count = automation?.get("relativeWindowCount")
        return count == null || count.toString().trim().isEmpty() || (count as Integer) == 1
    }

    /**
     * Error sentences for a pair about to be saved; empty when valid.
     * @param p allowanceMinutes, pulseEveryMinutes, upstreamSide (the caller's input)
     * @param automation the tenant's active, unarchived automation named by the caller, or null when there is none
     * @param upstreamSystemEnumId systemEnumId of the automation's source on the chosen side
     * @param duplicate another pair of the tenant already watches this automation
     * @param chatSpaceOk the chat space is blank or belongs to the tenant
     */
    static List<String> validate(Map p, Map automation, String upstreamSystemEnumId, boolean duplicate, boolean chatSpaceOk) {
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
        }
        if (!chatSpaceOk) errors << E_CHAT
        return errors
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
        return validate(p, automation, system, duplicate, chatOk)
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

    private static String pairLabel(def ec, String tenantId, Map pair, Map automation) {
        String name = normalize(automation?.get("automationName")) ?: normalize(pair.get("automationId"))
        String system = systemOf(ec, tenantId, normalize(pair.get("automationId")), normalize(pair.get("upstreamSide")))
        return system ? "${name} · ${ReconciliationServices.resolveEnumLabel(ec, system, system)}".toString() : name
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
            def latestOk = rows(ec, "darpan.pulse.SyncPulseSample", tenantId).condition("syncPairId", id)
                    .condition("statusEnumId", SyncPulseSupport.OK).orderBy("-sampleStamp").limit(1).list()?.find()
            long past = rows(ec, "darpan.pulse.SyncPulseOrder", tenantId).condition("syncPairId", id).condition("closedStamp", null).count()
            [syncPairId: id, label: pairLabel(ec, tenantId, pair, automation), automationId: normalize(pair.get("automationId")),
             automationName: normalize(automation?.get("automationName")), allowanceMinutes: pair.get("allowanceMinutes"),
             pulseEveryMinutes: pair.get("pulseEveryMinutes"), isActive: normalize(pair.get("isActive")) ?: "Y",
             lastPulseStamp: iso(pair.get("lastPulseStamp")), waitingCount: (latestOk?.get("waitingCount") ?: 0) as int,
             pastAllowanceCount: past as int] as Map
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
        String periodName = overrides ? windows.find { it.peakPeriodId == eff.peakPeriodId }?.periodName : null
        Map automation = automationOf(ec, tenantId, normalize(pair.get("automationId")))

        Map syncPair = [syncPairId: id, label: pairLabel(ec, tenantId, pair, automation), automationId: normalize(pair.get("automationId")),
                        automationName: normalize(automation?.get("automationName")), upstreamSide: normalize(pair.get("upstreamSide")),
                        allowanceMinutes: eff.allowanceMinutes, pulseEveryMinutes: eff.pulseEveryMinutes,
                        normalAllowanceMinutes: pair.get("allowanceMinutes"), normalPulseEveryMinutes: pair.get("pulseEveryMinutes"),
                        periodName: periodName, chatSpaceId: normalize(pair.get("chatSpaceId")),
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
        List<Map> past = (rows(ec, "darpan.pulse.SyncPulseOrder", tenantId).condition("syncPairId", id).condition("closedStamp", null)
                .orderBy("sourceCreatedStamp").list() ?: []).collect { o ->
            Object created = o.get("sourceCreatedStamp")
            Long minutes = created instanceof Timestamp ? Duration.between(((Timestamp) created).toInstant(), now).toMinutes() : null
            [recordKey: o.get("recordKey"), orderName: o.get("orderName"), otherSystemId: o.get("otherSystemId"),
             sourceCreatedStamp: iso(created), waitingMinutes: minutes, reportedInRunId: null] as Map
        }
        return [syncPair: syncPair, samplesToday: samples, pastOrders: past]
    }

    /** Deletes a pair with its peak-period rows, samples and open/closed orders (the tenant's rows only). */
    static void deletePairCascade(def ec, String tenantId, String syncPairId) {
        ["darpan.pulse.PeakPeriodSync", "darpan.pulse.SyncPulseSample", "darpan.pulse.SyncPulseOrder"].each { String entity ->
            rows(ec, entity, tenantId).condition("syncPairId", syncPairId).deleteAll()
        }
        rows(ec, "darpan.pulse.SyncPair", tenantId).condition("syncPairId", syncPairId).deleteAll()
    }
}
