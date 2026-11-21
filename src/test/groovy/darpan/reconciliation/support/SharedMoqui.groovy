package darpan.reconciliation.support

import org.junit.jupiter.api.extension.ExtendWith

import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

/**
 * DAR-OPS-026: opts a smoke class into the shared harness. ReconciliationSmokeTestSupport.initMoqui then reuses one
 * ExecutionContextFactory per test JVM instead of booting a fresh one: between classes it waits for the previous
 * class's background work to finish, resets the database to the empty schema, clears Moqui's data caches, gives the
 * class a fresh ec and login, and calls every resetForTests() (StaticResets.resetAll). The smokeSharedTest Gradle
 * pool picks marked classes up by this annotation's name in the source.
 *
 * The annotation goes on the concrete test class (SharedMoquiExtension reads the running class from JUnit, so the
 * lifecycle methods may be inherited from an unmarked base class). Mark a class only when it boots through initMoqui
 * alone, restores in @AfterAll any process-global seam or System property it changes, asserts no elapsed-time bound
 * and is not MySQL-tagged.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ExtendWith(SharedMoquiExtension)
@interface SharedMoqui {
}
