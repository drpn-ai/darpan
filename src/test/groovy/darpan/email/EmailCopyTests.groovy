package darpan.email

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

/** DAR-BE-303: the pure helpers behind the email copy blocks. */
class EmailCopyTests {
    @Test
    void applyFillsAllowedVariables() {
        assertEquals("Hi Riya, code 42.", EmailCopy.apply('Hi ${firstName}, code ${n}.', [firstName: "Riya", n: 42], ["firstName", "n"] as Set))
        assertEquals("Riya at IST", EmailCopy.apply('${user.name} at ${user.zone}', [user: [name: "Riya", zone: "IST"]], ["user.name", "user.zone"] as Set))
        assertEquals("a  b", EmailCopy.apply('a ${gone} b', [:], ["gone"] as Set), "a missing value is empty")
        assertEquals('x$1\\y', EmailCopy.apply('${v}', [v: 'x$1\\y'], ["v"] as Set), "a value is never read as a regex replacement")
    }

    @Test
    void applyRefusesAnUnknownVariable() {
        def e = assertThrows(IllegalArgumentException) { EmailCopy.apply('Hi ${firstName} ${nope}', [firstName: "R"], ["firstName"] as Set) }
        assertTrue(e.message.contains("nope"), e.message)
    }

    @Test
    void variablesFindsSimpleAndDottedForms() {
        assertEquals(["a", "b.c"] as Set, EmailCopy.variables('x ${a} y ${b.c} z ${a}'))
        assertEquals([] as Set, EmailCopy.variables("plain words"))
        assertEquals([] as Set, EmailCopy.variables(null))
    }

    @Test
    void guidesParseOneLabelAndPagePerLine() {
        assertEquals([["Two-factor sign-in", "guides/two-factor"], ["Lost it", "guides/two-factor#lost"]],
                EmailCopy.parseGuides("Two-factor sign-in|guides/two-factor\n\n Lost it | guides/two-factor#lost \n"))
        assertThrows(IllegalArgumentException) { EmailCopy.parseGuides("no bar here") }
    }

    /** DAR-BE-303 final review I2: a host or address of ANY ending is refused outside a slot; ordinary sentences pass. */
    @Test
    void anyHostOrAddressIsRefusedAndOrdinarySentencesPass() {
        ["Go to bit.ly/x", "Not you? evil.ru", "Open darpan-login.help now", "Sign in at login.darpan.support", "mail x@evil.ru",
         "See https://x.example", "www.x", 'Open ${a}.ru now', "Set up Darpan.Then log in"].each { String bad ->
            assertNotNull(EmailCopy.problem("T", "note", bad), bad)
        }
        ["Use e.g. this one", "Use i.e. that one", "Darpan.", "It takes 1.5 hours", "The U.S. team", "At 9 a.m. sharp", "Sign in.",
         "Acme Co. Ltd", "Wait... then sign in", 'Open ${appUrl} in your browser.', "Month-end, again."].each { String ok ->
            assertNull(EmailCopy.problem("T", "note", ok), ok)
        }
    }

    @Test
    void aGuideLabelIsPlainWordsOnly() {
        ["Forgot password", "Who requires two-factor", "Lockouts and session length"].each { assertNull(EmailCopy.guideLabelProblem(it), it) }
        ["bit.ly/x", "evil.ru", "Open login.darpan.support", 'Hi ${firstName}', "a@b.co", "<b>x</b>", " "].each { String bad ->
            assertNotNull(EmailCopy.guideLabelProblem(bad), bad)
        }
    }

    @Test
    void aSubjectShapeIsPlainWithSlotsAndNoHost() {
        assertNull(EmailCopy.subjectShapeProblem('Set up two-factor sign-in for Darpan by ${requiredDate}'))
        assertNull(EmailCopy.subjectShapeProblem('${tenantName} ${auditName}, ${span}: ${open} open findings'))
        ['Say "hi"', 'Hi ${x.y}', "Hi `x`", "see bit.ly/x", "Two\nlines", 'Cost $5', 'x@y.ru'].each { String bad ->
            assertNotNull(EmailCopy.subjectShapeProblem(bad), bad)
        }
        // a subject block is checked by problem() the same way
        assertNotNull(EmailCopy.problem("DARPAN_2FA_REMINDER", "subject.before", 'Say "hi" ${requiredDate}'))
        assertNull(EmailCopy.problem("DARPAN_2FA_REMINDER", "subject.before", 'Set up by ${requiredDate}'))
    }
}
