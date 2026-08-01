package darpan.facade.settings

import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.reconciliation.automation.SourceSystemConnectorSupport
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import static darpan.common.ValueSupport.normalize

/**
 * Operator-initiated connection diagnostics, dispatched off the SourceSystemConnector registry.
 *
 * Core never names a connector: the registry row carries healthCheckServiceName, each connector
 * component seeds its own row, and this class only resolves, fences and dispatches. A component that
 * is not installed has no row, so its diagnostics are simply unavailable.
 *
 * Envelope contract note: `ok` keeps its house meaning ("the service ran"), because the SPA client
 * throws on `ok:false` (client.ts) — a connection that fails its checks is a SUCCESSFUL diagnostic
 * run reporting a negative verdict, so the verdict travels separately as `connectionOk`. Refusals
 * (no write access, unknown config) are genuine errors and are raised as such.
 */
class SourceConnectionDiagnosticsSupport {
    private static final Logger logger = LoggerFactory.getLogger(SourceConnectionDiagnosticsSupport.class)

    static final String STATUS_PASS = "PASS"
    static final String STATUS_FAIL = "FAIL"
    static final String STATUS_SKIP = "SKIP"

    private static final Set<String> ALLOWED_STATUSES = [STATUS_PASS, STATUS_FAIL, STATUS_SKIP].toSet()

    /** Same message for "no such config" and "another tenant's config" — no cross-tenant existence oracle. */
    private static final String UNKNOWN_CONFIG_MESSAGE = "Unable to find that configuration for the active tenant."

    static Map<String, Object> testSourceConnection(def ec, Object rawSystemEnumId, Object rawConfigId) {
        long startedAt = System.currentTimeMillis()

        String systemEnumId = normalize(rawSystemEnumId)
        String configId = normalize(rawConfigId)
        if (!systemEnumId) {
            ec.message.addError("Source system is required.")
            return unavailable(startedAt)
        }
        if (!configId) {
            ec.message.addError("Configuration ID is required.")
            return unavailable(startedAt)
        }

        // Running a probe spends a real credential against a real remote system, so it is gated
        // exactly like editing or deleting the config it tests — same helper, same wording.
        if (!TenantAccessSupport.requireActiveTenantWriteAccess(ec)) {
            return unavailable(startedAt)
        }

        Map<String, Object> connector = SourceSystemConnectorSupport.resolve(ec, systemEnumId)
        if (connector == null) {
            ec.message.addMessage("Diagnostics are not available for this source system.")
            return unavailable(startedAt)
        }

        String probeServiceName = normalize(connector.healthCheckServiceName)
        if (!probeServiceName) {
            ec.message.addMessage("Diagnostics are not available for this connector.")
            return unavailable(startedAt)
        }
        if (!SourceSystemConnectorSupport.isAllowedProbeServiceShape(probeServiceName)) {
            // Registry rows are data; a name that fails the fence is a misconfiguration, never
            // something to dispatch. Loud in the log, bland to the operator.
            logger.warn("Connector ${connector.systemEnumId} declares healthCheckServiceName " +
                    "'${probeServiceName}', which does not match the probe fence — refusing to dispatch.")
            ec.message.addMessage("Diagnostics are not available for this connector.")
            return unavailable(startedAt)
        }

        String configParameterName = normalize(connector.configParameterName)
        String configEntityName = normalize(connector.configEntityName)
        if (!configParameterName || !configEntityName) {
            ec.message.addMessage("Diagnostics are not available for this connector.")
            return unavailable(startedAt)
        }

        def configRecord = TenantScopedFinder.findTenantScopedByIdQuiet(ec, configEntityName, configParameterName, configId)
        if (configRecord == null) {
            ec.message.addError(UNKNOWN_CONFIG_MESSAGE)
            return unavailable(startedAt)
        }

        List<Map<String, Object>> checks
        try {
            Map<String, Object> result = ec.service.sync()
                    .name(probeServiceName)
                    .parameters([(configParameterName): configId] as Map<String, Object>)
                    .call() as Map<String, Object>
            checks = normalizeChecks(result?.get("checks"))
        } catch (Throwable t) {
            // A probe that blows up is a defect, but the operator should see a diagnostic verdict
            // rather than a stack trace — and never the exception text, which can carry request context.
            logger.error("Connection probe ${probeServiceName} failed for ${connector.systemEnumId}/${configId}", t)
            checks = [check("probe", "Diagnostics completed", STATUS_FAIL, "The diagnostic could not be completed.")]
        }

        if (checks.isEmpty()) {
            logger.warn("Connection probe ${probeServiceName} returned no checks for ${connector.systemEnumId}.")
            checks = [check("probe", "Diagnostics completed", STATUS_FAIL, "The diagnostic returned no results.")]
        }

        boolean connectionOk = checks.every { it.status != STATUS_FAIL }
        long durationMillis = System.currentTimeMillis() - startedAt
        logger.info("Connection diagnostics: system=${connector.systemEnumId} config=${configId} " +
                "user=${ec.user?.userId} verdict=${connectionOk ? 'OK' : 'FAILED'} durationMillis=${durationMillis}")

        return [
                available     : true,
                connectionOk  : connectionOk,
                checks        : checks,
                durationMillis: durationMillis,
        ] as Map<String, Object>
    }

    /** Build one normalized check row. Exposed so connector probes emit a consistent shape. */
    static Map<String, Object> check(String key, String label, String status, String detail = null,
                                     Long durationMillis = null) {
        return [
                key           : key,
                label         : label,
                status        : ALLOWED_STATUSES.contains(status) ? status : STATUS_FAIL,
                detail        : normalize(detail),
                durationMillis: durationMillis,
        ] as Map<String, Object>
    }

    /**
     * Coerce whatever a probe returned into the check contract. An unrecognized status becomes FAIL
     * rather than being dropped: a row nobody can classify must not silently read as healthy.
     */
    protected static List<Map<String, Object>> normalizeChecks(Object raw) {
        if (!(raw instanceof Collection)) return []
        List<Map<String, Object>> out = []
        ((Collection) raw).each { Object item ->
            if (!(item instanceof Map)) return
            Map row = (Map) item
            String key = normalize(row.get("key"))
            String label = normalize(row.get("label"))
            if (!key || !label) return
            String status = normalize(row.get("status"))?.toUpperCase()
            Object rawDuration = row.get("durationMillis")
            out.add(check(key, label, status, normalize(row.get("detail")),
                    rawDuration instanceof Number ? ((Number) rawDuration).longValue() : null))
        }
        return out
    }

    private static Map<String, Object> unavailable(long startedAt) {
        return [
                available     : false,
                connectionOk  : false,
                checks        : [],
                durationMillis: System.currentTimeMillis() - startedAt,
        ] as Map<String, Object>
    }
}
