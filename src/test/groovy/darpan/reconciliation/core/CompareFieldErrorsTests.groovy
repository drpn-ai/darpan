package darpan.reconciliation.core

import darpan.facade.common.FacadeSupport
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

/** DAR-BE-031: a run whose ID field is not in a tenant's records says so in words, not as a Spark struct error. */
class CompareFieldErrorsTests {
    // The Mephisto returns run, 2026-09-02, verbatim.
    static final String MEPHISTO = "[FIELD_NOT_FOUND] No such struct field `shopifyReturnId` in `currencyUomId`, `entryDate`, `externalId`, " +
            "`items`, `orderExternalId`, `returnChannelEnumId`, `returnId`, `returnTotal`, `statusId`. SQLSTATE: 42704"

    @Test
    void aMissingNestedFieldNamesTheFieldTheSideAndWhatIsThere() {
        String m = CompareFieldErrors.plainMessage(new RuntimeException(MEPHISTO), "HotWax")
        assertTrue(m.startsWith("The ID field 'shopifyReturnId' is not in the HotWax records of this run."), m)
        assertTrue(m.contains("Pick a different ID field for this run."), m)
        assertTrue(m.contains("They have: currencyUomId, entryDate, externalId"), m)
    }

    @Test
    void aMissingFlatColumnToo() {
        String spark = "[UNRESOLVED_COLUMN.WITH_SUGGESTION] A column or function parameter with name `order_ref` cannot be resolved. " +
                "Did you mean one of the following? [`order_id`, `name`]."
        String m = CompareFieldErrors.plainMessage(new RuntimeException("wrapped", new RuntimeException(spark)), "Shopify")
        assertTrue(m.startsWith("The ID field 'order_ref' is not in the Shopify records of this run."), m)
        assertTrue(m.contains("They have: order_id, name"), m)
    }

    @Test
    void aScheduledRunRecordsThePlainMessageNotTheServiceWrapper() {
        Throwable wrapped = new RuntimeException("Java method for service [reconciliation.ReconciliationCoreServices.prepare#RuleSetCompareScope] threw an exception",
                new IllegalArgumentException("The ID field 'id' is not in the HotWax records of this run."))
        assertEquals("The ID field 'id' is not in the HotWax records of this run.",
                darpan.reconciliation.automation.AutomationRuntimeSupport.sanitizeErrorMessage(wrapped))
    }

    @Test
    void anyOtherErrorIsLeftAlone() {
        assertNull(CompareFieldErrors.plainMessage(new RuntimeException("Disk full"), "HotWax"))
    }

    @Test
    void moquisJavaMethodPrefixIsNotShownToPeople() {
        assertEquals("Pick a different ID field.", FacadeSupport.withoutServicePrefix(
                "Java method for service [reconciliation.ReconciliationCoreServices.prepare#RuleSetCompareScope] threw an exception; Pick a different ID field."))
        assertEquals("Pick one.", FacadeSupport.withoutServicePrefix("Java method for service [a.B.c#D] threw an exception, Pick one."))
        assertEquals("", FacadeSupport.withoutServicePrefix("Java method for service [a.B.c#D] threw an exception"))
        assertEquals("Plain.", FacadeSupport.withoutServicePrefix("Plain."))
    }
}
