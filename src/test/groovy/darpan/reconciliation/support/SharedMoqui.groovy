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
 * lifecycle methods may be inherited from an unmarked base class). Mark a class only when it: boots through the
 * harness (initMoqui directly, an extended base, or a helper); restores in @AfterAll every seam it swaps; sets no
 * System property of its own; asserts no elapsed-time bound; is not @Tag mysql or perf; starts NO Spark session; and
 * leaves NO background work running past its end (the quiet gate fails the class by name).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@ExtendWith(SharedMoquiExtension)
@interface SharedMoqui {
    /**
     * When true (default) the class fails itself if background work is still running when it ends. Only the harness's
     * own probe classes set false: they leave async work on purpose to prove the next class's reset waits for it.
     */
    boolean quietAtEnd() default true
}
