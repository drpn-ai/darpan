package darpan.common

import org.junit.jupiter.api.Test
import org.moqui.impl.context.ContextJavaUtil

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse

/**
 * DAR-OPS-029 OC149 (Aditi 2026-11-28): an ArtifactHit row stores its call's parameters as text. The framework fork
 * (drpn-ai/moqui-framework ContextJavaUtil.hitParameterString) masks every value whose key, in any case, names a password,
 * secret, token, key, code, credential, assertion or recovery value: the key stays (the row still says what was sent),
 * the value is ***. It used to drop only keys containing a lowercase "password", so currentPassword, newPassword, sign-in
 * codes, recovery codes, challenge tokens and API keys were stored in plain text wherever service hits are persisted.
 * Unit pool: no Moqui.
 */
class ArtifactHitParameterMaskingTests {

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
}
