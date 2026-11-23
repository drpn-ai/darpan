package darpan.query

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.notification.TenantNotificationSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import darpan.reconciliation.support.SharedMoqui
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.sql.Timestamp

import static darpan.query.QueryTestSupport.EDITOR_ID
import static darpan.query.QueryTestSupport.FROM_DATE
import static darpan.query.QueryTestSupport.GORJANA
import static darpan.query.QueryTestSupport.KREWE
import static darpan.query.QueryTestSupport.insert
import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-334: facade.TenantScriptFacadeServices — the active tenant's queries and schedules on moqui-jexl. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SharedMoqui
class TenantQueryFacadeSmokeTests {
    private static final String SVC = "facade.TenantScriptFacadeServices"
    private static final String HELLO = "{ 'summary': 'hi', 'files': [ { 'name': 'a.csv', 'columns': ['x'], 'rows': [[1], [2]] } ] }"
    private ExecutionContext ec
    private final List<Map> posts = []

    @BeforeAll
    void setup() {
        ec = QueryTestSupport.boot("query-facade")
        insert(ec, TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [tenantUserGroupId: KREWE, userId: EDITOR_ID,
                permissionUserGroupId: TenantAccessSupport.DARPAN_TENANT_ADMIN_GROUP_ID, fromDate: FROM_DATE])
        insert(ec, "moqui.security.UserAccount", [userId: "QT_EDITOR2", username: "qt.editor2", userFullName: "Editor Two", currentPassword: "", disabled: "N"])
        insert(ec, "moqui.security.UserGroupMember", [userGroupId: KREWE, userId: "QT_EDITOR2", fromDate: FROM_DATE])
        insert(ec, "moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: "QT_EDITOR2", fromDate: FROM_DATE])
        insert(ec, TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [tenantUserGroupId: KREWE, userId: "QT_EDITOR2",
                permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID, fromDate: FROM_DATE])
        insert(ec, "darpan.reconciliation.TenantChatSpace", [chatSpaceId: "QT_SPACE", companyUserGroupId: KREWE, spaceName: "Krewe ops",
                webhookUrl: "https://chat.example.test/krewe", isActive: "Y"])
        insert(ec, "jexl.script.UserScript", [userScriptId: "QT_GORJANA", companyUserGroupId: GORJANA, scriptName: "Gorjana secret",
                scriptProfile: "QUERY", scriptBody: "'g'", isActive: "Y"])
        TenantNotificationSupport.setDeliveryHook { String ref, Map payload -> posts << [ref: ref, text: payload?.text]; return [ok: true, statusCode: 200] }
    }

    @AfterAll
    void cleanup() {
        TenantNotificationSupport.resetDeliveryHook()
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void asEditor() {
        QueryTestSupport.actAsKreweEditor(ec)
        posts.clear()
        ec.message.clearAll()
    }

    private Map call(String name, Map p) {
        Map out = ec.service.sync().name("${SVC}.${name}").parameters(p).call()
        ec.message.clearErrors()
        return out
    }

    private String saveHello(String name) {
        Map out = call("save#TenantQuery", [queryName: name, scriptBody: HELLO])
        assertTrue(out.ok as boolean, "${out.errors}")
        return out.query.tenantQueryId
    }

    @Test
    void aQueryRunsOnTheTenantsData() {
        String id = saveHello("Hello")
        Map out = call("run#TenantQuery", [tenantQueryId: id])
        assertTrue(out.ok as boolean, "${out.errors}")
        assertEquals("hi", out.result.summary)
        assertEquals([[name: "a.csv", columns: ["x"], rowCount: 2, previewRows: [[1], [2]]]], out.result.files)
        assertTrue(call("list#TenantQueries", [:]).queries*.tenantQueryId.contains(id))
    }

    @Test
    void previewSendsNothing() {
        String id = saveHello("Hello preview")
        Map saved = call("save#TenantQuerySchedule", [tenantQueryId: id, dailyAt: "09:00", chatSpaceId: "QT_SPACE", emailTo: "ops@example.test"])
        assertTrue(saved.ok as boolean, "${saved.errors}")
        assertEquals("0 0 9 * * ?", saved.schedule.cronExpression)
        assertEquals(EDITOR_ID, saved.schedule.ownerUserId)
        assertEquals(1, saved.schedule.recipientCount)
        Map out = call("run#TenantQuerySchedule", [scheduleId: saved.schedule.scheduleId])
        assertTrue(out.ok as boolean, "${out.errors}")
        assertEquals("PREVIEW", out.outcome.status)
        assertEquals(0, posts.size())
    }

    @Test
    void scheduleNeedsThePermission() {
        String id = saveHello("Hello perm")
        assertTrue(ec.user.internalLoginUser("qt.editor2"))
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, KREWE)
        Map out = call("save#TenantQuerySchedule", [tenantQueryId: id, dailyAt: "09:00", emailTo: "ops@example.test"])
        assertFalse(out.ok as boolean)
        assertTrue("${out.errors}".contains("tenant-admin"), "${out.errors}")
    }

    @Test
    void aRemovedMemberStopsTheirSchedule() {
        String id = saveHello("Hello owner")
        Map saved = call("save#TenantQuerySchedule", [tenantQueryId: id, dailyAt: "09:00", chatSpaceId: "QT_SPACE", isActive: true])
        assertTrue(saved.ok as boolean, "${saved.errors}")
        String scheduleId = saved.schedule.scheduleId
        ec.entity.find("jexl.script.UserScriptDeployment").condition("scriptDeploymentId", scheduleId).disableAuthz().one()
                .set("nextRunDate", Timestamp.valueOf("2030-01-01 00:00:00")).update()
        def adminRow = ec.entity.find(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME)
                .condition([tenantUserGroupId: KREWE, userId: EDITOR_ID, permissionUserGroupId: TenantAccessSupport.DARPAN_TENANT_ADMIN_GROUP_ID])
                .disableAuthz().useCache(false).one()
        adminRow.set("thruDate", Timestamp.valueOf("2026-05-01 00:00:00")).update()
        try {
            ec.service.sync().name("moqui.jexl.ScriptServices.run#DueUserScriptDeployments").parameters([nowTimestamp: Timestamp.valueOf("2030-01-01 00:01:00")]).call()
            def schedule = ec.entity.find("jexl.script.UserScriptDeployment").condition("scriptDeploymentId", scheduleId).disableAuthz().useCache(false).one()
            assertEquals("OWNER_LOST", schedule.lastRunStatus)
            assertTrue((schedule.lastRunMessage as String).contains("no longer an admin"), schedule.lastRunMessage as String)
            assertEquals("N", schedule.isActive)
            assertEquals(0, posts.size())
        } finally {
            adminRow.set("thruDate", null).update()
        }
    }

    @Test
    void anotherTenantsQueryIsUntouchable() {
        assertFalse(call("list#TenantQueries", [:]).queries*.tenantQueryId.contains("QT_GORJANA"))
        Map run = call("run#TenantQuery", [tenantQueryId: "QT_GORJANA"])
        assertTrue("${run.errors}".contains("was not found"), "${run.errors}")
        Map save = call("save#TenantQuery", [tenantQueryId: "QT_GORJANA", queryName: "Mine now", scriptBody: "'x'"])
        assertTrue("${save.errors}".contains("was not found"), "${save.errors}")
        Map schedule = call("save#TenantQuerySchedule", [tenantQueryId: "QT_GORJANA", dailyAt: "09:00", emailTo: "x@example.test"])
        assertTrue("${schedule.errors}".contains("was not found"), "${schedule.errors}")
        Map delete = call("delete#TenantQuery", [tenantQueryId: "QT_GORJANA"])
        assertTrue("${delete.errors}".contains("was not found"), "${delete.errors}")
        assertEquals("Gorjana secret", ec.entity.find("jexl.script.UserScript").condition("userScriptId", "QT_GORJANA").disableAuthz().useCache(false).one().scriptName)
    }

    @Test
    void readTextDescribesTheRead() {
        assertEquals("Reads dataset D1, last 30 days, status = B, 2 fields",
                TenantQuerySupport.readText('{"inputs":[{"name":"o","source":"dataset","datasetId":"D1","fields":["id","status"],"where":{"field":"status","op":"equals","value":"B"},"window":{"field":"createdAt","lastDays":30}}]}'))
        assertNull(TenantQuerySupport.readText(null))
    }
}
