package darpan.facade.common

import darpan.facade.auth.CliAuthSupport
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ExecutionContext
import org.moqui.impl.screen.ScreenUrlInfo
import org.moqui.util.StringUtilities

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
 * <p>DAR-CLI-003: a CLI session (CliAuthSupport.isCliSession) is held to CliMethodAllowlist on top: only the paths in
 * CLI_PATHS, and on /rpc/json only ALLOWED methods, every batch entry included. That check runs on every path, not
 * only /rpc/json, so a CLI key cannot reach /rest/e1 or a screen instead. Requests that are not CLI sessions are
 * fenced exactly as before.
 *
 * <p>Runs from the webroot before-request hook (darpan MoquiConf.xml) on every webroot request. It reads the same
 * parsed request-parameter map the dispatcher reads, so the method checked is the method dispatched.
 *
 * <p>DAR-BE-189: and it judges the path Moqui routes on, beside the container's. The container hands the servlet
 * servletPath + pathInfo with dot segments resolved; Moqui routes on its own reading of the raw request URI: split and
 * URL-decoded (WebFacadeImpl.getPathInfoList), then cleaned by ScreenUrlInfo.cleanupPathNameList before any screen or
 * transition is matched (ScreenRenderImpl.render -> parseSubScreenPath). That cleaning drops empty and . segments,
 * resolves .. and drops every segment starting with ~ (an inline parameter), which the container keeps. So
 * /rpc/~a/json is /rpc/~a/json to the container but rpc/json to Moqui, and a transition takes any trailing segments.
 * Every fence decision is made on both views, and a path on which they disagree about a decision is refused.
 */
class RpcMethodFence {

    static final List<String> ALLOWED_METHOD_PREFIXES = ["facade.", "admin.", "gql."].asImmutable()

    static final String CLI_PATH_REFUSAL = "This address is not available to the Darpan CLI."
    static final String AMBIGUOUS_PATH_REFUSAL = "This address reads differently to the server and to the router, so it is not available."

    static void enforce(ExecutionContext ec) {
        def request = ec.web?.request
        if (request == null) return
        List<String> raw = rawSegments(request)
        List<String> routed = routedSegments(request)
        List<String> normalised = segmentsOf(requestPath(request))
        Map params = ec.web.requestParameters
        String reason = rejection(routed, normalised, params)
        if (reason) throw new ArtifactAuthorizationException(reason)
        if (CliAuthSupport.isCliSession(ec)) {
            String cliReason = cliRejection(raw, routed, normalised, isJsonRpc(routed) ? requestedMethods(params) : [])
            if (cliReason) throw new ArtifactAuthorizationException(cliReason)
        }
    }

    /** cliRejection for a plain path every view agrees on. Pure, for unit tests. */
    static String cliRejection(String path, List<String> methods) {
        List<String> segments = segmentsOf(path)
        return cliRejection(segments, segments, segments, methods)
    }

    /** cliRejection where the raw path is already the routed one (nothing for Moqui to clean). Pure, for unit tests. */
    static String cliRejection(List<String> routed, List<String> normalised, List<String> methods) {
        return cliRejection(routed, routed, normalised, methods)
    }

    /**
     * For a CLI session: null when the request may proceed, otherwise the refusal. Pure, for unit tests. Stricter than
     * the general fence: the raw path must be plain (no . or .. or ~ segment, so Moqui's cleaning changes nothing) and
     * Moqui's routed path must equal the container's, so the CLI path matched is the one routed to. A null method (a
     * call with none) is left to the dispatcher, which answers it with a JSON-RPC error.
     */
    static String cliRejection(List<String> raw, List<String> routed, List<String> normalised, List<String> methods) {
        if (raw == null || routed == null || raw != routed || routed != normalised) return CLI_PATH_REFUSAL
        if (raw.any { String segment -> isDotSegment(segment) || segment.startsWith("~") }) return CLI_PATH_REFUSAL
        if (!isCliPath(routed)) return CLI_PATH_REFUSAL
        for (String method in (methods ?: [])) {
            if (method == null) continue
            if (!CliMethodAllowlist.ALLOWED.contains(method)) return "${method} is not available to the Darpan CLI; use the app.".toString()
        }
        return null
    }

    /** Exactly one of CliMethodAllowlist.CLI_PATHS, segment for segment: no prefix, suffix or render-mode extension. */
    static boolean isCliPath(List<String> segments) {
        return CliMethodAllowlist.CLI_PATHS.any { String cliPath -> segmentsOf(cliPath) == segments }
    }

    /**
     * The path as Moqui routes it: the servlet path's segments, then the request URI as WebFacadeImpl.getPathInfoList
     * splits and URL-decodes it, cleaned by the framework's own ScreenUrlInfo.cleanupPathNameList (drops empty, . and
     * ~ segments; .. pops the one before), then each name cut at ;params like segmentsOf.
     */
    static List<String> routedSegments(def request) {
        ArrayList<String> cleaned = ScreenUrlInfo.cleanupPathNameList(new ArrayList<String>(moquiPathList(request)), null)
        return segmentsOf((request.servletPath ?: "") as String) + cleanSegments(cleaned)
    }

    /** The same split with nothing cleaned (each name still cut at ;params): what the CLI's plain-path rule reads. */
    static List<String> rawSegments(def request) {
        return segmentsOf((request.servletPath ?: "") as String) + cleanSegments(moquiPathList(request))
    }

    /** WebFacadeImpl.getPathInfoList(request), computed here because WebFacadeImpl's class init loads a signing key:
     *  the request URI past the context and servlet path, split on / and each segment URL-decoded, empties dropped. */
    static List<String> moquiPathList(def request) {
        String servletPath = (request.servletPath ?: "") as String
        String contextPath = (request.contextPath ?: "") as String
        int skip = (servletPath.isEmpty() ? 0 : servletPath.split("/").length - 1) +
                (contextPath.isEmpty() ? 0 : contextPath.split("/").length - 1)
        return StringUtilities.pathStringToList(request.requestURI as String, skip) as List<String>
    }

    /** The path inside the webapp, as the container decoded it (no context path, no ;params). */
    static String requestPath(def request) {
        String path = ((request.servletPath ?: "") as String) + ((request.pathInfo ?: "") as String)
        return path ?: (request.requestURI as String)
    }

    /** rejection for a path both views agree on. Pure, for unit tests. */
    static String rejection(String path, Map params) {
        List<String> segments = segmentsOf(path)
        return rejection(segments, segments, params)
    }

    /**
     * Null when the request may proceed; otherwise the refusal reason. Pure, for unit tests. Every check runs on the
     * path Moqui routes on and on the container's normalised path; either refusing refuses. And when the two views
     * differ about any fence decision (is it JSON-RPC, /rest/login, /Login/resetPassword) the request is refused even
     * if neither check alone would: the disagreement itself is the refusal, whichever view is right.
     */
    static String rejection(List<String> routed, List<String> normalised, Map params) {
        String reason = rejectionOf(routed, params)
        if (reason == null && normalised != routed) reason = rejectionOf(normalised, params)
        if (reason) return reason
        if (normalised != routed && fenceVerdicts(routed) != fenceVerdicts(normalised)) return AMBIGUOUS_PATH_REFUSAL
        return null
    }

    /** What each fence decision turns on, for one view of the path. */
    static List<Boolean> fenceVerdicts(List<String> segments) {
        return [isJsonRpc(segments), hasPair(segments, "rest", "login"), hasPair(segments, "Login", "resetPassword")]
    }

    static boolean isDotSegment(String segment) {
        return segment == "." || segment == ".."
    }

    private static String rejectionOf(List<String> segments, Map params) {
        // A transition takes trailing path segments, so match the pair, not the exact path.
        if (hasPair(segments, "rest", "login")) return "The /rest/login endpoint is not available."
        // DAR-BE-028: Darpan has its own reset (facade.AuthFacadeServices.request#PasswordReset), so the framework's
        // weaker one goes. /Login itself stays: the legacy super-admin /apps screens sign in through it.
        if (hasPair(segments, "Login", "resetPassword")) return "The /Login/resetPassword endpoint is not available."
        if (!isJsonRpc(segments)) return null

        for (String method in requestedMethods(params)) {
            // A missing method is left to the dispatcher, which answers it with a JSON-RPC error.
            if (method == null) continue
            if (!isAllowedMethod(method)) return "JSON-RPC method is not available: ${method}".toString()
        }
        return null
    }

    /**
     * The top-level method AND every batch entry's, whatever the shape (null where a call has none). The dispatcher
     * takes the batch branch only when _requestBodyJsonList is truthy, so an empty-array body falls through to the
     * top-level (e.g. query-string) method; checking one or the other by our own reading of the shape would leave
     * that gap.
     */
    static List<String> requestedMethods(Map params) {
        List<Object> methods = [params?.get("method")]
        def batch = params?.get("_requestBodyJsonList")
        if (batch instanceof List) {
            ((List) batch).each { Object call -> methods.add(call instanceof Map ? ((Map) call).get("method") : null) }
        }
        return methods.collect { Object method -> method?.toString() }
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
        return cleanSegments((path ?: "").split("/") as List<String>)
    }

    /** Each segment's name before any ;params, empty segments dropped. */
    static List<String> cleanSegments(List<String> segments) {
        return (segments ?: []).findAll { String s -> s }.collect { String s ->
            int semi = s.indexOf(';')
            semi >= 0 ? s.substring(0, semi) : s
        }.findAll { String s -> s }
    }
}
