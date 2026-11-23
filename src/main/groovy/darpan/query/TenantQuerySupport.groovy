package darpan.query

import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.reconciliation.notification.TenantNotificationSupport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.moqui.jexl.script.ReadSpec

import java.sql.Timestamp

/**
 * DAR-BE-334. The facade behind facade.TenantScriptFacadeServices: the active tenant's queries (a read over kept data
 * plus a JEXL script, profile QUERY) and their schedules (moqui-jexl deployments with email and chat deliveries). Every
 * read and write names the active tenant; writing and running need tenant edit access, scheduling needs tenant admin
 * (it sends the tenant's data out by email). moqui-jexl stores and runs; Darpan scopes.
 */
class TenantQuerySupport {
    static final String PROFILE = "QUERY"
    static final String JEXL = "moqui.jexl.ScriptServices"
    /** Email addresses one schedule may send to (to + cc). */
    static final int MAX_RECIPIENTS = 50

    /** Every read here names the active tenant, or a schedule or query already checked as the tenant's (deliveries carry
     *  no tenant), or reads the owner's own membership rows. */
    private static def unscoped(def ec, String entityName) {
        return TenantScopedFinder.findGlobalUnscoped(ec, entityName,
                "DAR-BE-334 queries: conditioned on the active tenant, a row already checked as the tenant's, or the owner's membership")
    }

    // ---- queries

    static Map toQueryMap(def row) {
        return [tenantQueryId: row.userScriptId, queryName: row.scriptName, description: row.description, readSpecJson: row.readSpecJson,
                scriptBody: row.scriptBody, isActive: row.isActive, ownerUserId: row.ownerUserId, readText: readText(row.readSpecJson as String),
                lastUpdatedStamp: row.lastUpdatedStamp]
    }

    static List<Map> listQueries(def ec, String tenantId) {
        return unscoped(ec, "jexl.script.UserScript").condition("companyUserGroupId", tenantId).orderBy("scriptName")
                .useCache(false).list().collect { toQueryMap(it) }
    }

    /** The tenant's query row, or null (another tenant's reads as not found). */
    static def ownQuery(def ec, String tenantId, String tenantQueryId) {
        if (!tenantQueryId) return null
        def row = unscoped(ec, "jexl.script.UserScript").condition("userScriptId", tenantQueryId).useCache(false).one()
        return row?.companyUserGroupId == tenantId ? row : null
    }

    /** A query a schedule runs sends what it computes to the schedule's addresses: changing or deleting it needs tenant admin. */
    static boolean scheduledNeedsAdmin(def ec, String tenantId, String tenantQueryId) {
        if (!tenantQueryId) return false
        boolean scheduled = unscoped(ec, "jexl.script.UserScriptDeployment").condition("userScriptId", tenantQueryId)
                .condition("companyUserGroupId", tenantId).useCache(false).count() > 0
        if (scheduled && !TenantAccessSupport.isTenantAdmin(ec, tenantId)) {
            ec.message.addError("This query has a schedule that sends its answer out, so changing or deleting it needs tenant-admin access.")
            return true
        }
        return false
    }

    static Map saveQuery(def ec, String tenantId, Map p) {
        String id = (p.tenantQueryId as String)?.trim() ?: null
        if (id && ownQuery(ec, tenantId, id) == null) { ec.message.addError("Query '${id}' was not found."); return null }
        if (scheduledNeedsAdmin(ec, tenantId, id)) return null
        String name = (p.queryName as String)?.trim()
        if (!name) { ec.message.addError("A query needs a name."); return null }
        def clash = unscoped(ec, "jexl.script.UserScript").condition("companyUserGroupId", tenantId).useCache(false).list()
                .find { it.userScriptId != id && (it.scriptName as String)?.trim()?.equalsIgnoreCase(name) }
        if (clash != null) { ec.message.addError("A query named '${name}' already exists."); return null }
        Map out = ec.service.sync().name("${JEXL}.store#UserScript").parameters([userScriptId: id, scriptName: name, description: p.description,
                scriptProfile: PROFILE, scriptBody: p.scriptBody, readSpecJson: p.readSpecJson ?: null, sampleInputJson: p.sampleInputJson,
                isActive: p.isActive == false ? "N" : "Y", ownerFields: [companyUserGroupId: tenantId]]).call()
        if (ec.message.hasError()) return null
        return [query: toQueryMap(ownQuery(ec, tenantId, out.userScriptId as String)), notes: out.notes]
    }

    static Map runQuery(def ec, String tenantId, Map p) {
        String id = (p.tenantQueryId as String)?.trim() ?: null
        if (id && ownQuery(ec, tenantId, id) == null) { ec.message.addError("Query '${id}' was not found."); return null }
        Map params = [scriptProfile: PROFILE, ownerKey: tenantId]
        // NOTE: given a userScriptId, moqui-jexl runs the SAVED body; a draft (a body given) runs as itself, scoped by ownerKey
        if (id && p.scriptBody == null) params.userScriptId = id
        if (p.scriptBody != null) params.scriptBody = p.scriptBody
        if (p.readSpecJson != null) params.readSpecJson = p.readSpecJson
        if (!id && p.scriptBody == null) { ec.message.addError("Write a query, or choose a saved one."); return null }
        Map out = ec.service.sync().name("${JEXL}.run#UserScript").parameters(params).call()
        if (ec.message.hasError()) return null
        Map normalized = (Map) out.normalizedResult ?: [:]
        List files = ((List<Map>) (normalized.files ?: [])).collect { Map f ->
            [name: f.name, columns: f.columns, rowCount: ((List) f.rows)?.size() ?: 0, previewRows: ((List) f.rows)?.take(20) ?: []]
        }
        return [ok: out.ok, summary: normalized.summary, files: files, data: normalized.data, nothing: normalized.nothing == true,
                invalid: normalized.invalid == true ? normalized.message : null, readCounts: out.readCounts, units: out.units,
                elapsedMs: out.elapsedMs, logs: out.logs, error: out.error,
                readText: readText((p.readSpecJson ?: (id ? ownQuery(ec, tenantId, id)?.readSpecJson : null)) as String)]
    }

    static void deleteQuery(def ec, String tenantId, String tenantQueryId) {
        def row = ownQuery(ec, tenantId, tenantQueryId)
        if (row == null) { ec.message.addError("Query '${tenantQueryId}' was not found."); return }
        if (scheduledNeedsAdmin(ec, tenantId, tenantQueryId)) return
        unscoped(ec, "jexl.script.UserScriptDeployment").condition("userScriptId", tenantQueryId).useCache(false).list().each { d ->
            unscoped(ec, "jexl.script.UserScriptDeploymentDelivery").condition("scriptDeploymentId", d.scriptDeploymentId).deleteAll()
            d.delete()
        }
        row.delete()
    }

    // ---- schedules

    static def ownSchedule(def ec, String tenantId, String scheduleId) {
        if (!scheduleId) return null
        def row = unscoped(ec, "jexl.script.UserScriptDeployment").condition("scriptDeploymentId", scheduleId).useCache(false).one()
        return row?.companyUserGroupId == tenantId ? row : null
    }

    static Map toScheduleMap(def ec, def row) {
        List deliveries = unscoped(ec, "jexl.script.UserScriptDeploymentDelivery").condition("scriptDeploymentId", row.scriptDeploymentId)
                .orderBy("deliverySeqId").useCache(false).list()
        Map email = null, chat = null
        List outcomes = []
        deliveries.each { d ->
            Map params = d.parametersJson ? (Map) new JsonSlurper().parseText(d.parametersJson as String) : [:]
            if (d.deliveryServiceName == QueryDelivery.EMAIL_SERVICE) email = [to: params.toAddresses, cc: params.ccAddresses]
            if (d.deliveryServiceName == QueryDelivery.CHAT_SERVICE) chat = [chatSpaceId: params.chatSpaceId]
            outcomes << [kind: d.deliveryServiceName == QueryDelivery.EMAIL_SERVICE ? "email" : "chat", lastOutcome: d.lastOutcome, lastMessage: d.lastMessage]
        }
        def owner = row.ownerUserId ? unscoped(ec, "moqui.security.UserAccount").condition("userId", row.ownerUserId).useCache(true).one() : null
        return [scheduleId: row.scriptDeploymentId, tenantQueryId: row.userScriptId, cronExpression: row.cronExpression, timeZone: row.timeZone,
                isActive: row.isActive, ownerUserId: row.ownerUserId, ownerName: owner?.userFullName ?: owner?.username,
                emailTo: email?.to, emailCc: email?.cc, chatSpaceId: chat?.chatSpaceId,
                recipientCount: QueryDelivery.emailRecipients(ec, row.scriptDeploymentId as String).size(),
                nextRunDate: row.nextRunDate, lastRunDate: row.lastRunDate, lastRunStatus: row.lastRunStatus, lastRunMessage: row.lastRunMessage,
                deliveries: outcomes]
    }

    static List<Map> listSchedules(def ec, String tenantId, String tenantQueryId) {
        def find = unscoped(ec, "jexl.script.UserScriptDeployment").condition("companyUserGroupId", tenantId).useCache(false)
        if (tenantQueryId) find.condition("userScriptId", tenantQueryId)
        return find.list().collect { toScheduleMap(ec, it) }
    }

    static Map saveSchedule(def ec, String tenantId, Map p) {
        String scheduleId = (p.scheduleId as String)?.trim() ?: null
        if (scheduleId && ownSchedule(ec, tenantId, scheduleId) == null) { ec.message.addError("Schedule '${scheduleId}' was not found."); return null }
        if (ownQuery(ec, tenantId, p.tenantQueryId as String) == null) { ec.message.addError("Query '${p.tenantQueryId}' was not found."); return null }
        String cron = p.cronExpression as String
        if (!cron && p.dailyAt) {
            def m = (p.dailyAt as String) =~ /^(\d{1,2}):(\d{2})$/
            if (!m.matches() || (m.group(1) as int) > 23 || (m.group(2) as int) > 59) { ec.message.addError("Daily time must be HH:mm."); return null }
            cron = "0 ${m.group(2) as int} ${m.group(1) as int} * * ?".toString()
        }
        if (!cron) { ec.message.addError("Choose when the schedule runs."); return null }
        List deliveries = []
        if (p.emailTo) deliveries << [deliveryServiceName: QueryDelivery.EMAIL_SERVICE, parameters: [toAddresses: p.emailTo, ccAddresses: p.emailCc ?: null]]
        if (p.chatSpaceId) deliveries << [deliveryServiceName: QueryDelivery.CHAT_SERVICE, parameters: [chatSpaceId: p.chatSpaceId]]
        if (!deliveries) { ec.message.addError("Send it somewhere: an email address or a chat space."); return null }
        if (p.chatSpaceId) {
            def space = ownChatSpace(ec, tenantId, p.chatSpaceId as String)
            if (space == null) { ec.message.addError("Chat space ${p.chatSpaceId} was not found."); return null }
            if (space.isActive == "N") { ec.message.addError("Chat space '${space.spaceName}' is switched off."); return null }
        }
        List<String> addresses = ((p.emailTo ?: "") + "," + (p.emailCc ?: "")).toString().split(/[\s,;]+/).findAll { it } as List<String>
        if (addresses.size() > MAX_RECIPIENTS) {
            ec.message.addError("A schedule sends to at most ${MAX_RECIPIENTS} addresses; this one names ${addresses.size()}.".toString()); return null
        }
        Map out = ec.service.sync().name("${JEXL}.store#UserScriptDeployment").parameters([scriptDeploymentId: scheduleId,
                userScriptId: p.tenantQueryId, cronExpression: cron, timeZone: TenantNotificationSupport.resolveTenantZone(ec, tenantId).id,
                isActive: p.isActive == true || p.isActive == "Y" ? "Y" : "N", deliveries: deliveries,
                ownerFields: [companyUserGroupId: tenantId]]).call()
        if (ec.message.hasError()) return null
        return [schedule: toScheduleMap(ec, ownSchedule(ec, tenantId, out.scriptDeploymentId as String))]
    }

    static Map runSchedule(def ec, String tenantId, String scheduleId, boolean send) {
        def row = ownSchedule(ec, tenantId, scheduleId)
        if (row == null) { ec.message.addError("Schedule '${scheduleId}' was not found."); return null }
        if (!send) {
            // A preview is any tenant admin's: the schedule's saved query runs as the caller and nothing is sent
            // (moqui-jexl's own preview is the owner's only).
            Map run = runQuery(ec, tenantId, [tenantQueryId: row.userScriptId])
            if (run == null) return null
            Map schedule = toScheduleMap(ec, row)
            String where = sendsToText(ec, tenantId, schedule)
            String status = run.error ? ((Map) run.error).kind as String : (run.nothing ? "NOTHING_TO_SEND" : "PREVIEW")
            String message = run.error ? ((Map) run.error).message as String
                    : (run.nothing ? "Nothing to send: the query returned nothing." : "Preview only: nothing was sent. It would go to ${where}.".toString())
            return [status: status, message: message, result: [summary: run.summary, files: run.files], readCounts: run.readCounts]
        }
        Map out = ec.service.sync().name("${JEXL}.run#UserScriptDeployment").parameters([scriptDeploymentId: scheduleId, deliver: send]).call()
        if (ec.message.hasError()) return null
        return [status: out.status, message: out.message, result: out.normalizedResult, readCounts: out.readCounts, deliveries: out.deliveries]
    }

    /** The tenant's chat space, or null. By its key, the tenant compared here: Moqui drops a condition beside a full key. */
    private static def ownChatSpace(def ec, String tenantId, String chatSpaceId) {
        if (!chatSpaceId) return null
        def space = unscoped(ec, "darpan.reconciliation.TenantChatSpace").condition("chatSpaceId", chatSpaceId).useCache(false).one()
        return space?.companyUserGroupId == tenantId ? space : null
    }

    private static String sendsToText(def ec, String tenantId, Map schedule) {
        List<String> parts = []
        int count = (schedule.recipientCount ?: 0) as int
        if (count) parts << "${count} email address${count == 1 ? '' : 'es'}".toString()
        if (schedule.chatSpaceId) {
            def space = ownChatSpace(ec, tenantId, schedule.chatSpaceId as String)
            parts << "the chat space ${space?.spaceName ?: schedule.chatSpaceId}".toString()
        }
        return parts ? parts.join(" and ") : "nowhere"
    }

    static void deleteSchedule(def ec, String tenantId, String scheduleId) {
        def row = ownSchedule(ec, tenantId, scheduleId)
        if (row == null) { ec.message.addError("Schedule '${scheduleId}' was not found."); return }
        unscoped(ec, "jexl.script.UserScriptDeploymentDelivery").condition("scriptDeploymentId", scheduleId).deleteAll()
        row.delete()
    }

    // ---- notification routes (DAR-UI-123): a schedule's chat delivery is one route

    private static List chatDeliveries(def ec, String scheduleId) {
        return unscoped(ec, "jexl.script.UserScriptDeploymentDelivery").condition("scriptDeploymentId", scheduleId)
                .condition("deliveryServiceName", QueryDelivery.CHAT_SERVICE).useCache(false).list()
    }

    private static String spaceOf(def delivery) {
        return delivery?.parametersJson ? ((Map) new JsonSlurper().parseText(delivery.parametersJson as String)).chatSpaceId as String : null
    }

    /** One route per schedule that posts to chat: {sourceType: SCRIPT, sourceId, label, when, paused, chatSpaceId}. */
    static List<Map> chatRoutes(def ec, String tenantId) {
        List queries = unscoped(ec, "jexl.script.UserScript").condition("companyUserGroupId", tenantId).useCache(false).list()
        List<Map> out = []
        unscoped(ec, "jexl.script.UserScriptDeployment").condition("companyUserGroupId", tenantId).orderBy("scriptDeploymentId")
                .useCache(false).list().each { d ->
            def chat = chatDeliveries(ec, d.scriptDeploymentId as String)[0]
            if (chat == null) return
            String daily = darpan.audit.AuditSettingsSupport.timeOf(d.cronExpression as String)
            out << [sourceType: "SCRIPT", sourceId: d.scriptDeploymentId, label: queries.find { it.userScriptId == d.userScriptId }?.scriptName ?: d.userScriptId,
                    when: daily ? "Daily " + daily : null, paused: d.isActive != "Y", chatSpaceId: spaceOf(chat)]
        }
        return out
    }

    /** How many of the tenant's schedules post to this chat space. */
    static int chatScheduleCount(def ec, String tenantId, String chatSpaceId) {
        return chatRoutes(ec, tenantId).count { it.chatSpaceId == chatSpaceId } as int
    }

    /** Moves a schedule's chat delivery to another space. A schedule that only posts to chat must post somewhere. */
    static void moveChatRoute(def ec, String tenantId, String scheduleId, String chatSpaceId) {
        if (ownSchedule(ec, tenantId, scheduleId) == null) { ec.message.addError("Schedule ${scheduleId} was not found."); return }
        def chat = chatDeliveries(ec, scheduleId)[0]
        if (chat == null) { ec.message.addError("Schedule ${scheduleId} does not post to chat."); return }
        if (!chatSpaceId) { ec.message.addError("A query schedule needs a space. Switch it off instead."); return }
        Map params = chat.parametersJson ? new LinkedHashMap((Map) new JsonSlurper().parseText(chat.parametersJson as String)) : [:]
        params.chatSpaceId = chatSpaceId
        chat.set("parametersJson", JsonOutput.toJson(params))
        chat.update()
    }

    // ---- moqui-jexl owner check (jexl_owner_check_service)

    /** Why this owner may no longer run a tenant's schedule (not an active member, or no longer its admin), or null. */
    static String ownerProblem(def ec, String userId, String tenantId) {
        if (!userId || !tenantId) return "The schedule has no owner or tenant."
        Timestamp now = ec.user.nowTimestamp
        Closure<Boolean> current = { def m -> (m.fromDate == null || !((Timestamp) m.fromDate).after(now)) && (m.thruDate == null || ((Timestamp) m.thruDate).after(now)) }
        boolean darpanAdmin = unscoped(ec, "moqui.security.UserGroupMember").condition("userId", userId)
                .condition("userGroupId", "in", [TenantAccessSupport.DARPAN_ADMIN_GROUP_ID, "DARPAN_SUPER_ADMIN"].findAll { it })
                .useCache(false).list().any(current)
        if (darpanAdmin) return null
        boolean member = unscoped(ec, "moqui.security.UserGroupMember").condition([userId: userId, userGroupId: tenantId])
                .useCache(false).list().any(current)
        if (!member) return "The owner is no longer a member of ${tenantId}."
        boolean admin = unscoped(ec, TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME)
                .condition([tenantUserGroupId: tenantId, userId: userId, permissionUserGroupId: TenantAccessSupport.DARPAN_TENANT_ADMIN_GROUP_ID])
                .useCache(false).list().any(current)
        return admin ? null : "The owner is no longer an admin of ${tenantId}, so they can no longer send its data out."
    }

    // ---- the read in words

    static String readText(String readSpecJson) {
        if (!readSpecJson) return null
        ReadSpec spec
        try { spec = ReadSpec.parse(readSpecJson) } catch (ReadSpec.Invalid ignored) { return null }
        return spec.inputs.collect { ReadSpec.Input i ->
            List<String> parts = []
            String what = i.source == "dataset" ? "dataset ${i.raw.datasetId}" : i.source == "runResult" ? "the newest result of saved run ${i.raw.savedRunId}" :
                    i.source == "auditFindings" ? "the findings of audit ${i.raw.auditId}" : i.source
            parts << "Reads ${what}".toString()
            if (i.window) parts << "last ${i.window.lastDays} days".toString()
            if (i.where) parts << whereText(i.where)
            if (i.groupBy) parts << "by ${i.groupBy.join(', ')}".toString()
            int n = i.fields.size() + i.measures.size()
            parts << "${n} field${n == 1 ? '' : 's'}".toString()
            return parts.join(", ")
        }.join("; ")
    }

    private static String whereText(Map where) {
        if (where.containsKey("and")) return ((List<Map>) where.and).collect { whereText(it) }.join(" and ")
        if (where.containsKey("or")) return "(" + ((List<Map>) where.or).collect { whereText(it) }.join(" or ") + ")"
        Map words = ["equals": "=", "not-equals": "≠", "less": "<", "greater": ">", "less-equals": "≤", "greater-equals": "≥", "in": "in",
                     "not-in": "not in", "between": "between", "like": "like", "not-like": "not like", "is-null": "is empty", "is-not-null": "is set"]
        String op = words[where.op] ?: where.op
        if (where.op in ["is-null", "is-not-null"]) return "${where.field} ${op}".toString()
        Object v = where.value instanceof List ? ((List) where.value).join(", ") : where.value
        return "${where.field} ${op} ${v}".toString()
    }
}
