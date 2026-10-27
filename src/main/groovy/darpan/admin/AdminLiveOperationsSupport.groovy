package darpan.admin

import darpan.common.DarpanEntityConstants
import darpan.facade.reconciliation.RunHealth
import darpan.facade.reconciliation.RunObservability
import darpan.reconciliation.automation.AutomationExecutionSupport
import groovy.transform.CompileDynamic
import org.moqui.entity.EntityCondition

import java.sql.Timestamp

/**
 * DAR-ADMIN-008. The admin live board: what is running on this instance right now, how healthy each
 * run is, how loaded the system is, and the warnings that follow — every tenant on one page.
 *
 * Reads across tenants deliberately and without tenant scoping, exactly like AdminScheduleSupport:
 * the DARPAN_ADMIN_API artifact fence plus the super-admin content gate are the boundary.
 *
 * Each source is read independently. One that throws lands in `unavailable` with its reason and the
 * rest of the board still loads; the UI must never render a failed source as a zero.
 *
 * Spec: docs/superpowers/specs/2026-10-27-admin-live-operations-design.md (wrapper repo).
 */
@CompileDynamic
class AdminLiveOperationsSupport {

    static final int FAILURE_WINDOW_HOURS = 24
    /** Overdue list starts one scan tick late; the warning thresholds live in LiveOperationsRules. */
    static final long OVERDUE_LIST_MS = 5 * LiveOperationsRules.MINUTE_MS

    static Map<String, Object> getLiveOperations(def ec, Timestamp now, Closure<Map> systemReader = { e -> SystemLoadReader.read(e) }) {
        if (!AdminAccessSupport.requireSuperAdmin(ec)) return null

        List<Map<String, Object>> unavailable = []
        Map<String, String> tenantLabels = guarded("tenants", unavailable, [:]) { tenantLabelsById(ec) }
        Map<String, Map> automations = guarded("automations", unavailable, [:]) { automationsById(ec) }
        Map<String, String> auditNames = guarded("audits", unavailable, [:]) { auditNameByAutomationId(ec) }
        List<Map> runningExecutions = guarded("executions", unavailable, []) {
            executionsWithStatus(ec, AutomationExecutionSupport.STATUS_RUNNING)
        }
        List<Map> pendingExecutions = guarded("executions", unavailable, []) {
            executionsWithStatus(ec, AutomationExecutionSupport.STATUS_PENDING)
        }
        Naming naming = new Naming(ec: ec, tenantLabels: tenantLabels, automations: automations)

        List<Map<String, Object>> running = guarded("runs", unavailable, []) {
            runningRows(ec, now, naming, runningExecutions, auditNames)
        }
        List<Map<String, Object>> failures = guarded("failures", unavailable, []) { recentFailures(ec, now, naming) }
        List<Map<String, Object>> overdue = guarded("automations", unavailable, []) {
            overdueAutomations(automations, runningExecutions, tenantLabels, now)
        }
        List<Map<String, Object>> retrying = pendingExecutions.findAll { ((it.retryCount ?: 0) as Integer) > 0 }.collect {
            it + [name: automations[it.automationId]?.automationName ?: it.automationId,
                  tenantLabel: tenantLabels[it.companyUserGroupId] ?: it.companyUserGroupId]
        }
        Map<String, Object> system = guarded("system", unavailable, null) { systemReader.call(ec) }

        int cap = AutomationExecutionSupport.MAX_CONCURRENT_EXECUTIONS
        List<Map> warnings = LiveOperationsRules.runWarnings(running) +
                LiveOperationsRules.failureWarnings(failures, FAILURE_WINDOW_HOURS) +
                LiveOperationsRules.retryWarnings(retrying) +
                LiveOperationsRules.slotWarnings(runningExecutions.size(), cap) +
                LiveOperationsRules.overdueWarnings(overdue) +
                (system == null ? [] : LiveOperationsRules.systemWarnings(system))

        return [generatedAt   : now,
                warnings      : LiveOperationsRules.sortWarnings(warnings),
                running       : running,
                overdue       : overdue,
                slots         : [inFlight: runningExecutions.size(), cap: cap, pending: pendingExecutions.size()],
                recentFailures: [windowHours: FAILURE_WINDOW_HOURS, count: failures.size(),
                                 items      : failures.sort(false) { -((it.completedDate as Timestamp)?.time ?: 0L) }],
                system        : system,
                unavailable   : unavailable.unique { it.source }]
    }

    /**
     * One row per RUNNING run result. Manual runs, scheduled automations and audits all write one: an
     * automation mints its row as it goes RUNNING and stamps the id on the execution in the same update,
     * so the execution is how a row is told apart from a manual run.
     */
    private static List<Map<String, Object>> runningRows(def ec, Timestamp now, Naming naming, List<Map> runningExecutions,
                                                         Map<String, String> auditNames) {
        Map<String, Map> executionByRunId = runningExecutions.findAll { it.reconciliationRunResultId }
                .collectEntries { [(it.reconciliationRunResultId): it] }
        List rows = ec.entity.find(DarpanEntityConstants.RECONCILIATION_RUN_RESULT)
                .condition("statusEnumId", RunObservability.STATUS_RUNNING).useCache(false).list() ?: []
        Map<String, Long> usualBySavedRun = [:]
        return rows.collect { row ->
            String runId = readField(row, "reconciliationRunResultId")
            String tenantId = readField(row, "companyUserGroupId")
            String savedRunId = readField(row, "savedRunId")
            Map execution = executionByRunId[runId]
            String automationId = execution?.automationId
            String auditName = automationId ? auditNames[automationId] : null
            String usualKey = "${tenantId}|${savedRunId}".toString()
            if (!usualBySavedRun.containsKey(usualKey)) usualBySavedRun[usualKey] = RunHealth.usualDurationMs(
                    ec.entity.find(DarpanEntityConstants.RECONCILIATION_RUN_RESULT), tenantId, savedRunId)

            Map<String, Object> run = [
                    runResultId          : runId,
                    kind                 : auditName ? "audit" : (execution ? "automation" : "run"),
                    name                 : auditName ?: (automationId ? naming.automationName(automationId) : naming.savedRunName(tenantId, savedRunId)),
                    tenantUserGroupId    : tenantId,
                    tenantLabel          : naming.tenantLabel(tenantId),
                    savedRunId           : savedRunId,
                    automationId         : automationId,
                    automationExecutionId: execution?.automationExecutionId,
                    attempt              : execution ? ((execution.retryCount ?: 0) as Integer) + 1 : null,
                    currentStage         : readField(row, "currentStage"),
                    stageSequence        : RunObservability.stageSequenceOf(readField(row, "currentStage") as String),
                    progressPercent      : readField(row, "progressPercent"),
                    startedDate          : readField(row, "startedDate"),
                    lastHeartbeatDate    : readField(row, "lastHeartbeatDate"),
                    cancelRequestedDate  : readField(row, "cancelRequestedDate"),
                    usualDurationMs      : usualBySavedRun[usualKey],
            ]
            Timestamp started = run.startedDate as Timestamp
            run.elapsedMs = started == null ? null : now.time - started.time
            Map health = RunHealth.of(run, now)
            run.health = health.health
            run.healthReason = health.reason
            return run
        }.sort { (it.startedDate as Timestamp)?.time ?: 0L }
    }

    private static List<Map<String, Object>> recentFailures(def ec, Timestamp now, Naming naming) {
        Timestamp since = new Timestamp(now.time - FAILURE_WINDOW_HOURS * 3600_000L)
        List rows = ec.entity.find(DarpanEntityConstants.RECONCILIATION_RUN_RESULT)
                .condition("statusEnumId", RunObservability.STATUS_FAILED)
                .condition("completedDate", EntityCondition.ComparisonOperator.GREATER_THAN_EQUAL_TO, since)
                .useCache(false).list() ?: []
        return rows.collect { row ->
            String tenantId = readField(row, "companyUserGroupId")
            String savedRunId = readField(row, "savedRunId")
            [runResultId      : readField(row, "reconciliationRunResultId"),
             tenantUserGroupId: tenantId,
             tenantLabel      : naming.tenantLabel(tenantId),
             savedRunId       : savedRunId,
             name             : naming.savedRunName(tenantId, savedRunId),
             currentStage     : readField(row, "currentStage"),
             completedDate    : readField(row, "completedDate"),
             errorMessage     : readField(row, "errorMessage")]
        }
    }

    /**
     * Active automations whose fire time has passed by more than a tick. The concurrency cap defers
     * them WITHOUT writing a PENDING row, so this lateness is the only queue the scheduler has.
     * An automation still running its previous fire is excluded: it is late, but not for want of a slot.
     */
    private static List<Map<String, Object>> overdueAutomations(Map<String, Map> automations, List<Map> runningExecutions,
                                                                Map<String, String> tenantLabels, Timestamp now) {
        Set<String> busy = runningExecutions*.automationId.toSet()
        return automations.values().findAll { Map automation ->
            Timestamp due = automation.nextScheduledFireTime as Timestamp
            automation.isActive != "N" && due != null && now.time - due.time > OVERDUE_LIST_MS && !busy.contains(automation.automationId)
        }.collect { Map automation ->
            Timestamp due = automation.nextScheduledFireTime as Timestamp
            [automationId     : automation.automationId,
             name             : automation.automationName,
             tenantUserGroupId: automation.companyUserGroupId,
             tenantLabel      : tenantLabels[automation.companyUserGroupId] ?: automation.companyUserGroupId,
             dueAt            : due,
             lateMs           : now.time - due.time]
        }.sort { -(it.lateMs as Long) }
    }

    private static List<Map> executionsWithStatus(def ec, String statusEnumId) {
        return (ec.entity.find(DarpanEntityConstants.RECONCILIATION_AUTOMATION_EXECUTION)
                .condition("statusEnumId", statusEnumId).useCache(false).list() ?: []).collect { row ->
            [automationExecutionId    : readField(row, "automationExecutionId"),
             automationId             : readField(row, "automationId"),
             companyUserGroupId       : readField(row, "companyUserGroupId"),
             reconciliationRunResultId: readField(row, "reconciliationRunResultId"),
             retryCount               : readField(row, "retryCount"),
             maxRetryCount            : readField(row, "maxRetryCount"),
             nextRetryDate            : readField(row, "nextRetryDate"),
             lastUpdatedDate          : readField(row, "lastUpdatedDate")]
        }
    }

    private static Map<String, Map> automationsById(def ec) {
        return (ec.entity.find("darpan.reconciliation.ReconciliationAutomation").list() ?: []).collectEntries { row ->
            String id = readField(row, "automationId")
            [(id): [automationId         : id,
                    automationName       : readField(row, "automationName"),
                    companyUserGroupId   : readField(row, "companyUserGroupId"),
                    savedRunId           : readField(row, "savedRunId"),
                    isActive             : readField(row, "isActive"),
                    nextScheduledFireTime: readField(row, "nextScheduledFireTime")]]
        }
    }

    /**
     * An automation an AuditCheck points at runs that audit's checks; its runs show as the audit. A check reaches its
     * audit through its statements (AuditStatementCheck -> AuditStatement.auditId), as AuditAutomationSupport.auditFor.
     * Plain Lists throughout: EntityList.find/findAll cast the closure's answer to Boolean.
     */
    private static Map<String, String> auditNameByAutomationId(def ec) {
        Map<String, String> auditNames = (ec.entity.find("darpan.audit.Audit").list() ?: [])
                .collectEntries { row -> [(readField(row, "auditId")): readField(row, "auditName")] }
        Map<String, String> auditByStatement = (ec.entity.find("darpan.audit.AuditStatement").list() ?: [])
                .collectEntries { row -> [(readField(row, "statementId")): readField(row, "auditId")] }
        Map<String, String> statementByCheck = (ec.entity.find("darpan.audit.AuditStatementCheck").list() ?: [])
                .collectEntries { row -> [(readField(row, "checkId")): readField(row, "statementId")] }
        Map<String, String> byAutomation = [:]
        (ec.entity.find("darpan.audit.AuditCheck").list() ?: []).each { row ->
            String automationId = readField(row, "automationId")
            String auditName = auditNames[auditByStatement[statementByCheck[readField(row, "checkId") as String]]]
            if (automationId && auditName) byAutomation[automationId] = auditName
        }
        return byAutomation
    }

    private static Map<String, String> tenantLabelsById(def ec) {
        return (ec.entity.find("moqui.security.UserGroup").list() ?: []).collectEntries { group ->
            String id = readField(group, "userGroupId")
            [(id): (readField(group, "description") ?: id)]
        }
    }

    private static <T> T guarded(String source, List<Map<String, Object>> unavailable, T fallback, Closure<T> read) {
        try {
            return read.call()
        } catch (Throwable t) {
            unavailable << [source: source, reason: t.message ?: t.class.simpleName]
            return fallback
        }
    }

    private static Object readField(def row, String fieldName) {
        return row == null ? null : row.get(fieldName)
    }

    /** Display names, resolved once per id. A saved run is named by its automation when it has one. */
    private static class Naming {
        def ec
        Map<String, String> tenantLabels
        Map<String, Map> automations
        Map<String, String> ruleSetNames = [:]

        String tenantLabel(String tenantId) { return tenantLabels[tenantId] ?: tenantId }

        String automationName(String automationId) { return automations[automationId]?.automationName ?: automationId }

        String savedRunName(String tenantId, String savedRunId) {
            Map automation = automations.values().find { it.savedRunId == savedRunId && it.companyUserGroupId == tenantId }
            if (automation) return automation.automationName
            if (!savedRunId) return "Unnamed run"
            if (!ruleSetNames.containsKey(savedRunId)) {
                def ruleSet = ec.entity.find("darpan.rule.RuleSet").condition("ruleSetId", savedRunId).useCache(false).one()
                ruleSetNames[savedRunId] = readField(ruleSet, "ruleSetName") as String
            }
            return ruleSetNames[savedRunId] ?: savedRunId
        }
    }
}
