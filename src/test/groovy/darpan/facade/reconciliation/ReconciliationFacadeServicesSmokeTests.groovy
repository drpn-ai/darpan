package darpan.facade.reconciliation

import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import darpan.reconciliation.support.SharedMoqui
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-OPS-029 S22: facade.ReconciliationFacadeServices behaviour that no other class pins.
 *
 * <ul>
 *   <li>delete#SavedRun deletes every generated output of the run, not only the first page that
 *       list#GeneratedOutputs returns (at most 200): the run's result rows all go, so an output it skipped
 *       would stay on disk with nothing left that lists or deletes it.</li>
 *   <li>save#RuleSetRun deletes a rule the editor removed even when that rule row carries no tenant stamp.
 *       darpan.rule.Rule is under the active-tenant entity filter, which hides an unstamped row from a plain
 *       find, so the delete pass must read the rules as children of the tenant-gated RuleSet (DAR-BE-076's
 *       fix for key fields and exclusion filters, applied to rules). Proven with authz ON, the only posture
 *       in which the filter applies.</li>
 *   <li>save#Automation without a windowTimeZone runs in the tenant's zone, as the app does, not in UTC (OC44); an edit
 *       that names no zone keeps the automation's own.</li>
 * </ul>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SharedMoqui
class ReconciliationFacadeServicesSmokeTests {

    private static final String F = "facade.ReconciliationFacadeServices."
    private static final String KREWE = "KREWE"
    // seedCompanyScope's user (its constants are private there). internalLoginUser takes the USERNAME.
    private static final String EDITOR_ID = "TEST_CUSTOMER_USER"
    private static final String EDITOR_USERNAME = "test.customer"
    private static final String SWITCH_ID = "RFS_SWITCH_USER"
    private static final String SWITCH_USERNAME = "rfs.switch"
    private static final Timestamp FROM_DATE = Timestamp.valueOf("2026-04-21 00:00:00")
    private static final String NOOP_RULE = 'rule "%s"\nwhen\n    $m : Map()\nthen\nend'

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "reconciliation-facade-services")
        // The security model the filter test needs: DARPAN_FACADE_APP grants and the DARPAN_ACTIVE_COMPANY_SCOPE rows.
        // dummyFks: the file loads in one transaction and forward-references data this bare H2 does not carry.
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/DarpanSystemSourceSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/SourceSystemConnectorSeedData.xml")
        ReconciliationSmokeTestSupport.seedSchemaBackedCsvMappingFixtures(ec)   // also seedCompanyScope: KREWE editor
        ReconciliationSmokeTestSupport.seedSftpServerFixtures(ec)
        insert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: EDITOR_ID, fromDate: FROM_DATE])
        insert("moqui.security.UserAccount", [userId: SWITCH_ID, username: SWITCH_USERNAME, userFullName: "Switch",
                                              currentPassword: "", disabled: "N"])
        // UserFacade caches a user's groups per login and reuses it for the same username, so the DARPAN_USER
        // membership above is only seen after a real switch of user.
        actAs(SWITCH_USERNAME, null)
        actAs(EDITOR_USERNAME, KREWE)
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void actAsKreweEditor() {
        actAs(EDITOR_USERNAME, KREWE)
    }

    @Test
    void deletingASavedRunRemovesEveryGeneratedOutputPastTheFirstPage() {
        String savedRunId = createRun("S22 delete pages", [])
        int outputCount = 205   // list#GeneratedOutputs returns at most 200 per page
        List<File> files = []
        try {
            (1..outputCount).each { int i ->
                String path = "reconciliation-runs/${savedRunId}/${String.format('%03d', i)}/result.json".toString()
                File file = DataManagerSupport.resolveDataManagerFile(ec, path, true)
                file.text = '{"metadata":{},"summary":{"totalDifferences":0},"differences":[]}\n'
                files << file
                insert("darpan.reconciliation.ReconciliationRunResult", [reconciliationRunResultId: "S22_DEL_${i}".toString(),
                        savedRunId: savedRunId, savedRunType: "ruleset", companyUserGroupId: KREWE, statusEnumId: "AUT_STAT_SUCCESS",
                        resultDataManagerPath: path, createdDate: new Timestamp(FROM_DATE.time + i * 1000L)])
            }

            Map<String, Object> deleted = ec.service.sync().name(F + "delete#SavedRun").parameters([savedRunId: savedRunId])
                    .disableAuthz().call()

            assertFalse(ec.message.hasError(), "${ec.message.errors}")
            assertEquals(outputCount, deleted.deletedGeneratedOutputCount as Integer, "every output of the run is deleted")
            assertEquals([], files.findAll { it.exists() }*.path, "no result file outlives its run")
        } finally {
            files.each { it.delete() }
            ec.entity.find("darpan.reconciliation.ReconciliationRunResult").condition("savedRunId", savedRunId)
                    .disableAuthz().useCache(false).deleteAll()
        }
    }

    @Test
    void savingARunDeletesARuleTheEditorRemovedEvenWhenItIsUnstamped() {
        String runName = "S22 unstamped rule"
        String savedRunId = createRun(runName, [rule("S22_KEEP", 10), rule("S22_DROP", 20)])
        List rules = ec.entity.find("darpan.rule.Rule").condition("ruleSetId", savedRunId).orderBy("sequenceNum")
                .disableAuthz().useCache(false).list()
        assertEquals(2, rules.size(), "test precondition: two rules")
        String keepId = rules[0].ruleId as String
        rules[1].set("companyUserGroupId", null).update()   // a row from before rules carried a stamp

        Map<String, Object> saved = withProductionAuthz {
            ec.service.sync().name(F + "save#RuleSetRun")
                    .parameters(ruleSetParams(runName) + [savedRunId: savedRunId, rules: [rule("S22_KEEP", 10) + [ruleId: keepId]]])
                    .call()
        }

        assertTrue(saved?.ok == true, "save#RuleSetRun failed under production authz: ${saved?.errors ?: ec.message.errors}")
        List remaining = ec.entity.find("darpan.rule.Rule").condition("ruleSetId", savedRunId).disableAuthz().useCache(false).list()
        assertEquals([keepId], remaining*.ruleId, "the rule the editor removed is deleted, stamped or not")
    }

    private Map<String, Object> saveSftpAutomation(Map<String, Object> params) {
        Map<String, Object> sources = [FILE_1: ["OMS", "OMS_TEST_SFTP"], FILE_2: ["SHOPIFY", "SHOPIFY_TEST_SFTP"]]
        return ec.service.sync().name(F + "save#Automation").parameters([
                inputModeEnumId: "AUT_IN_SFTP_FILES", savedRunType: "ruleset", scheduleExpr: "0 0 * * * ?",
                sources        : sources.collect { String side, List system ->
                    [fileSide: side, sourceTypeEnumId: "AUT_SRC_SFTP", systemEnumId: system[0], fileTypeEnumId: "DftCsv",
                     sftpServerId: system[1], remotePathTemplate: "/incoming/${system[0].toString().toLowerCase()}".toString()]
                },
        ] + params).disableAuthz().call() as Map<String, Object>
    }

    private String storedZone(String automationId) {
        return ec.entity.find("darpan.reconciliation.ReconciliationAutomation").condition("automationId", automationId)
                .disableAuthz().useCache(false).one()?.windowTimeZone as String
    }

    private String createRun(String runName, List<Map> rules) {
        Map<String, Object> created = ec.service.sync().name(F + "create#RuleSetRun")
                .parameters(ruleSetParams(runName) + [rules: rules]).disableAuthz().call()
        assertFalse(ec.message.hasError(), "${ec.message.errors}")
        return created.savedRun.savedRunId as String
    }

    private static Map<String, Object> rule(String name, int sequenceNum) {
        return [sequenceNum: sequenceNum, ruleLogic: String.format(NOOP_RULE, name), enabled: "Y"] as Map<String, Object>
    }

    private static Map<String, Object> ruleSetParams(String runName) {
        return [runName: runName, file1SystemEnumId: "OMS", file1FileTypeEnumId: "DftCsv", file1PrimaryIdExpression: "order_id",
                file2SystemEnumId: "SHOPIFY", file2FileTypeEnumId: "DftCsv", file2PrimaryIdExpression: "order_id"] as Map<String, Object>
    }

    /** Production posture: no harness grant on the stack, authz ON (as TenantOwnedEntityFilterRuntimeTests). */
    private <T> T withProductionAuthz(Closure<T> body) {
        def harnessFrame = ec.artifactExecution.peek()
        assertEquals("smokeTests", harnessFrame?.name, "test precondition: unexpected artifact stack")
        ec.artifactExecution.pop(harnessFrame)
        boolean wasDisabled = ec.artifactExecution.authzDisabled
        ec.artifactExecution.enableAuthz()
        try {
            assertFalse(ec.artifactExecution.authzDisabled, "test precondition: authz must be ON")
            return body.call()
        } finally {
            if (wasDisabled) ec.artifactExecution.disableAuthz()
            ec.artifactExecution.push("smokeTests", ArtifactExecutionInfo.AT_OTHER, ArtifactExecutionInfo.AUTHZA_ALL, false)
            ec.artifactExecution.setAnonymousAuthorizedAll()
        }
    }

    private void actAs(String username, String tenant) {
        assertTrue(ec.user.internalLoginUser(username), "could not log in ${username}")
        if (tenant) ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, tenant)
        TenantAccessSupport.syncUserContext(ec)
        ec.message.clearErrors()
    }

    private void insert(String entityName, Map<String, Object> fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }
}
