package darpan.agent.pipeline

import darpan.audit.AuditAutomationSupport
import darpan.audit.AuditReportAssembler
import darpan.datasource.DataDefinedExtract
import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.facade.reconciliation.RunConclusionStep
import darpan.facade.reconciliation.RunVerificationSupport
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.automation.ExtractReplaySupport
import darpan.reconciliation.conclusion.ConclusionCatalogSupport
import darpan.reconciliation.conclusion.ConclusionKeySupport
import darpan.reconciliation.conclusion.JsonRecordStream
import darpan.reconciliation.conclusion.RunConclusionAuthoringSupport
import darpan.reconciliation.conclusion.ConclusionTreeSupport
import darpan.reconciliation.conclusion.RunConclusionSupport
import darpan.reconciliation.source.SourceFilterSupport
import groovy.json.JsonSlurper
import org.moqui.entity.EntityCondition

import java.sql.Timestamp
import java.time.LocalDate
import java.time.ZoneId

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-159. What an agent's proposed change would do to the audit, before a person approves it: which open findings
 * would close and which records would newly raise one. Computed only from data already landed (a succeeded execution's
 * extract and result document, or the findings ledger itself): no system is read live and nothing is written, no
 * finding, execution, ledger row, pending mark or file.
 *
 * <p>Result: [supported, reason, basisExecutionId, closed, opened, unchanged, limits]. Open means AUDFS_OPEN (a finding is
 * only open or fixed). {@code closed}: findingIds of open findings the change would stop reporting; {@code opened}: recordKeys
 * it would newly report; {@code unchanged}: open findings in scope that stay. {@code limits} says what the numbers do not
 * cover: CLOSES_ONLY (the kind can only close), LATEST_WINDOW_ONLY (the basis is each automation's latest landed window),
 * OPENED_NOT_COMPUTED (some newly unmatched records may open findings that cannot be decided from landed data; reason says
 * so too, and closed still holds), WIDENING_NOT_COMPUTED (the change also loosens: what it would let back in is not read).</p>
 *
 * <p>Basis and period: the basis is, per automation of the run, its latest succeeded execution whose files are still on
 * disk. A basis may close only open findings whose period (in its audit's period kind) contains the basis window, first to
 * last day in the automation's zone; a finding of another period is unchanged.</p>
 *
 * <ul>
 *   <li>EXCLUSION_FILTER: refused like the extract would refuse it (SOURCE_TAKES_NO_FILTERS, FILTER_FIELD_NOT_OFFERED).
 *   The side's candidate filters run in memory over the basis extract; an open finding whose recordKey they now drop
 *   closes, unless the record still raises it. Two-sided runs: a dropped record the other side's landed extract holds
 *   becomes "missing" on the dropped side (MISSING_IN_FILE_1 when FILE_1 drops it), so a link counting that diff type
 *   reports it (opened, or kept), unless the run marks a cause expected (or its brain may): the new row's cause is not
 *   computed, so it cannot be decided (OPENED_NOT_COMPUTED), and an open finding for that record is left out of closed. A rule on a
 *   data-defined field no landed record carries is refused (FIELD_NOT_IN_LANDED_RECORDS). EVALUATE runs: CLOSES_ONLY.</li>
 *   <li>STATEMENT_NARROWING: a link names diff types and rules only (DAR-BE-196), and a finding records neither, so it is
 *   refused (DIFF_TYPE_NOT_ON_FINDINGS).</li>
 *   <li>EXPECTED_CAUSES: from AuditFinding rows alone (CLOSES_ONLY); every open finding of a check on the run whose cause
 *   is newly expected closes, any period (no statement counts an expected cause).</li>
 *   <li>FIX_TEXT: display only.</li>
 *   <li>CONCLUSION_ROWS: the run's conclude path (RunConclusionSupport.concludeEach) over the basis result rows twice,
 *   saved rows then candidate rows (built by the save's own RunConclusionAuthoringSupport.toRows); a record whose
 *   conclusion changes closes where a statement stops counting it and opens where one starts. The candidate keeps each
 *   rule's saved outcome as save#RunConclusions does (DAR-BE-198), and a row whose cause is expected counts nowhere.</li>
 * </ul>
 */
class AuditChangeBacktest {

    static final String CLOSES_ONLY = "CLOSES_ONLY"
    static final String LATEST_WINDOW_ONLY = "LATEST_WINDOW_ONLY"
    static final String OPENED_NOT_COMPUTED = "OPENED_NOT_COMPUTED"
    static final String WIDENING_NOT_COMPUTED = "WIDENING_NOT_COMPUTED"
    /** Succeeded executions looked at per automation, newest first, for one whose files are still on disk. */
    static final int BASIS_LOOKBACK = 20
    private static final String WHY = "agent backtest: explicit companyUserGroupId condition applied"

    /** current: the lever state the planner merged into (AuditChangeLevers.current); read here when not given. */
    static Map run(def ec, String tenantId, String kind, Map target, Map merged, Map current = null) {
        // The planner pins the proposal's tenant; it must be the session's active tenant, whose rows every read here is.
        if (!tenantId || tenantId != TenantAccessSupport.currentActiveTenantUserGroupId(ec)) return unsupported("TENANT_MISMATCH")
        if (merged == null) return unsupported("NO_CHANGE")
        try {
            switch (kind) {
                case "EXCLUSION_FILTER": return exclusion(ec, tenantId, target, merged, current)
                case "STATEMENT_NARROWING": return unsupported("DIFF_TYPE_NOT_ON_FINDINGS")
                case "EXPECTED_CAUSES": return expectedCauses(ec, tenantId, merged, current ?: AuditChangeLevers.current(ec, kind, target))
                case "FIX_TEXT": return result(true, "DISPLAY_ONLY", null, [], [], 0, [])
                case "CONCLUSION_ROWS": return conclusionRows(ec, tenantId, target, merged)
                // A schedule or a new statement moves no landed finding.
                case "AUDIT_SETTINGS":
                case "ADD_STATEMENT":
                case "ADD_CHECK": return unsupported("NOT_APPLICABLE")
                default: return unsupported("UNKNOWN_KIND")
            }
        } catch (Exception e) {
            // A backtest never fails a plan (a truncated extract throws a checked JsonParseException): say why instead.
            return unsupported("BACKTEST_FAILED") + [detail: (normalize(e.message) ?: e.class.simpleName).take(255)]
        }
    }

    // ---------------------------------------------------------------- EXCLUSION_FILTER

    private static Map exclusion(def ec, String tenantId, Map target, Map merged, Map current) {
        String savedRunId = target.savedRunId as String
        String side = target.fileSide as String
        String other = side == ExtractReplaySupport.SIDE_1 ? ExtractReplaySupport.SIDE_2 : ExtractReplaySupport.SIDE_1
        def scope = scopeOf(ec, tenantId, savedRunId)
        Map sideFields = (Map) ((Map) ((Map) merged.run)?.sides)?.get(side)
        if (scope == null || sideFields == null) return unsupported("NO_RUN")
        boolean evaluate = "EVALUATE".equalsIgnoreCase(scope.scopeMode as String)

        // As the extract decides: a connector that declares no filter parameter never receives the rules.
        Map connector = RunVerificationSupport.resolveConnector(ec, sideFields)
        if (!normalize(connector?.filterParameterName)) return unsupported("SOURCE_TAKES_NO_FILTERS")
        List<Map> candidate = ((List<Map>) (((Map) merged.filters)?.get(side) ?: [])).withIndex().collect { Map f, int i ->
            [sequenceNum: i + 1, fieldExpression: f.fieldExpression, operator: f.operator, filterValues: f.filterValues] as Map<String, Object>
        }
        List<Map<String, Object>> rules = SourceFilterSupport.parseRules(SourceFilterSupport.toRecordFieldRules(candidate))
        Set<String> ruleFields = rules.collect { it.fieldExpression as String } as Set<String>
        boolean dataDefined = connector.extractServiceName == DataDefinedExtract.SERVICE_NAME && normalize(connector.dataSourceDefinitionId)
        if (dataDefined) {
            Set<String> offered = offeredFilterFields(ec, connector.dataSourceDefinitionId as String)
            if (offered == null) return unsupported("SOURCE_TAKES_NO_FILTERS")
            if (!offered.containsAll(ruleFields)) return unsupported("FILTER_FIELD_NOT_OFFERED")
        }
        Map key = RunConclusionStep.keySpecFor(ec, scope.compareScopeId as String, side)
        if (!key.keyReliable) return unsupported("KEY_NOT_REBUILDABLE")
        Map otherKey = evaluate ? null : RunConclusionStep.keySpecFor(ec, scope.compareScopeId as String, other)
        // A newly missing row's cause is not computed here: where the run marks a cause expected (or its brain may), the row
        // may be one no statement counts.
        boolean causesMayBeExpected = RunConclusionStep.brainKind(scope.conclusionBrain) || unexplainedExpected(scope) ||
                RunConclusionStep.scopeRules(ec, scope.compareScopeId as String).any { it.outcome == RunConclusionSupport.OUTCOME_EXPECTED }

        List<Map> bases = bases(ec, tenantId, savedRunId, evaluate ? [side] : [side, other])
        if (!bases) return unsupported("NO_LANDED_EXTRACT")

        List<String> limits = [LATEST_WINDOW_ONLY]
        if (evaluate) limits.add(0, CLOSES_ONLY)
        Map state = current ?: AuditChangeLevers.current(ec, "EXCLUSION_FILTER", target)
        if (loosens(((Map) state?.filters)?.get(side) as List<Map>, candidate)) {
            limits << WIDENING_NOT_COMPUTED
        }
        List<String> checkIds = checkIdsOf(ec, tenantId, savedRunId)
        List<Map> links = linksOf(ec, tenantId, checkIds)
        // A record the other side holds turns "missing" on the side that drops it.
        String newType = side == ExtractReplaySupport.SIDE_1 ? "MISSING_IN_FILE_1" : "MISSING_IN_FILE_2"
        boolean undecided = false
        long recordsRead = 0
        Set<String> carried = new HashSet<String>()
        // Findings a new MISSING row may keep open on a link that also narrows by conclusion: never listed as closed.
        Set<String> maybeKept = new HashSet<String>()
        Map<String, Set<String>> droppedByBasis = [:]
        Set<String> reportedAfter = new HashSet<String>()
        bases.each { Map basis ->
            Set<String> dropped = new LinkedHashSet<String>()
            JsonRecordStream.forEachRecord(fileOf(basis, side), "records") { Map record ->
                recordsRead++
                if (dataDefined) ruleFields.each { String f -> if (record.containsKey(f)) carried.add(f) }
                if (SourceFilterSupport.evaluate(record, rules) == null) return
                String recordKey = ConclusionKeySupport.compareKey(record, (List<String>) key.keyFields, (String) key.idNormalizer)
                if (recordKey) dropped.add(recordKey)
            }
            droppedByBasis[basis.executionId as String] = dropped
            if (evaluate || !dropped) return
            // Keys the other side cannot be keyed by: any dropped record may still be held there.
            boolean otherKnown = otherKey.keyReliable as boolean
            Set<String> held = otherKnown ? new HashSet<String>() : dropped
            if (otherKnown) {
                JsonRecordStream.forEachRecord(fileOf(basis, other), "records") { Map record ->
                    String k = ConclusionKeySupport.compareKey(record, (List<String>) otherKey.keyFields, (String) otherKey.idNormalizer)
                    if (k && dropped.contains(k)) held.add(k)
                }
            } else {
                undecided = true
            }
            held.each { String k ->
                links.each { Map link ->
                    if (!AuditAutomationSupport.matches(link.diffTypes as String, newType) ||
                            !AuditAutomationSupport.matches(link.ruleIds as String, null)) return
                    String id = findingId(link.checkId, link.statementId, k)
                    if (!otherKnown || causesMayBeExpected) { undecided = true; maybeKept.add(id); return }
                    reportedAfter.add(id)
                }
            }
        }
        // Not the extract's own check (checkFilterFields tests only the offered fields): a rule on a field no landed record
        // carries would drop nothing here, so the backtest would read as "closes nothing" for the wrong reason. An empty
        // basis carries nothing and drops nothing, which is a real answer.
        if (dataDefined && recordsRead > 0 && !carried.containsAll(ruleFields)) return unsupported("FIELD_NOT_IN_LANDED_RECORDS")
        if (undecided) limits << OPENED_NOT_COMPUTED

        List open = openFindings(ec, tenantId, checkIds, null)
        Map<String, String> periodKinds = periodKindsOf(ec, tenantId, open)
        Set<String> closedIds = new TreeSet<String>()
        bases.each { Map basis ->
            Set<String> dropped = droppedByBasis[basis.executionId as String]
            open.each { f ->
                String id = findingId(f.checkId, f.statementId, f.recordKey)
                if (dropped.contains(f.recordKey as String) && !reportedAfter.contains(id) && !maybeKept.contains(id) &&
                        periodHolds(f, periodKinds, basis)) closedIds.add(f.findingId as String)
            }
        }
        Set<String> openIds = open.collect { findingId(it.checkId, it.statementId, it.recordKey) } as Set<String>
        List<String> opened = recordKeysOf(reportedAfter - openIds)
        return result(true, undecided ? OPENED_NOT_COMPUTED : null, basisId(bases), closedIds as List<String>, opened,
                open.size() - closedIds.size(), limits)
    }

    /**
     * True when the candidate lets back in something the current filters drop: a filter removed or its operator changed,
     * an EXCLUDE_IN that lists fewer values, or an include that lists more.
     */
    static boolean loosens(List<Map> current, List<Map> candidate) {
        Map<String, Map> byField = (candidate ?: []).collectEntries { [(it.fieldExpression as String): it] }
        return (current ?: []).any { Map was ->
            Map now = byField[was.fieldExpression as String]
            if (now == null) return true
            String op = ((was.operator ?: SourceFilterSupport.OPERATOR_EXCLUDE_IN) as String).toUpperCase(Locale.ROOT)
            if (((now.operator ?: SourceFilterSupport.OPERATOR_EXCLUDE_IN) as String).toUpperCase(Locale.ROOT) != op) return true
            Set<String> before = upper(was.filterValues), after = upper(now.filterValues)
            return op == SourceFilterSupport.OPERATOR_EXCLUDE_IN ? !after.containsAll(before) : !before.containsAll(after)
        }
    }

    /** The fields a data-defined source's filter step offers (DataSourceRunner.checkFilterFields), or null when it has none. */
    private static Set<String> offeredFilterFields(def ec, String definitionId) {
        def row = ec.entity.find(DataDefinedExtract.DEFINITION_ENTITY).condition("dataSourceDefinitionId", definitionId).useCache(false).one()
        if (row == null || row.statusId == "RETIRED") return null
        Object parsed = new JsonSlurper().parseText(row.definitionJson as String)
        Map step = ((parsed instanceof Map ? ((Map) parsed).steps : null) ?: []).find { it instanceof Map && it.kind == "filter" } as Map
        return step == null ? null : (((step.fields ?: []) as List).collect { it.toString() } as Set<String>)
    }

    // ---------------------------------------------------------------- EXPECTED_CAUSES

    private static Map expectedCauses(def ec, String tenantId, Map merged, Map current) {
        String savedRunId = ((Map) merged.check)?.savedRunId as String
        if (!savedRunId || current == null) return unsupported("NO_RUN")
        Set<String> added = listSet(((Map) merged.check).expectedCodes) - listSet(((Map) current.check).expectedCodes)
        List open = openFindings(ec, tenantId, checkIdsOf(ec, tenantId, savedRunId), null)
        Map<String, String> codeOfEnum = [:]
        List<String> closed = open.findAll { f ->
            String enumId = normalize(f.conclusionEnumId)
            enumId && (added.contains(enumId) || added.contains(enumCode(ec, enumId, codeOfEnum)))
        }*.findingId.collect { it as String }.sort()
        return result(true, null, null, closed, [], open.size() - closed.size(), [CLOSES_ONLY])
    }

    // ---------------------------------------------------------------- CONCLUSION_ROWS

    private static Map conclusionRows(def ec, String tenantId, Map target, Map merged) {
        String savedRunId = target.savedRunId as String
        def scope = scopeOf(ec, tenantId, savedRunId)
        if (scope == null) return unsupported("NO_RUN")
        String compareScopeId = scope.compareScopeId as String
        // The brain and lookups read systems live; the backtest reads nothing live.
        if (RunConclusionStep.brainKind(scope.conclusionBrain)) return unsupported("BRAIN_SCOPE")
        if (normalize(scope.conclusionLookupsJson) && normalize(scope.conclusionLookupsJson) != "[]") return unsupported("NEEDS_LIVE_LOOKUP")
        List<Map> saved = RunConclusionStep.scopeRules(ec, compareScopeId)
        // A hidden row left the result document, so its new conclusion cannot be seen (and R29 refuses the change).
        if (saved.any { it.outcome == RunConclusionSupport.OUTCOME_HIDE }) return unsupported("HIDDEN_ROWS")
        boolean evaluate = "EVALUATE".equalsIgnoreCase(scope.scopeMode as String)
        List<Map> candidate = candidateRules(ec, compareScopeId, (List<Map>) (merged.conclusions ?: []), evaluate)
        if (candidate == null) return unsupported("INVALID_TREE")

        List<String> sides = evaluate ? [ExtractReplaySupport.SIDE_1] : [ExtractReplaySupport.SIDE_1, ExtractReplaySupport.SIDE_2]
        List<Map> bases = bases(ec, tenantId, savedRunId, sides + [ExtractReplaySupport.RESULT])
        if (!bases) return unsupported("NO_LANDED_EXTRACT")

        List<String> checkIds = checkIdsOf(ec, tenantId, savedRunId)
        List<Map> links = linksOf(ec, tenantId, checkIds)
        Map<String, Set<String>> changedByBasis = [:]
        Set<String> savedReported = new HashSet<String>()
        Set<String> candidateReported = new HashSet<String>()
        bases.each { Map basis ->
            Set<String> changed = new HashSet<String>()
            changedByBasis[basis.executionId as String] = changed
            Map args = conclusionArgs(ec, compareScopeId, basis, evaluate)
            List<String> savedCodes = []
            List<Boolean> savedExpected = []
            boolean unexplained = unexplainedExpected(scope)
            RunConclusionSupport.concludeEach(args + [rules: saved]) { Map row, Map conclusion ->
                savedCodes << (conclusion.code as String)
                savedExpected << expectedOf(saved, conclusion, unexplained)
            }
            int i = 0
            RunConclusionSupport.concludeEach(args + [rules: candidate]) { Map row, Map conclusion ->
                boolean beforeExpected = savedExpected[i]
                String before = savedCodes[i++]
                String after = conclusion.code as String
                boolean afterExpected = expectedOf(candidate, conclusion, unexplained)
                String recordKey = (row.primaryId ?: row.id) as String
                if (!recordKey) return
                if (before != after) changed.add(recordKey)
                String type = (row.diffType ?: row.type) as String
                String ruleId = normalize(row.ruleId)
                links.each { Map link ->
                    String id = findingId(link.checkId, link.statementId, recordKey)
                    if (counts(link, type, beforeExpected, ruleId)) savedReported.add(id)
                    if (counts(link, type, afterExpected, ruleId)) candidateReported.add(id)
                }
            }
        }
        List open = openFindings(ec, tenantId, checkIds, null)
        Map<String, String> periodKinds = periodKindsOf(ec, tenantId, open)
        Set<String> closedIds = new TreeSet<String>()
        bases.each { Map basis ->
            Set<String> changed = changedByBasis[basis.executionId as String]
            open.each { f ->
                if (changed.contains(f.recordKey as String) && !candidateReported.contains(findingId(f.checkId, f.statementId, f.recordKey)) &&
                        periodHolds(f, periodKinds, basis)) closedIds.add(f.findingId as String)
            }
        }
        Set<String> openIds = open.collect { findingId(it.checkId, it.statementId, it.recordKey) } as Set<String>
        List<String> opened = recordKeysOf(candidateReported - savedReported - openIds)
        return result(true, null, basisId(bases), closedIds as List<String>, opened, open.size() - closedIds.size(), [LATEST_WINDOW_ONLY])
    }

    /**
     * The candidate tree as save#RunConclusions stores it (its own toRows, with the saved rules as priors, so a node that
     * names no outcome keeps its rule's as the save keeps it, DAR-BE-198), then as the conclude step compiles it; null if refused.
     */
    private static List<Map> candidateRules(def ec, String compareScopeId, List<Map> nodes, boolean evaluate) {
        Map<String, Object> priorByKey = TenantScopedFinder.findTenantScoped(ec, RunConclusionAuthoringSupport.RULE)
                .condition("compareScopeId", compareScopeId).useCache(false).list().collectEntries { [("${it.sequenceNum}".toString()): it] }
        Map converted = RunConclusionAuthoringSupport.toRows(nodes, evaluate, priorByKey) { String conclusion ->
            def e = ConclusionCatalogSupport.visibleConclusion(ec, conclusion)
            e == null ? null : ((e.description ?: conclusion) as String)
        }
        if (converted.errors) return null
        List<Map> rows = (List<Map>) converted.rows
        List<Map> conditionRows = []
        rows.each { Map row ->
            ((List<Map>) row.conditions).eachWithIndex { Map c, int j -> conditionRows << (c + [sequenceNum: row.sequenceNum, conditionSeq: j + 1]) }
        }
        return RunConclusionStep.buildRules(rows, conditionRows, RunConclusionStep.labelsOf(ec, rows))
    }

    /** RunConclusionSupport arguments for one basis: its landed result document and extracts, keyed as the compare keyed them. */
    private static Map conclusionArgs(def ec, String compareScopeId, Map basis, boolean evaluate) {
        File diffFile = fileOf(basis, ExtractReplaySupport.RESULT)
        Map labels = RunConclusionStep.documentLabels(diffFile)
        boolean singleSided = evaluate || labels.file2Label == null
        Closure<Map> spec = { String side ->
            def source = ec.entity.find("darpan.reconciliation.ReconciliationAutomationSource").condition("companyUserGroupId", basis.tenantId)
                    .condition("automationId", basis.automationId).condition("fileSide", side).useCache(false).list().find()
            RunConclusionStep.sideSpec(ec, compareScopeId, side, source, [fileLocation: fileOf(basis, side).absolutePath])
        }
        return [diffFile: diffFile, file1Label: labels.file1Label, file2Label: labels.file2Label, singleSided: singleSided,
                file1: spec(ExtractReplaySupport.SIDE_1), file2: singleSided ? null : spec(ExtractReplaySupport.SIDE_2)]
    }

    /** As AuditAutomationSupport.findingsFrom decides it (DAR-BE-196): a type and rule the link counts, and a cause not expected. */
    private static boolean counts(Map link, String type, boolean expected, String ruleId) {
        return !expected && AuditAutomationSupport.matches(link.diffTypes as String, type) &&
                AuditAutomationSupport.matches(link.ruleIds as String, ruleId)
    }

    /**
     * As the run writes conclusion.expected: an EXPECTED rule of the cause, or an expected step on its path; Unexplained only
     * when the run says its Unexplained rows are expected (DAR-BE-198 b, set on the run, not by a tree edit).
     */
    private static boolean expectedOf(List<Map> rules, Map conclusion, boolean unexplainedExpected) {
        String code = conclusion.code as String
        if (!code) return false
        if (code == ConclusionTreeSupport.UNEXPLAINED) return unexplainedExpected
        return rules.any { it.conclusionEnumId == code && it.outcome == RunConclusionSupport.OUTCOME_EXPECTED } ||
                ((List<Map>) (conclusion.path ?: [])).any { it?.expected == true }
    }

    // ---------------------------------------------------------------- shared reads

    /** DAR-BE-198 (b): the run marks its Unexplained rows expected. */
    private static boolean unexplainedExpected(def scope) {
        return normalize(scope?.unexplainedOutcome) == RunConclusionSupport.OUTCOME_EXPECTED
    }

    /**
     * Per automation of the run: its latest succeeded execution whose named files are all still on disk.
     * [automationId, executionId, tenantId, files, firstDay, lastDay] (the window's days in the automation's zone).
     */
    private static List<Map> bases(def ec, String tenantId, String savedRunId, List<String> needed) {
        List<Map> out = []
        automationsOf(ec, tenantId, savedRunId).each { automation ->
            String automationId = automation.automationId as String
            List executions = ec.entity.find("darpan.reconciliation.ReconciliationAutomationExecution")
                    .condition("companyUserGroupId", tenantId).condition("automationId", automationId)
                    .condition("statusEnumId", AutomationExecutionSupport.STATUS_SUCCEEDED)
                    .orderBy(["-completedDate", "-automationExecutionId"]).limit(BASIS_LOOKBACK).useCache(false).list()
            for (def execution : executions) {
                Map files = ExtractReplaySupport.landedFilesOfSucceeded(ec, tenantId, execution.automationExecutionId as String)
                if (!needed.every { files.containsKey(it) }) continue
                ZoneId zone
                try { zone = ZoneId.of(normalize(automation.windowTimeZone) ?: "UTC") } catch (Exception ignored) { zone = ZoneId.of("UTC") }
                Timestamp start = (Timestamp) (execution.childWindowStartDate ?: execution.windowStartDate)
                Timestamp end = (Timestamp) (execution.childWindowEndDate ?: execution.windowEndDate)
                out << [automationId: automationId, executionId: execution.automationExecutionId, tenantId: tenantId, files: files,
                        firstDay: start ? start.toInstant().atZone(zone).toLocalDate() : null,
                        lastDay : end ? new Timestamp(end.time - 1).toInstant().atZone(zone).toLocalDate() : null]
                break
            }
        }
        return out
    }

    /** A basis closes a finding only when the finding's period holds the whole basis window. */
    private static boolean periodHolds(def finding, Map<String, String> periodKinds, Map basis) {
        LocalDate first = (LocalDate) basis.firstDay, last = (LocalDate) basis.lastDay
        if (first == null || last == null) return false
        List<LocalDate> bounds = AuditReportAssembler.periodBounds(finding.period as String, periodKinds[finding.statementId as String])
        return bounds != null && !first.isBefore(bounds[0]) && !last.isAfter(bounds[1])
    }

    /** statementId -> its audit's period kind (day, month, quarter), for the findings' statements. */
    private static Map<String, String> periodKindsOf(def ec, String tenantId, List findings) {
        List<String> statementIds = findings*.statementId.collect { it as String }.unique()
        if (!statementIds) return [:]
        List statements = ec.entity.find("darpan.audit.AuditStatement").condition("companyUserGroupId", tenantId)
                .condition("statementId", EntityCondition.IN, statementIds).useCache(false).list()
        Map<String, String> kindByAudit = [:]
        statements*.auditId.collect { it as String }.unique().each { String auditId ->
            def audit = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.Audit", "auditId", auditId, tenantId, WHY)
            String periodEnumId = audit?.periodEnumId as String
            kindByAudit[auditId] = periodEnumId ? ec.entity.find("moqui.basic.Enumeration").condition("enumId", periodEnumId)
                    .useCache(true).one()?.enumCode as String : null
        }
        return statements.collectEntries { [(it.statementId as String): kindByAudit[it.auditId as String]] }
    }

    private static List automationsOf(def ec, String tenantId, String savedRunId) {
        return ec.entity.find("darpan.reconciliation.ReconciliationAutomation").condition("companyUserGroupId", tenantId)
                .condition("savedRunId", savedRunId).orderBy("automationId").useCache(false).list()
                .findAll { (normalize(it.savedRunType) ?: "ruleset").equalsIgnoreCase("ruleset") }
    }

    /** The run's audit checks: those naming the run, and those its automations run. */
    private static List<String> checkIdsOf(def ec, String tenantId, String savedRunId) {
        List<String> automations = automationsOf(ec, tenantId, savedRunId)*.automationId.collect { it as String }
        EntityCondition byRun = ec.entity.conditionFactory.makeCondition("savedRunId", EntityCondition.EQUALS, savedRunId)
        EntityCondition either = automations ? ec.entity.conditionFactory.makeCondition([byRun,
                ec.entity.conditionFactory.makeCondition("automationId", EntityCondition.IN, automations)], EntityCondition.OR) : byRun
        return ec.entity.find("darpan.audit.AuditCheck").condition("companyUserGroupId", tenantId).condition(either)
                .useCache(false).list()*.checkId.collect { it as String }.unique()
    }

    /** The checks' links to their active statements. */
    private static List<Map> linksOf(def ec, String tenantId, List<String> checkIds) {
        if (!checkIds) return []
        List<Map> links = ec.entity.find("darpan.audit.AuditStatementCheck").condition("companyUserGroupId", tenantId)
                .condition("checkId", EntityCondition.IN, checkIds).useCache(false).list()*.getMap()
        Set<String> active = (links ? ec.entity.find("darpan.audit.AuditStatement").condition("companyUserGroupId", tenantId)
                .condition("statementId", EntityCondition.IN, links*.statementId).useCache(false).list()
                .findAll { (it.isActive ?: "Y") != "N" }*.statementId.collect { it as String } : []) as Set<String>
        return links.findAll { active.contains(it.statementId as String) }
    }

    private static List openFindings(def ec, String tenantId, List<String> checkIds, String statementId) {
        if (!checkIds) return []
        def find = ec.entity.find("darpan.audit.AuditFinding").condition("companyUserGroupId", tenantId)
                .condition("checkId", EntityCondition.IN, checkIds).condition("stateEnumId", "AUDFS_OPEN")
        if (statementId) find.condition("statementId", statementId)
        return find.orderBy("findingId").useCache(false).list()
    }

    private static def scopeOf(def ec, String tenantId, String savedRunId) {
        if (!savedRunId) return null
        return ec.entity.find("darpan.rule.RuleSetCompareScope").condition("companyUserGroupId", tenantId)
                .condition("ruleSetId", savedRunId).useCache(false).list().find()
    }

    private static File fileOf(Map basis, String part) {
        return (File) ((Map) ((Map) basis.files)[part]).file
    }

    /** A finding's identity within one period's ledger: check, statement, record. */
    private static String findingId(Object checkId, Object statementId, Object recordKey) {
        return "${checkId}|${statementId}|${recordKey}".toString()
    }

    private static List<String> recordKeysOf(Collection<String> ids) {
        return ids.collect { String id -> id.substring(id.indexOf('|', id.indexOf('|') + 1) + 1) }.unique().sort()
    }

    private static String enumCode(def ec, String enumId, Map<String, String> cache) {
        if (!cache.containsKey(enumId)) {
            cache[enumId] = ec.entity.find("moqui.basic.Enumeration").condition("enumId", enumId).useCache(true).one()?.enumCode as String
        }
        return cache[enumId]
    }

    private static Set<String> upper(Object values) {
        return SourceFilterSupport.splitValues(values).collect { it.toUpperCase(Locale.ROOT) } as Set<String>
    }


    private static Set<String> listSet(Object v) {
        return (((v ?: []) as List).collect { normalize(it) }.findAll { it }) as Set<String>
    }

    private static String basisId(List<Map> bases) {
        return bases*.executionId.collect { it as String }.join(",")
    }

    private static Map unsupported(String reason) {
        return result(false, reason, null, [], [], 0, [])
    }

    private static Map result(boolean supported, String reason, String basisExecutionId, List<String> closed, List<String> opened,
                              int unchanged, List<String> limits) {
        return [supported: supported, reason: reason, basisExecutionId: basisExecutionId, closed: closed, opened: opened,
                unchanged: unchanged, limits: limits]
    }
}
