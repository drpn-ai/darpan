package darpan.admin

import darpan.admin.AdminTestStubs.FinderStub
import darpan.admin.AdminTestStubs.MessageFacadeStub
import darpan.admin.AdminTestStubs.ServiceFacadeStub
import darpan.facade.common.TenantAccessSupport
import org.junit.jupiter.api.Test

import static darpan.admin.AdminTestStubs.plainUserEc
import static darpan.admin.AdminTestStubs.superAdminEc
import static org.junit.jupiter.api.Assertions.*

class AdminTenantSupportTests {

    private static def adminEc(Map finders, ServiceFacadeStub service = new ServiceFacadeStub(),
                               MessageFacadeStub message = new MessageFacadeStub()) {
        return superAdminEc(finders, [service: service, message: message])
    }

    @Test
    void createTenantCreatesGroupSettingAndAudit() {
        def service = new ServiceFacadeStub()
        def ec = adminEc(["moqui.security.UserGroup": new FinderStub(oneResult: null)], service)

        Map result = AdminTenantSupport.createTenant(ec, "KREWE", "Krewe", "America/Chicago")

        assertEquals("KREWE", result.tenantUserGroupId)
        List names = service.calls*.serviceName
        assertTrue(names.contains("create#moqui.security.UserGroup"))
        assertTrue(names.contains("create#darpan.auth.TenantSetting"))
        assertTrue(names.contains("create#darpan.admin.AdminAuditLog"))
        def groupCall = service.calls.find { it.serviceName == "create#moqui.security.UserGroup" }
        assertEquals("KREWE", groupCall.parametersMap.userGroupId)
        assertEquals("Krewe", groupCall.parametersMap.description)
        assertEquals(TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID, groupCall.parametersMap.groupTypeEnumId)
    }

    @Test
    void createTenantRejectsMalformedId() {
        def service = new ServiceFacadeStub()
        def message = new MessageFacadeStub()
        def ec = adminEc([:], service, message)

        assertNull(AdminTenantSupport.createTenant(ec, "bad id!", "Bad", null))
        assertTrue(message.hasError())
        assertEquals(0, service.calls.size(), "no writes on validation failure")
    }

    @Test
    void createTenantRejectsDuplicateId() {
        def service = new ServiceFacadeStub()
        def message = new MessageFacadeStub()
        def ec = adminEc(["moqui.security.UserGroup":
                new FinderStub(oneResult: [userGroupId: "KREWE", description: "Krewe"])], service, message)

        assertNull(AdminTenantSupport.createTenant(ec, "KREWE", "Krewe again", null))
        assertTrue(message.errors.any { it.contains("already exists") })
        assertEquals(0, service.calls.size())
    }

    @Test
    void createTenantDeniedForPlainUserWritesNothing() {
        def service = new ServiceFacadeStub()
        def message = new MessageFacadeStub()
        def ec = plainUserEc(service: service, message: message)

        assertNull(AdminTenantSupport.createTenant(ec, "KREWE", "Krewe", null))
        assertTrue(message.hasError())
        assertEquals(0, service.calls.size())
    }

    // update#Tenant, deactivate#Tenant / reactivate#Tenant, get#TenantList and get#TenantDetail are
    // XML actions now; their coverage lives in AdminServicesSmokeTests, through the real service engine.

    @Test
    void createTenantRejectsAnInvalidTimeZone() {
        // Same hole update#Tenant guards against, reachable through the create wizard instead.
        def service = new ServiceFacadeStub()
        def message = new MessageFacadeStub()
        def ec = adminEc(["moqui.security.UserGroup": new FinderStub(oneResult: null)], service, message)

        assertEquals(null, AdminTenantSupport.createTenant(ec, "KREWE", "Krewe", "Not/AZone"))
        assertTrue(message.hasError())
        assertEquals(0, service.calls.size())
    }
}
