package darpan.facade.auth.support

import org.moqui.Moqui
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext
import org.moqui.impl.context.ExecutionContextFactoryImpl

import java.nio.file.Path

class MysqlLoginTestSupport {
    static ExecutionContext initMoqui(Path backendRoot, String testToken) {
        String runtimePath = backendRoot.resolve("runtime").toString()
        String safe = testToken.replaceAll(/[^A-Za-z0-9_-]/, "_")
        Path txLogPath = backendRoot.resolve("runtime/tmp/test-txlog/${safe}")

        System.setProperty("moqui.runtime", runtimePath)
        System.setProperty("moqui_runtime", runtimePath)
        // InnoDB, not H2: H2 does not implement FK parent-row locking, so the deadlock
        // under test is unreproducible there.
        // mysql8 db conf also serves MySQL 9.x — the wire protocol and the InnoDB locking
        // semantics this test depends on are unchanged.
        System.setProperty("entity_ds_db_conf", "mysql8")
        System.setProperty("entity_ds_host", "127.0.0.1")
        System.setProperty("entity_ds_port", "3306")
        System.setProperty("entity_ds_database", "darpan_test")
        System.setProperty("entity_ds_user", "darpan_test")
        System.setProperty("entity_ds_password", "Darpan_Test@2026")

        txLogPath.toFile().mkdirs()
        System.setProperty("bitronix.tm.journal.disk.logPart1Filename", txLogPath.resolve("btm1.tlog").toString())
        System.setProperty("bitronix.tm.journal.disk.logPart2Filename", txLogPath.resolve("btm2.tlog").toString())
        System.setProperty("bitronix.tm.serverId", "test-${safe}".toString().take(45))

        if (Moqui.getExecutionContextFactory() != null && !Moqui.getExecutionContextFactory().isDestroyed()) {
            Moqui.destroyActiveExecutionContextFactory()
        }
        Moqui.dynamicInit(new ExecutionContextFactoryImpl(runtimePath, "conf/MoquiDevConf.xml"))

        ExecutionContext ec = Moqui.getExecutionContext()
        assert ec.user.loginAnonymousIfNoUser()
        ec.artifactExecution.disableAuthz()
        ec.artifactExecution.push("mysqlLoginTests", ArtifactExecutionInfo.AT_OTHER,
                ArtifactExecutionInfo.AUTHZA_ALL, false)
        ec.artifactExecution.setAnonymousAuthorizedAll()
        ec.message.clearErrors()
        return ec
    }

    static void cleanupMoqui(ExecutionContext ec) {
        try { ec?.destroy() } catch (Exception ignored) { }
        if (Moqui.getExecutionContextFactory() != null && !Moqui.getExecutionContextFactory().isDestroyed()) {
            Moqui.destroyActiveExecutionContextFactory()
        }
    }
}
