package darpan.facade.auth

import darpan.facade.common.RpcMethodFence
import org.moqui.context.AuthenticationRequiredException
import org.moqui.impl.webapp.ScreenResourceNotFoundException

/**
 * DAR-BE-264 — the framework's password-only ways in are closed for everyone (Aditi, 2026-11-13, open question 5).
 *
 * <p>UserFacadeImpl.initFromHttpRequest signs a request in from an HTTP Basic Authorization header or from
 * authUsername/authPassword parameters with the password alone, and the webroot /Login, /SecondFactor and /ChangePassword
 * screens do the same through the framework's own (unlimited) second-factor code. Darpan's clients use none of them
 * (its own Basic headers are outbound, to OMS and GraphQL sources): darpan-ui, the admin app and the CLIs sign in through
 * facade.AuthFacadeServices.login#Session, which asks for the second factor.
 *
 * <p>Runs first in the webroot before-request hook, after the framework has already tried the credentials: a request
 * that carried them is signed out again and refused with 401 whether the password was right or not, so the refusal says
 * nothing about it. The three screens answer 404 on either view of the path (RpcMethodFence's routed and normalised).
 *
 * <p>Not covered: a WebSocket handshake (UserFacadeImpl.initFromHandshakeRequest also reads Basic), which runs no
 * before-request hook. Darpan serves no WebSocket endpoint its clients use.
 */
class PasswordOnlyRouteFence {

    static final List<String> CLOSED_SCREENS = ["Login", "SecondFactor", "ChangePassword"].asImmutable()
    static final List<String> CREDENTIAL_PARAMETERS = ["authUsername", "authPassword"].asImmutable()
    static final String CREDENTIALS_REFUSAL = "Sign in through the Darpan app. Passwords are not accepted on this request."

    static void enforce(def ec) {
        def request = ec?.web?.request
        if (request == null) return
        if (carriesPasswordCredentials(request.getHeader("Authorization") as String, parameterNames(ec))) {
            signOut(ec, request)
            throw new AuthenticationRequiredException(CREDENTIALS_REFUSAL)
        }
        List<String> routed = RpcMethodFence.routedSegments(request)
        List<String> normalised = RpcMethodFence.segmentsOf(RpcMethodFence.requestPath(request))
        String closed = closedScreen(routed) ?: closedScreen(normalised)
        if (closed) throw new ScreenResourceNotFoundException(null, routed, null, closed, null, null)
    }

    /** True for an HTTP Basic Authorization header (any case) or an authUsername/authPassword parameter. Pure. */
    static boolean carriesPasswordCredentials(String authorization, Collection<String> parameterNames) {
        String value = authorization?.trim()
        if (value && value.length() >= 5 && value.substring(0, 5).equalsIgnoreCase("Basic")) return true
        return parameterNames?.any { it in CREDENTIAL_PARAMETERS } ?: false
    }

    /** The closed screen a path starts at, matched on the name before its first dot (a render-mode extension), or null. Pure. */
    static String closedScreen(List<String> segments) {
        if (!segments) return null
        String first = segments[0]
        int dot = first.indexOf('.')
        String name = dot >= 0 ? first.substring(0, dot) : first
        return name in CLOSED_SCREENS ? name : null
    }

    private static Set<String> parameterNames(def ec) {
        Set<String> names = new HashSet<>()
        try {
            Map secure = ec.web.secureRequestParameters as Map
            if (secure) names.addAll(secure.keySet().collect { it as String })
        } catch (Exception ignored) {
            // no parameters to read: the header still counts
        }
        try {
            Map all = ec.web.requestParameters as Map
            if (all) names.addAll(all.keySet().collect { it as String })
        } catch (Exception ignored) {
        }
        def request = ec.web.request
        try {
            def requestNames = request.getParameterNames()
            if (requestNames != null) names.addAll(Collections.list(requestNames).collect { it as String })
        } catch (Exception ignored) {
        }
        return names
    }

    private static void signOut(def ec, def request) {
        try {
            if (ec.user?.userId) ec.user.logoutLocal()
        } catch (Exception ignored) {
            // the session is invalidated below either way
        }
        try {
            request.getSession(false)?.invalidate()
        } catch (IllegalStateException ignored) {
            // already invalidated by the logout
        }
    }
}
