package darpan.agent

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
 * DAR-BE-194. A user's AI access in one tenant: whether their grant is live, what it has spent (the ledger's own
 * AgentTurn.costMicros), and the pause once spend reaches the limit. Tenants, users and amounts are synthetic.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentAccessSmokeTests {
    private static final String ALPHA = "AAS_ALPHA"
    private static final String BETA = "AAS_BETA"
    private static final String ANN = "AAS_ANN"
    private static final String BOB = "AAS_BOB"
    private static final long HOUR = 3_600_000L

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "agent-access")
        asSystem {
            [ALPHA, BETA].each { String t ->
                ec.entity.makeValue("darpan.agent.AgentConversation").setAll([conversationId: "${t}_CONV".toString(), companyUserGroupId: t,
                        userId: ANN, createdDate: now()]).createOrUpdate()
            }
        }
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void reset() {
        asSystem {
            ["darpan.agent.AgentTurn", "darpan.agent.AgentAccessGrantEvent", "darpan.agent.AgentAccessGrant"].each { ec.entity.find(it).deleteAll() }
        }
    }

    private static Timestamp now() { new Timestamp(System.currentTimeMillis()) }

    private static Timestamp inHours(long h) { new Timestamp(System.currentTimeMillis() + h * HOUR) }

    private void grant(String id, String tenant, String user, Map extra = [:]) {
        asSystem {
            ec.entity.makeValue("darpan.agent.AgentAccessGrant").setAll([grantId: id, companyUserGroupId: tenant, userId: user,
                    fromDate: inHours(-1), statusId: "ACTIVE"] + extra).create()
        }
    }

    private void turn(String id, String grantId, String tenant, long micros) {
        asSystem {
            ec.entity.makeValue("darpan.agent.AgentTurn").setAll([turnId: id, conversationId: "${tenant}_CONV".toString(), companyUserGroupId: tenant,
                    userId: ANN, grantId: grantId, role: "AUTHOR", costMicros: micros, startedDate: now()]).create()
        }
    }

    private List<Map> events(String grantId) {
        asSystem { ec.entity.find("darpan.agent.AgentAccessGrantEvent").condition("grantId", grantId).useCache(false).list()*.getMap() }
    }

    private <T> T asSystem(Closure<T> work) {
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try { return work.call() } finally { if (!wasDisabled) ec.artifactExecution.enableAuthz() }
    }

    @Test
    void "no grant, a revoked grant and a grant that starts tomorrow are NOT_GRANTED"() {
        assertEquals(AgentAccess.NOT_GRANTED, AgentAccess.access(ec, ALPHA, ANN).state)
        grant("G_REV", ALPHA, ANN, [statusId: "REVOKED"])
        assertEquals(AgentAccess.NOT_GRANTED, AgentAccess.access(ec, ALPHA, ANN).state)
        grant("G_LATER", ALPHA, BOB, [fromDate: inHours(24)])
        Map later = AgentAccess.access(ec, ALPHA, BOB)
        assertEquals(AgentAccess.NOT_GRANTED, later.state, "a window that has not started is not live, and not expired either")
        assertEquals("G_LATER", later.grantId)
    }

    @Test
    void "a grant whose thruDate has passed is EXPIRED"() {
        grant("G_OLD", ALPHA, ANN, [fromDate: inHours(-48), thruDate: inHours(-24)])
        Map a = AgentAccess.access(ec, ALPHA, ANN)
        assertEquals(AgentAccess.EXPIRED, a.state)
        assertEquals("G_OLD", a.grantId)
        assertNotNull(a.thruDate)
    }

    @Test
    void "a live grant is LIVE with its spend summed from its own turns only"() {
        grant("G_LIVE", ALPHA, ANN, [thruDate: inHours(24), costCapMicros: 5_000_000L])
        grant("G_OTHER", BETA, ANN)
        turn("T1", "G_LIVE", ALPHA, 300L)
        turn("T2", "G_LIVE", ALPHA, 200L)
        turn("T3", "G_OTHER", BETA, 9_000L)
        Map a = AgentAccess.access(ec, ALPHA, ANN)
        assertEquals(AgentAccess.LIVE, a.state)
        assertEquals("G_LIVE", a.grantId)
        assertEquals(500L, a.spentMicros)
        assertEquals(5_000_000L, a.capMicros)
        assertEquals(500L, AgentAccess.spentMicros(ec, "G_LIVE"))
        assertEquals(0L, AgentAccess.spentMicros(ec, "G_NONE"))
    }

    @Test
    void "spend at the cap reads PAUSED and pauseIfOverLimit pauses once with one AUTO_PAUSE event"() {
        grant("G_CAP", ALPHA, ANN, [costCapMicros: 500L])
        turn("T1", "G_CAP", ALPHA, 500L)
        assertEquals(AgentAccess.PAUSED, AgentAccess.access(ec, ALPHA, ANN).state, "at the limit is no longer live, even before the pause is written")
        assertTrue(AgentAccess.pauseIfOverLimit(ec, "G_CAP"))
        Map row = asSystem { ec.entity.find("darpan.agent.AgentAccessGrant").condition("grantId", "G_CAP").useCache(false).one().getMap() }
        assertEquals(["PAUSED", "COST_LIMIT"], [row.statusId, row.pauseReason])
        assertNotNull(row.pausedDate)
        assertTrue(AgentAccess.pauseIfOverLimit(ec, "G_CAP"), "still paused")
        List<Map> evts = events("G_CAP")
        assertEquals(1, evts.size(), "a second call writes no second event")
        assertEquals(["AUTO_PAUSE", "SYSTEM"], [evts[0].action, evts[0].byUserId])
        assertTrue((evts[0].beforeJson as String).contains("ACTIVE") && (evts[0].afterJson as String).contains("PAUSED"), evts[0].toString())
        assertEquals("COST_LIMIT", AgentAccess.access(ec, ALPHA, ANN).pauseReason)
    }

    @Test
    void "spend under the cap or no cap never pauses"() {
        grant("G_UNDER", ALPHA, ANN, [costCapMicros: 1_000L])
        turn("T1", "G_UNDER", ALPHA, 999L)
        assertFalse(AgentAccess.pauseIfOverLimit(ec, "G_UNDER"))
        grant("G_NOCAP", ALPHA, BOB)
        turn("T2", "G_NOCAP", ALPHA, 9_999_999L)
        assertFalse(AgentAccess.pauseIfOverLimit(ec, "G_NOCAP"))
        assertEquals(AgentAccess.LIVE, AgentAccess.access(ec, ALPHA, BOB).state)
        assertEquals([], events("G_UNDER") + events("G_NOCAP"))
    }

    @Test
    void "another tenant's grant or another user's grant does not count"() {
        grant("G_BETA", BETA, ANN)
        grant("G_BOB", ALPHA, BOB)
        assertEquals(AgentAccess.NOT_GRANTED, AgentAccess.access(ec, ALPHA, ANN).state)
        assertEquals(AgentAccess.LIVE, AgentAccess.access(ec, BETA, ANN).state)
        assertEquals(AgentAccess.NOT_GRANTED, AgentAccess.access(ec, ALPHA, null).state)
        assertEquals(AgentAccess.NOT_GRANTED, AgentAccess.access(ec, null, ANN).state)
    }

    @Test
    void "microsOf converts exactly and refuses zero and negatives"() {
        assertEquals(5_005_000L, AgentAccess.microsOf(new BigDecimal("5.005")))
        assertEquals(1L, AgentAccess.microsOf(new BigDecimal("0.0000005")), "half a micro-dollar rounds up")
        assertThrows(IllegalArgumentException) { AgentAccess.microsOf(BigDecimal.ZERO) }
        assertThrows(IllegalArgumentException) { AgentAccess.microsOf(new BigDecimal("-1")) }
        assertThrows(IllegalArgumentException) { AgentAccess.microsOf(null) }
    }
}
