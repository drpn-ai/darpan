package darpan.agent

import darpan.facade.common.TenantAccessSupport

/**
 * DAR-BE-159. Whether the embedded agent may run for the active tenant. Off unless the deployment property
 * darpan.agent.enabled is 'true' AND the tenant's TenantSetting.agentEnabled is 'Y'. Whether an API key is configured is
 * checked later, by the caller that needs it, so core only answers ON or AGENT_OFF.
 */
class AgentSwitch {
    static final String ENABLED_PROPERTY = "darpan.agent.enabled"
    static final String ENABLED_ENV = "DARPAN_AGENT_ENABLED"
    static final String ON = "ON"
    static final String AGENT_OFF = "AGENT_OFF"
    static final String AGENT_UNAVAILABLE = "AGENT_UNAVAILABLE"

    static String state(def ec) {
        if (!"true".equalsIgnoreCase(propertyValue())) return AGENT_OFF
        String tenantId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)?.trim()
        if (!tenantId) return AGENT_OFF
        // The tenant-scope filter narrows this read to the active tenant; the explicit key keeps it correct without it.
        def setting = ec.entity.find("darpan.auth.TenantSetting").condition("companyUserGroupId", tenantId).useCache(false).one()
        return setting?.agentEnabled == "Y" ? ON : AGENT_OFF
    }

    private static String propertyValue() {
        // Moqui copies a conf default-property (MoquiProductionConf) into System properties at startup, unless one is already
        // set, so System.getProperty is where the prod setting lives. (ec.resource.properties is the Groovy bean map, not conf.)
        return System.getenv(ENABLED_ENV)?.toString()?.trim() ?:
                System.getProperty(ENABLED_PROPERTY)?.toString()?.trim() ?:
                null
    }
}
