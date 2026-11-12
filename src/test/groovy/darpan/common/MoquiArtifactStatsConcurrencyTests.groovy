package darpan.common

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ArtifactExecutionInfo.ArtifactType
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ExecutionContextFactoryImpl

import java.nio.file.Path
import java.util.concurrent.CountDownLatch

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Moqui counts every service, screen and entity hit in one map shared by all threads (drpn-ai/moqui-framework,
 * ExecutionContextFactoryImpl.artifactStatsInfoByType). Darpan's async config tracking (DAR-BE-160) counts hits on worker
 * threads, so the map is written from several threads at once. As a plain HashMap it lost entries and could hold a null
 * value, and Moqui's shutdown then failed on it (an NPE in destroy(): CI on main 2026-11-11, ee0a7905).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MoquiArtifactStatsConcurrencyTests {

    private static final String PREFIX = "darpan.test.StatsRace.hit"

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "moqui-artifact-stats-concurrency")
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @Test
    void hitsCountedOnManyThreadsAtOnceAreAllKeptAndNoneIsNull() {
        ExecutionContextFactoryImpl ecfi = (ExecutionContextFactoryImpl) ec.factory
        Map stats = ecfi.@artifactStatsInfoByType
        int threads = 8
        int perThread = 400
        CountDownLatch start = new CountDownLatch(1)
        List<Throwable> failures = Collections.synchronizedList([])
        List<Thread> workers = (0..<threads).collect { int t ->
            Thread.start {
                try {
                    start.await()
                    long now = System.currentTimeMillis()
                    for (int i = 0; i < perThread; i++) {
                        ecfi.countArtifactHit(ArtifactType.AT_SERVICE, null, "${PREFIX}${t}_${i}".toString(), null, now, 1.0d, null)
                    }
                } catch (Throwable e) {
                    failures << e
                }
            }
        }
        start.countDown()
        workers*.join(60_000L)

        try {
            assertTrue(failures.isEmpty(), "no hit may fail: ${failures*.toString()}")
            List keys = new ArrayList(stats.keySet()).findAll { it != null && it.toString().startsWith(PREFIX) }
            assertEquals(threads * perThread, keys.size(), "every hit's stats entry is kept")
            assertFalse(new ArrayList(stats.values()).contains(null), "no stats entry is null")
        } finally {
            // Leave nothing for shutdown to persist.
            new ArrayList(stats.keySet()).findAll { it != null && it.toString().startsWith(PREFIX) }.each { stats.remove(it) }
        }
    }
}
