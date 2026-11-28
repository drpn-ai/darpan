package darpan.reconciliation.notification

import org.junit.jupiter.api.Test

import java.net.http.HttpClient
import java.time.Duration

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * S99-067: the notification package's one way out. A request leaves only over https to the exact host its caller
 * pins, so a stored or transferred URL cannot point a delivery elsewhere. No socket is opened here: every refusal
 * comes before the send.
 */
class NotificationHttpTests {

    @Test
    void aRequestOffItsPinnedHostOrNotHttpsIsRefusedBeforeItIsSent() {
        [
                "http://hooks.slack.com/services/T/B/X",           // not https
                "https://evil.example/services/T/B/X",             // another host
                "https://hooks.slack.com.evil.example/x",          // the pinned host as a prefix
                "https://hooks.slack.com@evil.example/x",          // the pinned host as user info
                "https://HOOKS.SLACK.COM.evil.example/x",
                "not a url at all",
                "",
        ].each { String url ->
            IllegalArgumentException refused = assertThrows(IllegalArgumentException) {
                NotificationHttp.post(url, "hooks.slack.com", "application/json", "{}", 10)
            }
            assertTrue(refused.message.contains("hooks.slack.com"), "${url}: ${refused.message}")
            assertTrue(!refused.message.contains("/services/"), "the refusal must not repeat the URL's secret path: ${refused.message}")
        }
    }

    @Test
    void oneSharedClientThatNeverFollowsARedirect() {
        assertEquals(HttpClient.Redirect.NEVER, NotificationHttp.CLIENT.followRedirects())
        assertEquals(Optional.of(Duration.ofSeconds(10)), NotificationHttp.CLIENT.connectTimeout())
    }
}
