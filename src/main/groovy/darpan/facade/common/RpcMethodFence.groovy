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
        if (segments == ["rest", "login"]) return "The /rest/login endpoint is not available."
        if (!isJsonRpc(segments)) return null

        List<Object> methods = []
        def batch = params?.get("_requestBodyJsonList")
        if (batch instanceof List) {
            ((List) batch).each { Object call -> methods.add(call instanceof Map ? ((Map) call).get("method") : null) }
        } else {
            methods.add(params?.get("method"))
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
        for (int i = 0; i < segments.size() - 1; i++) {
            if (segments[i] == "rpc" && segments[i + 1] == "json") return true
        }
        return false
    }

    static List<String> segmentsOf(String path) {
        return (path ?: "").split("/").findAll { String s -> s }.collect { String s ->
            int semi = s.indexOf(';')
            semi >= 0 ? s.substring(0, semi) : s
        }.findAll { String s -> s }
    }
}
