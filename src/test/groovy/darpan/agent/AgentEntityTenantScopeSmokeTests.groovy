package darpan.agent

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-159. The agent's conversation, turn, message, proposal and ticket rows are tenant-owned: another tenant's
 * active scope never lists them. Tenants and rows are synthetic.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentEntityTenantScopeSmokeTests {

    private static final String ALPHA = "AGENT_ALPHA"
    private static final String BETA = "AGENT_BETA"
    private static final String EDITOR_ID = "TEST_CUSTOMER_USER"
    private static final String EDITOR_USERNAME = "test.customer"
    private static final Timestamp FROM_DATE = Timestamp.valueOf("2026-04-21 00:00:00")
    private static final String FACADE_GRANT = "facade.ReconciliationFacadeServices.list#Reconciliations"
    private static final List<String> ENTITIES = ["darpan.agent.AgentConversation", "darpan.agent.AgentTurn",
            "darpan.agent.AgentMessage", "darpan.agent.AuditChangeProposal", "darpan.agent.BrainChangeTicket",
            "darpan.agent.AgentAccessGrant", "darpan.agent.AgentAccessGrantEvent"]

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "agent-entities")
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userGroupId: "DARPAN_USER", userId: EDITOR_ID, fromDate: FROM_DATE]).createOrUpdate()
        [ALPHA, BETA].each { String t ->
            ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: t, description: "${t} Co".toString(),
                    groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID]).createOrUpdate()
            ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userGroupId: t, userId: EDITOR_ID, fromDate: FROM_DATE]).createOrUpdate()
            ec.entity.makeValue(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME).setAll([tenantUserGroupId: t,
                    userId: EDITOR_ID, permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID, fromDate: FROM_DATE]).createOrUpdate()
        }
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @Test
    void "tenant B cannot read tenant A's proposal, ticket or conversation"() {
        Timestamp now = new Timestamp(System.currentTimeMillis())
        ec.entity.makeValue("darpan.agent.AgentConversation").setAll([conversationId: "AGC_A", companyUserGroupId: ALPHA,
                userId: EDITOR_ID, createdDate: now]).create()
        ec.entity.makeValue("darpan.agent.AgentTurn").setAll([turnId: "AGT_A", conversationId: "AGC_A", companyUserGroupId: ALPHA,
                role: "ROUTER", startedDate: now, userId: EDITOR_ID, grantId: "AAG_A"]).create()
        ec.entity.makeValue("darpan.agent.AgentMessage").setAll([messageId: "AGM_A", conversationId: "AGC_A", turnId: "AGT_A",
                companyUserGroupId: ALPHA, sequenceNum: 1, kind: "USER", contentJson: "{}"]).create()
        ec.entity.makeValue("darpan.agent.AuditChangeProposal").setAll([proposalId: "ACP_A", companyUserGroupId: ALPHA,
                conversationId: "AGC_A", kind: "FIX_TEXT", status: "DRAFT", provenanceStatus: "PASS"]).create()
        ec.entity.makeValue("darpan.agent.BrainChangeTicket").setAll([ticketId: "BCT_A", companyUserGroupId: ALPHA,
                conversationId: "AGC_A", category: "NEW_CHECK", status: "RECEIVED", createdDate: now]).create()
        // DAR-BE-194: a Darpan admin's grant of AI access to one user in one tenant, and its change log.
        ec.entity.makeValue("darpan.agent.AgentAccessGrant").setAll([grantId: "AAG_A", userId: EDITOR_ID, companyUserGroupId: ALPHA,
                fromDate: now, costCapMicros: 5_000_000L, statusId: "ACTIVE"]).create()
        ec.entity.makeValue("darpan.agent.AgentAccessGrantEvent").setAll([eventId: "AAE_A", grantId: "AAG_A", companyUserGroupId: ALPHA,
                userId: EDITOR_ID, action: "GRANT", byUserId: "ADMIN", eventDate: now, afterJson: "{}"]).create()

        actAs(ALPHA)
        underFacadeGrant {
            ENTITIES.each { String name ->
                assertEquals(1, ec.entity.find(name).useCache(false).list().size(), "tenant A sees its own ${name}")
            }
            def turn = ec.entity.find("darpan.agent.AgentTurn").useCache(false).list()[0]
            assertEquals([EDITOR_ID, "AAG_A"], [turn.userId, turn.grantId], "a turn names its user and grant")
        }
        actAs(BETA)
        underFacadeGrant {
            ENTITIES.each { String name ->
                assertEquals(0, ec.entity.find(name).useCache(false).list().size(), "tenant B must not see ${name}")
            }
        }
    }

    private void actAs(String tenant) {
        assertTrue(ec.user.internalLoginUser(EDITOR_USERNAME), "could not log in")
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, tenant)
        TenantAccessSupport.syncUserContext(ec)
        ec.message.clearErrors()
    }

    private void underFacadeGrant(Closure body) {
        def harnessFrame = ec.artifactExecution.peek()
        assertEquals("smokeTests", harnessFrame?.name, "test precondition: unexpected artifact stack")
        ec.artifactExecution.pop(harnessFrame)
        boolean wasDisabled = ec.artifactExecution.authzDisabled
        ec.artifactExecution.enableAuthz()
        try {
            def aei = ec.artifactExecution.push(FACADE_GRANT, ArtifactExecutionInfo.AT_SERVICE, ArtifactExecutionInfo.AUTHZA_VIEW, true)
            try { body.call() } finally { ec.artifactExecution.pop(aei) }
        } finally {
            if (wasDisabled) ec.artifactExecution.disableAuthz()
            ec.artifactExecution.push("smokeTests", ArtifactExecutionInfo.AT_OTHER, ArtifactExecutionInfo.AUTHZA_ALL, false)
            ec.artifactExecution.setAnonymousAuthorizedAll()
        }
    }
}
