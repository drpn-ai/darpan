package darpan.facade.common

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-CLI-003 — the CLI-session method allowlist, held against the published API contract (docs/api-contract/methods.txt).
 * A new remote method fails here until it is placed in ALLOWED or EXCLUDED. Pure; no Moqui boot.
 */
class CliMethodAllowlistTests {

    /** Global Constraints: never on the CLI allowlist (admin.* is checked separately, as a whole namespace). */
    private static final List<String> NEVER_ALLOWED = [
            "facade.AuthFacadeServices.save#ActiveTenant",
            // password, recovery and session methods (logout#CliSession is the one exception)
            "facade.AuthFacadeServices.change#ExpiredPassword",
            "facade.AuthFacadeServices.change#OwnPassword",
            "facade.AuthFacadeServices.complete#PasswordReset",
            "facade.AuthFacadeServices.get#PasswordResetStatus",
            "facade.AuthFacadeServices.request#PasswordReset",
            "facade.AuthFacadeServices.verify#PasswordResetCode",
            "facade.AuthFacadeServices.verify#OwnPassword",
            "facade.AuthFacadeServices.save#RecoveryEmail",
            "facade.AuthFacadeServices.login#Session",
            "facade.AuthFacadeServices.logout#Session",
            "facade.AuthFacadeServices.logout#AllSessions",
            // credential saves and deletes
            "facade.SettingsFacadeServices.save#NsAuthConfig",
            "facade.SettingsFacadeServices.delete#NsAuthConfig",
            "facade.SettingsFacadeServices.save#NsRestletConfig",
            "facade.SettingsFacadeServices.save#SftpServer",
            "facade.SettingsFacadeServices.save#LlmSettings",
            "facade.SettingsFacadeServices.save#TenantChatSpace",
            "facade.SettingsFacadeServices.delete#TenantChatSpace",
            "facade.ShopifyFacadeServices.save#ShopifyAuthConfig",
            "facade.ShopifyFacadeServices.delete#ShopifyAuthConfig",
            "facade.HotWaxOmsFacadeServices.save#HotWaxOmsRestSourceConfig",
            "facade.HotWaxOmsFacadeServices.delete#HotWaxOmsRestSourceConfig",
            "facade.SlackFacadeServices.save#SlackBotToken",
            "facade.SlackFacadeServices.begin#SlackInstall",
            "facade.SlackFacadeServices.disconnect#SlackWorkspace",
            // CLI sign-in approval, listing and revoking are browser-only
            "facade.CliAuthFacadeServices.approve#CliLogin",
            "facade.CliAuthFacadeServices.deny#CliLogin",
            "facade.CliAuthFacadeServices.get#CliLoginRequest",
            "facade.CliAuthFacadeServices.list#CliSessions",
            "facade.CliAuthFacadeServices.revoke#CliSession",
            // final review Important 2: a CLI session must never mint another CLI key (the CLI's own sign-in calls
            // these anonymously, so the fence never needs them)
            "facade.CliAuthFacadeServices.start#CliLogin",
            "facade.CliAuthFacadeServices.poll#CliLogin",
            // it can send now
            "facade.TenantScriptFacadeServices.run#TenantScriptDeployment",
    ].asImmutable()

    /** Verbs an allowed method may have: reads and run starters. */
    private static final Set<String> READ_OR_RUN_VERBS = ["get", "list", "search", "preview", "test",
                                                          "run", "retry", "cancel"] as Set
    private static final String TENANT_SCRIPT_SERVICES = "facade.TenantScriptFacadeServices."
    private static final String CLI_LOGOUT = "facade.CliAuthFacadeServices.logout#CliSession"

    private static List<String> contractMethods() {
        Path methodsFile = ReconciliationSmokeTestSupport.resolveBackendRoot()
                .resolve("runtime/component/darpan/docs/api-contract/methods.txt")
        List<String> methods = Files.readAllLines(methodsFile).collect { it.trim() }.findAll { it && !it.startsWith("#") }
        assertTrue(methods.size() > 100, "contract looks empty: ${methods.size()} methods")
        return methods
    }

    @Test
    void everyMethodInTheContractIsInExactlyOneList() {
        List<String> unplaced = contractMethods().findAll { !CliMethodAllowlist.ALLOWED.contains(it) && !CliMethodAllowlist.EXCLUDED.contains(it) }
        assertTrue(unplaced.isEmpty(), "place each in CliMethodAllowlist.ALLOWED or .EXCLUDED: ${unplaced}")
        Set<String> both = CliMethodAllowlist.ALLOWED.intersect(CliMethodAllowlist.EXCLUDED)
        assertTrue(both.isEmpty(), "in both lists: ${both}")
    }

    @Test
    void everyListedMethodIsInTheContract() {
        // A name that is not published is a typo or a deleted service; either way the list no longer says what it means.
        Set<String> published = contractMethods() as Set
        List<String> stale = (CliMethodAllowlist.ALLOWED + CliMethodAllowlist.EXCLUDED).findAll { !published.contains(it) }.sort()
        assertTrue(stale.isEmpty(), "not in docs/api-contract/methods.txt: ${stale}")
    }

    @Test
    void noAdminMethodIsAllowed() {
        List<String> admin = CliMethodAllowlist.ALLOWED.findAll { !it.startsWith("facade.") }.sort()
        assertTrue(admin.isEmpty(), "only facade.* may be on the CLI allowlist: ${admin}")
        assertTrue(contractMethods().findAll { it.startsWith("admin.") }.every { CliMethodAllowlist.EXCLUDED.contains(it) })
    }

    @Test
    void theNeverListInGlobalConstraintsIsExcluded() {
        Set<String> published = contractMethods() as Set
        NEVER_ALLOWED.each { String method ->
            assertTrue(published.contains(method), "never-list name is not published (renamed?): ${method}")
            assertFalse(CliMethodAllowlist.ALLOWED.contains(method), "must never be on the CLI allowlist: ${method}")
            assertTrue(CliMethodAllowlist.EXCLUDED.contains(method), "not excluded: ${method}")
        }
    }

    @Test
    void noSaveDeleteCreateOrUpdateOutsideTenantScriptsAndCliLogoutIsAllowed() {
        List<String> writers = CliMethodAllowlist.ALLOWED.findAll { String method ->
            if (method == CLI_LOGOUT || method.startsWith(TENANT_SCRIPT_SERVICES)) return false
            return !READ_OR_RUN_VERBS.contains(verbOf(method))
        }.sort()
        assertTrue(writers.isEmpty(), "a CLI session may only read, start runs and use tenant scripts: ${writers}")
    }

    @Test
    void theCliPathsAreTheJsonRpcAndGraphqlEndpointsOnly() {
        assertEquals(["/rpc/json", "/rest/s1/graphql"], CliMethodAllowlist.CLI_PATHS)
    }

    private static String verbOf(String method) {
        String verbAndNoun = method.substring(method.lastIndexOf('.') + 1)
        return verbAndNoun.substring(0, verbAndNoun.indexOf('#'))
    }
}
