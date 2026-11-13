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
 * <p>DAR-BE-276: the framework also signed a service call in from authUsername/authPassword/authUserAccount inside its
 * own parameters, which over /rpc/json are the JSON body's params (or each call of a batch) and over GraphQL its
 * variables. The fork no longer does (ServiceCallSyncImpl); as a second line, a body carrying any of those keys at any
 * depth is refused here the same way, read from the body the framework has already parsed.
 *
 * <p>Not covered: a WebSocket handshake (UserFacadeImpl.initFromHandshakeRequest also reads Basic), which runs no
 * before-request hook. Darpan serves no WebSocket endpoint its clients use.
 */
class PasswordOnlyRouteFence {

    static final List<String> CLOSED_SCREENS = ["Login", "SecondFactor", "ChangePassword"].asImmutable()
    static final Set<String> CREDENTIAL_PARAMETERS = (["authUsername", "authPassword", "authUserAccount"] as Set<String>).asImmutable()
    static final String CREDENTIALS_REFUSAL = "Sign in through the Darpan app. Passwords are not accepted on this request."

    static void enforce(def ec) {
        def request = ec?.web?.request
        if (request == null) return
        if (carriesPasswordCredentials(request.getHeader("Authorization") as String, parameterNames(ec))
                || nestsCredentials(requestParameters(ec))) {
            signOut(ec, request)
            throw new AuthenticationRequiredException(CREDENTIALS_REFUSAL)
        }
        List<String> routed = RpcMethodFence.routedSegments(request)
        List<String> normalised = RpcMethodFence.segmentsOf(RpcMethodFence.requestPath(request))
        String closed = closedScreen(routed) ?: closedScreen(normalised)
        if (closed) throw new ScreenResourceNotFoundException(null, routed, null, closed, null, null)
    }

    /** True for an HTTP Basic Authorization header (any case) or an authUsername/authPassword/authUserAccount parameter. Pure. */
    static boolean carriesPasswordCredentials(String authorization, Collection<String> parameterNames) {
        String value = authorization?.trim()
        if (value && value.length() >= 5 && value.substring(0, 5).equalsIgnoreCase("Basic")) return true
        return parameterNames?.any { it in CREDENTIAL_PARAMETERS } ?: false
    }

    /**
     * True when a credential key sits in any map at any depth of an already-parsed request (JSON-RPC params, each call of a
     * batch's _requestBodyJsonList, GraphQL variables). Keys only: a value that merely reads "authUsername" is fine. Walks
     * maps and lists without recursion, so a deeply nested body cannot exhaust the stack. Pure.
     */
    static boolean nestsCredentials(Object parsed) {
        Deque<Object> pending = new ArrayDeque<>()
        if (parsed instanceof Map || parsed instanceof Collection) pending.push(parsed)
        while (!pending.isEmpty()) {
            Object value = pending.pop()
            Collection children
            if (value instanceof Map) {
                for (Object key : ((Map) value).keySet()) {
                    if (key instanceof String && CREDENTIAL_PARAMETERS.contains((String) key)) return true
                }
                children = ((Map) value).values()
            } else {
                children = (Collection) value
            }
            for (Object child : children) {
                if (child instanceof Map || child instanceof Collection) pending.push(child)
            }
        }
        return false
    }

    /** The closed screen a path starts at, matched on the name before its first dot (a render-mode extension), or null. Pure. */
    static String closedScreen(List<String> segments) {
        if (!segments) return null
        String first = segments[0]
        int dot = first.indexOf('.')
        String name = dot >= 0 ? first.substring(0, dot) : first
        return name in CLOSED_SCREENS ? name : null
    }

    /** The parameters the framework parsed for this request (query, form and JSON body), or null. */
    private static Object requestParameters(def ec) {
        try {
            return ec.web.requestParameters
        } catch (Exception ignored) {
            return null
        }
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
