package darpan.migration

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import java.sql.Connection
import java.sql.DriverManager

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-OPS-029. OrphanSchemaDrops on a bare H2 database, through its seam (a connection and a model map), with no Moqui.
 * Each rule has its own case; OrphanSchemaDropsMoquiMysqlTests runs the registered service on MySQL as prod would.
 */
class OrphanSchemaDropsTests {

    private Connection conn
    private static int dbSeq = 0

    @BeforeEach
    void open() {
        conn = DriverManager.getConnection("jdbc:h2:mem:orphan_drops_${dbSeq++};DB_CLOSE_DELAY=-1".toString(), "sa", "")
        sql("CREATE TABLE RECONCILIATION_RUN (RECONCILIATION_RUN_ID VARCHAR(40) PRIMARY KEY)")
        sql("CREATE TABLE RUN_DATA_DOCUMENT (RECONCILIATION_RUN_ID VARCHAR(40), DATA_DOCUMENT_ID VARCHAR(40), " +
                "FOREIGN KEY (RECONCILIATION_RUN_ID) REFERENCES RECONCILIATION_RUN(RECONCILIATION_RUN_ID))")
        sql("CREATE TABLE PASSKEY_SIGN_IN_CHALLENGE (CHALLENGE_ID VARCHAR(40) PRIMARY KEY, USERNAME_HINT VARCHAR(255))")
        sql("CREATE TABLE TENANT_CHAT_SPACE (CHAT_SPACE_ID VARCHAR(40) PRIMARY KEY, WEBHOOK_URL VARCHAR(4000), GOOGLE_CHAT_WEBHOOK_URL VARCHAR(4000))")
        sql("INSERT INTO RECONCILIATION_RUN VALUES ('R1')")
        sql("INSERT INTO RUN_DATA_DOCUMENT VALUES ('R1', 'D1'), ('R1', 'D2')")
        sql("INSERT INTO PASSKEY_SIGN_IN_CHALLENGE VALUES ('C1', 'ada'), ('C2', NULL)")
        sql("INSERT INTO TENANT_CHAT_SPACE VALUES ('S1', 'https://chat.invalid/new', 'https://chat.invalid/old')")
    }

    @AfterEach
    void close() { conn?.close() }

    private void sql(String statement) { conn.createStatement().withCloseable { it.execute(statement) } }

    private boolean hasTable(String table) { OrphanSchemaDrops.storedName(conn, table, null) != null }

    private boolean hasColumn(String table, String column) { OrphanSchemaDrops.storedName(conn, table, column) != null }

    /** The live model after the deletions: the run and challenge tables stay, without the dropped field. */
    private static final Map<String, Set<String>> MODEL_AFTER = [
            RECONCILIATION_RUN       : ["RECONCILIATION_RUN_ID"] as Set,
            PASSKEY_SIGN_IN_CHALLENGE: ["CHALLENGE_ID"] as Set,
            TENANT_CHAT_SPACE        : ["CHAT_SPACE_ID", "WEBHOOK_URL"] as Set,
    ]

    @Test
    void aDryRunNamesEachDropWithItsSizeAndChangesNothing() {
        Map out = OrphanSchemaDrops.apply(conn, OrphanSchemaDrops.DROPS, MODEL_AFTER, true)
        assertEquals([], out.errors)
        assertEquals(0, out.dropped)
        assertTrue(out.messages.any { it.startsWith("RUN_DATA_DOCUMENT: would run DROP TABLE RUN_DATA_DOCUMENT (2 row(s)") }, "${out.messages}")
        assertTrue(out.messages.any { it.startsWith("PASSKEY_SIGN_IN_CHALLENGE.USERNAME_HINT: would run ALTER TABLE PASSKEY_SIGN_IN_CHALLENGE DROP COLUMN USERNAME_HINT (1 value(s)") }, "${out.messages}")
        assertTrue(out.messages.contains("RUN_SYSTEM_INSTANCE: not in this database; nothing to do."), "${out.messages}")
        assertTrue(hasTable("RUN_DATA_DOCUMENT") && hasColumn("PASSKEY_SIGN_IN_CHALLENGE", "USERNAME_HINT"))
    }

    @Test
    void aRunDropsTheOrphansAndASecondRunHasNothingToDo() {
        Map first = OrphanSchemaDrops.apply(conn, OrphanSchemaDrops.DROPS, MODEL_AFTER, false)
        assertEquals([], first.errors)
        assertEquals(3, first.dropped, "${first.messages}")
        assertTrue(!hasTable("RUN_DATA_DOCUMENT") && !hasColumn("PASSKEY_SIGN_IN_CHALLENGE", "USERNAME_HINT")
                && !hasColumn("TENANT_CHAT_SPACE", "GOOGLE_CHAT_WEBHOOK_URL"))
        assertTrue(hasTable("RECONCILIATION_RUN") && hasColumn("TENANT_CHAT_SPACE", "WEBHOOK_URL"), "nothing live is touched")

        Map second = OrphanSchemaDrops.apply(conn, OrphanSchemaDrops.DROPS, MODEL_AFTER, false)
        assertEquals(0, second.dropped)
        assertEquals([], second.errors)
        assertTrue(second.messages.every { it.endsWith("not in this database; nothing to do.") }, "${second.messages}")
    }

    @Test
    void aTableOrColumnTheModelStillDefinesIsKept() {
        Map<String, Set<String>> before = MODEL_AFTER + [
                RUN_DATA_DOCUMENT        : ["RECONCILIATION_RUN_ID"] as Set,
                PASSKEY_SIGN_IN_CHALLENGE: ["CHALLENGE_ID", "USERNAME_HINT"] as Set,
                TENANT_CHAT_SPACE        : ["CHAT_SPACE_ID", "WEBHOOK_URL", "GOOGLE_CHAT_WEBHOOK_URL"] as Set]
        Map out = OrphanSchemaDrops.apply(conn, OrphanSchemaDrops.DROPS, before, false)
        assertEquals(0, out.dropped, "${out.messages}")
        assertTrue(out.messages.contains("RUN_DATA_DOCUMENT: an entity in the model still maps it; kept."), "${out.messages}")
        assertTrue(out.messages.contains("PASSKEY_SIGN_IN_CHALLENGE.USERNAME_HINT: still a field in the entity model; kept."), "${out.messages}")
        assertTrue(out.messages.any { it.startsWith("TENANT_CHAT_SPACE.GOOGLE_CHAT_WEBHOOK_URL: still a field in the entity model; kept. Gate: 0 ") },
                "a dry run reports the gate's count even while the field is live: ${out.messages}")
        assertTrue(hasTable("RUN_DATA_DOCUMENT") && hasColumn("PASSKEY_SIGN_IN_CHALLENGE", "USERNAME_HINT")
                && hasColumn("TENANT_CHAT_SPACE", "GOOGLE_CHAT_WEBHOOK_URL"))
    }

    @Test
    void theChatColumnWaitsWhileASpacesOnlyWebhookIsStillInIt() {
        sql("INSERT INTO TENANT_CHAT_SPACE VALUES ('S2', NULL, 'https://chat.invalid/only-old')")
        sql("INSERT INTO TENANT_CHAT_SPACE VALUES ('S3', '', 'https://chat.invalid/only-old-too')")
        Map out = OrphanSchemaDrops.apply(conn, OrphanSchemaDrops.DROPS, MODEL_AFTER, false)
        assertTrue(out.messages.any { it.startsWith("TENANT_CHAT_SPACE.GOOGLE_CHAT_WEBHOOK_URL: kept, 2 chat space(s)") }, "${out.messages}")
        assertTrue(hasColumn("TENANT_CHAT_SPACE", "GOOGLE_CHAT_WEBHOOK_URL"))
        assertEquals([], out.errors, "a gate waits; it is not an error")
    }

    @Test
    void aTableAnotherStillReferencesIsAnErrorAndStays() {
        sql("CREATE TABLE AUTH_SECRET (SECRET_ID VARCHAR(40) PRIMARY KEY)")
        sql("CREATE TABLE SOMETHING_LIVE (ID VARCHAR(40), SECRET_ID VARCHAR(40), FOREIGN KEY (SECRET_ID) REFERENCES AUTH_SECRET(SECRET_ID))")
        Map out = OrphanSchemaDrops.apply(conn, OrphanSchemaDrops.DROPS, MODEL_AFTER + [SOMETHING_LIVE: ["ID", "SECRET_ID"] as Set], false)
        assertEquals(["AUTH_SECRET: still referenced by a foreign key from SOMETHING_LIVE; not dropped."], out.errors)
        assertTrue(hasTable("AUTH_SECRET"))
        assertTrue(!hasTable("RUN_DATA_DOCUMENT"), "the other entries still run")
    }

    @Test
    void namesMatchExactlyNotAsLikePatterns() {
        // In a metadata lookup an underscore matches any character: RUNXSYSTEMXINSTANCE must not pass for RUN_SYSTEM_INSTANCE.
        sql("CREATE TABLE RUNXSYSTEMXINSTANCE (ID VARCHAR(40))")
        assertNull(OrphanSchemaDrops.storedName(conn, "RUN_SYSTEM_INSTANCE", null))
        assertNotNull(OrphanSchemaDrops.storedName(conn, "run_data_document", null), "any case")
        Map out = OrphanSchemaDrops.apply(conn, OrphanSchemaDrops.DROPS, MODEL_AFTER, false)
        assertTrue(hasTable("RUNXSYSTEMXINSTANCE"))
        assertTrue(out.messages.contains("RUN_SYSTEM_INSTANCE: not in this database; nothing to do."), "${out.messages}")
    }
}
