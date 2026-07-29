package darpan.reconciliation.notification

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantChatSpaceEntitySmokeTests {
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "tenant-chat-space-entity-smoke")
        // ReconciliationRunResult.statusEnumId defaults to AUT_STAT_SUCCESS and FKs to
        // moqui.basic.Enumeration; the fresh test DB has no seed data loaded, so pull it in
        // explicitly (same convention as GenericReconciliationServiceSmokeTests).
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml")
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @Test
    void chatSpaceAndSubscriptionRoundTrip() {
        ec.artifactExecution.disableAuthz()

        // FK prerequisites: TenantChatSpace.companyUserGroupId -> UserGroup,
        // ReconciliationRunNotifySubscription.userId -> UserAccount and
        // .reconciliationRunResultId -> ReconciliationRunResult.
        ec.entity.makeValue("moqui.security.UserGroup")
                .setAll([userGroupId: "TEN_TEST", description: "Smoke-test tenant"])
                .create()
        ec.entity.makeValue("moqui.security.UserAccount")
                .setAll([userId: "USER_A", username: "user.a", currentPassword: ""])
                .create()
        ec.entity.makeValue("darpan.reconciliation.ReconciliationRunResult")
                .setAll([reconciliationRunResultId: "RUNRES_1"])
                .create()

        def created = ec.service.sync().name("create#darpan.reconciliation.TenantChatSpace")
                .parameters([companyUserGroupId: "TEN_TEST", spaceName: "Ops space",
                             googleChatWebhookUrl: "https://chat.googleapis.com/v1/spaces/AAA111/messages?key=k&token=t",
                             isActive: "Y"]).call()
        assertNotNull(created.chatSpaceId)
        def row = ec.entity.find("darpan.reconciliation.TenantChatSpace")
                .condition("chatSpaceId", created.chatSpaceId).one()
        assertEquals("Ops space", row.spaceName)

        ec.service.sync().name("create#darpan.reconciliation.ReconciliationRunNotifySubscription")
                .parameters([reconciliationRunResultId: "RUNRES_1", userId: "USER_A",
                             chatSpaceId: created.chatSpaceId, subscribedDate: ec.user.nowTimestamp]).call()
        assertNotNull(ec.entity.find("darpan.reconciliation.ReconciliationRunNotifySubscription")
                .condition([reconciliationRunResultId: "RUNRES_1", userId: "USER_A"]).one())
    }
}
