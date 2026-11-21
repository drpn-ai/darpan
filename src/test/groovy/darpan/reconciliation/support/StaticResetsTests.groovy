package darpan.reconciliation.support

import org.junit.jupiter.api.Test

import java.lang.reflect.Method

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-OPS-026: a resetForTests the sweep cannot call fails loudly instead of being skipped. */
class StaticResetsTests {
    static class Callable { static void resetForTests() { } }
    static class OnlyCalls { static void other() { Callable.resetForTests() } }
    static class ReturnsValue { static def resetForTests() { return null } }
    static class Instance { void resetForTests() { } }
    static class TakesArgs { static void resetForTests(boolean hard) { } }

    @Test
    void keepsStaticVoidNoArgResetsAndSkipsClassesThatOnlyCallOne() {
        List<Method> found = StaticResets.resetMethodsOf([Callable, OnlyCalls] as List<Class<?>>)
        assertEquals([Callable], found*.declaringClass)
    }

    @Test
    void failsOnAResetDeclaredInAShapeTheSweepCannotCall() {
        [ReturnsValue, Instance, TakesArgs].each { Class<?> bad ->
            AssertionError e = assertThrows(AssertionError) { StaticResets.resetMethodsOf([Callable, bad] as List<Class<?>>) }
            assertTrue(e.message.contains(bad.name.replace('$', '.')) || e.message.contains(bad.name), e.message)
        }
    }
}
