package darpan.peak

import darpan.audit.AuditSettingsSupport
import darpan.facade.common.TenantScopedFinder
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.time.LocalDate
import java.time.LocalTime

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-328. Reads one tenant's peak periods for the automation scheduler, which runs with no active tenant: every
 * read carries an explicit companyUserGroupId condition. Never throws for bad data: a period or run time whose "HH:mm"
 * does not parse is left out with a warn naming its id, so one bad row never stops the scheduler.
 */
class PeakPeriodLoader {
    private static final Logger logger = LoggerFactory.getLogger(PeakPeriodLoader)
    private static final String WHY = "scheduler reads a tenant's peak periods (no active tenant): explicit companyUserGroupId condition applied"

    /**
     * The tenant's active periods with thruDate on or after the day before fromDate (that day covers peak hours that cross
     * midnight), oldest first, each with its run-time moves. A move that names an audit applies to the automations its
     * checks run through.
     */
    static List<PeakWindow> load(def ec, String tenantId, LocalDate fromDate) {
        String tenant = normalize(tenantId)
        if (!tenant) return []
        LocalDate earliestThru = fromDate?.minusDays(1)
        List periods = (rows(ec, "darpan.peak.PeakPeriod", tenant).list() ?: []).findAll { period ->
            LocalDate thru = toLocalDate(period.get("thruDate"))
            normalize(period.get("isActive")) != "N" && thru != null && (earliestThru == null || !thru.isBefore(earliestThru))
        }
        if (!periods) return []

        Map<String, List> runTimesByPeriod = (rows(ec, "darpan.peak.PeakPeriodRunTime", tenant).list() ?: [])
                .groupBy { normalize(it.get("peakPeriodId")) }
        Map<String, Set<String>> auditAutomations = [:]
        List<PeakWindow> windows = []
        periods.each { period ->
            String peakPeriodId = normalize(period.get("peakPeriodId"))
            LocalTime peakStart
            LocalTime peakEnd
            try {
                peakStart = PeakPeriodSupport.parseHhMm(period.get("peakHoursStart") as String)
                peakEnd = PeakPeriodSupport.parseHhMm(period.get("peakHoursEnd") as String)
            } catch (IllegalArgumentException e) {
                logger.warn("Peak period {} (tenant {}) left out of scheduling: peak hours {}", peakPeriodId, tenant, e.message)
                return
            }
            if (peakStart != null && peakStart == peakEnd) {
                // Save refuses this ("Peak hours cannot start and end at the same time."); read as is it would be 24 hours.
                logger.warn("Peak period {} (tenant {}) left out of scheduling: its peak hours start and end at {}",
                        peakPeriodId, tenant, peakStart)
                return
            }
            if ((peakStart == null) != (peakEnd == null)) {
                logger.warn("Peak period {} (tenant {}) has only one end of its peak hours; scheduling reads it as no peak hours",
                        peakPeriodId, tenant)
                peakStart = null
                peakEnd = null
            }
            List<RunTimeMove> moves = (runTimesByPeriod[peakPeriodId] ?: []).collect { runTime ->
                moveOf(ec, tenant, runTime, auditAutomations)
            }.findAll { it != null }
            windows << new PeakWindow(peakPeriodId, normalize(period.get("periodName")), toLocalDate(period.get("fromDate")),
                    toLocalDate(period.get("thruDate")), peakStart, peakEnd, moves)
        }
        return windows.findAll { it.fromDate != null }.sort { PeakWindow w -> w.fromDate }
    }

    private static RunTimeMove moveOf(def ec, String tenant, def runTime, Map<String, Set<String>> auditAutomations) {
        String runTimeId = normalize(runTime.get("runTimeId"))
        LocalTime at
        try {
            at = PeakPeriodSupport.parseHhMm(runTime.get("runTime") as String)
        } catch (IllegalArgumentException e) {
            logger.warn("Peak run time {} (tenant {}) skipped: {}", runTimeId, tenant, e.message)
            return null
        }
        if (at == null) {
            logger.warn("Peak run time {} (tenant {}) skipped: it has no time", runTimeId, tenant)
            return null
        }
        Set<String> automationIds = new LinkedHashSet<String>()
        String automationId = normalize(runTime.get("automationId"))
        if (automationId) automationIds << automationId
        String auditId = normalize(runTime.get("auditId"))
        if (auditId) {
            automationIds.addAll(auditAutomations.computeIfAbsent(auditId) { String id ->
                (AuditSettingsSupport.automationsOfAudit(ec, tenant, [auditId: id])*.automationId
                        .collect { normalize(it) }.findAll { it }) as Set<String>
            })
        }
        if (!automationIds) return null
        return new RunTimeMove(automationIds.asImmutable(), toLocalDate(runTime.get("onDate")), at)
    }

    private static def rows(def ec, String entityName, String tenant) {
        // Every scan tick reads every tenant: the DEBUG twin, so the INFO log does not get a line per tenant per tick.
        return TenantScopedFinder.findGlobalUnscopedPerRequest(ec, entityName, WHY).condition("companyUserGroupId", tenant).useCache(false)
    }

    private static LocalDate toLocalDate(Object value) {
        if (value == null) return null
        if (value instanceof LocalDate) return (LocalDate) value
        if (value instanceof java.sql.Date) return ((java.sql.Date) value).toLocalDate()
        if (value instanceof Date) return new java.sql.Date(((Date) value).time).toLocalDate()
        String text = normalize(value)
        return text ? LocalDate.parse(text.take(10)) : null
    }
}
