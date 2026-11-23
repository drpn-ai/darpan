package darpan.migration

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 3b-2c. The retired automation filter table is dropped by a registered migration: prod's database enforces its
 * foreign key to ReconciliationAutomationSource, so its rows would block every automation save once no code clears them.
 * The table no longer has an entity definition, so each test makes it by hand.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetiredTableSupportTests {
    private static final String TABLE = "RECONCILIATION_AUTOMATION_SOURCE_FILTER"
    private static final String SERVICE = "reconciliation.ReconciliationNotificationServices.retire#AutomationSourceFilterTable"
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "retired-table-smoke")
    }

    @AfterAll
    void cleanup() {
        ddl("DROP TABLE IF EXISTS ${TABLE}")
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void freshTable() {
        ddl("DROP TABLE IF EXISTS ${TABLE}")
        ddl("CREATE TABLE ${TABLE} (AUTOMATION_ID VARCHAR(40) NOT NULL, FILE_SIDE VARCHAR(20) NOT NULL)")
        ddl("INSERT INTO ${TABLE} (AUTOMATION_ID, FILE_SIDE) VALUES ('A1', 'FILE_1'), ('A1', 'FILE_2')")
    }

    @Test
    void aDryRunCountsTheRowsAndKeepsTheTable() {
        Map out = ec.service.sync().name(SERVICE).parameters([dryRun: true]).disableAuthz().call()

        assertEquals([true, true, 2L, false], [out.ok, out.tableFound, out.rowCount as Long, out.dropped], out.toString())
        assertTrue(tableExists())
    }

    @Test
    void aRunDropsTheTable() {
        Map out = ec.service.sync().name(SERVICE).parameters([dryRun: false]).disableAuthz().call()

        assertEquals([true, true, 2L, true], [out.ok, out.tableFound, out.rowCount as Long, out.dropped], out.toString())
        assertFalse(tableExists())
    }

    @Test
    void aSecondRunFindsNoTable() {
        RetiredTableSupport.dropRetiredTable(ec, "transactional", TABLE, false)

        Map again = RetiredTableSupport.dropRetiredTable(ec, "transactional", TABLE, false)

        assertEquals([tableFound: false, rowCount: 0L, dropped: false], again)
    }

    private boolean tableExists() {
        return (boolean) withConnection { Connection con ->
            ResultSet rs = con.metaData.getTables(null, null, TABLE, null)
            try { return rs.next() } finally { rs.close() }
        }
    }

    private void ddl(String sql) {
        withConnection { Connection con ->
            Statement st = con.createStatement()
            try { st.executeUpdate(sql) } finally { st.close() }
        }
    }

    /** Outside any transaction, as Moqui runs its own table changes (EntityDbMeta.runSqlUpdate). */
    private Object withConnection(Closure work) {
        return ec.transaction.runRequireNew(30, "retired-table test", false, true, {
            Connection con = ec.entity.getConnection("transactional")
            try { return work(con) } finally { con.close() }
        })
    }
}
