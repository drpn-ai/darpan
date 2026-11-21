package darpan.conclusion.brain

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertNotSame
import static org.junit.jupiter.api.Assertions.assertSame

/** DAR-OPS-026. resetForTests empties the engine cache so the next lookup compiles afresh. */
class BrainEngineResetTests {

    @Test
    void resetDropsCachedEngines() {
        List<String> packs = ["oms-netsuite"]
        BrainEngine first = BrainEngine.forPacks(packs)
        assertSame(first, BrainEngine.forPacks(packs))
        BrainEngine.resetForTests()
        assertNotSame(first, BrainEngine.forPacks(packs))
        BrainEngine.resetForTests()
        BrainEngine.resetForTests()
    }
}
