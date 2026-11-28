package darpan.reconciliation.notification

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * S99-067. The notification package's one way out (the Slack Web API, Slack's OAuth exchange). Each caller names the
 * one host its request may go to; the request leaves only over https to exactly that host, so a URL built from stored
 * or transferred data can never point a request elsewhere. A refusal never repeats the URL: a webhook's path is its
 * secret.
 */
class NotificationHttp {
    /** One client for the package: thread-safe, 10 s to connect, and a 3xx is an answer, never a new host to visit. */
    static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()

    static HttpResponse<String> post(String url, String pinnedHost, String contentType, String body, int timeoutSeconds,
                                     Map<String, String> headers = [:]) {
        HttpRequest.Builder request = HttpRequest.newBuilder(pinnedHttps(url, pinnedHost))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", contentType)
        (headers ?: [:]).each { String name, String value -> request.header(name, value) }
        return CLIENT.send(request.POST(HttpRequest.BodyPublishers.ofString(body ?: "")).build(), HttpResponse.BodyHandlers.ofString())
    }

    private static URI pinnedHttps(String url, String pinnedHost) {
        URI uri = null
        try {
            uri = url?.trim() ? new URI(url.trim()) : null
        } catch (URISyntaxException ignored) {
            // refused below
        }
        if (uri == null || uri.scheme != "https" || uri.rawUserInfo != null || !pinnedHost || !pinnedHost.equalsIgnoreCase(uri.host)) {
            throw new IllegalArgumentException("Refused: this request may go only over https to ${pinnedHost}.".toString())
        }
        return uri
    }
}
