package darpan.common

import org.junit.jupiter.api.Test
import org.moqui.impl.context.ContextJavaUtil

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-OPS-029 OC149 (Aditi 2026-11-28): an ArtifactHit row stores its call's parameters as text. The framework fork
 * (drpn-ai/moqui-framework ContextJavaUtil.hitParameterString) masks every value whose key, in any case, names a password,
 * secret, token, key, code, credential, assertion or recovery value: the key stays (the row still says what was sent),
 * the value is ***. It used to drop only keys containing a lowercase "password", so currentPassword, newPassword, sign-in
 * codes, recovery codes, challenge tokens and API keys were stored in plain text wherever service hits are persisted.
 * Unit pool: no Moqui.
 */
class ArtifactHitParameterMaskingTests {

    // DAR-OPS-029 OC217 (GAP-16): the row's requestUrl, referrerUrl and errorMessage are masked with the same key list, which now
    // also names state, session, cookie, jwt, otp and auth values (an OAuth callback's code and state, a session in a link).
    @Test
    void aUrlsSecretQueryValuesAndItsPasswordAreMasked() {
        assertEquals("https://app/apps/darpan/slackOauthCallback?code=***&state=***",
                ContextJavaUtil.maskUrl("https://app/apps/darpan/slackOauthCallback?code=4%2F0AX&state=abc"))
        assertEquals("https://app/x?page=2&apiKey=***#top", ContextJavaUtil.maskUrl("https://app/x?page=2&apiKey=k1#top"))
        assertEquals("https://svc:***@host/p?jsessionid=***", ContextJavaUtil.maskUrl("https://svc:pw@host/p?jsessionid=s1"))
        assertEquals("https://app/plain?page=2", ContextJavaUtil.maskUrl("https://app/plain?page=2"))
        assertEquals(null, ContextJavaUtil.maskUrl(null))
    }

    @Test
    void anErrorMessagesSecretsAreMasked() {
        assertEquals("Login failed for password=***; token: ***;", ContextJavaUtil.maskErrorText("Login failed for password=hunter2; token: t1;"))
        assertEquals("callback https://app/cb?code=*** rejected", ContextJavaUtil.maskErrorText("callback https://app/cb?code=c1 rejected"))
        assertEquals("Order M1 not found;", ContextJavaUtil.maskErrorText("Order M1 not found;"))
    }

    @Test
    void stateSessionCookieJwtOtpAndAuthValuesAreMaskedInParameters() {
        String ps = ContextJavaUtil.hitParameterString([state: "s1", sessionId: "x1", jwt: "j1", otp: "o1", authHeader: "a1",
                                                        cookie: "c1", page: "2"])
        ["s1", "x1", "j1", "o1", "a1", "c1"].each { assertFalse(ps.contains("=${it}".toString()), "${it} stored: ${ps}") }
        assertTrue(ps.contains("page=2"), ps)
    }

    @Test
    void secretValuesAreMaskedWhateverTheKeysCase() {
        Map<String, Object> params = [
                username        : "ada",
                currentPassword : "Hunter2!x",
                newPasswordVerify: "N3w-password!",
                password        : "p",
                PASSWD          : "q",
                code            : "123456",
                recoveryCode    : "abcd-efgh-jkmn-pq01",
                challengeToken  : "tok-1",
                passkeyAssertion: "{\"id\":\"cred\"}",
                apiKey          : "k-1",
                clientSecret    : "s-1",
                credentials     : "c-1",
                statusId        : "OK",
        ]
        assertEquals("username=ada, currentPassword=***, newPasswordVerify=***, password=***, PASSWD=***, code=***, " +
                "recoveryCode=***, challengeToken=***, passkeyAssertion=***, apiKey=***, clientSecret=***, credentials=***, statusId=OK",
                ContextJavaUtil.hitParameterString(params))
        String text = ContextJavaUtil.hitParameterString(params)
        ["Hunter2", "N3w-password", "123456", "abcd-efgh", "tok-1", "k-1", "s-1", "c-1"].each { String secret ->
            assertFalse(text.contains(secret), "${secret} must not be stored")
        }
    }

    @Test
    void plainValuesKeepTheirCutsAndSkips() {
        String long90 = "x" * 90
        assertEquals("note=${"x" * 80}, n=7".toString(),
                ContextJavaUtil.hitParameterString([note: long90, n: 7, empty: "", nothing: null, list: [1, 2], map: [a: 1]]))
        assertEquals(1000, ContextJavaUtil.hitParameterString((1..40).collectEntries { ["field${it}".toString(), "y" * 80] }).length())
        assertEquals("", ContextJavaUtil.hitParameterString(null))
    }

    /** DAR-OPS-029 (S29 F6): the entity-field rule EntityValueBase.toString uses: secret-bearing names, not every id or code. */
    @Test
    void sensitiveFieldNamesForEntityPrinting() {
        ["webhookUrl", "googleChatWebhookUrl", "headersJson", "currentPassword", "clientSecret", "accessToken", "apiKey",
         "privateKeyPem", "recoveryCode", "credentialsJson"].each { assertTrue(ContextJavaUtil.isSensitiveFieldName(it), it) }
        ["statusId", "chatSpaceId", "spaceName", "currencyUomId", "recordKey", "conclusionCode", "baseUrl"].each {
            assertFalse(ContextJavaUtil.isSensitiveFieldName(it), it)
        }
    }
}
