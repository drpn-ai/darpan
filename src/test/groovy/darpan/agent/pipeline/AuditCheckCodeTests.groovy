package darpan.agent.pipeline

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows

/**
 * DAR-BE-287. An agent's new check takes its code from its label; a code is unique per tenant, so a label another audit
 * already uses gets the audit's code added (then -2, -3) instead of a draft that can never be approved. The same state must
 * give the same code, because plan, apply and the after-state find each derive it.
 */
class AuditCheckCodeTests {

    @Test
    void "a free label keeps its plain code, and a revert keeps its own id"() {
        assertEquals("payments-match", AuditChangeLevers.checkCodeOf([label: "Payments match"], [audit: "store-audit", otherCheckCodes: ["totals-match"]]))
        assertEquals("payments-match", AuditChangeLevers.checkCodeOf([label: "Payments match"]))
        assertEquals("old-id", AuditChangeLevers.checkCodeOf([id: "old-id", label: "Payments match"], [audit: "a", otherCheckCodes: ["old-id"]]))
    }

    @Test
    void "a label another audit uses gets the audit's code, then a number, and the same state gives the same code"() {
        Map state = [audit: "store-audit", otherCheckCodes: ["payments-match"]]
        assertEquals("payments-match-store-audit", AuditChangeLevers.checkCodeOf([label: "Payments match"], state))
        assertEquals("payments-match-store-audit", AuditChangeLevers.checkCodeOf([label: "Payments match"], state))
        assertEquals("payments-match-store-audit-2", AuditChangeLevers.checkCodeOf([label: "Payments match"],
                [audit: "store-audit", otherCheckCodes: ["payments-match", "payments-match-store-audit"]]))
    }

    @Test
    void "a label with no letters or digits is refused"() {
        assertThrows(IllegalArgumentException) { AuditChangeLevers.checkCodeOf([label: " -- "], [audit: "a", otherCheckCodes: []]) }
    }
}
