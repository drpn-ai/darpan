package darpan.agent

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
        if (!"true".equalsIgnoreCase(propertyValue(ec))) return AGENT_OFF
        String tenantId = ec.user.context.get("activeTenantUserGroupId")?.toString()?.trim()
        if (!tenantId) return AGENT_OFF
        // The tenant-scope filter narrows this read to the active tenant; the explicit key keeps it correct without it.
        def setting = ec.entity.find("darpan.auth.TenantSetting").condition("companyUserGroupId", tenantId).useCache(false).one()
        return setting?.agentEnabled == "Y" ? ON : AGENT_OFF
    }

    private static String propertyValue(def ec) {
        return System.getenv(ENABLED_ENV)?.toString()?.trim() ?:
                ec?.resource?.properties?.get(ENABLED_PROPERTY)?.toString()?.trim() ?:
                System.getProperty(ENABLED_PROPERTY)?.toString()?.trim() ?:
                null
    }
}
