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
}
