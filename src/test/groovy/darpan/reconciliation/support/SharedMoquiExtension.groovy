package darpan.reconciliation.support

import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * DAR-OPS-026: registered by @SharedMoqui. Tells the harness which concrete test class is running, so shared mode
 * holds even when initMoqui/cleanupMoqui sit in an inherited @BeforeAll/@AfterAll of an unmarked base class
 * (e.g. TwoFactorTestSupport). beforeAll runs before the class's @BeforeAll methods, afterAll after its @AfterAll ones.
 */
class SharedMoquiExtension implements BeforeAllCallback, AfterAllCallback {
    @Override
    void beforeAll(ExtensionContext context) {
        ReconciliationSmokeTestSupport.enterSharedClass(context.requiredTestClass)
    }

    @Override
    void afterAll(ExtensionContext context) {
        ReconciliationSmokeTestSupport.exitSharedClass(context.requiredTestClass)
    }
}
