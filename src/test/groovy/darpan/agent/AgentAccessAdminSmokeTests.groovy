package darpan.agent

import darpan.admin.AdminAccessSupport
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonSlurper
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
 * DAR-BE-194. Only a Darpan admin grants, changes, pauses, unpauses or revokes a user's AI access, and reads usage; a
 * tenant's own editor or viewer is refused by the admin fence and again by each service. Synthetic tenants and users.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentAccessAdminSmokeTests {
    private static final String SVC = "admin.AgentAccessAdminServices"
    private static final List<String> SERVICES = ["list#AgentAccessGrants", "create#AgentAccessGrant", "update#AgentAccessGrant",
            "pause#AgentAccessGrant", "unpause#AgentAccessGrant", "revoke#AgentAccessGrant", "get#AgentUsage"]
    private static final String ALPHA = "AAA_ALPHA"
    private static final String BETA = "AAA_BETA"
    private static final String EDITOR = "TEST_CUSTOMER_USER"
    private static final String EDITOR_USERNAME = "test.customer"
    private static final String VIEWER = "AAA_VIEWER"
    private static final String VIEWER_USERNAME = "aaa.viewer"
    private static final String OUTSIDER = "AAA_OUTSIDER"
    private static final String SUPER_ID = "AAA_SUPER"
    private static final String SUPER_USERNAME = "aaa.super"
    private static final Timestamp FROM = Timestamp.valueOf("2026-04-21 00:00:00")

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "agent-access-admin")
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        asSystem {
            [ALPHA, BETA].each { String t ->
                upsert("moqui.security.UserGroup", [userGroupId: t, description: "${t} Co".toString(), groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
                upsert("darpan.agent.AgentConversation", [conversationId: "${t}_CONV".toString(), companyUserGroupId: t, userId: EDITOR, createdDate: FROM])
            }
            [[VIEWER, VIEWER_USERNAME], [OUTSIDER, "aaa.outsider"], [SUPER_ID, SUPER_USERNAME]].each { List u ->
                upsert("moqui.security.UserAccount", [userId: u[0], username: u[1], userFullName: u[1], emailAddress: "${u[1]}@example.com".toString(),
                        disabled: "N", requirePasswordChange: "N"])
            }
            upsert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_SUPER_ADMIN", userId: SUPER_ID, fromDate: FROM])
            [[EDITOR, TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID], [VIEWER, TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID]].each { List m ->
                upsert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: m[0], fromDate: FROM])
                [ALPHA, BETA].each { String t ->
                    upsert("moqui.security.UserGroupMember", [userGroupId: t, userId: m[0], fromDate: FROM])
                    upsert(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [tenantUserGroupId: t, userId: m[0],
                            permissionUserGroupId: m[1], fromDate: FROM])
                }
            }
        }
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void reset() {
        asSystem { ["darpan.agent.AgentTurn", "darpan.agent.AgentAccessGrantEvent", "darpan.agent.AgentAccessGrant"].each { ec.entity.find(it).deleteAll() } }
    }

    // ------------------------------------------------------------------ helpers

    private Map admin(String verbNoun, Map params) {
        Map h = onThread(SUPER_USERNAME, "${SVC}.${verbNoun}".toString(), params, false)
        assertNull(h.authzDenied, "the admin passes the fence: ${h.authzDenied}")
        return h
    }

    private Map adminOk(String verbNoun, Map params) {
        Map h = admin(verbNoun, params)
        assertFalse(h.hasError as boolean, "${verbNoun}: ${h.errors}")
        return (Map) h.result
    }

    private Map grantRow(String grantId) {
        asSystem { ec.entity.find("darpan.agent.AgentAccessGrant").condition("grantId", grantId).useCache(false).one()?.getMap() }
    }

    private List<Map> events(String grantId) {
        asSystem { ec.entity.find("darpan.agent.AgentAccessGrantEvent").condition("grantId", grantId).orderBy("eventId").useCache(false).list()*.getMap() }
    }

    private long grantCount() { asSystem { ec.entity.find("darpan.agent.AgentAccessGrant").useCache(false).count() } }

    private void turn(String id, String grantId, String tenant, String user, String role, String model, long micros, Timestamp started) {
        asSystem {
            upsert("darpan.agent.AgentTurn", [turnId: id, conversationId: "${tenant}_CONV".toString(), companyUserGroupId: tenant, userId: user,
                    grantId: grantId, role: role, model: model, requestCount: 2L, inputTokens: 10L, outputTokens: 100L, cacheReadTokens: 1000L,
                    cacheWriteTokens: 50L, costMicros: micros, startedDate: started])
        }
    }

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
        } as Runnable, "aaa-${username}")
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

    // ------------------------------------------------------------------ tests

    @Test
    void "a non-admin is refused by the fence and by the service on every grant service"() {
        String id = adminOk("create#AgentAccessGrant", [costCapUsd: 5, userId: EDITOR, tenantUserGroupId: ALPHA, days: 1]).grant.grantId
        Map before = grantRow(id)
        Map params = [grantId: id, userId: EDITOR, tenantUserGroupId: ALPHA, days: 30, costCapUsd: 100]
        [EDITOR_USERNAME, VIEWER_USERNAME].each { String who ->
            SERVICES.each { String s ->
                Map fenced = onThread(who, "${SVC}.${s}".toString(), params, false)
                assertNotNull(fenced.authzDenied, "${who} ${s}: the admin fence must refuse")
                Map gated = onThread(who, "${SVC}.${s}".toString(), params, true)
                assertTrue((gated.errors as List).contains(AdminAccessSupport.SUPER_ADMIN_REQUIRED_MESSAGE), "${who} ${s}: ${gated.errors}")
            }
        }
        assertEquals(1L, grantCount(), "no grant was created")
        assertEquals(before, grantRow(id), "and the grant is unchanged")
    }

    @Test
    void "create needs a member of the tenant and replaces the existing grant"() {
        Map outsider = admin("create#AgentAccessGrant", [costCapUsd: 5, userId: OUTSIDER, tenantUserGroupId: ALPHA, days: 1])
        assertTrue(outsider.hasError as boolean)
        assertTrue((outsider.errors as List).any { it.contains("not a member") }, "${outsider.errors}")
        assertEquals(0L, grantCount())

        Map first = adminOk("create#AgentAccessGrant", [userId: EDITOR, tenantUserGroupId: ALPHA, days: 1, costCapUsd: 5]).grant
        assertEquals([EDITOR, EDITOR_USERNAME, ALPHA, "ACTIVE", 5_000_000L, "5.0000", AgentAccess.LIVE],
                [first.userId, first.username, first.tenantUserGroupId, first.statusId, first.capMicros, first.costCapUsd, first.state])
        Map second = adminOk("create#AgentAccessGrant", [costCapUsd: 5, userId: EDITOR, tenantUserGroupId: ALPHA, days: 2]).grant
        assertEquals("REVOKED", grantRow(first.grantId as String).statusId)
        assertEquals(second.grantId, AgentAccess.access(ec, ALPHA, EDITOR).grantId, "one live grant per user and tenant")
        assertEquals(["GRANT", "REVOKE"], events(first.grantId as String)*.action)
        assertEquals(["GRANT"], events(second.grantId as String)*.action)
        assertEquals(SUPER_ID, events(second.grantId as String)[0].byUserId)
        Map beta = adminOk("create#AgentAccessGrant", [costCapUsd: 5, userId: EDITOR, tenantUserGroupId: BETA]).grant
        assertEquals("ACTIVE", grantRow(second.grantId as String).statusId, "a grant in another tenant leaves this one alone")
        assertNull(beta.thruDate, "no days and no thruDate: no end date")
    }

    @Test
    void "a Darpan admin can be granted in a tenant they reach only as super admin"() {
        // A super admin enters every tenant without a membership row (TenantAccessSupport.listAvailableTenants); the spec has
        // admins need a grant too, so they must be grantable.
        Map g = adminOk("create#AgentAccessGrant", [costCapUsd: 5, userId: SUPER_ID, tenantUserGroupId: ALPHA, days: 1]).grant
        assertEquals([SUPER_ID, ALPHA, AgentAccess.LIVE], [g.userId, g.tenantUserGroupId, g.state])
        assertTrue(admin("create#AgentAccessGrant", [costCapUsd: 5, userId: OUTSIDER, tenantUserGroupId: ALPHA]).hasError as boolean, "a plain non-member is still refused")
    }

    @Test
    void "days sets the window, and days 0, a zero or negative limit or days with thruDate are refused"() {
        Map g = adminOk("create#AgentAccessGrant", [costCapUsd: 5, userId: EDITOR, tenantUserGroupId: ALPHA, days: 1]).grant
        long window = (g.thruDate as Timestamp).time - (g.fromDate as Timestamp).time
        assertEquals(24L * 3_600_000L, window, "one day is 24 hours from the grant")
        [[days: 0], [costCapUsd: 0], [costCapUsd: -5], [days: 1, thruDate: new Timestamp(System.currentTimeMillis() + 86_400_000L)],
         [thruDate: new Timestamp(System.currentTimeMillis() - 1_000L)]].each { Map bad ->
            Map r = admin("create#AgentAccessGrant", [costCapUsd: 5, userId: EDITOR, tenantUserGroupId: BETA] + bad)
            assertTrue(r.hasError as boolean, "refused: ${bad}")
        }
        assertEquals(1L, grantCount(), "none of the refused creates left a row")
        assertEquals(5_005_000L, adminOk("create#AgentAccessGrant", [userId: EDITOR, tenantUserGroupId: BETA, costCapUsd: new BigDecimal("5.005")]).grant.capMicros)
    }

    @Test
    void "a grant is not made without a limit, and no change takes the limit away"() {
        // Aditi 2026-11-10: the spend limit is decided when the Darpan admin grants access.
        Map none = admin("create#AgentAccessGrant", [userId: EDITOR, tenantUserGroupId: ALPHA, days: 1])
        assertTrue(none.hasError as boolean, "no costCapUsd: refused")
        Map tiny = admin("create#AgentAccessGrant", [userId: EDITOR, tenantUserGroupId: ALPHA, costCapUsd: new BigDecimal("0.0000001")])
        assertTrue(tiny.hasError as boolean, "a limit that rounds to 0 micro-dollars is no limit: refused")
        assertTrue((tiny.errors as List).any { it.contains("more than 0") }, "${tiny.errors}")
        assertEquals(0L, grantCount())

        String id = adminOk("create#AgentAccessGrant", [userId: EDITOR, tenantUserGroupId: ALPHA, costCapUsd: 5]).grant.grantId
        Map cleared = admin("update#AgentAccessGrant", [grantId: id, clearCostCap: true])
        assertTrue(cleared.hasError as boolean, "clearCostCap is gone: nothing to change")
        assertEquals(5_000_000L, grantRow(id).costCapMicros as long, "the limit stays")
    }

    @Test
    void "unpause over the limit needs a higher limit"() {
        String id = adminOk("create#AgentAccessGrant", [userId: EDITOR, tenantUserGroupId: ALPHA, costCapUsd: new BigDecimal("0.0005")]).grant.grantId
        turn("T1", id, ALPHA, EDITOR, "AUTHOR", "claude-opus-5-5", 600L, new Timestamp(System.currentTimeMillis()))
        assertTrue(AgentAccess.pauseIfOverLimit(ec, id))
        Map refused = admin("unpause#AgentAccessGrant", [grantId: id])
        assertTrue(refused.hasError as boolean)
        assertTrue((refused.errors as List).any { it.contains("higher limit") }, "${refused.errors}")
        assertEquals("PAUSED", grantRow(id).statusId)
        assertTrue(admin("unpause#AgentAccessGrant", [grantId: id, costCapUsd: new BigDecimal("0.0006")]).hasError as boolean, "equal to the spend is still at the limit")
        Map ok = adminOk("unpause#AgentAccessGrant", [grantId: id, costCapUsd: 1]).grant
        assertEquals(["ACTIVE", null, AgentAccess.LIVE, 1_000_000L], [ok.statusId, ok.pauseReason, ok.state, ok.capMicros])
    }

    @Test
    void "pause, unpause and revoke each log one event with who and the before and after"() {
        String id = adminOk("create#AgentAccessGrant", [costCapUsd: 5, userId: EDITOR, tenantUserGroupId: ALPHA, days: 1]).grant.grantId
        assertEquals(["PAUSED", "ADMIN"], adminOk("pause#AgentAccessGrant", [grantId: id]).grant.with { [it.statusId, it.pauseReason] })
        assertEquals(AgentAccess.PAUSED, AgentAccess.access(ec, ALPHA, EDITOR).state)
        assertEquals("ACTIVE", adminOk("unpause#AgentAccessGrant", [grantId: id, days: 3]).grant.statusId)
        assertEquals("ACTIVE", adminOk("update#AgentAccessGrant", [grantId: id, costCapUsd: 2]).grant.statusId)
        assertEquals("REVOKED", adminOk("revoke#AgentAccessGrant", [grantId: id]).grant.statusId)
        assertTrue(admin("unpause#AgentAccessGrant", [grantId: id]).hasError as boolean, "a revoked grant stays revoked")
        List<Map> evts = events(id)
        assertEquals(["GRANT", "PAUSE", "UNPAUSE", "CHANGE", "REVOKE"], evts*.action)
        assertTrue(evts.every { it.byUserId == SUPER_ID })
        Map pausedAfter = (Map) new JsonSlurper().parseText(evts[1].afterJson as String)
        Map pausedBefore = (Map) new JsonSlurper().parseText(evts[1].beforeJson as String)
        assertEquals(["ACTIVE", "PAUSED"], [pausedBefore.statusId, pausedAfter.statusId])
        assertEquals(2_000_000L, ((Map) new JsonSlurper().parseText(evts[3].afterJson as String)).costCapMicros as long)
    }

    @Test
    void "usage sums the ledger by day and by role and model for a user and a tenant"() {
        String id = adminOk("create#AgentAccessGrant", [costCapUsd: 5, userId: EDITOR, tenantUserGroupId: ALPHA]).grant.grantId
        Timestamp today = new Timestamp(System.currentTimeMillis())
        Timestamp yesterday = new Timestamp(System.currentTimeMillis() - 86_400_000L)
        turn("T1", id, ALPHA, EDITOR, "AUTHOR", "claude-opus-5-5", 40_000L, today)
        turn("T2", id, ALPHA, EDITOR, "INVESTIGATOR", "claude-sonnet-5-5", 10_000L, today)
        turn("T3", id, ALPHA, EDITOR, "AUTHOR", "claude-opus-5-5", 30_000L, yesterday)
        turn("T4", null, BETA, EDITOR, "AUTHOR", "claude-opus-5-5", 99_000L, today)
        turn("T5", null, ALPHA, VIEWER, "ROUTER", "claude-haiku-5-5", 500L, today)

        Map mine = adminOk("get#AgentUsage", [userId: EDITOR, tenantUserGroupId: ALPHA, days: 30])
        assertEquals(80_000L, mine.totalMicros)
        assertEquals(6L, mine.requests)
        assertEquals([30_000L, 50_000L], ((List<Map>) mine.byDay)*.costMicros, "oldest day first")
        Map opus = ((List<Map>) mine.byRoleModel).find { it.role == "AUTHOR" && it.model == "claude-opus-5-5" }
        assertEquals([2, 4L, 20L, 200L, 2000L, 100L, 70_000L], [opus.turns, opus.requests, opus.inputTokens, opus.outputTokens,
                                                                opus.cacheReadTokens, opus.cacheWriteTokens, opus.costMicros])
        assertEquals([id], ((List<Map>) mine.grants)*.grantId)
        assertEquals(80_000L, ((List<Map>) mine.grants)[0].spentMicros)

        assertEquals(80_500L, adminOk("get#AgentUsage", [tenantUserGroupId: ALPHA]).totalMicros, "the tenant: both users, not the other tenant")
        assertEquals(50_500L, adminOk("get#AgentUsage", [tenantUserGroupId: ALPHA, days: 1]).totalMicros, "the last day only")
        assertTrue(admin("get#AgentUsage", [days: 0]).hasError as boolean)
        assertTrue(admin("get#AgentUsage", [days: 367]).hasError as boolean)
        assertEquals([id], ((List<Map>) adminOk("list#AgentAccessGrants", [tenantUserGroupId: ALPHA]).grants)*.grantId)
    }
}
