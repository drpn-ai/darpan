package darpan.admin

import darpan.common.DarpanEntityConstants
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.automation.SchedulePlanner
import groovy.transform.CompileDynamic

import java.sql.Timestamp

/**
 * Instance-wide schedule board for the admin app: every tenant's scheduled automations on one
 * timeline, ordered by when the scheduler will actually submit them.
 *
 * Reads across tenants deliberately and without tenant scoping — the DARPAN_ADMIN_API artifact
 * fence plus the super-admin content gate are the boundary, the same shape AdminTenantSupport uses.
 */
@CompileDynamic
class AdminScheduleSupport {

    static final int DEFAULT_HORIZON_HOURS = 24

    /** Per-automation projection cap, so a PT1M schedule cannot flood the payload. */
    static final int MAX_FIRES_PER_AUTOMATION = 500

    static Map<String, Object> getScheduleBoard(def ec, Integer horizonHours, Timestamp now) {
        if (!AdminAccessSupport.requireSuperAdmin(ec)) return null

        int hours = (horizonHours != null && horizonHours > 0) ? horizonHours : DEFAULT_HORIZON_HOURS
        Timestamp to = new Timestamp(now.time + (hours * 3600000L))
        Map<String, String> tenantLabels = tenantLabelsById(ec)

        // Partitioned in Groovy off a single read rather than two conditioned queries: the board
        // needs both halves, and a paused automation still carries a stale nextScheduledFireTime
        // that MUST NOT reach the timeline — the scanner skips it, so projecting it would invent
        // load that never happens.
        Map<Boolean, List> byActive = allAutomations(ec)
                .collect { toAutomationMap(it) }
                .groupBy { it.isActive != "N" }

        List<Map<String, Object>> fires = []
        (byActive[true] ?: []).each { Map automation ->
            SchedulePlanner.projectFireTimes(automation, now, to, MAX_FIRES_PER_AUTOMATION)
                    .each { Timestamp fireTime ->
                        fires << fireEntry(automation, tenantLabels, fireTime)
                    }
        }
        fires.sort { it.fireTime }
        stampTickConcurrency(fires)

        List<Map<String, Object>> paused = (byActive[false] ?: []).collect { Map automation ->
            rosterEntry(automation, tenantLabels)
        }.sort { it.automationName }

        return [generatedAt : now,
                horizonHours: hours,
                inFlight    : inFlightExecutions(ec, tenantLabels, namesByAutomationId(byActive)),
                inFlightCap : AutomationExecutionSupport.MAX_CONCURRENT_EXECUTIONS,
                fires       : fires,
                paused      : paused]
    }

    /**
     * Executions the scheduler has started but not finished — what the next tick's submission
     * budget is measured against, and the direct answer to "is anything running right now".
     */
    private static List<Map<String, Object>> inFlightExecutions(def ec, Map<String, String> tenantLabels,
                                                                Map<String, String> automationNames) {
        List running = ec.entity.find(DarpanEntityConstants.RECONCILIATION_AUTOMATION_EXECUTION)
                .condition("statusEnumId", AutomationExecutionSupport.STATUS_RUNNING).list() ?: []
        return running.collect { execution ->
            String tenantId = readField(execution, "companyUserGroupId")
            String automationId = readField(execution, "automationId")
            [
                    automationExecutionId: readField(execution, "automationExecutionId"),
                    automationId         : automationId,
                    automationName       : automationNames[automationId],
                    tenantUserGroupId    : tenantId,
                    tenantLabel          : tenantLabels[tenantId] ?: tenantId,
                    startedDate          : readField(execution, "startedDate"),
            ]
        }.sort { it.startedDate }
    }

    /** Names for BOTH halves: an execution can still be running for an automation just paused. */
    private static Map<String, String> namesByAutomationId(Map<Boolean, List> byActive) {
        return ((byActive[true] ?: []) + (byActive[false] ?: []))
                .collectEntries { [(it.automationId): it.automationName] }
    }

    /**
     * Marks each fire with how many fires share its submission tick.
     *
     * Bucketed on submitsAt, not fireTime: three automations due at 15:01, 15:03 and 15:05 all
     * start together on the 15:05 tick, and counting by due time would report three quiet minutes
     * where the scheduler actually sees a burst of three.
     */
    private static void stampTickConcurrency(List<Map<String, Object>> fires) {
        Map<Timestamp, Integer> countsByTick = fires.countBy { it.submitsAt }
        fires.each { Map<String, Object> fire -> fire.concurrentAtTick = countsByTick[fire.submitsAt] }
    }

    private static Map<String, Object> fireEntry(Map automation, Map<String, String> tenantLabels, Timestamp fireTime) {
        String tenantId = automation.companyUserGroupId
        return [
                fireTime         : fireTime,
                submitsAt        : SchedulePlanner.submitsAt(fireTime),
                automationId     : automation.automationId,
                automationName   : automation.automationName,
                tenantUserGroupId: tenantId,
                tenantLabel      : tenantLabels[tenantId] ?: tenantId,
                scheduleExpr     : automation.scheduleExpr,
                windowTimeZone   : automation.windowTimeZone ?: "UTC",
        ]
    }

    private static List allAutomations(def ec) {
        return ec.entity.find("darpan.reconciliation.ReconciliationAutomation").list() ?: []
    }

    private static Map<String, Object> rosterEntry(Map automation, Map<String, String> tenantLabels) {
        String tenantId = automation.companyUserGroupId
        return [
                automationId     : automation.automationId,
                automationName   : automation.automationName,
                tenantUserGroupId: tenantId,
                tenantLabel      : tenantLabels[tenantId] ?: tenantId,
                scheduleExpr     : automation.scheduleExpr,
                windowTimeZone   : automation.windowTimeZone ?: "UTC",
        ]
    }

    private static Map<String, String> tenantLabelsById(def ec) {
        return (ec.entity.find("moqui.security.UserGroup").list() ?: []).collectEntries { group ->
            String id = readField(group, "userGroupId")
            [(id): (readField(group, "description") ?: id)]
        }
    }

    /**
     * Copies the fields the board needs off the row before any projection runs.
     *
     * Deliberately a plain Map: an EntityValue RAISES on an undeclared field where a Map answers
     * null, so narrowing to a Map here keeps the downstream planner safe against either shape.
     */
    private static Map<String, Object> toAutomationMap(def automation) {
        return [
                automationId         : readField(automation, "automationId"),
                automationName       : readField(automation, "automationName"),
                companyUserGroupId   : readField(automation, "companyUserGroupId"),
                scheduleExpr         : readField(automation, "scheduleExpr"),
                windowTimeZone       : readField(automation, "windowTimeZone"),
                nextScheduledFireTime: readField(automation, "nextScheduledFireTime"),
                isActive             : readField(automation, "isActive"),
        ]
    }

    private static Object readField(def row, String fieldName) {
        return row == null ? null : row.get(fieldName)
    }
}
