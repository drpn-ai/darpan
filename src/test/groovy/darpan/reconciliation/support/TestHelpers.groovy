package darpan.reconciliation.support

import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-OPS-029 S99-203: the small test helpers that were copied file to file. Import them statically.
 * <ul>
 *   <li>{@link #componentRoot}: the darpan component directory, for tests that read its XML, data or source;</li>
 *   <li>{@link #asSystem}: run a block with authz disabled, restoring it after (seed and inspect rows);</li>
 *   <li>{@link #upsert}: create or update one row from a field map;</li>
 *   <li>{@link #withProductionAuthz}: run a block as production authz would, without the harness's blanket grant.</li>
 * </ul>
 */
class TestHelpers {

    /** runtime/component/darpan, found through {@link ReconciliationSmokeTestSupport#resolveBackendRoot}. */
    static Path componentRoot() {
        return ReconciliationSmokeTestSupport.resolveBackendRoot().resolve("runtime/component/darpan")
    }

    /** Runs {@code work} with authz disabled, re-enabling it after only when it was enabled before. */
    static <T> T asSystem(ExecutionContext ec, Closure<T> work) {
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            return work.call()
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    /** Creates or updates one {@code entityName} row from {@code fields} (primary key included). */
    static void upsert(ExecutionContext ec, String entityName, Map fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }

    /**
     * Runs {@code body} as production authz would: the harness's all-authorizing "smokeTests" frame popped, nothing else
     * on the artifact stack, authz on. Afterwards the harness frame and the authz state come back. Errors the body left in
     * ec.message stay for the caller to assert on, unless {@code clearErrorsAfter} (a test that calls services back to
     * back needs that: Moqui does not call a service while an earlier error is pending).
     */
    static <T> T withProductionAuthz(ExecutionContext ec, boolean clearErrorsAfter = false, Closure<T> body) {
        def harnessFrame = ec.artifactExecution.peek()
        assertEquals("smokeTests", harnessFrame?.name, "test precondition: unexpected artifact stack")
        ec.artifactExecution.pop(harnessFrame)
        boolean wasDisabled = ec.artifactExecution.authzDisabled
        ec.artifactExecution.enableAuthz()
        try {
            assertFalse(ec.artifactExecution.authzDisabled, "test precondition: authz must be ON")
            assertTrue(ec.artifactExecution.stack.isEmpty(), "test precondition: no inherited grant on the stack")
            return body.call()
        } finally {
            if (wasDisabled) ec.artifactExecution.disableAuthz()
            ec.artifactExecution.push("smokeTests", ArtifactExecutionInfo.AT_OTHER, ArtifactExecutionInfo.AUTHZA_ALL, false)
            ec.artifactExecution.setAnonymousAuthorizedAll()
            if (clearErrorsAfter) ec.message.clearErrors()
        }
    }
}
