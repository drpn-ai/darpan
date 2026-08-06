package darpan.reconciliation.automation

import java.util.regex.Pattern

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.normalizeBool
import static darpan.common.ValueSupport.readField
import static darpan.common.ValueSupport.readString

/**
 * Read-only resolver over the SourceSystemConnector registry (config over code).
 *
 * Phase 1 (DAR-292 / DAR-293): nothing in the dispatch paths calls this yet. It exists so the
 * per-system switches in AutomationExecutionSupport / ReconciliationSavedRunSupport can be replaced
 * with data-driven lookups in later phases.
 *
 * SourceSystemConnector is use="configuration" (platform config, NOT tenant-scoped), so its own read
 * is a plain cached find (the JsonSchemaUtil config-read convention). Any downstream config-id /
 * source-config lookups a caller performs off the resolved row remain tenant-scoped via
 * TenantScopedFinder.
 */
class SourceSystemConnectorSupport {
    static final String ENTITY_NAME = "darpan.reconciliation.SourceSystemConnector"

    /**
     * Collapse a raw system id to a comparison key the same way
     * ReconciliationSavedRunSupport.canonicalSystemEnumId does: strip whitespace/underscore/hyphen and
     * upper-case. Used for alias matching so "DAR_SYS_OMS", "Hot-Wax" and "oms" all line up.
     */
    static String normalizeSystemKey(Object raw) {
        String n = normalize(raw)
        if (!n) return null
        return n.replaceAll(/[\s_-]/, "").toUpperCase()
    }

    /**
     * Resolve a connector by exact systemEnumId (PK) or by one of its systemAliases.
     * Returns the connector's fields as a Map, or null when no ENABLED row matches.
     */
    static Map<String, Object> resolve(def ec, String rawSystemEnumId) {
        String raw = normalize(rawSystemEnumId)
        if (!raw) return null

        def record = ec.entity.find(ENTITY_NAME)
                .condition("systemEnumId", raw)
                .useCache(true)
                .one()

        if (record == null) {
            String key = normalizeSystemKey(raw)
            record = ec.entity.find(ENTITY_NAME).useCache(true).list()?.find { row ->
                isEnabled(row) && aliasKeys(row).contains(key)
            }
        }

        if (record == null || !isEnabled(record)) return null
        return toConnectorMap(record)
    }

    /**
     * Reverse lookup: the enabled connector whose extractServiceName matches, so the dispatch path can
     * read date-parameter names / preserveWindowInstants keyed by the service being invoked (what the
     * pre-registry switch keyed on). Returns null when no enabled row matches.
     */
    static Map<String, Object> resolveByExtractServiceName(def ec, String serviceName) {
        String svc = normalize(serviceName)
        if (!svc) return null
        def record = ec.entity.find(ENTITY_NAME).useCache(true).list()?.find { row ->
            isEnabled(row) && svc == readString(row, "extractServiceName")
        }
        return record == null ? null : toConnectorMap(record)
    }

    /**
     * Resolve the enabled connector whose expectedSourceConfigType matches. The interactive
     * saved-run path keys sources on sourceConfigType (not systemEnumId), so this is its
     * registry entry point — one connector per config type by construction.
     */
    static Map<String, Object> resolveByExpectedSourceConfigType(def ec, String sourceConfigType) {
        String wanted = normalize(sourceConfigType)
        if (!wanted) return null
        def record = ec.entity.find(ENTITY_NAME).useCache(true).list()?.find { row ->
            isEnabled(row) && wanted == readString(row, "expectedSourceConfigType")
        }
        return record == null ? null : toConnectorMap(record)
    }

    /**
     * The set of service names any enabled connector may dispatch: each row's extractServiceName plus
     * its remoteSendServiceName. This is the data-driven allow-list — registering a connector row
     * auto-permits its extract service, so onboarding a new source needs no core change.
     */
    static Set<String> allowedServiceNames(def ec) {
        Set<String> names = new LinkedHashSet<>()
        ec.entity.find(ENTITY_NAME).useCache(true).list()?.each { row ->
            if (!isEnabled(row)) return
            String extract = readString(row, "extractServiceName")
            if (extract) names.add(extract)
            String remote = readString(row, "remoteSendServiceName")
            if (remote) names.add(remote)
        }
        return names
    }

    /**
     * Recognized extractor/execute service-name shape. The reconciliation extract services
     * (reconciliation.*ExtractionServices.extract#*) and the Shopify GraphQL execute service
     * (facade.ShopifyFacadeServices.execute#*) match; arbitrary internal service names
     * (create#/store#/delete# or framework services) do not.
     */
    private static final Pattern EXTRACTOR_SERVICE_NAME =
            Pattern.compile(/^(reconciliation|facade)\.[A-Za-z0-9]+(Extraction|Facade)Services\.(extract|execute)#[A-Za-z0-9]+$/)

    /**
     * Defense-in-depth naming guard: even a registry-registered service name must match a recognized
     * extractor/execute shape before it is dispatched, so a misconfigured/hostile connector row cannot
     * point the (authz-relaxed) dispatch sink at an arbitrary internal service.
     */
    static boolean isAllowedExtractorServiceShape(String serviceName) {
        String s = normalize(serviceName)
        return s != null && EXTRACTOR_SERVICE_NAME.matcher(s).matches()
    }

    /**
     * Recognized verification lookup service-name shape: the lookup slot may only dispatch lookup#*
     * services (never extract/execute/framework services), keeping the verification sink narrower
     * than the extraction fence.
     */
    private static final Pattern LOOKUP_SERVICE_NAME =
            Pattern.compile(/^(reconciliation|facade)\.[A-Za-z0-9]+(Extraction|Facade)Services\.lookup#[A-Za-z0-9]+$/)

    static boolean isAllowedLookupServiceShape(String serviceName) {
        String s = normalize(serviceName)
        return s != null && LOOKUP_SERVICE_NAME.matcher(s).matches()
    }

    /**
     * Recognized connection-diagnostics service-name shape. `probe#` is reserved for this slot alone:
     * a registry row is data, so anyone able to write the table could otherwise aim the diagnostics
     * sink at an extract/execute/framework service. Reserving a verb no other slot dispatches keeps
     * this the narrowest fence of the three.
     */
    private static final Pattern PROBE_SERVICE_NAME =
            Pattern.compile(/^(reconciliation|facade)\.[A-Za-z0-9]+(Extraction|Facade)Services\.probe#[A-Za-z0-9]+$/)

    static boolean isAllowedProbeServiceShape(String serviceName) {
        String s = normalize(serviceName)
        return s != null && PROBE_SERVICE_NAME.matcher(s).matches()
    }

    protected static boolean isEnabled(def record) {
        return normalizeBool(readField(record, "enabled"), true)
    }

    protected static Set<String> aliasKeys(def record) {
        String raw = readString(record, "systemAliases")
        if (!raw) return Collections.<String> emptySet()
        return raw.split(/[,\s]+/)
                .collect { normalizeSystemKey(it) }
                .findAll { it } as Set<String>
    }

    protected static Map<String, Object> toConnectorMap(def record) {
        return [
                systemEnumId               : readString(record, "systemEnumId"),
                extractServiceName         : readString(record, "extractServiceName"),
                dateFromParameterName      : readString(record, "dateFromParameterName"),
                dateToParameterName        : readString(record, "dateToParameterName"),
                expectedSourceConfigType   : readString(record, "expectedSourceConfigType"),
                configParameterName        : readString(record, "configParameterName"),
                configEntityName           : readString(record, "configEntityName"),
                configIdResolverServiceName: readString(record, "configIdResolverServiceName"),
                validationServiceName      : readString(record, "validationServiceName"),
                remoteId                   : readString(record, "remoteId"),
                endpointLabel              : readString(record, "endpointLabel"),
                sendUrlTemplate            : readString(record, "sendUrlTemplate"),
                remoteSendServiceName      : readString(record, "remoteSendServiceName"),
                systemAliases              : readString(record, "systemAliases"),
                preserveWindowInstants     : normalizeBool(readField(record, "preserveWindowInstants"), false),
                keepFieldsParameterName    : readString(record, "keepFieldsParameterName"),
                keepFieldsBase             : readString(record, "keepFieldsBase"),
                windowFieldName            : readString(record, "windowFieldName"),
                filterParameterName        : readString(record, "filterParameterName"),
                lookupServiceName          : readString(record, "lookupServiceName"),
                pairLookupServiceName          : readString(record, "pairLookupServiceName"),
                exchangeStateLookupServiceName : readString(record, "exchangeStateLookupServiceName"),
                exchangeSweepServiceName       : readString(record, "exchangeSweepServiceName"),
                healthCheckServiceName     : readString(record, "healthCheckServiceName"),
                enabled                    : isEnabled(record),
        ] as Map<String, Object>
    }
}
