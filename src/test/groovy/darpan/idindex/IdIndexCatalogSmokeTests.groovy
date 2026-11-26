package darpan.idindex

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import darpan.reconciliation.support.SharedMoqui
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import static org.junit.jupiter.api.Assertions.*

/** DAR-BE-374: the index's field catalog through Moqui — tenant rows, tenant isolation, and the save guard. */
@SharedMoqui
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdIndexCatalogSmokeTests {

    private static final String MINE = "KREWE"   // ReconciliationSmokeTestSupport's TEST_COMPANY_USER_GROUP_ID
    private static final String OTHER = "IDX_OTHER"
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "idindex-catalog")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)   // signs in a KREWE editor, KREWE active
        asSystem {
            ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: OTHER, description: "Other tenant",
                    groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID]).createOrUpdate()
            ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID,
                    description: "View only", groupTypeEnumId: TenantAccessSupport.DARPAN_PERMISSION_GROUP_TYPE_ENUM_ID]).createOrUpdate()
        }
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void reset() {
        asSystem {
            ["darpan.idindex.IdIndexSource", "darpan.idindex.IdIndexField", "darpan.idindex.TenantIdIndexField", "darpan.idindex.RecordIdIndexCursor"].each { ec.entity.find(it).deleteAll() }
            row("darpan.idindex.IdIndexSource", [systemEnumId: "SHOPIFY", systemCode: "SHOPIFY", recordKind: "ORDER", idField: "id", isActive: "Y"])
            row("darpan.idindex.IdIndexSource", [systemEnumId: "OMS_GQL", systemCode: "OMS", recordKind: "ORDER", idField: "orderId", isActive: "Y"])
            row("darpan.idindex.IdIndexField", [systemEnumId: "SHOPIFY", fieldId: "ORDER_NAME", fieldPath: "name", role: "OWN"])
            row("darpan.idindex.IdIndexField", [systemEnumId: "SHOPIFY", fieldId: "ORDER_ID", fieldPath: "id", role: "OWN"])
        }
        ec.message.clearAll()
    }

    @Test
    void "fields merge the tenant's rows, and another tenant's rows are invisible"() {
        asSystem {
            row("darpan.idindex.TenantIdIndexField", [companyUserGroupId: MINE, systemEnumId: "SHOPIFY", fieldId: "ORDER_NAME", isActive: "N"])
            row("darpan.idindex.TenantIdIndexField", [companyUserGroupId: OTHER, systemEnumId: "SHOPIFY", fieldId: "ORDER_ID", isActive: "N"])
            row("darpan.idindex.TenantIdIndexField", [companyUserGroupId: OTHER, systemEnumId: "SHOPIFY", fieldId: "OTHER_ONLY", fieldPath: "x", role: "OWN"])
        }
        assertEquals(["ORDER_ID"], IdIndexCatalog.fields(ec, MINE, "SHOPIFY")*.fieldId)
        assertEquals(["OMS_GQL", "SHOPIFY"], IdIndexCatalog.sources(ec)*.systemEnumId)
    }

    @Test
    void "an editor saves a tenant field, and a customer field is refused"() {
        ec.service.sync().name("facade.IdIndexFacadeServices.save#TenantIdIndexField")
                .parameters([systemEnumId: "SHOPIFY", fieldId: "RAILS_EXT", fieldPath: "custom_attributes[*].value", role: "REF"]).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        assertEquals(["ORDER_ID", "ORDER_NAME", "RAILS_EXT"], IdIndexCatalog.fields(ec, MINE, "SHOPIFY")*.fieldId)

        ec.service.sync().name("facade.IdIndexFacadeServices.save#TenantIdIndexField")
                .parameters([systemEnumId: "SHOPIFY", fieldId: "MAIL", fieldPath: "customer.email", role: "OWN"]).call()
        assertTrue(ec.message.hasError())
        assertTrue(ec.message.errorsString.contains("customer"), ec.message.errorsString)
        ec.message.clearAll()
        assertFalse("MAIL" in IdIndexCatalog.fields(ec, MINE, "SHOPIFY")*.fieldId)

        ec.service.sync().name("facade.IdIndexFacadeServices.save#TenantIdIndexField")
                .parameters([systemEnumId: "SHOPIFY", fieldId: "SNEAK", fieldPath: '$..e_mail', role: "OWN"]).call()
        assertTrue(ec.message.hasError())
        ec.message.clearAll()
        assertFalse("SNEAK" in IdIndexCatalog.fields(ec, MINE, "SHOPIFY")*.fieldId)
    }

    @Test
    void "a new field needs a path and a role, and the system must be one the index reads"() {
        Map noRole = call([systemEnumId: "SHOPIFY", fieldId: "NO_ROLE", fieldPath: "x"])
        assertTrue(noRole.hadError as boolean)
        assertTrue((noRole.errorText as String).contains("role"), noRole.errorText as String)
        Map unknown = call([systemEnumId: "NOT_A_SOURCE", fieldId: "X", fieldPath: "x", role: "OWN"])
        assertTrue((unknown.errorText as String).contains("does not read"), unknown.errorText as String)
        assertFalse(call([systemEnumId: "SHOPIFY", fieldId: "ORDER_NAME", fieldPath: "name2"]).hadError as boolean,
                "an override of a product field keeps the product's role")
    }

    @Test
    void "a field change marks that system's kept copies for re-indexing"() {
        asSystem {
            row("darpan.idindex.RecordIdIndexCursor", [companyUserGroupId: MINE, datasetId: "D1", systemEnumId: "SHOPIFY", indexedVersion: "v7"])
            row("darpan.idindex.RecordIdIndexCursor", [companyUserGroupId: MINE, datasetId: "D2", systemEnumId: "OMS_GQL", indexedVersion: "v3"])
        }
        assertFalse(call([systemEnumId: "SHOPIFY", fieldId: "NEW", fieldPath: "x", role: "OWN"]).hadError as boolean)
        Map<String, String> versions = asSystem { ec.entity.find("darpan.idindex.RecordIdIndexCursor").condition("companyUserGroupId", MINE).list()
                .collectEntries { [(it.datasetId): it.indexedVersion] } }
        assertEquals([D1: null, D2: "v3"], versions)
    }

    private Map call(Map params) {
        ec.message.clearAll()
        ec.service.sync().name("facade.IdIndexFacadeServices.save#TenantIdIndexField").parameters(params).call()
        Map out = [hadError: ec.message.hasError(), errorText: ec.message.errorsString]
        ec.message.clearAll()
        return out
    }

    @Test
    void "a view-only member cannot save"() {
        setPermissions([TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID])
        try {
            ec.service.sync().name("facade.IdIndexFacadeServices.save#TenantIdIndexField")
                    .parameters([systemEnumId: "SHOPIFY", fieldId: "X", fieldPath: "x", role: "OWN"]).call()
            assertTrue(ec.message.hasError())
            assertTrue(ec.message.errorsString.contains("view access"), ec.message.errorsString)
            ec.message.clearAll()
        } finally {
            setPermissions([])
        }
        assertFalse("X" in IdIndexCatalog.fields(ec, MINE, "SHOPIFY")*.fieldId)
    }

    private void setPermissions(List<String> groups) {
        asSystem {
            ec.entity.find(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME).condition("tenantUserGroupId", MINE)
                    .condition("userId", ec.user.userId).deleteAll()
            groups.each { String g ->
                ec.entity.makeValue(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME).setAll([tenantUserGroupId: MINE,
                        userId: ec.user.userId, permissionUserGroupId: g, fromDate: java.sql.Timestamp.valueOf("2026-01-01 00:00:00")]).create()
            }
        }
    }

    private void row(String entity, Map fields) { ec.entity.makeValue(entity).setAll(fields).createOrUpdate() }

    private <T> T asSystem(Closure<T> work) {
        boolean was = ec.artifactExecution.disableAuthz()
        try { return work.call() } finally { if (!was) ec.artifactExecution.enableAuthz() }
    }
}
