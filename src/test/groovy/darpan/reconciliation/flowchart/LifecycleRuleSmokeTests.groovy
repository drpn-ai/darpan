package darpan.reconciliation.flowchart

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-UI-050. A lifecycle's rules leave records out: "leave out sales orders where HC Sales Channel is Shopify
 * Draft Order". A rule is a filter on the start's records, so a left-out record is in no place at all.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LifecycleRuleSmokeTests {

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "lifecycle-rule-smoke")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/DarpanSystemSourceSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/SourceSystemConnectorSeedData.xml")
        ReconciliationSmokeTestSupport.seedSchemaBackedCsvMappingFixtures(ec)
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void tenant() {
        ec.message.clearErrors()
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, "KREWE")
        ec.artifactExecution.disableAuthz()
        try {
            ec.entity.makeValue("moqui.basic.Enumeration").setAll([enumId: "DftJson", enumTypeId: "DarpanFileType",
                    enumCode: "JSON", description: "JSON", sequenceNum: 2]).createOrUpdate()
            ec.entity.makeValue("darpan.reconciliation.NsAuthConfig").setAll([nsAuthConfigId: "LC_AUTH", companyUserGroupId: "KREWE",
                    description: "Lifecycle test account"]).createOrUpdate()
        } finally {
            ec.artifactExecution.enableAuthz()
        }
        ec.message.clearErrors()
    }

    Map call(String name, Map params) {
        return ec.service.sync().name(name).parameters(params).disableAuthz().call() ?: [:]
    }

    String lifecycle() {
        Map out = call("facade.ReconciliationFacadeServices.save#Lifecycle",
                [nsAuthConfigId: "LC_AUTH", recordType: "SalesOrd", stageRecordTypes: ["ItemShip", "CustInvc"]])
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        return out.reconciliationId as String
    }

    Map rule(String recId, String fieldId, String fieldLabel, String value) {
        return call("facade.ReconciliationFacadeServices.save#LifecycleRule",
                [reconciliationId: recId, fieldId: fieldId, fieldLabel: fieldLabel, value: value])
    }

    List<Map> rules(String recId) {
        return call("facade.ReconciliationFacadeServices.get#Reconciliation", [reconciliationId: recId]).rules as List<Map>
    }

    @Test
    void aRuleLeavesRecordsOutOfTheStartAndReadsInPlainWords() {
        String recId = lifecycle()
        rule(recId, "custbody_hc_sales_channel", "HC Sales Channel", "Shopify Draft Order")
        rule(recId, "custbody_hc_order_id", "HC Order Id", LifecycleRuleSupport.NO_VALUE)
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())

        assertEquals([["custbody_hc_sales_channel", "HC Sales Channel", "Shopify Draft Order", "Shopify Draft Order"],
                      ["custbody_hc_order_id", "HC Order Id", "NO_VALUE", "No value"]],
                rules(recId).collect { [it.fieldId, it.fieldLabel, it.value, it.valueLabel] })

        Map got = call("facade.ReconciliationFacadeServices.get#Reconciliation", [reconciliationId: recId])
        assertEquals(["LC_AUTH", "SalesOrd", "Sales orders"], got.lifecycle.with { [it.nsAuthConfigId, it.recordType, it.recordLabel] })

        // The start's query now carries the field, with an empty value made visible so it can be left out.
        Map start = (got.questions as List<Map>).find { it.questionRole == "START" }
        ec.artifactExecution.disableAuthz()
        try {
            def scope = ec.entity.find("darpan.rule.RuleSetCompareScope").condition("ruleSetId", start.ruleSetId).one()
            def source = ec.entity.find("darpan.rule.RuleSetCompareSource").condition("compareScopeId", scope.compareScopeId).one()
            def column = ec.entity.find("darpan.reconciliation.NsSuiteQlSourceQueryField")
                    .condition("nsSuiteQlSourceQueryId", source.sourceConfigId).condition("recordFieldName", "custbody_hc_order_id").one()
            assertEquals("NVL(custbody_hc_order_id, 'NO_VALUE') AS custbody_hc_order_id", column.sourceColumn)
            List filters = ec.entity.find("darpan.rule.RuleSetCompareSourceFilter").condition("compareScopeId", scope.compareScopeId).list()
            assertEquals(2, filters.size())
            assertTrue(filters.every { it.operator == "EXCLUDE_IN" && it.fileSide == "FILE_1" })
        } finally {
            ec.artifactExecution.enableAuthz()
        }
    }

    @Test
    void aSecondValueOnAFieldJoinsItsFilterAndRemovingOneKeepsTheOther() {
        String recId = lifecycle()
        rule(recId, "custbody_hc_sales_channel", "HC Sales Channel", "Shopify Draft Order")
        rule(recId, "custbody_hc_sales_channel", "HC Sales Channel", "FUEGO MOBILE APP")
        rule(recId, "custbody_hc_sales_channel", "HC Sales Channel", "FUEGO MOBILE APP")
        assertEquals(["Shopify Draft Order", "FUEGO MOBILE APP"], rules(recId)*.value, "a repeat is not added twice")

        call("facade.ReconciliationFacadeServices.delete#LifecycleRule",
                [reconciliationId: recId, fieldId: "custbody_hc_sales_channel", value: "Shopify Draft Order"])
        assertEquals(["FUEGO MOBILE APP"], rules(recId)*.value)
        call("facade.ReconciliationFacadeServices.delete#LifecycleRule",
                [reconciliationId: recId, fieldId: "custbody_hc_sales_channel", value: "FUEGO MOBILE APP"])
        assertEquals([], rules(recId))
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
    }

    @Test
    void aFieldOrValueThatCouldBreakTheQueryOrTheFilterIsRefused() {
        String recId = lifecycle()
        rule(recId, "custbody_x) OR 1=1", "Bad", "A")
        assertTrue(ec.message.hasError())
        ec.message.clearErrors()
        rule(recId, "custbody_hc_sales_channel", "HC Sales Channel", "a,b")
        assertTrue(ec.message.errors.any { it.contains("comma") }, ec.message.errors?.toString())
        ec.message.clearErrors()
        assertEquals([], rules(recId))
    }

    @Test
    void rulesChosenWhileSettingUpAreSavedWithTheLifecycleOrNotAtAll() {
        Map out = call("facade.ReconciliationFacadeServices.save#Lifecycle", [nsAuthConfigId: "LC_AUTH", recordType: "SalesOrd",
                stageRecordTypes: ["ItemShip", "CustInvc"], rules: [[fieldId: "custbody_hc_sales_channel", fieldLabel: "HC Sales Channel", value: "Shopify Draft Order"]]])
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals(["Shopify Draft Order"], rules(out.reconciliationId as String)*.value)

        long before = count()
        call("facade.ReconciliationFacadeServices.save#Lifecycle", [nsAuthConfigId: "LC_AUTH", recordType: "SalesOrd",
                stageRecordTypes: ["ItemShip"], rules: [[fieldId: "custbody_hc_sales_channel", fieldLabel: "HC Sales Channel", value: "a,b"]]])
        assertTrue(ec.message.hasError())
        ec.message.clearErrors()
        assertEquals(before, count(), "a refused rule leaves no half-made lifecycle")
    }

    private long count() {
        ec.artifactExecution.disableAuthz()
        try { return ec.entity.find("darpan.reconciliation.Reconciliation").count() } finally { ec.artifactExecution.enableAuthz() }
    }

    @Test
    void lateAfterIsASettingOfTheLifecycle() {
        String recId = lifecycle()
        call("facade.ReconciliationFacadeServices.save#Reconciliation", [reconciliationId: recId, lateAfterDays: 3])
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals(3L, call("facade.ReconciliationFacadeServices.get#Reconciliation", [reconciliationId: recId]).reconciliation.lateAfterDays as Long)
        call("facade.ReconciliationFacadeServices.save#Reconciliation", [reconciliationId: recId, lateAfterDays: 400])
        assertTrue(ec.message.hasError())
        ec.message.clearErrors()
    }

    // A stage exception: Store Pickup orders never ship, so Fulfillment is not expected of them. Every check that asks
    // about the stage carries it, the "is it missing?" ones and the "has it?" ones alike.
    @Test
    void aStageExceptionLandsOnEveryCheckOfThatStage() {
        String recId = lifecycle()
        Map out = call("facade.ReconciliationFacadeServices.save#LifecycleStageException",
                [reconciliationId: recId, stageRecordType: "ItemShip", fieldId: "shipmethod", fieldLabel: "Shipping Method", value: "Store Pickup"])
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals([["ItemShip", "Fulfillment", "shipmethod", "Shipping Method", "Store Pickup"]],
                (out.exceptions as List<Map>).collect { [it.stageRecordType, it.stageLabel, it.fieldId, it.fieldLabel, it.value] })

        Map got = call("facade.ReconciliationFacadeServices.get#Reconciliation", [reconciliationId: recId])
        assertEquals(["Fulfillment", "Invoice"], (got.stages as List<Map>)*.label)
        assertEquals(1, (got.exceptions as List).size())
        ec.artifactExecution.disableAuthz()
        try {
            List queries = (got.questions as List<Map>).findAll { it.questionRole != "START" }.collect { Map q ->
                def sc = ec.entity.find("darpan.rule.RuleSetCompareScope").condition("ruleSetId", q.ruleSetId).one()
                def src = ec.entity.find("darpan.rule.RuleSetCompareSource").condition("compareScopeId", sc.compareScopeId).one()
                ec.entity.find("darpan.reconciliation.NsSuiteQlSourceQuery").condition("nsSuiteQlSourceQueryId", src.sourceConfigId).one()
            }.unique { it.nsSuiteQlSourceQueryId }
            queries.each { def qy ->
                boolean ship = qy.absentChildRecordType == "ItemShip" || qy.presentChildRecordType == "ItemShip"
                assertEquals(ship ? [[fieldId: "shipmethod", fieldLabel: "Shipping Method", values: ["Store Pickup"]]] : null,
                        qy.waiveRules ? new groovy.json.JsonSlurper().parseText(qy.waiveRules as String) : null, qy.nsSuiteQlSourceQueryId as String)
            }
        } finally {
            ec.artifactExecution.enableAuthz()
        }

        // A stage holds exceptions on several fields: Store Pickup orders and gift-card orders are both not owed a fulfillment.
        Map both = call("facade.ReconciliationFacadeServices.save#LifecycleStageException",
                [reconciliationId: recId, stageRecordType: "ItemShip", fieldId: "item", fieldLabel: "Item", value: "GFT-CP"])
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals([["shipmethod", "Store Pickup"], ["item", "GFT-CP"]], (both.exceptions as List<Map>).collect { [it.fieldId, it.value] })
        call("facade.ReconciliationFacadeServices.save#LifecycleStageException",
                [reconciliationId: recId, stageRecordType: "ItemShip", fieldId: "shipmethod", fieldLabel: "Shipping Method", value: "Store' OR '1'='1"])
        assertTrue(ec.message.hasError())
        ec.message.clearErrors()

        Map left = call("facade.ReconciliationFacadeServices.delete#LifecycleStageException",
                [reconciliationId: recId, stageRecordType: "ItemShip", fieldId: "shipmethod", value: "Store Pickup"])
        assertEquals([["item", "GFT-CP"]], (left.exceptions as List<Map>).collect { [it.fieldId, it.value] })
        left = call("facade.ReconciliationFacadeServices.delete#LifecycleStageException",
                [reconciliationId: recId, stageRecordType: "ItemShip", fieldId: "item", value: "GFT-CP"])
        assertEquals([], left.exceptions)
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
    }

    /** Every active check's query, once each, with its shown columns in order. */
    List<List<List<String>>> columnsOfChecks(String recId) {
        ec.artifactExecution.disableAuthz()
        try {
            return (call("facade.ReconciliationFacadeServices.get#Reconciliation", [reconciliationId: recId]).questions as List<Map>).collect { Map q ->
                def sc = ec.entity.find("darpan.rule.RuleSetCompareScope").condition("ruleSetId", q.ruleSetId).one()
                ec.entity.find("darpan.rule.RuleSetCompareSource").condition("compareScopeId", sc.compareScopeId).one().sourceConfigId as String
            }.unique().collect { String queryId ->
                ec.entity.find("darpan.reconciliation.NsSuiteQlSourceQueryField").condition("nsSuiteQlSourceQueryId", queryId)
                        .orderBy("sequenceNum").list().collect { [it.recordFieldName as String, it.sourceColumn as String] }
            }
        } finally {
            ec.artifactExecution.enableAuthz()
        }
    }

    // A record shows the standard columns by name, then the custom fields chosen for it, on every check; a leave-out
    // rule's column stays, and a reorder's new checks show the same.
    @Test
    void detailsShowOnEveryCheckBesideTheStandardColumns() {
        String recId = lifecycle()
        rule(recId, "custbody_hc_order_id", "HC Order Id", LifecycleRuleSupport.NO_VALUE)
        Map out = call("facade.ReconciliationFacadeServices.save#LifecycleDetails", [reconciliationId: recId,
                details: [[fieldId: "custbody_hc_order_id", label: "HC Order Id"], [fieldId: "CUSTBODY_CELIGO_ETAIL_ORDER_ID", label: "eTail Order Id"]]])
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals([[fieldId: "custbody_hc_order_id", key: "hcOrderId"], [fieldId: "custbody_celigo_etail_order_id", key: "etailOrderId"]], out.details)

        List<String> shown = ["internalId", "order", "date", "status", "customer", "total", "shippingMethod", "reference", "hcOrderId", "etailOrderId"]
        columnsOfChecks(recId).each { List<List<String>> cols -> assertEquals(shown, cols*.get(0).findAll { it != "custbody_hc_order_id" }, cols.toString()) }
        List<List<String>> start = columnsOfChecks(recId).find { cols -> cols.any { it[0] == "custbody_hc_order_id" } }
        assertNotNull(start, "the leave-out rule's column is kept")
        assertEquals(["custbody_hc_order_id", "NVL(custbody_hc_order_id, 'NO_VALUE') AS custbody_hc_order_id"], start.last())
        assertTrue(start.contains(["hcOrderId", "custbody_hc_order_id AS detail1"]), start.toString())
        assertTrue(start.contains(["status", "BUILTIN.DF(status) AS status"]), start.toString())

        call("facade.ReconciliationFacadeServices.save#LifecycleStages", [reconciliationId: recId, stageRecordTypes: ["CustInvc", "ItemShip"]])
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        columnsOfChecks(recId).each { List<List<String>> cols -> assertEquals(shown, cols*.get(0).findAll { it != "custbody_hc_order_id" }, cols.toString()) }

        // Only a body field can be shown: the id reaches SuiteQL as a column.
        call("facade.ReconciliationFacadeServices.save#LifecycleDetails", [reconciliationId: recId, details: [[fieldId: "id; DROP", label: "x"]]])
        assertTrue(ec.message.hasError())
        ec.message.clearErrors()
    }

    // A Closed order reached its final state: a Status rule reads the record's own status column, by name, with no NVL.
    @Test
    void aStatusRuleReadsTheStatusEveryRecordShows() {
        String recId = lifecycle()
        Map out = rule(recId, "status", "Status", "Sales Order : Closed")
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        assertEquals([["status", "Closed"]], (out.rules as List<Map>).collect { [it.fieldId, it.valueLabel] })
        List<List<List<String>>> all = columnsOfChecks(recId)
        all.each { List<List<String>> cols -> assertEquals(1, cols.count { it[0] == "status" }, cols.toString()) }
        assertTrue(all.every { cols -> cols.contains(["status", "BUILTIN.DF(status) AS status"]) }, all.toString())
    }

    // A lifecycle's checks are named after it, so renaming it renames them: their names head the pages its records open on.
    @Test
    void renamingALifecycleRenamesItsChecks() {
        String recId = lifecycle()
        call("facade.ReconciliationFacadeServices.save#Reconciliation", [reconciliationId: recId, reconciliationName: "Gorjana Orders"])
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        ec.artifactExecution.disableAuthz()
        try {
            List<String> names = (call("facade.ReconciliationFacadeServices.get#Reconciliation", [reconciliationId: recId]).questions as List<Map>)
                    .collect { ec.entity.find("darpan.rule.RuleSet").condition("ruleSetId", it.ruleSetId).one().ruleSetName as String }
            assertTrue(names.every { it.startsWith("Gorjana Orders · ") }, names.toString())
            assertTrue(names.contains("Gorjana Orders · Billed, never shipped"), names.toString())
        } finally {
            ec.artifactExecution.enableAuthz()
        }
    }

    @Test
    void reorderingStagesRebuildsTheTreeAndKeepsFiltersExceptionsAndPastRuns() {
        String recId = lifecycle()   // ItemShip then CustInvc: shipped -> billed
        rule(recId, "custbody_hc_sales_channel", "HC Sales Channel", "Shopify Draft Order")
        call("facade.ReconciliationFacadeServices.save#LifecycleStageException",
                [reconciliationId: recId, stageRecordType: "ItemShip", fieldId: "shipmethod", fieldLabel: "Shipping Method", value: "Store Pickup"])
        List<String> before = (call("facade.ReconciliationFacadeServices.get#Reconciliation", [reconciliationId: recId]).questions as List<Map>)
                .findAll { it.questionRole != "START" }*.reconciliationRunId

        Map out = call("facade.ReconciliationFacadeServices.save#LifecycleStages", [reconciliationId: recId, stageRecordTypes: ["CustInvc", "ItemShip"]])
        assertFalse(ec.message.hasError(), ec.message.errors?.toString())
        // The name is the records', so a reorder keeps it.
        assertEquals(call("facade.ReconciliationFacadeServices.get#Reconciliation", [reconciliationId: recId]).reconciliation.reconciliationName,
                out.reconciliationName)

        Map got = call("facade.ReconciliationFacadeServices.get#Reconciliation", [reconciliationId: recId])
        assertEquals(["Invoice", "Fulfillment"], (got.stages as List<Map>)*.label)
        List<Map> qs = got.questions as List<Map>
        assertTrue(qs.every { !before.contains(it.reconciliationRunId) || it.questionRole == "START" }, "old stage questions are off")
        assertEquals(6, qs.size(), "the same START and five new stage questions")
        assertTrue(qs*.noOutcomeLabel.contains("Billed, never shipped"))
        assertEquals(["Shopify Draft Order"], (got.rules as List<Map>)*.value, "filters live on the START and stay")
        assertEquals([["ItemShip", "Store Pickup"]], (got.exceptions as List<Map>).collect { [it.stageRecordType, it.value] }, "the exception follows its stage")
    }

    @Test
    void onlyALifecycleBuiltFromStagesTakesRules() {
        String plain = call("facade.ReconciliationFacadeServices.save#Reconciliation", [reconciliationName: "Plain ${UUID.randomUUID()}".toString()])
                .reconciliation.reconciliationId
        rule(plain, "custbody_hc_sales_channel", "HC Sales Channel", "Shopify Draft Order")
        assertTrue(ec.message.hasError())
        ec.message.clearErrors()
    }
}
