package darpan.reconciliation.automation

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

/** DAR-OPS-026: resetForTests restores the execution hooks, peak loaders and run admission. */
class AutomationExecutionSupportResetTests {
    @Test void resetRestoresEverySeam() {
        Closure extractor = { -> null }
        Closure loader = { -> [] }
        Closure zone = { -> null }
        AutomationExecutionSupport.setSourceExtractor(extractor)
        AutomationExecutionSupport.setReconcileRunner(extractor)
        AutomationExecutionSupport.setPeakWindowsLoader(loader)
        AutomationExecutionSupport.setTenantZoneLoader(zone)
        AutomationExecutionSupport.setRunAdmission(new RunAdmission(1, 1, 100, { -> 0 }, { Runnable r -> } as java.util.concurrent.Executor))
        AutomationExecutionSupport.resetForTests()
        assertNotSame(loader, AutomationExecutionSupport.peakWindowsLoader)
        assertNotSame(zone, AutomationExecutionSupport.tenantZoneLoader)
        assertSame(RunAdmission.SHARED, AutomationExecutionSupport.getRunAdmission())
        AutomationExecutionSupport.resetForTests()  // idempotent
        assertSame(RunAdmission.SHARED, AutomationExecutionSupport.getRunAdmission())
    }
}
