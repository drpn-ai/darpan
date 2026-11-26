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

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

/** DAR-CLI-008 part 3: lookup#Order wires the catalog, the tenant's connections and its findings; tenant edits need write access. */
@SharedMoqui
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LookupFacadeSmokeTests {

    private static final String MINE = "KREWE"
    private static final String F = "facade.LookupFacadeServices."
    private static final Timestamp FROM = Timestamp.valueOf("2026-01-01 00:00:00")
    private ExecutionContext ec
    private List<List> queried = []

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "lookup-facade")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml", "component://darpan/data/DarpanSystemSourceSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)   // signs in a KREWE editor, KREWE active
        asSystem {
            ec.entity.makeValue("darpan.audit.Audit").setAll([auditId: "LKP_MC", companyUserGroupId: MINE, auditCode: "month-close",
                    auditName: "Month close", periodEnumId: "AUDP_MONTH"]).createOrUpdate()
            ec.entity.makeValue("darpan.audit.AuditStatement").setAll([statementId: "LKP_R1", companyUserGroupId: MINE, auditId: "LKP_MC",
                    statementCode: "R1", categoryEnumId: "AUDC_RETURNS", weightEnumId: "AUDSW_FIRM", sequenceNum: 1L,
                    statementText: "R1 text", isActive: "Y"]).createOrUpdate()
            ec.entity.makeValue("darpan.audit.AuditCheck").setAll([checkId: "LKP_CHK", companyUserGroupId: MINE, checkCode: "orders-open",
                    label: "Open orders"]).createOrUpdate()
            ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID,
                    description: "View only", groupTypeEnumId: TenantAccessSupport.DARPAN_PERMISSION_GROUP_TYPE_ENUM_ID]).createOrUpdate()
            ec.entity.makeValue("darpan.hotwax.HotWaxOmsRestSourceConfig").setAll([omsRestSourceConfigId: "LKP_OMS", description: "KREWE OMS",
                    companyUserGroupId: MINE, baseUrl: "https://oms.lookup-test.invalid", isActive: "Y"]).createOrUpdate()
            ec.entity.makeValue("darpan.hotwax.HotWaxOmsRestSourceConfig").setAll([omsRestSourceConfigId: "LKP_OMS_OFF", description: "Old OMS",
                    companyUserGroupId: MINE, baseUrl: "https://old.lookup-test.invalid", isActive: "N"]).createOrUpdate()
        }
        OrderLookup.liveQueryHook = { String service, Map params ->
            queried << [service, params]
            return [ok: true, data: [orders: [edges: [[node: [orderId: "OMS100", externalId: params.variables?.value, statusId: "ORDER_APPROVED"]]]]]]
        }
    }

    @AfterAll
    void cleanup() {
        OrderLookup.resetForTests()
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void reset() {
        queried.clear()
        asSystem {
            ["darpan.lookup.LookupRead", "darpan.lookup.TenantLookupRead", "darpan.lookup.LookupLink", "darpan.lookup.TenantLookupLink"].each { ec.entity.find(it).deleteAll() }
            ec.entity.makeValue("darpan.lookup.LookupRead").setAll([readId: "LKP_OMS_BY_EXT", systemCode: "OMS", recordKind: "ORDER", keyField: "externalId",
                    queryTemplate: 'query($value: String){ orders(query: $value){ edges { node { orderId externalId statusId } } } }',
                    valueFormat: "externalId:%s orderTypeId:SALES_ORDER", recordsAt: "orders.edges[*].node", fieldsDefault: "orderId,statusId",
                    isFind: "Y", sequenceNum: 1]).create()
            // an order's id links onward (to NetSuite, not installed here); the link still names it as an id for findings
            ec.entity.makeValue("darpan.lookup.LookupLink").setAll([linkId: "LKP_L1", fromReadId: "LKP_OMS_BY_EXT", fromField: "orderId",
                    toReadId: "LKP_NS_BY_EXT"]).create()
            ec.entity.makeValue("darpan.audit.AuditFinding").setAll([findingId: "F-LKP-1", companyUserGroupId: MINE, statementId: "LKP_R1", checkId: "LKP_CHK",
                    recordKey: "OMS100", period: "2026-11", stateEnumId: "AUDFS_OPEN", headline: "Order OMS100 is open",
                    firstFoundDate: java.sql.Date.valueOf("2026-11-20"), lastSeenDate: java.sql.Date.valueOf("2026-11-25")]).createOrUpdate()
        }
        setPermissions([TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID])
    }

    @Test
    void "lookup reads every active connection through the fresh-query service and attaches the order's findings"() {
        Map out = call("lookup#Order", [value: "5550001"])
        assertFalse(out.hadError as boolean, out.errorText as String)
        Map lookup = out.lookup as Map
        assertEquals([["facade.HotWaxOmsFacadeServices.run#OmsGraphqlAgentQuery",
                       [omsRestSourceConfigId: "LKP_OMS", document: 'query($value: String){ orders(query: $value){ edges { node { orderId externalId statusId } } } }',
                        variables: [value: "externalId:5550001 orderTypeId:SALES_ORDER"]]]], queried, "the inactive connection is not read")
        List<Map> records = lookup.records as List<Map>
        assertEquals(1, records.size())
        assertEquals([orderId: "OMS100", statusId: "ORDER_APPROVED"], records[0].record, "default fields only")
        assertEquals("KREWE OMS", records[0].connectionLabel)
        assertEquals(["F-LKP-1"], (lookup.findings as List<Map>)*.findingId, "found by the record's order id")
    }

    @Test
    void "a view-only member is told up front that a lookup queries connected systems, and cannot edit the tenant's reads"() {
        setPermissions([TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID])
        Map look = call("lookup#Order", [value: "5550001"])
        assertTrue(look.hadError as boolean, "the fresh-query services refuse a view member; say so once, not as 'not found'")
        assertTrue((look.errorText as String).contains("user, editor or admin"), look.errorText as String)
        assertEquals([], queried, "nothing was read")
        Map r = call("save#TenantLookupRead", [readId: "LKP_OMS_BY_EXT", keyField: "orderName"])
        assertTrue(r.hadError as boolean)
        assertTrue((r.errorText as String).contains("view access"), r.errorText as String)
    }

    @Test
    void "a tenant read is saved only when its template is read-only"() {
        Map bad = call("save#TenantLookupRead", [readId: "MY_SO", systemCode: "NETSUITE", recordKind: "ORDER", keyField: "custbody_x",
                queryTemplate: "UPDATE transaction SET memo = 'x' WHERE id = {value}", isFind: "Y"])
        assertTrue(bad.hadError as boolean)
        assertTrue((bad.errorText as String).contains("read-only"), bad.errorText as String)
        Map badGql = call("save#TenantLookupRead", [readId: "MY_SHOP", systemCode: "SHOPIFY", recordKind: "ORDER", keyField: "id",
                queryTemplate: 'mutation { orderClose(input: {id: "1"}) { order { id } } }'])
        assertTrue(badGql.hadError as boolean)
        Map ok = call("save#TenantLookupRead", [readId: "MY_SO", systemCode: "NETSUITE", recordKind: "ORDER", keyField: "custbody_x",
                queryTemplate: "SELECT id, tranid FROM transaction WHERE custbody_x = {value}", isFind: "Y"])
        assertFalse(ok.hadError as boolean, ok.errorText as String)
        assertEquals(["LKP_OMS_BY_EXT", "MY_SO"] as Set, LookupCatalog.reads(ec, MINE)*.readId as Set)
        assertFalse(call("delete#TenantLookupRead", [readId: "MY_SO"]).hadError as boolean)
        assertEquals(["LKP_OMS_BY_EXT"], LookupCatalog.reads(ec, MINE)*.readId)
        assertFalse(call("save#TenantLookupLink", [linkId: "MY_L", fromReadId: "MY_SO", fromField: "custbody_x", toReadId: "LKP_OMS_BY_EXT"]).hadError as boolean)
        assertEquals(["LKP_L1", "MY_L"], LookupCatalog.links(ec, MINE)*.linkId)
        assertFalse(call("delete#TenantLookupLink", [linkId: "MY_L"]).hadError as boolean)
        assertEquals(["LKP_L1"], LookupCatalog.links(ec, MINE)*.linkId)
    }

    @Test
    void "a tenant read with a format pattern, an unsafe shape or an oversized template is refused"() {
        Map fmt = call("save#TenantLookupRead", [readId: "MY_F", systemCode: "SHOPIFY", recordKind: "ORDER", keyField: "id",
                queryTemplate: 'query($value: ID!){ order(id: $value){ id } }', valueFormat: "%1000000000s"])
        assertTrue(fmt.hadError as boolean)
        Map big = call("save#TenantLookupRead", [readId: "MY_B", systemCode: "NETSUITE", recordKind: "ORDER", keyField: "x",
                queryTemplate: "SELECT id FROM transaction WHERE x = {value} " + ("AND 1=1 " * 3000)])
        assertTrue(big.hadError as boolean)
        Map noValue = call("save#TenantLookupRead", [readId: "MY_N", systemCode: "NETSUITE", recordKind: "ORDER", keyField: "x",
                queryTemplate: "SELECT id FROM transaction WHERE x = '1'"])
        assertTrue(noValue.hadError as boolean, "a SuiteQL template must take {value}")
    }

    @Test
    void "a line-level finding of the order is attached too"() {
        asSystem {
            ec.entity.makeValue("darpan.audit.AuditFinding").setAll([findingId: "F-LKP-2", companyUserGroupId: MINE, statementId: "LKP_R1", checkId: "LKP_CHK",
                    recordKey: "OMS100/01", period: "2026-11", stateEnumId: "AUDFS_OPEN", headline: "Line OMS100/01 is open",
                    firstFoundDate: java.sql.Date.valueOf("2026-11-20"), lastSeenDate: java.sql.Date.valueOf("2026-11-24")]).createOrUpdate()
        }
        Map out = call("lookup#Order", [value: "5550001"])
        assertTrue((((out.lookup as Map).findings as List<Map>)*.findingId as Set).containsAll(["F-LKP-1", "F-LKP-2"]))
    }

    @Test
    void "a finding keyed by another system's id is attached through the records it names"() {
        asSystem {
            ec.entity.makeValue("darpan.audit.AuditFinding").setAll([findingId: "F-LKP-3", companyUserGroupId: MINE, statementId: "LKP_R1", checkId: "LKP_CHK",
                    recordKey: "18979108061315|50861592150147", period: "2026-11", stateEnumId: "AUDFS_OPEN", headline: "Item OMS100/01 is completed and not billed",
                    firstFoundDate: java.sql.Date.valueOf("2026-11-03"), lastSeenDate: java.sql.Date.valueOf("2026-11-24")]).createOrUpdate()
            ec.entity.makeValue("darpan.audit.AuditFindingRecord").setAll([findingId: "F-LKP-3", recordSeqId: "01", companyUserGroupId: MINE,
                    systemEnumId: "OMS_GQL", recordType: "orderItem", recordId: "OMS100/01"]).createOrUpdate()
        }
        Map out = call("lookup#Order", [value: "5550001"])
        assertTrue("F-LKP-3" in ((out.lookup as Map).findings as List<Map>)*.findingId, "${(out.lookup as Map).findings}")
    }

    @Test
    void "a NetSuite read with a value format is shape-checked on the value as given and bound formatted"() {
        asSystem {
            ec.entity.makeValue("darpan.lookup.LookupRead").setAll([readId: "LKP_NS_BY_SHOPIFY", systemCode: "NETSUITE", recordKind: "ORDER", keyField: "externalid",
                    queryTemplate: "SELECT id, tranid FROM transaction WHERE externalid = {value}", valueShape: "[0-9]{6,20}", valueFormat: "SO_%s",
                    isFind: "Y", sequenceNum: 2]).create()
            ec.entity.makeValue("darpan.reconciliation.NsAuthConfig").setAll([nsAuthConfigId: "LKP_NS", companyUserGroupId: MINE, description: "KREWE NetSuite",
                    isActive: "Y"]).createOrUpdate()
        }
        Map out = call("lookup#Order", [value: "5550001"])
        assertFalse(out.hadError as boolean, out.errorText as String)
        List ns = queried.findAll { it[0] == "facade.NetSuiteQueryFacadeServices.run#NsSuiteQlAgentQuery" }
        assertEquals(1, ns.size(), "skipped: ${(out.lookup as Map).skipped}")
        assertEquals("SELECT id, tranid FROM transaction WHERE externalid = 'SO_5550001'", (ns[0][1] as Map).sql)
    }

    @Test
    void "a new tenant read must say its system, kind, key and template, and its shape must compile"() {
        [[readId: "MY_X", queryTemplate: "SELECT id FROM transaction WHERE x = {value}"],
         [readId: "MY_X", systemCode: "NETSUITE", recordKind: "ORDER", keyField: "x"],
         [readId: "MY_X", systemCode: "NETSUITE", recordKind: "ORDER", keyField: "x", queryTemplate: "SELECT id FROM transaction WHERE x = {value}", valueShape: "[0-9"],
         [readId: "MY_X", systemCode: "OMS", recordKind: "ORDER", keyField: "x", queryTemplate: 'query($value: String){ orders(query: $value){ edges { node { orderId } } } }']].each { Map p ->
            assertTrue(this.call("save#TenantLookupRead", p).hadError as boolean, "refused: ${p}")
        }
        assertFalse(call("save#TenantLookupRead", [readId: "LKP_OMS_BY_EXT", keyField: "orderName"]).hadError as boolean,
                "an override of a product read may change one field")
    }

    @Test
    void "the catalog lists reads without their templates, and links"() {
        Map cat = call("get#LookupCatalog", [:])
        Map read = (cat.reads as List<Map>)[0]
        assertEquals("LKP_OMS_BY_EXT", read.readId)
        assertNull(read.queryTemplate)
        assertEquals(["LKP_L1"], (cat.links as List<Map>)*.linkId)
    }

    private Map call(String verbNoun, Map params) {
        ec.message.clearAll()
        Map out = (Map) ec.service.sync().name(F + verbNoun).parameters(params).call() ?: [:]
        Map result = out + [hadError: ec.message.hasError(), errorText: ec.message.errorsString]
        ec.message.clearAll()
        return result
    }

    private void setPermissions(List<String> groups) {
        asSystem {
            ec.entity.find(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME).condition("tenantUserGroupId", MINE)
                    .condition("userId", ec.user.userId).deleteAll()
            groups.each { String g ->
                ec.entity.makeValue(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME).setAll([tenantUserGroupId: MINE,
                        userId: ec.user.userId, permissionUserGroupId: g, fromDate: FROM]).create()
            }
        }
    }

    private <T> T asSystem(Closure<T> work) {
        boolean was = ec.artifactExecution.disableAuthz()
        try { return work.call() } finally { if (!was) ec.artifactExecution.enableAuthz() }
    }
}
