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
 * Every path places a side through effectiveSideKeep, handing in the tenant's rows from its own store: the interactive run reads
 * the compare scope's RuleSetCompareSourceFilter rows for one named tenant (tenantRuleRows); the automation path keeps reading
 * its own filter store in this plan (Ruling 3b2b-15). Built-ins are data on the connector row; this class holds no system name.
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
     * places a side calls this: the interactive run (RunExtractSupport.sideKeep), replay, the scheduled path and the sync pulse.
     * A side its connector places in Spark keeps in Spark only when all hold:
     * <ul>
     * <li>keepInSpark: the caller's compare takes the side's Keep (keepBySide). Every run path's does, an evaluate run's
     *     included (Task 16b); a caller that does not say so is taken as one whose compare takes none;</li>
     * <li>sparkKeepsSide: its extract no longer declares the connector's filter parameter;</li>
     * <li>keyedOnRecords: its records are the extract's top-level records array and every key field is read off a record there
     *     (Spark Keep numbers and reads that array, review M5/M6). A side with no key at all fails with that reason of its own.</li>
     * </ul>
     * Ruling 3b2b-29 (final review I2): a side its connector places in Spark whose extract no longer declares the filter parameter
     * fails, before anything else is weighed, when the connector row has no keepLinesJson loaded (null or blank). Its extractor
     * no longer drops the records its built-ins set aside, so a missing value would keep them all silently. A connector with no
     * built-ins carries an explicit "[]". The message names the side and the connector and says to load the upgrade data.
     *
     * A side whose extract still declares the filter parameter (or is not installed) keeps today's read (todaysRead), and so does
     * a side whose data is a landed file its read already filtered (landed: a retried run's or a replay's, landedFilteredAtRead):
     * it is not read again, and Spark Keep over it would find nothing left and write zero counts.
     *
     * Ruling 3b2b-25: a side whose extract no longer declares the parameter cannot filter at the read: Moqui drops an undeclared
     * parameter, so its tenant rules would never arrive and its built-in drops are gone from the extractor. When Spark cannot
     * keep it either (keepInSpark false, no key, or not keyed on top-level records), its data was not already filtered at the read, and
     * it has Keep rules (tenant rows, or declared built-ins that apply to its options: KeepLineTranslator.declared), it fails
     * here, before any read, with a message naming the side and why. With no Keep rules there is nothing to filter, and it reads
     * as today with no filters.
     *
     * The gate is checked before the Spark plan is built, so a connector's declared Keep lines are compiled only for a side that
     * runs them or that the 3b2b-25 check has to weigh. READ and NONE placements get their plan as before.
     *
     * @param recordFieldRows the side's filter rows in getter shape, from whichever store the path reads (tenantRuleRows, or the
     *        automation's own, Ruling 3b2b-15)
     * @param extractServiceDefinition the side's extract service definition (ec.service.getServiceDefinition), or null
     * @param recordRootExpression the side's RuleSetCompareSource.recordRootExpression
     * @param keyExpressions the side's key field expressions (composite key fields, else its primaryIdExpression)
     * @param landed the side's landed file when its data is one that is not read again (a retried run's, a replay's), else null:
     *        whether its read filtered it is landedFilteredAtRead's answer, asked here so that no path can ask it differently
     * @param fileSide the side (FILE_1 or FILE_2) the failure message names; optional
     * @throws IllegalArgumentException when neither the read nor Spark can filter a side that has Keep rules (Ruling 3b2b-25), or
     *         when a side Spark would keep has no Keep lines loaded on its connector (Ruling 3b2b-29). A configuration error,
     *         not a passing fault: the scheduled path fails it at once (isPermanentFailure) rather than queueing retries that
     *         would fail the same way.
     */
    static Map effectiveSideKeep(def ec, List<Map<String, Object>> recordFieldRows, Map connector, Map extractOptions,
                                 Object extractServiceDefinition, String recordRootExpression, List<String> keyExpressions,
                                 boolean keepInSpark, File landed = null, String fileSide = null) {
        if (placement(connector) == SPARK) {
            if (!sparkKeepsSide(connector, extractServiceDefinition)) return todaysRead(recordFieldRows)
            requireKeepLinesLoaded(ec, connector, fileSide)
            if (landedFilteredAtRead(ec, landed, recordFieldRows, connector, extractOptions)) return todaysRead(recordFieldRows)
            boolean keyless = !keyExpressions?.any { normalize(it) }
            List<String> sparkCannot = []
            if (!keepInSpark) sparkCannot.add("this run does not run Keep")
            if (keyless) sparkCannot.add("it has no key")
            else if (!keyedOnRecords(recordRootExpression, keyExpressions)) sparkCannot.add("its records are not top-level")
            if (sparkCannot) {
                if (recordFieldRows || KeepLineTranslator.declared(keepLines(ec, connector), extractOptions ?: [:])) {
                    throw new IllegalArgumentException(("${sideName(fileSide, connector)} cannot be filtered: its source no longer " +
                            "filters at read and ${sparkCannot.join(' and ')}, so Keep cannot run in Spark." +
                            (keyless ? " Give the side a key field in its compare scope." : "")).toString())
                }
                return todaysRead(recordFieldRows)
            }
        }
        return plan(ec, recordFieldRows ?: [], connector, extractOptions)
    }

    /**
     * Task 16e (reviews 16b-I1, 16d-M6): the one answer to "was this landed file filtered at the read?", which effectiveSideKeep
     * asks for every path that hands a side back without reading it (the interactive retry, the automation's retry from its last
     * extract, the judge replay). False when a Keep summary lies beside it (Spark Keep ran over it, final review m3). Otherwise
     * true when its read reported configuredExclusions, or, for a side with no tenant rows, when no record carries a field the
     * side's built-in Keep lines read (KeepLineTranslator.declared for these extract options): a capture whose read applied only
     * its built-in drops reports no configuredExclusions, and every built-in flag a Spark-era extractor writes came in with
     * Tasks 17-20 (KeepArtifacts.filteredAtRead says why the tenant-rows case takes only the first signal).
     *
     * The flag signal holds only for lines that need their field present to set a record aside (EQ, IN, LIKE and the like). A
     * line that could hold on a missing field (LT, LE, NE, NOT_IN, IS_NULL, NOT_LIKE, or any line under a "not") sets aside
     * records that carry no flag at all, so a file with no flagged record says nothing about its read: when any declared line is
     * of that kind, only configuredExclusions tells (final review triage 6, 16e M4). False when there is no landed file; a landed file
     * that is missing or unreadable answers true.
     */
    static boolean landedFilteredAtRead(def ec, File landed, List<Map<String, Object>> recordFieldRows, Map connector,
                                        Map extractOptions) {
        if (landed == null) return false
        List<Map> builtIns = recordFieldRows ? [] : KeepLineTranslator.declared(keepLines(ec, connector), extractOptions ?: [:])
        Collection<String> builtInFields = builtIns.any { Map rule -> holdsOnAbsence(rule) } ? null : KeepRules.fields(builtIns)
        return KeepArtifacts.filteredAtRead(landed, builtInFields)
    }

    /** The compare ops that can hold on a record that lacks the field (KeepLineTranslator.OPS' values). */
    private static final Set<String> ABSENCE_OPS = ["LT", "LE", "NE", "NOT_IN", "IS_NULL", "NOT_LIKE"] as Set

    /** Whether any test of a compiled rule could set aside a record that lacks the field it reads. */
    private static boolean holdsOnAbsence(Map rule) {
        return ((List<Map>) rule.get("tests") ?: []).any { Map test -> predicateHoldsOnAbsence((Map) test.get("test")) }
    }

    private static boolean predicateHoldsOnAbsence(Map predicate) {
        switch (predicate?.get("kind")) {
            case "compare": return ABSENCE_OPS.contains(predicate.get("op"))
            case "not": return true
            case "all": case "any": return ((List<Map>) predicate.get("of") ?: []).any { predicateHoldsOnAbsence(it) }
            default: return false
        }
    }

    /**
     * Ruling 3b2b-29: a side Spark keeps needs its connector's built-in Keep lines loaded; with none (null or blank keepLinesJson,
     * an upgrade-data load skipped) the records the built-ins set aside would be compared silently. "[]" is a loaded value.
     */
    private static void requireKeepLinesLoaded(def ec, Map connector, String fileSide) {
        if (keepLines(ec, connector)?.toString()?.trim()) return
        String system = normalize(connector?.get("systemEnumId")) ?: "unnamed"
        throw new IllegalArgumentException(("${sideName(fileSide, connector)} cannot be filtered: the ${system} connector's Keep " +
                "lines (keepLinesJson) are not loaded. Load the upgrade data, then run again.").toString())
    }

    /** "File 2 (ACME)" for FILE_2 on connector ACME; "The ACME side" when the side is not given. */
    private static String sideName(String fileSide, Map connector) {
        String system = normalize(connector?.get("systemEnumId"))
        String side = normalize(fileSide)?.replace("_", " ")?.toLowerCase()?.capitalize()
        if (side) return system ? "${side} (${system})".toString() : side
        return system ? "The ${system} side".toString() : "A side"
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
