package darpan.datasource

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonOutput
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.Moqui
import org.moqui.context.ExecutionContext

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-235. Authoring data-defined reads through facade.DataSourceDefinitionFacadeServices: a bad document is refused with
 * its problems, a save makes the family's next version as a DRAFT and leaves an ACTIVE row as it was, a definition a
 * connector reads cannot be retired, and only a super admin may do any of it. Each call runs on its own thread as a real
 * user, the artifact fence bypassed, so the in-service super-admin gate is what decides.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DataSourceDefinitionServicesTests {

    private ExecutionContext ec
    private static final String SVC = "facade.DataSourceDefinitionFacadeServices"
    private static final String SUPER = "dsdef.super"
    private static final String PLAIN = "dsdef.plain"
    private static final Timestamp T0 = Timestamp.valueOf("2026-01-01 00:00:00")

    /** A REST_JSON read of a made-up system that passes the check. */
    static String goodDocument(String path = "/v1/orders") {
        JsonOutput.toJson([connection: [urlTemplate: "{baseUrl}/api", auth: [kinds: [NONE: [method: "NONE"]]]], limits: [maxCostPerCall: 10],
                           steps     : [[id    : "orders", kind: "query",
                                         document: JsonOutput.toJson([method: "GET", path: path, query: [limit: '$first', cursor: '$after'],
                                                                      recordsAt: "data[*]", paging: [mode: "CURSOR", nextAt: "meta.next"]]),
                                         bind  : [all: [variables: [:]]], paging: [firstVariable: "first", afterVariable: "after", pageSize: 50],
                                         reservation: 1]],
                           record    : [fields: ["orderNo"]]])
    }

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "data-source-definition-services")
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void reset() {
        ["darpan.reconciliation.SourceSystemConnector", "darpan.datasource.DataSourceDefinition", "moqui.security.UserGroupMember"].each {
            ec.entity.find(it).disableAuthz().deleteAll()
        }
        [[userId: "DSDEF_SUPER", username: SUPER], [userId: "DSDEF_PLAIN", username: PLAIN]].each { Map u ->
            ec.entity.makeValue("moqui.security.UserAccount").setAll(u + [userFullName: u.username, emailAddress: "${u.username}@example.com".toString(),
                    disabled: "N", requirePasswordChange: "N"]).createOrUpdate()
        }
        ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userGroupId: "DARPAN_SUPER_ADMIN", userId: "DSDEF_SUPER", fromDate: T0]).create()
        ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userGroupId: "DARPAN_USER", userId: "DSDEF_PLAIN", fromDate: T0]).create()
        row("ACME_LEDGER_V1", "ACTIVE", 1, goodDocument())
    }

    private void row(String id, String status, int version, String json) {
        ec.entity.makeValue("darpan.datasource.DataSourceDefinition").setAll([dataSourceDefinitionId: id, definitionName: "Acme ledger",
                protocol: "REST_JSON", definitionVersion: version, statusId: status, definitionJson: json, createdDate: T0, lastUpdatedDate: T0]).create()
    }

    private Map definition(String id) {
        def v = ec.entity.find("darpan.datasource.DataSourceDefinition").condition("dataSourceDefinitionId", id).disableAuthz().useCache(false).one()
        return v == null ? null : v.getMap()
    }

    private Map callAs(String username, String verbNoun, Map params) {
        Map holder = [:]
        Thread worker = new Thread({
            ExecutionContext tec = Moqui.getExecutionContextFactory().getExecutionContext()
            try {
                if (!tec.user.internalLoginUser(username)) throw new IllegalStateException("could not log in ${username}")
                holder.result = tec.service.sync().name("${SVC}.${verbNoun}").parameters(new LinkedHashMap(params)).disableAuthz().call()
                holder.errors = new ArrayList<String>(tec.message.errors)
            } catch (Throwable t) {
                holder.failure = t
            } finally {
                tec.destroy()
            }
        } as Runnable, "dsdef-${username}")
        worker.start()
        worker.join()
        if (holder.failure) throw (Throwable) holder.failure
        return holder
    }

    @Test
    void aBadDocumentIsRefusedWithEveryProblemAndAGoodOnePasses() {
        Map bad = callAs(SUPER, "validate#DataSourceDefinition", [protocol: "REST_JSON", definitionJson: goodDocument("v1/../admin")])
        assertEquals([], bad.errors)
        assertFalse(bad.result.valid as boolean)
        assertTrue((bad.result.problems as List<String>).any { it.contains("path starts with /") }, bad.result.problems.toString())
        assertTrue(callAs(SUPER, "validate#DataSourceDefinition", [protocol: "REST_JSON", definitionJson: goodDocument()]).result.valid as boolean)
        assertFalse(callAs(SUPER, "validate#DataSourceDefinition", [protocol: "REST_JSON", definitionJson: "{nope"]).result.valid as boolean)

        Map refused = callAs(SUPER, "create#DataSourceDefinition", [definitionId: "ACME_RETURNS", definitionName: "Acme returns", protocol: "REST_JSON",
                definitionJson: goodDocument("v1/../admin")])
        assertTrue(refused.errors.any { it.contains("path starts with /") }, refused.errors.toString())
        assertNull(definition("ACME_RETURNS_V1"), "nothing saved")
    }

    @Test
    void aNewFamilyStartsAtVersionOneAsADraft() {
        Map out = callAs(SUPER, "create#DataSourceDefinition", [definitionId: "ACME_RETURNS", definitionName: "Acme returns", protocol: "REST_JSON",
                definitionJson: goodDocument()])
        assertEquals([], out.errors)
        assertEquals(["ACME_RETURNS_V1", 1], [out.result.dataSourceDefinitionId, out.result.definitionVersion as int])
        assertEquals(["DRAFT", 1L, "REST_JSON"], definition("ACME_RETURNS_V1").with { [it.statusId, it.definitionVersion as long, it.protocol] })

        Map again = callAs(SUPER, "create#DataSourceDefinition", [definitionId: "ACME_RETURNS", definitionName: "x", protocol: "REST_JSON", definitionJson: goodDocument()])
        assertTrue(again.errors.any { it.contains("already exists") }, again.errors.toString())
        Map versioned = callAs(SUPER, "create#DataSourceDefinition", [definitionId: "ACME_V2", definitionName: "x", protocol: "REST_JSON", definitionJson: goodDocument()])
        assertTrue(versioned.errors.any { it.contains("not ending in _V") }, versioned.errors.toString())
    }

    @Test
    void aSaveBumpsTheVersionAndTheActiveRowStaysAsItWas() {
        Map before = definition("ACME_LEDGER_V1")
        Map out = callAs(SUPER, "create#DataSourceDefinition", [basedOnId: "ACME_LEDGER_V1", definitionJson: goodDocument("/v2/orders")])
        assertEquals([], out.errors)
        assertEquals(["ACME_LEDGER_V2", 2], [out.result.dataSourceDefinitionId, out.result.definitionVersion as int])
        Map v2 = definition("ACME_LEDGER_V2")
        assertEquals(["DRAFT", "Acme ledger", "REST_JSON"], [v2.statusId, v2.definitionName, v2.protocol], "name and protocol come from the family")
        assertTrue((v2.definitionJson as String).contains("/v2/orders"))
        assertEquals(before, definition("ACME_LEDGER_V1"), "the ACTIVE row is never edited in place")

        assertEquals("ACME_LEDGER_V3", callAs(SUPER, "create#DataSourceDefinition", [basedOnId: "ACME_LEDGER_V1", definitionJson: goodDocument()])
                .result.dataSourceDefinitionId, "the family's next version, whichever row it is based on")
        assertTrue(callAs(SUPER, "create#DataSourceDefinition", [basedOnId: "NO_SUCH_V1", definitionJson: goodDocument()]).errors
                .any { it.contains("was not found") })
    }

    @Test
    void aDefinitionAConnectorReadsCannotBeRetired() {
        ec.entity.makeValue("darpan.reconciliation.SourceSystemConnector").setAll([systemEnumId: "ACME_ORDERS", dataSourceDefinitionId: "ACME_LEDGER_V1"]).create()
        Map refused = callAs(SUPER, "retire#DataSourceDefinition", [dataSourceDefinitionId: "ACME_LEDGER_V1"])
        assertTrue(refused.errors.any { it.contains("still read by 1 connector") }, refused.errors.toString())
        assertEquals("ACTIVE", definition("ACME_LEDGER_V1").statusId)

        ec.entity.find("darpan.reconciliation.SourceSystemConnector").disableAuthz().deleteAll()
        assertEquals([], callAs(SUPER, "retire#DataSourceDefinition", [dataSourceDefinitionId: "ACME_LEDGER_V1"]).errors)
        assertEquals("RETIRED", definition("ACME_LEDGER_V1").statusId)
        assertEquals([], callAs(SUPER, "retire#DataSourceDefinition", [dataSourceDefinitionId: "ACME_LEDGER_V1"]).errors, "again is a no-op")
    }

    @Test
    void aNonAdminIsRefusedEverythingAndNothingIsWritten() {
        Map before = definition("ACME_LEDGER_V1")
        [["validate#DataSourceDefinition", [protocol: "REST_JSON", definitionJson: goodDocument()]],
         ["create#DataSourceDefinition", [definitionId: "ACME_RETURNS", definitionName: "x", protocol: "REST_JSON", definitionJson: goodDocument()]],
         ["create#DataSourceDefinition", [basedOnId: "ACME_LEDGER_V1", definitionJson: goodDocument()]],
         ["retire#DataSourceDefinition", [dataSourceDefinitionId: "ACME_LEDGER_V1"]]].each { List c ->
            Map out = callAs(PLAIN, (String) c[0], (Map) c[1])
            assertEquals(["This operation requires super-admin access."], out.errors, c[0] as String)
        }
        assertEquals(1L, ec.entity.find("darpan.datasource.DataSourceDefinition").disableAuthz().useCache(false).count())
        assertEquals(before, definition("ACME_LEDGER_V1"))
    }
}
