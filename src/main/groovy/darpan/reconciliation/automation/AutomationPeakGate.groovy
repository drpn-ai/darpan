package darpan.reconciliation.automation

import darpan.facade.common.TenantAccessSupport
import darpan.peak.AllowanceSpec
import darpan.peak.PeakPeriodLoader
import darpan.peak.PeakPeriodSupport
import darpan.peak.PeakWindow

import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.readField
import static darpan.reconciliation.automation.AutomationWindows.resolveZoneId
import static darpan.reconciliation.automation.AutomationWindows.toTimestamp

/**
 * DAR-OPS-029 S06 (split out of AutomationExecutionSupport). DAR-BE-328: when a scheduled run may start: a peak period's
 * run-time move, peak hours, and a sync pair's lag allowance, read once per scan tick per tenant. Fails open: a peak-period
 * error never stops scheduling.
 */
class AutomationPeakGate {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(AutomationPeakGate)

    /** DAR-BE-328: a tenant's peak windows; tests set their own, as setRunAdmission. Null restores the default. */
    private static final Closure<List<PeakWindow>> DEFAULT_PEAK_WINDOWS_LOADER = { def ec, String tenantId, LocalDate fromDate ->
        PeakPeriodLoader.load(ec, tenantId, fromDate)
    }

    /** DAR-BE-328: a tenant's own zone (TenantSetting.timeZone, else UTC); tests set their own. Null restores the default. */
    private static final Closure<ZoneId> DEFAULT_TENANT_ZONE_LOADER = { def ec, String tenantId ->
        TenantAccessSupport.tenantTimeZoneFor(ec, tenantId)
    }

    /** DAR-BE-328 plan 3: a tenant's sync lag allowances by automationId (active sync pairs); tests set their own. Null restores the default. */
    private static final Closure<Map<String, AllowanceSpec>> DEFAULT_PEAK_ALLOWANCES_LOADER = { def ec, String tenantId ->
        PeakPeriodLoader.allowances(ec, tenantId)
    }

    static Closure<List<PeakWindow>> peakWindowsLoader = DEFAULT_PEAK_WINDOWS_LOADER

    static Closure<ZoneId> tenantZoneLoader = DEFAULT_TENANT_ZONE_LOADER

    static Closure<Map<String, AllowanceSpec>> peakAllowancesLoader = DEFAULT_PEAK_ALLOWANCES_LOADER

    static void setPeakAllowancesLoader(Closure<Map<String, AllowanceSpec>> loader) { peakAllowancesLoader = loader ?: DEFAULT_PEAK_ALLOWANCES_LOADER }

    static void setPeakWindowsLoader(Closure<List<PeakWindow>> loader) { peakWindowsLoader = loader ?: DEFAULT_PEAK_WINDOWS_LOADER }

    static void setTenantZoneLoader(Closure<ZoneId> loader) { tenantZoneLoader = loader ?: DEFAULT_TENANT_ZONE_LOADER }

    /**
     * DAR-BE-328: one scan tick's per-tenant peak windows, zones and sync lag allowances, each loaded at most once and
     * never kept past the tick.
     */
    static Map<String, Object> newPeakTick(Timestamp now) {
        return [now: now, windows: [:], zones: [:], allowances: [:]] as Map<String, Object>
    }

    /**
     * DAR-BE-328: when a run of this fire may start. A run-time move for the fire's date (applyMoves; only while no fire of
     * that date has run yet, so a move cannot start every later fire of the same day too), then peak hours: a start inside
     * them waits until they end, and so does one that would happen now while now is inside them. With no peak periods,
     * or if reading them fails, the fire itself: a peak-period bug never stops scheduling.
     * A run moved earlier never starts before its fire's date has begun in the automation's windowTimeZone, when its
     * read window has closed (see readWindowEnd), or it would read orders still to come; with an automation in another
     * zone than the tenant that can be hours after the move. Callers that apply moves pass the automation; without it
     * no bound is applied.
     * Plan 3: an automation with an active sync pair never starts before its read window's end plus the sync lag
     * allowance in force on the fire's tenant-local date (PeakPeriodSupport.effectiveAllowance), with or without peak
     * periods. That bound only raises the start, after the window clamp and before the peak hold; the fire, and so the
     * read window, never changes. Only applied when the automation is passed (a retry's never is).
     */
    static Instant peakStart(def ec, Map<String, Object> tick, Instant fire, String automationId, String tenantId,
                                       boolean applyMoves, Timestamp lastFire = null, def automation = null) {
        List<PeakWindow> windows = peakWindowsOf(ec, tick, tenantId)
        AllowanceSpec allowance = automation != null ? peakAllowanceOf(ec, tick, tenantId, automationId) : null
        if (!windows && allowance == null) return fire
        try {
            ZoneId zone = peakZoneOf(ec, tick, tenantId)
            Instant now = ((Timestamp) tick.now).toInstant()
            boolean moves = applyMoves && (lastFire == null ||
                    lastFire.toInstant().atZone(zone).toLocalDate() != fire.atZone(zone).toLocalDate())
            Instant start = PeakPeriodSupport.effectiveStart(fire, automationId, windows, zone, moves)
            if (moves && automation != null && start.isBefore(fire)) {
                Instant windowEnd = readWindowEnd(automation, fire)
                if (start.isBefore(windowEnd)) start = PeakPeriodSupport.effectiveStart(windowEnd, automationId, windows, zone, false)
            }
            if (allowance != null) {
                // The allowance of the day the check reads (not the fire's day): the last day of its window, tenant zone.
                Instant windowEnd = readWindowEnd(automation, fire)
                int minutes = PeakPeriodSupport.effectiveAllowance(allowance, windows, lastReadDay(windowEnd, zone))
                Instant allowed = windowEnd.plus(Duration.ofMinutes(minutes))
                if (start.isBefore(allowed)) start = PeakPeriodSupport.effectiveStart(allowed, automationId, windows, zone, false)
            }
            if (start.isAfter(now)) return start
            Instant fromNow = PeakPeriodSupport.effectiveStart(now, automationId, windows, zone, false)
            return fromNow.isAfter(now) ? fromNow : start
        } catch (Throwable peakError) {
            logger.error("Peak periods or the sync lag allowance of tenant {} could not be applied to automation {}; it starts at its normal time",
                    tenantId, automationId, peakError)
            return fire
        }
    }

    /** DAR-BE-328 plan 3: the last day a read window ending at windowEnd reads, in the tenant's zone. */
    static LocalDate lastReadDay(Instant windowEnd, ZoneId tenantZone) {
        return windowEnd.minusMillis(1).atZone(tenantZone).toLocalDate()
    }

    /** DAR-BE-328 plan 3: the longest allowance a pair can have, normal or any period's, in minutes. */
    static int longestAllowanceMinutes(AllowanceSpec spec) {
        int longest = spec.normalMinutes
        (spec.periodMinutes ?: [:]).values().each { Integer m -> if (m != null && m > longest) longest = m }
        return longest
    }

    /**
     * DAR-BE-328: the earliest a moved-earlier run of this fire may start: the start of the fire's own date in the
     * automation's windowTimeZone, never after the fire. Every relative read window (last days, previous day, week or
     * month) has closed by then. resolveWindows is not used: an audit's check reads the audit's window (a service call per
     * automation per tick), which runs to the end of the report day, so it would forbid every earlier move of an audit.
     * Plan 3: also where a sync pair's allowance counts from (PeakPeriodFacadeSupport.heldRuns uses it too).
     */
    static Instant readWindowEnd(def automation, Instant fire) {
        ZoneId zone = resolveZoneId(readField(automation, "windowTimeZone"))
        Instant dayStart = fire.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
        return dayStart.isAfter(fire) ? fire : dayStart
    }

    /**
     * DAR-BE-328: what the automation page shows for the next run. effectiveNextStart is when the scheduler will start it
     * (the SAME peakStart the scan uses, so page and scheduler cannot disagree; the stored fire itself when no period
     * applies); peakPeriodName names the period behind a differing start; peakHoursUntil is set only while now is inside
     * peak hours. Fails open: any error leaves the stored fire and no peak hours.
     */
    static Map<String, Object> peakNextRun(def ec, Map<String, Object> tick, def automation) {
        Map<String, Object> out = [:]
        Timestamp fire = toTimestamp(readField(automation, "nextScheduledFireTime"))
        if (fire != null) out.effectiveNextStart = fire
        try {
            String tenantId = normalize(readField(automation, "companyUserGroupId"))
            String automationId = normalize(readField(automation, "automationId"))
            List<PeakWindow> windows = peakWindowsOf(ec, tick, tenantId)
            // Plan 3: a sync lag allowance moves the next run with no peak period at all.
            if (!windows && peakAllowanceOf(ec, tick, tenantId, automationId) == null) return out
            ZoneId zone = peakZoneOf(ec, tick, tenantId)
            Instant until = PeakPeriodSupport.peakHoursEndIfInside(windows, ((Timestamp) tick.now).toInstant(), zone)
            Map<String, Object> result = [:]
            if (fire != null) {
                Instant start = peakStart(ec, tick, fire.toInstant(), automationId, tenantId,
                        true, toTimestamp(readField(automation, "lastScheduledFireTime")), automation)
                result.effectiveNextStart = Timestamp.from(start)
                if (start != fire.toInstant()) {
                    PeakWindow period = PeakPeriodSupport.activeOn(windows, fire.toInstant().atZone(zone).toLocalDate()) ?:
                            PeakPeriodSupport.activeOn(windows, start.atZone(zone).toLocalDate())
                    if (period?.periodName) result.peakPeriodName = period.periodName
                }
            }
            if (until != null) result.peakHoursUntil = Timestamp.from(until)
            return result
        } catch (Throwable peakError) {
            logger.error("Peak periods could not be applied to the next run of automation {}; showing its normal time",
                    readField(automation, "automationId"), peakError)
            return out
        }
    }

    /** DAR-BE-328: when the tenant's peak hours end, only while now is inside them; null otherwise or if they cannot be read. */
    static Instant peakHoursEndNow(def ec, String tenantId, Timestamp now) {
        try {
            Map<String, Object> tick = newPeakTick(now)
            List<PeakWindow> windows = peakWindowsOf(ec, tick, tenantId)
            if (!windows) return null
            return PeakPeriodSupport.peakHoursEndIfInside(windows, now.toInstant(), peakZoneOf(ec, tick, tenantId))
        } catch (Throwable peakError) {
            logger.error("Peak hours of tenant {} could not be read; none is shown", tenantId, peakError)
            return null
        }
    }

    private static List<PeakWindow> peakWindowsOf(def ec, Map<String, Object> tick, String tenantId) {
        Map<String, List<PeakWindow>> cache = (Map<String, List<PeakWindow>>) tick.windows
        String key = tenantId ?: ""
        if (cache.containsKey(key)) return cache[key]
        List<PeakWindow> windows = []
        if (tenantId) {
            try {
                // UTC date less a day: the loader adds its own day back for peak hours that cross midnight in any zone.
                LocalDate fromDate = ((Timestamp) tick.now).toInstant().atZone(ZoneId.of("UTC")).toLocalDate().minusDays(1)
                windows = (peakWindowsLoader.call(ec, tenantId, fromDate) ?: []) as List<PeakWindow>
            } catch (Throwable loadError) {
                logger.error("Could not read the peak periods of tenant {}; its runs start at their normal time this tick",
                        tenantId, loadError)
                windows = []
            }
        }
        cache[key] = windows
        return windows
    }

    /**
     * DAR-BE-328 plan 3: the automation's sync lag allowance, null when it has no active sync pair. The tenant's
     * allowances load once per tick; if that read fails, none apply this tick (fail open, as peak periods do).
     */
    static AllowanceSpec peakAllowanceOf(def ec, Map<String, Object> tick, String tenantId, String automationId) {
        if (!tenantId || !automationId) return null
        Map<String, Map<String, AllowanceSpec>> cache = (Map<String, Map<String, AllowanceSpec>>) tick.computeIfAbsent("allowances") { [:] }
        if (!cache.containsKey(tenantId)) {
            Map<String, AllowanceSpec> loaded = [:]
            try {
                loaded = (peakAllowancesLoader.call(ec, tenantId) ?: [:]) as Map<String, AllowanceSpec>
            } catch (Throwable loadError) {
                logger.error("Could not read the sync lag allowances of tenant {}; its runs start without them this tick",
                        tenantId, loadError)
            }
            cache[tenantId] = loaded
        }
        return cache[tenantId][automationId]
    }

    private static ZoneId peakZoneOf(def ec, Map<String, Object> tick, String tenantId) {
        Map<String, ZoneId> cache = (Map<String, ZoneId>) tick.zones
        if (!cache.containsKey(tenantId)) cache[tenantId] = (tenantZoneLoader.call(ec, tenantId) ?: ZoneId.of("UTC")) as ZoneId
        return cache[tenantId]
    }

    /** DAR-OPS-026: the peak loaders back to their defaults. Test-only. */
    static void resetForTests() {
        peakWindowsLoader = DEFAULT_PEAK_WINDOWS_LOADER
        tenantZoneLoader = DEFAULT_TENANT_ZONE_LOADER
        peakAllowancesLoader = DEFAULT_PEAK_ALLOWANCES_LOADER
    }
}
