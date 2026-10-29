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
 * DAR-BE-160 phase A. Restoring a tenant config version: the audit behaves exactly as it did then. Everything the version
 * holds is put back; a statement added since is switched off and a check link added since is removed, but nothing that
 * findings or runs point at is deleted. The current config is kept as a version first, so a restore can be undone.
 * A restore applies only the plan that was previewed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantConfigRestoreSmokeTests {

    private static final String FACADE = "facade.TenantConfigFacadeServices"
    private static final String VERSION = "darpan.config.TenantConfigVersion"
    private static final String MINE = "KREWE"                // seedCompanyScope's tenant
    private static final String USER = "TEST_CUSTOMER_USER"   // and its editor
    private static final Timestamp FROM = Timestamp.valueOf("2026-04-21 00:00:00")
    private static final String OTHER = "TCR_OTHER"
    private static final String R2_TEXT = "Every invoice carries the Etail id."
    private static final String R2_ID = "${MINE}_R2".toString()

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "tenant-config-restore")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/DarpanSystemSourceSeedData.xml", "component://darpan/data/AutomationSeedData.xml",
                "component://darpan/data/SourceSystemConnectorSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        asSystem {
            upsert("moqui.security.UserGroup", [userGroupId: OTHER, description: "Other tenant",
                    groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
            upsert("moqui.security.UserGroup", [userGroupId: TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID,
                    description: "Can view tenant-scoped Darpan data", groupTypeEnumId: "UgtDarpanPermission"])
            upsert("darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "TCR_NS_AUTH", description: "krewe netsuite",
                    companyUserGroupId: MINE, authType: "BASIC", username: "svc-user", password: "S3cret-Tcr-Pass"])
        }
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    /** Every test starts from one audit, month-close: R2 linked to one check, plus a second check nothing uses yet. */
    @BeforeEach
    void reset() {
        ec.message.clearErrors()
        asSystem {
            ec.entity.find(VERSION).deleteAll()
            ec.entity.find("darpan.audit.AuditStatementCheck").condition("companyUserGroupId", MINE).deleteAll()
            ec.entity.find("darpan.audit.AuditStatement").condition("companyUserGroupId", MINE).deleteAll()
            upsert("darpan.audit.Audit", [auditId: "${MINE}_MC".toString(), companyUserGroupId: MINE, auditCode: "month-close",
                    auditName: "Month close", periodEnumId: "AUDP_MONTH"])
            upsert("darpan.audit.AuditStatement", [statementId: R2_ID, companyUserGroupId: MINE, auditId: "${MINE}_MC".toString(),
                    statementCode: "R2", categoryEnumId: "AUDC_ORDERS", weightEnumId: "AUDSW_FIRM", sequenceNum: 2L,
                    statementText: R2_TEXT, isActive: "Y"])
            upsert("darpan.audit.AuditCheck", [checkId: "${MINE}_INV".toString(), companyUserGroupId: MINE,
                    checkCode: "invoices-without-etail-id", label: "Invoices with no Etail id", graceDays: 1L])
            upsert("darpan.audit.AuditCheck", [checkId: "${MINE}_EXTRA".toString(), companyUserGroupId: MINE,
                    checkCode: "credit-memos-open", label: "Open credit memos", graceDays: 1L])
            upsert("darpan.audit.AuditStatementCheck", [checkId: "${MINE}_INV".toString(), statementId: R2_ID,
                    companyUserGroupId: MINE, sequenceNum: 1L])
            setPermission(TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID)
        }
    }

    @Test
    void restoringTheLatestVersionChangesNothing() {
        Map v1 = snapshot()
        Map preview = call("preview#TenantConfigRestore", [id: v1.id])
        assertFalse(preview.hadError as boolean, preview.errorText as String)
        Map plan = (Map) preview.plan
        assertEquals([], plan.blocking)
        assertTrue((plan.lines as List<Map>).every { it.action in ["UNCHANGED", "MAPPED"] }, "nothing to write: ${plan.lines}")
        assertEquals([], plan.switchOff)
        assertEquals([], plan.unlink)
        assertTrue((preview.planHash as String) ==~ /[0-9a-f]{64}/)
    }

    @Test
    void aRestoreUndoesAnEditedStatementAndKeepsTheEditAsAVersion() {
        Map v1 = snapshot()
        setR2Text("Every invoice has an Etail id.")
        Map preview = call("preview#TenantConfigRestore", [id: v1.id])
        Map r2 = (preview.plan.lines as List<Map>).find { it.kind == "STATEMENT" }
        assertEquals("UPDATE", r2.action, "R2 goes back: ${preview.plan}")
        assertEquals([[field: "statementText", from: "Every invoice has an Etail id.", to: R2_TEXT]], r2.changes)

        Map out = call("restore#TenantConfigVersion", [id: v1.id, planHash: preview.planHash])
        assertFalse(out.hadError as boolean, out.errorText as String)
        assertTrue(out.restored as boolean)
        assertEquals(R2_TEXT, r2Row().statementText)
        assertEquals("Before restoring version 1", out.before.reason, "the edit is kept, so the restore can be undone")
        assertEquals("Restored version 1", out.after.reason)
        assertEquals(v1.hash, out.after.hash, "the config is exactly version 1 again")
        assertEquals([3L, 2L, 1L], (call("get#TenantConfigVersions").versions as List<Map>)*.seq.collect { it as Long })
    }

    @Test
    void aStatementAddedSinceIsSwitchedOffNotDeleted() {
        Map v1 = snapshot()
        asSystem {
            upsert("darpan.audit.AuditStatement", [statementId: "${MINE}_R9".toString(), companyUserGroupId: MINE,
                    auditId: "${MINE}_MC".toString(), statementCode: "R9", categoryEnumId: "AUDC_ORDERS", weightEnumId: "AUDSW_FIRM",
                    sequenceNum: 9L, statementText: "Every refund reaches NetSuite.", isActive: "Y"])
        }
        Map preview = call("preview#TenantConfigRestore", [id: v1.id])
        assertEquals([[audit: "month-close", statement: "R9"]], (preview.plan.switchOff as List<Map>).collect { [audit: it.audit, statement: it.statement] })

        Map out = call("restore#TenantConfigVersion", [id: v1.id, planHash: preview.planHash])
        assertTrue(out.restored as boolean, out.errorText as String)
        def r9 = asSystem { ec.entity.find("darpan.audit.AuditStatement").condition("statementId", "${MINE}_R9".toString()).one() }
        assertNotNull(r9, "findings may point at R9, so it stays")
        assertEquals("N", r9.isActive)
    }

    @Test
    void aCheckLinkedSinceIsUnlinkedButKept() {
        Map v1 = snapshot()
        asSystem {
            upsert("darpan.audit.AuditStatementCheck", [checkId: "${MINE}_EXTRA".toString(), statementId: R2_ID,
                    companyUserGroupId: MINE, sequenceNum: 2L])
        }
        Map preview = call("preview#TenantConfigRestore", [id: v1.id])
        assertEquals([[statement: "R2", check: "credit-memos-open"]],
                (preview.plan.unlink as List<Map>).collect { [statement: it.statement, check: it.check] })

        Map out = call("restore#TenantConfigVersion", [id: v1.id, planHash: preview.planHash])
        assertTrue(out.restored as boolean, out.errorText as String)
        asSystem {
            assertEquals(["invoices-without-etail-id"], ec.entity.find("darpan.audit.AuditStatementCheck").condition("statementId", R2_ID)
                    .list().collect { ec.entity.find("darpan.audit.AuditCheck").condition("checkId", it.checkId).one().checkCode })
            assertNotNull(ec.entity.find("darpan.audit.AuditCheck").condition("checkId", "${MINE}_EXTRA".toString()).one())
        }
    }

    @Test
    void aPlanThatNoLongerMatchesIsRefusedAndNothingIsWritten() {
        Map v1 = snapshot()
        setR2Text("First edit.")
        Map preview = call("preview#TenantConfigRestore", [id: v1.id])
        setR2Text("Second edit, after the preview.")
        Map out = call("restore#TenantConfigVersion", [id: v1.id, planHash: preview.planHash])
        assertTrue(out.hadError as boolean)
        assertFalse(out.restored as boolean)
        assertEquals("Second edit, after the preview.", r2Row().statementText)
        assertEquals(1L, asSystem { ec.entity.find(VERSION).condition("companyUserGroupId", MINE).count() }, "no version written")
    }

    @Test
    void anotherTenantsVersionCannotBePreviewedOrRestored() {
        asSystem {
            ec.entity.makeValue(VERSION).setAll([configVersionId: "TCR_OTHERS", companyUserGroupId: OTHER, versionSeq: 1L,
                    contentHash: "0" * 64, bundleVersion: 1L, packJson: '{"bundleVersion":1,"audits":[]}', changeSource: "MANUAL",
                    createdDate: ec.user.nowTimestamp]).create()
        }
        assertTrue(call("preview#TenantConfigRestore", [id: "TCR_OTHERS"]).hadError as boolean)
        Map out = call("restore#TenantConfigVersion", [id: "TCR_OTHERS", planHash: "0" * 64])
        assertTrue(out.hadError as boolean)
        assertFalse(out.restored as boolean)
    }

    @Test
    void aViewOnlyUserCanPreviewButNotRestore() {
        Map v1 = snapshot()
        setR2Text("Edited.")
        asSystem { setPermission(TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID) }
        Map preview = call("preview#TenantConfigRestore", [id: v1.id])
        assertFalse(preview.hadError as boolean, preview.errorText as String)
        Map out = call("restore#TenantConfigVersion", [id: v1.id, planHash: preview.planHash])
        assertTrue(out.hadError as boolean, "a viewer cannot restore")
        assertEquals("Edited.", r2Row().statementText)
    }

    private Map snapshot() {
        Map out = call("snapshot#TenantConfig")
        assertFalse(out.hadError as boolean, out.errorText as String)
        return (Map) out.version
    }

    private void setR2Text(String text) {
        asSystem { ec.entity.find("darpan.audit.AuditStatement").condition("statementId", R2_ID).one().set("statementText", text).update() }
    }

    private def r2Row() {
        return asSystem { ec.entity.find("darpan.audit.AuditStatement").condition("statementId", R2_ID).one() }
    }

    private Map call(String verbNoun, Map params = [:]) {
        Map out = ec.service.sync().name("${FACADE}.${verbNoun}").parameters(params).call() ?: [:]
        Map result = out + [hadError: ec.message.hasError(), errorText: ec.message.errorsString]
        ec.message.clearErrors()
        return result
    }

    private void setPermission(String permissionGroupId) {
        ec.entity.find(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME)
                .condition("tenantUserGroupId", MINE).condition("userId", USER).deleteAll()
        upsert(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [tenantUserGroupId: MINE, userId: USER,
                permissionUserGroupId: permissionGroupId, fromDate: FROM])
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
