package darpan.script

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.moqui.context.ExecutionContext
import org.moqui.impl.screen.ScreenTestImpl
import org.moqui.screen.ScreenTest

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-175 POC: Settings > Script Lab runs JEXL and guarded Groovy on the ScriptLane inside Moqui. */
class ScriptLabScreenTests {
    private static ExecutionContext ec
    private static final String SUPER_ADMIN = 'SL_SCREEN_SUPER_ADMIN'
    private static final String REGULAR = 'SL_SCREEN_REGULAR'

    @BeforeAll
    static void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, 'script-lab-screen')
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        try {
            ec.entity.makeDataLoader().dummyFks(true).dataTypes(['seed', 'seed-initial', 'darpan-seed'] as Set).load()
            [(SUPER_ADMIN): 'DARPAN_SUPER_ADMIN', (REGULAR): 'DARPAN_USER'].each { String userId, String group ->
                ec.entity.makeValue('moqui.security.UserAccount').set('userId', userId).set('username', userId.toLowerCase())
                        .set('userFullName', userId).createOrUpdate()
                ec.entity.makeValue('moqui.security.UserGroupMember').set('userGroupId', group).set('userId', userId)
                        .set('fromDate', new Timestamp(0L)).createOrUpdate()
            }
        } finally {
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
        loginAs(SUPER_ADMIN)
        ec.service.sync().name('admin.TenantAdminServices.create#Tenant').parameters([tenantUserGroupId: 'SL_TENANT', label: 'Lab tenant']).call()
        ec.service.sync().name('facade.AuthFacadeServices.save#ActiveTenant').parameters([activeTenantUserGroupId: 'SL_TENANT']).call()
    }

    @AfterAll
    static void teardown() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @AfterEach
    void laneOn() { ScriptLane.setEnabled(null) }

    private static void loginAs(String userId) {
        ec.artifactExecution.disableAuthz()
        try { ec.user.internalLoginUser(userId) } finally { ec.artifactExecution.enableAuthz() }
    }

    private static ScreenTest lab() { ec.screen.makeTest().baseScreenPath('apps/darpan/Settings') }

    /** POSTs a run like the form does, then renders the screen in the same session and returns its output. */
    private static String runAndRender(Map params) {
        loginAs(SUPER_ADMIN)
        ScreenTest t = lab()
        ScreenTest.ScreenTestRender post = t.render('ScriptLab/runScript', params + [moquiSessionToken: 'TestSessionToken'], 'post')
        assertFalse(post.errorMessages as boolean, "errors: ${post.errorMessages}")
        assertTrue(((ScreenTestImpl) t).sessionAttributes.get('darpanScriptLabLastRun') instanceof String, 'stored as JSON text')
        return t.render('ScriptLab', [:], 'get').output ?: ''
    }

    @Test
    void aSuperAdminSeesTheLaneAndTheExamples() {
        loginAs(SUPER_ADMIN)
        ScreenTest.ScreenTestRender str = lab().render('ScriptLab', [example: 'g-gap-call'], 'get')
        String out = str.output ?: ''
        assertFalse(str.errorMessages as boolean, "errors: ${str.errorMessages}")
        assertTrue(out.contains('Script lane') && out.contains('threads busy') && out.contains('scripts refused above'), out.take(1500))
        assertTrue(out.contains('Groovy gap: one long library call') && out.contains('JEXL: endless loop'), 'examples listed')
        assertTrue(out.contains('Loaded: Groovy gap: one long library call'), 'example loaded into the form')
    }

    @Test
    void aRegularUserNeverSeesTheLab() {
        loginAs(REGULAR)
        String out = ''
        try { out = lab().render('ScriptLab', [:], 'get').output ?: '' } catch (Exception denied) { }
        assertFalse(out.contains('Script lane'), out.take(600))
    }

    @Test
    void aJexlScriptRunsOnTheLaneAndItsResultIsShown() {
        String out = runAndRender([engine: 'JEXL', scriptProfile: 'SCRIPT', scriptBody: 'var t = 0; for (var i : 1..3) { t = t + i; } t', inputJson: '{}'])
        // The same script traced through the engine on 2026-11-02: result 6, exactly 9 units.
        assertTrue(out.contains('Engine JEXL · units 9 ·') && out.contains('on the lane'), out.take(2500))
        assertTrue(out.contains('stopped:') == false && out.contains('ok · '), 'ran ok')
    }

    @Test
    void aGroovyScriptRunsOnTheLaneAndAGuardStopsALoop() {
        String ok = runAndRender([engine: 'GROOVY', scriptBody: 'input.n * 7', inputJson: '{"n": 6}'])
        assertTrue(ok.contains('Engine GROOVY') && ok.contains('42'), ok.take(2500))
        String stopped = runAndRender([engine: 'GROOVY', scriptBody: 'while (true) { }', inputJson: '{}'])
        assertTrue(stopped.contains('stopped:'), stopped.take(2500))
        String refused = runAndRender([engine: 'GROOVY', scriptBody: 'System.exit(0)', inputJson: '{}'])
        assertTrue(refused.contains('stopped: NOT_ALLOWED'), refused.take(2500))
    }

    @Test
    void theOffSwitchRefusesScripts() {
        ScriptLane.setEnabled(false)
        String out = runAndRender([engine: 'JEXL', scriptProfile: 'SCRIPT', scriptBody: '1 + 1', inputJson: '{}'])
        assertTrue(out.contains('stopped: SWITCHED_OFF') && out.contains('SWITCHED OFF'), out.take(2500))
    }
}
