package darpan.facade.auth

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.Moqui
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-209. A user asks for the CLI in their active tenant; a Darpan admin approves (one grant) or declines (a note the
 * user sees). Synthetic tenants T1/T2; VIEWER is view-only in both; STAFF is a Darpan super admin.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CliAccessRequestSmokeTests {
    private static final String T1 = "CAR_T1"
    private static final String T2 = "CAR_T2"
    private static final String VIEWER = "CAR_VIEWER"
    private static final String STAFF = "CAR_STAFF"
    private static final Timestamp FROM = Timestamp.valueOf("2026-04-21 00:00:00")

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "cli-access-request")
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        asSystem {
            [T1, T2].each { String t ->
                upsert("moqui.security.UserGroup", [userGroupId: t, description: "${t} Co".toString(), groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
            }
            [VIEWER, STAFF].each { String u ->
                upsert("moqui.security.UserAccount", [userId: u, username: u.toLowerCase(), userFullName: u, emailAddress: "${u.toLowerCase()}@example.com".toString(),
                        disabled: "N", requirePasswordChange: "N"])
            }
            upsert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_SUPER_ADMIN", userId: STAFF, fromDate: FROM])
            upsert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: VIEWER, fromDate: FROM])
            [T1, T2].each { String t -> member(t, VIEWER) }
        }
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void reset() {
        asSystem {
            [CliAccessRequests.REQUEST, CliAccess.EVENT, CliAccess.GRANT].each { ec.entity.find(it).deleteAll() }
            [T1, T2].each { String t -> member(t, VIEWER) }
        }
    }

    @Test
    void askingTwiceReturnsTheOpenRequest() {
        Map first = asUser(VIEWER, T1) { CliAccessRequests.request(it, "  run audits  ") }.result as Map
        Map second = asUser(VIEWER, T1) { CliAccessRequests.request(it, "nightly audits") }.result as Map
        assertEquals(first.requestId, second.requestId)
        assertEquals("nightly audits", row(first.requestId as String).reason)
        assertEquals(1L, count())
    }

    @Test
    void aRequestIsForTheActiveTenantOnly() {
        Map r = asUser(VIEWER, T2) { CliAccessRequests.request(it, null) }.result as Map
        assertEquals(T2, row(r.requestId as String).companyUserGroupId)
        assertNull(asUser(VIEWER, T1) { CliAccessRequests.myAccess(it) }.result.request, "T1 sees no request")
        assertEquals(CliAccessRequests.PENDING, asUser(VIEWER, T2) { CliAccessRequests.myAccess(it) }.result.request.statusId)
    }

    @Test
    void aDarpanAdminOrALiveUserIsRefused() {
        Map staff = asUser(STAFF, T1) { CliAccessRequests.request(it, null) }
        assertNull(staff.result)
        assertTrue((staff.errors as List).any { it.contains("without a grant") }, "${staff.errors}")
        approve(asUser(VIEWER, T1) { CliAccessRequests.request(it, null) }.result.requestId as String, 7)
        Map live = asUser(VIEWER, T1) { CliAccessRequests.request(it, null) }
        assertNull(live.result)
        assertTrue((live.errors as List).any { it.contains("already on") }, "${live.errors}")
        assertEquals(CliAccess.LIVE, asUser(VIEWER, T1) { CliAccessRequests.myAccess(it) }.result.state)
    }

    @Test
    void approveMakesALiveGrantAndLinksIt() {
        String id = asUser(VIEWER, T1) { CliAccessRequests.request(it, "audits") }.result.requestId as String
        Map done = approve(id, 7)
        assertEquals(CliAccessRequests.APPROVED, done.request.statusId)
        assertEquals(done.grant.grantId, done.request.grantId)
        assertNotNull(CliAccess.liveGrant(ec, VIEWER, T1))
        long days = Math.round(((done.grant.thruDate as Timestamp).time - System.currentTimeMillis()) / 86_400_000d)
        assertEquals(7L, days)
        assertNull(CliAccess.liveGrant(ec, VIEWER, T2), "approving in T1 says nothing about T2")
    }

    @Test
    void approveTwiceMakesOneGrant() {
        String id = asUser(VIEWER, T1) { CliAccessRequests.request(it, null) }.result.requestId as String
        approve(id, null)
        Map again = asUser(STAFF, T1) { CliAccessRequests.adminApprove(it, id, null, null) }
        assertNull(again.result)
        assertTrue((again.errors as List).any { it.contains("already approved") }, "${again.errors}")
        assertEquals(1L, asSystem { ec.entity.find(CliAccess.GRANT).useCache(false).count() })
    }

    @Test
    void approveAfterTheMembershipIsGoneIsRefused() {
        String id = asUser(VIEWER, T1) { CliAccessRequests.request(it, null) }.result.requestId as String
        asSystem {
            ec.entity.find(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME).condition("userId", VIEWER)
                    .condition("tenantUserGroupId", T1).deleteAll()
        }
        Map refused = asUser(STAFF, T2) { CliAccessRequests.adminApprove(it, id, null, 7) }
        assertNull(refused.result)
        assertTrue((refused.errors as List).any { it.contains("not a member") }, "${refused.errors}")
        assertEquals(CliAccessRequests.PENDING, row(id).statusId)
        assertEquals(0L, asSystem { ec.entity.find(CliAccess.GRANT).useCache(false).count() })
    }

    @Test
    void declineKeepsTheNoteTheUserSeesAndTheUserMayAskAgain() {
        String id = asUser(VIEWER, T1) { CliAccessRequests.request(it, null) }.result.requestId as String
        Map declined = asUser(STAFF, T1) { CliAccessRequests.adminDecline(it, id, "Use the app for now") }.result as Map
        assertEquals(CliAccessRequests.DECLINED, declined.statusId)
        Map seen = asUser(VIEWER, T1) { CliAccessRequests.myAccess(it) }.result.request as Map
        assertEquals(["DECLINED", "Use the app for now"], [seen.statusId, seen.decisionNote])
        Map again = asUser(VIEWER, T1) { CliAccessRequests.request(it, "please") }.result as Map
        assertNotEquals(id, again.requestId, "asking again after a decline opens a new request")
        assertEquals([again.requestId], asUser(STAFF, T1) { CliAccessRequests.adminList(it, null, VIEWER, null) }.result*.requestId)
    }

    @Test
    void aLongReasonAndNoteAreKeptWhole() {
        String reason = "r" * 400, note = "n" * 400
        Map filed = asUser(VIEWER, T1) { CliAccessRequests.request(it, reason) }
        assertNotNull(filed.result, "a 400-character reason is accepted: ${filed.errors}")
        String id = filed.result.requestId as String
        assertEquals(400, (row(id).reason as String).length())
        assertNotNull(asUser(STAFF, T1) { CliAccessRequests.adminDecline(it, id, note) }.result)
        assertEquals(400, (row(id).decisionNote as String).length())
        assertNull(asUser(VIEWER, T1) { CliAccessRequests.request(it, "r" * 501) }.result, "over 500 is refused")
    }

    /** Review I2: approve#CliLogin is allowed with a live grant in any tenant, so the Approve CLI page needs that, not the active tenant's state. */
    @Test
    void canUseCliFollowsAnyTenantNotTheActiveOne() {
        assertFalse(asUser(VIEWER, T1) { CliAccessRequests.myAccess(it) }.result.canUseCli as boolean)
        approve(asUser(VIEWER, T2) { CliAccessRequests.request(it, null) }.result.requestId as String, 7)
        Map inT1 = asUser(VIEWER, T1) { CliAccessRequests.myAccess(it) }.result as Map
        assertEquals([CliAccess.NOT_GRANTED, true], [inT1.state, inT1.canUseCli])
        assertTrue(asUser(STAFF, T1) { CliAccessRequests.myAccess(it) }.result.canUseCli as boolean)
    }

    // ------------------------------------------------------------------ helpers

    private Map approve(String requestId, Integer days) {
        Map h = asUser(STAFF, T1) { CliAccessRequests.adminApprove(it, requestId, null, days) }
        assertNotNull(h.result, "approve: ${h.errors}")
        return h.result as Map
    }

    /** Runs work signed in as the user with the tenant requested, on its own thread (as a web call). */
    private Map asUser(String userId, String tenant, Closure work) {
        Map holder = [:]
        Thread worker = new Thread({
            ExecutionContext tec = Moqui.getExecutionContextFactory().getExecutionContext()
            try {
                if (!tec.user.internalLoginUser(userId.toLowerCase())) throw new IllegalStateException("could not log in ${userId}")
                boolean was = tec.artifactExecution.disableAuthz()
                try {
                    holder.result = TenantAccessSupport.withRequestedTenant(tenant) { work.call(tec) }
                } finally { if (!was) tec.artifactExecution.enableAuthz() }
                holder.errors = new ArrayList<String>(tec.message.errors)
            } catch (Throwable t) {
                holder.failure = t
            } finally {
                tec.destroy()
            }
        } as Runnable, "car-${userId}")
        worker.start()
        worker.join()
        if (holder.failure) throw (Throwable) holder.failure
        return holder
    }

    private void member(String tenant, String user) {
        upsert("moqui.security.UserGroupMember", [userGroupId: tenant, userId: user, fromDate: FROM])
        upsert(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [tenantUserGroupId: tenant, userId: user,
                permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID, fromDate: FROM])
    }

    private Map row(String requestId) {
        asSystem { ec.entity.find(CliAccessRequests.REQUEST).condition("requestId", requestId).useCache(false).one()?.getMap() }
    }

    private long count() { asSystem { ec.entity.find(CliAccessRequests.REQUEST).useCache(false).count() } }

    private <T> T asSystem(Closure<T> work) {
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try { return work.call() } finally { if (!wasDisabled) ec.artifactExecution.enableAuthz() }
    }

    private void upsert(String entityName, Map fields) { ec.entity.makeValue(entityName).setAll(fields).createOrUpdate() }
}
