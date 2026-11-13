package darpan.facade.auth

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.moqui.context.AuthenticationRequiredException
import org.moqui.impl.webapp.ScreenResourceNotFoundException

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-264 — the framework's password-only ways in are refused for everyone: HTTP Basic and authUsername/authPassword
 * with 401 (the request signed out again first), the /Login, /SecondFactor and /ChangePassword screens with 404. Darpan's
 * own sign-in (/rpc/json login#Session) passes. Stubs, no boot.
 */
class PasswordOnlyRouteFenceTests {

    @Test
    @DisplayName("Basic credentials and auth parameters are password-only; a login_key header or Bearer is not")
    void whatCountsAsAPasswordOnlySignIn() {
        assertTrue(PasswordOnlyRouteFence.carriesPasswordCredentials("Basic dTpw", []))
        assertTrue(PasswordOnlyRouteFence.carriesPasswordCredentials("  basic dTpw", []))
        assertTrue(PasswordOnlyRouteFence.carriesPasswordCredentials(null, ["authUsername"]))
        assertTrue(PasswordOnlyRouteFence.carriesPasswordCredentials(null, ["x", "authPassword"]))
        assertFalse(PasswordOnlyRouteFence.carriesPasswordCredentials("Bearer abc", ["login_key", "method"]))
        assertFalse(PasswordOnlyRouteFence.carriesPasswordCredentials(null, []))
    }

    @Test
    @DisplayName("the three framework screens are closed at the root, with or without a render-mode extension")
    void closedScreens() {
        assertEquals("Login", PasswordOnlyRouteFence.closedScreen(["Login"]))
        assertEquals("Login", PasswordOnlyRouteFence.closedScreen(["Login", "login"]))
        assertEquals("SecondFactor", PasswordOnlyRouteFence.closedScreen(["SecondFactor.html"]))
        assertEquals("ChangePassword", PasswordOnlyRouteFence.closedScreen(["ChangePassword", "changePassword"]))
        assertNull(PasswordOnlyRouteFence.closedScreen(["rpc", "json"]))
        assertNull(PasswordOnlyRouteFence.closedScreen(["apps", "Login"]))
        assertNull(PasswordOnlyRouteFence.closedScreen([]))
    }

    @Test
    @DisplayName("DAR-BE-028: the framework's /Login/resetPassword answers 404 in every shape (RpcMethodFence no longer fences it itself)")
    void loginResetPasswordIsClosed() {
        ["/Login/resetPassword", "/Login/resetPassword;jsessionid=x", "/Login/resetPassword/x", "/Login/resetPassword.json",
         "/Login.html/resetPassword", "/Login/~a/resetPassword", "/~a/Login/resetPassword", "/x/../Login/resetPassword"].each { String uri ->
            def ec = new Expando(web: new Expando(request: new RequestStub(uri: uri, servletPath: ""), requestParameters: [username: "x"],
                    secureRequestParameters: [:]), user: new WebSignInLifetimeTests.UserStub())
            assertThrows(ScreenResourceNotFoundException, { PasswordOnlyRouteFence.enforce(ec) }, uri)
        }
    }

    @Test
    @DisplayName("a Basic sign-in is signed out again and refused with 401, right password or not")
    void basicIsRefusedAndSignedOut() {
        def session = new WebSignInLifetimeTests.SessionStub()
        def request = new RequestStub(headers: [Authorization: "Basic " + "tf.user:right-password".bytes.encodeBase64().toString()],
                session: session, uri: "/rest/s1/darpan/anything", servletPath: "/rest")
        def user = new WebSignInLifetimeTests.UserStub()
        def ec = new Expando(web: new Expando(request: request, requestParameters: [:], secureRequestParameters: [:]), user: user)
        assertThrows(AuthenticationRequiredException) { PasswordOnlyRouteFence.enforce(ec) }
        assertNull(user.userId, "signed out again")
        assertTrue(session.invalidated)
    }

    @Test
    @DisplayName("authUsername on the query is refused; /Login answers 404; Darpan's own login#Session passes")
    void parametersScreensAndOwnLogin() {
        def withParams = new Expando(web: new Expando(request: new RequestStub(uri: "/rpc/json", servletPath: ""),
                requestParameters: [authUsername: "tf.user", authPassword: "x"], secureRequestParameters: [:]),
                user: new WebSignInLifetimeTests.UserStub())
        assertThrows(AuthenticationRequiredException) { PasswordOnlyRouteFence.enforce(withParams) }

        def login = new Expando(web: new Expando(request: new RequestStub(uri: "/Login/login", servletPath: ""),
                requestParameters: [:], secureRequestParameters: [:]), user: new WebSignInLifetimeTests.UserStub())
        assertThrows(ScreenResourceNotFoundException) { PasswordOnlyRouteFence.enforce(login) }

        def ownLogin = new Expando(web: new Expando(request: new RequestStub(uri: "/rpc/json", servletPath: ""),
                requestParameters: [method: "facade.AuthFacadeServices.login#Session", params: [username: "tf.user", password: "x"]],
                secureRequestParameters: [:]), user: new WebSignInLifetimeTests.UserStub())
        PasswordOnlyRouteFence.enforce(ownLogin)
        assertEquals("WEB_USER", ownLogin.user.userId, "nothing signed out")
    }

    @Test
    @DisplayName("DAR-BE-276: credentials anywhere in a JSON body are refused: params, a batch, GraphQL variables, authUserAccount")
    void credentialsInsideAJsonBody() {
        Map refused = [
                rpcParams   : [method: "facade.AuthFacadeServices.get#SessionInfo", params: [authUsername: "tf.user", authPassword: "x"]],
                rpcAccount  : [method: "facade.AuthFacadeServices.get#SessionInfo", params: [authUserAccount: [username: "tf.user", currentPassword: "x"]]],
                batch       : [_requestBodyJsonList: [[method: "facade.AuthFacadeServices.get#SessionInfo", params: [:]],
                                                      [method: "facade.AuthFacadeServices.logout#AllSessions", params: [authPassword: "x"]]]],
                gqlVariables: [query: "query { x }", variables: [input: [list: [[authUsername: "tf.user"]]]]],
        ]
        refused.each { String name, Map body ->
            def user = new WebSignInLifetimeTests.UserStub()
            def ec = new Expando(web: new Expando(request: new RequestStub(uri: "/rpc/json", servletPath: ""),
                    requestParameters: body, secureRequestParameters: body), user: user)
            assertThrows(AuthenticationRequiredException, { PasswordOnlyRouteFence.enforce(ec) }, name)
            assertNull(user.userId, "${name}: signed out again")
        }

        assertTrue(PasswordOnlyRouteFence.nestsCredentials([params: [deep: [[authPassword: "x"]]]]))
        assertFalse(PasswordOnlyRouteFence.nestsCredentials([method: "facade.AuthFacadeServices.login#Session",
                params: [username: "tf.user", password: "x", note: "authUsername as a value is fine"]]))
        assertFalse(PasswordOnlyRouteFence.nestsCredentials(null))
    }

    static class RequestStub {
        Map<String, String> headers = [:]
        WebSignInLifetimeTests.SessionStub session
        String uri = "/"
        String servletPath = ""
        String getHeader(String name) { headers[name] }
        String getRequestURI() { uri }
        String getContextPath() { "" }
        String getPathInfo() { uri }
        Enumeration<String> getParameterNames() { Collections.emptyEnumeration() }
        WebSignInLifetimeTests.SessionStub getSession(boolean create) { session?.invalidated ? null : session }
    }
}
