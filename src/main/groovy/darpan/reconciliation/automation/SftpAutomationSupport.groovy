package darpan.reconciliation.automation

import darpan.facade.common.DataManagerSupport
import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport

import org.moqui.sftp.SftpClient

class SftpAutomationSupport {
    static final String SFTP_SCOPE_TENANT = "DARPAN_SFTP_TENANT"
    static final String SFTP_SCOPE_TENANT_GROUP = "DARPAN_SFTP_TENANT_GROUP"
    static final String SFTP_SCOPE_ADMIN = "DARPAN_SFTP_ADMIN"

    private static final Closure DEFAULT_CLIENT_FACTORY = { String host, String user, Integer port ->
        new SftpClient(host, user, port)
    }

    private static Closure clientFactory = DEFAULT_CLIENT_FACTORY

    static Object createClient(String host, String user, Integer port) {
        return clientFactory.call(host, user, port)
    }

    static void setClientFactory(Closure factory) {
        clientFactory = factory ?: DEFAULT_CLIENT_FACTORY
    }

    static void resetClientFactory() {
        clientFactory = DEFAULT_CLIENT_FACTORY
    }

    static String resolveDefaultOutputLocation(def ec, Object runId, Object timestamp) {
        return DataManagerSupport.resolveReconciliationRunLocation(ec, runId, timestamp)
    }

    static Map<String, Object> resolveRunScope(def ec, Object rawRunScopeEnumId, Object rawRunTenantUserGroupId,
            Object rawAllowAdminSftp) {
        String runScopeEnumId = normalize(rawRunScopeEnumId) ?: SFTP_SCOPE_TENANT
        if (![SFTP_SCOPE_TENANT, SFTP_SCOPE_ADMIN].contains(runScopeEnumId)) {
            throw new IllegalArgumentException("sftpRunScopeEnumId must be ${SFTP_SCOPE_TENANT} or ${SFTP_SCOPE_ADMIN}")
        }

        String runTenantUserGroupId = normalize(rawRunTenantUserGroupId)
        if (!runTenantUserGroupId && runScopeEnumId == SFTP_SCOPE_TENANT) {
            runTenantUserGroupId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        }

        if (runScopeEnumId == SFTP_SCOPE_TENANT && !runTenantUserGroupId) {
            throw new IllegalArgumentException("runTenantUserGroupId is required for tenant-scoped SFTP automation")
        }

        boolean allowAdminSftp = rawAllowAdminSftp == Boolean.TRUE ||
                ["Y", "true", "TRUE", "yes", "YES"].contains(normalize(rawAllowAdminSftp))
        return [
                runScopeEnumId      : runScopeEnumId,
                runTenantUserGroupId: runTenantUserGroupId,
                allowAdminSftp      : allowAdminSftp,
        ]
    }

    static Object loadSftpServerForRun(def ec, Object rawSftpServerId, Map<String, Object> runScope, String label) {
        String sftpServerId = normalize(rawSftpServerId)
        if (!sftpServerId) throw new IllegalArgumentException("SFTP server is required for ${label}")

        def server = ec?.entity?.find("darpan.reconciliation.SftpServer")
                ?.condition("sftpServerId", sftpServerId)
                ?.disableAuthz()
                ?.useCache(false)
                ?.one()
        if (!server) throw new IllegalArgumentException("SFTP Server ${sftpServerId} not found for ${label}")

        requireSftpServerAccess(ec, server, runScope, label)
        return server
    }

    static void requireSftpServerAccess(def ec, def server, Map<String, Object> runScope, String label) {
        String sftpServerId = normalize(readField(server, "sftpServerId"))
        String serverScopeEnumId = resolveServerScopeEnumId(server)
        String runScopeEnumId = normalize(runScope?.runScopeEnumId) ?: SFTP_SCOPE_TENANT
        String runTenantUserGroupId = normalize(runScope?.runTenantUserGroupId)

        if (runScopeEnumId == SFTP_SCOPE_ADMIN) {
            if (serverScopeEnumId != SFTP_SCOPE_ADMIN) {
                throw new IllegalArgumentException("SFTP Server ${sftpServerId} is not an admin SFTP server for ${label}")
            }
            if (runScope?.allowAdminSftp != true) {
                throw new IllegalArgumentException("allowAdminSftp must be true to use admin SFTP Server ${sftpServerId} for ${label}")
            }
            return
        }

        if (serverScopeEnumId == SFTP_SCOPE_ADMIN) {
            throw new IllegalArgumentException("Admin SFTP Server ${sftpServerId} is not available to tenant-scoped automation for ${label}")
        }

        if (!runTenantUserGroupId) {
            throw new IllegalArgumentException("runTenantUserGroupId is required to use SFTP Server ${sftpServerId} for ${label}")
        }

        if (serverScopeEnumId == SFTP_SCOPE_TENANT) {
            String ownerTenantUserGroupId = normalize(readField(server, "companyUserGroupId"))
            if (ownerTenantUserGroupId == runTenantUserGroupId) return
            throw new IllegalArgumentException("SFTP Server ${sftpServerId} is not available to tenant ${runTenantUserGroupId} for ${label}")
        }

        if (serverScopeEnumId == SFTP_SCOPE_TENANT_GROUP && tenantGroupServerAllowsTenant(ec, sftpServerId, runTenantUserGroupId)) {
            return
        }

        throw new IllegalArgumentException("SFTP Server ${sftpServerId} is not available to tenant ${runTenantUserGroupId} for ${label}")
    }

    static String resolveServerScopeEnumId(def server) {
        String explicitScope = normalize(readField(server, "scopeEnumId"))
        if (explicitScope) return explicitScope
        return normalize(readField(server, "companyUserGroupId")) ? SFTP_SCOPE_TENANT : SFTP_SCOPE_ADMIN
    }

    static String remotePathForRuntimeLocation(Object location) {
        String normalized = location?.toString()?.trim()
        if (!normalized?.startsWith("runtime://")) return null

        String path = normalized.substring("runtime://".length())
                .replaceAll(/\\+/, "/")
                .replaceAll(/\/+$/, "")
        if (!path) return null
        return path.startsWith("/") ? path : "/${path}"
    }

    protected static boolean tenantGroupServerAllowsTenant(def ec, String sftpServerId, String tenantUserGroupId) {
        if (!sftpServerId || !tenantUserGroupId) return false
        def access = ec?.entity?.find("darpan.reconciliation.SftpServerTenantAccess")
                ?.condition("sftpServerId", sftpServerId)
                ?.condition("tenantUserGroupId", tenantUserGroupId)
                ?.disableAuthz()
                ?.useCache(false)
                ?.one()
        return access != null
    }

    protected static String normalize(Object value) {
        return FacadeSupport.normalize(value)
    }

    protected static Object readField(def record, String fieldName) {
        if (record == null || !fieldName) return null
        if (record instanceof Map) return record[fieldName]
        if (record.metaClass.respondsTo(record, "get", String)) return record.get(fieldName)
        return record."${fieldName}"
    }
}
