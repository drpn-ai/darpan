package darpan.reconciliation.source

import darpan.facade.common.TenantScopedFinder
import darpan.reconciliation.automation.SourceSystemConnectorSupport

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-318 (plan 3b-2b, Task 9). One side's Keep plan: where its Keep runs, the rules Spark runs, the filters the read gets,
 * whether Spark writes the side file, and the record fields Keep reads (every projection adds them, Ruling 3b2b-4).
 *
 * Placement (Ruling 3b2b-9), from the side's connector row:
 * <ul>
 * <li>NONE: no connector, or a blank filterParameterName (file uploads, databases). No filters and no Keep, as today.</li>
 * <li>READ: a data-defined endpoint (dataSourceDefinitionId set). Its filter is a positioned step of its pipeline, so it keeps
 * filtering at the read (Ruling 3b2b-16): the read gets the tenant's rows in getter shape, and Spark runs nothing.</li>
 * <li>SPARK: every other endpoint. The read gets no filters; Spark runs the connector's built-ins (keepLinesJson, compiled by
 * KeepLineTranslator.declared, Ruling 3b2b-12) and then the tenant's rules (front-end A), in that order.</li>
 * </ul>
 *
 * Two entry points feed the tenant's rows. The interactive run reads the compare scope's RuleSetCompareSourceFilter rows for one
 * named tenant (tenantRuleRows). The automation path keeps reading its own filter store in this plan (Ruling 3b2b-15) and hands
 * its rows in (sideKeepFromFilterRows). Built-ins are data on the connector row; this class holds no system name.
 */
class KeepPlanSupport {

    /** The extract-service parameter a SPARK side's Keep fields go to when the extract declares it. */
    static final String KEEP_FIELDS_PARAMETER = "keepRuleFields"

    static final String SPARK = "SPARK"
    static final String READ = "READ"
    static final String NONE = "NONE"

    static final String FILTER_ENTITY = "darpan.rule.RuleSetCompareSourceFilter"

    static String placement(Map connector) {
        if (!normalize(connector?.get("filterParameterName"))) return NONE
        return normalize(connector.get("dataSourceDefinitionId")) ? READ : SPARK
    }

    /**
     * The compare scope's filter rows for one side, owned by companyUserGroupId only, in sequenceNum order and getter shape
     * (SourceFilterSupport.toRecordFieldRules). The read is pinned to the named tenant whatever the caller's context: a
     * scheduled run has no active tenant, so the tenant filter cannot be relied on (Review Focus 4). A blank tenant throws.
     */
    static List<Map<String, Object>> tenantRuleRows(def ec, String compareScopeId, String fileSide, String companyUserGroupId) {
        String tenant = requireTenant(companyUserGroupId)
        if (!normalize(compareScopeId) || !normalize(fileSide)) return []
        List rows = TenantScopedFinder.findGlobalUnscoped(ec, FILTER_ENTITY,
                "Keep plan reads one side's source filters for the named tenant; pinned by an explicit companyUserGroupId")
                .condition("companyUserGroupId", tenant)
                .condition("compareScopeId", normalize(compareScopeId))
                .condition("fileSide", normalize(fileSide))
                .orderBy("sequenceNum")
                .useCache(false)
                .list() ?: []
        return SourceFilterSupport.toRecordFieldRules(rows.collect { def row ->
            [sequenceNum    : row.sequenceNum,
             fieldExpression: normalize(row.fieldExpression),
             operator       : normalize(row.operator),
             filterValues   : normalize(row.filterValues)] as Map<String, Object>
        } as List<Map<String, Object>>)
    }

    /**
     * The interactive side's plan: [placement, readFilters, rules, writeSidecar, fields]. The compare scope's rows are read
     * (for the named tenant) only when the side takes Keep.
     */
    static Map sideKeep(def ec, String compareScopeId, String fileSide, String companyUserGroupId, Map connector, Map extractOptions) {
        requireTenant(companyUserGroupId)
        List<Map<String, Object>> rows = placement(connector) == NONE ? [] : tenantRuleRows(ec, compareScopeId, fileSide, companyUserGroupId)
        return plan(ec, rows, connector, extractOptions)
    }

    /**
     * The automation side's plan (Ruling 3b2b-15), from the automation's own filter rows in getter shape
     * (AutomationRuntimeSupport.loadAutomationSourceFilters). Same shape as sideKeep.
     */
    static Map sideKeepFromFilterRows(def ec, List<Map<String, Object>> recordFieldRows, Map connector, Map extractOptions) {
        return plan(ec, recordFieldRows ?: [], connector, extractOptions)
    }

    private static Map plan(def ec, List<Map<String, Object>> rows, Map connector, Map extractOptions) {
        String where = placement(connector)
        Map out = [placement: where, readFilters: [], rules: [], writeSidecar: false, fields: new LinkedHashSet<String>()]
        if (where == READ) {
            out.readFilters = rows
            rows.each { ((Set) out.fields).add(it.fieldExpression as String) }
        } else if (where == SPARK) {
            List<Map> rules = []
            rules.addAll(KeepLineTranslator.declared(keepLines(ec, connector), extractOptions ?: [:]))
            rules.addAll(tenantKeepRules(SourceFilterSupport.parseRules(rows)))
            KeepRules.validate(rules)
            out.rules = rules
            out.writeSidecar = normalize(connector.get("excludedRecordsSidecar"))?.toUpperCase() == "Y"
            ((Set) out.fields).addAll(KeepRules.fields(rules))
        }
        return out
    }

    /** keepLinesJson off the connector's registry row: SourceSystemConnectorSupport.toConnectorMap does not carry it. */
    private static Object keepLines(def ec, Map connector) {
        String systemEnumId = normalize(connector?.get("systemEnumId"))
        if (!systemEnumId) return null
        return ec.entity.find(SourceSystemConnectorSupport.ENTITY_NAME).condition("systemEnumId", systemEnumId).useCache(true).one()?.keepLinesJson
    }

    private static String requireTenant(String companyUserGroupId) {
        String tenant = normalize(companyUserGroupId)
        if (!tenant) throw new IllegalArgumentException("A side's Keep plan is read for one named tenant; none was given.")
        return tenant
    }

    /**
     * REPLACE AFTER TASK 5: SourceFilterSupport.toKeepRules(parsedRules) is front-end A's owner (Shared contracts, Task 5) and
     * its parity suite. This adapter states SourceFilterSupport.evaluate in the Keep rule shape until it lands, then is deleted.
     */
    private static List<Map> tenantKeepRules(List<Map<String, Object>> parsedRules) {
        return parsedRules.collect { Map<String, Object> rule ->
            String field = rule.fieldExpression as String
            List<String> values = new ArrayList<String>((List<String>) rule.values)
            Closure<Map> compare = { String op, List<String> listed ->
                [kind: "compare", field: field, text: "GROOVY", op: op, values: listed, valueKind: "TEXT", fold: "A_UPPER"] as Map
            }
            List<Map> tests
            switch (rule.operator) {
                case SourceFilterSupport.OPERATOR_INCLUDE_IN:
                    tests = [[test: compare("IS_NULL", null), reason: SourceFilterSupport.REASON_FIELD_ABSENT],
                             [test: compare("NOT_IN", values), reason: SourceFilterSupport.REASON_VALUE]]
                    break
                case SourceFilterSupport.OPERATOR_INCLUDE_PREFIX:
                    tests = [[test: compare("IS_NULL", null), reason: SourceFilterSupport.REASON_FIELD_ABSENT],
                             [test: [kind: "not", of: compare("STARTS_WITH_ANY", values)], reason: SourceFilterSupport.REASON_VALUE]]
                    break
                default:
                    tests = [[test: compare("IN", values), reason: SourceFilterSupport.REASON_VALUE]]
            }
            return [id: "A:${rule.sequenceNum}".toString(), sequenceNum: rule.sequenceNum as Integer, label: field, source: "TENANT",
                    tests: tests] as Map
        }
    }
}
