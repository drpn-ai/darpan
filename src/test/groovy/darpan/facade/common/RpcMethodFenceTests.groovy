package darpan.facade.common

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-078 — the /rpc/json method fence. Pure decision tests; no Moqui boot. */
class RpcMethodFenceTests {

    private static final String UPDATE_PASSWORD = "org.moqui.impl.UserServices.update#Password"

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
}
