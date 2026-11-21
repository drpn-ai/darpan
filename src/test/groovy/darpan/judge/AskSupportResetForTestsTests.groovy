package darpan.judge

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotSame
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-OPS-026: AskSupport.resetForTests restores the fetcher and reader seams. Pure: no Moqui. */
class AskSupportResetForTestsTests {

    @AfterEach
    void cleanup() { AskSupport.resetForTests() }

    @Test
    void "resetForTests restores the injected fetcher and reader and is idempotent"() {
        AskSupport.resetForTests()
        Closure defaultFetcher = AskSupport.fetcher
        Closure injectedFetcher = { Object... a -> [:] }
        Closure injectedReader = { Object ec, String c, String t -> [[askName: "x"]] }
        AskSupport.fetcher = injectedFetcher
        AskSupport.reader = injectedReader

        AskSupport.resetForTests()
        AskSupport.resetForTests()

        assertNotSame(injectedFetcher, AskSupport.fetcher)
        assertNotSame(injectedReader, AskSupport.reader)
        assertEquals(defaultFetcher.method, AskSupport.fetcher.method)

        AskReadChain chain = new AskReadChain()
        Object ec = [entity: chain]
        assertEquals([], AskSupport.reader.call(ec, "chk1", "ten1"))
        assertTrue(chain.calls.any { it.contains("chk1") })
        assertTrue(chain.calls.any { it.contains("ten1") })
    }
}

/** Records the entity-find chain AskSupport.readRows builds and returns no rows. */
class AskReadChain {
    List calls = []
    Object methodMissing(String name, Object args) {
        calls << ([name] + (args as List)).join(":")
        return name == "list" ? [] : this
    }
}
