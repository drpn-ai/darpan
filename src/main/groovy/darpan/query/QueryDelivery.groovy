package darpan.query

import darpan.reconciliation.notification.TenantNotificationSupport
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * DAR-BE-334. Darpan's deliveries for scheduled queries. Both run as the deployment's owner (moqui-jexl) and first
 * enter the query's tenant (QuerySourceScope: the owner must still be a member; the deployment must be that tenant's).
 * Chat posts the summary (and which files went by email), never files. Email sends through moqui-jexl's
 * deliver#ScriptEmail with Darpan's template, and writes one DataExportLog row per emailed file with its recipients.
 */
class QueryDelivery {
    private static final Logger logger = LoggerFactory.getLogger(QueryDelivery)
    static final String EMAIL_TEMPLATE = "DARPAN_QUERY_RESULT"
    static final String EMAIL_SERVICE = "query.QueryHostServices.deliver#QueryEmail"
    static final String CHAT_SERVICE = "query.QueryHostServices.deliver#QueryChat"

    /** {outcome, message} or {error: message} */
    static Map<String, Object> chat(def ec, Map p) {
        Map deployment = (Map) p.scriptDeployment
        Map parameters = (Map) ((Map) p.delivery)?.parameters ?: [:]
        Map result = (Map) p.scriptResult ?: [:]
        List<Map> files = (List<Map>) (p.files ?: [])
        return inTenant(ec, deployment) { String tenantId ->
            String chatSpaceId = parameters.chatSpaceId as String
            if (!chatSpaceId) return [error: "The chat delivery needs a chatSpaceId."]
            String name = queryName(ec, deployment)
            List<String> lines = [result.summary ? "${name}: ${result.summary}".toString() : name]
            if (files) {
                int people = emailRecipients(ec, deployment.scriptDeploymentId as String).size()
                if (people) lines.add("${files.size()} file${files.size() == 1 ? '' : 's'} (${files*.name.join(', ')}) ${files.size() == 1 ? 'goes' : 'go'} to ${people} ${people == 1 ? 'person' : 'people'} by email.".toString())
            }
            Map posted = TenantNotificationSupport.postToChatSpace(ec, tenantId, chatSpaceId, [text: lines.join("\n")])
            if (posted == null) return [error: "The chat space ${chatSpaceId} was deleted, switched off, has no webhook, or is not this tenant's.".toString()]
            if (posted.ok != true) return [error: "The chat space refused the message (${posted.statusCode ?: '?'}).".toString()]
            return [outcome: "OK", message: "Posted to the chat space."]
        }
    }

    static Map<String, Object> email(def ec, Map p) {
        Map deployment = (Map) p.scriptDeployment
        Map delivery = (Map) p.delivery ?: [:]
        Map parameters = (Map) delivery.parameters ?: [:]
        List<Map> files = (List<Map>) (p.files ?: [])
        return inTenant(ec, deployment) { String tenantId ->
            Map out = ec.service.sync().name("moqui.jexl.ScriptServices.deliver#ScriptEmail").parameters([scriptDeployment: deployment,
                    delivery: delivery + [parameters: parameters + [emailTemplateId: EMAIL_TEMPLATE]], scriptResult: p.scriptResult,
                    files: files, scriptRunId: p.scriptRunId]).call()
            if (ec.message.hasError()) {
                String errors = ec.message.errorsString
                ec.message.clearErrors()
                return [error: errors]
            }
            // Only an email that carried its files logs them (TOO_LARGE sent the summary alone). A log that cannot be written
            // never turns a sent email into a failure (which would send it again next run).
            if (out?.outcome == "OK") {
                String recipients = fitRecipients([parameters.toAddresses, parameters.ccAddresses].findAll { it }.join(", "))
                files.each { Map f ->
                    try {
                        ec.entity.makeValue("darpan.admin.DataExportLog").setAll([dataExportLogId: ec.entity.sequencedIdPrimary("darpan.admin.DataExportLog", null, null),
                                userId: deployment.ownerUserId, companyUserGroupId: tenantId, resourceType: "SCRIPT_EMAIL", resourceRef: f.name,
                                format: "csv", byteCount: ((byte[]) f.contentBytes)?.length, serviceName: EMAIL_SERVICE,
                                recipients: recipients, eventTimestamp: ec.user.nowTimestamp]).create()
                    } catch (Exception e) {
                        logger.error("Query email sent but its export log for ${f.name} could not be written", e)
                        ec.message.clearErrors()
                    }
                }
            }
            return [outcome: out?.outcome ?: "OK", message: out?.message]
        }
    }

    /** The recipients column holds 255 characters: a longer list keeps what fits and says how many more. */
    static String fitRecipients(String recipients) {
        if (recipients == null || recipients.length() <= 255) return recipients
        List<String> all = recipients.split(/,\s*/) as List<String>
        List<String> kept = []
        for (String r : all) {
            String next = (kept + [r]).join(", ") + " (+${all.size() - kept.size() - 1} more)"
            if (next.length() > 255) break
            kept << r
        }
        return kept.join(", ") + " (+${all.size() - kept.size()} more)"
    }

    private static Map<String, Object> inTenant(def ec, Map deployment, Closure<Map> body) {
        Map<String, Object> out = QuerySourceScope.withTenant(ec, [userScriptId: deployment?.userScriptId]) { String tenantId ->
            if (deployment.companyUserGroupId != tenantId) return [error: "The schedule and its query belong to different tenants."]
            return body.call(tenantId)
        }
        if (out.error instanceof Map) return [error: ((Map) out.error).message]
        return out
    }

    private static String queryName(def ec, Map deployment) {
        return ec.entity.find("jexl.script.UserScript").condition("userScriptId", deployment.userScriptId).useCache(false).one()?.scriptName ?: "Query"
    }

    /** The email addresses the schedule's active email deliveries send to. */
    static List<String> emailRecipients(def ec, String scriptDeploymentId) {
        List<String> out = []
        ec.entity.find("jexl.script.UserScriptDeploymentDelivery").condition("scriptDeploymentId", scriptDeploymentId)
                .condition("deliveryServiceName", EMAIL_SERVICE).condition("isActive", "Y").useCache(false).list().each { d ->
            Map params = d.parametersJson ? (Map) new groovy.json.JsonSlurper().parseText(d.parametersJson as String) : [:]
            [params.toAddresses, params.ccAddresses].each { String list -> list?.split(",")?.each { String a -> if (a.trim()) out.add(a.trim()) } }
        }
        return out.unique()
    }
}
