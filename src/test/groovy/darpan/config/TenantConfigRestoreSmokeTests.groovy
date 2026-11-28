package darpan.config

import darpan.reconciliation.support.SharedMoqui
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
import static darpan.reconciliation.support.TestHelpers.asSystem
import static darpan.reconciliation.support.TestHelpers.upsert

/**
 * DAR-BE-160 phase A. Restoring a tenant config version: the audit behaves exactly as it did then. Everything the version
 * holds is put back; a statement added since is switched off and a check link added since is removed, but nothing that
 * findings or runs point at is deleted. The current config is kept as a version first, so a restore can be undone.
 * A restore applies only the plan that was previewed.
 */
@SharedMoqui
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
        asSystem(ec) {
            upsert(ec, "moqui.security.UserGroup", [userGroupId: OTHER, description: "Other tenant",
                    groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
            upsert(ec, "moqui.security.UserGroup", [userGroupId: TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID,
                    description: "Can view tenant-scoped Darpan data", groupTypeEnumId: "UgtDarpanPermission"])
            upsert(ec, "darpan.reconciliation.NsAuthConfig", [nsAuthConfigId: "TCR_NS_AUTH", description: "krewe netsuite",
                    companyUserGroupId: MINE, authType: "BASIC", username: "svc-user", password: "S3cret-Tcr-Pass"])
            // A query no run uses: a version never holds it, so it must not read as "added since" (browser pass 2026-10-30).
            upsert(ec, "darpan.reconciliation.NsSuiteQlSourceQuery", [nsSuiteQlSourceQueryId: "TCR_UNUSED_QUERY", companyUserGroupId: MINE,
                    description: "Cash sales that never shipped", recordType: "cashsale"])
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
        asSystem(ec) {
            ec.entity.find(VERSION).deleteAll()
            ec.entity.find("darpan.audit.AuditStatementCheck").condition("companyUserGroupId", MINE).deleteAll()
            ec.entity.find("darpan.audit.AuditStatement").condition("companyUserGroupId", MINE).deleteAll()
            upsert(ec, "darpan.audit.Audit", [auditId: "${MINE}_MC".toString(), companyUserGroupId: MINE, auditCode: "month-close",
                    auditName: "Month close", periodEnumId: "AUDP_MONTH"])
            upsert(ec, "darpan.audit.AuditStatement", [statementId: R2_ID, companyUserGroupId: MINE, auditId: "${MINE}_MC".toString(),
                    statementCode: "R2", categoryEnumId: "AUDC_ORDERS", weightEnumId: "AUDSW_FIRM", sequenceNum: 2L,
                    statementText: R2_TEXT, isActive: "Y"])
            upsert(ec, "darpan.audit.AuditCheck", [checkId: "${MINE}_INV".toString(), companyUserGroupId: MINE,
                    checkCode: "invoices-without-etail-id", label: "Invoices with no Etail id"])
            upsert(ec, "darpan.audit.AuditCheck", [checkId: "${MINE}_EXTRA".toString(), companyUserGroupId: MINE,
                    checkCode: "credit-memos-open", label: "Open credit memos"])
            upsert(ec, "darpan.audit.AuditStatementCheck", [checkId: "${MINE}_INV".toString(), statementId: R2_ID,
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
        // The version holds the tenant's one connection (mapped to itself), the audit, R2 and the one check R2 links;
        // the unlinked check and the query no run uses are in no version, so they make no line.
        assertEquals([["NETSUITE_AUTH", "MAPPED"], ["AUDIT", "UNCHANGED"], ["STATEMENT", "UNCHANGED"], ["CHECK", "UNCHANGED"]],
                (plan.lines as List<Map>).collect { [it.kind, it.action] }, "nothing to write: ${plan.lines}")
        assertEquals([], plan.switchOff)
        assertEquals([], plan.unlink)
        assertEquals([], plan.kept, "a query no run uses was there all along; nothing was added since")
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

    // DAR-BE-237: a version holds the tenant's scripts and deployments, and a restore brings them back as they were.
    @Test
    void aRestoreBringsTheEarlierScriptAndItsDeploymentBack() {
        try {
            asSystem(ec) {
                upsert(ec, "darpan.reconciliation.TenantChatSpace", [chatSpaceId: "TCR_SPACE", companyUserGroupId: MINE, spaceName: "Digest",
                        isActive: "Y"])
                upsert(ec, "jexl.script.UserScript", [userScriptId: "TCR_SCRIPT", companyUserGroupId: MINE, scriptName: "Digest",
                        scriptProfile: "QUERY", scriptBody: "'version one'", isActive: "Y"])
                upsert(ec, "jexl.script.UserScriptDeployment", [scriptDeploymentId: "TCR_DEPLOY", companyUserGroupId: MINE, userScriptId: "TCR_SCRIPT",
                        cronExpression: "0 0 9 * * ?", timeZone: "UTC", ownerUserId: ec.user.userId, isActive: "Y"])
                upsert(ec, "jexl.script.UserScriptDeploymentDelivery", [scriptDeploymentId: "TCR_DEPLOY", deliverySeqId: "01",
                        deliveryServiceName: "query.QueryHostServices.deliver#QueryChat", parametersJson: '{"chatSpaceId":"TCR_SPACE"}', isActive: "Y"])
            }
            Map v1 = snapshot()
            asSystem(ec) {
                ec.entity.find("jexl.script.UserScript").condition("userScriptId", "TCR_SCRIPT").one().set("scriptBody", "'version two'").update()
                ec.entity.find("jexl.script.UserScriptDeployment").condition("scriptDeploymentId", "TCR_DEPLOY").one()
                        .set("cronExpression", "0 30 17 * * ?").update()
            }
            Map preview = call("preview#TenantConfigRestore", [id: v1.id])
            assertFalse(preview.hadError as boolean, preview.errorText as String)
            assertEquals("UPDATE", (preview.plan.lines as List<Map>).find { it.kind == "SCRIPT" }?.action, "${preview.plan.lines}")

            Map out = call("restore#TenantConfigVersion", [id: v1.id, planHash: preview.planHash])
            assertFalse(out.hadError as boolean, out.errorText as String)
            asSystem(ec) {
                assertEquals("'version one'", ec.entity.find("jexl.script.UserScript").condition("userScriptId", "TCR_SCRIPT").useCache(false).one().scriptBody)
                def deployment = ec.entity.find("jexl.script.UserScriptDeployment").condition("scriptDeploymentId", "TCR_DEPLOY").useCache(false).one()
                def chat = ec.entity.find("jexl.script.UserScriptDeploymentDelivery").condition("scriptDeploymentId", "TCR_DEPLOY").useCache(false).one()
                assertEquals(["0 0 9 * * ?", '{"chatSpaceId":"TCR_SPACE"}', "N"], [deployment.cronExpression, chat.parametersJson, deployment.isActive],
                        "back under its own id, on its own space, and switched off for a tenant admin to turn on (DAR-BE-334)")
            }
            assertEquals(v1.hash, out.after.hash, "the config is exactly version 1 again")
        } finally {
            asSystem(ec) {
                ec.entity.find("jexl.script.UserScriptDeploymentDelivery").condition("scriptDeploymentId", "TCR_DEPLOY").deleteAll()
                ec.entity.find("jexl.script.UserScriptDeployment").condition("companyUserGroupId", MINE).deleteAll()
                ec.entity.find("jexl.script.UserScript").condition("companyUserGroupId", MINE).deleteAll()
                ec.entity.find("darpan.reconciliation.TenantChatSpace").condition("chatSpaceId", "TCR_SPACE").deleteAll()
            }
        }
    }

    @Test
    void aStatementAddedSinceIsSwitchedOffNotDeleted() {
        Map v1 = snapshot()
        asSystem(ec) {
            upsert(ec, "darpan.audit.AuditStatement", [statementId: "${MINE}_R9".toString(), companyUserGroupId: MINE,
                    auditId: "${MINE}_MC".toString(), statementCode: "R9", categoryEnumId: "AUDC_ORDERS", weightEnumId: "AUDSW_FIRM",
                    sequenceNum: 9L, statementText: "Every refund reaches NetSuite.", isActive: "Y"])
        }
        Map preview = call("preview#TenantConfigRestore", [id: v1.id])
        assertEquals([[audit: "month-close", statement: "R9"]], (preview.plan.switchOff as List<Map>).collect { [audit: it.audit, statement: it.statement] })

        Map out = call("restore#TenantConfigVersion", [id: v1.id, planHash: preview.planHash])
        assertTrue(out.restored as boolean, out.errorText as String)
        def r9 = asSystem(ec) { ec.entity.find("darpan.audit.AuditStatement").condition("statementId", "${MINE}_R9".toString()).one() }
        assertNotNull(r9, "findings may point at R9, so it stays")
        assertEquals("N", r9.isActive)
    }

    @Test
    void aCheckLinkedSinceIsUnlinkedButKept() {
        Map v1 = snapshot()
        asSystem(ec) {
            upsert(ec, "darpan.audit.AuditStatementCheck", [checkId: "${MINE}_EXTRA".toString(), statementId: R2_ID,
                    companyUserGroupId: MINE, sequenceNum: 2L])
        }
        Map preview = call("preview#TenantConfigRestore", [id: v1.id])
        assertEquals([[statement: "R2", check: "credit-memos-open"]],
                (preview.plan.unlink as List<Map>).collect { [statement: it.statement, check: it.check] })

        Map out = call("restore#TenantConfigVersion", [id: v1.id, planHash: preview.planHash])
        assertTrue(out.restored as boolean, out.errorText as String)
        asSystem(ec) {
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
        assertEquals(1L, asSystem(ec) { ec.entity.find(VERSION).condition("companyUserGroupId", MINE).count() }, "no version written")
    }

    @Test
    void anotherTenantsVersionCannotBePreviewedOrRestored() {
        asSystem(ec) {
            ec.entity.makeValue(VERSION).setAll([configVersionId: "TCR_OTHERS", companyUserGroupId: OTHER, versionSeq: 1L,
                    contentHash: "0" * 64, bundleVersion: 1L, packJson: '{"bundleVersion":1,"audits":[]}', changeSource: "MANUAL",
                    createdDate: ec.user.nowTimestamp]).create()
        }
        // Foreign reads exactly as missing: the same refusal an id that exists nowhere gets.
        assertEquals("Config version NO_SUCH was not found.", (call("preview#TenantConfigRestore", [id: "NO_SUCH"]).errorText as String).trim())
        assertEquals("Config version TCR_OTHERS was not found.", (call("preview#TenantConfigRestore", [id: "TCR_OTHERS"]).errorText as String).trim())
        assertEquals("Config version NO_SUCH was not found.",
                (call("restore#TenantConfigVersion", [id: "NO_SUCH", planHash: "0" * 64]).errorText as String).trim())
        Map out = call("restore#TenantConfigVersion", [id: "TCR_OTHERS", planHash: "0" * 64])
        assertEquals("Config version TCR_OTHERS was not found.", (out.errorText as String).trim())
        assertFalse(out.restored as boolean)
    }

    @Test
    void aViewOnlyUserCanPreviewButNotRestore() {
        Map v1 = snapshot()
        setR2Text("Edited.")
        asSystem(ec) { setPermission(TenantAccessSupport.DARPAN_COMPANY_VIEW_ONLY_GROUP_ID) }
        Map preview = call("preview#TenantConfigRestore", [id: v1.id])
        assertFalse(preview.hadError as boolean, preview.errorText as String)
        Map out = call("restore#TenantConfigVersion", [id: v1.id, planHash: preview.planHash])
        assertEquals("Your active tenant only has view access, so it cannot restore a config version.", (out.errorText as String).trim(),
                "a viewer cannot restore")
        assertEquals("Edited.", r2Row().statementText)
    }

    private Map snapshot() {
        Map out = call("snapshot#TenantConfig")
        assertFalse(out.hadError as boolean, out.errorText as String)
        return (Map) out.version
    }

    private void setR2Text(String text) {
        asSystem(ec) { ec.entity.find("darpan.audit.AuditStatement").condition("statementId", R2_ID).one().set("statementText", text).update() }
    }

    private def r2Row() {
        return asSystem(ec) { ec.entity.find("darpan.audit.AuditStatement").condition("statementId", R2_ID).one() }
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
        upsert(ec, TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [tenantUserGroupId: MINE, userId: USER,
                permissionUserGroupId: permissionGroupId, fromDate: FROM])
    }
}
