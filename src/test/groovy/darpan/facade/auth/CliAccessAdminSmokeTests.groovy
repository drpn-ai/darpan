package darpan.facade.auth

import darpan.admin.AdminAccessSupport
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.Moqui
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-195. Only a Darpan admin turns the CLI on, changes, pauses, unpauses or revokes it; a tenant admin, editor or
 * viewer is refused by the admin fence and again by each service. Synthetic tenants and users.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CliAccessAdminSmokeTests {
    private static final String SVC = "admin.CliAccessAdminServices"
    private static final List<String> SERVICES = ["list#CliAccessGrants", "create#CliAccessGrant", "update#CliAccessGrant",
            "pause#CliAccessGrant", "unpause#CliAccessGrant", "revoke#CliAccessGrant",
            "list#CliAccessRequests", "approve#CliAccessRequest", "decline#CliAccessRequest"]
    private static final String ALPHA = "CAA_ALPHA"
    private static final String TADMIN = "CAA_TADMIN"
    private static final String EDITOR = "CAA_EDITOR"
    private static final String VIEWER = "CAA_VIEWER"
    private static final String OUTSIDER = "CAA_OUTSIDER"
    private static final String SUPER_ID = "CAA_SUPER"
    private static final Timestamp FROM = Timestamp.valueOf("2026-04-21 00:00:00")

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "cli-access-admin")
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        asSystem {
            upsert("moqui.security.UserGroup", [userGroupId: ALPHA, description: "Alpha Co", groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
            [TADMIN, EDITOR, VIEWER, OUTSIDER, SUPER_ID].each { String u ->
                upsert("moqui.security.UserAccount", [userId: u, username: u.toLowerCase(), userFullName: u, emailAddress: "${u.toLowerCase()}@example.com".toString(),
                        disabled: "N", requirePasswordChange: "N"])
            }
            upsert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_SUPER_ADMIN", userId: SUPER_ID, fromDate: FROM])
            [[TADMIN, TenantAccessSupport.DARPAN_TENANT_ADMIN_GROUP_ID], [EDITOR, TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID],
             [VIEWER, TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID]].each { List m ->
                upsert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: m[0], fromDate: FROM])
                upsert("moqui.security.UserGroupMember", [userGroupId: ALPHA, userId: m[0], fromDate: FROM])
                upsert(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [tenantUserGroupId: ALPHA, userId: m[0],
                        permissionUserGroupId: m[1], fromDate: FROM])
            }
        }
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void reset() {
        asSystem { [CliAccess.EVENT, CliAccess.GRANT].each { ec.entity.find(it).deleteAll() } }
    }

    @Test
    void "a tenant admin, editor or viewer is refused by the fence and by the service on every CLI grant service"() {
        String id = adminOk("create#CliAccessGrant", [userId: VIEWER, tenantUserGroupId: ALPHA]).grant.grantId
        Map before = grantRow(id)
        Map params = [grantId: id, requestId: 'NONE', userId: EDITOR, tenantUserGroupId: ALPHA, days: 30]
        [TADMIN, EDITOR, VIEWER].each { String who ->
            SERVICES.each { String s ->
                Map fenced = onThread(who.toLowerCase(), "${SVC}.${s}".toString(), params, false)
                assertNotNull(fenced.authzDenied, "${who} ${s}: the admin fence must refuse")
                Map gated = onThread(who.toLowerCase(), "${SVC}.${s}".toString(), params, true)
                assertTrue((gated.errors as List).contains(AdminAccessSupport.SUPER_ADMIN_REQUIRED_MESSAGE), "${who} ${s}: ${gated.errors}")
            }
        }
        assertEquals(1L, grantCount(), "no grant was created")
        assertEquals(before, grantRow(id), "and the grant is unchanged")
    }

    @Test
    void "a grant needs a member, replaces the old one, and shows the lifetime the role gives"() {
        assertTrue(admin("create#CliAccessGrant", [userId: OUTSIDER, tenantUserGroupId: ALPHA]).hasError as boolean)
        assertEquals(0L, grantCount())
        Map first = adminOk("create#CliAccessGrant", [userId: VIEWER, tenantUserGroupId: ALPHA, days: 7]).grant
        assertEquals([VIEWER, ALPHA, "ACTIVE", CliAccess.LIVE, 7], [first.userId, first.tenantUserGroupId, first.statusId, first.state, first.lifetimeDays])
        Map second = adminOk("create#CliAccessGrant", [userId: VIEWER, tenantUserGroupId: ALPHA]).grant
        assertEquals("REVOKED", grantRow(first.grantId as String).statusId, "one grant per user and tenant")
        assertNull(second.thruDate, "no days and no thruDate: no end")
        assertEquals(7, adminOk("create#CliAccessGrant", [userId: TADMIN, tenantUserGroupId: ALPHA]).grant.lifetimeDays, "DAR-BE-261: a tenant admin no longer than a viewer")
        assertTrue(admin("create#CliAccessGrant", [userId: EDITOR, tenantUserGroupId: ALPHA, days: 0]).hasError as boolean, "days 0 refused")
    }

    @Test
    void "pause, unpause, change and revoke each take effect and log one event by the admin"() {
        String id = adminOk("create#CliAccessGrant", [userId: EDITOR, tenantUserGroupId: ALPHA]).grant.grantId
        assertEquals(CliAccess.PAUSED, adminOk("pause#CliAccessGrant", [grantId: id]).grant.state)
        assertNull(CliAccess.liveGrant(ec, EDITOR, ALPHA), "a paused grant is not live")
        assertEquals(CliAccess.LIVE, adminOk("unpause#CliAccessGrant", [grantId: id, days: 3]).grant.state)
        assertNotNull(adminOk("update#CliAccessGrant", [grantId: id, days: 10]).grant.thruDate)
        assertEquals("REVOKED", adminOk("revoke#CliAccessGrant", [grantId: id]).grant.statusId)
        assertTrue(admin("unpause#CliAccessGrant", [grantId: id]).hasError as boolean, "a revoked grant stays revoked")
        List<Map> evts = asSystem { ec.entity.find(CliAccess.EVENT).condition("grantId", id).orderBy("eventId").useCache(false).list()*.getMap() }
        assertEquals(["GRANT", "PAUSE", "UNPAUSE", "CHANGE", "REVOKE"], evts*.action)
        assertTrue(evts.every { it.byUserId == SUPER_ID })
    }

    @Test
    void "every grant and request decision writes one admin audit row by the admin, and a refused call writes none"() {
        // DAR-BE-265
        String id = adminOk("create#CliAccessGrant", [userId: EDITOR, tenantUserGroupId: ALPHA]).grant.grantId
        adminOk("pause#CliAccessGrant", [grantId: id])
        adminOk("unpause#CliAccessGrant", [grantId: id])
        adminOk("update#CliAccessGrant", [grantId: id, days: 10])
        adminOk("revoke#CliAccessGrant", [grantId: id])
        assertTrue(admin("unpause#CliAccessGrant", [grantId: id]).hasError as boolean)
        assertEquals(["create#CliAccessGrant", "pause#CliAccessGrant", "unpause#CliAccessGrant", "update#CliAccessGrant", "revoke#CliAccessGrant"]
                .collect { "${SVC}.${it}".toString() }, auditRows(id)*.serviceName, "one row per change; the refused unpause adds none")
        assertTrue(auditRows(id).every { it.adminUserId == SUPER_ID && it.targetType == "CliAccessGrant" && it.detailText })

        asSystem {
            ["CAA_REQ_1", "CAA_REQ_2"].each { String r ->
                upsert(CliAccessRequests.REQUEST, [requestId: r, userId: VIEWER, companyUserGroupId: ALPHA, statusId: CliAccessRequests.PENDING, requestedDate: FROM])
            }
        }
        Map approved = adminOk("approve#CliAccessRequest", [requestId: "CAA_REQ_1", days: 7])
        adminOk("decline#CliAccessRequest", [requestId: "CAA_REQ_2", decisionNote: "Not yet"])
        assertTrue(admin("decline#CliAccessRequest", [requestId: "CAA_REQ_2"]).hasError as boolean, "a decided request is refused")
        assertEquals(["${SVC}.approve#CliAccessRequest".toString()], auditRows("CAA_REQ_1")*.serviceName)
        assertTrue((auditRows("CAA_REQ_1")[0].detailText as String).contains(approved.grant.grantId as String), "the row names the grant it made")
        assertEquals(["${SVC}.decline#CliAccessRequest".toString()], auditRows("CAA_REQ_2")*.serviceName)
        assertEquals([], auditRows(approved.grant.grantId as String), "an approval is one row, under the request")
    }

    private List<Map> auditRows(String targetId) {
        asSystem { ec.entity.find("darpan.admin.AdminAuditLog").condition("targetId", targetId).orderBy("adminAuditLogId").useCache(false).list()*.getMap() }
    }

    // ------------------------------------------------------------------ helpers

    private Map admin(String verbNoun, Map params) {
        Map h = onThread(SUPER_ID.toLowerCase(), "${SVC}.${verbNoun}".toString(), params, false)
        assertNull(h.authzDenied, "the admin passes the fence: ${h.authzDenied}")
        return h
    }

    private Map adminOk(String verbNoun, Map params) {
        Map h = admin(verbNoun, params)
        assertFalse(h.hasError as boolean, "${verbNoun}: ${h.errors}")
        return (Map) h.result
    }

    private Map grantRow(String grantId) {
        asSystem { ec.entity.find(CliAccess.GRANT).condition("grantId", grantId).useCache(false).one()?.getMap() }
    }

    private long grantCount() { asSystem { ec.entity.find(CliAccess.GRANT).useCache(false).count() } }

    private Map onThread(String username, String serviceName, Map params, boolean bypassFence) {
        Map holder = [:]
        Thread worker = new Thread({
            ExecutionContext tec = Moqui.getExecutionContextFactory().getExecutionContext()
            try {
                if (!tec.user.internalLoginUser(username)) throw new IllegalStateException("could not log in ${username}")
                def c = tec.service.sync().name(serviceName).parameters(new LinkedHashMap(params))
                if (bypassFence) c.disableAuthz()
                try { holder.result = c.call() } catch (ArtifactAuthorizationException e) { holder.authzDenied = e }
                holder.errors = new ArrayList<String>(tec.message.errors)
                holder.hasError = tec.message.hasError()
            } catch (Throwable t) {
                holder.failure = t
            } finally {
                tec.destroy()
            }
        } as Runnable, "caa-${username}")
        worker.start()
        worker.join()
        if (holder.failure) throw (Throwable) holder.failure
        return holder
    }

    private <T> T asSystem(Closure<T> work) {
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try { return work.call() } finally { if (!wasDisabled) ec.artifactExecution.enableAuthz() }
    }

    private void upsert(String entityName, Map fields) { ec.entity.makeValue(entityName).setAll(fields).createOrUpdate() }
}
