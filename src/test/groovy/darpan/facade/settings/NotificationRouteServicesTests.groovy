package darpan.facade.settings

import darpan.reconciliation.support.SharedMoqui
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.notification.TenantNotificationSupport
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
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-UI-123: the notifications grid. get#NotificationRoutes lists every thing that posts to chat (streaks, each audit's
 * run update, each automation, each deployed script) with the one space it posts to; set#NotificationRoute moves one
 * source and writes nothing else on it; send#ChatSpaceTest posts a test message through the run-notification delivery.
 *
 * <p>MINE is the signed-in editor's tenant; OTHER owns a space, an audit, an automation and a deployment that must never
 * be listed or moved from MINE.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SharedMoqui
class NotificationRouteServicesTests {
    private static final String MINE = "KREWE"
    private static final String OTHER = "GORJANA"
    private static final String SPACE_A = "NR_SPACE_A"
    private static final String SPACE_B = "NR_SPACE_B"
    private static final String SPACE_OFF = "NR_SPACE_OFF"
    private static final String OTHER_SPACE = "NR_OTHER_SPACE"
    private static final String WEBHOOK_A = "https://chat.googleapis.com/v1/spaces/NRA/messages?key=k&token=a"
    private static final String WEBHOOK = "https://chat.googleapis.com/v1/spaces/NRX/messages?key=k&token=x"
    private static final String AUT_DAILY = "NR_AUT_DAILY"
    private static final String AUT_PAUSED = "NR_AUT_PAUSED"
    private static final String AUT_ARCHIVED = "NR_AUT_ARCHIVED"
    private static final String AUT_OTHER = "NR_AUT_OTHER"
    private static final String DEP_MINE = "NR_DEP_MINE"
    private static final String DEP_OTHER = "NR_DEP_OTHER"
    private static final String SERVICES = "facade.SettingsFacadeServices."
    private static final Timestamp FROM = Timestamp.valueOf("2026-04-23 00:00:00")

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "notification-route-services")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/AutomationSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)   // signs in a MINE editor, MINE active
        insert("moqui.security.UserGroup", [userGroupId: OTHER, description: "Other tenant",
                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
        insert("moqui.security.UserGroup", [userGroupId: TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID,
                description: "View only", groupTypeEnumId: TenantAccessSupport.DARPAN_PERMISSION_GROUP_TYPE_ENUM_ID])
        insert("moqui.security.UserGroup", [userGroupId: TenantAccessSupport.DARPAN_TENANT_ADMIN_GROUP_ID,
                description: "Tenant admin", groupTypeEnumId: TenantAccessSupport.DARPAN_PERMISSION_GROUP_TYPE_ENUM_ID])
    }

    @AfterAll
    void cleanup() {
        TenantNotificationSupport.resetDeliveryHook()
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    /** Every fixture is MERGEd back to its starting values, so each test starts from the same routes. */
    @BeforeEach
    void resetFixtures() {
        TenantNotificationSupport.resetDeliveryHook()
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        setPermissions([TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID])
        space(SPACE_A, MINE, "Alpha", "Y", WEBHOOK_A)
        space(SPACE_B, MINE, "Bravo", "Y", WEBHOOK)
        space(SPACE_OFF, MINE, "Off", "N", WEBHOOK)
        space(OTHER_SPACE, OTHER, "Theirs", "Y", WEBHOOK)
        insert("darpan.auth.TenantSetting", [companyUserGroupId: MINE, timeZone: "America/Los_Angeles", streaksChatSpaceId: SPACE_A])

        insert("darpan.audit.Audit", [auditId: "NR_MINE_MC", companyUserGroupId: MINE, auditCode: "month-close",
                auditName: "Month close", periodEnumId: "AUDP_MONTH", scheduleActive: "Y"])
        insert("darpan.audit.AuditDigest", [auditDigestId: "RUN_NR_MINE_MC", companyUserGroupId: MINE, auditId: "NR_MINE_MC",
                kindEnumId: "AUDDK_RUN", sendTime: "00:00", isActive: "Y", chatSpaceId: SPACE_B,
                emailAddresses: "close@krewe.example"])
        insert("darpan.audit.Audit", [auditId: "NR_OTHER_AU", companyUserGroupId: OTHER, auditCode: "other-only",
                auditName: "Other only", periodEnumId: "AUDP_MONTH"])

        automation(AUT_DAILY, MINE, "Orders daily", "0 30 6 * * ?", "Y", "N", SPACE_A)
        automation(AUT_PAUSED, MINE, "Returns weekly", "0 0 7 ? * MON", "N", "N", null)
        automation(AUT_ARCHIVED, MINE, "Old one", "0 0 8 * * ?", "N", "Y", SPACE_A)
        automation(AUT_OTHER, OTHER, "Their automation", "0 0 6 * * ?", "Y", "N", OTHER_SPACE)
        ["FILE_1", "FILE_2"].each { String side ->
            insert("darpan.reconciliation.ReconciliationAutomationSource", [automationId: AUT_DAILY, fileSide: side,
                    companyUserGroupId: MINE, sourceTypeEnumId: "AUT_SRC_API", primaryIdExpression: "orderId"])
        }

        insert("darpan.script.TenantScript", [tenantScriptId: "NR_SCRIPT_MINE", companyUserGroupId: MINE, scriptName: "Morning note",
                scriptProfile: "EXPRESSION", inputSource: "SAMPLE", scriptBody: "'hi'", isActive: "Y"])
        insert("darpan.script.TenantScript", [tenantScriptId: "NR_SCRIPT_OTHER", companyUserGroupId: OTHER, scriptName: "Their note",
                scriptProfile: "EXPRESSION", inputSource: "SAMPLE", scriptBody: "'hi'", isActive: "Y"])
        insert("darpan.script.TenantScriptDeployment", [tenantScriptDeploymentId: DEP_MINE, companyUserGroupId: MINE,
                tenantScriptId: "NR_SCRIPT_MINE", scheduleExpr: "0 0 9 * * ?", inputSavedRunId: "NR_SAVED_RUN", chatSpaceId: SPACE_A,
                isActive: "Y", nextRunDate: Timestamp.valueOf("2026-11-19 09:00:00"), createdDate: FROM])
        insert("darpan.script.TenantScriptDeployment", [tenantScriptDeploymentId: DEP_OTHER, companyUserGroupId: OTHER,
                tenantScriptId: "NR_SCRIPT_OTHER", scheduleExpr: "0 0 9 * * ?", chatSpaceId: OTHER_SPACE, isActive: "Y", createdDate: FROM])
        ec.message.clearErrors()
    }

    @Test
    void theReadListsStreaksEachAuditUpdateEveryAutomationAndEachDeployedScriptWithItsSpace() {
        Map result = facade("get#NotificationRoutes", [:])
        assertTrue(result.ok as boolean, result.errors?.toString())

        List<Map> spaces = result.spaces as List<Map>
        assertEquals([SPACE_A, SPACE_B, SPACE_OFF], spaces*.chatSpaceId, "the tenant's spaces by name, switched off too, never another tenant's")
        assertEquals(["Y", "Y", "N"], spaces*.isActive)
        assertTrue(spaces.every { !it.containsKey("webhookUrl") && !it.containsKey("googleChatWebhookUrl") }, "no webhook URL: ${spaces}")
        assertEquals(true, spaces[0].webhookConfigured)

        List<Map> routes = result.routes as List<Map>
        assertEquals([
                [sourceType: "STREAKS", sourceId: null, label: "Streaks", when: null, paused: false, chatSpaceId: SPACE_A],
                [sourceType: "AUDIT_UPDATE", sourceId: "month-close", label: "Month close update", when: "After each run", paused: false, chatSpaceId: SPACE_B],
                [sourceType: "AUTOMATION", sourceId: AUT_DAILY, label: "Orders daily", when: "Daily 06:30", paused: false, chatSpaceId: SPACE_A],
                [sourceType: "AUTOMATION", sourceId: AUT_PAUSED, label: "Returns weekly", when: null, paused: true, chatSpaceId: null],
                [sourceType: "SCRIPT", sourceId: DEP_MINE, label: "Morning note", when: "Daily 09:00", paused: false, chatSpaceId: SPACE_A],
        ], routes, "archived automations and everything of the other tenant are left out")
    }

    @Test
    void aTenantWithNoAuditHasNoAuditRowAndAnAuditWithNoRunDigestPostsNowhere() {
        ec.entity.find("darpan.audit.AuditDigest").condition("companyUserGroupId", MINE).disableAuthz().deleteAll()
        List<Map> routes = facade("get#NotificationRoutes", [:]).routes as List<Map>
        assertEquals([sourceType: "AUDIT_UPDATE", sourceId: "month-close", label: "Month close update", when: "After each run",
                      paused: false, chatSpaceId: null], routes.find { it.sourceType == "AUDIT_UPDATE" })

        ec.entity.find("darpan.audit.Audit").condition("companyUserGroupId", MINE).disableAuthz().deleteAll()
        routes = facade("get#NotificationRoutes", [:]).routes as List<Map>
        assertTrue(routes.every { it.sourceType != "AUDIT_UPDATE" }, "${routes}")
        assertEquals("STREAKS", routes[0].sourceType, "streaks is always listed")
    }

    @Test
    void setMovesEachSourceAndTouchesNothingElseOnIt() {
        // Streaks: only the pointer; the tenant's time zone stays.
        Map streaks = facade("set#NotificationRoute", [sourceType: "STREAKS", chatSpaceId: SPACE_B])
        assertTrue(streaks.ok as boolean, streaks.errors?.toString())
        assertEquals([sourceType: "STREAKS", sourceId: null, label: "Streaks", when: null, paused: false, chatSpaceId: SPACE_B], streaks.route)
        assertEquals(["Posts to Bravo."], streaks.messages)
        assertEquals([SPACE_B, "America/Los_Angeles"], ["streaksChatSpaceId", "timeZone"].collect { row("darpan.auth.TenantSetting", "companyUserGroupId", MINE)[it] })

        // Automation: chatSpaceId only. Its sources are the ones sync#Automation would rewrite.
        Map automationBefore = withoutSpace(row("darpan.reconciliation.ReconciliationAutomation", "automationId", AUT_DAILY))
        List sourcesBefore = children("darpan.reconciliation.ReconciliationAutomationSource")
        Map moved = facade("set#NotificationRoute", [sourceType: "AUTOMATION", sourceId: AUT_DAILY, chatSpaceId: SPACE_B])
        assertTrue(moved.ok as boolean, moved.errors?.toString())
        assertEquals(SPACE_B, (moved.route as Map).chatSpaceId)
        Map automationAfter = row("darpan.reconciliation.ReconciliationAutomation", "automationId", AUT_DAILY)
        assertEquals(SPACE_B, automationAfter.chatSpaceId)
        assertEquals(automationBefore, withoutSpace(automationAfter))
        assertEquals(sourcesBefore, children("darpan.reconciliation.ReconciliationAutomationSource"))
        assertEquals(2, sourcesBefore.size(), "the sources fixture is in place")

        // Deployed script: chatSpaceId only; schedule, saved run, on/off and next run stay.
        Map deploymentBefore = withoutSpace(row("darpan.script.TenantScriptDeployment", "tenantScriptDeploymentId", DEP_MINE))
        Map script = facade("set#NotificationRoute", [sourceType: "SCRIPT", sourceId: DEP_MINE, chatSpaceId: SPACE_B])
        assertTrue(script.ok as boolean, script.errors?.toString())
        Map deploymentAfter = row("darpan.script.TenantScriptDeployment", "tenantScriptDeploymentId", DEP_MINE)
        assertEquals(SPACE_B, deploymentAfter.chatSpaceId)
        assertEquals(deploymentBefore, withoutSpace(deploymentAfter))
        assertEquals(["0 0 9 * * ?", "NR_SAVED_RUN", "Y"], ["scheduleExpr", "inputSavedRunId", "isActive"].collect { deploymentAfter[it] })

        // Audit update: the run digest's chatSpaceId only; its addresses and on/off stay. Needs a tenant admin.
        asTenantAdmin {
            Map audit = facade("set#NotificationRoute", [sourceType: "AUDIT_UPDATE", sourceId: "month-close", chatSpaceId: SPACE_A])
            assertTrue(audit.ok as boolean, audit.errors?.toString())
            assertEquals(SPACE_A, (audit.route as Map).chatSpaceId)
        }
        assertEquals([SPACE_A, "close@krewe.example", "Y"], ["chatSpaceId", "emailAddresses", "isActive"]
                .collect { row("darpan.audit.AuditDigest", "auditDigestId", "RUN_NR_MINE_MC")[it] })

        assertEquals([SPACE_B, SPACE_A, SPACE_B, SPACE_B], (facade("get#NotificationRoutes", [:]).routes as List<Map>)
                .findAll { it.sourceId in [null, "month-close", AUT_DAILY, DEP_MINE] }*.chatSpaceId)
    }

    @Test
    void nowhereClearsTheFieldButADeployedScriptAlwaysPostsSomewhere() {
        Map streaks = facade("set#NotificationRoute", [sourceType: "STREAKS", chatSpaceId: ""])
        assertTrue(streaks.ok as boolean, streaks.errors?.toString())
        assertEquals(["Posts nowhere."], streaks.messages)
        assertNull(row("darpan.auth.TenantSetting", "companyUserGroupId", MINE).streaksChatSpaceId)

        Map automation = facade("set#NotificationRoute", [sourceType: "AUTOMATION", sourceId: AUT_DAILY, chatSpaceId: ""])
        assertTrue(automation.ok as boolean, automation.errors?.toString())
        assertNull((automation.route as Map).chatSpaceId)
        assertNull(row("darpan.reconciliation.ReconciliationAutomation", "automationId", AUT_DAILY).chatSpaceId)

        asTenantAdmin {
            assertTrue(facade("set#NotificationRoute", [sourceType: "AUDIT_UPDATE", sourceId: "month-close"]).ok as boolean)
        }
        assertNull(row("darpan.audit.AuditDigest", "auditDigestId", "RUN_NR_MINE_MC").chatSpaceId)

        Map script = facade("set#NotificationRoute", [sourceType: "SCRIPT", sourceId: DEP_MINE, chatSpaceId: ""])
        assertFalse(script.ok as boolean)
        assertEquals(["A deployed script needs a space. Turn its schedule off instead."], script.errors)
        assertEquals(SPACE_A, row("darpan.script.TenantScriptDeployment", "tenantScriptDeploymentId", DEP_MINE).chatSpaceId)
    }

    @Test
    void refusesAnotherTenantsSpaceASwitchedOffSpaceAndAnotherTenantsSources() {
        // Positive control: the same call with the tenant's own active space goes through.
        assertTrue(facade("set#NotificationRoute", [sourceType: "AUTOMATION", sourceId: AUT_DAILY, chatSpaceId: SPACE_B]).ok as boolean)

        Map foreignSpace = facade("set#NotificationRoute", [sourceType: "AUTOMATION", sourceId: AUT_DAILY, chatSpaceId: OTHER_SPACE])
        assertEquals(["Chat space ${OTHER_SPACE} was not found.".toString()], foreignSpace.errors)
        Map offSpace = facade("set#NotificationRoute", [sourceType: "STREAKS", chatSpaceId: SPACE_OFF])
        assertEquals(["Chat space 'Off' is switched off."], offSpace.errors)
        assertNull(offSpace.route)
        assertEquals(SPACE_B, row("darpan.reconciliation.ReconciliationAutomation", "automationId", AUT_DAILY).chatSpaceId)
        assertEquals(SPACE_A, row("darpan.auth.TenantSetting", "companyUserGroupId", MINE).streaksChatSpaceId)

        Map foreignAutomation = facade("set#NotificationRoute", [sourceType: "AUTOMATION", sourceId: AUT_OTHER, chatSpaceId: SPACE_A])
        assertEquals(["Automation ${AUT_OTHER} was not found.".toString()], foreignAutomation.errors)
        assertEquals(OTHER_SPACE, row("darpan.reconciliation.ReconciliationAutomation", "automationId", AUT_OTHER).chatSpaceId)

        Map foreignScript = facade("set#NotificationRoute", [sourceType: "SCRIPT", sourceId: DEP_OTHER, chatSpaceId: SPACE_A])
        assertEquals(["Deployed script ${DEP_OTHER} was not found.".toString()], foreignScript.errors)
        assertEquals(OTHER_SPACE, row("darpan.script.TenantScriptDeployment", "tenantScriptDeploymentId", DEP_OTHER).chatSpaceId)

        asTenantAdmin {
            Map foreignAudit = facade("set#NotificationRoute", [sourceType: "AUDIT_UPDATE", sourceId: "other-only", chatSpaceId: SPACE_A])
            assertEquals(["Audit other-only was not found."], foreignAudit.errors)
        }
        assertEquals(0L, ec.entity.find("darpan.audit.AuditDigest").condition("auditId", "NR_OTHER_AU").disableAuthz().count())

        Map unknown = facade("set#NotificationRoute", [sourceType: "DIGEST", sourceId: "x", chatSpaceId: SPACE_A])
        assertEquals(["DIGEST does not post to chat. Use STREAKS, AUDIT_UPDATE, AUTOMATION or SCRIPT."], unknown.errors)
    }

    @Test
    void movingNeedsEditAccessAndAnAuditUpdateNeedsATenantAdmin() {
        Map editor = facade("set#NotificationRoute", [sourceType: "AUDIT_UPDATE", sourceId: "month-close", chatSpaceId: SPACE_A])
        assertEquals(["Only a tenant admin can change where an audit update posts."], editor.errors)
        assertEquals(SPACE_B, row("darpan.audit.AuditDigest", "auditDigestId", "RUN_NR_MINE_MC").chatSpaceId)

        setPermissions([TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID])
        Map viewer = facade("set#NotificationRoute", [sourceType: "AUTOMATION", sourceId: AUT_DAILY, chatSpaceId: SPACE_B])
        assertEquals(["Your active tenant only has view access for notifications."], viewer.errors)
        assertEquals(SPACE_A, row("darpan.reconciliation.ReconciliationAutomation", "automationId", AUT_DAILY).chatSpaceId)

        boolean delivered = false
        TenantNotificationSupport.setDeliveryHook { String ref, Map payload -> delivered = true; [ok: true, statusCode: 200] }
        Map test = facade("send#ChatSpaceTest", [chatSpaceId: SPACE_A])
        assertEquals(["Your active tenant only has view access for chat spaces."], test.errors)
        assertFalse(delivered, "a viewer's test reached the space")

        assertTrue(facade("get#NotificationRoutes", [:]).ok as boolean, "a viewer still reads the grid")
    }

    @Test
    void theTestMessageGoesThroughRunDeliveryAndAFailureComesBackInWords() {
        List<List> posted = []
        TenantNotificationSupport.setDeliveryHook { String ref, Map payload -> posted << [ref, payload]; [ok: true, statusCode: 200] }
        Map sent = facade("send#ChatSpaceTest", [chatSpaceId: SPACE_A])
        assertTrue(sent.ok as boolean, sent.errors?.toString())
        assertEquals(["Sent a test message to Alpha."], sent.messages)
        assertEquals([[WEBHOOK_A, [text: ChatSpaceFacadeSupport.TEST_MESSAGE]]], posted)

        TenantNotificationSupport.setDeliveryHook { String ref, Map payload -> [ok: false, statusCode: 404, providerMessage: "no_service"] }
        Map refused = facade("send#ChatSpaceTest", [chatSpaceId: SPACE_A])
        assertFalse(refused.ok as boolean)
        assertEquals(["The chat space refused the message (404, no_service)."], refused.errors)

        TenantNotificationSupport.setDeliveryHook { String ref, Map payload -> throw new IOException("connect to ${ref} failed") }
        Map unreachable = facade("send#ChatSpaceTest", [chatSpaceId: SPACE_A])
        assertEquals(["Could not reach the chat space."], unreachable.errors, "the webhook URL never comes back in the error")

        posted.clear()
        TenantNotificationSupport.setDeliveryHook { String ref, Map payload -> posted << [ref, payload]; [ok: true, statusCode: 200] }
        assertEquals(["Chat space 'Off' is switched off."], facade("send#ChatSpaceTest", [chatSpaceId: SPACE_OFF]).errors)
        assertEquals(["Chat space ${OTHER_SPACE} was not found.".toString()], facade("send#ChatSpaceTest", [chatSpaceId: OTHER_SPACE]).errors)
        assertTrue(posted.isEmpty(), "nothing is posted to a switched-off or foreign space: ${posted}")
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private Map facade(String verbNoun, Map parameters) {
        ec.message.clearAll()
        Map result = (Map) ec.service.sync().name(SERVICES + verbNoun).parameters(parameters).disableAuthz().call()
        ec.message.clearAll()
        return result
    }

    private void asTenantAdmin(Closure body) {
        setPermissions([TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID, TenantAccessSupport.DARPAN_TENANT_ADMIN_GROUP_ID])
        try {
            body.call()
        } finally {
            setPermissions([TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID])
        }
    }

    /** The signed-in user's permission groups on MINE, exactly these. */
    private void setPermissions(List<String> permissionGroupIds) {
        ec.entity.find(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME).condition("tenantUserGroupId", MINE)
                .condition("userId", ec.user.userId).disableAuthz().deleteAll()
        permissionGroupIds.each { String groupId ->
            insert(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [tenantUserGroupId: MINE, userId: ec.user.userId,
                    permissionUserGroupId: groupId, fromDate: FROM])
        }
    }

    private void space(String id, String tenant, String name, String active, String webhook) {
        insert("darpan.reconciliation.TenantChatSpace", [chatSpaceId: id, companyUserGroupId: tenant, spaceName: name,
                webhookUrl: webhook, isActive: active])
    }

    private void automation(String id, String tenant, String name, String cron, String active, String archived, String spaceId) {
        insert("darpan.reconciliation.ReconciliationAutomation", [automationId: id, automationName: name, companyUserGroupId: tenant,
                inputModeEnumId: "AUT_IN_API_RANGE", savedRunId: "NR_SAVED_RUN", savedRunType: "ruleset", scheduleExpr: cron,
                windowTimeZone: "UTC", isActive: active, isArchived: archived, chatSpaceId: spaceId, createdDate: FROM])
    }

    private Map row(String entityName, String pkField, String pkValue) {
        def value = ec.entity.find(entityName).condition(pkField, pkValue).disableAuthz().useCache(false).one()
        assertNotNull(value, "${entityName} ${pkValue} is missing")
        return value.getMap()
    }

    private List<Map> children(String entityName) {
        return ec.entity.find(entityName).condition("automationId", AUT_DAILY).orderBy("fileSide").disableAuthz().useCache(false)
                .list()*.getMap().collect { withoutSpace(it) }
    }

    /** The row without the field the move writes, and without the stamp Moqui sets on any update. */
    private static Map withoutSpace(Map row) {
        return row.findAll { it.key != "chatSpaceId" && it.key != "lastUpdatedStamp" }
    }

    private void insert(String entityName, Map<String, Object> fields) {
        ReconciliationSmokeTestSupport.insertEntityDirect(ec, entityName, fields)
    }
}
