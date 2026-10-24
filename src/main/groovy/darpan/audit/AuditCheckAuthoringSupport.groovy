package darpan.audit

import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder

import static darpan.common.ValueSupport.normalize

/**
 * DAR-UI-064. Adding a check to an audit from the app: which run tests which statements, which of the run's rows break
 * each one, and how its findings read. Until this, AuditCheck and AuditStatementCheck rows arrived only by data load.
 *
 * <p>Every read and write is the active tenant's own (TenantScopedFinder, an explicit companyUserGroupId); a code from
 * another tenant reads as "not found". A save is all or nothing and is refused, in words, when it names a statement
 * outside the audit, a run the tenant does not own, a rule that is not in that run, or a row type the ledger does not
 * write. The automation stays DAR-UI-063's: a new check, or one whose run changed, has none, and the audit's play or
 * settings creates one per run on demand.</p>
 *
 * <p>A check can serve statements of several audits; this class only ever adds or removes links to the named audit's
 * statements. Deleting a check removes those links, and the row too unless findings already name it (the ledger
 * keeps its history).</p>
 */
class AuditCheckAuthoringSupport {

    /** The diff row types the ledger records (AuditAutomationSupport.findingsFrom). */
    static final List<String> DIFF_TYPES = ["MISSING_IN_FILE_1", "MISSING_IN_FILE_2", "FIELD_MISMATCH", "FINDING",
                                            "DUPLICATE_IN_FILE_1", "DUPLICATE_IN_FILE_2"].asImmutable()
    /** Root systems a fix is made in (AuditCheck.fixWhereEnumId). */
    static final List<String> FIX_WHERE = ["OMS", "NETSUITE", "SHOPIFY"].asImmutable()
    static final String CODE_PATTERN = /[a-z0-9]+(-[a-z0-9]+)*/
    static final int MAX_TEMPLATE = 500
    static final int MAX_GRACE_DAYS = 60

    private static final List<String> TEXT_FIELDS = ["label", "file1RecordType", "file2RecordType", "matchedBy", "fixOwner",
                                                     "expectedSource", "actualSource", "headlineTemplate",
                                                     "fixActionTemplate", "passesWhenTemplate"].asImmutable()

    /** What the editor offers for one audit: its statements, the tenant's runs with their rules and conclusions, and the
     *  audit's checks. */
    static Map<String, Object> options(def ec, String auditCode) {
        Map audit = auditFor(ec, auditCode)
        if (audit == null) return null
        List<Map> statements = statementsOf(ec, audit)
        List<Map<String, Object>> runs = TenantScopedFinder.findTenantScoped(ec, "darpan.rule.RuleSet")
                .orderBy("ruleSetName").useCache(false).list().collect { rs ->
            String ruleSetId = rs.ruleSetId as String
            def scope = TenantScopedFinder.findTenantScoped(ec, "darpan.rule.RuleSetCompareScope")
                    .condition("ruleSetId", ruleSetId).useCache(false).list().find()
            // DAR-UI-078: what the editor names each side by and fills a new check's records and "matched by" from.
            String recordType = recordTypeOf(scope == null ? null : scope.getString("objectType"))
            List<Map> sides = []
            if (scope != null) {
                TenantScopedFinder.findTenantScoped(ec, "darpan.rule.RuleSetCompareSource")
                        .condition("compareScopeId", scope.getString("compareScopeId")).orderBy("fileSide").useCache(false).list()
                        .each { source ->
                            String system = source.getString("systemEnumId")
                            sides.add([side: source.getString("fileSide"), system: system ? system.tokenize("_")[0] : null,
                                       key : source.getString("primaryIdExpression")])
                        }
            }
            [savedRunId : ruleSetId, runName: rs.ruleSetName ?: ruleSetId,
             scopeMode  : (scope?.scopeMode ?: "COMPARE") as String,
             rules      : TenantScopedFinder.findTenantScoped(ec, "darpan.rule.Rule").condition("ruleSetId", ruleSetId)
                     .orderBy("sequenceNum").useCache(false).list().collect { [ruleId: it.ruleId, ruleText: it.ruleText] },
             conclusions: scope == null ? [] : conclusionsOf(ec, scope.compareScopeId as String),
             recordType : recordType, sides: sides] as Map<String, Object>
        }
        return [audit     : [id: audit.auditCode, name: audit.auditName],
                statements: statements.collect { [id: it.statementCode, text: it.statementText] },
                runs      : runs, diffTypes: DIFF_TYPES, fixWhere: FIX_WHERE,
                checks    : checksOf(ec, audit, statements).collect { [id: it.checkCode, label: it.label] }] as Map<String, Object>
    }

    /** A run's object as a finding's record type: RETURN -> return, CREDIT_MEMO -> creditMemo. */
    static String recordTypeOf(String objectType) {
        List<String> words = (objectType ?: "").toLowerCase().split("_").findAll { it } as List<String>
        return words ? words[0] + words.drop(1).collect { it.capitalize() }.join("") : null
    }

    /**
     * Creates or updates the check named by check.id (check.create: true refuses an id already in use) and replaces its
     * links to this audit's statements. statements:
     * [{id: statementCode, diffTypes: [..], conclusionCodes: [..], ruleIds: [..]}], at least one.
     */
    static Map<String, Object> saveCheck(def ec, String auditCode, Map check, List statementLinks) {
        if (!TenantAccessSupport.requireActiveTenantWriteAccess(ec, "Your active tenant only has view access, so it cannot change an audit.")) return null
        Map audit = auditFor(ec, auditCode)
        if (audit == null) return null
        String tenantId = audit.companyUserGroupId as String
        List<Map> statements = statementsOf(ec, audit)
        Map<String, Map> statementByCode = statements.collectEntries { [(it.statementCode as String): it] }

        String code = normalize(check?.id)
        if (!code || !(code ==~ CODE_PATTERN) || code.length() > 60) {
            ec.message.addError("A check id is lower-case words joined by hyphens, e.g. returns-not-in-netsuite.")
            return null
        }
        Map<String, Object> values = [:]
        TEXT_FIELDS.each { String f ->
            String v = normalize(check.get(f))
            if (v != null && v.length() > MAX_TEMPLATE) ec.message.addError("${f} is longer than ${MAX_TEMPLATE} characters.")
            values[f] = v
        }
        if (!values.label) ec.message.addError("Name the check: what it compares, in a few words.")
        String savedRunId = normalize(check.savedRunId)
        Map run = null
        if (savedRunId) {
            def rs = TenantScopedFinder.findTenantScoped(ec, "darpan.rule.RuleSet").condition("ruleSetId", savedRunId).useCache(false).list().find()
            if (rs == null) ec.message.addError("Run '${savedRunId}' was not found.")
            else run = [ruleIds: TenantScopedFinder.findTenantScoped(ec, "darpan.rule.Rule").condition("ruleSetId", savedRunId)
                    .useCache(false).list()*.ruleId.collect { it as String } as Set<String>]
        }
        String fixWhere = normalize(check.fixWhereEnumId)
        if (fixWhere && !(fixWhere in FIX_WHERE)) ec.message.addError("Fix where must be one of ${FIX_WHERE.join(', ')}.")
        Integer graceDays = null
        if (normalize(check.graceDays) != null) {
            String g = normalize(check.graceDays)
            if (!(g ==~ /\d{1,3}/) || (g as int) > MAX_GRACE_DAYS) ec.message.addError("Grace days is a whole number from 0 to ${MAX_GRACE_DAYS}.")
            else graceDays = g as int
        }
        String currency = normalize(check.amountCurrencyUomId)
        if (currency && !(currency ==~ /[A-Z]{3}/)) ec.message.addError("Currency is a three-letter code, e.g. USD.")

        List<Map<String, Object>> links = []
        ((statementLinks ?: []) as List).each { Object o ->
            if (!(o instanceof Map)) return
            Map l = (Map) o
            String statementCode = normalize(l.id)
            Map statement = statementByCode[statementCode]
            if (statement == null) { ec.message.addError("Statement '${statementCode}' is not in this audit."); return }
            List<String> diffTypes = listOf(l.diffTypes)
            diffTypes.findAll { !(it in DIFF_TYPES) }.each { ec.message.addError("'${it}' is not a row type a check records.") }
            List<String> ruleIds = listOf(l.ruleIds)
            if (ruleIds && run == null) ec.message.addError("Choose the run before naming its rules.")
            ruleIds.findAll { run != null && !(it in (Set) run.ruleIds) }.each { ec.message.addError("Rule '${it}' is not in run '${savedRunId}'.") }
            List<String> conclusionCodes = listOf(l.conclusionCodes)
            conclusionCodes.findAll { !(it ==~ /[A-Z0-9_]{1,60}/) }.each { ec.message.addError("'${it}' is not a conclusion code.") }
            links << [statementId: statement.statementId, diffTypes: diffTypes.join(",") ?: null,
                      conclusionCodes: conclusionCodes.join(",") ?: null, ruleIds: ruleIds.join(",") ?: null] as Map<String, Object>
        }
        if (!links) ec.message.addError("Link the check to at least one statement.")
        if (links*.statementId.unique().size() != links.size()) ec.message.addError("Each statement is linked once.")
        if (ec.message.hasError()) return null

        def row = checkFor(ec, code)
        // A check code is unique per TENANT, not per audit: a "new" check must not quietly take over one another audit uses.
        if (row != null && check.create in [true, "true", "Y"]) {
            ec.message.addError("The check id '${code}' is already used; choose another.")
            return null
        }
        if (row == null) {
            row = ec.entity.makeValue("darpan.audit.AuditCheck")
            row.setSequencedIdPrimary()
            row.setAll([companyUserGroupId: tenantId, checkCode: code])
            row.setAll(values + [savedRunId: savedRunId, fixWhereEnumId: fixWhere, graceDays: graceDays, amountCurrencyUomId: currency])
            row.create()
        } else {
            // A changed run makes the automation stale: clear it, and the audit's play / settings make one for the new run.
            if ((row.savedRunId as String) != savedRunId) row.set("automationId", null)
            row.setAll(values + [savedRunId: savedRunId, fixWhereEnumId: fixWhere, graceDays: graceDays, amountCurrencyUomId: currency])
            row.update()
        }
        String checkId = row.checkId as String
        Set<String> auditStatementIds = statements*.statementId.collect { it as String } as Set<String>
        TenantScopedFinder.findTenantScoped(ec, "darpan.audit.AuditStatementCheck").condition("checkId", checkId).useCache(false).list()
                .findAll { (it.statementId as String) in auditStatementIds }.each { it.delete() }
        links.eachWithIndex { Map<String, Object> l, int i ->
            ec.entity.makeValue("darpan.audit.AuditStatementCheck").setAll(l + [checkId: checkId, companyUserGroupId: tenantId,
                    sequenceNum: nextSequence(ec, l.statementId as String, checkId)]).create()
        }
        return describe(ec, row, statements)
    }

    private static Map<String, Object> describe(def ec, def check, List<Map> statements) {
        Map<String, String> codeById = statements.collectEntries { [(it.statementId as String): it.statementCode as String] }
        List<Map<String, Object>> links = TenantScopedFinder.findTenantScoped(ec, "darpan.audit.AuditStatementCheck")
                .condition("checkId", check.checkId).orderBy("sequenceNum").useCache(false).list()
                .findAll { codeById.containsKey(it.statementId as String) }
                .collect { [id: codeById[it.statementId as String], diffTypes: listOf(it.diffTypes),
                            conclusionCodes: listOf(it.conclusionCodes), ruleIds: listOf(it.ruleIds)] as Map<String, Object> }
        Map<String, Object> out = [id: check.checkCode, savedRunId: check.savedRunId, automationId: check.automationId,
                                   fixWhereEnumId: check.fixWhereEnumId, graceDays: check.graceDays,
                                   amountCurrencyUomId: check.amountCurrencyUomId] as Map<String, Object>
        TEXT_FIELDS.each { String f -> out[f] = check.get(f) }
        out.statements = links
        return out
    }

    private static Map auditFor(def ec, String auditCode) {
        String code = normalize(auditCode)
        Map audit = code ? TenantScopedFinder.findTenantScoped(ec, "darpan.audit.Audit").condition("auditCode", code)
                .useCache(false).list().find()?.getMap() : null
        if (audit == null) ec.message.addError("Audit '${auditCode}' was not found.")
        return audit
    }

    private static List<Map> statementsOf(def ec, Map audit) {
        return TenantScopedFinder.findTenantScoped(ec, "darpan.audit.AuditStatement").condition("auditId", audit.auditId)
                .orderBy(["sequenceNum", "statementCode"]).useCache(false).list()*.getMap()
    }

    private static List<Map> checksOf(def ec, Map audit, List<Map> statements) {
        Set<String> ids = TenantScopedFinder.findTenantScoped(ec, "darpan.audit.AuditStatementCheck")
                .condition("statementId", "in", statements*.statementId).useCache(false).list()*.checkId.collect { it as String } as Set<String>
        if (!ids) return []
        return TenantScopedFinder.findTenantScoped(ec, "darpan.audit.AuditCheck").condition("checkId", "in", ids)
                .orderBy("checkCode").useCache(false).list()*.getMap()
    }

    private static def checkFor(def ec, String checkCode) {
        String code = normalize(checkCode)
        return code ? TenantScopedFinder.findTenantScoped(ec, "darpan.audit.AuditCheck").condition("checkCode", code)
                .useCache(false).list().find() : null
    }

    private static List<Map<String, Object>> conclusionsOf(def ec, String compareScopeId) {
        List<String> ids = TenantScopedFinder.findTenantScoped(ec, "darpan.rule.RuleSetConclusionRule")
                .condition("compareScopeId", compareScopeId).orderBy("sequenceNum").useCache(false).list()
                *.conclusionEnumId.findAll { it }.collect { it as String }.unique()
        return ids.collect { String id ->
            def e = ec.entity.find("moqui.basic.Enumeration").condition("enumId", id).useCache(true).one()
            [code: (e?.enumCode ?: id) as String, label: (e?.description ?: id) as String] as Map<String, Object>
        }
    }

    private static int nextSequence(def ec, String statementId, String checkId) {
        List rows = TenantScopedFinder.findTenantScoped(ec, "darpan.audit.AuditStatementCheck").condition("statementId", statementId)
                .useCache(false).list().findAll { (it.checkId as String) != checkId }
        return ((rows*.sequenceNum.findAll { it != null }.collect { it as int }.max() ?: 0) as int) + 1
    }

    private static List<String> listOf(Object value) {
        if (value == null) return []
        Collection raw = value instanceof Collection ? (Collection) value : value.toString().split(",") as List
        return raw.collect { normalize(it) }.findAll { it }.unique() as List<String>
    }
}
