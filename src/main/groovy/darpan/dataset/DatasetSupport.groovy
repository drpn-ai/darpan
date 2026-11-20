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
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.security.MessageDigest

import static darpan.common.ValueSupport.normalize

/** DAR-BE-318 plan 3a: helpers for the kept copy of a dataset (the hashing and folder ones are pure). */
class DatasetSupport {
    private static final Logger logger = LoggerFactory.getLogger(DatasetSupport)

    /** {@link #unionFields}' keep list: the fields a dataset's copy keeps of each record, in order; null for whole records. */
    static Set<String> keptFields(def ec, Map dataset) {
        return (Set<String>) unionFields(ec, dataset)?.keep
    }

    /**
     * Plan 3b-1 (ruling 3b1-7): what a dataset's read asks of its source, as [keep, read], or null for whole records. Over
     * the dataset tenant's current run sources (RuleSetCompareSource of an active, unarchived rule set) whose system,
     * connection and options key equal the dataset's (KeptCopySupport.optionsKey, the key find#ServingDataset matches a side
     * by; ruling P3b1-1):
     * <ul>
     * <li>keep: each source's keep fields (RunExtractSupport.sideKeepFields) and filter fields (a slice filters the copy
     *     record), then the dataset's own id, stamp and window fields; trimmed to the fields the connector's projection
     *     service says its read can keep (project#KeptRecords' keepableFields), so one source's field the system cannot read
     *     never fails the shared read (that source's side then reads live, as it would anyway);</li>
     * <li>read: each source's read fields (ReconciliationSavedRunSupport.extractReadFields), sent as readFields, the way a
     *     live read sends them: a field the system cannot select is left out by its read, never refused.</li>
     * </ul>
     * Null when the connector names no projection service (servingProjectionServiceName, ruling P3b1-9) or takes no keep
     * fields, when no source matches, when any matching source keeps whole records (no projection; every current automation
     * source, which sends none), or when the active tenant is not the dataset's. Only the dataset tenant's rows are read (A7).
     * Null too, logged, when the trim would drop the dataset's own id, stamp or window field or leave nothing to keep: a copy
     * without them cannot be keyed, stamped or windowed, so it keeps whole records.
     */
    static Map<String, Set<String>> unionFields(def ec, Map dataset) {
        String tenantId = normalize(dataset?.companyUserGroupId)
        String systemEnumId = normalize(dataset?.systemEnumId)
        String sourceConfigId = normalize(dataset?.sourceConfigId)
        if (!tenantId || !systemEnumId || !sourceConfigId) return null
        if (TenantAccessSupport.currentActiveTenantUserGroupId(ec) != tenantId) return null
        Map<String, Object> connector = SourceSystemConnectorSupport.resolve(ec, systemEnumId)
        if (!normalize(connector?.servingProjectionServiceName) || !normalize(connector?.keepFieldsParameterName)) return null
        String datasetHash = normalize(dataset.optionsHash) ?: optionsHash(new groovy.json.JsonSlurper().parseText(
                (dataset.optionsJson ?: "{}") as String) as Map)

        Set<String> keep = new LinkedHashSet<String>()
        Set<String> read = new LinkedHashSet<String>()
        int matched = 0
        // automation sources keep whole records: a current one that reads this copy makes it whole (M6: an archived or
        // paused automation runs nothing)
        Set<String> currentAutomations = TenantScopedFinder.findTenantScoped(ec, "darpan.reconciliation.ReconciliationAutomation")
                .condition("companyUserGroupId", tenantId).useCache(false).list()
                .findAll { current(it) }*.automationId as Set<String>
        def automationSources = TenantScopedFinder.findTenantScoped(ec, "darpan.reconciliation.ReconciliationAutomationSource")
                .condition("companyUserGroupId", tenantId).condition("systemEnumId", systemEnumId).useCache(false).list()
        for (def source : automationSources) {
            if (!((source.automationId as String) in currentAutomations)) continue
            Map parameters = (AutomationExecutionSupport.resolveSourceExtractorMetadata(ec, source, [:])?.parameters ?: [:]) as Map
            if (matches(connector, parameters, sourceConfigId, datasetHash)) return null
        }

        // run sources: the tenant's current rule sets (the tenant boundary), their scopes, their sides on this system
        List<String> ruleSetIds = TenantScopedFinder.findTenantScoped(ec, "darpan.rule.RuleSet").condition("companyUserGroupId", tenantId)
                .useCache(false).list().findAll { current(it) }*.ruleSetId as List<String>
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
            List<String> sideKeep = RunExtractSupport.sideKeepFields(ec, source, connector)
            if (sideKeep == null) return null
            keep.addAll(sideKeep)
            read.addAll(ReconciliationSavedRunSupport.extractReadFields(ec, connector.extractServiceName as String,
                    source.compareScopeId, source.fileSide) ?: [])
            try {
                keep.addAll(ReconciliationSavedRunSupport.resolveExtractExcludeFilters(ec, source)
                        .collect { normalize(it.fieldExpression) }.findAll { it })
            } catch (IllegalArgumentException ignored) {
                // a filter its live read would refuse as well: keep whole records rather than guess its field
                return null
            }
        }
        if (matched == 0) return null
        Set<String> own = new LinkedHashSet<String>(DatasetRefreshSupport.idFields(dataset.idField as String))
        [dataset.stampField, dataset.windowField].each { String f = normalize(it); if (f) own.add(f) }
        keep.addAll(own)
        Set<String> keepable = keepableFields(ec, connector, keep)
        if (keepable == null) return null
        Set<String> trimmed = keep.findAll { it in keepable } as LinkedHashSet<String>
        List<String> ownDropped = own.findAll { !(it in keepable) } as List<String>
        if (ownDropped || trimmed.isEmpty()) {
            logger.warn("Dataset ${dataset.datasetId} on ${systemEnumId} keeps whole records: its read cannot keep " +
                    (ownDropped ? "its own field(s) ${ownDropped.join(', ')}" : "any of ${keep.join(', ')}"))
            return null
        }
        return [keep: trimmed, read: read]
    }

    /** A rule set or automation that still runs: active and not archived. */
    private static boolean current(def row) {
        return !"N".equalsIgnoreCase(normalize(row.isActive) ?: "Y") && !"Y".equalsIgnoreCase(normalize(row.isArchived) ?: "N")
    }

    /**
     * The fields of {@code fields} the connector's projection service says its read can keep (project#KeptRecords called
     * with unionFields); null, so whole records, when the service is missing or fails (a failure is logged). Leaves the
     * message facade as it found it.
     */
    private static Set<String> keepableFields(def ec, Map<String, Object> connector, Set<String> fields) {
        String serviceName = normalize(connector.servingProjectionServiceName)
        if (!SourceSystemConnectorSupport.isAllowedProjectionServiceShape(serviceName) || ec.service.getServiceDefinition(serviceName) == null) {
            return null
        }
        // a service called with errors on the facade does not run; those errors are the caller's, left alone
        if (ec.message.hasError()) return null
        try {
            Map out = ec.service.sync().name(serviceName).parameters([systemEnumId: connector.systemEnumId,
                    unionFields: new ArrayList<String>(fields)]).call() ?: [:]
            if (ec.message.hasError()) {
                logger.warn("The kept-fields union call ${serviceName} for ${connector.systemEnumId} failed; the copy keeps whole " +
                        "records: ${ec.message.getErrorsString()}")
                return null
            }
            return out.keepableFields instanceof Collection ? (out.keepableFields as Collection).collect { it as String } as Set<String> : null
        } catch (Throwable t) {
            logger.warn("The kept-fields union call ${serviceName} for ${connector.systemEnumId} failed; the copy keeps whole records", t)
            return null
        } finally {
            ec.message.clearErrors()
        }
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
