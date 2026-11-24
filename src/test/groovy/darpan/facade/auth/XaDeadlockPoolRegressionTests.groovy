package darpan.facade.auth

import darpan.facade.auth.support.MysqlLoginTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.moqui.context.ExecutionContext

import java.nio.file.Paths
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * DAR-BE-340: one InnoDB deadlock must not leave a pooled connection unusable.
 *
 * The deadlocked branch's XA END answers XA_RBDEADLOCK. Bitronix treats that as "rolled back by the resource" and
 * never sends XA ROLLBACK, so MySQL keeps the session in the XA ROLLBACK ONLY state. The pool hands that connection
 * out next, and every XA START on it fails with XAER_RMFAIL "... in the ROLLBACK ONLY state": in prod every read
 * answered 500 for 16 minutes. The framework's XaEndRollbackDataSource sends the XA ROLLBACK.
 *
 * MySQL, not H2: the H2 XA resource never answers XA_RB* on end. The victim's connection is released after the
 * winner's, so it is the next one the pool hands out.
 */
@Tag("mysql")
class XaDeadlockPoolRegressionTests {
    static ExecutionContext ec
    static final String TABLE = "DAR_BE_340_DEADLOCK_PROBE"

    @BeforeAll
    static void setup() {
        ec = MysqlLoginTestSupport.initMoqui(Paths.get(System.getProperty("user.dir")).parent.parent.parent,
                "xa-deadlock-pool")
        // DDL commits implicitly in MySQL, so it runs with no transaction open
        Connection con = ec.entity.getConnection("transactional")
        try {
            exec(con, "CREATE TABLE IF NOT EXISTS ${TABLE} (ID INT PRIMARY KEY, V INT NOT NULL) ENGINE=InnoDB")
            exec(con, "REPLACE INTO ${TABLE} (ID, V) VALUES (1, 0), (2, 0)")
        } finally { con.close() }
    }

    @AfterAll
    static void teardown() {
        try {
            Connection con = ec.entity.getConnection("transactional")
            try { exec(con, "DROP TABLE IF EXISTS ${TABLE}") } finally { con.close() }
        } catch (Throwable ignored) { }
        MysqlLoginTestSupport.cleanupMoqui(ec)
    }

    @Test
    void connectionIsUsableAfterItsTransactionDeadlocked() {
        CountDownLatch bothLocked = new CountDownLatch(2)
        CountDownLatch winnerDone = new CountDownLatch(1)
        ExecutorService threads = Executors.newFixedThreadPool(2)
        List<String> outcomes
        try {
            Future<String> a = threads.submit({ lockBoth(1, 2, 0L, bothLocked, winnerDone) } as Callable<String>)
            Future<String> b = threads.submit({ lockBoth(2, 1, 300L, bothLocked, winnerDone) } as Callable<String>)
            outcomes = [a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS)]
        } finally { threads.shutdownNow() }
        assert outcomes.sort() == ["committed", "deadlock"]

        3.times { assert readInTransaction() == 1 }
    }

    /** Lock row {@code first}, wait for the other thread, then lock {@code second}: one of the two deadlocks. */
    private static String lockBoth(int first, int second, long pauseMs, CountDownLatch bothLocked, CountDownLatch winnerDone) {
        ExecutionContext tec = ec.factory.getExecutionContext()
        try {
            boolean began = tec.transaction.begin(30)
            Connection con = tec.entity.getConnection("transactional")
            exec(con, "UPDATE ${TABLE} SET V = V + 1 WHERE ID = ${first}")
            bothLocked.countDown()
            assert bothLocked.await(10, TimeUnit.SECONDS)
            Thread.sleep(pauseMs)
            try {
                exec(con, "UPDATE ${TABLE} SET V = V + 1 WHERE ID = ${second}")
            } catch (SQLException e) {
                if (e.errorCode != 1213) throw e
                assert winnerDone.await(30, TimeUnit.SECONDS)
                tec.transaction.rollback(began, "DAR-BE-340 test: expected deadlock", e)
                return "deadlock"
            }
            tec.transaction.commit(began)
            winnerDone.countDown()
            return "committed"
        } finally { tec.destroy() }
    }

    private static int readInTransaction() {
        boolean began = ec.transaction.begin(30)
        try {
            Connection con = ec.entity.getConnection("transactional")
            int rows = 0
            con.createStatement().withCloseable { st ->
                st.executeQuery("SELECT V FROM ${TABLE} WHERE ID = 1").withCloseable { rs -> while (rs.next()) rows++ }
            }
            ec.transaction.commit(began)
            return rows
        } catch (Throwable t) {
            ec.transaction.rollback(began, "DAR-BE-340 test read failed", t)
            throw t
        }
    }

    private static void exec(Connection con, String sql) {
        con.createStatement().withCloseable { it.execute(sql) }
    }
}
