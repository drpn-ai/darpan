package darpan.security

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.LoggerConfig
import org.apache.logging.log4j.core.config.Property
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityException

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-140. An encrypt="true" column's plaintext never reaches an error message or a log line. The framework fork
 * prints such a field as *** in EntityValue.toString() (every "Error creating/updating ..." log and the update-miss
 * exception) and in a condition parameter's toString() (the "parameters were ..." list and debug SQL logs). Proven on
 * NsAuthConfig, whose password, apiToken and privateKeyPem are encrypted, in this test's own H2.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EncryptedFieldMaskingTests {
    private ExecutionContext ec

    private static final String ENTITY = "darpan.reconciliation.NsAuthConfig"
    private static final String TENANT = "MASK_TENANT"
    private static final String PASSWORD = "Plain-Password-BE140"
    private static final String API_TOKEN = "Plain-ApiToken-BE140"
    private static final String PEM = "Plain-Pem-BE140"
    private static final String NEW_PASSWORD = "Plain-Password-BE140-Changed"
    private static final List<String> PLAINTEXTS = [PASSWORD, API_TOKEN, PEM]

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "encrypted-field-masking")
        ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: TENANT, description: "Masking tenant"]).createOrUpdate()
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @Test
    void toStringMasksEveryEncryptedFieldThatIsSet() {
        def value = credentials("MASK_TO_STRING")
        String printed = value.toString()
        ["password:***", "apiToken:***", "privateKeyPem:***", "description:masking test", "nsAuthConfigId:MASK_TO_STRING"].each {
            assertTrue(printed.contains(it), "${it} in ${printed}")
        }
        PLAINTEXTS.each { assertFalse(printed.contains(it), "no plaintext in ${printed}") }

        def plain = ec.entity.makeValue(ENTITY).setAll([nsAuthConfigId: "MASK_PLAIN", description: "no secret", username: "svc"])
        String plainPrinted = plain.toString()
        assertTrue(plainPrinted.startsWith("[${ENTITY}: ".toString()), plainPrinted)
        assertTrue(plainPrinted.contains("description:no secret") && plainPrinted.contains("username:svc"), plainPrinted)
        assertFalse(plainPrinted.contains("***"), "a value with no encrypted field set prints as before: ${plainPrinted}")
    }

    @Test
    void anUpdateOfAMissingRowSaysNoPlaintext() {
        // Line 143 of EntityValueImpl ("Tried to update a value that does not exist ... parameters were") is reached only
        // by an entity with no field defaults, audit log or optimistic lock: those refresh first, and a missing row then
        // reads as unchanged. moqui.entity.sync.EntitySync.targetPassword is encrypted and has none of them. A value read
        // from the database, its row deleted underneath it, then changed:
        String entity = "moqui.entity.sync.EntitySync"
        ec.entity.makeValue(entity).setAll([entitySyncId: "MASK_GONE", description: "masking test", targetPassword: PASSWORD]).create()
        def value = ec.entity.find(entity).condition("entitySyncId", "MASK_GONE").useCache(false).one()
        ec.entity.find(entity).condition("entitySyncId", "MASK_GONE").deleteAll()
        value.set("targetPassword", NEW_PASSWORD)
        EntityException failure = assertThrows(EntityException) { value.update() }
        String said = chain(failure)
        assertTrue(said.contains("parameters were"), said)
        assertTrue(said.contains("targetPassword:***"), "the value and the parameter list name the field and mask it: ${said}")
        [PASSWORD, NEW_PASSWORD].each { assertFalse(said.contains(it), "no plaintext in: ${said}") }
    }

    @Test
    void aFailedCreateLogsAndThrowsNoPlaintext() {
        credentials("MASK_DUPLICATE").create()
        Capture capture = new Capture()
        LoggerContext context = (LoggerContext) LogManager.getContext(false)
        LoggerConfig loggerConfig = context.configuration.getLoggerConfig("org.moqui.impl.entity.EntityValueImpl")
        capture.start()
        loggerConfig.addAppender(capture, Level.ALL, null)
        context.updateLoggers()
        Throwable failure
        try {
            failure = assertThrows(Throwable) { credentials("MASK_DUPLICATE").create() }
        } finally {
            loggerConfig.removeAppender(capture.name)
            context.updateLoggers()
            capture.stop()
        }
        String logged = capture.lines.join("\n")
        assertTrue(logged.contains("Error creating"), "the failed create was logged: ${logged}")
        String said = logged + "\n" + chain(failure)
        PLAINTEXTS.each { assertFalse(said.contains(it), "no plaintext in the log or the exception: ${said}") }
    }

    private def credentials(String id) {
        return ec.entity.makeValue(ENTITY).setAll([nsAuthConfigId: id, description: "masking test", companyUserGroupId: TENANT,
                authType: "OAUTH2_M2M", password: PASSWORD, apiToken: API_TOKEN, privateKeyPem: PEM])
    }

    private static String chain(Throwable t) {
        List<String> said = []
        for (Throwable c = t; c != null; c = (c.cause == c ? null : c.cause)) said << c.toString()
        return said.join("\n")
    }

    /** Collects what EntityValueImpl logs while attached. */
    static class Capture extends AbstractAppender {
        final List<String> lines = Collections.synchronizedList(new ArrayList<String>())

        Capture() {
            super("be140-capture", null, null, true, Property.EMPTY_ARRAY)
        }

        @Override
        void append(LogEvent event) {
            lines << (event.message.formattedMessage + (event.thrown ? " " + event.thrown.toString() : ""))
        }
    }
}
