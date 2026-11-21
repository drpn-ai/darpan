package darpan.reconciliation.source

import darpan.reconciliation.automation.SourceSystemConnectorSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonOutput
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 (plan 3b-2b, Task 9). A side's Keep plan: where its Keep runs (SPARK, READ or NONE), the tenant's filter rows read
 * for one named tenant only, and the connector's declared built-ins ahead of them. Every connector here is made up (ACME*):
 * core holds no system name, and no system component's rows are needed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeepPlanSupportTests {

    private static final String A = "KP_TENANT_A"
    private static final String B = "KP_TENANT_B"

    /** Two entries: the second is skipped when the side reads only cancelled records. */
    private static final String ACME_KEEP_LINES = JsonOutput.toJson([
            [sequenceNum: 2, label: "Draft records", kinds: [stage: "FVT_TEXT"],
             lines: [[conditionSeqId: "01", sequenceNum: 1, conditionTypeId: "RCT_COMPARE", fieldName: "stage",
                      operatorEnumId: "ENTCO_EQUALS", compareValue: "DRAFT"]]],
            [sequenceNum: 1, label: "Cancelled records", appliesUnless: [onlyCancelled: "true"], kinds: [isCancelled: "FVT_BOOLEAN"],
             lines: [[conditionSeqId: "01", sequenceNum: 1, conditionTypeId: "RCT_COMPARE", fieldName: "isCancelled",
                      operatorEnumId: "ENTCO_EQUALS", compareValue: "true"]]],
    ])

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "keep-plan-support")
        Closure<String> filter = { String scopeId, String fileSide, int seq, String field, String operator, String values, String tenant ->
            """<darpan.rule.RuleSetCompareSourceFilter compareScopeId="${scopeId}" fileSide="${fileSide}" sequenceNum="${seq}"
                fieldExpression="\$.records[*].${field}" operator="${operator}" filterValues="${values}" companyUserGroupId="${tenant}"/>"""
        }
        Closure<String> scope = { String scopeId, String tenant ->
            """<darpan.rule.RuleSet ruleSetId="${scopeId}_RS" ruleSetName="${scopeId}" companyUserGroupId="${tenant}"/>
            <darpan.rule.RuleSetCompareScope compareScopeId="${scopeId}" ruleSetId="${scopeId}_RS" objectType="ORDER" companyUserGroupId="${tenant}"/>
            <darpan.rule.RuleSetCompareSource compareScopeId="${scopeId}" fileSide="FILE_1" systemEnumId="ACME" companyUserGroupId="${tenant}"/>
            <darpan.rule.RuleSetCompareSource compareScopeId="${scopeId}" fileSide="FILE_2" systemEnumId="ACME" companyUserGroupId="${tenant}"/>"""
        }
        String keepLinesAttr = ACME_KEEP_LINES.replace("&", "&amp;").replace("\"", "&quot;")
        ec.entity.makeDataLoader().xmlText("""<entity-facade-xml type="seed">
            <darpan.reconciliation.SourceSystemConnector systemEnumId="ACME" extractServiceName="reconciliation.AcmeExtractionServices.extract#Records"
                filterParameterName="excludeFilters" excludedRecordsSidecar="Y" keepLinesJson="${keepLinesAttr}"/>
            <darpan.reconciliation.SourceSystemConnector systemEnumId="ACME_QUIET" extractServiceName="reconciliation.AcmeExtractionServices.extract#Quiet"
                filterParameterName="excludeFilters" excludedRecordsSidecar="N"/>
            <darpan.reconciliation.SourceSystemConnector systemEnumId="ACME_DEFINED" extractServiceName="datasource.DataSourceServices.extract#DataDefinedSource"
                filterParameterName="excludeFilters" excludedRecordsSidecar="Y" dataSourceDefinitionId="ACME_DEF" keepLinesJson="${keepLinesAttr}"/>
            <darpan.reconciliation.SourceSystemConnector systemEnumId="ACME_UPLOAD" extractServiceName="reconciliation.AcmeExtractionServices.extract#Upload"
                keepLinesJson="${keepLinesAttr}"/>
            <darpan.reconciliation.SourceSystemConnector systemEnumId="ACME_BAD" extractServiceName="reconciliation.AcmeExtractionServices.extract#Bad"
                filterParameterName="excludeFilters" keepLinesJson='[{"sequenceNum":1,"label":"Has lines","lines":[{"conditionSeqId":"01","conditionTypeId":"RCT_EXISTS","fieldName":"lines"}]}]'/>
            ${scope('KP_SCOPE_A', A)}
            ${scope('KP_SCOPE_B', B)}
            ${filter('KP_SCOPE_A', 'FILE_1', 2, 'channel', 'INCLUDE_IN', 'WEB,POS', A)}
            ${filter('KP_SCOPE_A', 'FILE_1', 1, 'orderType', 'EXCLUDE_IN', 'TRANSFER', A)}
            ${filter('KP_SCOPE_A', 'FILE_2', 1, 'other', 'EXCLUDE_IN', 'X', A)}
            ${filter('KP_SCOPE_B', 'FILE_1', 1, 'secretField', 'EXCLUDE_IN', 'B_ONLY', B)}
        </entity-facade-xml>""").dummyFks(true).load()
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    private Map connector(String systemEnumId) {
        Map resolved = SourceSystemConnectorSupport.resolveBySystemEnumId(ec, systemEnumId)
        assertEquals(systemEnumId, resolved?.systemEnumId, "test precondition: ${systemEnumId} resolves")
        return resolved
    }

    @Test
    void placementIsNoneForAnUploadSparkForACodedConnectorReadForADataDefinedOne() {
        assertEquals("NONE", KeepPlanSupport.placement(null))
        assertEquals("NONE", KeepPlanSupport.placement([:]))
        assertEquals("NONE", KeepPlanSupport.placement([filterParameterName: "  "]))
        assertEquals("NONE", KeepPlanSupport.placement(connector("ACME_UPLOAD")))
        assertEquals("SPARK", KeepPlanSupport.placement(connector("ACME")))
        assertEquals("READ", KeepPlanSupport.placement(connector("ACME_DEFINED")))

        Map none = KeepPlanSupport.sideKeep(ec, "KP_SCOPE_A", "FILE_1", A, connector("ACME_UPLOAD"), [:])
        assertEquals([placement: "NONE", readFilters: [], rules: [], writeSidecar: false, fields: [] as Set], none,
                "an upload gets no filters and no Keep, its declared lines included")

        Map read = KeepPlanSupport.sideKeep(ec, "KP_SCOPE_A", "FILE_1", A, connector("ACME_DEFINED"), [:])
        assertEquals("READ", read.placement)
        assertEquals([], read.rules, "a data-defined side keeps filtering at read (Ruling 3b2b-16): no Spark rules, no built-ins")
        assertEquals([[1, "orderType", "EXCLUDE_IN", "TRANSFER"], [2, "channel", "INCLUDE_IN", "WEB,POS"]],
                read.readFilters.collect { [it.sequenceNum as Integer, it.fieldExpression, it.operator, it.filterValues] },
                "the read gets today's getter-shape rows, in sequence order")
        assertEquals(["orderType", "channel"] as Set, read.fields)
        assertFalse(read.writeSidecar, "the read writes its own side file; Spark writes none")

        Map spark = KeepPlanSupport.sideKeep(ec, "KP_SCOPE_A", "FILE_2", A, connector("ACME"), [:])
        assertEquals("SPARK", spark.placement)
        assertEquals([], spark.readFilters, "a Spark side's read gets no filters")
        assertEquals(["BUILT_IN:1", "BUILT_IN:2", "A:1"], spark.rules*.id)
    }

    /**
     * Ruling 3b2b-23: Spark keeps a SPARK side only once its extract no longer declares the connector's filter parameter; an
     * extract that still takes it filters at the read, and so does any side of another placement or with no extract installed.
     */
    @Test
    void sparkKeepsASideOnlyOnceItsExtractStopsTakingTheFilterParameter() {
        Map stillFilters = [inParameterNameList: ["sourceConfigId", "excludeFilters", "keepFields"]]
        Map noLongerFilters = [inParameterNameList: ["sourceConfigId", "keepRuleFields"]]
        assertFalse(KeepPlanSupport.sparkKeepsSide(connector("ACME"), stillFilters))
        assertTrue(KeepPlanSupport.sparkKeepsSide(connector("ACME"), noLongerFilters))
        assertFalse(KeepPlanSupport.sparkKeepsSide(connector("ACME"), null), "an extract that is not installed")
        assertFalse(KeepPlanSupport.sparkKeepsSide(connector("ACME_DEFINED"), noLongerFilters), "a data-defined side keeps its read")
        assertFalse(KeepPlanSupport.sparkKeepsSide(connector("ACME_UPLOAD"), noLongerFilters), "an upload gets no Keep at all")
        assertEquals([placement: "READ", readFilters: [[sequenceNum: 1]], rules: [], writeSidecar: false, fields: [] as Set],
                KeepPlanSupport.todaysRead([[sequenceNum: 1]]), "today's read: the rows to the read, nothing to Spark, no field added")
    }

    /** Review Focus 4: a scheduled read for tenant A never sees tenant B's filter rows, with no active tenant. */
    @Test
    void aScopeOfAnotherTenantReadsAsNoRules() {
        assertEquals([], KeepPlanSupport.tenantRuleRows(ec, "KP_SCOPE_B", "FILE_1", A))
        assertEquals(["secretField"], KeepPlanSupport.tenantRuleRows(ec, "KP_SCOPE_B", "FILE_1", B)*.fieldExpression,
                "the owner reads its row (control)")
        assertEquals([], KeepPlanSupport.tenantRuleRows(ec, "KP_SCOPE_A", "FILE_1", B))

        Map plan = KeepPlanSupport.sideKeep(ec, "KP_SCOPE_B", "FILE_1", A, connector("ACME_QUIET"), [:])
        assertEquals([], plan.rules)
        assertEquals([] as Set, plan.fields)
    }

    @Test
    void aBlankTenantIsRefused() {
        [null, "", "  "].each { String tenant ->
            assertThrows(IllegalArgumentException) { KeepPlanSupport.tenantRuleRows(ec, "KP_SCOPE_A", "FILE_1", tenant) }
            assertThrows(IllegalArgumentException) { KeepPlanSupport.sideKeep(ec, "KP_SCOPE_A", "FILE_1", tenant, connector("ACME"), [:]) }
            assertThrows(IllegalArgumentException) { KeepPlanSupport.sideKeep(ec, "KP_SCOPE_A", "FILE_1", tenant, connector("ACME_UPLOAD"), [:]) }
        }
    }

    @Test
    void builtInsComeBeforeTheTenantsRules() {
        Map plan = KeepPlanSupport.sideKeep(ec, "KP_SCOPE_A", "FILE_1", A, connector("ACME"), [:])
        assertEquals(["BUILT_IN:1", "BUILT_IN:2", "A:1", "A:2"], plan.rules*.id)
        assertEquals(["BUILT_IN", "BUILT_IN", "TENANT", "TENANT"], plan.rules*.source)
        assertEquals(["Cancelled records", "Draft records", "orderType", "channel"], plan.rules*.label,
                "a tenant rule's label is its field, as _excludedBy names it today")
        assertEquals(["isCancelled", "stage", "orderType", "channel"] as Set, plan.fields)
        KeepRules.validate(plan.rules)

        Map include = plan.rules[3]
        assertEquals(["FIELD_ABSENT", "VALUE"], include.tests*.reason, "an include rule rejects no value first, then an unlisted value")
        assertEquals([kind: "compare", field: "channel", text: "GROOVY", op: "NOT_IN", values: ["WEB", "POS"], valueKind: "TEXT", fold: "A_UPPER"],
                include.tests[1].test)

        Map skipped = KeepPlanSupport.sideKeep(ec, "KP_SCOPE_A", "FILE_1", A, connector("ACME"), [onlyCancelled: true])
        assertEquals(["BUILT_IN:2", "A:1", "A:2"], skipped.rules*.id, "an entry is skipped when the side sets its appliesUnless option")

        // Ruling 3b2b-15: the automation path brings its own filter rows (loadAutomationSourceFilters' getter shape)
        List<Map<String, Object>> automationRows = [[sequenceNum: 1, fieldExpression: "kind", operator: "INCLUDE_PREFIX", filterValues: "EXC-"]]
        Map automation = KeepPlanSupport.sideKeepFromFilterRows(ec, automationRows, connector("ACME"), [:])
        assertEquals(["BUILT_IN:1", "BUILT_IN:2", "A:1"], automation.rules*.id)
        assertEquals([kind: "not", of: [kind: "compare", field: "kind", text: "GROOVY", op: "STARTS_WITH_ANY", values: ["EXC-"],
                                        valueKind: "TEXT", fold: "A_UPPER"]], automation.rules[2].tests[1].test)
        Map automationRead = KeepPlanSupport.sideKeepFromFilterRows(ec, automationRows, connector("ACME_DEFINED"), [:])
        assertEquals(automationRows, automationRead.readFilters)
        assertEquals([], automationRead.rules)
    }

    @Test
    void writeSidecarFollowsTheConnectorRow() {
        assertTrue(KeepPlanSupport.sideKeep(ec, "KP_SCOPE_A", "FILE_1", A, connector("ACME"), [:]).writeSidecar as boolean)
        assertFalse(KeepPlanSupport.sideKeep(ec, "KP_SCOPE_A", "FILE_1", A, connector("ACME_QUIET"), [:]).writeSidecar as boolean)
        assertFalse(KeepPlanSupport.sideKeep(ec, "KP_SCOPE_A", "FILE_1", A, connector("ACME_DEFINED"), [:]).writeSidecar as boolean)
        assertFalse(KeepPlanSupport.sideKeep(ec, "KP_SCOPE_A", "FILE_1", A, connector("ACME_UPLOAD"), [:]).writeSidecar as boolean)
    }

    /** Built-ins are data on a connector row (Ruling 3b2b-12): a made-up system's lines compile with no system component. */
    @Test
    void aMadeUpSystemsDeclaredKeepLinesCompile() {
        assertNull(ec.entity.find(SourceSystemConnectorSupport.ENTITY_NAME).condition("systemEnumId", "ACME_ABSENT").one())
        Map plan = KeepPlanSupport.sideKeepFromFilterRows(ec, [], connector("ACME"), [:])
        assertEquals([
                [id: "BUILT_IN:1", sequenceNum: 1, label: "Cancelled records", source: "BUILT_IN", tests: [[reason: "BUILT_IN",
                        test: [kind: "compare", field: "isCancelled", text: "FACT", op: "EQ", values: ["true"], valueKind: "BOOLEAN", fold: "NONE"]]]],
                [id: "BUILT_IN:2", sequenceNum: 2, label: "Draft records", source: "BUILT_IN", tests: [[reason: "BUILT_IN",
                        test: [kind: "compare", field: "stage", text: "FACT", op: "EQ", values: ["DRAFT"], valueKind: "TEXT", fold: "NONE"]]]],
        ], plan.rules)
        assertEquals(["isCancelled", "stage"], KeepRules.injections(plan.rules)*.field)

        IllegalArgumentException refused = assertThrows(IllegalArgumentException) {
            KeepPlanSupport.sideKeepFromFilterRows(ec, [], connector("ACME_BAD"), [:])
        }
        assertTrue(refused.message.contains("Has lines"), "a refused declared line is a data error naming the entry: ${refused.message}")
    }
}
