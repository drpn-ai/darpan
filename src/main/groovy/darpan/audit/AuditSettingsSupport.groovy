package darpan.audit

import darpan.facade.common.TenantAccessSupport
import groovy.transform.PackageScope
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
 * DAR-UI-063. An audit run's settings and its play button, from the audit's page.
 *
 * Settings: the audit's name, one daily schedule for every check it runs, and each statement's text and on/off. The
 * schedule is the audit's own (DAR-BE-199, Audit.scheduleTime/scheduleTimeZone/scheduleActive; Aditi 2026-11-04 "audits
 * should run everything on the audit schedule"). A check runs through an automation (DAR-BE-092,
 * AuditAutomationSupport.checksFor), which carries a copy of that schedule for the scheduler and is never set, paused or
 * resumed on its own (ownScheduleRefusal). Play runs every check over the days a person picks, through the same
 * automations, submitted to the background (run#AutomationNow is synchronous and a dozen checks would outlast the
 * gateway), where RunAdmission starts them as the instance and the tenant's share allow (DAR-BE-143); a check past that
 * waits, queued.
 *
 * A check whose run has no automation gets one the first time either is asked for: one per run (a run several checks
 * share serves them all), its sources copied from the run's compare scope (each naming the config it reads), on the
 * audit's schedule. It is written directly rather than through save#Automation, which requires two sources and so cannot
 * make one for a check that reads one system (an EVALUATE scope, e.g. RAILS R1).
 *
 * Every read and write is pinned to the tenant with an explicit companyUserGroupId condition.
 */
class AuditSettingsSupport {

    private static final String WHY = "audit settings, active tenant: explicit companyUserGroupId condition applied"

    /** The tenant's audit by its code, or by its id when no audit has that code (an agent holds the id it was given). */
    static Map auditOf(def ec, String tenantId, String auditCode) {
        String key = normalize(auditCode)
        if (!tenantId || !key) return null
        return (rows(ec, "darpan.audit.Audit", tenantId).condition("auditCode", key).list().find() ?:
                rows(ec, "darpan.audit.Audit", tenantId).condition("auditId", key).list().find())?.getMap()
    }

    static Map settings(def ec, String tenantId, Map audit) {
        Map<String, String> periods = AuditReportAssembler.enumCodes(ec)
        List<Map> statements = statementsOf(ec, tenantId, audit)
        Map<String, List<Map>> checksByStatement = checksByStatement(ec, tenantId, statements)
        List<Map> checks = checksByStatement.values().flatten().unique { it.checkId } as List<Map>
        List<Map> automations = automationsOf(ec, tenantId, checks)
        Map<String, String> runNames = runNames(ec, tenantId, checks*.savedRunId.findAll { it }.unique() as List<String>)
        Map schedule = scheduleOf(ec, audit)
        ZoneId zone = ZoneId.of(tenantZone(ec))
        Map levels = AuditReportAssembler.priorityLevels(ec, tenantId, statements)
        Map<String, List<Map>> reasons = reasonsOf(ec, tenantId, checksByStatement, (Map<String, Map<String, String>>) levels.overrides)
        return [audit     : [id: audit.auditCode, name: audit.auditName, period: periods[audit.periodEnumId as String]],
                playableFrom: playableFrom(ec, tenantId, audit, LocalDate.now(zone), zone),
                // DAR-BE-328: the instant today's peak hours end, only while now is inside them; play notes it.
                peakHoursUntil: AutomationExecutionSupport.peakHoursEndNow(ec, tenantId, ec.user.nowTimestamp)?.toString(),
                schedule  : [active          : schedule.active,
                             time            : schedule.time,
                             timeZone        : schedule.timeZone,
                             // An automation off the audit's schedule (made before DAR-BE-199): saving puts it back on.
                             mixed           : automations.any { offSchedule(it, schedule) },
                             automations     : automations.size(),
                             checksWithoutRun: checks.count { !it.automationId } as int],
                statements: statements.collect { Map st ->
                    [id    : st.statementId, code: st.statementCode, text: st.statementText, active: (st.isActive ?: "Y") != "N",
                     // DAR-BE-348: a statement with no level reads P2.
                     priority: ((Map) levels.statementLevels)[st.statementId as String] ?: AuditPriority.DEFAULT_LEVEL,
                     checks: checksByStatement[st.statementId as String].collect { Map c ->
                         [id: c.checkCode, label: c.label, savedRunId: c.savedRunId, runName: c.savedRunId ? runNames[c.savedRunId as String] : null]
                     },
                     reasons: reasons[st.statementId as String]]
                },
                // DAR-BE-207: the update emailed after each run.
                runEmail  : AuditRunUpdateSupport.runEmailSettings(ec, tenantId, audit.auditId as String)]
    }

    /**
     * Applies what changed. name: blank is ignored. statements: [{id, text, active, priority}], each of this audit. schedule:
     * {active, time HH:mm, timeZone}, written to every automation the audit's checks run through (made first if missing).
     * runEmail (DAR-BE-207): {active, alsoSendTo, chatSpaceId}, any left out unchanged; null changes nothing.
     * reasons (DAR-BE-348): [{statement (its id), conclusion, priority}]. A priority is a level's code (P0, P1, P2, KNOWN); null
     * clears a statement's (it reads P2) or deletes a reason's (it takes the statement's). A reason that closes on every check
     * of its statement takes no level. Errors go to ec.message; nothing is written when any input is wrong.
     */
    static void save(def ec, String tenantId, Map audit, String name, List<Map> statementChanges, Map schedule, Map runEmail = null,
                     List<Map> reasonChanges = null) {
        List<Map> statements = statementsOf(ec, tenantId, audit)
        Set<String> ours = statements*.statementId as Set<String>
        Map<String, String> levelIds = priorityEnumIds(ec)
        (statementChanges ?: []).each { Map change ->
            if (!ours.contains(change.id as String)) ec.message.addError("Statement ${change.id} is not part of audit ${audit.auditCode}.")
            else if (change.containsKey("text") && !normalize(change.text)) ec.message.addError("Statement ${change.id} needs its text.")
            else if (normalize(change.priority) && !levelIds[normalize(change.priority)]) ec.message.addError(notALevel(change.priority))
        }
        (reasonChanges ?: []).each { Map change ->
            String statementId = normalize(change.statement), conclusion = normalize(change.conclusion), level = normalize(change.priority)
            def reason = conclusion ? ec.entity.find("moqui.basic.Enumeration").condition("enumId", conclusion).useCache(true).one() : null
            if (!ours.contains(statementId)) ec.message.addError("Statement ${change.statement} is not part of audit ${audit.auditCode}.")
            else if (!conclusion) ec.message.addError("A reason's level needs its conclusion.")
            else if (level && !levelIds[level]) ec.message.addError(notALevel(level))
            else if (level && conclusion == "UNEXPLAINED") ec.message.addError("An Unexplained finding takes its statement's level.")
            else if (level && reason?.enumTypeId != "DarpanConclusion") ec.message.addError("${conclusion} is not a reason.".toString())
            else if (level && reasonClosesEverywhere(ec, tenantId, statementId, conclusion)) {
                String code = statements.find { it.statementId == statementId }?.statementCode
                ec.message.addError("${reason.description ?: conclusion} closes every finding on ${code}'s checks, so it takes no level.".toString())
            }
        }
        Map current = scheduleOf(ec, audit)
        String time = schedule ? normalize(schedule.time) ?: current.time : null
        String zone = schedule ? normalize(schedule.timeZone) ?: current.timeZone : null
        if (time && !(time ==~ /([01]\d|2[0-3]):[0-5]\d/)) ec.message.addError("The time must be HH:mm, got ${time}.")
        if (zone) {
            try { ZoneId.of(zone) } catch (Exception ignored) { ec.message.addError("${zone} is not a time zone.") }
        }
        if (runEmail != null) {
            // Who gets every run's findings and CSVs is a tenant admin's call (security review, 2026-11-10); the rest of the
            // settings keep the edit access the service checks.
            if (!TenantAccessSupport.isTenantAdmin(ec, tenantId)) {
                ec.message.addError("Only a tenant admin can change who gets audit ${audit.auditCode}'s run email.".toString())
            } else {
                AuditRunUpdateSupport.runEmailErrors(ec, tenantId, runEmail).each { ec.message.addError(it) }
            }
        }
        if (ec.message.hasError()) return

        Timestamp now = ec.user.nowTimestamp
        if (normalize(name) && normalize(name) != audit.auditName) {
            def row = rows(ec, "darpan.audit.Audit", tenantId).condition("auditId", audit.auditId).list().find()
            row.set("auditName", normalize(name)); row.update()
        }
        (statementChanges ?: []).each { Map change ->
            def row = rows(ec, "darpan.audit.AuditStatement", tenantId).condition("statementId", change.id).list().find()
            if (change.containsKey("text")) row.set("statementText", normalize(change.text))
            if (change.containsKey("active")) row.set("isActive", change.active in [true, "true", "Y"] ? "Y" : "N")
            row.update()
        }
        (statementChanges ?: []).findAll { it.containsKey("priority") }.each { Map change ->
            ec.service.sync().name("facade.AuditFacadeServices.set#AuditStatementPriority").parameters([companyUserGroupId: tenantId,
                    statementId: change.id, priorityEnumId: levelIds[normalize(change.priority)]]).call()
        }
        (reasonChanges ?: []).each { Map change ->
            ec.service.sync().name("facade.AuditFacadeServices.set#AuditReasonPriority").parameters([companyUserGroupId: tenantId,
                    statementId: normalize(change.statement), conclusionEnumId: normalize(change.conclusion),
                    priorityEnumId: levelIds[normalize(change.priority)]]).call()
        }
        if (schedule) {
            Map stamp = [scheduleTime: time, scheduleTimeZone: zone, scheduleActive: schedule.active in [true, "true", "Y"] ? "Y" : "N"]
            def row = rows(ec, "darpan.audit.Audit", tenantId).condition("auditId", audit.auditId).list().find()
            row.setAll(stamp); row.update()
            Map stamped = audit + stamp
            ensureAutomations(ec, tenantId, stamped)   // a new check's automation is made on the schedule just saved
            applySchedule(ec, tenantId, stamped, now)
        }
        if (runEmail != null) AuditRunUpdateSupport.saveRunEmail(ec, tenantId, audit.auditId as String, runEmail)
    }

    static final String CLOSES_ALL = "all", CLOSES_SOME = "some", CLOSES_NONE = "none"

    /**
     * DAR-BE-348, builder call 5. True when the reason (a conclusion) is expected on every check of the statement that can
     * reach it, so every finding it reaches closes and a level would never apply: settings refuses one, and so does the agent.
     * False for another tenant's statement.
     */
    static boolean reasonClosesEverywhere(def ec, String tenantId, String statementId, String conclusionEnumId) {
        if (!tenantId || !normalize(statementId) || !normalize(conclusionEnumId)) return false
        List<Map> statements = rows(ec, "darpan.audit.AuditStatement", tenantId).condition("statementId", normalize(statementId)).list()*.getMap()
        if (!statements) return false
        Map reason = reasonsOf(ec, tenantId, checksByStatement(ec, tenantId, statements), [:])[normalize(statementId)]
                .find { it.conclusion == normalize(conclusionEnumId) }
        return reason?.closes == CLOSES_ALL
    }

    /**
     * DAR-BE-348. Each statement's reasons, in its checks' order: the conclusions its checks' runs can reach (the
     * RuleSetConclusionRule rows of each run's compare scopes), DAR-BE-353 only those the rows its link reports can reach, so
     * statements sharing one run do not each list the whole tree (reasonsFrom). A cause hidden on a run (HIDE) never becomes a finding
     * there; Unexplained is no reason (its findings carry none and take the statement's level). checks: how many of the
     * statement's checks reach it; closesOn: on how many it is expected (EXPECTED, which closes the finding); closes: all,
     * some or none. An override no check reaches any more is listed as well (checks 0), so its level shows and can be cleared.
     * (AuditStatementCheck.conclusionCodes is retired, DAR-BE-196, and not read.)
     */
    private static Map<String, List<Map>> reasonsOf(def ec, String tenantId, Map<String, List<Map>> checksByStatement,
                                                    Map<String, Map<String, String>> overrides) {
        List<String> runIds = checksByStatement.values().flatten()*.savedRunId.findAll { it }.collect { it as String }.unique()
        Map<String, String> runOfScope = runIds ? rows(ec, "darpan.rule.RuleSetCompareScope", tenantId)
                .condition("ruleSetId", EntityCondition.IN, runIds).list().collectEntries { [(it.compareScopeId as String): it.ruleSetId as String] } : [:]
        Map<String, List<Map>> rulesByRun = [:]
        (runOfScope ? rows(ec, "darpan.rule.RuleSetConclusionRule", tenantId)
                .condition("compareScopeId", EntityCondition.IN, runOfScope.keySet() as List).orderBy("sequenceNum").list() : []).each { rule ->
            rulesByRun.computeIfAbsent(runOfScope[rule.compareScopeId as String]) { [] } << rule.getMap()
        }
        Map<String, String> labels = [:]
        Closure<String> labelOf = { String id ->
            labels.computeIfAbsent(id) {
                def e = ec.entity.find("moqui.basic.Enumeration").condition("enumId", id).useCache(true).one()
                return (e?.description ?: e?.enumCode ?: id) as String
            }
        }
        return reasonsFrom(checksByStatement, rulesByRun, overrides, labelOf)
    }

    /**
     * DAR-BE-353. reasonsOf without the reads: checksByStatement's checks carry savedRunId and their link's linkDiffTypes;
     * rulesByRun holds each run's RuleSetConclusionRule rows (all its compare scopes) in sequence order. A check reaches the
     * nodes its link's rows can conclude in (reaches); hidden and expected are read on those nodes alone, as the run concludes
     * that check's rows: any HIDE node hides the cause, any EXPECTED one expects it. A link's ruleIds do not narrow: a cause
     * tree node names a bucket, not the rule that raised the row, so every rule check of a run reaches all its RULE causes.
     */
    static Map<String, List<Map>> reasonsFrom(Map<String, List<Map>> checksByStatement, Map<String, List<Map>> rulesByRun,
                                              Map<String, Map<String, String>> overrides, Closure<String> labelOf) {
        Map<String, List<Map>> nodesByRun = rulesByRun.collectEntries { String run, List<Map> rules -> [(run): AuditSettingsSupport.nodesOf(rules)] }
        return checksByStatement.collectEntries { String statementId, List<Map> checks ->
            Map<String, int[]> counts = new LinkedHashMap<>()   // conclusion -> [checks, closesOn]
            checks.each { Map check ->
                Map<String, Boolean> causes = new LinkedHashMap<>()   // conclusion -> expected, in rule order
                Set<String> hidden = [] as Set
                // DAR-BE-398: get, not [x as String]: Groovy 5 reads a subscript on a null String as a property named null and throws.
                (nodesByRun.get(check.savedRunId) ?: []).each { Map node ->
                    // Qualified: a bare static call from a closure nested in a closure fails with "argument type mismatch".
                    if (!AuditSettingsSupport.reaches(check.linkDiffTypes as String, node.bucket as String)) return
                    if (node.outcome == "HIDE") hidden.add(node.conclusion as String)
                    causes.put(node.conclusion as String, (causes[node.conclusion as String] ?: false) || node.outcome == "EXPECTED")
                }
                causes.each { String conclusion, Boolean expected ->
                    if (hidden.contains(conclusion)) return
                    int[] c = counts.computeIfAbsent(conclusion) { new int[2] }
                    c[0]++
                    if (expected) c[1]++
                }
            }
            Map<String, String> levels = overrides?.get(statementId) ?: [:]
            List<Map> out = counts.collect { String conclusion, int[] c ->
                [conclusion: conclusion, label: labelOf(conclusion), priority: levels[conclusion],
                 closes: c[1] == c[0] ? CLOSES_ALL : c[1] > 0 ? CLOSES_SOME : CLOSES_NONE, closesOn: c[1], checks: c[0]] as Map
            }
            levels.keySet().findAll { !counts.containsKey(it) }.each { String conclusion ->
                out << [conclusion: conclusion, label: labelOf(conclusion), priority: levels[conclusion], closes: CLOSES_NONE,
                        closesOn: 0, checks: 0]
            }
            [(statementId): out]
        } as Map<String, List<Map>>
    }

    /** NO_BUCKET: a node whose path names two buckets, which no row can match (ConclusionTreeSupport.matches reads each). */
    private static final String NO_BUCKET = "-"
    /** A repeated key's partner rows, DUPLICATE_IN_FILE_y_PAIRED. */
    private static final String PAIRED_SUFFIX = "_PAIRED"

    /**
     * DAR-BE-353. A run's cause nodes: [conclusion, outcome, bucket], the bucket the one its path names (its own, else its
     * nearest ancestor's; blank: any row; NO_BUCKET when the path names two). Unexplained and blank conclusions are dropped.
     */
    private static List<Map> nodesOf(List<Map> rules) {
        return chainsOf(rules).collect { Map chain ->
            Set<String> buckets = chain.buckets as Set<String>
            [conclusion: chain.conclusion, outcome: chain.outcome,
             bucket: buckets.size() > 1 ? NO_BUCKET : buckets ? buckets.first() : null]
        } as List<Map>
    }

    /**
     * Each concluding node of one tree (Unexplained and blank conclusions dropped) with the appliesToBucket values on its
     * chain to the root: [conclusion, outcome, buckets]. A cycle ends the walk.
     */
    private static List<Map> chainsOf(List<Map> rules) {
        Map<Integer, Map> bySeq = rules.findAll { it.sequenceNum != null }.collectEntries { [((it.sequenceNum as int)): it] }
        return rules.findResults { Map rule ->
            String conclusion = normalize(rule.conclusionEnumId)
            if (!conclusion || conclusion == "UNEXPLAINED") return null
            Set<String> buckets = new LinkedHashSet<>()
            Set<Integer> seen = [] as Set
            for (Map n = rule; n != null && seen.add(n.sequenceNum as Integer);
                 n = normalize(n.parentSequenceNum) ? bySeq[n.parentSequenceNum as int] : null) {
                if (normalize(n.appliesToBucket)) buckets.add(normalize(n.appliesToBucket))
            }
            return [conclusion: conclusion, outcome: normalize(rule.outcome), buckets: buckets]
        } as List<Map>
    }

    /** A cause chain that names no bucket: it applies to every row. */
    static final String ANY_BUCKET = "*"

    /**
     * DAR-OPS-029 D11 (OC53). The buckets a run's cause tree targets: for each concluding node (Unexplained skipped), every
     * appliesToBucket on its chain to the root, or ANY_BUCKET when the chain names none. A chain naming two buckets
     * (reaches' NO_BUCKET) adds each. Empty for a run with no tree. One reading of the tree for findingsFrom and the settings
     * page; read per compare scope, since a node's parent is in its own tree.
     */
    static Set<String> targetedBuckets(def ec, String tenantId, String savedRunId) {
        if (!tenantId || !normalize(savedRunId)) return [] as Set<String>
        List<String> scopeIds = rows(ec, "darpan.rule.RuleSetCompareScope", tenantId).condition("ruleSetId", normalize(savedRunId))
                .list()*.compareScopeId.collect { it as String }
        if (!scopeIds) return [] as Set<String>
        Map<String, List<Map>> byScope = rows(ec, "darpan.rule.RuleSetConclusionRule", tenantId)
                .condition("compareScopeId", EntityCondition.IN, scopeIds).orderBy("sequenceNum").list()*.getMap()
                .groupBy { it.compareScopeId as String }
        Set<String> out = new LinkedHashSet<>()
        byScope.values().each { List<Map> tree -> out.addAll(targetedBucketsFrom(tree)) }
        return out
    }

    /** targetedBuckets over one tree's RuleSetConclusionRule rows, without the reads. */
    static Set<String> targetedBucketsFrom(List<Map> rules) {
        Set<String> out = new LinkedHashSet<>()
        chainsOf(rules ?: []).each { Map chain ->
            Set<String> buckets = chain.buckets as Set<String>
            if (buckets) out.addAll(buckets) else out.add(ANY_BUCKET)
        }
        return out
    }

    /**
     * DAR-BE-353. Whether a statement link's rows can conclude in a node's bucket. A link that names no row type reports every
     * row but a repeated key's partner rows (DAR-OPS-029 S12, OC14: only a link naming DUPLICATE_IN_FILE_y_PAIRED reports
     * them, as findingsFrom records); a single-sided run's rows all conclude in FINDING (RunConclusionSupport), whatever their
     * type; a two-sided run's row type maps to its bucket exactly as the run concludes it (RunConclusionSupport.bucketOf).
     */
    static boolean reaches(String linkDiffTypes, String bucket) {
        if (bucket == NO_BUCKET) return false
        List<String> types = (linkDiffTypes ?: "").split(",")*.trim().findAll { it }
        if (!types && bucket?.endsWith(PAIRED_SUFFIX)) return false
        if (!bucket || !types || bucket == "FINDING") return true
        return types.any { String type -> RunConclusionSupport.bucketOf([diffType: type], null, null) == bucket }
    }

    /** DAR-BE-348. code -> enumId of each level (P0, P1, P2, KNOWN). */
    private static Map<String, String> priorityEnumIds(def ec) {
        return ec.entity.find("moqui.basic.Enumeration").condition("enumTypeId", "AuditPriority").useCache(true).list()
                .findAll { it.enumCode in AuditPriority.LEVELS }.collectEntries { [(it.enumCode as String): it.enumId as String] }
    }

    private static String notALevel(Object code) {
        return "${code} is not a priority: P0, P1, P2 or KNOWN.".toString()
    }

    @PackageScope static String tenantZone(def ec) {
        return TenantAccessSupport.resolveActiveTenantTimeZone(ec) ?: "UTC"
    }

    /** The audit's statements in report order; activeOnly drops those turned off (they run nothing). */
    @PackageScope static List<Map> statementsOf(def ec, String tenantId, Map audit, boolean activeOnly = false) {
        List<Map> all = rows(ec, "darpan.audit.AuditStatement", tenantId).condition("auditId", audit.auditId)
                .orderBy(["sequenceNum", "statementCode"]).list()*.getMap()
        return activeOnly ? all.findAll { (it.isActive ?: "Y") != "N" } : all
    }

    @PackageScope static Map<String, List<Map>> checksByStatement(def ec, String tenantId, List<Map> statements) {
        Map<String, List<Map>> out = statements.collectEntries { [(it.statementId as String): []] }
        if (!statements) return out
        List<Map> links = rows(ec, "darpan.audit.AuditStatementCheck", tenantId)
                .condition("statementId", EntityCondition.IN, statements*.statementId).orderBy(["sequenceNum", "checkId"]).list()*.getMap()
        Map<String, Map> checks = links ? rows(ec, "darpan.audit.AuditCheck", tenantId)
                .condition("checkId", EntityCondition.IN, links*.checkId.unique()).list().collectEntries { [(it.checkId): it.getMap()] } : [:]
        // DAR-BE-353: each check as linked to that statement, with the link's row types and rules (findingsFrom's narrowing).
        links.each { Map l ->
            Map check = checks[l.checkId as String]
            if (check) out[l.statementId as String] << (check + [linkDiffTypes: l.diffTypes, linkRuleIds: l.ruleIds])
        }
        return out
    }

    /** Every automation the audit's checks run through, on any of its statements (the settings page's count). */
    static List<Map> automationsOfAudit(def ec, String tenantId, Map audit) {
        List<Map> checks = checksByStatement(ec, tenantId, statementsOf(ec, tenantId, audit)).values().flatten()
                .unique { it.checkId } as List<Map>
        return automationsOf(ec, tenantId, checks)
    }

    @PackageScope static List<Map> automationsOf(def ec, String tenantId, List<Map> checks) {
        return automationRows(ec, tenantId, checks*.automationId.findAll { it }.unique() as List<String>)*.getMap()
    }

    @PackageScope static List automationRows(def ec, String tenantId, List<String> ids) {
        return ids ? rows(ec, "darpan.reconciliation.ReconciliationAutomation", tenantId)
                .condition("automationId", EntityCondition.IN, ids).orderBy("automationId").list() : []
    }

    @PackageScope static Map<String, String> runNames(def ec, String tenantId, List<String> ids) {
        return ids ? rows(ec, "darpan.rule.RuleSet", tenantId).condition("ruleSetId", EntityCondition.IN, ids).list()
                .collectEntries { [(it.ruleSetId): it.ruleSetName] } : [:]
    }

    @PackageScope static def rows(def ec, String entityName, String tenantId) {
        return darpan.facade.common.TenantScopedFinder.findGlobalUnscoped(ec, entityName, WHY)
                .condition("companyUserGroupId", tenantId).useCache(false)
    }

    // DAR-OPS-029 S12: moved by responsibility; these delegates keep the callers (AuditFacadeServices.xml, AuditAutomationSupport,
    // the agent pipeline, the migrations and tests) working until S99 repoints them.
    static final String DEFAULT_TIME = AuditSchedule.DEFAULT_TIME
    static Map scheduleOf(def ec, Map audit) { AuditSchedule.scheduleOf(ec, audit) }
    static boolean offSchedule(Map automation, Map schedule) { AuditSchedule.offSchedule(automation, schedule) }
    static void applySchedule(def ec, String tenantId, Map audit, Timestamp now) { AuditSchedule.applySchedule(ec, tenantId, audit, now) }
    static Map auditOfAutomation(def ec, String tenantId, String automationId) { AuditSchedule.auditOfAutomation(ec, tenantId, automationId) }
    static String ownScheduleRefusal(def ec, def automation) { AuditSchedule.ownScheduleRefusal(ec, automation) }
    static String timeOf(String cron) { AuditSchedule.timeOf(cron) }
    static String playableFrom(def ec, String tenantId, Map audit, LocalDate today, ZoneId zone) { AuditPlay.playableFrom(ec, tenantId, audit, today, zone) }
    static List<Map> run(def ec, String tenantId, Map audit, LocalDate from, LocalDate thru, boolean fullRead, List<String> checkCodes = null) {
        AuditPlay.run(ec, tenantId, audit, from, thru, fullRead, checkCodes)
    }
    static Map retryCheck(def ec, String tenantId, Map audit, String checkCode, Date reportDate, String period) {
        AuditPlay.retryCheck(ec, tenantId, audit, checkCode, reportDate, period)
    }
    static boolean verifyFinding(def ec, String tenantId, String findingId) { AuditPlay.verifyFinding(ec, tenantId, findingId) }
    static Map progress(def ec, String tenantId, Map audit, Timestamp now) { AuditProgress.progress(ec, tenantId, audit, now) }
    static List<Map> steps(def ec, String tenantId, String runResultId, String automationId, String previousRunResultId = null) {
        AuditProgress.steps(ec, tenantId, runResultId, automationId, previousRunResultId)
    }
    static List<String> ensureAutomations(def ec, String tenantId, Map audit) { AuditCheckAutomation.ensureAutomations(ec, tenantId, audit) }
}
