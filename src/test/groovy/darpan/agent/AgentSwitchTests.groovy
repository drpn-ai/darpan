package darpan.agent

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

/** DAR-BE-159. The agent runs only when the deployment property AND the tenant flag are both on. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentSwitchTests {

    private static final String TENANT = "AGSW_TENANT"
    private static final String EDITOR_ID = "TEST_CUSTOMER_USER"
    private static final String EDITOR_USERNAME = "test.customer"
    private static final Timestamp FROM_DATE = Timestamp.valueOf("2026-04-21 00:00:00")

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "agent-switch")
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: TENANT, description: "Switch Co",
                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID]).createOrUpdate()
        ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userGroupId: TENANT, userId: EDITOR_ID, fromDate: FROM_DATE]).createOrUpdate()
        ec.entity.makeValue(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME).setAll([tenantUserGroupId: TENANT,
                userId: EDITOR_ID, permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID, fromDate: FROM_DATE]).createOrUpdate()
        assertTrue(ec.user.internalLoginUser(EDITOR_USERNAME), "could not log in")
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, TENANT)
        TenantAccessSupport.syncUserContext(ec)
    }

    @AfterAll
    void cleanup() {
        System.clearProperty(AgentSwitch.ENABLED_PROPERTY)
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void relogin() {
        Assumptions.assumeTrue(!System.getenv(AgentSwitch.ENABLED_ENV), "${AgentSwitch.ENABLED_ENV} is set in the environment and wins over the property; unset it to run")
        assertTrue(ec.user.internalLoginUser(EDITOR_USERNAME), "could not log in")
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, TENANT)
        TenantAccessSupport.syncUserContext(ec)
        System.setProperty(AgentSwitch.ENABLED_PROPERTY, "true")
        setFlag("Y")
    }

    private void setFlag(String flag) {
        ec.entity.makeValue("darpan.auth.TenantSetting").setAll([companyUserGroupId: TENANT, agentEnabled: flag]).createOrUpdate()
    }

    @Test
    void "the tenant comes from the resolver, not the raw context key"() {
        ec.user.context.remove("activeTenantUserGroupId")
        assertEquals("ON", AgentSwitch.state(ec))
    }

    @Test
    void "a system-tenant session with no user is switched by its tenant flag"() {
        ec.user.logoutUser()
        try {
            TenantAccessSupport.withSystemTenant(TENANT) { assertEquals("ON", AgentSwitch.state(ec)) }
            setFlag("N")
            TenantAccessSupport.withSystemTenant(TENANT) { assertEquals("AGENT_OFF", AgentSwitch.state(ec)) }
            assertEquals("AGENT_OFF", AgentSwitch.state(ec), "no user and no system tenant: off")
        } finally {
            assertTrue(ec.user.internalLoginUser(EDITOR_USERNAME))
        }
    }

    @Test
    void "a conf default-property reaches the switch the way prod sets it"() {
        // Moqui's startup does System.setProperty for each <default-property>; that is the whole mechanism.
        System.setProperty(AgentSwitch.ENABLED_PROPERTY, "false")
        assertEquals("AGENT_OFF", AgentSwitch.state(ec))
        System.setProperty(AgentSwitch.ENABLED_PROPERTY, "true")
        assertEquals("ON", AgentSwitch.state(ec))
    }

    @Test
    void "agent is off unless both the property and the tenant flag are on"() {
        Map<String, String> expected = [
                "true|Y": "ON", "true|N": "AGENT_OFF", "false|Y": "AGENT_OFF", "false|N": "AGENT_OFF"]
        expected.each { String combo, String state ->
            def (String prop, String flag) = combo.split("\\|") as List<String>
            System.setProperty(AgentSwitch.ENABLED_PROPERTY, prop)
            ec.entity.makeValue("darpan.auth.TenantSetting").setAll([companyUserGroupId: TENANT, agentEnabled: flag]).createOrUpdate()
            assertEquals(state, AgentSwitch.state(ec), "property=${prop} tenantFlag=${flag}")
        }
    }

    @Test
    void "agent is off with no property and no tenant row"() {
        System.clearProperty(AgentSwitch.ENABLED_PROPERTY)
        assertEquals("AGENT_OFF", AgentSwitch.state(ec))
    }
}
