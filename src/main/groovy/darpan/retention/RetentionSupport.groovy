package darpan.retention

import darpan.admin.AdminAuditSupport
import darpan.config.DarpanSettings
import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.facade.reconciliation.RunObservability
import groovy.transform.CompileDynamic
import org.moqui.entity.EntityCondition
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Date
import java.sql.Timestamp

/**
 * DAR-BE-266, DAR-BE-306, DAR-BE-328. Deletes rows older than a per-table retention period, one tenant at a time, in small batches.
 *
 * <p><strong>Off until configured.</strong> Each table's instance period is an optional setting
 * ({@code darpan.retention.<table>.days}, edited in production through admin.RetentionAdminServices). Unset means keep
 * forever, so a deploy deletes nothing until a period is set (DAR-OPS-017 decision 7). Sync pulse samples
 * (SYNC_PULSE_SAMPLE, DAR-BE-328) are the one exception: their setting defaults to 30 days, so they never pile up.</p>
 *
 * <p><strong>Per tenant.</strong> A tenant may carry its own period for a table (TenantRetentionPeriod): its days, or
 * "forever" (an empty periodDays), and it wins over the instance period for that tenant's rows only. No row follows the
 * instance period.</p>
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
    static final String SYNC_PULSE_SAMPLE = "SYNC_PULSE_SAMPLE"

    /**
     * Table key to the setting that holds its instance period in days (optional: unset = keep forever). SYNC_PULSE_SAMPLE's
     * setting has a 30-day default in the settings catalog (DAR-BE-328), so samples never pile up unbounded.
     */
    static final Map<String, String> TABLE_SETTINGS = [
            (AUDIT_FINDING)  : "darpan.retention.auditFinding.days",
            (RUN_RESULT)     : "darpan.retention.runResult.days",
            (AGENT_MESSAGE)  : "darpan.retention.agentMessage.days",
            (AGENT_QUERY_LOG): "darpan.retention.agentQueryLog.days",
            (SYNC_PULSE_SAMPLE): "darpan.retention.syncPulseSample.days",
    ].asImmutable()
    static final String BATCH_SIZE_SETTING = "darpan.retention.batchSize"
    static final String TENANT_PERIOD_ENTITY = "darpan.retention.TenantRetentionPeriod"

    static final String KEEP_FOREVER = "KEEP_FOREVER"
    static final String DRY_RUN = "DRY_RUN"
    static final String DELETED = "DELETED"
    static final String TENANT = "TENANT"
    static final String INSTANCE = "INSTANCE"

    private static final String REASON = "retention sweep (DAR-BE-266): system job, explicit companyUserGroupId condition for the tenant swept"
    private static final String PERIOD_REASON = "retention sweep (DAR-BE-306): the tenant's own period rows, explicit tenantUserGroupId condition"
    private static final long DAY_MILLIS = 86_400_000L
    /** A run result still being written: never swept, however old (a run result's status is RunObservability's). */
    private static final List<String> UNFINISHED_RUN_STATUSES = [RunObservability.STATUS_PENDING, RunObservability.STATUS_RUNNING]

    /**
     * The period of each table for one tenant (or, with no tenantId, for the instance): the tenant's own row when it has one,
     * else the instance setting. {@code instanceOverrides} and {@code tenantOverrides} are a dry run's what-ifs by table key
     * (days, "forever", and for a tenant "inherit"); a tenant override wins over the tenant's stored row. Each entry:
     * [table, property (the instance setting key), periodDays (Integer, null = keep forever), status KEEP_FOREVER | null,
     * source TENANT | INSTANCE, overridden (the instance value is a what-if)].
     */
    static Map<String, Map> periods(def ec, String tenantId, Map instanceOverrides = null, Map tenantOverrides = null) {
        Map<String, Integer> stored = [:]
        Set<String> storedTables = [] as Set
        if (tenantId?.trim()) {
            TenantScopedFinder.findGlobalUnscoped(ec, TENANT_PERIOD_ENTITY, PERIOD_REASON).condition("tenantUserGroupId", tenantId.trim())
                    .useCache(false).list().each { row ->
                storedTables << (row.tableKey as String)
                stored[row.tableKey as String] = storedDays(tenantId.trim(), row.tableKey as String, row.periodDays)
            }
        }
        Map<String, Map> out = [:]
        TABLE_SETTINGS.each { String table, String setting ->
            boolean tenantHasOwn = false
            Integer days = null
            if (tenantId?.trim()) {
                if (tenantOverrides != null && tenantOverrides.containsKey(table)) {
                    Map p = RetentionPeriodRules.parse(table, tenantOverrides.get(table), true)
                    if (p.error) throw new IllegalArgumentException(p.error as String)
                    if (!p.inherit) { tenantHasOwn = true; days = p.days as Integer }
                } else if (storedTables.contains(table)) {
                    tenantHasOwn = true
                    days = stored[table]
                }
            }
            boolean whatIf = false
            if (!tenantHasOwn) {
                if (instanceOverrides != null && instanceOverrides.containsKey(table)) {
                    Map p = RetentionPeriodRules.parse(table, instanceOverrides.get(table), false)
                    if (p.error) throw new IllegalArgumentException(p.error as String)
                    days = p.days as Integer
                    whatIf = true
                } else {
                    // OC188: strict, so a table that has not loaded since the restart never answers a catalog default (30 days of
                    // sync pulse samples where 90 are set) to the sweep
                    days = DarpanSettings.optionalIntValueStrict(setting)
                }
            }
            out[table] = [table: table, property: setting, periodDays: days, status: days == null ? KEEP_FOREVER : null,
                          source: tenantHasOwn ? TENANT : INSTANCE, overridden: whatIf]
        }
        return out
    }

    private static final Set<String> warnedStored = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Test-harness reset: forgets which stored periods were already warned about. Never called from src/main. */
    static void resetForTests() {
        try { warnedStored.clear() } catch (Throwable ignored) { }
    }

    /**
     * A tenant row written outside the service (data load, DB console) may hold a period the service would refuse. A value outside
     * 1 to 3650 days must never reach the cutoff maths (0 or less puts the cutoff at or after now and deletes today's rows too):
     * it counts as keep forever, with one WARN per tenant, table and value.
     */
    private static Integer storedDays(String tenantId, String table, Object raw) {
        if (raw == null) return null
        long days = (raw as Number).longValue()
        if (days >= RetentionPeriodRules.MIN_DAYS && days <= RetentionPeriodRules.MAX_DAYS) return days as int
        if (warnedStored.size() < 1000 && warnedStored.add(tenantId + "|" + table + "|" + days)) {
            logger.warn("Retention: tenant {} table {} has a stored period of {} days (valid is {} to {}); keeping the table forever for it",
                    tenantId, table, days, RetentionPeriodRules.MIN_DAYS, RetentionPeriodRules.MAX_DAYS)
        }
        return null
    }

    static int batchSize() { return DarpanSettings.intValue(BATCH_SIZE_SETTING) }

    /**
     * Counts (dryRun) or deletes what each table's period expires, for one tenant or every tenant, each tenant under its own
     * effective period. A dry run may override the instance periods by table key (e.g. [AUDIT_FINDING: 395]) and, for one
     * tenant, that tenant's (tenantPeriodOverrides, needs tenantId) to preview a period before setting it; a real run only
     * ever uses the stored ones.
     */
    static Map run(def ec, String tenantId, boolean dryRun, Map periodOverrides = null, Map tenantPeriodOverrides = null) {
        if (!dryRun && (periodOverrides || tenantPeriodOverrides)) throw new IllegalArgumentException("A real retention run uses the stored periods only")
        if (tenantPeriodOverrides && !tenantId?.trim()) throw new IllegalArgumentException("tenantPeriodDays needs a tenantUserGroupId")
        Map<String, Map> instance = periods(ec, null, periodOverrides)
        List<String> tenants = tenantId?.trim() ? [tenantId.trim()] : allTenants(ec)
        Map<String, Map<String, Map>> effective = tenants.collectEntries { String t ->
            [(t): periods(ec, t, periodOverrides, t == tenantId?.trim() ? tenantPeriodOverrides : null)]
        }
        Timestamp now = new Timestamp(System.currentTimeMillis())
        int batch = batchSize()
        List<Map> tables = []
        instance.values().each { Map p ->
            Map row = new LinkedHashMap(p)
            row.remove("source")
            row.rows = 0L
            row.dependentRows = [:]
            row.byTenant = [:]
            row.byTenantPeriod = [:]
            if (p.periodDays != null) row.cutoff = new Timestamp(now.time - ((p.periodDays as long) * DAY_MILLIS))
            boolean anyPeriod = p.periodDays != null
            tenants.each { String t ->
                Map eff = effective[t][p.table as String]
                if (eff.source == TENANT) row.byTenantPeriod[t] = eff.periodDays
                if (eff.periodDays == null) return
                anyPeriod = true
                Timestamp cutoff = new Timestamp(now.time - ((eff.periodDays as long) * DAY_MILLIS))
                Map counts = sweep(ec, p.table as String, t, cutoff, dryRun, batch)
                long n = counts.remove("_rows") as long
                if (n > 0) row.byTenant[t] = n
                row.rows = (row.rows as long) + n
                counts.each { String entity, Object c -> row.dependentRows[entity] = ((row.dependentRows[entity] ?: 0L) as long) + (c as long) }
            }
            if (anyPeriod) row.status = dryRun ? DRY_RUN : DELETED
            tables << row
        }
        boolean anySet = tables.any { it.status != KEEP_FOREVER }
        if (!dryRun && anySet) {
            String summary = tables.collect { "${it.table} ${it.periodDays ?: 'forever'}${it.periodDays ? 'd' : ''}${it.byTenantPeriod ? " (+${it.byTenantPeriod.size()} tenant period(s))" : ''}: ${it.rows}" }.join("; ")
            AdminAuditSupport.recordRun(ec, "retention.RetentionServices.run#Retention", "Retention", tenantId?.trim() ?: "ALL",
                    "Retention run over ${tenants.size()} tenant(s), batch ${batch}: ${summary}.".toString())
        }
        logger.info("Retention ${dryRun ? 'dry run' : 'run'} over ${tenants.size()} tenant(s): " +
                tables.collect { "${it.table}=${it.status}/${it.rows}" }.join(", "))
        return [dryRun: dryRun, tenantCount: tenants.size(), batchSize: batch, tables: tables]
    }

    private static List<String> allTenants(def ec) {
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
                         [entity: "darpan.audit.AuditFindingEvent", fk: "findingId", tenantStamped: true],
                         [entity: "darpan.audit.AuditFindingAcknowledgement", fk: "findingId", tenantStamped: true]], [])
            case RUN_RESULT:
                return sweepParents(ec, tenantId, dryRun, batch, "darpan.reconciliation.ReconciliationRunResult", "reconciliationRunResultId",
                        { find -> find.condition("createdDate", EntityCondition.LESS_THAN, cutoff)
                                .condition("statusEnumId", EntityCondition.NOT_IN, UNFINISHED_RUN_STATUSES) },
                        [[entity: "darpan.reconciliation.ReconciliationRunStep", fk: "reconciliationRunResultId"],
                         [entity: "darpan.reconciliation.ReconciliationRunNotifySubscription", fk: "reconciliationRunResultId"]],
                        // An automation execution keeps its own counts and history; it only forgets which run result it made.
                        [[entity: "darpan.reconciliation.ReconciliationAutomationExecution", fk: "reconciliationRunResultId"]])
            case AGENT_QUERY_LOG:
                return sweepParents(ec, tenantId, dryRun, batch, "darpan.agent.AgentQueryLog", "agentQueryLogId",
                        { find -> find.condition("createdDate", EntityCondition.LESS_THAN, cutoff) }, [], [])
            case AGENT_MESSAGE:
                return sweepAgentMessages(ec, tenantId, cutoff, dryRun, batch)
            case SYNC_PULSE_SAMPLE:
                // Samples by their stamp; with them, pulse orders closed before the same cutoff (an open one always stays).
                Map counts = sweepParents(ec, tenantId, dryRun, batch, "darpan.pulse.SyncPulseSample", "sampleId",
                        { find -> find.condition("sampleStamp", EntityCondition.LESS_THAN, cutoff) }, [], [])
                Map closedOrders = sweepParents(ec, tenantId, dryRun, batch, "darpan.pulse.SyncPulseOrder", "pulseOrderId",
                        { find -> find.condition("closedStamp", EntityCondition.LESS_THAN, cutoff) }, [], [])
                counts["darpan.pulse.SyncPulseOrder"] = closedOrders._rows
                counts["darpan.pulse.SyncPulsePresentKey"] = sweepPresentKeys(ec, tenantId, dryRun, batch)
                return counts
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

    /**
     * Pulse present keys older than three days (the longest read window is about 60 hours): they only help inside the pulse read window (hours), so this catches the rows
     * of a pair deactivated while the process was down. Composite key, so pages are "the first batch found" until none are left.
     */
    private static long sweepPresentKeys(def ec, String tenantId, boolean dryRun, int batch) {
        Timestamp cutoff = new Timestamp(System.currentTimeMillis() - 3L * 86_400_000L)
        Closure find = {
            TenantScopedFinder.findGlobalUnscoped(ec, "darpan.pulse.SyncPulsePresentKey", REASON).condition("companyUserGroupId", tenantId)
                    .condition("sourceCreatedStamp", EntityCondition.LESS_THAN, cutoff).useCache(false)
        }
        if (dryRun) return find.call().count()
        long deleted = 0L
        while (true) {
            List rows = find.call().limit(batch).list()
            if (!rows) break
            ec.transaction.runRequireNew(300, "Retention batch of SyncPulsePresentKey for ${tenantId}".toString()) { rows.each { it.delete() } }
            deleted += rows.size()
            if (rows.size() < batch) break
        }
        return deleted
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
