package darpan.config

import darpan.facade.common.TenantScopedFinder
import groovy.transform.CompileDynamic
import jakarta.transaction.Status
import jakarta.transaction.Synchronization
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * DAR-BE-160 phase A. Notices that a tenant's audit config changed, so a version can be kept without every save service
 * having to remember to ask for one.
 *
 * <p>entity/TenantConfig.eecas.xml calls {@link #note} before every create, update and delete of a table a config version
 * holds. note() only records the tenant on one Synchronization per transaction; after the transaction COMMITS, one
 * async call per tenant marks it pending (TenantConfigPending), in its own thread and transaction. So a save that writes
 * forty rows marks its tenant once, nothing is held locked while the save runs, a rolled-back save marks nothing, and
 * this bookkeeping can never fail a save: every error is logged and swallowed.</p>
 */
@CompileDynamic
class TenantConfigChangeTracker {

    private static final Logger logger = LoggerFactory.getLogger(TenantConfigChangeTracker.class)

    static final String SYNC_NAME = "darpan.config.TenantConfigChanges"
    static final String NOTE_SERVICE = "config.TenantConfigServices.note#TenantConfigChange"

    /** Marks a tenant pending in its own thread and transaction: the note service, called async. */
    static final Closure DEFAULT_DISPATCH = { def ecf, Map params -> ecf.getService().async().name(NOTE_SERVICE).parameters(params).call() }
    /**
     * How a mark is sent (S99-211 F32). A test swaps in a closure that records the marks, so it can drain them or assert
     * that none was sent, instead of sleeping for an async thread; resetForTests puts the async call back.
     */
    static Closure dispatch = DEFAULT_DISPATCH

    static void resetForTests() {
        dispatch = DEFAULT_DISPATCH
    }

    /**
     * Where a row finds its tenant when it has no companyUserGroupId (or an old row left it empty): [parent entity, the
     * parent's primary key, which the child carries under the same name].
     */
    static final Map<String, List<String>> PARENTS = [
            "darpan.reconciliation.NsSuiteQlSourceQueryField": ["darpan.reconciliation.NsSuiteQlSourceQuery", "nsSuiteQlSourceQueryId"],
            "darpan.rule.Rule"                               : ["darpan.rule.RuleSet", "ruleSetId"],
            "darpan.rule.RuleSetCompareScope"                : ["darpan.rule.RuleSet", "ruleSetId"],
            "darpan.rule.RuleSetCompareSource"               : ["darpan.rule.RuleSetCompareScope", "compareScopeId"],
            "darpan.rule.RuleSetCompareSourceKeyField"       : ["darpan.rule.RuleSetCompareScope", "compareScopeId"],
            "darpan.rule.RuleSetCompareSourceFilter"         : ["darpan.rule.RuleSetCompareScope", "compareScopeId"],
            "darpan.rule.RuleSetConclusionRule"              : ["darpan.rule.RuleSetCompareScope", "compareScopeId"],
            "darpan.rule.RuleSetConclusionCondition"         : ["darpan.rule.RuleSetCompareScope", "compareScopeId"],
            "darpan.audit.AuditStatementCheck"               : ["darpan.audit.AuditStatement", "statementId"],
            "darpan.audit.AuditReasonPriority"               : ["darpan.audit.AuditStatement", "statementId"],
            "darpan.reconciliation.ReconciliationAutomationSource"      : ["darpan.reconciliation.ReconciliationAutomation", "automationId"],
            "darpan.reconciliation.ReconciliationAutomationSourceFilter": ["darpan.reconciliation.ReconciliationAutomation", "automationId"],
            "darpan.reconciliation.ReconciliationRun"                   : ["darpan.reconciliation.Reconciliation", "reconciliationId"],
            "jexl.script.UserScriptDeploymentDelivery"                  : ["jexl.script.UserScriptDeployment", "scriptDeploymentId"],
    ].asImmutable()

    /**
     * Columns a version does not hold, so an update that changes only these is no config change: when an automation last
     * and next fires and whether it is on (every scheduled run writes the first two), a script deployment's run state
     * (DAR-BE-237), and the update stamps.
     */
    static final Set<String> STAMPS = (["lastUpdatedStamp", "lastUpdatedDate"] as Set<String>).asImmutable()
    static final Map<String, Set<String>> OPERATIONAL_FIELDS = [
            // DAR-BE-318: generate#PairFactType writes this id on a replay; it is derived state, not config (never in a version).
            "darpan.audit.AuditCheck"                       : (["pairFactTypeId"] as Set<String>),
            "darpan.reconciliation.ReconciliationAutomation": (["isActive", "nextScheduledFireTime", "lastScheduledFireTime"] as Set<String>),
            // DAR-BE-237: every run of a script deployment writes when it ran, how it went and when it runs next.
            "jexl.script.UserScriptDeployment"              : (["isActive", "nextRunDate", "lastRunDate", "lastRunStatus", "lastRunMessage",
                                                                "lastRunUnits", "ownerUserId"] as Set<String>),
            // DAR-BE-334: every run writes each delivery's last outcome
            "jexl.script.UserScriptDeploymentDelivery"      : (["lastOutcome", "lastMessage"] as Set<String>),
    ].asImmutable()

    /** Called by the EECA rules with their entity's name (a write may hand over a plain Map). Never throws. */
    static void note(def ec, String entityName, Map entityValue, String operation = null) {
        try {
            if (operation == "update" && onlyOperational(ec, entityName, entityValue)) return
            String tenantId = tenantOf(ec, entityName, entityValue)
            if (!tenantId) return
            String userId = ec.user.userId
            if (!ec.transaction.isTransactionInPlace()) {
                // No transaction to wait for: the write is already done.
                dispatch.call(ec.factory, [tenantId: tenantId, userId: userId])
                return
            }
            Changes changes = (Changes) ec.transaction.getActiveSynchronization(SYNC_NAME)
            if (changes == null) {
                changes = new Changes(ec.factory)
                ec.transaction.putAndEnlistActiveSynchronization(SYNC_NAME, changes)
            }
            changes.add(tenantId, userId)
        } catch (Throwable t) {
            logger.warn("Could not note a config change on ${entityName}; its tenant's next version waits for another save", t)
        }
    }

    /** The row's own companyUserGroupId, else its parent's (PARENTS); null when neither names one. */
    private static String tenantOf(def ec, String entityName, Map fields) {
        if (fields == null) return null
        Object own = valueOf(fields, "companyUserGroupId")
        if (own) return own as String
        List<String> parent = PARENTS[entityName]
        if (parent == null) return null
        Object parentId = valueOf(fields, parent[1])
        if (!parentId) return null
        def parentRow = TenantScopedFinder.findGlobalUnscoped(ec, parent[0],
                "DAR-BE-160 config change tracking: a child row names its parent; the tenant is read from that parent")
                .condition(parent[1], parentId).useCache(false).one()
        return parentRow == null ? null : tenantOf(ec, parent[0], parentRow)
    }

    /**
     * True when an update changes only operational columns. Only an EntityValue read from the database knows which
     * fields changed; any other update counts as a change (the snapshot then finds nothing new and writes no version).
     */
    private static boolean onlyOperational(def ec, String entityName, Map fields) {
        Set<String> operational = OPERATIONAL_FIELDS[entityName]
        if (!operational || !(fields instanceof org.moqui.entity.EntityValue)) return false
        def value = (org.moqui.entity.EntityValue) fields
        // DAR-BE-237: Moqui's modified flags do not always say what an update changed (a TenantScriptDeployment read and
        // set back marks every field), so the stored row says: the rule runs before the write, so it is still the old one.
        def stored = TenantScopedFinder.findGlobalUnscoped(ec, entityName, "DAR-BE-160 config change tracking: the row being updated, before the write")
                .condition(value.getPrimaryKeys()).useCache(false).one()
        if (stored == null) return false
        List<String> changed = value.getEntityDefinition().getAllFieldNames()
                .findAll { String f -> value.isFieldModified(f) && value.get(f) != stored.get(f) }
        return changed.every { it in operational || it in STAMPS }
    }

    /** A field's value without EntityValue.get's throw on a field the entity does not declare. */
    private static Object valueOf(Map fields, String name) {
        if (fields instanceof org.moqui.entity.EntityValue) {
            return ((org.moqui.entity.EntityValue) fields).isField(name) ? fields.get(name) : null
        }
        return fields.get(name)
    }

    /** The tenants one transaction changed, marked pending once it commits. */
    static class Changes implements Synchronization {
        final def ecf
        final Map<String, String> lastUserByTenant = new LinkedHashMap<String, String>()

        Changes(def ecf) {
            this.ecf = ecf
        }

        synchronized void add(String tenantId, String userId) {
            lastUserByTenant.put(tenantId, userId ?: lastUserByTenant.get(tenantId))
        }

        @Override
        void beforeCompletion() { }

        @Override
        void afterCompletion(int status) {
            if (status != Status.STATUS_COMMITTED) return
            Map<String, String> marks
            synchronized (this) { marks = new LinkedHashMap<String, String>(lastUserByTenant) }
            marks.each { String tenantId, String userId ->
                try {
                    dispatch.call(ecf, [tenantId: tenantId, userId: userId])
                } catch (Throwable t) {
                    logger.warn("Could not mark tenant ${tenantId}'s config pending after a commit", t)
                }
            }
        }
    }
}
