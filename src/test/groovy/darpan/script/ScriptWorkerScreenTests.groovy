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
    // Its own user: the active tenant is kept per user, and the no-tenant test needs none.
    private static final String TENANT_SUPER_ADMIN_USER_ID = 'SW_SCREEN_TENANT_ADMIN'

    @BeforeAll
    static void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, 'script-worker-screen')
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        try {
            ec.entity.makeDataLoader().dummyFks(true).dataTypes(['seed', 'seed-initial', 'darpan-seed'] as Set).load()
            createUserInGroup(SUPER_ADMIN_USER_ID, 'DARPAN_SUPER_ADMIN')
            createUserInGroup(REGULAR_USER_ID, 'DARPAN_USER')
            createUserInGroup(TENANT_SUPER_ADMIN_USER_ID, 'DARPAN_SUPER_ADMIN')
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
    void aRunPostWithoutTheSessionTokenIsRefused() {
        // A browser form carries moquiSessionToken (the harness stub's is "TestSessionToken"); a forged cross-site
        // POST does not, and Moqui refuses it before the transition runs.
        ec.artifactExecution.disableAuthz()
        try { ec.user.internalLoginUser(SUPER_ADMIN_USER_ID) } finally { ec.artifactExecution.enableAuthz() }
        ScreenTest screenTest = ec.screen.makeTest().baseScreenPath('apps/darpan/Settings')
        ScreenTest.ScreenTestRender str = screenTest.render('ScriptWorker/runScript',
                [scriptProfile: 'EXPRESSION', scriptBody: '1 + 1', inputJson: '{}'], 'post')
        String errors = (str.errorMessages ?: []).join(' | ')
        assertTrue(errors.contains('Session token required'), "errors: ${errors}")
        assertTrue(((org.moqui.impl.screen.ScreenTestImpl) screenTest).sessionAttributes.get('darpanScriptWorkerLastRun') == null,
                'nothing ran, so nothing was stored')
    }

    @Test
    void aRunIsKeptAsSerializableTextAndShownOnTheNextRender() {
        ec.artifactExecution.disableAuthz()
        try { ec.user.internalLoginUser(TENANT_SUPER_ADMIN_USER_ID) } finally { ec.artifactExecution.enableAuthz() }
        ec.service.sync().name('admin.TenantAdminServices.create#Tenant')
                .parameters([tenantUserGroupId: 'SW_SCREEN_TENANT', label: 'Screen test tenant']).call()
        ec.service.sync().name('facade.AuthFacadeServices.save#ActiveTenant')
                .parameters([activeTenantUserGroupId: 'SW_SCREEN_TENANT']).call()
        assertFalse(ec.message.hasError(), "tenant setup: ${ec.message.errorsString}")

        ScreenTest screenTest = ec.screen.makeTest().baseScreenPath('apps/darpan/Settings')
        ScreenTest.ScreenTestRender post = screenTest.render('ScriptWorker/runScript',
                [scriptProfile: 'EXPRESSION', scriptBody: '6 * 7', inputJson: '{}', moquiSessionToken: 'TestSessionToken'], 'post')
        assertFalse(post.errorMessages as boolean, "errors: ${post.errorMessages}")
        // A real servlet session drops a value that is not Serializable (WebUtilities warns and the result never
        // shows, as on UAT 2026-10-31); the harness stub keeps anything, so check the stored type directly.
        Object stored = ((org.moqui.impl.screen.ScreenTestImpl) screenTest).sessionAttributes.get('darpanScriptWorkerLastRun')
        assertTrue(stored instanceof String, "the last run is stored as JSON text, got ${stored?.getClass()}")

        String out = screenTest.render('ScriptWorker', [:], 'get').output ?: ''
        assertTrue(out.contains('Result') && out.contains('42'), out.take(2000))
        assertTrue(out.contains('ran in the inside Moqui'), 'no worker URL in the test JVM, and the screen says so')
    }

    private static void createUserInGroup(String userId, String userGroupId) {
        ec.entity.makeValue('moqui.security.UserAccount').set('userId', userId)
                .set('username', userId.toLowerCase()).set('userFullName', userId).createOrUpdate()
        ec.entity.makeValue('moqui.security.UserGroupMember').set('userGroupId', userGroupId)
                .set('userId', userId).set('fromDate', new Timestamp(0L)).createOrUpdate()
    }
}
