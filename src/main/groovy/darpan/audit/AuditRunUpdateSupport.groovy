package darpan.audit

import darpan.common.DarpanEntityConstants
import darpan.config.DarpanSettings
import darpan.email.EmailCopyReader
import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.automation.AutomationRuntimeSupport
import darpan.reconciliation.notification.TenantNotificationSupport
import org.moqui.entity.EntityCondition
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Date
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.readField

/**
 * DAR-BE-207. The update after each audit run: the whole run by email (a CSV per rule with open findings attached) to
 * every current member of the tenant and every Darpan admin, on for every audit by default (product owner, 2026-11-10). An AuditDigest of kind
 * run tunes it for its audit: isActive N turns it off, emailAddresses are sent to as well as the members (never instead),
 * chatSpaceId adds a summary card in chat. An audit with no such row gets a default one on its first update, which holds the run's claim. Asked for after every execution of an audit's automation
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
    /** Optional (darpan-agent, DAR-BE-320): turns a play's read flags into staff tickets. Core never imports it. */
    static final String READ_REVIEW_SERVICE = "agent.AgentWriterServices.review#AuditReads"
    static final String EMAIL_TEMPLATE = "DARPAN_AUDIT_RUN"
    /** DAR-BE-358: Darpan's own address(es) for the Unexplained CSV after each run; unset sends none. */
    static final String UNEXPLAINED_TO = "audit.unexplained.email.to"

    /** Every run-update email goes through here, so tests record sends instead of making them. */
    private static final Closure DEFAULT_SEND_EMAIL = { def ec, String toAddresses, Map bodyParameters, List<Map> attachments ->
        AutomationExecutionSupport.dispatchInternalService(ec, "org.moqui.impl.EmailServices.send#EmailTemplate",
                [emailTemplateId: EMAIL_TEMPLATE, toAddresses: toAddresses, bodyParameters: bodyParameters, attachments: attachments])
    }
    static Closure sendEmail = DEFAULT_SEND_EMAIL

    /** How a finished execution hands the update to the background; tests call it in line. */
    private static final Closure DEFAULT_DISPATCH = { def ec, Map params ->
        ec.service.async().name(SERVICE).parameters(params).call()
    }
    static Closure dispatch = DEFAULT_DISPATCH

    /** Test-only: restores both seams to their production values. Idempotent; never called from src/main. */
    static void resetForTests() {
        sendEmail = DEFAULT_SEND_EMAIL
        dispatch = DEFAULT_DISPATCH
    }

    /**
     * After one execution of an automation, success or failure: when it runs an audit's checks and the audit's update is
     * not turned off (every run digest inactive), the update is asked for in the background. A verify (one finding re-checked) is not a run. Never throws:
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
            if (!auditId) return
            List<Map> digests = runDigests(ec, tenantId, auditId)
            if (digests && !digests.any { active(it) } && !unexplainedTo()) return
            dispatch.call(ec, [companyUserGroupId  : tenantId, auditId: auditId, reportDate: result?.auditReportDate,
                               period              : result?.auditPeriod, finishedAutomationId: automationId])
        } catch (Throwable t) {
            logger.warn("Audit run update after automation {} not asked for: {}", readField(automation, "automationId"), t.message)
        }
    }

    /**
     * Sends the run update for each active run digest of the audit (a default one made when it has none), once the audit
     * is idle; nothing when every run digest is inactive, except Darpan's Unexplained CSV (DAR-BE-358, sent once per run
     * when its setting holds an address and the run has an Unexplained finding). reportDate and period name the
     * run (a play's last window); blank, they are read off the audit's latest check run. Answers per digest: auditDigestId,
     * sent, and the reason when not sent (RUN_NOT_DONE, ALREADY_SENT, NO_DESTINATION, NO_REPORT, DELIVERY_FAILED, FAILED).
     */
    static List<Map> send(def ec, String tenantId, String auditId, Date reportDate, String period, String finishedAutomationId,
                          Timestamp now) {
        List<Map> all = runDigests(ec, tenantId, auditId)
        // DAR-BE-358: a run whose update is off still sends Darpan its Unexplained CSV when the address is set.
        List<String> unexplainedTo = unexplainedTo()
        if (all && !all.any { active(it) } && !unexplainedTo) return []
        Map audit = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.Audit", "auditId", auditId, tenantId, WHY)?.getMap()
        if (audit == null) return []
        if (!idle(ec, tenantId, audit, finishedAutomationId, now)) {
            return (all ?: [[auditDigestId: defaultDigestId(auditId)]]).findAll { active(it) }
                    .collect { [auditDigestId: it.auditDigestId, sent: false, reason: "RUN_NOT_DONE"] }
        }
        List<Map> digests = all ? all.findAll { active(it) } : ensureDefaultDigest(ec, tenantId, auditId)
        if (reportDate == null) {
            Map latest = latestCheckRun(ec, tenantId, audit)
            reportDate = (latest?.reportDate ?: Date.valueOf(LocalDate.now(AuditDigestSupport.tenantZone(ec, tenantId)))) as Date
            period = period ?: latest?.period as String
        }
        String stamp = runStamp(reportDate, period)
        List<Map> outcomes = []
        List<Map> claimed = []
        digests.each { Map digest ->
            if (claim(ec, digest, tenantId, stamp)) claimed << digest
            else outcomes << [auditDigestId: digest.auditDigestId, sent: false, reason: "ALREADY_SENT"]
        }
        // The Unexplained CSV goes once per run: with the claim of the audit's first run digest, active or not.
        Map anchor = all ? all[0] : digests.find()
        boolean anchorClaimed = anchor != null && unexplainedTo && (claimed.any { it.auditDigestId == anchor.auditDigestId } ||
                (!active(anchor) && claim(ec, anchor, tenantId, stamp)))
        if (!claimed && !anchorClaimed) return outcomes

        Map built = null
        try {
            built = build(ec, tenantId, auditId, reportDate, period)
        } catch (Throwable t) {
            logger.warn("Audit run update for audit {} of tenant {}: not built: {}", auditId, tenantId, t.message)
        }
        Set<String> mailed = new HashSet<>()
        claimed.each { Map digest ->
            try {
                outcomes << [auditDigestId: digest.auditDigestId] + (built == null ? [sent: false, reason: "NO_REPORT"] :
                        deliver(ec, tenantId, digest, built, mailed))
            } catch (Throwable t) {
                logger.warn("Audit run update {} for tenant {} failed: {}", digest.auditDigestId, tenantId, t.message)
                outcomes << [auditDigestId: digest.auditDigestId, sent: false, reason: "FAILED"]
            }
        }
        // DAR-BE-378: a run with a check that did not run tells Darpan's alerts room once, with this run's claim; the client
        // room's card never carries it.
        if (claimed && built?.alert) darpan.ops.ErrorChatSupport.postAlertText(ec, built.alert as String)
        if (anchorClaimed && built != null) sendUnexplained(ec, tenantId, auditId, built, unexplainedTo)
        reviewReads(ec, tenantId, auditId, built?.readReport as Map)
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
        String reportPeriod = report.report?.period as String
        // DAR-BE-361: the groups and per-rule CSVs list the findings still open; the Unexplained CSV keeps the acknowledged too.
        List<Map> units = AuditReportAssembler.findingsByOrder(ec, tenantId, auditId, null, reportPeriod, null, "open")
        List<Map> everyUnit = AuditReportAssembler.findingsByOrder(ec, tenantId, auditId, null, reportPeriod)
        Map facts = AuditRunFacts.build(report, units) { String rule, String cause, String findingId -> fixTextOf(ec, tenantId, findingId) }
        Map words = words(ec, tenantId, facts)
        // DAR-BE-320: after the words, so the writer's fact sheet stays the findings only.
        Map readReport = readReport(ec, tenantId, auditId, reportDate, period)
        Map readSummary = AuditReadReport.summary(readReport)
        if (readSummary) facts = facts + [reads: readSummary]
        String base = TenantNotificationSupport.resolveAppBaseUrl(ec)
        Map<String, Map> groupsByKey = (facts.groups as List<Map>).collectEntries { [("${it.rule}|${it.cause ?: ''}".toString()): it] }
        Map<String, String> csvs = AuditRunFacts.csvByRule(units) { String rule, String cause ->
            Map group = groupsByKey["${rule}|${cause ?: ''}".toString()]
            group ? AuditRunMessage.fixLine(group, words) : null
        }
        EmailCopyReader copy = new EmailCopyReader(ec, AuditRunMessage.TEMPLATE)
        Map unexplained = AuditRunFacts.unexplainedCsv(everyUnit, tenantId, auditId) { String rule, String cause ->
            Map group = groupsByKey["${rule}|${cause ?: ''}".toString()]
            group ? AuditRunMessage.fixLine(group, words) : null
        }
        return [facts      : facts,
                unexplained: unexplained ? AuditRunMessage.unexplainedEmail(facts, unexplained.count as int, copy) +
                        [csv: unexplained.csv] : null,
                words      : words,
                readReport : readReport,
                subject    : AuditRunMessage.subject(facts, copy),
                html       : AuditRunMessage.emailHtml(facts, words, base, copy),
                text       : AuditRunMessage.emailText(facts, words, base, copy),
                card       : AuditRunMessage.card(facts, words, base, copy),
                // DAR-BE-378 / OC102: what did not run, and what the reads cost when one could cost less, for Darpan's alerts
                // room only; null when there is nothing for Darpan to act on.
                alert      : AuditRunMessage.staffText(facts, base, copy),
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
            String headline = normalize(out?.headline)
            Map fixes = out?.fixes instanceof Map ? out.fixes as Map : [:]
            // The writer answers nothing when the agent is off for the tenant: the same as no writer installed.
            return headline || fixes ? [headline: headline, fixes: fixes] : [:]
        } catch (Throwable t) {
            ec.message.clearErrors()
            logger.warn("Audit run update: the writer failed: {}", t.message)
            return [:]
        }
    }

    /** DAR-BE-358: the Unexplained CSV address(es) from the setting, valid ones only; empty when unset or unreadable. */
    static List<String> unexplainedTo() {
        try {
            return AuditDigestSupport.emailsOf(DarpanSettings.stringValue(UNEXPLAINED_TO))*.toLowerCase().unique()
        } catch (Throwable t) {
            logger.warn("Audit run update: setting {} not read: {}", UNEXPLAINED_TO, t.message)
            return []
        }
    }

    /**
     * DAR-BE-358. Darpan's own email after a run: every open finding no cause explains, as one CSV across all rules. Sent
     * only when there is one; never to the tenant (the address is a Darpan setting). Never throws.
     */
    static void sendUnexplained(def ec, String tenantId, String auditId, Map built, List<String> to) {
        Map mail = built.unexplained as Map
        if (!mail || !to) return
        try {
            sendEmail.call(ec, to.join(","), [runSubject: mail.subject, runHtml: mail.html, runText: mail.text],
                    [[fileName: mail.fileName, contentType: "text/csv; charset=UTF-8", contentText: mail.csv]])
            if (ec.message.hasError()) {
                logger.warn("Audit run update: Unexplained CSV for audit {} of tenant {} refused: {}", auditId, tenantId, ec.message.errorsString)
                ec.message.clearErrors()
            }
        } catch (Throwable t) {
            ec.message.clearErrors()
            logger.warn("Audit run update: Unexplained CSV for audit {} of tenant {} failed: {}", auditId, tenantId, t.message)
        }
    }

    /** The play's read report (DAR-BE-320), or null: a report that cannot be built never holds up the update. */
    static Map readReport(def ec, String tenantId, String auditId, Date reportDate, String period) {
        try {
            return AuditReadReport.forPlay(ec, tenantId, auditId, reportDate, period)
        } catch (Throwable t) {
            logger.warn("Audit run update: read report for audit {} not built: {}", auditId, t.message)
            return null
        }
    }

    /**
     * DAR-BE-320. A play with read flags goes to the optional reviewer (darpan-agent), which writes a diagnosis per flag
     * and raises one staff ticket per tenant, flag and connection. No flags, or no reviewer installed: nothing is sent.
     * After the update went out, so a slow model never delays it. Never throws.
     */
    static void reviewReads(def ec, String tenantId, String auditId, Map readReport) {
        if (!(readReport?.flags) || !ec.service.isServiceDefined(READ_REVIEW_SERVICE)) return
        try {
            AutomationExecutionSupport.dispatchInternalService(ec, READ_REVIEW_SERVICE,
                    [companyUserGroupId: tenantId, auditId: auditId, readReport: readReport])
            if (ec.message.hasError()) {
                logger.warn("Audit run update: the read reviewer refused: {}", ec.message.errorsString)
                ec.message.clearErrors()
            }
        } catch (Throwable t) {
            ec.message.clearErrors()
            logger.warn("Audit run update: the read reviewer failed: {}", t.message)
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

    /** mailed: the addresses this run's update already went to, so two digests of one audit never mail one person twice. */
    private static Map deliver(def ec, String tenantId, Map digest, Map built, Set<String> mailed) {
        String chatSpaceId = normalize(digest.chatSpaceId)
        Map destination = null
        if (chatSpaceId) {
            def space = TenantScopedFinder.findOneOwnedByTenant(ec, DarpanEntityConstants.TENANT_CHAT_SPACE,
                    "chatSpaceId", chatSpaceId, tenantId, "audit run update destination — owner checked against the digest's tenant")
            destination = TenantNotificationSupport.describeDestination(ec, tenantId, chatSpaceId, space)
            if (destination == null) logger.warn("Audit run update {}: chat space {} cannot be used", digest.auditDigestId, chatSpaceId)
        }
        // Members always; a digest's addresses are added (group mailboxes, people with no Darpan account), never instead.
        // DAR-BE-397: a digest's typed addresses may name a disabled account; members already exclude them
        List<String> emails = AuditDigestSupport.withoutDisabled(ec, (defaultRecipients(ec, tenantId) + extraEmails(digest)).unique())
                .findAll { !mailed.contains(it) }
        if (destination == null && !emails) return [sent: false, reason: "NO_DESTINATION"]
        boolean delivered = false
        if (destination != null) {
            // A Slack space takes the card's one line (its fallbackText) as text; cardsV2 is Google Chat's.
            boolean slack = destination.deliveryMode == TenantNotificationSupport.DELIVERY_SLACK_BOT ||
                    destination.chatProviderEnumId == TenantNotificationSupport.PROVIDER_SLACK
            Map card = built.card as Map
            Map delivery = TenantNotificationSupport.deliverToDestination(destination, slack ? [text: card.fallbackText] : card)
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
                mailed.addAll(emails)
            }
        }
        return delivered ? [sent: true, reason: null] : [sent: false, reason: "DELIVERY_FAILED"]
    }

    /**
     * A due automation the scan has not submitted yet (its tenant's lane was full, the scan was paused) still belongs to this
     * run. DAR-BE-378: 12 hours, not one: RAILS's checks due 15:15 IST on 2026-11-26 queued past an hour behind dead runs, the
     * audit read as finished and its update went out with them missing.
     */
    static final long STILL_DUE_MS = 12L * 60 * 60 * 1000

    /**
     * The audit's whole run has ended: no execution of its automations running or queued for a retry (the run page's
     * Run health reads the same); none of them waiting in or holding a RunAdmission slot, apart from the one that just
     * finished (an automation admitted but not yet started has no execution row); and none due within the last hour and
     * not yet submitted (the scan defers what its tenant's lane cannot start to its next tick). A due time older than
     * that means no scheduler is running here, and does not hold the update back. DAR-BE-328: nor any held for peak hours
     * or moved later by a peak period (see {@link #stillOwed}).
     */
    static boolean idle(def ec, String tenantId, Map audit, String finishedAutomationId, Timestamp now) {
        if (AuditSettingsSupport.progress(ec, tenantId, audit, now).active) return false
        def admission = AutomationExecutionSupport.getRunAdmission()
        // DAR-BE-328: the tenant's peak periods and zone are read once for this call, as the scan reads them once a tick.
        Map<String, Object> peakTick = AutomationExecutionSupport.newPeakTick(now)
        return !AuditSettingsSupport.automationsOfAudit(ec, tenantId, audit).any { Map automation ->
            String id = automation.automationId as String
            if (id == finishedAutomationId) return false
            if (admission.busy(AutomationExecutionSupport.admissionLabel(id))) return true
            if (automation.isActive != "Y" || automation.isArchived == "Y") return false
            return stillOwed(ec, peakTick, automation, now)
        }
    }

    /**
     * DAR-BE-328: whether the scan still owes this automation a start for this run, with the same peak-period start the
     * scan uses. One whose fire has come but whose start is later (held for peak hours, or moved later) is owed: it will
     * run. One whose start has come is owed for STILL_DUE_MS after that start. With no peak periods, or if reading them
     * fails, the start is the fire, exactly the rule before peak periods.
     */
    static boolean stillOwed(def ec, Map<String, Object> peakTick, Map automation, Timestamp now) {
        Timestamp fire = AutomationExecutionSupport.resolveCandidateFireTime(automation, now)
        if (fire == null) return false
        Instant start = AutomationExecutionSupport.peakStart(ec, peakTick, fire.toInstant(), automation.automationId as String,
                automation.companyUserGroupId as String, true, automation.lastScheduledFireTime as Timestamp, automation)
        if (start.isAfter(now.toInstant())) return !fire.after(now)
        return now.time - start.toEpochMilli() < STILL_DUE_MS
    }

    /**
     * Identifies one run: its morning and its period. DAR-BE-378 (Aditi 2026-11-26): one update a morning. The stamp used
     * to carry the latest execution's end too, so each check that finished after the update had gone out sent another one.
     */
    static String runStamp(Date reportDate, String period) {
        return "${reportDate}|${period ?: ''}".toString()
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

    /** The audit's run digests, active or not. */
    static List<Map> runDigests(def ec, String tenantId, String auditId) {
        return rows(ec, "darpan.audit.AuditDigest", tenantId).condition("auditId", auditId).condition("kindEnumId", RUN)
                .orderBy("auditDigestId").list()*.getMap()
    }

    private static boolean active(Map digest) { return digest.isActive != "N" }

    /** The digest's own addresses, lowercased, each once; one that is not an address is dropped and logged. */
    static List<String> extraEmails(Map digest) {
        List<String> raw = (normalize(digest?.emailAddresses) ?: "").split(/[,;]/)*.trim().findAll { it } as List<String>
        List<String> valid = AuditDigestSupport.emailsOf(raw.join(","))
        List<String> dropped = raw.findAll { !valid.contains(it) }
        if (dropped) logger.warn("Audit run update {}: not email addresses, left out: {}", digest.auditDigestId, dropped)
        return valid*.toLowerCase().unique()
    }

    // ---- the audit settings' "run email" (facade get/save#AuditSettings) ------------------------------------------

    /** The run digest the settings page edits: the audit's default one, else its first. */
    private static Map settingsDigest(List<Map> digests, String auditId) {
        return digests.find { it.auditDigestId == defaultDigestId(auditId) } ?: digests.find()
    }

    /**
     * {active (true when the audit has no run digest), alsoSendTo [addresses added to the members], members (how many
     * accounts it reaches by default: current members and Darpan admins), admins (how many of those are Darpan admins),
     * chatSpaceId (null for none), canEdit (the caller is a tenant admin)}.
     */
    static Map runEmailSettings(def ec, String tenantId, String auditId) {
        Map digest = settingsDigest(runDigests(ec, tenantId, auditId), auditId)
        return [active     : digest == null || active(digest),
                alsoSendTo : AuditDigestSupport.emailsOf(digest?.emailAddresses)*.toLowerCase().unique(),
                members    : defaultRecipients(ec, tenantId).size(),
                admins     : adminEmails(ec).size(),
                chatSpaceId: normalize(digest?.chatSpaceId),
                // Only a tenant admin (a Darpan admin is one for every tenant) may change it; others see it read-only.
                canEdit    : TenantAccessSupport.isTenantAdmin(ec, tenantId)]
    }

    /** What is wrong with a runEmail change, in words: each address that is not one, a chat space not the tenant's. */
    static List<String> runEmailErrors(def ec, String tenantId, Map runEmail) {
        List<String> errors = []
        if (runEmail.containsKey("alsoSendTo")) {
            Object raw = runEmail.alsoSendTo
            List<String> given = (raw instanceof Collection ? raw as List : (raw ? raw.toString().split(/[,;]/) as List : []))
                    .collect { it?.toString()?.trim() }.findAll { it }
            List<String> bad = given.findAll { !AuditDigestSupport.emailsOf(it) }
            if (bad) errors << "Not an email address: ${bad.join(', ')}.".toString()
        }
        String space = runEmail.containsKey("chatSpaceId") ? normalize(runEmail.chatSpaceId) : null
        if (space && TenantScopedFinder.findOneOwnedByTenant(ec, DarpanEntityConstants.TENANT_CHAT_SPACE, "chatSpaceId", space,
                tenantId, "audit run email settings: the chat space must be the tenant's own") == null) {
            errors << "Chat space ${space} was not found.".toString()
        }
        return errors
    }

    /** Writes a validated runEmail change onto the audit's run digest, made (RUN_<auditId>) when it has none. */
    static void saveRunEmail(def ec, String tenantId, String auditId, Map runEmail) {
        Map current = settingsDigest(runDigests(ec, tenantId, auditId), auditId)
        Map fields = [:]
        if (runEmail.containsKey("active")) fields.isActive = runEmail.active in [false, "false", "N"] ? "N" : "Y"
        if (runEmail.containsKey("alsoSendTo")) {
            Object raw = runEmail.alsoSendTo
            String joined = raw instanceof Collection ? (raw as List).collect { it?.toString() }.join(",") : (raw?.toString() ?: "")
            fields.emailAddresses = AuditDigestSupport.emailsOf(joined)*.toLowerCase().unique().join(", ") ?: null
        }
        if (runEmail.containsKey("chatSpaceId")) fields.chatSpaceId = normalize(runEmail.chatSpaceId)
        if (!fields) return
        if (current != null) {
            def row = rows(ec, "darpan.audit.AuditDigest", tenantId).condition("auditDigestId", current.auditDigestId).list().find()
            row.setAll(fields); row.update()
        } else {
            String id = defaultDigestId(auditId)
            if (id.length() > 40) id = ec.entity.sequencedIdPrimary("darpan.audit.AuditDigest", null, null)
            ec.entity.makeValue("darpan.audit.AuditDigest").setAll([auditDigestId: id, companyUserGroupId: tenantId, auditId: auditId,
                    kindEnumId: RUN, sendTime: "00:00", isActive: "Y"] + fields).create()
        }
    }

    /** RUN_<auditId>: one per audit, so two first updates finishing together make one row. */
    static String defaultDigestId(String auditId) { return "RUN_${auditId}".toString() }

    /**
     * The default run digest of an audit that has none: active, no addresses (the tenant's members), no chat space. It
     * exists to hold the run's claim. Two first updates both try; the second's create fails on the key and it reads the
     * first's row.
     */
    private static List<Map> ensureDefaultDigest(def ec, String tenantId, String auditId) {
        String id = defaultDigestId(auditId)
        if (id.length() > 40) id = ec.entity.sequencedIdPrimary("darpan.audit.AuditDigest", null, null)
        try {
            AutomationRuntimeSupport.runInNewTransaction(ec, "Could not make audit ${auditId}'s run update".toString()) { ->
                ec.entity.makeValue("darpan.audit.AuditDigest").setAll([auditDigestId: id, companyUserGroupId: tenantId,
                        auditId: auditId, kindEnumId: RUN, sendTime: "00:00", isActive: "Y"]).create()
            }
        } catch (Throwable ignored) {
            ec.message.clearErrors()   // made by the other execution's update a moment ago
        }
        return runDigests(ec, tenantId, auditId).findAll { active(it) }
    }

    /**
     * Who the update goes to by default: every current member of the tenant (UserGroupMember on its company group, as
     * add#TenantMember writes it) and every Darpan admin (product owner, 2026-11-10: "keep darpan admin in the email
     * list"), each with an enabled account and an email address, lowercased, each once. Admins reach tenants through the
     * admin groups, not memberships, so they are added by the rule behind isSuperAdmin: a current membership in
     * TenantAccessSupport.SUPER_ADMIN_GROUP_IDS (ADMIN, DARPAN_SUPER_ADMIN).
     */
    static List<String> defaultRecipients(def ec, String tenantId) {
        return (tenantMemberEmails(ec, tenantId) + adminEmails(ec)).unique().sort()
    }

    /** The tenant's current members' addresses. */
    static List<String> tenantMemberEmails(def ec, String tenantId) {
        return tenantId ? groupEmails(ec, [tenantId]) : []
    }

    /** The Darpan admins' addresses, by the rule behind isSuperAdmin. */
    static List<String> adminEmails(def ec) {
        return groupEmails(ec, TenantAccessSupport.SUPER_ADMIN_GROUP_IDS)
    }

    private static List<String> groupEmails(def ec, List<String> groupIds) {
        Timestamp now = new Timestamp(System.currentTimeMillis())
        def cf = ec.entity.conditionFactory
        List userIds = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.security.UserGroupMember",
                "run-update recipients: current members of the tenant's company group or the Darpan admin groups")
                .condition("userGroupId", EntityCondition.IN, groupIds).condition("fromDate", EntityCondition.LESS_THAN_EQUAL_TO, now)
                .condition(cf.makeCondition([cf.makeCondition("thruDate", EntityCondition.EQUALS, null),
                                             cf.makeCondition("thruDate", EntityCondition.GREATER_THAN, now)], EntityCondition.OR))
                .useCache(false).list()*.userId.unique()
        if (!userIds) return []
        return TenantScopedFinder.findGlobalUnscoped(ec, "moqui.security.UserAccount",
                "run-update recipients: accounts of those members")
                .condition("userId", EntityCondition.IN, userIds).useCache(false).list()
                .findAll { it.disabled != "Y" }
                .collect { normalize(it.emailAddress)?.toLowerCase() }
                .findAll { it && AuditDigestSupport.emailsOf(it) }
                .unique().sort()
    }

    private static def rows(def ec, String entityName, String tenantId) {
        return TenantScopedFinder.findGlobalUnscoped(ec, entityName, WHY).condition("companyUserGroupId", tenantId).useCache(false)
    }
}
