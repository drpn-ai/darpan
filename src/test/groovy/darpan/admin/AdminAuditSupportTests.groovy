package darpan.admin

import darpan.admin.AdminTestStubs.ServiceFacadeStub
import darpan.admin.AdminTestStubs.UserStub
import org.junit.jupiter.api.Test

import static darpan.admin.AdminTestStubs.executionContext
import static org.junit.jupiter.api.Assertions.assertEquals

class AdminAuditSupportTests {

    @Test
    void recordCreatesAuditRowWithOperatorAndTarget() {
        def service = new ServiceFacadeStub()
        def ec = executionContext(user: new UserStub(userId: "ADMIN_USER", username: "darpan.admin"), service: service)

        AdminAuditSupport.record(ec, "admin.TenantAdminServices.create#Tenant", "Tenant", "KREWE", "Created tenant KREWE.")

        assertEquals(1, service.calls.size())
        def call = service.calls[0]
        assertEquals("create#darpan.admin.AdminAuditLog", call.serviceName)
        assertEquals("ADMIN_USER", call.parametersMap.adminUserId)
        assertEquals("admin.TenantAdminServices.create#Tenant", call.parametersMap.serviceName)
        assertEquals("Tenant", call.parametersMap.targetType)
        assertEquals("KREWE", call.parametersMap.targetId)
        assertEquals("Created tenant KREWE.", call.parametersMap.detailText)
        assertEquals(false, call.ignoredPreviousError, "a mutation's row rides its transaction: an earlier error stops it")
    }

    // DAR-BE-265: a scheduled run (a migration, retention) runs as no one and may have left errors; its row is still written.
    @Test
    void aRunWithNoUserIsRecordedAsTheSystemUserEvenAfterAnError() {
        def service = new ServiceFacadeStub()
        def ec = executionContext(user: new UserStub(), service: service)

        AdminAuditSupport.recordRun(ec, "admin.MigrationAdminServices.run#Migration", "Migration", "M1", "Run failed: boom.")

        def call = service.calls[0]
        assertEquals(AdminAuditSupport.SYSTEM_USER_ID, call.parametersMap.adminUserId)
        assertEquals(true, call.ignoredPreviousError)
    }
}
