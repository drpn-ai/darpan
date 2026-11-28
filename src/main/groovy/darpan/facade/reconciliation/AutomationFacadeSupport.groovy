package darpan.facade.reconciliation

import darpan.common.DarpanEntityConstants
import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantScopedFinder
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp
import java.time.Instant

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.readString

class AutomationFacadeSupport {
    protected static final Logger logger = LoggerFactory.getLogger(AutomationFacadeSupport.class)

    static final String INPUT_MODE_API_RANGE = "AUT_IN_API_RANGE"
    static final String INPUT_MODE_SFTP_FILES = "AUT_IN_SFTP_FILES"
    static final String SOURCE_TYPE_SFTP = "AUT_SRC_SFTP"
    static final String SHOPIFY_SYSTEM_ENUM_ID = "SHOPIFY"
    static final String NETSUITE_SYSTEM_ENUM_ID = "NETSUITE"
    // Constants below reference ReconciliationSavedRunSupport to avoid duplicate string literals.
    static final String SOURCE_TYPE_API = ReconciliationSavedRunSupport.SOURCE_TYPE_API
    static final String SOURCE_TYPE_DB = ReconciliationSavedRunSupport.SOURCE_TYPE_DB
    static final String FILE_SIDE_1 = ReconciliationSavedRunSupport.FILE_SIDE_1
    static final String FILE_SIDE_2 = ReconciliationSavedRunSupport.FILE_SIDE_2
    static final String SOURCE_CONFIG_TYPE_SHOPIFY_AUTH = ReconciliationSavedRunSupport.SOURCE_CONFIG_TYPE_SHOPIFY_AUTH
    static final String SOURCE_CONFIG_TYPE_NETSUITE_AUTH = ReconciliationSavedRunSupport.SOURCE_CONFIG_TYPE_NETSUITE_AUTH
    /** Matches SourceSystemConnector.expectedSourceConfigType on the NETSUITE_SUITEQL row. */
    static final String SOURCE_CONFIG_TYPE_NETSUITE_SUITEQL = "NETSUITE_SUITEQL"
    static final String NETSUITE_SUITEQL_SYSTEM_ENUM_ID = "NETSUITE_SUITEQL"
    static final String SHOPIFY_ORDERS_REMOTE_ID = ReconciliationSavedRunSupport.SHOPIFY_ORDERS_REMOTE_ID
    static final String SHOPIFY_ORDERS_EXTRACT_SERVICE = ReconciliationSavedRunSupport.SHOPIFY_ORDERS_EXTRACT_SERVICE
    static final String SHOPIFY_GRAPHQL_EXECUTE_SERVICE = ReconciliationSavedRunSupport.SHOPIFY_GRAPHQL_EXECUTE_SERVICE
    static final String HOTWAX_OMS_WINDOW_START_PARAMETER = "windowStart"
    static final String HOTWAX_OMS_WINDOW_END_PARAMETER = "windowEnd"
    static final String SHOPIFY_WINDOW_START_PARAMETER = "windowStart"
    static final String SHOPIFY_WINDOW_END_PARAMETER = "windowEnd"
    static final List<String> FILE_SIDES = [FILE_SIDE_1, FILE_SIDE_2].asImmutable()
    static final List<String> SUPPORTED_INPUT_MODES = [INPUT_MODE_API_RANGE, INPUT_MODE_SFTP_FILES].asImmutable()

    // RD1 (DAR-OPS-029 S19): save validation and enrichment live in AutomationSaveSupport. These delegates keep the XML
    // (save#Automation, list#Automations), AuditCheckAutomation and another slice's test resolving; S99 repoints and
    // deletes them.
    static Map<String, Object> prepareAutomationSave(def ec, Map params = [:]) { return AutomationSaveSupport.prepareAutomationSave(ec, params) }

    static Map<String, List<Map<String, Object>>> captureSourceFiltersByFileSide(def ec, String automationId) {
        return AutomationSaveSupport.captureSourceFiltersByFileSide(ec, automationId)
    }

    static List<Map<String, Object>> resolveSourceFilters(def ec, String compareScopeId, Object sourceEntry,
            Map<String, List<Map<String, Object>>> capturedByFileSide) {
        return AutomationSaveSupport.resolveSourceFilters(ec, compareScopeId, sourceEntry, capturedByFileSide)
    }

    static List<Map<String, Object>> seedFromRuleSet(def ec, String compareScopeId, String fileSide) {
        return AutomationSaveSupport.seedFromRuleSet(ec, compareScopeId, fileSide)
    }

    static List<Map<String, Object>> seedFromRuleSet(def ec, String tenantId, String compareScopeId, String fileSide) {
        return AutomationSaveSupport.seedFromRuleSet(ec, tenantId, compareScopeId, fileSide)
    }

    static void validateApiSource(def ec, Map<String, Object> source) { AutomationSaveSupport.validateApiSource(ec, source) }

    // RD1 (DAR-OPS-029 S19): the read model lives in AutomationRowSupport. These delegates keep the
    // ReconciliationFacadeServices.xml callers resolving; S99 repoints them and deletes these.
    static Map<String, Object> buildAutomationRow(def ec, def automation, boolean includeSources, List sources, List executions,
                                                  Map<String, Object> peakTick = null) {
        return AutomationRowSupport.buildAutomationRow(ec, automation, includeSources, sources, executions, peakTick)
    }

    static Map<String, Object> buildExecutionRow(def ec, def execution) {
        return AutomationRowSupport.buildExecutionRow(ec, execution)
    }

    static List collapseExecutionHistory(List executions) {
        return AutomationRowSupport.collapseExecutionHistory(executions)
    }

    // RD1 (DAR-OPS-029 S19): sync and drift live in AutomationSyncSupport. These delegates keep the XML
    // (ReconciliationFacadeServices.xml) and AuditChangeLevers callers resolving; S99 repoints them and deletes these.
    static Map<String, Object> deriveRunAuthoritativeConfig(def ec, def automation) {
        return AutomationSyncSupport.deriveRunAuthoritativeConfig(ec, automation)
    }

    static List<Map<String, Object>> mergeSyncedSourceEntries(List storedSources,
            Map<String, Map<String, Object>> derivedByFileSide, Map<String, List<Map<String, Object>>> filtersByFileSide) {
        return AutomationSyncSupport.mergeSyncedSourceEntries(storedSources, derivedByFileSide, filtersByFileSide)
    }

    static String resolveSyncedInputMode(List<Map<String, Object>> mergedSourceEntries) {
        return AutomationSyncSupport.resolveSyncedInputMode(mergedSourceEntries)
    }

    static Map<String, Object> buildAutomationSyncPayload(def automation, String derivedInputModeEnumId,
            List<Map<String, Object>> sourceEntries) {
        return AutomationSyncSupport.buildAutomationSyncPayload(automation, derivedInputModeEnumId, sourceEntries)
    }

    static Map<String, Object> buildAutomationSyncStatus(def automation, List storedSources, Map<String, Object> derivedConfig) {
        return AutomationSyncSupport.buildAutomationSyncStatus(automation, storedSources, derivedConfig)
    }

    static List<Map<String, Object>> attachCapturedFilters(def ec, List sources, String automationId) {
        return AutomationSyncSupport.attachCapturedFilters(ec, sources, automationId)
    }

    // RD1 (DAR-OPS-029 S19): the option lists live in AutomationSourceOptionsSupport. These delegates keep the XML
    // (ReconciliationFacadeServices.xml, SettingsFacadeServices.xml), other slices' tests and netsuite-darpan resolving;
    // S99 repoints them and deletes these.
    static final Map<String, List<String>> SUITEQL_TEMPLATE_FIELDS = AutomationSourceOptionsSupport.SUITEQL_TEMPLATE_FIELDS

    static List<Map<String, Object>> listEnumOptions(def ec, String enumTypeId) { return AutomationSourceOptionsSupport.listEnumOptions(ec, enumTypeId) }

    static List<Map<String, Object>> listSftpServerOptions(def ec) { return AutomationSourceOptionsSupport.listSftpServerOptions(ec) }

    static List<Map<String, Object>> listSourceConfigOptions(def ec) { return AutomationSourceOptionsSupport.listSourceConfigOptions(ec) }

    static List<Map<String, Object>> listNsRestletOptions(def ec) { return AutomationSourceOptionsSupport.listNsRestletOptions(ec) }

    static List<Map<String, Object>> listNsSuiteQlQueryOptions(def ec) { return AutomationSourceOptionsSupport.listNsSuiteQlQueryOptions(ec) }

    static List<Map<String, Object>> listOmsRestSourceRemoteOptions(def ec) { return AutomationSourceOptionsSupport.listOmsRestSourceRemoteOptions(ec) }

    static List<Map<String, Object>> listShopifySourceRemoteOptions(def ec) { return AutomationSourceOptionsSupport.listShopifySourceRemoteOptions(ec) }

    static List<Map<String, Object>> connectorRowsForConfigEntity(def ec, String configEntityName) {
        return AutomationSourceOptionsSupport.connectorRowsForConfigEntity(ec, configEntityName)
    }

    static List<Map<String, Object>> primaryIdOptionsForSystem(def ec, String systemEnumId) {
        return AutomationSourceOptionsSupport.primaryIdOptionsForSystem(ec, systemEnumId)
    }

    static List<Map<String, Object>> fieldOptionsForSystem(def ec, String systemEnumId) {
        return AutomationSourceOptionsSupport.fieldOptionsForSystem(ec, systemEnumId)
    }

    static boolean supportsExcludeFiltersForSystem(def ec, String systemEnumId) {
        return AutomationSourceOptionsSupport.supportsExcludeFiltersForSystem(ec, systemEnumId)
    }

    protected static String sftpServerLabel(def ec, String sftpServerId) {
        if (!sftpServerId) return null
        def server = TenantScopedFinder.findGlobalUnscoped(ec, DarpanEntityConstants.SFTP_SERVER,
                        "sftp server label lookup for display — access enforced at execution time")
                .condition("sftpServerId", sftpServerId)
                .useCache(true)
                .one()
        if (!server) return sftpServerId
        return readString(server, "description") ?: readString(server, "sftpServerId")
    }

    protected static String enumLabel(def ec, String enumId) {
        def enumValue = FacadeSupport.findEnum(ec, enumId)
        return enumValue ? FacadeSupport.enumLabel(enumValue) : enumId
    }

    protected static String enumCode(def ec, String enumId) {
        return readString(FacadeSupport.findEnum(ec, enumId), "enumCode")
    }

    static String canonicalJsonText(def ec, Object rawValue, String label) {
        String value = normalize(rawValue)
        if (!value) return null
        try {
            Object parsed = new JsonSlurper().parseText(value)
            return JsonOutput.toJson(parsed)
        } catch (Exception e) {
            ec.message.addError("${label} must be valid JSON")
            return null
        }
    }

    protected static Map<String, Object> parseJsonMap(Object rawValue) {
        String value = normalize(rawValue)
        if (!value) return [:]
        try {
            Object parsed = new JsonSlurper().parseText(value)
            return parsed instanceof Map ? new LinkedHashMap<>((Map<String, Object>) parsed) : [:]
        } catch (Exception e) {
            logger.warn("Invalid JSON in automation metadata field", e)
            return [:]
        }
    }

    protected static Timestamp toTimestamp(Object rawValue) {
        if (rawValue == null) return null
        if (rawValue instanceof Timestamp) return (Timestamp) rawValue
        if (rawValue instanceof Date) return new Timestamp(((Date) rawValue).time)
        String value = normalize(rawValue)
        if (!value) return null
        try {
            return Timestamp.from(Instant.parse(value))
        } catch (Exception ignored) {
            return Timestamp.valueOf(value)
        }
    }

    protected static Timestamp nowTimestamp(def ec) {
        return ec?.user?.nowTimestamp ?: new Timestamp(System.currentTimeMillis())
    }

}
