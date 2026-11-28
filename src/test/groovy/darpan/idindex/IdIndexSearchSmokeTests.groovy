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

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*
import static darpan.reconciliation.support.TestHelpers.asSystem

/** DAR-BE-374: search#RecordIds and get#IdIndexStatus through Moqui — the active tenant's rows only, view access enough. */
@SharedMoqui
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IdIndexSearchSmokeTests {

    private static final String MINE = "KREWE"
    private static final String OTHER = "IDX_SRCH_OTHER"
    private static final String F = "facade.IdIndexFacadeServices."
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "idindex-search")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        asSystem(ec) {
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
        setPermissions([])
        asSystem(ec) {
            ["darpan.idindex.RecordIdIndex", "darpan.idindex.RecordIdIndexCursor", "darpan.dataset.Dataset", "darpan.idindex.IdIndexSource"].each { ec.entity.find(it).deleteAll() }
            IdIndexer.upsertRows(ec, MINE, "SHOP", [r("5551", "id", "5551"), r("GOR123", "name", "5551")])
            IdIndexer.upsertRows(ec, OTHER, "SHOP_X", [r("GOR123", "name", "9999")])
        }
    }

    @Test
    void "another tenant's rows are never returned"() {
        Map out = call("search#RecordIds", [value: "#gor123"])
        assertFalse(out.hadError as boolean, out.errorText as String)
        assertEquals(["5551"], (out.search.records as List<Map>)*.recordKey)
        assertEquals("SHOP", out.search.records[0].connectionId)
    }

    @Test
    void "a view-only member can search"() {
        setPermissions([TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID])
        Map out = call("search#RecordIds", [value: "5551"])
        assertFalse(out.hadError as boolean, out.errorText as String)
        assertEquals(["5551"], (out.search.records as List<Map>)*.recordKey)
    }

    @Test
    void "status lists each kept copy of an indexed system: indexed, behind, or failed"() {
        asSystem(ec) {
            ec.entity.makeValue("darpan.idindex.IdIndexSource").setAll([systemEnumId: "SHOPIFY", systemCode: "SHOPIFY", recordKind: "ORDER",
                    idField: "id", isActive: "Y"]).createOrUpdate()
            [[datasetId: "D_OK", companyUserGroupId: MINE, currentVersion: "v2"], [datasetId: "D_BEHIND", companyUserGroupId: MINE, currentVersion: "v5"],
             [datasetId: "D_NEVER", companyUserGroupId: MINE, currentVersion: "v1"], [datasetId: "D_X", companyUserGroupId: OTHER, currentVersion: "v1"]].each {
                Map d = it + [systemEnumId: "SHOPIFY", sourceConfigId: "SHOP", optionsHash: it.datasetId, statusId: "DST_ACTIVE",
                        readRuleEnumId: "DRR_CHANGED_SINCE", idField: "id", stampField: "updatedAt", windowField: "createdAt"]
                ec.entity.makeDataLoader().xmlText("<entity-facade-xml type=\"seed\"><darpan.dataset.Dataset " +
                        d.collect { k, v -> "${k}=\"${v}\"" }.join(" ") + "/></entity-facade-xml>").dummyFks(true).load()
            }
            ec.entity.makeValue("darpan.idindex.RecordIdIndexCursor").setAll([companyUserGroupId: MINE, datasetId: "D_OK", indexedVersion: "v2", lastStatus: "OK"]).create()
            ec.entity.makeValue("darpan.idindex.RecordIdIndexCursor").setAll([companyUserGroupId: MINE, datasetId: "D_BEHIND", indexedVersion: "v4",
                    lastStatus: "FAILED", lastError: "Spark said no"]).create()
        }
        Map out = call("get#IdIndexStatus", [:])
        assertFalse(out.hadError as boolean, out.errorText as String)
        Map<String, Map> byDataset = (out.connections as List<Map>).collectEntries { [(it.datasetId): it] }
        assertEquals(["D_OK", "D_BEHIND", "D_NEVER"] as Set, byDataset.keySet(), "the other tenant's copy is not listed")
        assertEquals([false, false], [byDataset.D_OK.behind, byDataset.D_OK.stale])
        assertEquals([true, true, "Spark said no"], [byDataset.D_BEHIND.behind, byDataset.D_BEHIND.stale, byDataset.D_BEHIND.lastError])
        assertEquals([true, "NOT_INDEXED"], [byDataset.D_NEVER.behind, byDataset.D_NEVER.lastStatus])
        assertEquals(2L, out.rowCount as long)
    }

    private static Map r(String raw, String field, String key) {
        return [systemCode: "SHOPIFY", systemEnumId: "SHOPIFY", recordKind: "ORDER", recordKey: key, fieldPath: field, role: "OWN",
                idNorm: IdNormalizer.normalize(raw), idRaw: raw]
    }

    private Map call(String verbNoun, Map params) {
        ec.message.clearAll()
        Map out = (Map) ec.service.sync().name(F + verbNoun).parameters(params).call() ?: [:]
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
                        userId: ec.user.userId, permissionUserGroupId: g, fromDate: Timestamp.valueOf("2026-01-01 00:00:00")]).create()
            }
        }
    }
}
