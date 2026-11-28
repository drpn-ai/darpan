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
