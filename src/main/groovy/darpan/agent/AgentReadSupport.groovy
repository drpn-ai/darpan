package darpan.agent

import darpan.audit.AuditReportGraph
import darpan.conclusion.brain.BrainConclude
import darpan.conclusion.brain.BrainEngine
import darpan.datasource.DataDefinedExtract
import darpan.facade.common.SharedConfigAccessSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.knowledge.KnowledgeSupport
import darpan.reconciliation.automation.SourceSystemConnectorSupport
import darpan.reconciliation.conclusion.ConclusionCatalogSupport
import org.moqui.entity.EntityCondition

/**
 * DAR-BE-159. The reads behind the embedded agent's tools (facade.AgentReadFacadeServices). Every read is of the signed-in
 * user's ACTIVE tenant, taken from the session: no method here takes a tenant id from a caller, and each tenant-owned find
 * also names companyUserGroupId (the entity filter is skipped when authz is off). Another tenant's finding reads as missing.
 */
class AgentReadSupport {

    static final int MAX_LOOKALIKES = 200
    static final int DEFAULT_LOOKALIKES = 50
    static final String CATALOG = "darpan.agent.AgentReadCatalog"
    private static final String WHY = "agent read: explicit companyUserGroupId condition applied"

    // ---------------------------------------------------------------- look-alikes

    /** [sameCause, controls] of one finding, or null (with an error) when the finding is not the active tenant's. */
    static Map lookalikes(def ec, String findingId, Object limitValue) {
        String tenantId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (!tenantId || !AuditReportGraph.ownsFinding(ec, tenantId, findingId)) {
            ec.message.addError("Finding ${findingId} was not found".toString())
            return null
        }
        int limit = clamp(limitValue, DEFAULT_LOOKALIKES, MAX_LOOKALIKES)
        def base = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.AuditFinding", "findingId", findingId, tenantId, WHY)
        String conclusion = base.conclusionEnumId as String
        def sameCause = findings(ec, tenantId, base.checkId as String)
                .condition(ec.entity.conditionFactory.makeCondition("conclusionEnumId", EntityCondition.EQUALS, conclusion ?: null))
                .condition("findingId", EntityCondition.NOT_EQUAL, findingId).orderBy("findingId").limit(limit).list()
        EntityCondition different = conclusion == null
                ? ec.entity.conditionFactory.makeCondition("conclusionEnumId", EntityCondition.NOT_EQUAL, null)
                : ec.entity.conditionFactory.makeCondition([
                        ec.entity.conditionFactory.makeCondition("conclusionEnumId", EntityCondition.NOT_EQUAL, conclusion),
                        ec.entity.conditionFactory.makeCondition("conclusionEnumId", EntityCondition.EQUALS, null)], EntityCondition.OR)
        def controls = findings(ec, tenantId, base.checkId as String).condition(different).orderBy("findingId").limit(limit).list()
        Map<String, String> stateWords = enumWords(ec, "AuditFindingState")
        Map<String, String> statementCodes = [:]
        Closure row = { def f ->
            String statementId = f.statementId as String
            if (!statementCodes.containsKey(statementId)) {
                statementCodes.put(statementId, TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.audit.AuditStatement", "statementId", statementId, tenantId, WHY)?.statementCode as String)
            }
            List<Map> checks = ec.entity.find("darpan.audit.AuditFindingCondition").condition("companyUserGroupId", tenantId)
                    .condition("findingId", f.findingId).orderBy("sequenceNum").useCache(false).list()
                    .collect { [label: it.label, passed: it.passed == "Y", value: AgentRedaction.safeValue(it.checkValue ?: null)] as Map }
            [findingId: f.findingId, recordKey: f.recordKey, state: stateWords.get(f.stateEnumId as String) ?: f.stateEnumId,
             conclusionCode: f.conclusionEnumId, statementCode: statementCodes.get(statementId), conditionValues: checks] as Map
        }
        return [sameCause: sameCause.collect(row), controls: controls.collect(row)]
    }

    private static def findings(def ec, String tenantId, String checkId) {
        return TenantScopedFinder.findGlobalUnscoped(ec, "darpan.audit.AuditFinding", WHY)
                .condition("companyUserGroupId", tenantId).condition("checkId", checkId).useCache(false)
    }

    // ---------------------------------------------------------------- brain catalog

    /** Conclusions, rules and the active tenant's statements, each list sorted so two calls give the same bytes. */
    static Map brainCatalog(def ec) {
        String tenantId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        List<Map> conclusions = ConclusionCatalogSupport.catalog(ec).collect { Map c -> [id: c.id, label: c.label, own: c.own] as Map }
                .sort { Map a, Map b -> (a.id as String) <=> (b.id as String) }
        Map<String, Map<String, Object>> meta = BrainEngine.forPacks(BrainConclude.PILOT_PACKS).ruleMetadata()
        List<Map> rules = meta.keySet().sort().collect { String name ->
            Map<String, Object> m = meta.get(name)
            [name: name, concludes: text(m.concludes), rank: text(m.rank), asks: text(m.asks)] as Map
        }
        List<Map> statements = []
        if (tenantId) {
            Map<String, String> checkCodeById = [:]
            ec.entity.find("darpan.audit.AuditCheck").condition("companyUserGroupId", tenantId).useCache(false).list()
                    .each { checkCodeById.put(it.checkId as String, it.checkCode as String) }
            Map<String, List<String>> checksByStatement = [:].withDefault { [] }
            ec.entity.find("darpan.audit.AuditStatementCheck").condition("companyUserGroupId", tenantId).useCache(false).list()
                    .each { checksByStatement[it.statementId as String] << checkCodeById.get(it.checkId as String) }
            Map<String, String> auditCodes = [:]
            ec.entity.find("darpan.audit.Audit").condition("companyUserGroupId", tenantId).useCache(false).list()
                    .each { auditCodes.put(it.auditId as String, it.auditCode as String) }
            statements = ec.entity.find("darpan.audit.AuditStatement").condition("companyUserGroupId", tenantId).useCache(false).list()
                    .collect { [statementCode: it.statementCode, audit: auditCodes.get(it.auditId as String), text: it.statementText,
                                checkCodes: (checksByStatement[it.statementId as String] as List<String>).findAll { it }.unique().sort()] as Map }
                    .sort { Map a, Map b -> ((a.audit ?: "") as String) <=> ((b.audit ?: "") as String) ?: (a.statementCode as String) <=> (b.statementCode as String) }
        }
        return [conclusions: conclusions, rules: rules, statements: statements]
    }

    // ---------------------------------------------------------------- one record by key

    /** The one record the catalog's read finds for key, redacted, or null (with an error). Writes no file. */
    static Map inspect(def ec, String readId, String key) {
        def entry = readId ? ec.entity.find(CATALOG).condition("readId", readId).useCache(false).one() : null
        if (entry == null) { ec.message.addError("Unknown read ${readId}".toString()); return null }
        String trimmed = key?.trim()
        if (!trimmed) { ec.message.addError("Give the key to look up."); return null }
        String tenantId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (!tenantId) { ec.message.addError("No active tenant."); return null }

        Map connector = ec.entity.find(SourceSystemConnectorSupport.ENTITY_NAME).condition("dataSourceDefinitionId", entry.definitionId)
                .useCache(false).orderBy("systemEnumId").list().collect { SourceSystemConnectorSupport.resolve(ec, it.systemEnumId as String) }
                .find { it != null }
        if (connector == null) { ec.message.addError("Read ${readId} has no endpoint on this installation.".toString()); return null }
        String configType = connector.expectedSourceConfigType as String
        Map<String, String> type = SharedConfigAccessSupport.configType(configType)
        Object config = type == null ? null : SharedConfigAccessSupport.listAccessibleConfigRows(ec, configType).find { row ->
            "N" != row.get("isActive")?.toString()?.toUpperCase() &&
                    darpan.reconciliation.automation.SourceEndpointAccessSupport.isEndpointEnabled(ec, configType, row.get(type.pkField) as String, connector.systemEnumId as String)
        }
        if (config == null) { ec.message.addError("Your tenant has no connection for ${entry.system}.".toString()); return null }

        Set<String> allowed = (entry.allowedFields as String).split(",").collect { it.trim() }.findAll { it } as LinkedHashSet<String>
        Map context = [systemEnumId: connector.systemEnumId, sourceConfigId: config.get(type.pkField), lookupKeys: [trimmed],
                       lookupKeyField: entry.lookupKeyField, keepRecordFields: new ArrayList<String>(allowed),
                       companyUserGroupId: tenantId, returnRecords: true]
        DataDefinedExtract.run(ec, context)
        List errors = (List) (context.errors ?: [])
        if (errors) { errors.each { ec.message.addError(it.toString()) }; return null }
        List<Map> records = (List<Map>) (context.records ?: [])
        return [record: records ? AgentRedaction.redactRecord(records[0], allowed) : null, found: !records.isEmpty(),
                recordKind: entry.recordKind, system: entry.system]
    }

    // ---------------------------------------------------------------- knowledge

    static List<Map> searchKnowledge(def ec, String words, Object limitValue) {
        String tenantId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        Map result = KnowledgeSupport.search(ec, tenantId, words, null, null, clamp(limitValue, 8, 25))
        return (List<Map>) result.hits
    }

    // ---------------------------------------------------------------- helpers

    private static Map<String, String> enumWords(def ec, String enumTypeId) {
        return ec.entity.find("moqui.basic.Enumeration").condition("enumTypeId", enumTypeId).useCache(true).list()
                .collectEntries { [(it.enumId as String): ((it.description ?: it.enumId) as String).toLowerCase()] }
    }

    private static int clamp(Object value, int fallback, int max) {
        int n = value == null ? fallback : (value as Integer)
        return Math.max(1, Math.min(n, max))
    }

    private static String text(Object v) { v?.toString()?.replaceAll(/^"|"$/, "")?.trim() }
}
