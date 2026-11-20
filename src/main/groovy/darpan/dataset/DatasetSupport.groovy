package darpan.dataset

import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.facade.reconciliation.ReconciliationSavedRunSupport
import darpan.facade.reconciliation.RunExtractSupport
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.automation.SourceExtractOptionsSupport
import darpan.reconciliation.automation.SourceSystemConnectorSupport
import groovy.json.JsonOutput

import java.security.MessageDigest

import static darpan.common.ValueSupport.normalize

/** DAR-BE-318 plan 3a: helpers for the kept copy of a dataset (the hashing and folder ones are pure). */
class DatasetSupport {

    /**
     * Plan 3b-1 (ruling 3b1-7): the fields a dataset's copy keeps of each record, in order, or null for whole records. The
     * union, over the dataset tenant's run sources (RuleSetCompareSource) and automation sources whose system, connection and
     * options key equal the dataset's (KeptCopySupport.optionsKey, the key find#ServingDataset matches a side by; ruling
     * P3b1-1), of each source's keep fields (RunExtractSupport.sideKeepFields), read fields and filter fields (a slice
     * filters the copy record); then the dataset's own id, stamp and window fields. Null when the connector names no
     * projection service (servingProjectionServiceName, ruling P3b1-9) or takes no keep fields, when no source matches, when any matching source keeps whole records (no projection; every automation source, which sends
     * none), or when the active tenant is not the dataset's. Only the dataset tenant's rows are read (A7).
     */
    static Set<String> keptFields(def ec, Map dataset) {
        String tenantId = normalize(dataset?.companyUserGroupId)
        String systemEnumId = normalize(dataset?.systemEnumId)
        String sourceConfigId = normalize(dataset?.sourceConfigId)
        if (!tenantId || !systemEnumId || !sourceConfigId) return null
        if (TenantAccessSupport.currentActiveTenantUserGroupId(ec) != tenantId) return null
        Map<String, Object> connector = SourceSystemConnectorSupport.resolve(ec, systemEnumId)
        if (!normalize(connector?.servingProjectionServiceName) || !normalize(connector?.keepFieldsParameterName)) return null
        String datasetHash = normalize(dataset.optionsHash) ?: optionsHash(new groovy.json.JsonSlurper().parseText(
                (dataset.optionsJson ?: "{}") as String) as Map)

        Set<String> union = new LinkedHashSet<String>()
        int matched = 0
        // automation sources keep whole records: one that reads this copy makes it whole
        def automationSources = TenantScopedFinder.findTenantScoped(ec, "darpan.reconciliation.ReconciliationAutomationSource")
                .condition("companyUserGroupId", tenantId).condition("systemEnumId", systemEnumId).useCache(false).list()
        for (def source : automationSources) {
            Map parameters = (AutomationExecutionSupport.resolveSourceExtractorMetadata(ec, source, [:])?.parameters ?: [:]) as Map
            if (matches(connector, parameters, sourceConfigId, datasetHash)) return null
        }

        // run sources: the tenant's rule sets (the tenant boundary), their scopes, their sides on this system
        List<String> ruleSetIds = TenantScopedFinder.findTenantScoped(ec, "darpan.rule.RuleSet").condition("companyUserGroupId", tenantId)
                .useCache(false).list()*.ruleSetId as List<String>
        if (!ruleSetIds) return null
        Map<String, String> ruleSetOfScope = [:]
        TenantScopedFinder.findGlobalUnscoped(ec, "darpan.rule.RuleSetCompareScope",
                "transitively tenant-owned; ruleSetId pre-gated to the dataset tenant's rule sets")
                .condition("ruleSetId", "in", ruleSetIds).useCache(false).list().each { ruleSetOfScope.put(it.compareScopeId as String, it.ruleSetId as String) }
        if (!ruleSetOfScope) return null
        List sources = TenantScopedFinder.findGlobalUnscoped(ec, "darpan.rule.RuleSetCompareSource",
                "transitively tenant-owned; compareScopeId pre-gated to the dataset tenant's rule sets")
                .condition("compareScopeId", "in", ruleSetOfScope.keySet()).condition("systemEnumId", systemEnumId)
                .orderBy("compareScopeId,fileSide").useCache(false).list()
        for (def source : sources) {
            Map stored = SourceExtractOptionsSupport.runtimeOptions(connector, SourceExtractOptionsSupport.storedOn(source))
            Map parameters = new LinkedHashMap(stored)
            parameters.put(normalize(connector.configParameterName) ?: "sourceConfigId", source.sourceConfigId)
            if (!matches(connector, parameters, sourceConfigId, datasetHash)) continue
            matched++
            List<String> keep = RunExtractSupport.sideKeepFields(ec, source, connector)
            if (keep == null) return null
            union.addAll(keep)
            union.addAll(ReconciliationSavedRunSupport.extractReadFields(ec, connector.extractServiceName as String,
                    source.compareScopeId, source.fileSide) ?: [])
            try {
                union.addAll(ReconciliationSavedRunSupport.resolveExtractExcludeFilters(ec, source)
                        .collect { normalize(it.fieldExpression) }.findAll { it })
            } catch (IllegalArgumentException ignored) {
                // a filter its live read would refuse as well: keep whole records rather than guess its field
                return null
            }
        }
        if (matched == 0) return null
        union.addAll(DatasetRefreshSupport.idFields(dataset.idField as String))
        [dataset.stampField, dataset.windowField].each { String f = normalize(it); if (f) union.add(f) }
        return union
    }

    /** The source would read this dataset: its connection and its options key (KeptCopySupport.optionsKey) are the dataset's. */
    private static boolean matches(Map<String, Object> connector, Map parameters, String sourceConfigId, String datasetHash) {
        String configName = normalize(connector?.configParameterName) ?: "sourceConfigId"
        if (normalize(parameters?.get(configName)) != sourceConfigId) return false
        return optionsHash(KeptCopySupport.optionsKey(connector, (Map<String, Object>) parameters)) == datasetHash
    }

    /** SHA-256 hex (64 characters) of the options as canonical JSON: map keys sorted at every depth. */
    static String optionsHash(Map options) {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalJson(options).getBytes("UTF-8"))
        return digest.collect { String.format("%02x", it & 0xff) }.join()
    }

    static String canonicalJson(Object options) {
        return JsonOutput.toJson(canonical(options == null ? [:] : options))
    }

    private static Object canonical(Object value) {
        if (value instanceof Map) {
            Map sorted = new LinkedHashMap()
            ((Map) value).keySet().collect { it.toString() }.sort().each { String key -> sorted.put(key, canonical(((Map) value).get(key))) }
            return sorted
        }
        if (value instanceof Collection) return ((Collection) value).collect { canonical(it) }
        return value
    }

    /**
     * &lt;root&gt;/datasets/&lt;safe tenant&gt;/&lt;safe datasetId&gt;/, with the same safe-token rule as the run folders. Main code
     * passes the data manager root through DatasetRefreshSupport.datasetDir(ec, ...), which honours overrides (ruling P3a-7).
     */
    static File datasetDir(File root, String tenantId, String datasetId) {
        return new File(new File(new File(root, "datasets"), DataManagerSupport.safeToken(tenantId, "tenant")),
                DataManagerSupport.safeToken(datasetId, "dataset"))
    }
}
