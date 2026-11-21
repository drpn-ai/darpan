package darpan.agent

import darpan.agent.pipeline.AuditChangePlanner
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertSame
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-OPS-026: resetForTests on the agent-query and change-planner seams restores their defaults. Pure: no Moqui. */
class AgentQueryResetForTestsTests {

    @Test
    void "AgentQueryLane resetForTests empties the lane and is idempotent"() {
        assertTrue(AgentQueryLane.tryAcquire("t1"))
        assertTrue(AgentQueryLane.tryAcquire("t1"))
        assertFalse(AgentQueryLane.tryAcquire("t1"))
        AgentQueryLane.resetForTests()
        AgentQueryLane.resetForTests()
        assertEquals(0, AgentQueryLane.inFlight("t1"))
        assertTrue(AgentQueryLane.tryAcquire("t1"))
        AgentQueryLane.resetForTests()
    }

    @Test
    void "AgentQuerySupport resetForTests restores the timeout"() {
        AgentQuerySupport.timeoutMillis = 5L
        AgentQuerySupport.resetForTests()
        assertEquals(60_000L, AgentQuerySupport.timeoutMillis)
    }

    @Test
    void "AuditChangePlanner resetForTests restores the real backtest"() {
        Closure original = AuditChangePlanner.backtest
        AuditChangePlanner.backtest = { Object... a -> [:] }
        AuditChangePlanner.resetForTests()
        assertSame(original, AuditChangePlanner.backtest)
    }
}
