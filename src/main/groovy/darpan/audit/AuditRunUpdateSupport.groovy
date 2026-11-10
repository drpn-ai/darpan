package darpan.audit

import darpan.common.DarpanEntityConstants
import darpan.facade.common.TenantScopedFinder
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.automation.AutomationRuntimeSupport
import darpan.reconciliation.notification.TenantNotificationSupport
import org.moqui.entity.EntityCondition
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Date
import java.sql.Timestamp
import java.time.LocalDate

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.readField

/**
 * DAR-BE-207. The update after each audit run, for every AuditDigest of kind run: the whole run by email (a CSV per rule
 * with open findings attached) and a summary card in chat. Asked for after every execution of an audit's automation
 * (AutomationExecutionSupport.executeAutomation), it goes out once the whole audit is idle: no execution running or
 * queued for a retry, and none of its automations waiting in or holding a RunAdmission slot. Each digest is claimed for
 * the run first, so executions finishing together send it once. Runs with no user: every read carries the tenant.
 */
class AuditRunUpdateSupport {
    private static final Logger logger = LoggerFactory.getLogger(AuditRunUpdateSupport.class)
    private static final String WHY = "audit run update on a background thread: explicit companyUserGroupId condition applied"
    static final String RUN = "AUDDK_RUN"
    static final String SERVICE = "audit.AuditDigestServices.send#AuditRunUpdate"
    /** Optional (darpan-agent): {headline, fixes {"R10|cause": sentence}} from the fact sheet. Core never imports it. */
    static final String WORDS_SERVICE = "agent.AgentWriterServices.write#AuditRunWords"
    static final String EMAIL_TEMPLATE = "DARPAN_AUDIT_RUN"

    /** Every run-update email goes through here, so tests record sends instead of making them. */
    static Closure sendEmail = { def ec, String toAddresses, Map bodyParameters, List<Map> attachments ->
        AutomationExecutionSupport.dispatchInternalService(ec, "org.moqui.impl.EmailServices.send#EmailTemplate",
                [emailTemplateId: EMAIL_TEMPLATE, toAddresses: toAddresses, bodyParameters: bodyParameters, attachments: attachments])
    }

    /** How a finished execution hands the update to the background; tests call it in line. */
    static Closure dispatch = { def ec, Map params ->
        ec.service.async().name(SERVICE).parameters(params).call()
    }

    /**
     * After one execution of an automation, success or failure: when it runs an audit's checks and that audit has a run
     * digest, the update is asked for in the background. A verify (one finding re-checked) is not a run. Never throws:
     * the execution's own outcome stands whatever happens here.
     */
    static void afterExecution(def ec, def automation, Map input, Map result) {
        try {
            if (input?.auditVerifyKeys) return
            String tenantId = normalize(readField(automation, "companyUserGroupId"))
            String automationId = normalize(readField(automation, "automationId"))
            if (!tenantId || !automationId) return
            String auditId = normalize(result?.auditId)
            if (!auditId) {
                List<Map> checks = AuditAutomationSupport.checksFor(ec, automation)
                if (!checks) return
                auditId = AuditAutomationSupport.auditFor(ec, tenantId, checks)?.auditId as String
            }
            if (!auditId || !runDigests(ec, tenantId, auditId)) return
            dispatch.call(ec, [companyUserGroupId  : tenantId, auditId: auditId, reportDate: result?.auditReportDate,
                               period              : result?.auditPeriod, finishedAutomationId: automationId])
        } catch (Throwable t) {
            logger.warn("Audit run update after automation {} not asked for: {}", readField(automation, "automationId"), t.message)
        }
    }

    /**
     * Sends the run update to each active run digest of the audit, once the audit is idle. reportDate and period name the
     * run (a play's last window); blank, they are read off the audit's latest check run. Answers per digest: auditDigestId,
     * sent, and the reason when not sent (RUN_NOT_DONE, ALREADY_SENT, NO_DESTINATION, NO_REPORT, DELIVERY_FAILED, FAILED).
     */
    static List<Map> send(def ec, String tenantId, String auditId, Date reportDate, String period, String finishedAutomationId,
                          Timestamp now) {
        List<Map> digests = runDigests(ec, tenantId, auditId)
        if (!digests) return []
        Map audit = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.Audit", "auditId", auditId, tenantId, WHY)?.getMap()
        if (audit == null) return []
        if (!idle(ec, tenantId, audit, finishedAutomationId, now)) {
            return digests.collect { [auditDigestId: it.auditDigestId, sent: false, reason: "RUN_NOT_DONE"] }
        }
        if (reportDate == null) {
            Map latest = latestCheckRun(ec, tenantId, audit)
            reportDate = (latest?.reportDate ?: Date.valueOf(LocalDate.now(AuditDigestSupport.tenantZone(ec, tenantId)))) as Date
            period = period ?: latest?.period as String
        }
        String stamp = runStamp(ec, tenantId, audit, reportDate, period)
        List<Map> outcomes = []
        List<Map> claimed = []
        digests.each { Map digest ->
            if (claim(ec, digest, tenantId, stamp)) claimed << digest
            else outcomes << [auditDigestId: digest.auditDigestId, sent: false, reason: "ALREADY_SENT"]
        }
        if (!claimed) return outcomes

        Map built = null
        try {
            built = build(ec, tenantId, auditId, reportDate, period)
        } catch (Throwable t) {
            logger.warn("Audit run update for audit {} of tenant {}: not built: {}", auditId, tenantId, t.message)
        }
        claimed.each { Map digest ->
            try {
                outcomes << [auditDigestId: digest.auditDigestId] + (built == null ? [sent: false, reason: "NO_REPORT"] : deliver(ec, tenantId, digest, built))
            } catch (Throwable t) {
                logger.warn("Audit run update {} for tenant {} failed: {}", digest.auditDigestId, tenantId, t.message)
                outcomes << [auditDigestId: digest.auditDigestId, sent: false, reason: "FAILED"]
            }
        }
        return outcomes
    }

    /** The fact sheet, the words, and everything each destination is sent; null when there is no report. */
    static Map build(def ec, String tenantId, String auditId, Date reportDate, String period) {
        Map out = AutomationExecutionSupport.dispatchInternalService(ec, "audit.AuditReportServices.assemble#AuditReport",
                [companyUserGroupId: tenantId, auditId: auditId, reportDate: reportDate, period: period])
        if (ec.message.hasError()) {
            logger.warn("Audit run update: report for audit {} on {} refused: {}", auditId, reportDate, ec.message.errorsString)
            ec.message.clearErrors()
            return null
        }
        Map report = out?.document as Map
        if (report == null) return null
        List<Map> units = AuditReportAssembler.findingsByOrder(ec, tenantId, auditId, null, report.report?.period as String)
        Map facts = AuditRunFacts.build(report, units) { String rule, String cause, String findingId -> fixTextOf(ec, tenantId, findingId) }
        Map words = words(ec, tenantId, facts)
        String base = TenantNotificationSupport.resolveAppBaseUrl(ec)
        Map<String, Map> groupsByKey = (facts.groups as List<Map>).collectEntries { [("${it.rule}|${it.cause ?: ''}".toString()): it] }
        Map<String, String> csvs = AuditRunFacts.csvByRule(units) { String rule, String cause ->
            Map group = groupsByKey["${rule}|${cause ?: ''}".toString()]
            group ? AuditRunMessage.fixLine(group, words) : null
        }
        return [facts      : facts,
                words      : words,
                subject    : AuditRunMessage.subject(facts),
                html       : AuditRunMessage.emailHtml(facts, words, base),
                text       : AuditRunMessage.emailText(facts, words, base),
                card       : AuditRunMessage.card(facts, words, base),
                attachments: csvs.collect { String rule, String body ->
                    [fileName: AuditRunMessage.csvName(facts, rule), contentType: "text/csv; charset=UTF-8", contentText: body]
                }]
    }

    /**
     * The optional writer's words, or none: then the email has no headline and each group's fix line is its cause's fix.
     * The writer lives in darpan-agent, which an instance may not install.
     */
    static Map words(def ec, String tenantId, Map facts) {
        if (!ec.service.isServiceDefined(WORDS_SERVICE)) return [:]
        try {
            Map out = AutomationExecutionSupport.dispatchInternalService(ec, WORDS_SERVICE, [companyUserGroupId: tenantId, facts: facts])
            if (ec.message.hasError()) {
                logger.warn("Audit run update: the writer refused: {}", ec.message.errorsString)
                ec.message.clearErrors()
                return [:]
            }
            return [headline: normalize(out?.headline), fixes: out?.fixes instanceof Map ? out.fixes : [:]]
        } catch (Throwable t) {
            ec.message.clearErrors()
            logger.warn("Audit run update: the writer failed: {}", t.message)
            return [:]
        }
    }

    /** One finding's cause fix (its conclusion rule's), else its check's: unfilled, as it reads for the whole group. */
    private static String fixTextOf(def ec, String tenantId, String findingId) {
        if (!findingId) return null
        def finding = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.AuditFinding", "findingId", findingId, tenantId, WHY)
        if (finding == null) return null
        Map check = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.AuditCheck", "checkId", finding.checkId, tenantId, WHY)?.getMap()
        if (check == null) return null
        List<Map> evidence = rows(ec, "darpan.audit.AuditFindingCondition", tenantId).condition("findingId", findingId)
                .orderBy("sequenceNum").list()*.getMap()
        return AuditReportAssembler.causeFixOf(ec, tenantId, check, finding.conclusionEnumId as String, evidence, [:]) ?:
                AuditReportAssembler.fillTemplate(check.fixActionTemplate as String, [:])
    }

    private static Map deliver(def ec, String tenantId, Map digest, Map built) {
        String chatSpaceId = normalize(digest.chatSpaceId)
        Map destination = null
        if (chatSpaceId) {
            def space = TenantScopedFinder.findOneOwnedByTenant(ec, DarpanEntityConstants.TENANT_CHAT_SPACE,
                    "chatSpaceId", chatSpaceId, tenantId, "audit run update destination — owner checked against the digest's tenant")
            destination = TenantNotificationSupport.describeDestination(ec, tenantId, chatSpaceId, space)
            if (destination == null) logger.warn("Audit run update {}: chat space {} cannot be used", digest.auditDigestId, chatSpaceId)
        }
        List<String> emails = AuditDigestSupport.emailsOf(digest.emailAddresses)
        if (destination == null && !emails) return [sent: false, reason: "NO_DESTINATION"]
        boolean delivered = false
        if (destination != null) {
            // A Slack space takes the card's one-line text; cardsV2 is Google Chat's.
            boolean slack = destination.deliveryMode == TenantNotificationSupport.DELIVERY_SLACK_BOT ||
                    destination.chatProviderEnumId == TenantNotificationSupport.PROVIDER_SLACK
            Map card = built.card as Map
            Map delivery = TenantNotificationSupport.deliverToDestination(destination, slack ? [text: card.text] : card)
            if (delivery?.ok == true) delivered = true
            else logger.warn("Audit run update {} to space {} returned status {}", digest.auditDigestId, chatSpaceId, delivery?.statusCode)
        }
        if (emails) {
            sendEmail.call(ec, emails.join(","), [runSubject: built.subject, runHtml: built.html, runText: built.text],
                    built.attachments as List<Map>)
            if (ec.message.hasError()) {
                logger.warn("Audit run update {} email refused: {}", digest.auditDigestId, ec.message.errorsString)
                ec.message.clearErrors()
            } else {
                delivered = true
            }
        }
        return delivered ? [sent: true, reason: null] : [sent: false, reason: "DELIVERY_FAILED"]
    }

    /** A due automation the scan has not submitted yet (its tenant's lane was full) still belongs to this run. */
    static final long STILL_DUE_MS = 60L * 60 * 1000

    /**
     * The audit's whole run has ended: no execution of its automations running or queued for a retry (the run page's
     * Run health reads the same); none of them waiting in or holding a RunAdmission slot, apart from the one that just
     * finished (an automation admitted but not yet started has no execution row); and none due within the last hour and
     * not yet submitted (the scan defers what its tenant's lane cannot start to its next tick). A due time older than
     * that means no scheduler is running here, and does not hold the update back.
     */
    static boolean idle(def ec, String tenantId, Map audit, String finishedAutomationId, Timestamp now) {
        if (AuditSettingsSupport.progress(ec, tenantId, audit, now).active) return false
        def admission = AutomationExecutionSupport.getRunAdmission()
        return !AuditSettingsSupport.automationsOfAudit(ec, tenantId, audit).any { Map automation ->
            String id = automation.automationId as String
            if (id == finishedAutomationId) return false
            if (admission.busy(AutomationExecutionSupport.admissionLabel(id))) return true
            if (automation.isActive != "Y" || automation.isArchived == "Y") return false
            Timestamp due = AutomationExecutionSupport.resolveDueScheduledFireTime(automation, now)
            return due != null && now.time - due.time < STILL_DUE_MS
        }
    }

    /** Identifies one run: its morning, its period, and the latest end of any execution of the audit's automations. */
    static String runStamp(def ec, String tenantId, Map audit, Date reportDate, String period) {
        List ids = AuditSettingsSupport.automationsOfAudit(ec, tenantId, audit)*.automationId
        def latest = ids ? rows(ec, "darpan.reconciliation.ReconciliationAutomationExecution", tenantId)
                .condition("automationId", EntityCondition.IN, ids).condition("completedDate", EntityCondition.NOT_EQUAL, null)
                .orderBy("-completedDate").limit(1).list().find() : null
        return "${reportDate}|${period ?: ''}|${(latest?.completedDate as Timestamp)?.time ?: 0}".toString()
    }

    /**
     * Claims the digest for this run: the row locked (SELECT ... FOR UPDATE) in its own transaction, so a second
     * execution finishing at the same moment waits, then reads this run's stamp and backs off. A conditional updateAll
     * would not do: Moqui runs it as a read and then an update by key, and both threads would pass the read.
     */
    private static boolean claim(def ec, Map digest, String tenantId, String stamp) {
        return AutomationRuntimeSupport.runInNewTransaction(ec, "Could not claim audit run update ${digest.auditDigestId}".toString()) { ->
            // list(), not one(): one() drops the tenant condition beside the full primary key.
            def row = TenantScopedFinder.findGlobalUnscoped(ec, "darpan.audit.AuditDigest",
                    "run-update claim: the digest row locked by its id and tenant")
                    .condition("auditDigestId", digest.auditDigestId).condition("companyUserGroupId", tenantId)
                    .forUpdate(true).useCache(false).list().find()
            if (row == null || row.lastRunClaim == stamp) return false
            row.set("lastRunClaim", stamp)
            row.update()
            return true
        } == true
    }

    private static Map latestCheckRun(def ec, String tenantId, Map audit) {
        List checkIds = rows(ec, "darpan.audit.AuditStatementCheck", tenantId).condition("statementId", EntityCondition.IN,
                rows(ec, "darpan.audit.AuditStatement", tenantId).condition("auditId", audit.auditId).list()*.statementId ?: ["_none"])
                .list()*.checkId
        return checkIds ? rows(ec, "darpan.audit.AuditCheckRun", tenantId).condition("checkId", EntityCondition.IN, checkIds)
                .orderBy("-runStamp").limit(1).list().find()?.getMap() : null
    }

    /** The audit's active run digests. */
    static List<Map> runDigests(def ec, String tenantId, String auditId) {
        def cf = ec.entity.conditionFactory
        def active = cf.makeCondition([cf.makeCondition("isActive", EntityCondition.EQUALS, null),
                                       cf.makeCondition("isActive", EntityCondition.NOT_EQUAL, "N")], EntityCondition.OR)
        return rows(ec, "darpan.audit.AuditDigest", tenantId).condition("auditId", auditId).condition("kindEnumId", RUN)
                .condition(active).orderBy("auditDigestId").list()*.getMap()
    }

    private static def rows(def ec, String entityName, String tenantId) {
        return TenantScopedFinder.findGlobalUnscoped(ec, entityName, WHY).condition("companyUserGroupId", tenantId).useCache(false)
    }
}
