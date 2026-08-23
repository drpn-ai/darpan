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
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Cross-tenant containment for the chat-space facade.
 *
 * <p>These services pin their reads with
 * {@code .condition('chatSpaceId', id).condition('companyUserGroupId', tenantId).one()}. That
 * expresses the intent, but Moqui's {@code one()} discards non-primary-key conditions when the full
 * PK is supplied (proven 2026-08-23: identical conditions give {@code list().size() == 0} while
 * {@code one()} returns a foreign row). So the pin may never have applied, and these tests exist to
 * settle it with the real services rather than by reading the code.</p>
 *
 * <p>The dangerous one is {@code save#TenantChatSpace}: it looks the row up by id, then stores it
 * back with the CALLER's {@code companyUserGroupId} — so an ineffective pin does not merely leak a
 * webhook, it re-parents another tenant's chat space into the caller's tenant.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChatSpaceCrossTenantSmokeTests {
    private static final String TEST_USER_ID = "TEST_CUSTOMER_USER"
    private static final String VICTIM = "KREWE"
    private static final String ATTACKER = "GORJANA"
    private static final String VICTIM_SPACE = "VICTIM_SPACE"
    private static final String VICTIM_WEBHOOK =
            "https://chat.googleapis.com/v1/spaces/VICTIM/messages?key=victim-key&token=victim-token"
    private static final Timestamp TEST_FROM_DATE = Timestamp.valueOf("2026-04-23 00:00:00")

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "chat-space-cross-tenant-smoke")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        seedTenant(ATTACKER, "Gorjana")

    }

    /**
     * Recreated per test. One of these services genuinely deletes the victim's row, and a shared
     * fixture let that deletion silently satisfy the assertions in every test that ran afterwards.
     */
    private void seedVictimSpace() {
        ec.entity.makeValue("darpan.reconciliation.TenantChatSpace").setAll([
                chatSpaceId         : VICTIM_SPACE,
                companyUserGroupId  : VICTIM,
                spaceName           : "Victim Ops",
                googleChatWebhookUrl: VICTIM_WEBHOOK,
                webhookUrl          : VICTIM_WEBHOOK,
                isActive            : "Y",
        ]).createOrUpdate()
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void actAsAttacker() {
        ec.message.clearErrors()
        // Every test below runs as a legitimate editor of ATTACKER, never of VICTIM.
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, ATTACKER)
        seedVictimSpace()
    }

    @Test
    void positiveControlTheAttackerCanSaveTheirOwnSpace() {
        // Without this, every "refused" result below is worthless: a setup problem (no write access,
        // no active tenant) would refuse EVERYTHING and read as containment.
        Map<String, Object> result = (Map<String, Object>) ec.service.sync()
                .name("facade.SettingsFacadeServices.save#TenantChatSpace")
                .parameters([spaceName: "Attacker Own",
                             webhookUrl: "https://chat.googleapis.com/v1/spaces/ATK/messages?key=k&token=t",
                             isActive: true])
                .disableAuthz().call()
        assertTrue(result.ok as boolean,
                "setup is broken — the caller cannot even save their OWN space: ${result.errors}")
    }

    @Test
    void savingAnotherTenantsChatSpaceIsRefusedAndDoesNotRePARENTIt() {
        Map<String, Object> result = (Map<String, Object>) ec.service.sync()
                .name("facade.SettingsFacadeServices.save#TenantChatSpace")
                .parameters([chatSpaceId: VICTIM_SPACE, spaceName: "Stolen", isActive: true])
                .disableAuthz().call()

        assertFalse(result.ok as boolean,
                "a tenant must not be able to save a chat space owned by another tenant")

        def row = readSpace(VICTIM_SPACE)
        assertNotNull(row, "the victim's space must still exist")
        assertEquals(VICTIM, row.companyUserGroupId as String,
                "the victim's space was re-parented into the caller's tenant")
        assertEquals("Victim Ops", row.spaceName as String, "the victim's space was renamed")
    }

    @Test
    void deletingAnotherTenantsChatSpaceIsRefused() {
        ec.message.clearErrors()
        ec.service.sync()
                .name("facade.SettingsFacadeServices.delete#TenantChatSpace")
                .parameters([chatSpaceId: VICTIM_SPACE])
                .disableAuthz().call()

        assertNotNull(readSpace(VICTIM_SPACE), "another tenant's chat space was deleted")
    }

    @Test
    void anotherTenantsSpaceCannotBecomeThisUsersNotificationDefault() {
        Map<String, Object> result = (Map<String, Object>) ec.service.sync()
                .name("facade.SettingsFacadeServices.save#UserNotificationDefault")
                .parameters([chatSpaceId: VICTIM_SPACE])
                .disableAuthz().call()

        assertFalse(result.ok as boolean,
                "a foreign chat space must not be selectable as a notification default")
    }

    @Test
    void theListingIsAlreadyTenantScoped() {
        // Control case. list#TenantChatSpaces uses .list(), which DOES honour the tenant condition —
        // so if this ever fails the problem is broader than the primary-key path.
        Map<String, Object> result = (Map<String, Object>) ec.service.sync()
                .name("facade.SettingsFacadeServices.list#TenantChatSpaces")
                .parameters([:]).disableAuthz().call()
        List<Map<String, Object>> spaces = (List<Map<String, Object>>) (result.chatSpaces ?: [])
        assertTrue(spaces.every { it.chatSpaceId != VICTIM_SPACE },
                "the victim's space appeared in the attacker's listing: ${spaces*.chatSpaceId}")
    }

    private def readSpace(String chatSpaceId) {
        return ec.entity.find("darpan.reconciliation.TenantChatSpace")
                .condition("chatSpaceId", chatSpaceId).disableAuthz().useCache(false).one()
    }

    private void seedTenant(String tenantId, String label) {
        ec.entity.makeValue("moqui.security.UserGroup").setAll([
                userGroupId    : tenantId, description: label,
                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID,
        ]).createOrUpdate()
        ec.entity.makeValue("moqui.security.UserGroupMember").setAll([
                userGroupId: tenantId, userId: TEST_USER_ID, fromDate: TEST_FROM_DATE,
        ]).createOrUpdate()
        // Write access comes from the tenant-scoped permission-group membership, NOT from adding the
        // user to the editor UserGroup directly. Getting this wrong makes every service refuse for
        // "view access only", which reads exactly like tenant containment — see the positive control.
        ec.entity.makeValue(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME).setAll([
                tenantUserGroupId    : tenantId,
                userId               : TEST_USER_ID,
                permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID,
                fromDate             : TEST_FROM_DATE,
        ]).createOrUpdate()
    }
}
