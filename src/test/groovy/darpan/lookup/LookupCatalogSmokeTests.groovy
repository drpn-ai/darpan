package darpan.lookup

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

/** DAR-CLI-008 part 3: product lookup reads and links merged with a tenant's own (tenant wins by id; isActive N hides). */
@SharedMoqui
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LookupCatalogSmokeTests {

    private static final String MINE = "KREWE"   // ReconciliationSmokeTestSupport's TEST_COMPANY_USER_GROUP_ID
    private static final String OTHER = "LKP_OTHER"
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "lookup-catalog")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)   // signs in a KREWE editor, KREWE active
        asSystem {
            ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: OTHER, description: "Other tenant",
                    groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID]).createOrUpdate()
        }
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void reset() {
        asSystem {
            ["darpan.lookup.LookupRead", "darpan.lookup.TenantLookupRead", "darpan.lookup.LookupLink", "darpan.lookup.TenantLookupLink"]
                    .each { ec.entity.find(it).deleteAll() }
            read("darpan.lookup.LookupRead", [readId: "R_SO", systemCode: "NETSUITE", recordKind: "ORDER", keyField: "tranid",
                    queryTemplate: "SELECT id FROM transaction WHERE tranid = {value}", isFind: "Y", sequenceNum: 2])
            read("darpan.lookup.LookupRead", [readId: "R_OMS", systemCode: "OMS", recordKind: "ORDER", keyField: "orderId",
                    queryTemplate: 'query($value: String){ orders(query: $value){ edges { node { orderId } } } }', isFind: "Y", sequenceNum: 1])
            read("darpan.lookup.LookupLink", [linkId: "L1", fromReadId: "R_OMS", fromField: "orderId", toReadId: "R_SO"])
            read("darpan.lookup.LookupLink", [linkId: "L2", fromReadId: "R_SO", fromField: "externalid", toReadId: "R_OMS"])
        }
    }

    @Test
    void "a tenant row overrides the product row with the same id, and another tenant's rows are invisible"() {
        asSystem {
            read("darpan.lookup.TenantLookupRead", [companyUserGroupId: MINE, readId: "R_SO", keyField: "custbody_ext_order_id",
                    queryTemplate: "SELECT id FROM transaction WHERE custbody_ext_order_id = {value}"])
            read("darpan.lookup.TenantLookupRead", [companyUserGroupId: OTHER, readId: "R_OMS", isActive: "N"])
        }
        List<Map> reads = LookupCatalog.reads(ec, MINE)
        assertEquals(["R_OMS", "R_SO"], reads*.readId, "ordered by sequenceNum; OTHER's hide does not reach MINE")
        Map so = reads.find { it.readId == "R_SO" }
        assertEquals("custbody_ext_order_id", so.keyField)
        assertEquals("NETSUITE", so.systemCode, "a field the tenant row leaves blank keeps the product value")
        assertEquals("Y", so.isFind)
    }

    @Test
    void "an inactive tenant row hides the product row, and a tenant can add its own"() {
        asSystem {
            read("darpan.lookup.TenantLookupRead", [companyUserGroupId: MINE, readId: "R_OMS", isActive: "N"])
            read("darpan.lookup.TenantLookupRead", [companyUserGroupId: MINE, readId: "R_SHOP", systemCode: "SHOPIFY", recordKind: "ORDER",
                    keyField: "id", queryTemplate: 'query($value: ID!){ order(id: $value){ id } }', isFind: "Y", sequenceNum: 3])
        }
        assertEquals(["R_SO", "R_SHOP"], LookupCatalog.reads(ec, MINE)*.readId)
    }

    @Test
    void "links merge the same way"() {
        asSystem {
            read("darpan.lookup.TenantLookupLink", [companyUserGroupId: MINE, linkId: "L1", fromField: "externalId"])
            read("darpan.lookup.TenantLookupLink", [companyUserGroupId: MINE, linkId: "L2", isActive: "N"])
            read("darpan.lookup.TenantLookupLink", [companyUserGroupId: MINE, linkId: "L3", fromReadId: "R_SO", fromField: "custbody_rails_ext_order_id", toReadId: "R_OMS"])
            read("darpan.lookup.TenantLookupLink", [companyUserGroupId: OTHER, linkId: "L9", fromReadId: "R_SO", fromField: "x", toReadId: "R_OMS"])
        }
        List<Map> links = LookupCatalog.links(ec, MINE)
        assertEquals(["L1", "L3"], links*.linkId)
        Map l1 = links.find { it.linkId == "L1" }
        assertEquals("externalId", l1.fromField)
        assertEquals("R_OMS", l1.fromReadId, "a blank tenant field keeps the product value")
    }

    private void read(String entity, Map fields) { ec.entity.makeValue(entity).setAll(fields).createOrUpdate() }

    private <T> T asSystem(Closure<T> work) {
        boolean was = ec.artifactExecution.disableAuthz()
        try { return work.call() } finally { if (!was) ec.artifactExecution.enableAuthz() }
    }
}
