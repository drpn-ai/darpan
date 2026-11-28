package darpan.migration

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import darpan.reconciliation.support.SharedMoqui
import groovy.xml.XmlSlurper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-338. 3.4.0 dropped every automation's own filter rows (AUTOMATION_FILTER_TABLE); automations keep their own copy
 * again, so AUTOMATION_FILTER_COPY rebuilds each side's rows from its run's, where (and only where) the drop ran.
 */
@SharedMoqui
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutomationFilterCopySupportTests {
    private static final String A = "KREWE"
    private static final String B = "COPY_OTHER"
    private static final String AUTO_FILTER = "darpan.reconciliation.ReconciliationAutomationSourceFilter"
    private static final String SERVICE = "reconciliation.ReconciliationNotificationServices.restore#AutomationSourceFilters"
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "automation-filter-copy")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: B, description: "Other",
                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID]).createOrUpdate()
    }

    @AfterAll
    void cleanup() {
        clear()
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void seed() {
        clear()
        ec.entity.makeDataLoader().dummyFks(true).xmlText("""<entity-facade-xml type="seed" create-or-update="true">
            <darpan.rule.RuleSet ruleSetId="COPY_RS" ruleSetName="Copy" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareScope compareScopeId="COPY_SCOPE" ruleSetId="COPY_RS" objectType="ORDER" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareSource compareScopeId="COPY_SCOPE" fileSide="FILE_1" systemEnumId="SHOPIFY" fileTypeEnumId="DftJson"
                recordRootExpression="records" primaryIdExpression="orderId" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareSource compareScopeId="COPY_SCOPE" fileSide="FILE_2" systemEnumId="NETSUITE" fileTypeEnumId="DftJson"
                recordRootExpression="records" primaryIdExpression="tranId" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareSourceFilter compareScopeId="COPY_SCOPE" fileSide="FILE_1" sequenceNum="1" fieldLabel="Product"
                fieldExpression="productId" operator="EXCLUDE_IN" filterValues="142036" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareSourceFilter compareScopeId="COPY_SCOPE" fileSide="FILE_1" sequenceNum="2"
                fieldExpression="status" operator="EXCLUDE_IN" filterValues="VOID,DRAFT" companyUserGroupId="${A}"/>
            <darpan.rule.RuleSetCompareSourceFilter compareScopeId="COPY_SCOPE" fileSide="FILE_1" sequenceNum="3"
                fieldExpression="foreign" operator="EXCLUDE_IN" filterValues="NOT_MINE" companyUserGroupId="${B}"/>
            <darpan.rule.RuleSetCompareSourceFilter compareScopeId="COPY_SCOPE" fileSide="FILE_2" sequenceNum="1"
                fieldExpression="memoOrigin" operator="EXCLUDE_IN" filterValues="SHOPIFY" companyUserGroupId="${A}"/>
            <darpan.reconciliation.ReconciliationAutomation automationId="COPY_A1" automationName="Copy one" companyUserGroupId="${A}"
                inputModeEnumId="${AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE}" savedRunId="COPY_RS" compareScopeId="COPY_SCOPE"/>
            <darpan.reconciliation.ReconciliationAutomationSource automationId="COPY_A1" fileSide="FILE_1" companyUserGroupId="${A}"
                sourceTypeEnumId="${AutomationExecutionSupport.AUTOMATION_SOURCE_API}"/>
            <darpan.reconciliation.ReconciliationAutomationSource automationId="COPY_A1" fileSide="FILE_2" companyUserGroupId="${A}"
                sourceTypeEnumId="${AutomationExecutionSupport.AUTOMATION_SOURCE_API}"/>
            <darpan.reconciliation.ReconciliationAutomation automationId="COPY_A2" automationName="Copy two" companyUserGroupId="${A}"
                inputModeEnumId="${AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE}" savedRunId="COPY_RS" compareScopeId="COPY_SCOPE"/>
            <darpan.reconciliation.ReconciliationAutomationSource automationId="COPY_A2" fileSide="FILE_1" companyUserGroupId="${A}"
                sourceTypeEnumId="${AutomationExecutionSupport.AUTOMATION_SOURCE_API}"/>
            <darpan.reconciliation.ReconciliationAutomationSourceFilter automationId="COPY_A2" fileSide="FILE_1" sequenceNum="1"
                fieldExpression="productId" operator="EXCLUDE_IN" filterValues="OWN_ONLY" companyUserGroupId="${A}"/>
        </entity-facade-xml>""").load()
    }

    @Test
    void whereTheTableWasNeverDroppedNothingIsWritten() {
        Map out = AutomationFilterCopySupport.copyFromRuns(ec, false)

        assertEquals(0, out.rowsWritten)
        assertEquals("table never dropped here", out.skipped)
        assertEquals(["COPY_A2|FILE_1|OWN_ONLY"], ownRows())
    }

    @Test
    void aSideWithNoRowsTakesItsRunsRowsForItsOwnTenantOnly() {
        droppedHere()

        Map out = AutomationFilterCopySupport.copyFromRuns(ec, false)

        assertEquals(3, out.rowsWritten, out.toString())
        assertEquals(2, out.sidesFilled, out.toString())
        assertEquals(["COPY_A1|FILE_1|142036", "COPY_A1|FILE_1|VOID,DRAFT", "COPY_A1|FILE_2|SHOPIFY", "COPY_A2|FILE_1|OWN_ONLY"], ownRows(),
                "another tenant's row under the same scope is never copied")
        def copied = ec.entity.find(AUTO_FILTER).condition([automationId: "COPY_A1", fileSide: "FILE_1", sequenceNum: 1L]).disableAuthz().one()
        assertEquals(["productId", "EXCLUDE_IN", A], [copied.fieldExpression, copied.operator, copied.companyUserGroupId])
    }

    @Test
    void aSideThatAlreadyHasRowsIsLeftAlone() {
        droppedHere()

        AutomationFilterCopySupport.copyFromRuns(ec, false)

        assertTrue(ownRows().contains("COPY_A2|FILE_1|OWN_ONLY"))
        assertEquals(1, ownRows().count { it.startsWith("COPY_A2|") })
    }

    @Test
    void aDryRunCountsAndWritesNothing() {
        droppedHere()

        Map out = ec.service.sync().name(SERVICE).parameters([dryRun: true]).disableAuthz().call()

        assertEquals([true, 3, 2], [out.ok, out.rowsWritten as Integer, out.sidesFilled as Integer], out.toString())
        assertEquals(["COPY_A2|FILE_1|OWN_ONLY"], ownRows())
    }

    @Test
    void aSecondRunWritesNothing() {
        droppedHere()
        AutomationFilterCopySupport.copyFromRuns(ec, false)

        Map again = ec.service.sync().name(SERVICE).parameters([dryRun: false]).disableAuthz().call()

        assertEquals([true, 0], [again.ok, again.rowsWritten as Integer], again.toString())
    }

    @Test
    void theRegistryNoLongerHoldsTheTableDropAndRegistersTheCopy() {
        // a fresh install never drops the table; an upgraded instance gets the drop switched off by the release pack's hand row
        Path seed = ReconciliationSmokeTestSupport.resolveBackendRoot().resolve("runtime/component/darpan/data/MigrationRegistrySeedData.xml")
        def rows = new XmlSlurper().parse(seed.toFile()).'**'.findAll { it.name() == "darpan.migration.DarpanMigration" }
        assertTrue(rows.every { it.@migrationId != "AUTOMATION_FILTER_TABLE" })
        List<String> sequences = rows.collect { it.@sequenceNum.toString() }
        assertEquals(sequences.unique(false), sequences, "every migration has its own sequenceNum: ${sequences}")
        def copy = rows.find { it.@migrationId == "AUTOMATION_FILTER_COPY" }
        assertEquals([SERVICE, "Y"], [copy.@serviceName.toString(), copy.@supportsDryRun.toString()])
        // OC142: the seed states no enabled (a deploy must not un-park it); a fresh load starts it enabled by the entity default.
        assertEquals("", copy.@enabled.toString())
        def ledger = ec.entity.find("darpan.migration.DarpanMigration").condition("migrationId", "AUTOMATION_FILTER_TABLE").disableAuthz().one()
        // No seed carries the row and clear() removes the one droppedHere() makes, so a test boot has none at all.
        assertNull(ledger, "a test boot never registers the drop: ${ledger}")
    }

    private void droppedHere() {
        ec.artifactExecution.disableAuthz()
        try {
            // the registry row the ledger points at; the shared Moqui may already hold the seeded one, which is left as it is
            if (ec.entity.find("darpan.migration.DarpanMigration").condition("migrationId", "AUTOMATION_FILTER_TABLE").one() == null) {
                ec.entity.makeValue("darpan.migration.DarpanMigration").setAll([migrationId: "AUTOMATION_FILTER_TABLE", componentName: "darpan",
                        serviceName: "retired", sequenceNum: 140, enabled: "N"]).create()
            }
            ec.entity.makeValue("darpan.migration.DarpanMigrationRun").setAll([migrationId: "AUTOMATION_FILTER_TABLE", runId: "COPY_TEST_RUN",
                    startedDate: new Timestamp(System.currentTimeMillis()), statusId: MigrationLedgerSupport.STATUS_SUCCESS]).create()
        } finally {
            ec.artifactExecution.enableAuthz()
        }
    }

    private List<String> ownRows() {
        return ec.entity.find(AUTO_FILTER).condition("automationId", "in", ["COPY_A1", "COPY_A2"]).orderBy("automationId,fileSide,sequenceNum")
                .disableAuthz().useCache(false).list().collect { "${it.automationId}|${it.fileSide}|${it.filterValues}".toString() }
    }

    private void clear() {
        ec.artifactExecution.disableAuthz()
        try {
            ec.entity.find(AUTO_FILTER).condition("automationId", "in", ["COPY_A1", "COPY_A2"]).deleteAll()
            ec.entity.find("darpan.migration.DarpanMigrationRun").condition("runId", "COPY_TEST_RUN").deleteAll()
            ec.entity.find("darpan.migration.DarpanMigration").condition("migrationId", "AUTOMATION_FILTER_TABLE")
                    .condition("serviceName", "retired").deleteAll()
            ec.entity.find("darpan.reconciliation.ReconciliationAutomationSource").condition("automationId", "in", ["COPY_A1", "COPY_A2"]).deleteAll()
            ec.entity.find("darpan.reconciliation.ReconciliationAutomation").condition("automationId", "in", ["COPY_A1", "COPY_A2"]).deleteAll()
            ec.entity.find("darpan.rule.RuleSetCompareSourceFilter").condition("compareScopeId", "COPY_SCOPE").deleteAll()
        } finally {
            ec.artifactExecution.enableAuthz()
        }
    }
}
