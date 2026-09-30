package darpan.facade.reconciliation

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-060. "We do not delete anything, only disable."
 *
 * <p>The default is the load-bearing part. Every RuleSet written before isActive existed carries
 * a null, and a gate that read null as disabled would switch off every run in the system the
 * moment it deployed — a silent, total outage of the product's only function.</p>
 */
class SavedRunDisableTests {

    @Test
    void aRunWithNoFlagIsEnabled() {
        assertTrue(ReconciliationSavedRunSupport.isRunEnabled([ruleSetId: 'RS_LEGACY']))
    }

    @Test
    void anEmptyFlagIsEnabled() {
        assertTrue(ReconciliationSavedRunSupport.isRunEnabled([ruleSetId: 'RS', isActive: '']))
    }

    @Test
    void onlyAnExplicitNDisables() {
        assertFalse(ReconciliationSavedRunSupport.isRunEnabled([ruleSetId: 'RS', isActive: 'N']))
        assertFalse(ReconciliationSavedRunSupport.isRunEnabled([ruleSetId: 'RS', isActive: 'n']))
        assertTrue(ReconciliationSavedRunSupport.isRunEnabled([ruleSetId: 'RS', isActive: 'Y']))
    }

    /** A missing run is not a disabled run, but it must not be treated as runnable either. */
    @Test
    void aMissingRunIsNotRunnable() {
        assertFalse(ReconciliationSavedRunSupport.isRunEnabled(null))
    }

    @Test
    void theGateNamesTheRunAndSaysHowToUndoIt() {
        def ec = new StubEc()

        assertFalse(ReconciliationSavedRunSupport.requireRunEnabled(ec, [isActive: 'N'], 'Nightly OMS'))
        assertTrue(ec.errors.size() == 1)
        assertTrue(ec.errors[0].contains('Nightly OMS'), ec.errors[0] as String)
        assertTrue(ec.errors[0].toLowerCase().contains('enable'), ec.errors[0] as String)
    }

    @Test
    void theGatePassesAnEnabledRunSilently() {
        def ec = new StubEc()

        assertTrue(ReconciliationSavedRunSupport.requireRunEnabled(ec, [isActive: 'Y'], 'Nightly OMS'))
        assertTrue(ec.errors.isEmpty())
    }

    /** Minimal stand-in: the gate only ever reaches ec.message.addError. */
    private static class StubEc {
        List<String> errors = []

        // ec.message resolves through the getter; a same-named field alongside it is dead weight
        // that reads as if either might win.
        Object getMessage() { return new Object() { void addError(String m) { errors.add(m) } } }
    }
}
