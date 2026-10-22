package darpan.facade.settings

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-108: the tenant-level streaks chat space, set through save#TenantSettings.
 *
 * <p>Both settings on that service are now optional, so the tests that matter most are the ones
 * that save one and check the other survived — a timezone save must not wipe the streaks space,
 * and a streaks save must not reset the timezone to the entity default.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantStreaksChatSpaceSmokeTests {
    private static final String TEST_USER_ID = "TEST_CUSTOMER_USER"
    private static final String TENANT = "KREWE"
    private static final String OTHER_TENANT = "GORJANA"
    private static final String OTHER_SPACE = "OTHER_TENANT_SPACE"
    private static final String WEBHOOK = "https://chat.googleapis.com/v1/spaces/AAAA1234ZZZZ/messages?key=k&token=t"
    private static final Timestamp TEST_FROM_DATE = Timestamp.valueOf("2026-04-23 00:00:00")

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "tenant-streaks-chat-space-smoke")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        seedTenant(OTHER_TENANT, "Gorjana")
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void resetTenantScope() {
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ec.message.clearErrors()
        ec.entity.find("darpan.auth.TenantSetting").condition("companyUserGroupId", TENANT).disableAuthz().deleteAll()
        ec.entity.makeValue("darpan.reconciliation.TenantChatSpace").setAll([
                chatSpaceId       : OTHER_SPACE,
                companyUserGroupId: OTHER_TENANT,
                spaceName         : "Other tenant space",
                webhookUrl        : WEBHOOK,
                isActive          : "Y",
        ]).createOrUpdate()
    }

    @Test
    void savesTheStreaksSpaceAndReadsItBack() {
        String spaceId = createSpace("Streaks", true)

        def saved = callFacade("facade.SettingsFacadeServices.save#TenantSettings", [streaksChatSpaceId: spaceId])
        assertTrue(saved.ok as boolean, saved.errors?.toString())
        assertEquals(spaceId, saved.tenantSettings.streaksChatSpaceId)

        def read = callFacade("facade.SettingsFacadeServices.get#TenantSettings", [:])
        assertEquals(spaceId, read.tenantSettings.streaksChatSpaceId)
        assertEquals("UTC", read.tenantSettings.timeZone, "a streaks-only first save must leave the default timezone")
    }

    @Test
    void eachSettingSurvivesASaveOfTheOther() {
        String spaceId = createSpace("Streaks", true)
        assertTrue(callFacade("facade.SettingsFacadeServices.save#TenantSettings",
                [timeZone: "America/Los_Angeles"]).ok as boolean)
        assertTrue(callFacade("facade.SettingsFacadeServices.save#TenantSettings",
                [streaksChatSpaceId: spaceId]).ok as boolean)

        def afterSpaceSave = callFacade("facade.SettingsFacadeServices.get#TenantSettings", [:])
        assertEquals("America/Los_Angeles", afterSpaceSave.tenantSettings.timeZone)

        assertTrue(callFacade("facade.SettingsFacadeServices.save#TenantSettings",
                [timeZone: "Europe/Paris"]).ok as boolean)
        def afterZoneSave = callFacade("facade.SettingsFacadeServices.get#TenantSettings", [:])
        assertEquals(spaceId, afterZoneSave.tenantSettings.streaksChatSpaceId)
        assertEquals("Europe/Paris", afterZoneSave.tenantSettings.timeZone)
    }

    @Test
    void anEmptyValueClearsTheStreaksSpace() {
        String spaceId = createSpace("Streaks", true)
        assertTrue(callFacade("facade.SettingsFacadeServices.save#TenantSettings", [streaksChatSpaceId: spaceId]).ok as boolean)

        def cleared = callFacade("facade.SettingsFacadeServices.save#TenantSettings", [streaksChatSpaceId: ""])
        assertTrue(cleared.ok as boolean, cleared.errors?.toString())
        assertNull(cleared.tenantSettings.streaksChatSpaceId)
        assertNull(storedSetting()?.streaksChatSpaceId, "clearing must null the stored column, not just the response")
    }

    @Test
    void refusesAnInactiveSpaceAndReadsADeactivatedOneAsUnset() {
        String inactiveId = createSpace("Paused", false)
        def refused = callFacade("facade.SettingsFacadeServices.save#TenantSettings", [streaksChatSpaceId: inactiveId])
        assertFalse(refused.ok as boolean)
        assertTrue((refused.errors ?: []).join(" ").contains("inactive"))
        ec.message.clearErrors()

        String spaceId = createSpace("Streaks", true)
        assertTrue(callFacade("facade.SettingsFacadeServices.save#TenantSettings", [streaksChatSpaceId: spaceId]).ok as boolean)
        ec.entity.find("darpan.reconciliation.TenantChatSpace").condition("chatSpaceId", spaceId).disableAuthz()
                .updateAll([isActive: "N"])

        def read = callFacade("facade.SettingsFacadeServices.get#TenantSettings", [:])
        assertNull(read.tenantSettings.streaksChatSpaceId)
    }

    @Test
    void refusesAnotherTenantsSpaceAndStoresNothing() {
        // Positive control first: the caller can set their OWN space, so a refusal below is containment,
        // not a setup problem refusing everything.
        String ownId = createSpace("Own", true)
        assertTrue(callFacade("facade.SettingsFacadeServices.save#TenantSettings", [streaksChatSpaceId: ownId]).ok as boolean)

        def refused = callFacade("facade.SettingsFacadeServices.save#TenantSettings", [streaksChatSpaceId: OTHER_SPACE])
        assertFalse(refused.ok as boolean)
        assertTrue((refused.errors ?: []).join(" ").contains("not found"))
        assertEquals(ownId, storedSetting()?.streaksChatSpaceId)
    }

    @Test
    void theStreaksSpaceIsInUseAndCannotBeDeleted() {
        String spaceId = createSpace("Streaks", true)
        assertTrue(callFacade("facade.SettingsFacadeServices.save#TenantSettings", [streaksChatSpaceId: spaceId]).ok as boolean)

        def list = callFacade("facade.SettingsFacadeServices.list#TenantChatSpaces", [:])
        assertTrue(list.chatSpaces.any { it.chatSpaceId == spaceId && it.inUse == true })

        def del = callFacade("facade.SettingsFacadeServices.delete#TenantChatSpace", [chatSpaceId: spaceId])
        assertFalse(del.ok as boolean)
        assertTrue((del.errors ?: []).join(" ").contains("in use"))
    }

    @Test
    void savingNothingIsAnError() {
        def result = callFacade("facade.SettingsFacadeServices.save#TenantSettings", [:])
        assertFalse(result.ok as boolean)
        assertTrue((result.errors ?: []).join(" ").contains("No tenant setting"))
    }

    private String createSpace(String name, boolean active) {
        def save = callFacade("facade.SettingsFacadeServices.save#TenantChatSpace",
                [spaceName: "${name} ${System.nanoTime()}".toString(), webhookUrl: WEBHOOK, isActive: active])
        assertTrue(save.ok as boolean, save.errors?.toString())
        return save.chatSpace.chatSpaceId as String
    }

    private def storedSetting() {
        return ec.entity.find("darpan.auth.TenantSetting").condition("companyUserGroupId", TENANT)
                .disableAuthz().useCache(false).one()
    }

    private Map<String, Object> callFacade(String serviceName, Map<String, Object> parameters) {
        return (Map<String, Object>) ec.service.sync().name(serviceName).parameters(parameters).disableAuthz().call()
    }

    private void seedTenant(String tenantId, String label) {
        ec.entity.makeValue("moqui.security.UserGroup").setAll([
                userGroupId    : tenantId, description: label,
                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID,
        ]).createOrUpdate()
        ec.entity.makeValue("moqui.security.UserGroupMember").setAll([
                userGroupId: tenantId, userId: TEST_USER_ID, fromDate: TEST_FROM_DATE,
        ]).createOrUpdate()
    }
}
