package darpan.migration

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import java.sql.Connection
import java.sql.DriverManager

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-424. NSRESTLET_DROP over an in-memory H2 laid out as an old database has it: the RESTlet table with the
 * __TABLE_BOOTSTRAP__ placeholder, both source columns with their foreign keys, the shares, the enumeration and filter rows,
 * and NS_AUTH_CONFIG.NETSUITE_ACCOUNT. The report comes first on every run; an active side naming a RESTlet refuses the real
 * run; the statements run in dependency order (keys, columns, table) and a second run has nothing to do. MySQL's own DROP
 * FOREIGN KEY path is proven by NsRestletDropMoquiMysqlTests.
 */
class NsRestletDropTests {

    private Connection db

    @BeforeEach
    void setup() {
        db = DriverManager.getConnection("jdbc:h2:mem:nsrestlet-drop-${System.nanoTime()}")
        [
                "CREATE TABLE NS_RESTLET_CONFIG (NS_RESTLET_CONFIG_ID VARCHAR(40) PRIMARY KEY, COMPANY_USER_GROUP_ID VARCHAR(40), IS_ACTIVE CHAR(1))",
                "INSERT INTO NS_RESTLET_CONFIG VALUES ('EP_LIVE', 'KREWE', 'Y'), ('EP_OLD', 'KREWE', 'N'), ('__TABLE_BOOTSTRAP__', NULL, 'N')",
                "CREATE TABLE RULE_SET (RULE_SET_ID VARCHAR(40) PRIMARY KEY, IS_ACTIVE CHAR(1), IS_ARCHIVED CHAR(1))",
                "INSERT INTO RULE_SET VALUES ('RS_LIVE', 'Y', 'N'), ('RS_ARCHIVED', 'Y', 'Y'), ('RS_PLAIN', 'Y', 'N')",
                "CREATE TABLE RULE_SET_COMPARE_SCOPE (COMPARE_SCOPE_ID VARCHAR(40) PRIMARY KEY, RULE_SET_ID VARCHAR(40))",
                "INSERT INTO RULE_SET_COMPARE_SCOPE VALUES ('CS_LIVE', 'RS_LIVE'), ('CS_ARCHIVED', 'RS_ARCHIVED'), ('CS_PLAIN', 'RS_PLAIN')",
                "CREATE TABLE RULE_SET_COMPARE_SOURCE (COMPARE_SCOPE_ID VARCHAR(40), FILE_SIDE VARCHAR(20), NS_RESTLET_CONFIG_ID VARCHAR(40), " +
                        "CONSTRAINT RSCSR_NSREST FOREIGN KEY (NS_RESTLET_CONFIG_ID) REFERENCES NS_RESTLET_CONFIG (NS_RESTLET_CONFIG_ID))",
                "INSERT INTO RULE_SET_COMPARE_SOURCE VALUES ('CS_LIVE', 'FILE_1', 'EP_LIVE'), ('CS_ARCHIVED', 'FILE_2', 'EP_OLD'), ('CS_PLAIN', 'FILE_1', NULL)",
                "CREATE TABLE RECONCILIATION_AUTOMATION (AUTOMATION_ID VARCHAR(40) PRIMARY KEY, IS_ACTIVE CHAR(1), IS_ARCHIVED CHAR(1))",
                "INSERT INTO RECONCILIATION_AUTOMATION VALUES ('AUT_PAUSED', 'N', 'N')",
                "CREATE TABLE RECONCILIATION_AUTOMATION_SOURCE (AUTOMATION_ID VARCHAR(40), FILE_SIDE VARCHAR(20), NS_RESTLET_CONFIG_ID VARCHAR(40), " +
                        "CONSTRAINT RECAUTSRC_NSREST FOREIGN KEY (NS_RESTLET_CONFIG_ID) REFERENCES NS_RESTLET_CONFIG (NS_RESTLET_CONFIG_ID))",
                "INSERT INTO RECONCILIATION_AUTOMATION_SOURCE VALUES ('AUT_PAUSED', 'FILE_1', 'EP_LIVE')",
                "CREATE TABLE AUDIT_CHECK (CHECK_CODE VARCHAR(40), COMPANY_USER_GROUP_ID VARCHAR(40), SAVED_RUN_ID VARCHAR(40), AUTOMATION_ID VARCHAR(40))",
                "INSERT INTO AUDIT_CHECK VALUES ('R9', 'KREWE', 'RS_LIVE', NULL), ('R1', 'KREWE', 'RS_PLAIN', NULL)",
                "CREATE TABLE CONFIG_TENANT_ACCESS (CONFIG_TYPE_ENUM_ID VARCHAR(40), CONFIG_ID VARCHAR(40))",
                "INSERT INTO CONFIG_TENANT_ACCESS VALUES ('SCFG_NS_RESTLET', 'EP_LIVE'), ('SCFG_NS_AUTH', 'AUTH_1')",
                "CREATE TABLE SOURCE_CONFIG_ENDPOINT_ACCESS (CONFIG_TYPE_ENUM_ID VARCHAR(40), CONFIG_ID VARCHAR(40))",
                "CREATE TABLE ENTITY_FILTER (ENTITY_FILTER_ID VARCHAR(40) PRIMARY KEY)",
                "INSERT INTO ENTITY_FILTER VALUES ('DARPAN_SCOPE_NS_RESTLET'), ('DARPAN_SCOPE_NS_AUTH')",
                "CREATE TABLE ENUMERATION (ENUM_ID VARCHAR(40) PRIMARY KEY)",
                "INSERT INTO ENUMERATION VALUES ('SCFG_NS_RESTLET'), ('SCFG_NS_AUTH')",
                "CREATE TABLE NS_AUTH_CONFIG (NS_AUTH_CONFIG_ID VARCHAR(40) PRIMARY KEY, NETSUITE_ACCOUNT VARCHAR(60))",
                "INSERT INTO NS_AUTH_CONFIG VALUES ('AUTH_1', '1234567')",
        ].each { run(it) }
    }

    @AfterEach
    void cleanup() { db?.close() }

    @Test
    void everyRunReportsWhatTheDatabaseHolds() {
        List<String> words = NsRestletDrop.apply(db, [] as Set, true).messages as List<String>
        assertTrue(words.contains("NS_RESTLET_CONFIG: 3 RESTlet config(s): (no tenant) 1 (0 active), KREWE 2 (1 active); 1 of them the " +
                "__TABLE_BOOTSTRAP__ placeholder (disabled, no tenant; the old table-bootstrap data)."), "${words}")
        assertTrue(words.contains("Rule-set sides naming a RESTlet: RS_ARCHIVED FILE_2 (EP_OLD, paused or archived), " +
                "RS_LIVE FILE_1 (EP_LIVE, active)."), "${words}")
        assertTrue(words.contains("Automation sides naming a RESTlet: AUT_PAUSED FILE_1 (EP_LIVE, paused or archived)."), "${words}")
        assertTrue(words.contains("Audit checks over those: KREWE/R9."), "only the check over a referencing run: ${words}")
        assertTrue(words.contains("CONFIG_TENANT_ACCESS: 1 RESTlet tenant share(s)."), "${words}")
        assertTrue(words.contains("SOURCE_CONFIG_ENDPOINT_ACCESS: 0 RESTlet endpoint decision(s)."), "${words}")
    }

    @Test
    void anActiveRuleSetNamingARestletRefusesTheRealRunAndChangesNothing() {
        Map out = NsRestletDrop.apply(db, [] as Set, false)
        assertEquals(["Refused: 1 active side(s) still read a RESTlet, which nothing runs now. Move each to a NetSuite connection " +
                "(or pause it) first: rule set RS_LIVE FILE_1 (EP_LIVE)."], out.errors)
        assertEquals(0, out.changed)
        assertNotNull(NsRestletDrop.storedName(db, "NS_RESTLET_CONFIG", null), "nothing dropped")
        assertEquals(1L, count("SELECT COUNT(*) FROM CONFIG_TENANT_ACCESS WHERE CONFIG_TYPE_ENUM_ID = 'SCFG_NS_RESTLET'"), "nothing deleted")
    }

    @Test
    void anActiveAutomationRefusesItToo() {
        run("UPDATE RULE_SET SET IS_ACTIVE = 'N' WHERE RULE_SET_ID = 'RS_LIVE'")
        run("UPDATE RECONCILIATION_AUTOMATION SET IS_ACTIVE = 'Y'")
        assertEquals(["Refused: 1 active side(s) still read a RESTlet, which nothing runs now. Move each to a NetSuite connection " +
                "(or pause it) first: automation AUT_PAUSED FILE_1 (EP_LIVE)."], NsRestletDrop.apply(db, [] as Set, false).errors)
    }

    @Test
    void theDryRunPlansKeysBeforeColumnsBeforeTheTableAndChangesNothing() {
        run("UPDATE RULE_SET SET IS_ACTIVE = 'N' WHERE RULE_SET_ID = 'RS_LIVE'")
        Map out = NsRestletDrop.apply(db, [] as Set, true)
        assertEquals([], out.errors)
        assertEquals([
                "Would run: DELETE FROM CONFIG_TENANT_ACCESS WHERE CONFIG_TYPE_ENUM_ID = 'SCFG_NS_RESTLET'",
                "Would run: DELETE FROM ENTITY_FILTER WHERE ENTITY_FILTER_ID = 'DARPAN_SCOPE_NS_RESTLET'",
                "Would run: DELETE FROM ENUMERATION WHERE ENUM_ID = 'SCFG_NS_RESTLET'",
                "Would run: ALTER TABLE RULE_SET_COMPARE_SOURCE DROP CONSTRAINT RSCSR_NSREST",
                "Would run: ALTER TABLE RULE_SET_COMPARE_SOURCE DROP COLUMN NS_RESTLET_CONFIG_ID",
                "Would run: ALTER TABLE RECONCILIATION_AUTOMATION_SOURCE DROP CONSTRAINT RECAUTSRC_NSREST",
                "Would run: ALTER TABLE RECONCILIATION_AUTOMATION_SOURCE DROP COLUMN NS_RESTLET_CONFIG_ID",
                "Would run: DROP TABLE NS_RESTLET_CONFIG",
                "Would run: ALTER TABLE NS_AUTH_CONFIG DROP COLUMN NETSUITE_ACCOUNT",
        ], (out.messages as List<String>).findAll { it.startsWith("Would run: ") })
        assertEquals(0, out.changed)
        assertNotNull(NsRestletDrop.storedName(db, "RULE_SET_COMPARE_SOURCE", "NS_RESTLET_CONFIG_ID"), "a dry run changes nothing")
    }

    @Test
    void theRealRunRemovesEverythingOnceAndASecondRunHasNothingToDo() {
        run("UPDATE RULE_SET SET IS_ACTIVE = 'N' WHERE RULE_SET_ID = 'RS_LIVE'")
        Map out = NsRestletDrop.apply(db, [] as Set, false)
        assertEquals([], out.errors)
        assertEquals(9, out.changed, "${out.messages}")
        assertNull(NsRestletDrop.storedName(db, "NS_RESTLET_CONFIG", null))
        assertNull(NsRestletDrop.storedName(db, "RULE_SET_COMPARE_SOURCE", "NS_RESTLET_CONFIG_ID"))
        assertNull(NsRestletDrop.storedName(db, "RECONCILIATION_AUTOMATION_SOURCE", "NS_RESTLET_CONFIG_ID"))
        assertNull(NsRestletDrop.storedName(db, "NS_AUTH_CONFIG", "NETSUITE_ACCOUNT"))
        assertEquals([["SCFG_NS_AUTH"]], rows("SELECT CONFIG_TYPE_ENUM_ID FROM CONFIG_TENANT_ACCESS"), "only the RESTlet share goes")
        assertEquals([["DARPAN_SCOPE_NS_AUTH"]], rows("SELECT ENTITY_FILTER_ID FROM ENTITY_FILTER"))
        assertEquals([["SCFG_NS_AUTH"]], rows("SELECT ENUM_ID FROM ENUMERATION"))
        assertEquals(3L, count("SELECT COUNT(*) FROM RULE_SET_COMPARE_SOURCE"), "the source rows stay, only the column goes")

        Map again = NsRestletDrop.apply(db, [] as Set, false)
        assertEquals([], again.errors)
        assertEquals(0, again.changed)
        assertTrue((again.messages as List<String>).contains("Nothing left to remove."), "${again.messages}")
    }

    @Test
    void nothingRunsWhileTheEntityModelStillDefinesIt() {
        run("UPDATE RULE_SET SET IS_ACTIVE = 'N' WHERE RULE_SET_ID = 'RS_LIVE'")
        Map out = NsRestletDrop.apply(db, ["NS_RESTLET_CONFIG"] as Set, false)
        assertEquals(0, out.changed)
        assertTrue((out.messages as List<String>).contains("Still defined in the entity model: NS_RESTLET_CONFIG. Nothing changed; " +
                "run this after the release that deletes them."), "${out.messages}")
        assertNotNull(NsRestletDrop.storedName(db, "NS_RESTLET_CONFIG", null))
    }

    private void run(String sql) { db.createStatement().withCloseable { it.execute(sql) } }

    private long count(String sql) { return (rows(sql)[0][0] as Number).longValue() }

    private List<List> rows(String sql) {
        List<List> out = []
        db.createStatement().withCloseable { st ->
            def rs = st.executeQuery(sql)
            int n = rs.metaData.columnCount
            while (rs.next()) out << (1..n).collect { rs.getObject(it) }
        }
        return out
    }
}
