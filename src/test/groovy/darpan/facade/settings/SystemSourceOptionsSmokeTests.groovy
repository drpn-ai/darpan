package darpan.facade.settings

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-052 / Aditi 2026-11-10: "deselect any REST endpoints for OMS so that they do not show up in drop downs when
 * setting anything up". The JSON schema screens read the system list through list#EnumOptions, which offered every
 * DarpanSystemSource row, the retired OMS REST endpoints included. It now offers what the run pickers offer.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SystemSourceOptionsSmokeTests {
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "system-source-options")
        ["SecuritySeedData.xml", "DarpanSystemSourceTypeSeedData.xml", "DarpanSystemSourceSeedData.xml", "SourceSystemConnectorSeedData.xml"].each {
            ec.entity.makeDataLoader().location("component://darpan/data/${it}").dummyFks(true).load()
        }
        boolean was = ec.artifactExecution.disableAuthz()
        try {
            ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: "SSO_ADMIN", username: "sso.admin", userFullName: "SSO Admin",
                    emailAddress: "sso.admin@example.com", disabled: "N", requirePasswordChange: "N"]).createOrUpdate()
            ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userGroupId: "ADMIN", userId: "SSO_ADMIN",
                    fromDate: Timestamp.valueOf("2026-01-01 00:00:00")]).createOrUpdate()
        } finally { if (!was) ec.artifactExecution.enableAuthz() }
        assertTrue(ec.user.internalLoginUser("sso.admin"))
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @Test
    void theSystemListOffersNoRetiredOmsRestEndpoint() {
        Map out = ec.service.sync().name("facade.SettingsFacadeServices.list#EnumOptions").parameters([enumTypeId: "DarpanSystemSource"])
                .disableAuthz().call()
        assertFalse(ec.message.hasError(), "${ec.message.errors}")
        List<String> ids = (out.options as List<Map>)*.enumId
        assertTrue(ids.containsAll(["OMS", "SHOPIFY", "NETSUITE", "OMS_GQL", "OMS_RETURNS_GQL", "OMS_ORDER_ITEMS_GQL"]), "${ids}")
        List<String> rest = ["OMS_RETURNS", "OMS_TRANSFER_ORDERS", "OMS_RECON_ORDERS", "OMS_ORDER_ITEMS", "OMS_ORDER_LINE_UNITS"]
        assertEquals([], ids.findAll { it in rest }, "retired OMS REST endpoints are still offered")
    }
}
