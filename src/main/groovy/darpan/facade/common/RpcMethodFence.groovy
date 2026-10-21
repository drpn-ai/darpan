package darpan.facade.common

import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ExecutionContext

/**
 * DAR-BE-078 — the JSON-RPC endpoint only dispatches Darpan's own remote API.
 *
 * <p>The webroot /rpc/json transition is require-authentication="false" and the framework
 * dispatcher (ServiceJsonRpcDispatcher) checks nothing but allow-remote. That made stock Moqui
 * services callable with no session, notably org.moqui.impl.UserServices.update#Password and
 * reset#Password (authenticate="anonymous-all", allow-remote="true"): unlimited password guessing
 * that never counts a failed login, a disabled account re-enabling itself (update#PasswordInternal
 * defaults disabled=N), and a username oracle — exactly what AuthFacadeSupport hardens Darpan's own
 * wrappers against. The product's remote surface is facade.* and admin.* (docs/api-contract);
 * gql.* stays callable because each of its remote services gates itself.
 *
 * <p>Also closes the framework's /rest/login transition, which no Darpan client uses and which
 * returns the realm's raw "no account" vs "password incorrect" text.
 *
 * <p>And /Login/resetPassword (DAR-BE-028): the framework reset#Password it reaches reveals whether an account
 * exists and emails a reset password that never expires; darpan-ui uses Darpan's own reset instead.
 *
 * <p>Runs from the webroot before-request hook (darpan MoquiConf.xml). It reads the same parsed
 * request-parameter map the dispatcher reads, so the method checked is the method dispatched.
 */
class RpcMethodFence {

    static final List<String> ALLOWED_METHOD_PREFIXES = ["facade.", "admin.", "gql."].asImmutable()

    static void enforce(ExecutionContext ec) {
        def request = ec.web?.request
        if (request == null) return
        String reason = rejection(requestPath(request), ec.web.requestParameters)
        if (reason) throw new ArtifactAuthorizationException(reason)
    }

    /** The path inside the webapp, as the container decoded it (no context path, no ;params). */
    static String requestPath(def request) {
        String path = ((request.servletPath ?: "") as String) + ((request.pathInfo ?: "") as String)
        return path ?: (request.requestURI as String)
    }

    /** Null when the request may proceed; otherwise the refusal reason. Pure, for unit tests. */
    static String rejection(String path, Map params) {
        List<String> segments = segmentsOf(path)
        // A transition takes trailing path segments, so match the pair, not the exact path.
        if (hasPair(segments, "rest", "login")) return "The /rest/login endpoint is not available."
        // DAR-BE-028: Darpan has its own reset (facade.AuthFacadeServices.request#PasswordReset), so the framework's
        // weaker one goes. /Login itself stays: the legacy super-admin /apps screens sign in through it.
        if (hasPair(segments, "Login", "resetPassword")) return "The /Login/resetPassword endpoint is not available."
        if (!isJsonRpc(segments)) return null

        // Check the top-level method AND every batch entry, whatever the shape. The dispatcher takes
        // the batch branch only when _requestBodyJsonList is truthy, so an empty-array body falls
        // through to the top-level (e.g. query-string) method; checking one or the other by our own
        // reading of the shape would leave that gap.
        List<Object> methods = [params?.get("method")]
        def batch = params?.get("_requestBodyJsonList")
        if (batch instanceof List) {
            ((List) batch).each { Object call -> methods.add(call instanceof Map ? ((Map) call).get("method") : null) }
        }
        for (Object method in methods) {
            // A missing method is left to the dispatcher, which answers it with a JSON-RPC error.
            if (method == null) continue
            if (!isAllowedMethod(method.toString())) return "JSON-RPC method is not available: ${method}".toString()
        }
        return null
    }

    static boolean isAllowedMethod(String method) {
        return method != null && ALLOWED_METHOD_PREFIXES.any { String prefix -> method.startsWith(prefix) }
    }

    /** True for any path holding the rpc/json transition, including extra trailing segments. */
    static boolean isJsonRpc(List<String> segments) {
        return hasPair(segments, "rpc", "json")
    }

    /** Matches on each segment's name before its first dot: Moqui strips a render-mode extension before it
     *  matches a screen or transition (ScreenUrlInfo), so /Login/resetPassword.json reaches resetPassword. */
    static boolean hasPair(List<String> segments, String first, String second) {
        for (int i = 0; i < segments.size() - 1; i++) {
            if (nameBeforeDot(segments[i]) == first && nameBeforeDot(segments[i + 1]) == second) return true
        }
        return false
    }

    static String nameBeforeDot(String segment) {
        int dot = segment.indexOf('.')
        return dot > 0 ? segment.substring(0, dot) : segment
    }

    static List<String> segmentsOf(String path) {
        return (path ?: "").split("/").findAll { String s -> s }.collect { String s ->
            int semi = s.indexOf(';')
            semi >= 0 ? s.substring(0, semi) : s
        }.findAll { String s -> s }
    }
}
