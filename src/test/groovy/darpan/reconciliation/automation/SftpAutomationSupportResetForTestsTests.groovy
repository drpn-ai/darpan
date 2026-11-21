package darpan.reconciliation.automation

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertSame

class SftpAutomationSupportResetForTestsTests {
    @Test
    void resetForTestsRestoresTheDefaultFactoryAndSleeper() {
        Closure defaultFactory = SftpAutomationSupport.@clientFactory
        Closure defaultSleeper = SftpAutomationSupport.@retrySleeper
        SftpAutomationSupport.setClientFactory({ String h, String u, Integer p -> "fake" })
        SftpAutomationSupport.setRetrySleeper({ long ms -> })
        assertSame("fake", SftpAutomationSupport.createClient("h", "u", 22))
        SftpAutomationSupport.resetForTests()
        assertSame(defaultFactory, SftpAutomationSupport.@clientFactory)
        assertSame(defaultSleeper, SftpAutomationSupport.@retrySleeper)
        SftpAutomationSupport.resetForTests()
        assertSame(defaultFactory, SftpAutomationSupport.@clientFactory)
    }
}
