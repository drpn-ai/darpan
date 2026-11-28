package darpan.security

import darpan.reconciliation.support.SharedMoqui

import darpan.facade.common.TenantAccessSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-277 — attacks on the DARPAN_ACTIVE_COMPANY_SCOPE filter mechanism itself, and on tenant switching.
 *
 * <p>Each test asserts the SECURE outcome: red = a confirmed hole, green = the boundary held.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SharedMoqui
class TenantFilterAdversarialTests {

    static final String A = TenantAttackHarness.TENANT_A
    static final String B = TenantAttackHarness.TENANT_B
    static final String REC_A = "ADV_REC_A"
    static final String REC_B = "ADV_REC_B"

    TenantAttackHarness h

    @BeforeAll
    void setup() {
        h = new TenantAttackHarness("tenant-filter-adversarial")
        [(REC_A): A, (REC_B): B].each { String id, String tenant ->
            h.insert("darpan.reconciliation.Reconciliation", [reconciliationId: id, reconciliationName: "orig ${id}".toString(),
                    companyUserGroupId: tenant, isActive: "Y", isArchived: "N"])
        }
        h.insert("darpan.agent.AgentConversation", [conversationId: "ADV_CONV_A", companyUserGroupId: A, userId: TenantAttackHarness.ATTACKER_ID])
        h.insert("darpan.agent.AgentConversation", [conversationId: "ADV_CONV_B", companyUserGroupId: B, userId: TenantAttackHarness.ATTACKER_ID])
        h.insert("darpan.agent.AgentTurn", [turnId: "ADV_TURN_A", conversationId: "ADV_CONV_A", companyUserGroupId: A])
        h.insert("darpan.agent.AgentTurn", [turnId: "ADV_TURN_B", conversationId: "ADV_CONV_B", companyUserGroupId: B])
    }

    @AfterAll
    void cleanup() { h.close() }

    @BeforeEach
    void attacker() { h.asAttacker() }

    // ---- control -----------------------------------------------------------------------------

    @Test
    void controlFilterHidesForeignRowOnRead() {
        List seen = h.underFacadeGrant {
            h.ec.entity.find("darpan.reconciliation.Reconciliation").condition("reconciliationId", "in", [REC_A, REC_B])
                    .useCache(false).list()*.reconciliationId
        } as List
        assertEquals([REC_A], seen)
    }

    // ---- 1. no tenant in the user context: the filter must fail closed (DAR-BE-282) ----------

    /**
     * Moqui builds the filter map with excludeNulls=true, so [companyUserGroupId: activeTenantUserGroupId] dropped
     * the condition when the user context did not carry activeTenantUserGroupId and the find returned every
     * tenant's rows; the map now falls back to an impossible value. Only TenantAccessSupport.syncUserContext (webroot before-request / after-login) sets the
     * key; any facade-granted find on a context it did not run on (non-web login, a worker thread) is unfiltered.
     */
    @Test
    void filterFailsClosedWhenUserContextHasNoActiveTenant() {
        Object saved = h.ec.user.context.remove("activeTenantUserGroupId")
        try {
            List seen = h.underFacadeGrant {
                h.ec.entity.find("darpan.reconciliation.Reconciliation").condition("reconciliationId", "in", [REC_A, REC_B])
                        .useCache(false).list()*.reconciliationId
            } as List
            assertFalse(seen.contains(REC_B), "facade-granted find with no tenant in user context saw tenant B: ${seen}")
        } finally {
            h.ec.user.context.activeTenantUserGroupId = saved
        }
    }

    /** Same, through a real facade service: list#Reconciliations names the tenant explicitly too. */
    @Test
    void listReconciliationsHoldsWhenUserContextHasNoActiveTenant() {
        Object saved = h.ec.user.context.remove("activeTenantUserGroupId")
        try {
            Map out = h.call("facade.ReconciliationFacadeServices.list#Reconciliations")
            assertNull(out.thrown, "list#Reconciliations threw instead of answering: ${out}")
            assertEquals([], out._errors, "list#Reconciliations reported errors: ${out}")
            List ids = (out.reconciliations as List ?: [])*.reconciliationId
            assertFalse(ids.contains(REC_B), "list#Reconciliations leaked tenant B: ${ids}")
        } finally {
            h.ec.user.context.activeTenantUserGroupId = saved
        }
    }

    // ---- 2. a find-based bulk delete is filtered (writes by full PK are not: see DAR-BE-277) ---

    @Test
    void findDeleteAllUnderFacadeGrantCannotDeleteForeignRow() {
        h.insert("darpan.reconciliation.Reconciliation", [reconciliationId: "ADV_REC_B_DEL2", reconciliationName: "victim",
                companyUserGroupId: B, isActive: "Y", isArchived: "N"])
        // Positive control: A's own row, named in the same delete under the same grant, IS deleted.
        h.insert("darpan.reconciliation.Reconciliation", [reconciliationId: "ADV_REC_A_DEL2", reconciliationName: "own",
                companyUserGroupId: A, isActive: "Y", isArchived: "N"])
        long deleted = h.underFacadeGrant {
            h.ec.entity.find("darpan.reconciliation.Reconciliation").condition("reconciliationId", "in", ["ADV_REC_A_DEL2", "ADV_REC_B_DEL2"])
                    .deleteAll()
        } as long
        assertEquals(1L, deleted, "the filtered delete must remove exactly A's own row")
        assertNull(h.raw("darpan.reconciliation.Reconciliation", [reconciliationId: "ADV_REC_A_DEL2"]), "positive control: A's own row was not deleted")
        assertNotNull(h.raw("darpan.reconciliation.Reconciliation", [reconciliationId: "ADV_REC_B_DEL2"]))
    }

    // ---- 3. view-entity whose members are filtered -------------------------------------------

    @Test
    void viewEntityOverFilteredMembersHidesForeignRows() {
        // The CNV member's companyUserGroupId is not aliased, so the filter cannot be applied and Moqui refuses the
        // query (fail-closed). Either refusal or only tenant A's turn is secure.
        List seen
        try {
            seen = h.underFacadeGrant {
                h.ec.entity.find("darpan.agent.AgentTurnOfUser").condition("turnId", "in", ["ADV_TURN_A", "ADV_TURN_B"])
                        .useCache(false).list()*.turnId
            } as List
        } catch (org.moqui.context.ArtifactAuthorizationException refused) {
            assertTrue((refused.message ?: "").contains("authorized data filter"), "refused for another reason: ${refused}")
            return
        }
        assertEquals(["ADV_TURN_A"], seen, "the view must show exactly tenant A's turn")
    }

    @Test
    void viewEntityCountHidesForeignRows() {
        long n
        try {
            n = h.underFacadeGrant {
                h.ec.entity.find("darpan.agent.AgentTurnOfUser").condition("turnId", "in", ["ADV_TURN_A", "ADV_TURN_B"]).count()
            } as long
        } catch (Exception refused) {
            // count() wraps the same fail-closed ArtifactAuthorizationException in an EntityException.
            assertTrue(refused.toString().contains("authorized data filter") || refused.cause?.toString()?.contains("authorized data filter"),
                    "unexpected failure: ${refused}")
            return
        }
        assertEquals(1L, n, "the view count must be exactly tenant A's turn")
    }

    // ---- 4. tenant switching ------------------------------------------------------------------

    @Test
    void saveActiveTenantRefusesATenantTheUserIsNotIn() {
        Map out = h.call("facade.AuthFacadeServices.save#ActiveTenant", [activeTenantUserGroupId: B])
        assertNull(out.thrown, "save#ActiveTenant threw instead of refusing: ${out}")
        // TenantAccessSupport.ACTIVE_TENANT_UNAVAILABLE_MESSAGE
        assertTrue((out._errors as List).contains("Requested tenant is not available for current user."),
                "save#ActiveTenant must refuse tenant B as not one of the user's: ${out}")
        h.actAs(TenantAttackHarness.ATTACKER, null)
        assertEquals(A, h.ec.user.context.activeTenantUserGroupId, "active tenant moved to B")
    }

    @Test
    void storedPreferenceForForeignTenantIsIgnored() {
        // A stale/forged preference row (e.g. written before a membership was removed) must not select tenant B.
        h.ec.user.setPreference(TenantAttackHarness.PREF_KEY, B)
        TenantAccessSupport.syncUserContext(h.ec)
        assertEquals(A, h.ec.user.context.activeTenantUserGroupId)
    }

    @Test
    void requestedTenantForForeignTenantIsRefused() {
        // Positive control: the attacker's own tenant, requested the same way, resolves.
        assertEquals(A, TenantAccessSupport.withRequestedTenant(A) { TenantAccessSupport.currentActiveTenantUserGroupId(h.ec) })
        def refused = assertThrows(org.moqui.context.ArtifactAuthorizationException) {
            TenantAccessSupport.withRequestedTenant(B) { TenantAccessSupport.currentActiveTenantUserGroupId(h.ec) }
        }
        assertEquals("Tenant ${B} is not one of your tenants".toString(), refused.message)
    }

    @Test
    void callerTenantForForeignTenantIsRefused() {
        // Positive control: the attacker's own tenant runs the body, in that tenant.
        assertEquals(A, TenantAccessSupport.withCallerTenant(h.ec, A) { TenantAccessSupport.currentActiveTenantUserGroupId(h.ec) })
        def refused = assertThrows(org.moqui.context.ArtifactAuthorizationException) {
            TenantAccessSupport.withCallerTenant(h.ec, B) { "ran" }
        }
        assertEquals("Tenant ${B} is not one of your tenants".toString(), refused.message)
    }

    @Test
    void expiredMembershipDoesNotGrantTenant() {
        String uid = "ADV_EXPIRED"
        h.insert("moqui.security.UserAccount", [userId: uid, username: "adv.expired", userFullName: "x", currentPassword: "", disabled: "N"])
        h.insert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: uid, fromDate: TenantAttackHarness.FROM_DATE])
        h.insert("moqui.security.UserGroupMember", [userGroupId: B, userId: uid, fromDate: TenantAttackHarness.FROM_DATE,
                thruDate: java.sql.Timestamp.valueOf("2026-05-01 00:00:00")])
        h.insert(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [tenantUserGroupId: B, userId: uid,
                permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID, fromDate: TenantAttackHarness.FROM_DATE,
                thruDate: java.sql.Timestamp.valueOf("2026-05-01 00:00:00")])
        h.actAs("adv.expired", B)
        assertEquals(TenantAccessSupport.NO_ACTIVE_TENANT_FILTER_VALUE, h.ec.user.context.activeTenantUserGroupId,
                "an expired membership must leave no active tenant (B, or any other)")
    }
}
