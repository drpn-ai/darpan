package darpan.audit

import darpan.facade.common.TenantAccessSupport
import darpan.facade.reconciliation.RunHealth
import darpan.facade.reconciliation.RunObservability
import darpan.peak.PeakReadPolicy
import darpan.peak.ReadLimits
import darpan.reconciliation.core.ReconciliationServices
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.automation.AutomationRuntimeSupport
import darpan.reconciliation.automation.SourceExtractOptionsSupport
import darpan.reconciliation.automation.SourceSystemConnectorSupport
import darpan.reconciliation.conclusion.RunConclusionSupport
import org.moqui.entity.EntityCondition
import java.sql.Date
import java.sql.Timestamp
import java.time.LocalDate
import java.time.ZoneId
import static darpan.common.ValueSupport.normalize

/**
 * DAR-OPS-029 S12 (split from AuditSettingsSupport, no behaviour change). What a play or the morning run is doing right now (DAR-UI-085): the audit's running and queued automations, their
 * stage, health and steps (DAR-BE-173, DAR-BE-350).
 * Every read and write is pinned to the tenant with an explicit companyUserGroupId condition.
 */
class AuditProgress {

    /**
     * DAR-UI-085. What a play (or the morning run) is doing right now, for the audit page's Run health: one entry per
     * automation of the audit with an execution RUNNING or PENDING, naming the checks it feeds, its stage and progress,
     * and its health (RunHealth). The ledger row a check writes lands only when its window ends, so without this the
     * report reads every check as "not run" until then.
     *
     * Answers {checks: configured checks, activeChecks, active: [{run, checks: [codes], state: running | queued,
     * attempt, stage, progressPercent, elapsedMs, usualDurationMs, health, healthReason, retryAt, steps}]}; steps as
     * steps() below.
     */
    static Map progress(def ec, String tenantId, Map audit, Timestamp now) {
        List<Map> checks = AuditSettingsSupport.checksByStatement(ec, tenantId, AuditSettingsSupport.statementsOf(ec, tenantId, audit, true)).values().flatten()
                .unique { it.checkId } as List<Map>
        Map<String, List<Map>> checksByAutomation = checks.findAll { it.automationId }.groupBy { it.automationId as String }
        List<Map> active = []
        if (checksByAutomation) {
            Map<String, Map> automations = AuditSettingsSupport.automationsOf(ec, tenantId, checks).collectEntries { [(it.automationId): it] }
            Map<String, String> names = AuditSettingsSupport.runNames(ec, tenantId, automations.values()*.savedRunId.findAll { it }.unique() as List<String>)
            AuditSettingsSupport.rows(ec, "darpan.reconciliation.ReconciliationAutomationExecution", tenantId)
                    .condition("automationId", EntityCondition.IN, checksByAutomation.keySet() as List)
                    .condition("statusEnumId", EntityCondition.IN, [AutomationExecutionSupport.STATUS_RUNNING, AutomationExecutionSupport.STATUS_PENDING])
                    .list()*.getMap().groupBy { it.automationId as String }.each { String automationId, List<Map> executions ->
                Map execution = executions.find { it.statusEnumId == AutomationExecutionSupport.STATUS_RUNNING } ?:
                        executions.max { (it.createdDate as Timestamp)?.time ?: 0L }
                boolean running = execution.statusEnumId == AutomationExecutionSupport.STATUS_RUNNING
                Map automation = automations[automationId] ?: [:]
                Map run = running && execution.reconciliationRunResultId ? AuditSettingsSupport.rows(ec, "darpan.reconciliation.ReconciliationRunResult", tenantId)
                        .condition("reconciliationRunResultId", execution.reconciliationRunResultId).list().find()?.getMap() : null
                Map entry = [run         : names[automation.savedRunId as String] ?: automation.automationName ?: automationId,
                             checks      : checksByAutomation[automationId]*.checkCode.sort(),
                             state       : running ? "running" : "queued",
                             attempt     : ((execution.retryCount ?: 0) as Integer) + 1,
                             retryAt     : running ? null : execution.nextRetryDate,
                             stage       : run?.currentStage,
                             progressPercent: run?.progressPercent]
                if (run) {
                    Timestamp started = (run.startedDate ?: execution.startedDate) as Timestamp
                    Long usual = RunHealth.usualDurationMs(AuditSettingsSupport.rows(ec, "darpan.reconciliation.ReconciliationRunResult", tenantId),
                            tenantId, run.savedRunId as String, run.windowStartDate, run.windowEndDate)
                    Map health = RunHealth.of(run + [startedDate: started, usualDurationMs: usual], now)
                    // DAR-BE-350. This automation's own last success is the reads' baseline (another automation on the
                    // same saved run, or a manual run, reads with other filters).
                    String previousRunResultId = AuditSettingsSupport.rows(ec, "darpan.reconciliation.ReconciliationAutomationExecution", tenantId)
                            .condition("automationId", automationId).condition("statusEnumId", AutomationExecutionSupport.STATUS_SUCCEEDED)
                            .condition("reconciliationRunResultId", EntityCondition.NOT_EQUAL, null)
                            .orderBy("-completedDate").limit(1).list().find()?.get("reconciliationRunResultId") as String
                    entry += [elapsedMs: started ? now.time - started.time : null, usualDurationMs: usual,
                              health: health.health, healthReason: health.reason,
                              steps: steps(ec, tenantId, run.reconciliationRunResultId as String, automationId, previousRunResultId)]
                    // DAR-BE-350. A peak period's read limits, so a run slowed on purpose says why.
                    ReadLimits limits = PeakReadPolicy.limitsFor(ec, tenantId)
                    if (limits != null && (limits.hotwaxSharePct != null || limits.netsuiteSerial)) {
                        entry.limits = [hotwaxSharePct: limits.hotwaxSharePct, netsuiteSerial: limits.netsuiteSerial]
                    }
                }
                active << entry
            }
        }
        // Running before queued, then by run name.
        return [checks      : checks.size(),
                asOf        : now,
                activeChecks: (active.sum { it.checks.size() } ?: 0) as int,
                active      : active.sort { a, b -> (b.state <=> a.state) ?: (a.run <=> b.run) }]
    }

    /**
     * DAR-BE-173. A running run's steps in run order, for the audit page to say what it is doing: [{stage, status: running
     * | done | failed (any other status lower-cased), records (so far, while running: the extract's page heartbeat), startedDate, completedDate,
     * system (an extract's system, from the automation's source on that side)}]. Both extracts run at once when the
     * sides are read in parallel, each with its own count.
     */
    static List<Map> steps(def ec, String tenantId, String runResultId, String automationId, String previousRunResultId = null) {
        if (!runResultId) return []
        Map<String, String> systemBySide = AuditSettingsSupport.rows(ec, "darpan.reconciliation.ReconciliationAutomationSource", tenantId)
                .condition("automationId", automationId).list().collectEntries { source ->
            [(source.fileSide as String): ReconciliationServices.resolveEnumLabel(ec, source.systemEnumId as String, source.systemEnumId as String)]
        }
        Map<String, String> sideOfStage = [(RunObservability.STAGE_EXTRACT_FILE1): "FILE_1", (RunObservability.STAGE_EXTRACT_FILE2): "FILE_2"]
        // DAR-BE-350. Each read's page count on the previous success (metricsJson.readCount, kept by endStep; recordCount is
        // the connector's own count, in other units): today's estimate. A read that never heartbeated has none.
        Map<String, Object> expectedByStage = previousRunResultId ? AuditSettingsSupport.rows(ec, "darpan.reconciliation.ReconciliationRunStep", tenantId)
                .condition("reconciliationRunResultId", previousRunResultId)
                .condition("stageCode", EntityCondition.IN, sideOfStage.keySet() as List)
                .condition("statusEnumId", RunObservability.STATUS_SUCCESS).orderBy("startedDate").list()
                // DAR-BE-394: qualified, and no inline cast. Groovy 5.0.3 fails a bare static call from a closure with an
                // inline-cast null ("argument type mismatch"); a read that never heartbeats keeps no metricsJson, which took
                // down get#AuditProgress and, through idle(), send#AuditRunUpdate whenever such an automation was mid-run.
                .collectEntries { [(it.stageCode as String): AuditProgress.readCountOf(it.metricsJson)] }.findAll { it.value != null } : [:]
        return AuditSettingsSupport.rows(ec, "darpan.reconciliation.ReconciliationRunStep", tenantId)
                .condition("reconciliationRunResultId", runResultId).orderBy(["stageSequence", "startedDate"]).list().collect { step ->
            String stage = step.stageCode as String
            Map out = [stage        : stage,
                       status       : STEP_STATUS[step.statusEnumId as String] ?: (step.statusEnumId as String)?.toLowerCase(),
                       records      : step.recordCount,
                       startedDate  : step.startedDate,
                       completedDate: step.completedDate,
                       // DAR-BE-350. When its last page landed; none for a step that never heartbeats (Shopify bulk).
                       movedDate    : step.heartbeatDate]
            String side = sideOfStage[stage]
            if (side) out.system = systemBySide[side]
            if (side && expectedByStage[stage] != null) out.expectedRecords = expectedByStage[stage]
            return out
        }
    }

    private static Long readCountOf(Object metricsJson) {
        String text = metricsJson?.toString()
        if (!text?.contains("readCount")) return null
        try { return (new groovy.json.JsonSlurper().parseText(text) as Map)?.readCount as Long }
        catch (Exception ignored) { return null }
    }

    private static final Map<String, String> STEP_STATUS =[(RunObservability.STATUS_RUNNING): "running",
            (RunObservability.STATUS_SUCCESS): "done", (RunObservability.STATUS_FAILED): "failed"].asImmutable()
}
