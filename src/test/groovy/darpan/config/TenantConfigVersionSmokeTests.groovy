package darpan.config

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-160 phase A. facade.TenantConfigFacadeServices: the ACTIVE tenant's config (audits, statements, checks, runs,
 * queries, conclusion codes, connections) kept as versions. A snapshot writes a version only when the config changed,
 * chains it to the one before, never carries a secret, and another tenant's version reads as not found.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantConfigVersionSmokeTests {

    private static final String FACADE = "facade.TenantConfigFacadeServices"
    private static final String VERSION = "darpan.config.TenantConfigVersion"
    private static final String MINE = "KREWE"           // seedCompanyScope's tenant
    private static final String USER = "TEST_CUSTOMER_USER"   // and its editor
    private static final Timestamp FROM = Timestamp.valueOf("2026-04-21 00:00:00")
    private static final String OTHER = "TCV_OTHER"
    private static final String PASSWORD = "S3cret-Tcv-Pass"
    private static final String STATEMENT_TEXT = "Every invoice carries the Etail id."

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "tenant-config-version")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/DarpanSystemSourceSeedData.xml", "component://darpan/data/AutomationSeedData.xml",
                "component://darpan/data/SourceSystemConnectorSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)   // signs in a KREWE editor, KREWE active
        asSystem {
            upsert("moqui.security.UserGroup", [userGroupId: OTHER, description: "Other tenant",
                    groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
            // seedCompanyScope makes only the editor permission group.
            upsert("moqui.security.UserGroup", [userGroupId: TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID,
                    description: "Can view tenant-scoped Darpan data", groupTypeEnumId: "UgtDarpanPermission"])
            [MINE, OTHER].each { String t ->
                upsert("darpan.audit.Audit", [auditId: "${t}_MC".toString(), companyUserGroupId: t, auditCode: "month-close",
                        auditName: "Month close", periodEnumId: "AUDP_MONTH"])
                upsert("darpan.audit.AuditStatement", [statementId: "${t}_R2".toString(), companyUserGroupId: t, auditId: "${t}_MC".toString(),
                        statementCode: "R2", categoryEnumId: "AUDC_ORDERS", weightEnumId: "AUDSW_FIRM", sequenceNum: 2L,
                        statementText: STATEMENT_TEXT])
                upsert("darpan.audit.AuditCheck", [checkId: "${t}_INV".toString(), companyUserGroupId: t,
                        checkCode: "invoices-without-etail-id", label: "Invoices with no Etail id", graceDays: 1L])
                upsert("darpan.audit.AuditStatementCheck", [checkId: "${t}_INV".toString(), statementId: "${t}_R2".toString(),
                        companyUserGroupId: t, sequenceNum: 1L])
            }
            upsert("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "TCV_NS_AUTH", description: "krewe netsuite",
                    companyUserGroupId: MINE, authType: "BASIC", username: "svc-user", password: PASSWORD])
        }
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void reset() {
        ec.message.clearErrors()
        asSystem {
            ec.entity.find(VERSION).deleteAll()
            ec.entity.find("darpan.audit.AuditStatement").condition("statementId", "${MINE}_R2".toString()).one()
                    .set("statementText", STATEMENT_TEXT).update()
            setPermission(TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID)
        }
    }

    @Test
    void aSnapshotKeepsTheWholeConfigWithoutSecrets() {
        Map out = call("snapshot#TenantConfig", [reason: "before the R2 change"])
        assertFalse(out.hadError as boolean, out.errorText as String)
        assertTrue(out.created as boolean)
        Map version = (Map) out.version
        assertEquals(1L, version.seq as Long)
        assertNull(version.parentId)
        assertEquals("before the R2 change", version.reason)
        assertEquals("MANUAL", version.changeSource)
        assertEquals([audits: 1, statements: 1, checks: 1, runs: 0, queries: 0, conclusionCodes: 0, connections: 1, automations: 0], version.counts)

        Map got = call("get#TenantConfigVersion", [id: version.id])
        assertFalse(got.hadError as boolean, got.errorText as String)
        Map pack = (Map) ((Map) got.version).pack
        assertEquals(["month-close"], (pack.audits as List<Map>)*.label)
        assertEquals(["NETSUITE_AUTH:TCV_NS_AUTH"], (pack.connections as List<Map>)*.ref)
        String raw = rawPack(version.id as String)
        assertFalse(raw.contains(PASSWORD), "a secret never enters a version")
        assertFalse(raw.contains(OTHER), "nothing of another tenant")
    }

    @Test
    void aSnapshotWithNoChangeKeepsTheLatest() {
        Map first = (Map) call("snapshot#TenantConfig").version
        Map again = call("snapshot#TenantConfig", [reason: "nothing changed"])
        assertFalse(again.hadError as boolean, again.errorText as String)
        assertFalse(again.created as boolean)
        assertEquals(first.id, ((Map) again.version).id)
        assertEquals(1L, asSystem { ec.entity.find(VERSION).condition("companyUserGroupId", MINE).count() })
    }

    @Test
    void aChangeMakesANewVersionChainedToTheLast() {
        Map first = (Map) call("snapshot#TenantConfig").version
        asSystem {
            ec.entity.find("darpan.audit.AuditStatement").condition("statementId", "${MINE}_R2".toString()).one()
                    .set("statementText", "Every invoice has an Etail id.").update()
        }
        Map out = call("snapshot#TenantConfig", [reason: "R2 reworded"])
        assertTrue(out.created as boolean, out.errorText as String)
        Map second = (Map) out.version
        assertEquals(2L, second.seq as Long)
        assertEquals(first.id, second.parentId)
        assertNotEquals(first.hash, second.hash)
    }

    @Test
    void versionsAreListedNewestFirstWithoutThePack() {
        call("snapshot#TenantConfig")
        asSystem {
            ec.entity.find("darpan.audit.AuditStatement").condition("statementId", "${MINE}_R2".toString()).one()
                    .set("statementText", "Reworded.").update()
        }
        call("snapshot#TenantConfig")
        Map out = call("get#TenantConfigVersions")
        assertFalse(out.hadError as boolean, out.errorText as String)
        List<Map> versions = (List<Map>) out.versions
        assertEquals([2L, 1L], versions*.seq.collect { it as Long })
        assertTrue(versions.every { !it.containsKey("pack") }, "the list stays small; get#TenantConfigVersion carries the pack")
        assertEquals(["Smoke Test Customer", "Smoke Test Customer"], versions*.createdByName, "who, by name, not a user id")
    }

    @Test
    void anotherTenantsVersionReadsAsNotFound() {
        asSystem {
            ec.entity.makeValue(VERSION).setAll([configVersionId: "TCV_OTHERS", companyUserGroupId: OTHER, versionSeq: 1L,
                    contentHash: "0" * 64, bundleVersion: 1L, packJson: '{"audits":[]}', changeSource: "MANUAL",
                    createdDate: ec.user.nowTimestamp]).create()
        }
        Map got = call("get#TenantConfigVersion", [id: "TCV_OTHERS"])
        assertTrue(got.hadError as boolean, "another tenant's version is not found")
        assertNull(got.version)
        Map listed = call("get#TenantConfigVersions")
        assertFalse((listed.versions as List<Map>)*.id.contains("TCV_OTHERS"))
    }

    @Test
    void aViewOnlyUserCanReadButNotSnapshot() {
        call("snapshot#TenantConfig")
        asSystem { setPermission(TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID) }
        Map denied = call("snapshot#TenantConfig", [reason: "viewer"])
        assertTrue(denied.hadError as boolean, "a viewer cannot write a version")
        Map listed = call("get#TenantConfigVersions")
        assertFalse(listed.hadError as boolean, listed.errorText as String)
        assertEquals(1, (listed.versions as List).size())
    }

    private Map call(String verbNoun, Map params = [:]) {
        Map out = ec.service.sync().name("${FACADE}.${verbNoun}").parameters(params).call() ?: [:]
        Map result = out + [hadError: ec.message.hasError(), errorText: ec.message.errorsString]
        ec.message.clearErrors()
        return result
    }

    private String rawPack(String id) {
        return asSystem { ec.entity.find(VERSION).condition("configVersionId", id).one().packJson as String }
    }

    private void setPermission(String permissionGroupId) {
        ec.entity.find(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME)
                .condition("tenantUserGroupId", MINE).condition("userId", USER)
                .deleteAll()
        upsert(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [tenantUserGroupId: MINE,
                userId: USER, permissionUserGroupId: permissionGroupId,
                fromDate: FROM])
    }

    private <T> T asSystem(Closure<T> work) {
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            return work.call()
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    private void upsert(String entityName, Map<String, Object> fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }
}
