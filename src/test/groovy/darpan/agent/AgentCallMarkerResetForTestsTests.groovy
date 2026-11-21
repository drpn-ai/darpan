package darpan.agent

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-OPS-026: AgentCallMarker.resetForTests drops the calling thread's mark. Pure: no Moqui. */
class AgentCallMarkerResetForTestsTests {

    @Test
    void "resetForTests clears a nested mark on the calling thread and is idempotent"() {
        AgentCallMarker.resetForTests()
        assertFalse(AgentCallMarker.inAgentLoop())
        AgentCallMarker.run {
            AgentCallMarker.run {
                assertTrue(AgentCallMarker.inAgentLoop())
                AgentCallMarker.resetForTests()
                assertFalse(AgentCallMarker.inAgentLoop())
            }
            // the unwinding runs went below zero from a reset; the mark must stay clear
            assertFalse(AgentCallMarker.inAgentLoop())
        }
        assertFalse(AgentCallMarker.inAgentLoop())
        AgentCallMarker.resetForTests()
        AgentCallMarker.resetForTests()
        assertFalse(AgentCallMarker.inAgentLoop())
    }
}
