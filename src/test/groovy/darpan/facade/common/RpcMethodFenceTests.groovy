package darpan.facade.common

import darpan.facade.auth.CliAuthSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Test
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ExecutionContext
import org.moqui.context.WebFacade

import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-078 — the /rpc/json method fence; DAR-CLI-003 — the CLI-session allowlist on top. Pure decision tests and
 *  stubbed requests; no Moqui boot. */
class RpcMethodFenceTests {

    private static final String UPDATE_PASSWORD = "org.moqui.impl.UserServices.update#Password"
    private static final String SESSION_INFO = "facade.AuthFacadeServices.get#SessionInfo"
    private static final String SAVED_RUNS = "facade.ReconciliationFacadeServices.list#SavedRuns"
    private static final String SAVE_ACTIVE_TENANT = "facade.AuthFacadeServices.save#ActiveTenant"
    private static final String CLI_PATH_REFUSAL = "This address is not available to the Darpan CLI."

    @Test
    void refusesFrameworkPasswordServicesOnJsonRpc() {
        assertNotNull(RpcMethodFence.rejection("/rpc/json", [method: UPDATE_PASSWORD]))
        assertNotNull(RpcMethodFence.rejection("/rpc/json", [method: "org.moqui.impl.UserServices.reset#Password"]))
    }

    @Test
    void allowsDarpanRemoteNamespaces() {
        assertNull(RpcMethodFence.rejection("/rpc/json", [method: "facade.AuthFacadeServices.login#Session"]))
        assertNull(RpcMethodFence.rejection("/rpc/json", [method: "admin.TenantAdminServices.get#TenantList"]))
        assertNull(RpcMethodFence.rejection("/rpc/json", [method: "gql.QueryServices.get#Sdl"]))
    }

    @Test
    void refusesABatchIfAnyCallIsOutsideTheFence() {
        Map batch = [_requestBodyJsonList: [
                [method: "facade.AuthFacadeServices.get#SessionInfo", params: [:]],
                [method: UPDATE_PASSWORD, params: [:]],
        ]]
        assertNotNull(RpcMethodFence.rejection("/rpc/json", batch))
        assertNull(RpcMethodFence.rejection("/rpc/json", [_requestBodyJsonList: [
                [method: "facade.AuthFacadeServices.get#SessionInfo"], [method: "admin.UserAdminServices.get#UserList"]]]))
    }

    @Test
    void checksTheTopLevelMethodEvenWhenABatchKeyIsPresent() {
        // The dispatcher treats a request as a batch only when _requestBodyJsonList is truthy; an
        // empty JSON array body falls through to the top-level (e.g. query-string) method.
        assertNotNull(RpcMethodFence.rejection("/rpc/json", [_requestBodyJsonList: [], method: UPDATE_PASSWORD]))
        assertNotNull(RpcMethodFence.rejection("/rpc/json", [_requestBodyJsonList: [[method: "facade.AuthFacadeServices.get#SessionInfo"]],
                                                              method: UPDATE_PASSWORD]))
        assertNotNull(RpcMethodFence.rejection("/rpc/json", [_requestBodyJsonList: "not-a-list", method: UPDATE_PASSWORD]))
    }

    @Test
    void leavesAMissingMethodToTheDispatcher() {
        assertNull(RpcMethodFence.rejection("/rpc/json", [:]))
        assertNull(RpcMethodFence.rejection("/rpc/json", [_requestBodyJsonList: ["not-a-map"]]))
    }

    @Test
    void appliesToEveryPathShapeThatReachesTheTransition() {
        ["/rpc/json", "/rpc/json/", "//rpc//json", "/rpc/json/extra", "/rpc;jsessionid=x/json",
         "/qapps/darpan/rpc/json"].each { String path ->
            assertNotNull(RpcMethodFence.rejection(path, [method: UPDATE_PASSWORD]), "not fenced: ${path}")
        }
    }

    @Test
    void ignoresAMethodParameterOutsideJsonRpc() {
        assertNull(RpcMethodFence.rejection("/apps/darpan/Settings", [method: UPDATE_PASSWORD]))
        assertNull(RpcMethodFence.rejection("/rest/s1/graphql", [method: UPDATE_PASSWORD]))
        assertNull(RpcMethodFence.rejection("/rpcx/json", [method: UPDATE_PASSWORD]))
    }

    @Test
    void closesTheFrameworkRestLoginTransition() {
        ["/rest/login", "/rest/login/", "/rest/login/x", "/rest/login;foo/x", "//rest//login"].each { String path ->
            assertNotNull(RpcMethodFence.rejection(path, [username: "x", password: "y"]), "not fenced: ${path}")
        }
        assertNull(RpcMethodFence.rejection("/rest/s1/graphql/sdl", [:]))
    }

    /** DAR-BE-028 — the framework's reset#Password screen transition is a weaker second reset (it reveals
     *  whether an account exists and its emailed password never expires); /Login itself stays for legacy /apps. */
    @Test
    void closesTheFrameworkLoginResetPasswordTransition() {
        ["/Login/resetPassword", "/Login/resetPassword;jsessionid=x", "/Login/resetPassword/x"].each { String path ->
            assertEquals("The /Login/resetPassword endpoint is not available.",
                    RpcMethodFence.rejection(path, [username: "x"]), "not fenced: ${path}")
        }
        assertNull(RpcMethodFence.rejection("/Login", [:]))
        assertNull(RpcMethodFence.rejection("/Login/login", [username: "x", password: "y"]))
    }

    /** Security review 2026-10-21: Moqui strips a render-mode extension before matching a screen or transition
     *  name (ScreenUrlInfo: "is there an extension beyond a transition name?"), so a fenced transition must be
     *  matched on the name before the first dot. */
    @Test
    void fencedTransitionsCannotBeReachedWithARenderModeExtension() {
        ["/Login/resetPassword.json", "/Login/resetPassword.html", "/Login.html/resetPassword",
         "/rest/login.json", "/rest.json/login"].each { String path ->
            assertNotNull(RpcMethodFence.rejection(path, [username: "x"]), "not fenced: ${path}")
        }
        assertNull(RpcMethodFence.rejection("/Login/login.json", [username: "x", password: "y"]))
    }

    @Test
    void everyPublishedApiMethodPassesTheFence() {
        // The product's remote surface: if the fence ever refuses one of these, a client breaks.
        Path methodsFile = ReconciliationSmokeTestSupport.resolveBackendRoot()
                .resolve("runtime/component/darpan/docs/api-contract/methods.txt")
        List<String> methods = Files.readAllLines(methodsFile).collect { it.trim() }.findAll { it && !it.startsWith("#") }
        assertTrue(methods.size() > 100, "contract looks empty: ${methods.size()} methods")
        List<String> refused = methods.findAll { !RpcMethodFence.isAllowedMethod(it) }
        assertTrue(refused.isEmpty(), "fence refuses published API methods: ${refused}")
    }

    @Test
    void everyAnonymousRemoteFrameworkServiceIsRefused() {
        // Tripwire for a framework upgrade that adds another no-login remote service.
        Path frameworkServices = ReconciliationSmokeTestSupport.resolveBackendRoot().resolve("framework/service")
        List<String> anonymousRemote = []
        Files.walk(frameworkServices).filter { it.toString().endsWith(".xml") }.each { Path file ->
            def root = new groovy.xml.XmlSlurper().parse(file.toFile())
            String prefix = frameworkServices.relativize(file).toString().replace('/', '.').replaceFirst(/\.xml$/, '')
            root.service.each { svc ->
                String auth = svc.@authenticate.toString()
                if (svc.'@allow-remote'.toString() == "true" && (auth.startsWith("anonymous") || auth == "false")) {
                    anonymousRemote << "${prefix}.${svc.@verb}#${svc.@noun}".toString()
                }
            }
        }
        assertTrue(anonymousRemote.contains(UPDATE_PASSWORD), "scan found nothing, so it proves nothing: ${anonymousRemote}")
        anonymousRemote.each { String name -> assertFalse(RpcMethodFence.isAllowedMethod(name), "fence lets through ${name}") }
    }

    // ---- DAR-CLI-003: a CLI session reaches only CliMethodAllowlist.ALLOWED, on /rpc/json and /rest/s1/graphql ----

    @Test
    void cliAnAllowedMethodPasses() {
        assertNull(RpcMethodFence.cliRejection("/rpc/json", [SESSION_INFO]))
        assertNull(RpcMethodFence.cliRejection("/rpc/json", [SESSION_INFO, SAVED_RUNS]))
        assertNull(RpcMethodFence.cliRejection("/rpc/json", []))
        assertNull(RpcMethodFence.cliRejection("/rpc/json/", ["facade.TenantScriptFacadeServices.save#TenantScript"]))
    }

    @Test
    void cliABatchWithOneExcludedMethodIsRefused() {
        assertEquals("${SAVE_ACTIVE_TENANT} is not available to the Darpan CLI; use the app.".toString(),
                RpcMethodFence.cliRejection("/rpc/json", [SESSION_INFO, SAVE_ACTIVE_TENANT, SAVED_RUNS]))
        assertNotNull(RpcMethodFence.cliRejection("/rpc/json", ["admin.TenantAdminServices.get#TenantList"]))
        assertNotNull(RpcMethodFence.cliRejection("/rpc/json", ["gql.QueryServices.get#Sdl"]))
        assertNotNull(RpcMethodFence.cliRejection("/rpc/json", ["facade.TenantScriptFacadeServices.run#TenantScriptDeployment"]))
        // through enforce, where the batch comes from the parsed body as the dispatcher reads it
        ExecutionContext cli = stubEc(true, "/rpc/json", [_requestBodyJsonList: [[method: SESSION_INFO], [method: SAVE_ACTIVE_TENANT]]])
        ArtifactAuthorizationException refused = assertThrows(ArtifactAuthorizationException) { RpcMethodFence.enforce(cli) }
        assertTrue(refused.message.contains("${SAVE_ACTIVE_TENANT} is not available to the Darpan CLI"), refused.message)
        // the top-level method counts too, whatever the batch holds
        assertThrows(ArtifactAuthorizationException) {
            RpcMethodFence.enforce(stubEc(true, "/rpc/json", [method: SAVE_ACTIVE_TENANT, _requestBodyJsonList: [[method: SESSION_INFO]]]))
        }
    }

    @Test
    void cliRestE1IsRefused() {
        ["/rest/e1/moqui.security.UserAccount", "/rest/s1/moqui/users", "/rest/s1/darpan/anything", "/rest/login",
         "/apps/darpan/Settings", "/qapps/darpan/rpc/json", "/rpc/json/extra", "/rpc/json.json", "/rest/s1/graphql/public",
         "/", "", null].each { String path ->
            assertEquals(CLI_PATH_REFUSAL, RpcMethodFence.cliRejection(path, []), "CLI let through: ${path}")
        }
        ArtifactAuthorizationException refused = assertThrows(ArtifactAuthorizationException) {
            RpcMethodFence.enforce(stubEc(true, "/rest/e1/moqui.security.UserAccount", [:]))
        }
        assertTrue(refused.message.contains(CLI_PATH_REFUSAL), refused.message)
    }

    @Test
    void cliRestS1GraphqlIsAdmitted() {
        assertNull(RpcMethodFence.cliRejection("/rest/s1/graphql", []))
        assertNull(RpcMethodFence.cliRejection("/rest/s1/graphql/", []))
        assertNull(RpcMethodFence.cliRejection("/rest/s1/graphql;jsessionid=x", []))
        // a method parameter means nothing to the GraphQL endpoint, so enforce does not read one there
        RpcMethodFence.enforce(stubEc(true, "/rest/s1/graphql", [method: SAVE_ACTIVE_TENANT, query: "{ orders { id } }"]))
        RpcMethodFence.enforce(stubEc(true, "/rpc/json", [method: SESSION_INFO]))
    }

    @Test
    void nonCliRequestsAreUnchanged() {
        // what the browser and admin app reach today still passes
        RpcMethodFence.enforce(stubEc(false, "/rest/e1/moqui.security.UserAccount", [:]))
        RpcMethodFence.enforce(stubEc(false, "/apps/darpan/Settings", [:]))
        RpcMethodFence.enforce(stubEc(false, "/rpc/json", [method: SAVE_ACTIVE_TENANT]))
        RpcMethodFence.enforce(stubEc(false, "/rpc/json", [_requestBodyJsonList: [[method: "admin.TenantAdminServices.get#TenantList"],
                                                                                [method: "gql.QueryServices.get#Sdl"]]]))
        // and the DAR-BE-078 fence still refuses with its own reason, CLI or not
        [false, true].each { boolean cli ->
            ArtifactAuthorizationException refused = assertThrows(ArtifactAuthorizationException) {
                RpcMethodFence.enforce(stubEc(cli, "/rpc/json", [method: UPDATE_PASSWORD]))
            }
            assertEquals("JSON-RPC method is not available: ${UPDATE_PASSWORD}".toString(), refused.message)
        }
        // no web request (a service call, a job): nothing to fence
        RpcMethodFence.enforce([getWeb: { -> null }] as ExecutionContext)
    }

    // ---- DAR-BE-189: the fence judges the raw path Moqui routes on, not only the container's normalised one ----
    // Jetty 12.1.5 probe (2026-11-03): each raw URI below reaches the servlet with the pathInfo beside it, while
    // Moqui routes on the raw URI's segments (WebFacadeImpl.getPathInfoList), dot segments unresolved.

    private static final String NS_AUTH_CONFIGS = "facade.SettingsFacadeServices.list#NsAuthConfigs"
    private static final Map<String, String> DOT_PROBES = [
            "/rpc/json/../../rest/s1/graphql"                   : "/rest/s1/graphql",
            "/rpc/json/./../../rest/s1/graphql"                 : "/rest/s1/graphql",
            "/rest/e1/moqui.security.UserAccount/../../../rpc/json": "/rpc/json",
    ].asImmutable()

    @Test
    void routedSegmentsAreTheRawUriAsMoquiSplitsIt() {
        assertEquals(["rpc", "json", "..", "..", "rest", "s1", "graphql"],
                RpcMethodFence.routedSegments(request("/rpc/json/../../rest/s1/graphql", "/rest/s1/graphql", [:])))
        assertEquals(["rpc", "json"], RpcMethodFence.routedSegments(request("//rpc//json/", "/rpc/json", [:])))
        HttpServletRequest withContext = [getServletPath: { -> "" }, getPathInfo: { -> "/rpc/json" },
                                          getContextPath: { -> "/darpan" }, getRequestURI: { -> "/darpan/rpc/json" }] as HttpServletRequest
        assertEquals(["rpc", "json"], RpcMethodFence.routedSegments(withContext))
    }

    @Test
    void cliDotSegmentsCannotHideAJsonRpcCall() {
        DOT_PROBES.each { String rawUri, String pathInfo ->
            [NS_AUTH_CONFIGS, SESSION_INFO].each { String method ->
                ArtifactAuthorizationException refused = assertThrows(ArtifactAuthorizationException, {
                    RpcMethodFence.enforce(stubEc(true, pathInfo, [method: method], rawUri))
                }, "CLI let through ${rawUri} -> ${method}".toString())
                // the general fence may refuse first (the views disagree about JSON-RPC); either refusal holds
                assertTrue(refused.message in [CLI_PATH_REFUSAL, RpcMethodFence.DOT_SEGMENT_REFUSAL], "${rawUri}: ${refused.message}".toString())
            }
        }
        // the pure check: a raw path that differs from the normalised one, or carries . / .. / ~ segments
        assertEquals(CLI_PATH_REFUSAL, RpcMethodFence.cliRejection(["rpc", "json", "..", "..", "rest", "s1", "graphql"],
                ["rest", "s1", "graphql"], []))
        assertEquals(CLI_PATH_REFUSAL, RpcMethodFence.cliRejection(["rpc", ".", "json"], ["rpc", ".", "json"], [SESSION_INFO]))
        assertEquals(CLI_PATH_REFUSAL, RpcMethodFence.cliRejection(["~admin", "rpc", "json"], ["~admin", "rpc", "json"], []))
        assertEquals(CLI_PATH_REFUSAL, RpcMethodFence.cliRejection(["rpc", "json"], ["rest", "s1", "graphql"], []))
    }

    @Test
    void anonymousDotSegmentsCannotReachFrameworkServices() {
        // DAR-BE-078's own fence, not a CLI session: the framework password service stays out on every probe shape
        DOT_PROBES.each { String rawUri, String pathInfo ->
            ArtifactAuthorizationException refused = assertThrows(ArtifactAuthorizationException, {
                RpcMethodFence.enforce(stubEc(false, pathInfo, [method: UPDATE_PASSWORD], rawUri))
            }, "fence let through ${rawUri}".toString())
            assertEquals("JSON-RPC method is not available: ${UPDATE_PASSWORD}".toString(), refused.message, rawUri)
        }
        assertNotNull(RpcMethodFence.rejection(["rpc", "json", "..", "..", "rest", "s1", "graphql"], ["rest", "s1", "graphql"],
                [method: UPDATE_PASSWORD]))
        // /rest/login and /Login/resetPassword behind dot segments, either way round
        assertNotNull(RpcMethodFence.rejection(["x", "..", "rest", "login"], ["rest", "login"], [:]))
        assertNotNull(RpcMethodFence.rejection(["Login", "resetPassword", "..", ".."], [], [:]))
    }

    @Test
    void aDotPathOnWhichTheViewsDisagreeAboutJsonRpcIsRefused() {
        // even a method the fence would admit: the disagreement itself is the refusal
        ["/rpc/json/../../rest/s1/graphql", "/rpc/json/./../../rest/s1/graphql"].each { String rawUri ->
            ArtifactAuthorizationException refused = assertThrows(ArtifactAuthorizationException) {
                RpcMethodFence.enforce(stubEc(false, "/rest/s1/graphql", [method: SESSION_INFO], rawUri))
            }
            assertEquals(RpcMethodFence.DOT_SEGMENT_REFUSAL, refused.message, rawUri)
        }
        // the other way round: the container sees /rpc/json, the raw path does not
        assertEquals(RpcMethodFence.DOT_SEGMENT_REFUSAL, RpcMethodFence.rejection(["rpc", "x", "..", "json"], ["rpc", "json"], [:]))
        // a dot path both views read the same way is judged as before; a plain path is untouched
        assertNull(RpcMethodFence.rejection(["apps", "x", "..", "Settings"], ["apps", "Settings"], [method: UPDATE_PASSWORD]))
        RpcMethodFence.enforce(stubEc(false, "/rpc/json", [method: SAVE_ACTIVE_TENANT], "/rpc/json"))
    }

    /** An ExecutionContext on one web request. isCliSession reads its answer from the request memo, so no database.
     *  pathInfo is what the container hands the servlet; rawUri (default: the same) is what Moqui routes on. */
    private static ExecutionContext stubEc(boolean cliSession, String path, Map params, String rawUri = null) {
        HttpServletRequest request = request(rawUri != null ? rawUri : path, path, [(CliAuthSupport.CLI_SESSION_ATTRIBUTE): cliSession])
        WebFacade web = [getRequest: { -> request }, getRequestParameters: { -> params }] as WebFacade
        return [getWeb: { -> web }, getContext: { -> null }] as ExecutionContext
    }

    private static HttpServletRequest request(String rawUri, String pathInfo, Map<String, Object> attributes) {
        return [
                getServletPath: { -> "" },
                getPathInfo   : { -> pathInfo },
                getContextPath: { -> "" },
                getRequestURI : { -> rawUri },
                getAttribute  : { String name -> attributes[name] },
                setAttribute  : { String name, Object value -> attributes[name] = value },
        ] as HttpServletRequest
    }
}
