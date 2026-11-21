package darpan.reconciliation.source

import darpan.facade.common.TenantScopedFinder
import darpan.reconciliation.core.CompareIdExpressionSupport
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
     * The one decision on where a side's Keep runs (Ruling 3b2b-23 and the records guard; Task 13 review I2). Every path that
     * places a side calls this: the interactive run (RunExtractSupport.sideKeep), replay, and the scheduled path. A side its
     * connector places in Spark keeps in Spark only when all hold:
     * <ul>
     * <li>keepInSpark: the run's compare takes the side's Keep (an evaluate run's does not);</li>
     * <li>sparkKeepsSide: its extract no longer declares the connector's filter parameter;</li>
     * <li>keyedOnRecords: its records are the extract's top-level records array and every key field is read off a record there
     *     (Spark Keep numbers and reads that array, review M5/M6).</li>
     * </ul>
     * Otherwise it keeps today's read (todaysRead). The gate is checked before the Spark plan is built, so a connector's declared
     * Keep lines are compiled only for a side that runs them. READ and NONE placements get their plan as before.
     *
     * @param recordFieldRows the side's filter rows in getter shape, from whichever store the path reads (tenantRuleRows, or the
     *        automation's own, Ruling 3b2b-15)
     * @param extractServiceDefinition the side's extract service definition (ec.service.getServiceDefinition), or null
     * @param recordRootExpression the side's RuleSetCompareSource.recordRootExpression
     * @param keyExpressions the side's key field expressions (composite key fields, else its primaryIdExpression)
     */
    static Map effectiveSideKeep(def ec, List<Map<String, Object>> recordFieldRows, Map connector, Map extractOptions,
                                 Object extractServiceDefinition, String recordRootExpression, List<String> keyExpressions,
                                 boolean keepInSpark) {
        if (placement(connector) == SPARK && !(keepInSpark && sparkKeepsSide(connector, extractServiceDefinition) &&
                keyedOnRecords(recordRootExpression, keyExpressions))) {
            return todaysRead(recordFieldRows)
        }
        return plan(ec, recordFieldRows ?: [], connector, extractOptions)
    }

    /**
     * True when a side's records are the extract's top-level records array (a blank root, records, $.records or $.records[*])
     * and no key field runs through a nested array. Any other shape is false, so the side keeps today's read.
     */
    static boolean keyedOnRecords(String recordRootExpression, List<String> keyExpressions) {
        String root = normalize(recordRootExpression)
        if (root && root.replaceFirst(/^\$\.?/, "").replaceFirst(/\[\*\]$/, "") != "records") return false
        if (!keyExpressions) return false
        return keyExpressions.every { String expression ->
            String path = normalize(CompareIdExpressionSupport.splitIdExpression(expression).idExpr) ?: ""
            return path && !path.replaceFirst(/^\$\.?/, "").replaceFirst(/^records\[\*\]\./, "").contains("[")
        }
    }

    /**
     * Ruling 3b2b-23. Whether Spark runs the Keep of a side its connector places in Spark: only once the side's extract service
     * no longer declares the connector's filter parameter. An extract that still takes it still filters at the read until its
     * system's task (17–23) drops the parameter, and Spark Keep over that side would change live results: it cannot see fields
     * the read overlays (a unit grain filtering on its order's fields), and where both filter it reports zero exclusions that the
     * conclusions read as authoritative. Such a side keeps today's read (todaysRead). False for any other placement, and for an
     * extract that is not installed.
     *
     * @param extractServiceDefinition the side's extract service definition (ec.service.getServiceDefinition), or null
     */
    static boolean sparkKeepsSide(Map connector, Object extractServiceDefinition) {
        if (placement(connector) != SPARK || extractServiceDefinition == null) return false
        Collection<String> declared = (Collection<String>) (extractServiceDefinition.inParameterNameList ?: [])
        return !(normalize(connector.get("filterParameterName")) in declared)
    }

    /**
     * The plan of a side that keeps today's read: the read gets the rows (getter shape), Spark runs nothing and writes no Keep
     * artifact, and no field is added to any projection, so the read is sent exactly what it was before Keep moved into Spark.
     */
    static Map todaysRead(List<Map<String, Object>> recordFieldRows) {
        return [placement: READ, readFilters: recordFieldRows ?: [], rules: [], writeSidecar: false, fields: new LinkedHashSet<String>()]
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
            rules.addAll(SourceFilterSupport.toKeepRules(SourceFilterSupport.parseRules(rows)))
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
}
