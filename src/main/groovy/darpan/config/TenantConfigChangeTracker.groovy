package darpan.config

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
    static final String EECA_LOCATION = "component://darpan/entity/TenantConfig.eecas.xml"

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
            "darpan.reconciliation.ReconciliationAutomationSource"      : ["darpan.reconciliation.ReconciliationAutomation", "automationId"],
            "darpan.reconciliation.ReconciliationAutomationSourceFilter": ["darpan.reconciliation.ReconciliationAutomation", "automationId"],
            "darpan.reconciliation.ReconciliationRun"                   : ["darpan.reconciliation.Reconciliation", "reconciliationId"],
    ].asImmutable()

    /**
     * Columns a version does not hold, so an update that changes only these is no config change: when an automation last
     * and next fires and whether it is on (every scheduled run writes the first two), and the update stamps.
     */
    static final Set<String> STAMPS = (["lastUpdatedStamp", "lastUpdatedDate"] as Set<String>).asImmutable()
    static final Map<String, Set<String>> OPERATIONAL_FIELDS = [
            "darpan.reconciliation.ReconciliationAutomation": (["isActive", "nextScheduledFireTime", "lastScheduledFireTime"] as Set<String>),
    ].asImmutable()

    /** Called by the EECA rules with their entity's name (a write may hand over a plain Map). Never throws. */
    static void note(def ec, String entityName, Map entityValue, String operation = null) {
        try {
            if (operation == "update" && onlyOperational(entityName, entityValue)) return
            String tenantId = tenantOf(ec, entityName, entityValue)
            if (!tenantId) return
            String userId = ec.user.userId
            if (!ec.transaction.isTransactionInPlace()) {
                // No transaction to wait for: the write is already done.
                ec.factory.getService().async().name(NOTE_SERVICE).parameters([tenantId: tenantId, userId: userId]).call()
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
    static String tenantOf(def ec, String entityName, Map fields) {
        if (fields == null) return null
        Object own = valueOf(fields, "companyUserGroupId")
        if (own) return own as String
        List<String> parent = PARENTS[entityName]
        if (parent == null) return null
        Object parentId = valueOf(fields, parent[1])
        if (!parentId) return null
        def parentRow = ec.entity.find(parent[0]).condition(parent[1], parentId).disableAuthz().useCache(false).one()
        return parentRow == null ? null : tenantOf(ec, parent[0], parentRow)
    }

    /**
     * True when an update changes only operational columns. Only an EntityValue read from the database knows which
     * fields changed; any other update counts as a change (the snapshot then finds nothing new and writes no version).
     */
    static boolean onlyOperational(String entityName, Map fields) {
        Set<String> operational = OPERATIONAL_FIELDS[entityName]
        if (!operational || !(fields instanceof org.moqui.entity.EntityValue)) return false
        def value = (org.moqui.entity.EntityValue) fields
        List<String> changed = value.getEntityDefinition().getAllFieldNames().findAll { String f -> value.isFieldModified(f) }
        return changed && changed.every { it in operational || it in STAMPS }
    }

    /** A field's value without EntityValue.get's throw on a field the entity does not declare. */
    private static Object valueOf(Map fields, String name) {
        if (fields instanceof org.moqui.entity.EntityValue) {
            return ((org.moqui.entity.EntityValue) fields).isField(name) ? fields.get(name) : null
        }
        return fields.get(name)
    }

    /** The entities TenantConfig.eecas.xml watches, read from the file itself so a test can hold it to the transfer specs. */
    static List<String> watchedEntities(def ec) {
        def root = new groovy.xml.XmlSlurper().parseText(ec.resource.getLocationText(EECA_LOCATION, false))
        return root.eeca.findAll { it.@'on-create' == 'true' && it.@'on-update' == 'true' && it.@'on-delete' == 'true' }
                .collect { it.@entity.toString() }.unique().sort()
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
                    ecf.getService().async().name(NOTE_SERVICE).parameters([tenantId: tenantId, userId: userId]).call()
                } catch (Throwable t) {
                    logger.warn("Could not mark tenant ${tenantId}'s config pending after a commit", t)
                }
            }
        }
    }
}
