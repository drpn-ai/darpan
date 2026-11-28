package darpan.lookup

import darpan.facade.common.TenantAccessSupport
import darpan.idindex.IdIndexer
import darpan.idindex.IdNormalizer
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
import static darpan.reconciliation.support.TestHelpers.asSystem

/**
 * DAR-BE-374: lookup#Order answers from what Darpan already holds — the ID index and the tenant's open findings — and calls no
 * connected system. View access is enough.
 */
@SharedMoqui
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LookupFacadeSmokeTests {

    private static final String MINE = "KREWE"
    private static final Timestamp FROM = Timestamp.valueOf("2026-01-01 00:00:00")
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "lookup-facade")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)   // signs in a KREWE editor, KREWE active
        asSystem(ec) {
            ec.entity.makeValue("darpan.audit.Audit").setAll([auditId: "LKP_MC", companyUserGroupId: MINE, auditCode: "month-close",
                    auditName: "Month close", periodEnumId: "AUDP_MONTH"]).createOrUpdate()
            ec.entity.makeValue("darpan.audit.AuditStatement").setAll([statementId: "LKP_R1", companyUserGroupId: MINE, auditId: "LKP_MC",
                    statementCode: "R1", categoryEnumId: "AUDC_RETURNS", weightEnumId: "AUDSW_FIRM", sequenceNum: 1L,
                    statementText: "R1 text", isActive: "Y"]).createOrUpdate()
            ec.entity.makeValue("darpan.audit.AuditCheck").setAll([checkId: "LKP_CHK", companyUserGroupId: MINE, checkCode: "orders-open",
                    label: "Open orders"]).createOrUpdate()
            ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID,
                    description: "View only", groupTypeEnumId: TenantAccessSupport.DARPAN_PERMISSION_GROUP_TYPE_ENUM_ID]).createOrUpdate()
        }
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void reset() {
        setPermissions([])
        asSystem(ec) {
            ["darpan.idindex.RecordIdIndex", "darpan.audit.AuditFinding"].each { ec.entity.find(it).deleteAll() }
            IdIndexer.upsertRows(ec, MINE, "SHOP", [row("SHOPIFY", "ORDER", "5551", "id", "5551"), row("SHOPIFY", "ORDER", "5551", "name", "#GOR1")])
            IdIndexer.upsertRows(ec, MINE, "OMS", [row("OMS", "ORDER", "OMS100", "orderId", "OMS100"),
                                                   row("OMS", "ORDER", "OMS100", "externalId", "5551", "REF")])
            finding("F-LKP-1", "OMS100")
            finding("F-LKP-2", "RMA77")
        }
    }

    @Test
    void "an indexed id comes back with every record it connects to and their open findings"() {
        Map look = call([value: "#gor1"])
        assertFalse(look.hadError as boolean, look.errorText as String)
        Map lookup = look.lookup as Map
        assertEquals(["OMS:OMS100", "SHOPIFY:5551"] as Set, (lookup.records as List<Map>).collect { "${it.systemCode}:${it.recordKey}".toString() } as Set)
        assertEquals(["F-LKP-1"], (lookup.findings as List<Map>)*.findingId, "found through the HotWax order the index connected")
        assertFalse(lookup.notSeen as boolean)
    }

    @Test
    void "an id only a finding names still finds the finding"() {
        Map lookup = call([value: "RMA77"]).lookup as Map
        assertEquals([], lookup.records)
        assertEquals(["F-LKP-2"], (lookup.findings as List<Map>)*.findingId)
        assertFalse(lookup.notSeen as boolean)
    }

    @Test
    void "an id no Darpan read has seen is notSeen"() {
        Map lookup = call([value: "NEVER-SEEN-1"]).lookup as Map
        assertEquals([[], [], true], [lookup.records, lookup.findings, lookup.notSeen])
    }

    @Test
    void "a view-only member gets the same answer: nothing is read from a connected system"() {
        setPermissions([TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID])
        Map look = call([value: "5551"])
        assertFalse(look.hadError as boolean, look.errorText as String)
        assertEquals(2, ((look.lookup as Map).records as List).size())
    }

    private static Map row(String sys, String kind, String key, String field, String raw, String role = "OWN") {
        return [systemCode: sys, systemEnumId: sys, recordKind: kind, recordKey: key, fieldPath: field, role: role,
                idNorm: IdNormalizer.normalize(raw), idRaw: raw]
    }

    private void finding(String id, String recordKey) {
        ec.entity.makeValue("darpan.audit.AuditFinding").setAll([findingId: id, companyUserGroupId: MINE, statementId: "LKP_R1", checkId: "LKP_CHK",
                recordKey: recordKey, period: "2026-11", stateEnumId: "AUDFS_OPEN", headline: "Finding on ${recordKey}".toString(),
                firstFoundDate: java.sql.Date.valueOf("2026-11-20"), lastSeenDate: java.sql.Date.valueOf("2026-11-25")]).createOrUpdate()
    }

    private Map call(Map params) {
        ec.message.clearAll()
        Map out = (Map) ec.service.sync().name("facade.LookupFacadeServices.lookup#Order").parameters(params).call() ?: [:]
        Map result = out + [hadError: ec.message.hasError(), errorText: ec.message.errorsString]
        ec.message.clearAll()
        return result
    }

    private void setPermissions(List<String> groups) {
        asSystem(ec) {
            ec.entity.find(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME).condition("tenantUserGroupId", MINE)
                    .condition("userId", ec.user.userId).deleteAll()
            groups.each { String g ->
                ec.entity.makeValue(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME).setAll([tenantUserGroupId: MINE,
                        userId: ec.user.userId, permissionUserGroupId: g, fromDate: FROM]).create()
            }
        }
    }
}
