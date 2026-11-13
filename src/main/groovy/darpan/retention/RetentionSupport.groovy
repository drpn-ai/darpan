package darpan.retention

import darpan.admin.AdminAuditSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import groovy.transform.CompileDynamic
import org.moqui.entity.EntityCondition
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Date
import java.sql.Timestamp

/**
 * DAR-BE-266. Deletes rows older than a per-table retention period, one tenant at a time, in small batches.
 *
 * <p><strong>Off until configured.</strong> Each table's period is a system property (the deployment's other limits
 * are too, e.g. darpan.reconciliation.automation.maxRetryCount). Unset, blank, not a whole number, or below 1 means
 * keep forever, so a deploy deletes nothing until a period is set (DAR-OPS-017 decision 7).</p>
 *
 * <p><strong>Tenant-safe.</strong> A sweep runs as the scheduler, with no active tenant, so every read is a
 * {@code findGlobalUnscoped} with an explicit {@code companyUserGroupId} condition for the one tenant being swept, and
 * children are deleted only under parent ids that read selected. A row with no tenant stamp is never touched.</p>
 *
 * <p><strong>Short locks.</strong> Each batch of at most {@code darpan.retention.batchSize} parents (default 500) is
 * deleted, with its children, in its own transaction.</p>
 *
 * <p>Groovy, not XML actions: the sweep needs the unscoped finder with a reason for each read, per-batch new
 * transactions and keyset paging, none of which XML actions express without bare disableAuthz.</p>
 */
@CompileDynamic
class RetentionSupport {
    private static final Logger logger = LoggerFactory.getLogger(RetentionSupport.class)

    static final String AUDIT_FINDING = "AUDIT_FINDING"
    static final String RUN_RESULT = "RUN_RESULT"
    static final String AGENT_MESSAGE = "AGENT_MESSAGE"
    static final String AGENT_QUERY_LOG = "AGENT_QUERY_LOG"

    /** Table key to its system property; the property's value is the period in days. */
    static final Map<String, String> PERIOD_PROPERTIES = [
            (AUDIT_FINDING)  : "darpan.retention.auditFinding.days",
            (RUN_RESULT)     : "darpan.retention.runResult.days",
            (AGENT_MESSAGE)  : "darpan.retention.agentMessage.days",
            (AGENT_QUERY_LOG): "darpan.retention.agentQueryLog.days",
    ].asImmutable()
    static final String BATCH_SIZE_PROPERTY = "darpan.retention.batchSize"
    static final int DEFAULT_BATCH_SIZE = 500

    static final String KEEP_FOREVER = "KEEP_FOREVER"
    static final String INVALID_PERIOD = "INVALID_PERIOD"
    static final String DRY_RUN = "DRY_RUN"
    static final String DELETED = "DELETED"

    private static final String REASON = "retention sweep (DAR-BE-266): system job, explicit companyUserGroupId condition for the tenant swept"
    private static final long DAY_MILLIS = 86_400_000L
    private static final List<String> UNFINISHED_RUN_STATUSES = ["AUT_STAT_PENDING", "AUT_STAT_RUNNING"]

    /**
     * The period for each table as configured now, or as the dry run's override says. Each entry:
     * [table, property, periodDays (Integer or null), status KEEP_FOREVER | INVALID_PERIOD | null (set), rawValue].
     */
    static Map<String, Map> periods(Map overrides = null) {
        Map<String, Map> out = [:]
        PERIOD_PROPERTIES.each { String table, String property ->
            boolean overridden = overrides != null && overrides.containsKey(table)
            Object raw = overridden ? overrides.get(table) : System.getProperty(property)
            String text = raw?.toString()?.trim()
            Integer days = (text && text.isInteger()) ? text.toInteger() : null
            String status = !text ? KEEP_FOREVER : (days == null || days < 1 ? INVALID_PERIOD : null)
            out[table] = [table: table, property: property, rawValue: text, periodDays: status ? null : days, status: status,
                          overridden: overridden]
        }
        return out
    }

    static int batchSize() {
        String text = System.getProperty(BATCH_SIZE_PROPERTY)?.trim()
        return text?.isInteger() && text.toInteger() > 0 ? Math.min(text.toInteger(), 5000) : DEFAULT_BATCH_SIZE
    }

    /**
     * Counts (dryRun) or deletes what each table's period expires, for one tenant or every tenant.
     * A dry run may override periods by table key (e.g. [AUDIT_FINDING: 395]) to preview a period before setting it;
     * a real run only ever uses the configured ones.
     */
    static Map run(def ec, String tenantId, boolean dryRun, Map periodOverrides = null) {
        if (!dryRun && periodOverrides) throw new IllegalArgumentException("A real retention run uses the configured periods only")
        Map<String, Map> configured = periods(dryRun ? periodOverrides : null)
        List<String> tenants = tenantId?.trim() ? [tenantId.trim()] : allTenants(ec)
        Timestamp now = new Timestamp(System.currentTimeMillis())
        int batch = batchSize()
        List<Map> tables = []
        configured.values().each { Map p ->
            Map row = new LinkedHashMap(p)
            row.rows = 0L
            row.dependentRows = [:]
            row.byTenant = [:]
            if (p.periodDays == null) {
                tables << row
                return
            }
            Timestamp cutoff = new Timestamp(now.time - ((p.periodDays as long) * DAY_MILLIS))
            row.cutoff = cutoff
            row.status = dryRun ? DRY_RUN : DELETED
            tenants.each { String t ->
                Map counts = sweep(ec, p.table as String, t, cutoff, dryRun, batch)
                long n = counts.remove("_rows") as long
                if (n > 0) row.byTenant[t] = n
                row.rows = (row.rows as long) + n
                counts.each { String entity, Object c -> row.dependentRows[entity] = ((row.dependentRows[entity] ?: 0L) as long) + (c as long) }
            }
            tables << row
        }
        boolean anySet = tables.any { it.periodDays != null }
        if (!dryRun && anySet) {
            String summary = tables.collect { "${it.table} ${it.periodDays ?: 'forever'}${it.periodDays ? 'd' : ''}: ${it.rows}" }.join("; ")
            AdminAuditSupport.recordRun(ec, "retention.RetentionServices.run#Retention", "Retention", tenantId?.trim() ?: "ALL",
                    "Retention run over ${tenants.size()} tenant(s), batch ${batch}: ${summary}.".toString())
        }
        logger.info("Retention ${dryRun ? 'dry run' : 'run'} over ${tenants.size()} tenant(s): " +
                tables.collect { "${it.table}=${it.status}/${it.rows}" }.join(", "))
        return [dryRun: dryRun, tenantCount: tenants.size(), batchSize: batch, tables: tables]
    }

    static List<String> allTenants(def ec) {
        return TenantScopedFinder.findGlobalUnscoped(ec, "moqui.security.UserGroup", "retention sweep: list every tenant")
                .condition("groupTypeEnumId", TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID)
                .selectField("userGroupId").orderBy("userGroupId").useCache(false).list()*.userGroupId as List<String>
    }

    /** One table for one tenant: [_rows: parent rows, <child entity>: rows, ...]. */
    static Map sweep(def ec, String table, String tenantId, Timestamp cutoff, boolean dryRun, int batch) {
        switch (table) {
            case AUDIT_FINDING:
                return sweepParents(ec, tenantId, dryRun, batch, "darpan.audit.AuditFinding", "findingId",
                        { find -> find.condition("lastSeenDate", EntityCondition.LESS_THAN, new Date(cutoff.time)) },
                        [[entity: "darpan.audit.AuditFindingRecord", fk: "findingId", tenantStamped: true],
                         [entity: "darpan.audit.AuditFindingCondition", fk: "findingId", tenantStamped: true],
                         [entity: "darpan.audit.AuditFindingEvent", fk: "findingId", tenantStamped: true]], [])
            case RUN_RESULT:
                return sweepParents(ec, tenantId, dryRun, batch, "darpan.reconciliation.ReconciliationRunResult", "reconciliationRunResultId",
                        { find -> find.condition("createdDate", EntityCondition.LESS_THAN, cutoff)
                                .condition("statusEnumId", EntityCondition.NOT_IN, UNFINISHED_RUN_STATUSES) },
                        [[entity: "darpan.reconciliation.ReconciliationRunStep", fk: "reconciliationRunResultId"],
                         [entity: "darpan.reconciliation.SlackPostedMessage", fk: "reconciliationRunResultId"],
                         [entity: "darpan.reconciliation.ReconciliationRunNotifySubscription", fk: "reconciliationRunResultId"]],
                        // An automation execution keeps its own counts and history; it only forgets which run result it made.
                        [[entity: "darpan.reconciliation.ReconciliationAutomationExecution", fk: "reconciliationRunResultId"]])
            case AGENT_QUERY_LOG:
                return sweepParents(ec, tenantId, dryRun, batch, "darpan.agent.AgentQueryLog", "agentQueryLogId",
                        { find -> find.condition("createdDate", EntityCondition.LESS_THAN, cutoff) }, [], [])
            case AGENT_MESSAGE:
                return sweepAgentMessages(ec, tenantId, cutoff, dryRun, batch)
            default:
                throw new IllegalArgumentException("Unknown retention table ${table}")
        }
    }

    /**
     * Keyset pages of expired parent ids for the tenant; each page's children are deleted (or counted) under those ids,
     * nullable references to them are cleared, then the parents go, all in one new transaction per page.
     */
    private static Map sweepParents(def ec, String tenantId, boolean dryRun, int batch, String entity, String pk,
                                    Closure<Object> expired, List<Map> children, List<Map> references) {
        Map counts = [_rows: 0L]
        (children + references).each { counts[it.entity] = 0L }
        String lastId = null
        while (true) {
            def find = TenantScopedFinder.findGlobalUnscoped(ec, entity, REASON).condition("companyUserGroupId", tenantId)
            expired.call(find)
            if (lastId != null) find.condition(pk, EntityCondition.GREATER_THAN, lastId)
            List<String> ids = find.selectField(pk).orderBy(pk).limit(batch).useCache(false).list().collect { it.get(pk) as String }
            if (!ids) break
            lastId = ids.last()
            Closure work = {
                children.each { Map c ->
                    def cf = childFind(ec, c, ids, tenantId)
                    counts[c.entity] = (counts[c.entity] as long) + (dryRun ? cf.count() : cf.deleteAll())
                }
                references.each { Map r ->
                    def rf = TenantScopedFinder.findGlobalUnscoped(ec, r.entity as String, REASON)
                            .condition("companyUserGroupId", tenantId).condition(r.fk as String, EntityCondition.IN, ids)
                    counts[r.entity] = (counts[r.entity] as long) + (dryRun ? rf.count() : rf.updateAll([(r.fk as String): null]))
                }
                if (!dryRun) {
                    TenantScopedFinder.findGlobalUnscoped(ec, entity, REASON).condition("companyUserGroupId", tenantId)
                            .condition(pk, EntityCondition.IN, ids).deleteAll()
                }
            }
            if (dryRun) work.call() else ec.transaction.runRequireNew(300, "Retention batch of ${entity} for ${tenantId}".toString(), work)
            counts._rows = (counts._rows as long) + ids.size()
            if (ids.size() < batch) break
        }
        return counts
    }

    private static def childFind(def ec, Map child, List<String> ids, String tenantId) {
        def find = TenantScopedFinder.findGlobalUnscoped(ec, child.entity as String, REASON).condition(child.fk as String, EntityCondition.IN, ids)
        // Children whose tenant stamp is required are pinned to the tenant too: one stamped for another tenant stays,
        // and its parent's delete then fails loudly on the foreign key instead of crossing tenants.
        if (child.tenantStamped) find.condition("companyUserGroupId", tenantId)
        return find
    }

    /**
     * A transcript expires as a whole: a conversation's messages go once the conversation started before the cutoff and
     * no model run in it started on or after the cutoff. The conversation row and its AgentTurn rows stay: they hold no
     * transcript text, and AgentTurn is the AI spend ledger grants are measured against.
     */
    private static Map sweepAgentMessages(def ec, String tenantId, Timestamp cutoff, boolean dryRun, int batch) {
        Map counts = [_rows: 0L]
        String lastConversation = null
        while (true) {
            def conv = TenantScopedFinder.findGlobalUnscoped(ec, "darpan.agent.AgentConversation", REASON)
                    .condition("companyUserGroupId", tenantId).condition("createdDate", EntityCondition.LESS_THAN, cutoff)
            if (lastConversation != null) conv.condition("conversationId", EntityCondition.GREATER_THAN, lastConversation)
            List<String> page = conv.selectField("conversationId").orderBy("conversationId").limit(batch).useCache(false).list()
                    .collect { it.conversationId as String }
            if (!page) break
            lastConversation = page.last()
            Set<String> active = TenantScopedFinder.findGlobalUnscoped(ec, "darpan.agent.AgentTurn", REASON)
                    .condition("companyUserGroupId", tenantId).condition("conversationId", EntityCondition.IN, page)
                    .condition("startedDate", EntityCondition.GREATER_THAN_EQUAL_TO, cutoff)
                    .selectField("conversationId").distinct(true).useCache(false).list().collect { it.conversationId as String } as Set<String>
            List<String> expired = page.findAll { !active.contains(it) }
            if (expired) {
                Closure<Object> messages = {
                    TenantScopedFinder.findGlobalUnscoped(ec, "darpan.agent.AgentMessage", REASON)
                            .condition("companyUserGroupId", tenantId).condition("conversationId", EntityCondition.IN, expired)
                }
                if (dryRun) {
                    counts._rows = (counts._rows as long) + (messages.call().count() as long)
                } else {
                    while (true) {
                        List<String> ids = messages.call().selectField("messageId").orderBy("messageId").limit(batch).useCache(false).list()
                                .collect { it.messageId as String }
                        if (!ids) break
                        ec.transaction.runRequireNew(300, "Retention batch of darpan.agent.AgentMessage for ${tenantId}".toString()) {
                            TenantScopedFinder.findGlobalUnscoped(ec, "darpan.agent.AgentMessage", REASON)
                                    .condition("companyUserGroupId", tenantId).condition("messageId", EntityCondition.IN, ids).deleteAll()
                        }
                        counts._rows = (counts._rows as long) + ids.size()
                        if (ids.size() < batch) break
                    }
                }
            }
            if (page.size() < batch) break
        }
        return counts
    }
}
