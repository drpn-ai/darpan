package darpan.script

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.moqui.context.ExecutionContext
import org.moqui.screen.ScreenTest

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-169 operator screen (/apps/darpan/Settings/ScriptWorker): proves the worker POC without darpan-ui.
 * Renders through Moqui's ScreenTest harness with the real security seed, so URL authz and the screen's own
 * super-admin gate are both in play.
 */
class ScriptWorkerScreenTests {
    private static ExecutionContext ec
    private static final String SUPER_ADMIN_USER_ID = 'SW_SCREEN_SUPER_ADMIN'
    private static final String REGULAR_USER_ID = 'SW_SCREEN_REGULAR'

    @BeforeAll
    static void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, 'script-worker-screen')
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        try {
            ec.entity.makeDataLoader().dummyFks(true).dataTypes(['seed', 'seed-initial', 'darpan-seed'] as Set).load()
            createUserInGroup(SUPER_ADMIN_USER_ID, 'DARPAN_SUPER_ADMIN')
            createUserInGroup(REGULAR_USER_ID, 'DARPAN_USER')
        } finally {
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    @AfterAll
    static void teardown() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    private static ScreenTest.ScreenTestRender renderAs(String userId, String path, Map params, String method) {
        ec.artifactExecution.disableAuthz()
        try { ec.user.internalLoginUser(userId) } finally { ec.artifactExecution.enableAuthz() }
        ScreenTest screenTest = ec.screen.makeTest().baseScreenPath('apps/darpan/Settings')
        return screenTest.render(path, params, method)
    }

    @Test
    void aSuperAdminSeesWhereScriptsRunAndTheDemoExamples() {
        ScreenTest.ScreenTestRender str = renderAs(SUPER_ADMIN_USER_ID, 'ScriptWorker', [example: 'escape'], 'get')
        String out = str.output ?: ''
        assertFalse(str.errorMessages as boolean, "errors: ${str.errorMessages}")
        assertTrue(out.contains('Where scripts run'), out.take(600))
        // The test JVM has no DARPAN_SCRIPT_WORKER_URL, so the screen must say scripts run inside Moqui.
        assertTrue(out.contains('Inside Moqui'), out.take(1200))
        assertTrue(out.contains('No worker URL configured'), out.take(1200))
        assertTrue(out.contains('Endless loop') && out.contains('Sum of order lines'), 'example links rendered')
        // The chosen example fills the form (HTML-encoded quotes around the class name).
        assertTrue(out.contains('forName'), 'escape example loaded into the script field')
    }

    @Test
    void aRegularUserNeverSeesTheScreen() {
        String out = ''
        try {
            out = renderAs(REGULAR_USER_ID, 'ScriptWorker', [:], 'get').output ?: ''
        } catch (Exception denied) {
            // Either URL authz or the screen's own gate refuses; both are a pass.
        }
        assertFalse(out.contains('Where scripts run'), out.take(600))
    }

    @Test
    void runGoesThroughTheRealRunTenantScriptService() {
        // With no active tenant, run#TenantScript itself refuses: the transition reached the real service.
        // The POST carries the session token like a browser form does (WebFacadeStub's is "TestSessionToken");
        // without it Moqui refuses before the transition runs, which is the CSRF wall working.
        ScreenTest.ScreenTestRender str = renderAs(SUPER_ADMIN_USER_ID, 'ScriptWorker/runScript',
                [scriptProfile: 'EXPRESSION', scriptBody: '1 + 1', inputJson: '{}', moquiSessionToken: 'TestSessionToken'], 'post')
        String errors = (str.errorMessages ?: []).join(' | ')
        assertTrue(errors.contains('Choose a tenant first'), "errors: ${errors}")
    }

    private static void createUserInGroup(String userId, String userGroupId) {
        ec.entity.makeValue('moqui.security.UserAccount').set('userId', userId)
                .set('username', userId.toLowerCase()).set('userFullName', userId).createOrUpdate()
        ec.entity.makeValue('moqui.security.UserGroupMember').set('userGroupId', userGroupId)
                .set('userId', userId).set('fromDate', new Timestamp(0L)).createOrUpdate()
    }
}
