package darpan.reconciliation.conclusion

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.moqui.context.ExecutionContext

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-UI-064 — a run's conclusion tree through the facade, in the ACTIVE tenant (KREWE): saved as a tree, read back in
 * order; a refused tree writes nothing; another tenant's run is not found.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation)
class RunConclusionAuthoringSmokeTests {

    private static final String FACADE = "facade.RunConclusionFacadeServices"
    private static final String MINE = "KREWE"
    private static final String OTHER = "TCON_OTHER"
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "run-conclusion-authoring")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)   // signs in a KREWE editor, KREWE active
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            insert("moqui.security.UserGroup", [userGroupId: OTHER, description: "Other tenant"])
            insert("moqui.basic.EnumerationType", [enumTypeId: "DarpanConclusion", description: "Conclusion"])
            [["CONC_TCON_OPEN", "RMA still open"], ["CONC_TCON_PENDING", "Pending receipt"], ["CONC_TCON_DEEP", "Deep"]].each {
                insert("moqui.basic.Enumeration", [enumId: it[0], enumTypeId: "DarpanConclusion", enumCode: it[0], description: it[1]])
            }
            insert("moqui.basic.EnumerationType", [enumTypeId: "TconOther", description: "Not a conclusion"])
            insert("moqui.basic.Enumeration", [enumId: "TCON_NOT_A_CONCLUSION", enumTypeId: "TconOther", enumCode: "X"])
            insert("moqui.basic.Enumeration", [enumId: "CONC_TCON_THEIRS", enumTypeId: "DarpanConclusion", enumCode: "CONC_TCON_THEIRS",
                    description: "Their private label"])
            insert("darpan.rule.TenantConclusion", [enumId: "CONC_TCON_THEIRS", companyUserGroupId: OTHER])
            run("TCON_RS", MINE, "COMPARE")
            run("TCON_RS_EVAL", MINE, "EVALUATE")
            run("TCON_RS_OTHER", OTHER, "COMPARE")
            run("TCON_RS_NOMODE", MINE, null)
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void clearMessages() {
        ec.message.clearErrors()
    }

    @Test
    @Order(1)
    void savesATreeAndReadsItBackInOrder() {
        Map out = facade("save#RunConclusions", [savedRunId: "TCON_RS", conclusions: [
                [key: "a", conclusion: "CONC_TCON_OPEN", bucket: "RULE", fix: "Close RMA {rma} in NetSuite.", conditions: [
                        [subject: "FILE_1", field: "statusId", operator: "IN", values: ["RETURN_CANCELLED"], checkLabel: "Cancelled in HotWax"],
                        [subject: "FILE_2", presence: "KEPT", checkLabel: "An open RMA"],
                        [subject: "FILE_1", field: "refundAmount", operator: "gt", values: ["5"], checkLabel: "More than \$5"]]],
                [key: "b", parent: "a", conclusion: "CONC_TCON_PENDING", conditions: [
                        [subject: "FILE_2", field: "status", operator: "IN", values: ["Return Authorization : Pending Receipt"],
                         checkLabel: "Pending receipt"]]]]])
        assertFalse(out.hadError as boolean, out.errorText as String)
        List nodes = (List) ((Map) out.savedConclusions).conclusions
        assertEquals(["10", "20"], nodes*.key)
        assertEquals([null, "10"], nodes*.parent)
        assertEquals("RMA still open", nodes[0].label)
        assertEquals([["RETURN_CANCELLED"], [], ["5"]], nodes[0].conditions*.values)
        assertEquals("GT", nodes[0].conditions[2].operator)
        assertEquals("KEPT", nodes[0].conditions[1].presence)
        assertEquals(["Close RMA {rma} in NetSuite.", null], nodes*.fix, "DAR-BE-120: a cause's own fix")

        Map read = (Map) facade("get#RunConclusions", [savedRunId: "TCON_RS"]).conclusions
        assertEquals(nodes, read.conclusions)
        assertTrue(read.catalog*.id.contains("CONC_TCON_OPEN"))
        assertFalse(read.catalog*.id.contains("TCON_NOT_A_CONCLUSION"))
        assertEquals(["MISSING_FROM_FILE_1", "MISSING_FROM_FILE_2", "RULE", "FINDING"], read.buckets)
    }

    @Test
    @Order(2)
    void aRefusedTreeWritesNothing() {
        Map ok = [key: "a", conclusion: "CONC_TCON_OPEN", conditions: [[field: "status", operator: "NOT_BLANK", checkLabel: "x"]]]
        [
                [tree: [ok + [conclusion: "TCON_NOT_A_CONCLUSION"]], says: "is not a conclusion"],
                [tree: [ok + [parent: "z"]], says: "not above it"],
                [tree: [ok + [bucket: "EVERYTHING"]], says: "applies to"],
                [tree: [ok + [conditions: []]], says: "at least one condition"],
                [tree: [ok + [fix: "x" * 1001]], says: "fix longer than 1000"],
                [tree: [ok + [conditions: [[field: "status) OR (1", operator: "IN", values: ["x"], checkLabel: "x"]]]], says: "not a record field"],
                [tree: [ok + [conditions: [[field: "status", operator: "IN", values: ["a,b"], checkLabel: "x"]]]], says: "without commas"],
                [tree: [ok + [conditions: [[field: "status", operator: "NOT_BLANK"]]]], says: "says what it checks"],
                [tree: [ok + [conditions: [[field: "unapplied", operator: "GT", checkLabel: "x"]]]], says: "one number"],
                [tree: [ok + [conditions: [[field: "unapplied", operator: "GT", values: ["five"], checkLabel: "x"]]]], says: "one number"],
                [tree: [ok + [conditions: [[field: "unapplied", operator: "GT", values: ["5", "6"], checkLabel: "x"]]]], says: "one number"],
                [tree: [ok, ok + [key: "b", parent: "a"], ok + [key: "c", parent: "b"], ok + [key: "d", parent: "c"]], says: "levels deep"],
                [tree: [ok, ok], says: "share the key"],
        ].each { Map c ->
            Map out = facade("save#RunConclusions", [savedRunId: "TCON_RS", conclusions: c.tree])
            assertTrue(out.hadError as boolean, "should refuse: ${c.says}")
            assertTrue((out.errorText as String).contains(c.says as String), out.errorText as String)
        }
        assertEquals(2, ((Map) facade("get#RunConclusions", [savedRunId: "TCON_RS"]).conclusions).conclusions.size(), "the saved tree is untouched")
    }

    @Test
    @Order(3)
    void aOneSystemRunReadsOnlyItsOwnSide() {
        Map out = facade("save#RunConclusions", [savedRunId: "TCON_RS_EVAL", conclusions: [[key: "a", conclusion: "CONC_TCON_OPEN",
                conditions: [[subject: "FILE_2", field: "status", operator: "NOT_BLANK", checkLabel: "x"]]]]])
        assertTrue(out.hadError as boolean)
        assertTrue((out.errorText as String).contains("reads one system"), out.errorText as String)
    }

    @Test
    @Order(3)
    void aScopeWithNoModeIsATwoSystemRun() {
        // DAR-BE-168: SM's RS_PROD_ORDERS scope has no scopeMode; saving its tree answered a 500.
        Map out = facade("save#RunConclusions", [savedRunId: "TCON_RS_NOMODE", conclusions: [[key: "a", conclusion: "CONC_TCON_OPEN",
                conditions: [[subject: "FILE_2", field: "status", operator: "NOT_BLANK", checkLabel: "x"]]]]])
        assertFalse(out.hadError as boolean, out.errorText as String)
        assertEquals(1, ((Map) out.savedConclusions).conclusions.size())
    }

    @Test
    @Order(4)
    void anotherTenantsRunIsNotFoundAndAnEmptyListClears() {
        assertTrue(facade("get#RunConclusions", [savedRunId: "TCON_RS_OTHER"]).hadError as boolean)
        assertTrue(facade("save#RunConclusions", [savedRunId: "TCON_RS_OTHER", conclusions: []]).hadError as boolean)
        Map cleared = facade("save#RunConclusions", [savedRunId: "TCON_RS", conclusions: []])
        assertFalse(cleared.hadError as boolean, cleared.errorText as String)
        assertEquals([], ((Map) cleared.savedConclusions).conclusions)
    }

    @Test
    @Order(5)
    void aTenantMakesItsOwnCodeAndNeverSeesAnothersLabel() {
        Map made = facade("create#RunConclusionCode", [label: "HG credit not on 42065"])
        assertFalse(made.hadError as boolean, made.errorText as String)
        String id = (made.conclusionCode as Map).id
        assertTrue(id.startsWith("CONC_"), id)
        assertEquals("KREWE", row("darpan.rule.TenantConclusion", id).companyUserGroupId)

        List catalog = (List) ((Map) facade("get#RunConclusions", [savedRunId: "TCON_RS"]).conclusions).catalog
        assertTrue(catalog.find { it.id == id }?.own as boolean, "the tenant's own code is listed, marked own")
        assertTrue(catalog*.id.contains("CONC_TCON_OPEN"), "shared codes are listed")
        assertFalse(catalog*.label.contains("Their private label"), "another tenant's code never reaches this tenant")

        Map again = facade("create#RunConclusionCode", [label: "hg credit NOT on 42065"])
        assertTrue(again.hadError as boolean)
        assertTrue((again.errorText as String).contains("already a conclusion"), again.errorText as String)

        Map theirs = facade("save#RunConclusions", [savedRunId: "TCON_RS", conclusions: [[key: "a", conclusion: "CONC_TCON_THEIRS",
                conditions: [[field: "status", operator: "NOT_BLANK", checkLabel: "x"]]]]])
        assertTrue(theirs.hadError as boolean, "another tenant's code cannot be named")
        Map mine = facade("save#RunConclusions", [savedRunId: "TCON_RS", conclusions: [[key: "a", conclusion: id,
                conditions: [[field: "status", operator: "NOT_BLANK", checkLabel: "x"]]]]])
        assertFalse(mine.hadError as boolean, mine.errorText as String)
    }

    // DAR-BE-174: a run's lookups are set with its tree (SM RS_PROD_ORDERS: the order's items). Absent keeps them; a lookup
    // through a connector that cannot be asked by id, or with a part missing, is refused and writes nothing.
    @Test
    @Order(6)
    void aRunsLookupsAreSavedWithItsTree() {
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/DarpanSystemSourceSeedData.xml",
                "component://darpan/data/SourceSystemConnectorSeedData.xml")
        Map items = [name: "items", subject: "FILE_1", idField: "orderId", systemEnumId: "OMS_ORDER_ITEMS_GQL",
                     sourceConfigId: "steve_madden_prodhw", keyField: "omsOrderId", many: true]
        Map node = [key: "a", conclusion: "CONC_TCON_OPEN", bucket: "RULE", conditions: [[subject: "FILE_1", field: "items.statusId",
                operator: "IN", values: ["ITEM_COMPLETED", "ITEM_CANCELLED"], quantifier: "ALL", checkLabel: "Every item completed or cancelled"]]]

        Map saved = facade("save#RunConclusions", [savedRunId: "TCON_RS", conclusions: [node], lookups: [items]])
        assertFalse(saved.hadError as boolean, saved.errorText as String)
        assertEquals([items], ((Map) saved.savedConclusions).lookups)

        Map kept = facade("save#RunConclusions", [savedRunId: "TCON_RS", conclusions: [node]])
        assertFalse(kept.hadError as boolean, kept.errorText as String)
        assertEquals([items], ((Map) facade("get#RunConclusions", [savedRunId: "TCON_RS"]).conclusions).lookups, "absent keeps them")

        [[lookups: [items + [systemEnumId: "TCON_NO_SUCH_SYSTEM"]], says: "cannot be asked by id"],
         [lookups: [items - [keyField: "omsOrderId"]], says: "needs keyField"],
         [lookups: [items + [many: "yes"]], says: "many is true or false"],
         [lookups: [items, items], says: "Two lookups are named items"],
         [lookups: [items + [parameters: [outputLocation: "runtime://elsewhere"]]], says: "take only keepRecordFields"]].each { Map c ->
            Map out = facade("save#RunConclusions", [savedRunId: "TCON_RS", conclusions: [], lookups: c.lookups])
            assertTrue(out.hadError as boolean, "should refuse: ${c.says}")
            assertTrue((out.errorText as String).contains(c.says as String), out.errorText as String)
        }
        Map read = (Map) facade("get#RunConclusions", [savedRunId: "TCON_RS"]).conclusions
        assertEquals([items], read.lookups, "a refused save writes nothing")
        assertEquals(1, read.conclusions.size())

        Map cleared = facade("save#RunConclusions", [savedRunId: "TCON_RS", conclusions: [node], lookups: []])
        assertFalse(cleared.hadError as boolean, cleared.errorText as String)
        assertEquals([], ((Map) cleared.savedConclusions).lookups, "an empty list clears them")
    }

    private def row(String entity, String id) {
        return ec.entity.find(entity).condition("enumId", id).disableAuthz().useCache(false).one()
    }

    private Map facade(String verbNoun, Map params = [:]) {
        Map out = ec.service.sync().name("${FACADE}.${verbNoun}").parameters(params).call() ?: [:]
        Map result = out + [hadError: ec.message.hasError(), errorText: ec.message.errorsString]
        ec.message.clearErrors()
        return result
    }

    private void run(String ruleSetId, String tenant, String mode) {
        insert("darpan.rule.RuleSet", [ruleSetId: ruleSetId, ruleSetName: "${ruleSetId} run".toString(), version: "1.0", companyUserGroupId: tenant])
        insert("darpan.rule.RuleSetCompareScope", [compareScopeId: "${ruleSetId}_SCOPE".toString(), ruleSetId: ruleSetId,
                scopeMode: mode, objectType: "RETURN", companyUserGroupId: tenant])
    }

    private void insert(String entityName, Map<String, Object> fields) {
        ReconciliationSmokeTestSupport.insertEntityDirect(ec, entityName, fields)
    }
}
