package darpan.reconciliation.support

import org.moqui.context.ExecutionContext

import java.nio.file.Path

/**
 * DAR-OPS-029 S99-203: the small test helpers that were copied file to file. Import them statically.
 * <ul>
 *   <li>{@link #componentRoot}: the darpan component directory, for tests that read its XML, data or source;</li>
 *   <li>{@link #asSystem}: run a block with authz disabled, restoring it after (seed and inspect rows);</li>
 *   <li>{@link #upsert}: create or update one row from a field map.</li>
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
}
