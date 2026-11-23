package darpan.migration

import darpan.facade.common.TenantScopedFinder

import java.sql.Timestamp

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-338. Darpan 3.4.0 retired the automation's own filter rows (DAR-BE-318 3b-2c) and its AUTOMATION_FILTER_TABLE
 * migration dropped the table. Automations keep their own copy again, so an edit to the run reaches them only through
 * sync#Automation. This rebuilds the copy where the drop ran: every automation side with no filter rows takes its run's
 * (the compare scope's RuleSetCompareSourceFilter rows of the same side), read for the automation's own tenant only.
 *
 * At the drop every automation's rows equalled its run's (drift check 2026-11-23), and an automation saved since has none,
 * so the run's rows are what each automation ran with. A side that already has rows is left alone; a second run writes none.
 * Where the drop never ran (an instance upgrading from before 3.4.0) the automation still has its own rows: nothing is written.
 *
 * Groovy, not XML actions: a cross-tenant admin migration over the global finder, as the retired backfill was.
 */
class AutomationFilterCopySupport {
    static final String DROP_MIGRATION_ID = "AUTOMATION_FILTER_TABLE"
    static final String NEVER_DROPPED = "table never dropped here"
    private static final String AUTO_FILTER = "darpan.reconciliation.ReconciliationAutomationSourceFilter"
    private static final String WHY = "DAR-BE-338 migration: rebuilds each automation's filter copy from its run, pinned to the automation's own tenant"

    /** [rowsWritten, sidesFilled, skipped]: a dry run counts what a run would write and writes nothing. */
    static Map<String, Object> copyFromRuns(def ec, boolean dryRun) {
        if (!MigrationSupervisorSupport.hasSucceeded(ec, DROP_MIGRATION_ID)) {
            return [rowsWritten: 0, sidesFilled: 0, skipped: NEVER_DROPPED]
        }
        int rowsWritten = 0
        int sidesFilled = 0
        Timestamp now = ec.user.nowTimestamp
        List sources = TenantScopedFinder.findGlobalUnscoped(ec, "darpan.reconciliation.ReconciliationAutomationSource", WHY)
                .orderBy("automationId,fileSide").useCache(false).list() ?: []
        for (def source : sources) {
            String automationId = normalize(source.automationId)
            String fileSide = normalize(source.fileSide)
            String tenantId = normalize(source.companyUserGroupId)
            if (!automationId || !fileSide || !tenantId) continue
            long own = TenantScopedFinder.findGlobalUnscoped(ec, AUTO_FILTER, WHY).condition("automationId", automationId)
                    .condition("fileSide", fileSide).condition("companyUserGroupId", tenantId).useCache(false).count()
            if (own > 0) continue
            def automation = TenantScopedFinder.findGlobalUnscoped(ec, "darpan.reconciliation.ReconciliationAutomation", WHY)
                    .condition("automationId", automationId).condition("companyUserGroupId", tenantId).useCache(false).list()?.find()
            String compareScopeId = normalize(automation?.compareScopeId)
            if (!compareScopeId) continue
            List runRows = TenantScopedFinder.findGlobalUnscoped(ec, "darpan.rule.RuleSetCompareSourceFilter", WHY)
                    .condition("compareScopeId", compareScopeId).condition("fileSide", fileSide)
                    .condition("companyUserGroupId", tenantId).orderBy("sequenceNum").useCache(false).list() ?: []
            if (!runRows) continue
            sidesFilled++
            for (def row : runRows) {
                rowsWritten++
                if (dryRun) continue
                // a write: TenantScopedFinder wraps reads only, so this stays a bare disableAuthz create (DisableAuthzRatchetTest)
                ec.service.sync().name("create#${AUTO_FILTER}".toString()).parameters([
                        automationId      : automationId,
                        fileSide          : fileSide,
                        sequenceNum       : row.sequenceNum,
                        fieldExpression   : row.fieldExpression,
                        operator          : row.operator,
                        filterValues      : row.filterValues,
                        companyUserGroupId: tenantId,
                        createdByUserId   : normalize(automation.createdByUserId),
                        createdDate       : now,
                        lastUpdatedDate   : now,
                ]).disableAuthz().call()
            }
        }
        return [rowsWritten: rowsWritten, sidesFilled: sidesFilled, skipped: null]
    }
}
