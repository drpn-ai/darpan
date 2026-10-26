package darpan.security

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.Test
import org.moqui.impl.webapp.MoquiServlet

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-DOCS-005: the developers.drpn.ai API explorer calls the public GraphQL API from the browser. A
 * webapp_public_api_origins origin gets CORS on webapp_public_api_paths only, and never
 * Access-Control-Allow-Credentials, and a request from it that carries a cookie is refused, so a page on that
 * origin cannot act through a logged-in user's session.
 */
class PublicApiCorsTests {
    static final Set<String> ORIGINS = ["https://developers.drpn.ai"] as Set
    static final List<String> PATHS = ["/rest/s1/graphql/public", "/rest/s1/graphql/sdl"]

    @Test
    void allowsTheListedOriginOnTheListedPathsOnly() {
        assertTrue(MoquiServlet.publicApiAllows(ORIGINS, PATHS, "https://developers.drpn.ai", "/rest/s1/graphql/public"))
        assertTrue(MoquiServlet.publicApiAllows(ORIGINS, PATHS, "https://developers.drpn.ai", "/rest/s1/graphql/sdl"))
        // the session realm and the app's RPC stay closed to it
        assertFalse(MoquiServlet.publicApiAllows(ORIGINS, PATHS, "https://developers.drpn.ai", "/rest/s1/graphql"))
        assertFalse(MoquiServlet.publicApiAllows(ORIGINS, PATHS, "https://developers.drpn.ai", "/rpc/json"))
    }

    @Test
    void matchesPathsOnSegmentBoundaries() {
        assertTrue(MoquiServlet.publicApiAllows(ORIGINS, PATHS, "https://developers.drpn.ai", "/rest/s1/graphql/public/x"))
        assertFalse(MoquiServlet.publicApiAllows(ORIGINS, PATHS, "https://developers.drpn.ai", "/rest/s1/graphql/publicity"))
        assertTrue(MoquiServlet.publicApiAllows(ORIGINS, ["/rest/s1/graphql/public/"], "https://developers.drpn.ai", "/rest/s1/graphql/public"))
    }

    @Test
    void matchesTheOriginExactlyNotBySuffix() {
        assertFalse(MoquiServlet.publicApiAllows(ORIGINS, PATHS, "https://evil.developers.drpn.ai", "/rest/s1/graphql/public"))
        assertFalse(MoquiServlet.publicApiAllows(ORIGINS, PATHS, "http://developers.drpn.ai", "/rest/s1/graphql/public"))
        assertFalse(MoquiServlet.publicApiAllows(ORIGINS, PATHS, "https://app.drpn.ai", "/rest/s1/graphql/public"))
    }

    @Test
    void nothingIsAllowedWhenUnconfigured() {
        assertFalse(MoquiServlet.publicApiAllows([] as Set, PATHS, "https://developers.drpn.ai", "/rest/s1/graphql/public"))
        assertFalse(MoquiServlet.publicApiAllows(ORIGINS, [], "https://developers.drpn.ai", "/rest/s1/graphql/public"))
    }

    @Test
    void preflightIsAnsweredWithoutCredentials() {
        Map<String, String> out = [:]
        Integer[] status = [null]
        boolean answered = MoquiServlet.handlePublicApiCors(request("OPTIONS", ["Access-Control-Request-Method": "POST",
                "Access-Control-Request-Headers": "authorization,content-type"]), response(out, status), "https://developers.drpn.ai")

        assertTrue(answered)
        assertEquals(200, status[0])
        assertEquals("https://developers.drpn.ai", out["Access-Control-Allow-Origin"])
        assertEquals("authorization,content-type", out["Access-Control-Allow-Headers"])
        assertEquals("GET,POST,OPTIONS", out["Access-Control-Allow-Methods"])
        assertNull(out["Access-Control-Allow-Credentials"])
    }

    @Test
    void anActualRequestCarriesTheOriginAndContinues() {
        Map<String, String> out = [:]
        Integer[] status = [null]
        boolean answered = MoquiServlet.handlePublicApiCors(request("POST", [:]), response(out, status), "https://developers.drpn.ai")

        assertFalse(answered)
        assertNull(status[0])
        assertEquals("https://developers.drpn.ai", out["Access-Control-Allow-Origin"])
        assertNull(out["Access-Control-Allow-Credentials"])
    }

    @Test
    void aRequestCarryingACookieIsRefusedBeforeItReachesASession() {
        Map<String, String> out = [:]
        Integer[] status = [null]
        boolean answered = MoquiServlet.handlePublicApiCors(request("POST", [Cookie: "darpan_login_key=abc"]),
                response(out, status), "https://developers.drpn.ai")

        assertTrue(answered)
        assertEquals(401, status[0])
        assertNull(out["Access-Control-Allow-Origin"])
    }

    private static HttpServletRequest request(String method, Map<String, String> headers) {
        return [getMethod: { -> method }, getHeader: { String name -> headers[name] }] as HttpServletRequest
    }

    private static HttpServletResponse response(Map<String, String> out, Integer[] status) {
        return [setHeader: { String name, String value -> out[name] = value },
                setStatus: { int code -> status[0] = code }] as HttpServletResponse
    }
}
