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
            // DAR-CLI-008 (Aditi 2026-11-25): connection credentials are managed in the app — their getters return
            // usernames/ids/webhook URLs, and sharing widens another tenant's access to them
            "facade.SettingsFacadeServices.list#NsAuthConfigs",
            "facade.SettingsFacadeServices.list#NsRestletConfigs",
            "facade.SettingsFacadeServices.list#SftpServers",
            "facade.SettingsFacadeServices.list#TenantChatSpaces",
            "facade.ConfigSharingFacadeServices.grant#ConfigTenantAccess",
            "facade.ConfigSharingFacadeServices.revoke#ConfigTenantAccess",
            "facade.ReconciliationFacadeServices.list#AutomationSourceOptions",
            "facade.TenantConfigFacadeServices.get#TenantConfigVersion",
            "facade.ShopifyFacadeServices.begin#ShopifyInstall",
            "facade.AuthFacadeServices.get#TwoFactorStatus",
            // the embedded agent: a CLI session already is an agent
            "facade.AgentTurnFacadeServices.send#AgentTurn",
            "facade.AgentTurnFacadeServices.send#AgentQueryTurn",
            "facade.AgentReadFacadeServices.get#AgentAuditScope",
            // authoring data-defined reads is a super admin's (DAR-BE-235)
            "facade.DataSourceDefinitionFacadeServices.create#DataSourceDefinition",
            "facade.DataSourceDefinitionFacadeServices.retire#DataSourceDefinition",
    ].asImmutable()

    /** Read verbs: the only AuthFacadeServices methods a CLI session may call besides save#UserSettings. */
    private static final Set<String> READ_VERBS = ["get", "list", "search", "preview", "test"] as Set
    private static final String OWN_SETTINGS = "facade.AuthFacadeServices.save#UserSettings"
    private static final String CLI_LOGOUT = "facade.CliAuthFacadeServices.logout#CliSession"
    /** DAR-CLI-008 (Aditi 2026-11-25: "everything"): tenant-app edits a CLI session reaches; the service checks the role. */
    private static final List<String> TENANT_APP_EDITS = [
            "facade.ReconciliationFacadeServices.save#Automation",
            "facade.AuditFacadeServices.save#AuditSettings",
            "facade.PeakPeriodFacadeServices.save#PeakPeriod",
            "facade.TenantConfigFacadeServices.restore#TenantConfigVersion",
            "facade.AgentChangeFacadeServices.apply#AuditChange",
            "facade.AuthFacadeServices.save#UserSettings",
            "facade.AuditFacadeServices.verify#AuditFindings",
            // DAR-CLI-008 part 2: the person's own AI proposes, reads and applies audit changes
            "facade.AgentChangeFacadeServices.propose#AuditChange",
            "facade.AgentChangeFacadeServices.get#AuditChangeProposal",
            "facade.AgentChangeFacadeServices.list#AuditChangeProposals",
            "facade.AgentReadFacadeServices.find#AuditFindingLookalikes",
            // DAR-CLI-008 part 3: the order lookup and the tenant's lookup reads and links
            "facade.LookupFacadeServices.lookup#Order",
            "facade.LookupFacadeServices.save#TenantLookupRead",
            "facade.LookupFacadeServices.save#TenantLookupLink",
    ].asImmutable()

    /** Final review Important 2: credentials by name, so a NEW credential service is caught, not only today's names. */
    private static final java.util.regex.Pattern CREDENTIAL_NOUN = ~/AuthConfig|RestletConfig|SftpServer|TenantChatSpace|LlmSettings|BotToken|Install|Passkey|TwoFactor|Password|Recovery|SecretHandoff|ApiKey|Webhook|Credential|SignIn|Login/
    /** Getters that return only redaction flags (has*), never the secret; each checked by hand when it was allowed. */
    private static final Set<String> MASKED_GETTERS = ["facade.SettingsFacadeServices.get#LlmSettings",
                                                       "facade.ShopifyFacadeServices.get#ShopifyAuthConfig",
                                                       "facade.ShopifyFacadeServices.list#ShopifyAuthConfigs",
                                                       "facade.SlackFacadeServices.get#SlackInstall"] as Set

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
    void signInAndIdentityStayInTheBrowser() {
        List<String> leaks = CliMethodAllowlist.ALLOWED.findAll { String method ->
            String service = method.substring(0, method.lastIndexOf('.'))
            switch (service) {
                case "facade.AuthFacadeServices": return method != OWN_SETTINGS && !READ_VERBS.contains(verbOf(method))
                case "facade.CliAuthFacadeServices": return method != CLI_LOGOUT
                case "facade.CliAccessFacadeServices": return true
                case "facade.AgentTurnFacadeServices": return true
                default: return false
            }
        }.sort()
        assertTrue(leaks.isEmpty(), "sign-in, identity, CLI access and the embedded agent stay in the browser: ${leaks}")
    }

    @Test
    void noCredentialNamedMethodIsAllowedBeyondTheMaskedGetters() {
        List<String> credential = CliMethodAllowlist.ALLOWED.findAll { String method ->
            !MASKED_GETTERS.contains(method) && CREDENTIAL_NOUN.matcher(method.substring(method.indexOf('#') + 1)).find()
        }.sort()
        assertTrue(credential.isEmpty(), "connection credentials and sign-in stay in the app; a masked getter is named in MASKED_GETTERS: ${credential}")
    }

    @Test
    void tenantAppEditsAreAllowed() {
        List<String> missing = TENANT_APP_EDITS.findAll { !CliMethodAllowlist.ALLOWED.contains(it) }
        assertTrue(missing.isEmpty(), "DAR-CLI-008: a CLI session makes the edits the user's role allows in the app: ${missing}")
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
