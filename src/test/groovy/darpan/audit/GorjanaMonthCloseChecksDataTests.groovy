package darpan.audit

import darpan.facade.reconciliation.RunConclusionStep
import reconciliation.rule.FieldComparisonRuleLogicGenerator
import darpan.reconciliation.conclusion.ConclusionTreeEngine
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.xml.XmlParser
import org.junit.jupiter.api.Test

import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-093 — gorjana month close comparison 2 (R1, R2, R3) as configuration, at parse level (no Moqui boot). The
 * loader rolls a file back on a referential failure and still reports a count, so the references are proven here.
 */
class GorjanaMonthCloseChecksDataTests {

    private static final String FILE = "GorjanaMonthCloseChecksData.xml"
    /** DAR-BE-094: comparison 1, the returns checks (R9 so far). */
    private static final String RETURNS = "GorjanaMonthCloseReturnsChecksData.xml"
    /** DAR-BE-094 R14 / DAR-BE-095 R13: exchange credit memos against their replacement orders. */
    private static final String REPLACEMENT = "GorjanaMonthCloseReplacementChecksData.xml"
    /** DAR-BE-097 R11: warranty arrivals against NetSuite item receipts. */
    private static final String RECEIPT = "GorjanaMonthCloseReceiptChecksData.xml"
    /** DAR-BE-095 R12: warranty claims against their credit memos. */
    private static final String CLAIM = "GorjanaMonthCloseWarrantyClaimChecksData.xml"
    /** DAR-BE-113, R19: a paid Shopify order has its payment in HotWax. */
    private static final String PAYMENTS = "GorjanaMonthClosePaymentsChecksData.xml"

    private static Path dataDir() {
        return ReconciliationSmokeTestSupport.resolveBackendRoot().resolve("runtime/component/darpan/data")
    }

    private static Node doc(String file) {
        return new XmlParser(false, false).parse(dataDir().resolve(file).toFile())
    }

    private static List<Node> rows(String file, String elementName) {
        return doc(file).children().findAll { it instanceof Node && it.name() == elementName } as List<Node>
    }

    private static Set<String> attr(List<Node> nodes, String name) {
        return nodes.collect { it.attribute(name) as String } as Set<String>
    }

    @Test
    void theFileIsTenantConfigurationNotSeed() {
        [FILE, RETURNS, REPLACEMENT, RECEIPT, CLAIM, PAYMENTS].each { assertEquals("darpan-tenant-config", doc(it).attribute("type"), it) }
    }

    @Test
    void everyReferenceResolves() {
        [FILE, RETURNS, REPLACEMENT, RECEIPT, CLAIM, PAYMENTS].each { everyReferenceResolvesIn(it) }
        assertEquals(["GORJANA_R1", "GORJANA_R2", "GORJANA_R3"] as Set, attr(rows(FILE, "darpan.audit.AuditStatementCheck"), "statementId"),
                "comparison 2 covers R1-R3")
        assertEquals(["GORJANA_R4", "GORJANA_R7", "GORJANA_R8", "GORJANA_R9", "GORJANA_R16"] as Set, attr(rows(RETURNS, "darpan.audit.AuditStatementCheck"), "statementId"),
                "comparison 1 covers R7, R8, R9, R16 and R4's nothing-left-open so far")
        assertEquals(["GORJANA_R13", "GORJANA_R14"] as Set, attr(rows(REPLACEMENT, "darpan.audit.AuditStatementCheck"), "statementId"))
        assertEquals(["GORJANA_R11", "GORJANA_R15"] as Set, attr(rows(RECEIPT, "darpan.audit.AuditStatementCheck"), "statementId"))
        assertEquals(["GORJANA_R12"] as Set, attr(rows(CLAIM, "darpan.audit.AuditStatementCheck"), "statementId"))
        assertEquals(["GORJANA_R19"] as Set, attr(rows(PAYMENTS, "darpan.audit.AuditStatementCheck"), "statementId"))
    }

    @Test
    void anR7RowIsAnHgFlaggedMemoThatDoesNotPostTo42065() {
        String scope = "GORJANA_HG_ACCOUNT_SCOPE"
        Map memo = [tranId: "CM7657884", returnId: "HG-628096", isHg: "T", onHgAccount: "F", amount: "64.80"]
        assertEquals("CONC_HG_NOT_ON_42065", conclude(RETURNS, scope, memo))
        assertEquals("UNEXPLAINED", conclude(RETURNS, scope, memo + [onHgAccount: "T"]), "on 42065")
        assertEquals("UNEXPLAINED", conclude(RETURNS, scope, memo + [isHg: "F"]), "not a Happiness Guarantee credit")
        // CM7665367 (2026-10-21): a $0 memo posts no accounting line at all, so it is on no account; nothing to book.
        assertEquals("UNEXPLAINED", conclude(RETURNS, scope, memo + [amount: "0"]), "a \$0 memo credits nothing")
    }

    @Test
    void anR8RowIsACompletedOrReceivedReturnWithNoRma() {
        String scope = "GORJANA_RETURN_IN_NETSUITE_SCOPE"
        Map done = [returnId: "M256735", statusId: "RETURN_COMPLETED", orderExternalId: "7186636636291"]
        assertEquals("CONC_RETURN_NOT_IN_NETSUITE", conclude(RETURNS, scope, done, "MISSING_FROM_FILE_2"))
        assertEquals("CONC_RETURN_NOT_IN_NETSUITE", conclude(RETURNS, scope, done + [statusId: "RETURN_RECEIVED"], "MISSING_FROM_FILE_2"))
        assertEquals("CONC_RETURN_NO_ORDER_NOT_SYNCED", conclude(RETURNS, scope, [returnId: "602501", statusId: "RETURN_COMPLETED"], "MISSING_FROM_FILE_2"),
                "the OMS's own numeric return with no order: the sync skips it")
        assertEquals("UNEXPLAINED", conclude(RETURNS, scope, done + [statusId: "RETURN_REQUESTED"], "MISSING_FROM_FILE_2"),
                "a requested return is not judged")
        // Aditi 2026-10-23: "exclude returns with fromPartyId: DISCONTINUED from R8"; still read, never a finding.
        assertEquals("UNEXPLAINED", conclude(RETURNS, scope, done + [fromPartyId: "DISCONTINUED"], "MISSING_FROM_FILE_2"))
        assertEquals("UNEXPLAINED", conclude(RETURNS, scope, [returnId: "602501", statusId: "RETURN_COMPLETED", fromPartyId: "DISCONTINUED"],
                "MISSING_FROM_FILE_2"), "nor the no-order one")
        assertEquals("CONC_RETURN_NOT_IN_NETSUITE", conclude(RETURNS, scope, done + [fromPartyId: "10456"], "MISSING_FROM_FILE_2"))
    }

    @Test
    void anOpenWarrantyReplacementInvoiceIsAnR12Row() {
        String scope = "GORJANA_WTY_INVOICE_SCOPE"
        // amountDue is NS_INVOICE_REFERENCE's foreignamountunpaid.
        Map invoice = [tranId: "INV8408424", otherRef: "WTY-#GOR196407286-4", status: "Invoice : Open", amountDue: "65.00"]
        assertEquals("CONC_WTY_INVOICE_NOT_PAID", conclude(CLAIM, scope, invoice))
        assertEquals("UNEXPLAINED", conclude(CLAIM, scope, invoice + [amountDue: "5.00"]),
                "Aditi 2026-10-23 (F-100511): an open invoice with up to \$5 due is not unpaid, as R4's memos")
        assertEquals("UNEXPLAINED", conclude(CLAIM, scope, invoice + [status: "Invoice : Paid In Full"]))
        assertTrue(rows(CLAIM, "darpan.rule.RuleSetCompareSourceFilter").every { it.attribute("compareScopeId") != scope },
                "the WTY- narrowing is SuiteQL's (NS_INVOICE_REFERENCE STARTS_WITH), never a filter over every invoice")
    }

    @Test
    void anOpenCreditMemoIsAnR4RowAndAnAppliedOneIsNot() {
        String scope = "GORJANA_CREDIT_OPEN_SCOPE"
        // unapplied is NS_CREDIT_MEMO_STATUS's foreignpaymentamountunused.
        Map memo = [tranId: "CM7657927", returnId: "M247238", status: "Credit Memo : Open", unapplied: "6.19"]
        assertEquals("CONC_MEMO_LEFT_OPEN", conclude(RETURNS, scope, memo, "FINDING"))
        assertEquals("UNEXPLAINED", conclude(RETURNS, scope, memo + [status: "Credit Memo : Fully Applied"], "FINDING"))
        assertEquals("CONC_MEMO_LEFT_OPEN", conclude(RETURNS, scope, memo - [returnId: "M247238"], "FINDING"),
                "any credit memo, with or without a return")
    }

    @Test
    void anOpenCreditMemoWithFiveDollarsOrLessUnappliedIsNotAnR4Row() {
        String scope = "GORJANA_CREDIT_OPEN_SCOPE"
        Map memo = [tranId: "CM7665207", status: "Credit Memo : Open"]
        assertEquals("UNEXPLAINED", conclude(RETURNS, scope, memo + [unapplied: "1.61"], "FINDING"),
                "Aditi 2026-10-23: an open memo with up to \$5 unapplied is ignored")
        assertEquals("UNEXPLAINED", conclude(RETURNS, scope, memo + [unapplied: "5.00"], "FINDING"),
                "exactly \$5 is still up to \$5")
    }

    @Test
    void r12IsAClaimWithNoMemoOrAMemoNotAtTheClaimedAmount() {
        Map<String, Node> links = rows(CLAIM, "darpan.audit.AuditStatementCheck").collectEntries { [(it.attribute("checkId")): it] }
        assertEquals("MISSING_IN_FILE_2", links.GORJANA_CHK_WARRANTY_NO_MEMO.attribute("diffTypes"))
        assertEquals(["FIELD_MISMATCH", "GORJANA_WC_MEMO_AT_CLAIM"],
                ["diffTypes", "ruleIds"].collect { links.GORJANA_CHK_WARRANTY_MEMO_AMOUNT.attribute(it) })
        Map<String, Node> checks = rows(CLAIM, "darpan.audit.AuditCheck").collectEntries { [(it.attribute("checkId")): it] }
        assertEquals("USD", checks.GORJANA_CHK_WARRANTY_MEMO_AMOUNT.attribute("amountCurrencyUomId"), "the amount check's values are money")
        assertEquals(null, checks.GORJANA_CHK_WARRANTY_NO_MEMO.attribute("amountCurrencyUomId"))
        rows(CLAIM, "darpan.rule.RuleSetCompareSource").findAll { it.attribute("compareScopeId") == "GORJANA_WARRANTY_CLAIM_SCOPE" }.each {
            assertEquals("returnId", it.attribute("primaryIdExpression"), "both sides keyed by the HC return id")
        }
    }

    @Test
    void anR11RowIsAWarrantyPieceTheOmsReceivedThatNetSuiteNeverDid() {
        String scope = "GORJANA_WARRANTY_RECEIPT_SCOPE"
        Map arrived = [returnId: "M247365", returnChannelEnumId: "AFTSHP_OL_WTY",
                       items: [[returnItemSeqId: "00001", receivedQuantity: 0], [returnItemSeqId: "00002", receivedQuantity: 1]]]
        assertEquals("CONC_ARRIVED_NOT_BOOKED", conclude(RECEIPT, scope, arrived, "MISSING_FROM_FILE_2"), "one item received is enough")
        assertEquals("UNEXPLAINED", conclude(RECEIPT, scope, arrived + [items: [[receivedQuantity: 0]]], "MISSING_FROM_FILE_2"),
                "nothing has arrived yet: not R11's")
        assertEquals("UNEXPLAINED", conclude(RECEIPT, scope, arrived + [returnChannelEnumId: "POS_RTN_CHANNEL"], "MISSING_FROM_FILE_2"),
                "a store return is not a warranty piece")
        assertEquals("UNEXPLAINED", conclude(RECEIPT, scope, arrived - [items: arrived.items], "MISSING_FROM_FILE_2"),
                "no items read (projected away) must never read as received")
    }

    /**
     * A kit, as the OMS holds it (probed live 2026-10-22, run-oms-kit-return.sh: 9 of 600 returns since 13 Oct): ONE
     * return line for the kit product (MARKETING_PKG_PICK) carrying receivedQuantity; its pieces appear only in the
     * shipment receipts, which the extract does not read. So a received warranty kit is R11's like any piece, and a
     * kit whose NetSuite receipt failed (i441's "cannot create an inventory detail") has no ItemRcpt and is a finding.
     */
    @Test
    void aReceivedWarrantyKitIsOneLineAndR11TakesIt() {
        String scope = "GORJANA_WARRANTY_RECEIPT_SCOPE"
        Map kit = [returnId: "M263532", returnChannelEnumId: "AFTSHP_OL_WTY",
                   items: [[returnItemSeqId: "01", productId: "M100870", returnItemTypeId: "RET_FPROD_ITEM", receivedQuantity: 1],
                           [returnItemSeqId: "02", productId: "HG_CREDIT", returnItemTypeId: "RET_NPROD_ITEM", receivedQuantity: null]]]
        assertEquals("CONC_ARRIVED_NOT_BOOKED", conclude(RECEIPT, scope, kit, "MISSING_FROM_FILE_2"),
                "the kit line's receipt is the arrival; the credit line never has one")
        assertEquals("UNEXPLAINED", conclude(RECEIPT, scope, kit + [returnChannelEnumId: "POS_RTN_CHANNEL"], "MISSING_FROM_FILE_2"),
                "i441's store kit returns are received at the store: R8's, not a warranty arrival")
    }

    @Test
    void eachReplacementStatementTakesOnlyItsOwnRulesMismatches() {
        Set<String> rules = attr(rows(REPLACEMENT, "darpan.rule.Rule"), "ruleId")
        Map<String, String> byStatement = rows(REPLACEMENT, "darpan.audit.AuditStatementCheck")
                .collectEntries { [(it.attribute("statementId")): it.attribute("ruleIds")] }
        assertEquals([GORJANA_R14: "GORJANA_MR_SAME_CUSTOMER", GORJANA_R13: "GORJANA_MR_NOT_OWN_ORIGINAL,GORJANA_MR_NOT_YEAR_OLDER"], byStatement)
        assertTrue(rules.containsAll(byStatement.values().collectMany { it.split(",") as List }), "a link names rules the rule set has")
        rows(REPLACEMENT, "darpan.audit.AuditStatementCheck").each { assertEquals("FIELD_MISMATCH", it.attribute("diffTypes")) }
        // Both checks are one compare, so one automation runs them.
        assertEquals(["GORJANA_MEMO_REPLACEMENT_RS"] as Set, attr(rows(REPLACEMENT, "darpan.audit.AuditCheck"), "savedRunId"))
    }

    // DAR-BE-113 R19: Y/N against Y/N with "<=" breaks only when Shopify was paid (Y) and HotWax has no payment (N).
    @Test
    void r19FiresOnlyOnAPaidShopifyOrderWithNoPaymentInHotWax() {
        Map<List<String>, Boolean> cases = [["Y", "N"]: true, ["Y", "Y"]: false, ["N", "N"]: false, ["N", "Y"]: false]
        cases.each { List<String> pair, Boolean fires ->
            assertEquals(fires, reconciliation.rule.RuleDiffSupport.violatesOperator(pair[0], pair[1], "<="), "Shopify ${pair[0]}, HotWax ${pair[1]}")
        }
    }

    @Test
    void storedReplacementDrlIsExactlyWhatTheGeneratorMakes() {
        [REPLACEMENT, CLAIM, RETURNS, RECEIPT, PAYMENTS].each { storedDrlIsExactlyWhatTheGeneratorMakes(it) }
    }

    private static void storedDrlIsExactlyWhatTheGeneratorMakes(String file) {
        List<Node> sources = rows(file, "darpan.rule.RuleSetCompareSource")
        String key1 = sources.find { it.attribute("fileSide") == "FILE_1" }.attribute("primaryIdExpression")
        String key2 = sources.find { it.attribute("fileSide") == "FILE_2" }.attribute("primaryIdExpression")
        rows(file, "darpan.rule.Rule").eachWithIndex { Node rule, int i ->
            String expression = (rule.children().find { it instanceof Node && it.name() == "expression" } as Node).text()
            String stored = (rule.children().find { it instanceof Node && it.name() == "ruleLogic" } as Node).text().trim()
            String expected = FieldComparisonRuleLogicGenerator.generate(expression, key1, key2, rule.attribute("ruleId") as String,
                    rule.attribute("severity") as String, i)
            if (expected != stored) println "EXPECTED_DRL|${rule.attribute('ruleId')}|" + expected.bytes.encodeBase64().toString()
            assertEquals(expected, stored, rule.attribute("ruleId") as String)
        }
    }

    private static void everyReferenceResolvesIn(String file) {
        List<Node> checks = rows(file, "darpan.audit.AuditCheck")
        List<Node> links = rows(file, "darpan.audit.AuditStatementCheck")
        Set<String> ruleSets = attr(rows(file, "darpan.rule.RuleSet") +
                rows("TriSystemOrderRunsRuleSetData.xml", "darpan.rule.RuleSet"), "ruleSetId")
        Set<String> statements = attr(rows("GorjanaMonthCloseCatalogData.xml", "darpan.audit.AuditStatement"), "statementId")
        Set<String> conclusions = attr(rows(file, "moqui.basic.Enumeration") +
                rows("GorjanaConclusionRulesData.xml", "moqui.basic.Enumeration"), "enumId")

        checks.each { Node c ->
            String id = c.attribute("checkId")
            assertEquals("GORJANA", c.attribute("companyUserGroupId"), "${id} tenant")
            assertTrue(ruleSets.contains(c.attribute("savedRunId") as String), "${id} names an unknown saved run")
            assertTrue(c.attribute("fixWhereEnumId") in ["OMS", "NETSUITE", "SHOPIFY"], "${id} fixWhere must be a root system")
            String headline = c.attribute("headlineTemplate") as String
            List<String> names = ["{recordKey}"] + ["file1RecordType", "file2RecordType"].collect { c.attribute(it) }.findAll { it }.collect { "{${it}}".toString() }
            assertTrue(names.any { headline.contains(it) }, "${id} headline names the record (${names})")
            // Automations are created per environment; an id here would be a dangling FK on load.
            assertEquals(null, c.attribute("automationId"), "${id} must not carry an automationId")
            assertFalse(links.findAll { it.attribute("checkId") == id }.isEmpty(), "${id} tests no statement")
        }
        links.each { Node l ->
            assertTrue(statements.contains(l.attribute("statementId") as String), "unknown statement ${l.attribute('statementId')}")
            assertTrue(attr(checks, "checkId").contains(l.attribute("checkId") as String), "unknown check ${l.attribute('checkId')}")
            ((l.attribute("conclusionCodes") ?: "") as String).split(",")*.trim().findAll { it }.each { String code ->
                // UNEXPLAINED is the engine's own code for "no rule matched" (R1 still counts those, DAR-BE-120).
                assertTrue(conclusions.contains(code) || code == "UNEXPLAINED", "undeclared conclusion ${code}")
            }
        }
    }

    @Test
    void anR2RowIsAFindingOnlyWhenTheEtailIdIsBlank() {
        ["GORJANA_INVOICE_ETAIL_SCOPE", "GORJANA_CREDIT_ETAIL_SCOPE"].each { String scope ->
            assertEquals("CONC_NO_ETAIL_ID", conclude(scope, [tranId: "INV1"]), "${scope}: absent (SuiteQL omits null)")
            assertEquals("CONC_NO_ETAIL_ID", conclude(scope, [tranId: "INV1", etailOrderId: " "]), "${scope}: blank")
            assertNotEquals("CONC_NO_ETAIL_ID", conclude(scope, [tranId: "INV1", etailOrderId: "7156795932803"]), "${scope}: carried")
        }
    }

    /** Aditi 2026-10-23: "any fulfilled order is expected to be invoiced 24 hours after its fulfillment". */
    @Test
    void aCompletedItemHasOneDayToBillBeforeItIsOpen() {
        Node check = rows(FILE, "darpan.audit.AuditCheck").find { it.attribute("checkCode") == "completed-not-billed" }
        assertEquals("1", check.attribute("graceDays"))
    }

    @Test
    void theR2RunsReadEveryRecordSoAFixedOneCloses() {
        // A source filter runs in the getter, before the examined-keys file is written: a filtered-out record would
        // never be examined, and its finding could never close.
        [FILE, RETURNS].each { String file ->
            assertTrue(rows(file, "darpan.rule.RuleSetCompareSourceFilter").isEmpty(), file)
            // A one-sided scope reads every record; a two-sided one (R8) closes a finding when the other side shows up.
            Set<String> twoSided = attr(rows(file, "darpan.rule.RuleSetCompareSource").findAll { it.attribute("fileSide") == "FILE_2" }, "compareScopeId")
            rows(file, "darpan.rule.RuleSetCompareScope").each {
                assertEquals(twoSided.contains(it.attribute("compareScopeId")) ? "COMPARE" : "EVALUATE", it.attribute("scopeMode"), file)
            }
        }
    }

    @Test
    void anR9RowIsAFindingWhenTheCustomerNameTheReturnAddressOrTheBillingAddressIsMissing() {
        Map whole = [tranId: "RMA1", returnId: "M258615", customerName: "Ada Lovelace", shipAddressId: "9001", billAddressId: "9002"]
        ["GORJANA_RMA_CUSTOMER_SCOPE", "GORJANA_RETURN_CREDIT_CUSTOMER_SCOPE"].each { String scope ->
            assertEquals("UNEXPLAINED", conclude(RETURNS, scope, whole), "${scope}: name and address")
            assertEquals("CONC_NO_CUSTOMER_NAME", conclude(RETURNS, scope, whole - [customerName: "Ada Lovelace"]), "${scope}: no name")
            assertEquals("CONC_NO_CUSTOMER_NAME", conclude(RETURNS, scope, whole + [customerName: " "]), "${scope}: blank name")
            assertEquals("CONC_NO_RETURN_ADDRESS", conclude(RETURNS, scope, whole - [shipAddressId: "9001"]), "${scope}: no address")
            // Aditi, 2026-10-22: billing is required too. After the name and the return address, so those still name it.
            assertEquals("CONC_NO_BILLING_ADDRESS", conclude(RETURNS, scope, whole + [billAddressId: null]), "${scope}: no billing address")
            assertEquals("CONC_NO_RETURN_ADDRESS", conclude(RETURNS, scope, whole - [shipAddressId: "9001"] + [billAddressId: null]),
                    "${scope}: no address at all reads as the return address first")
        }
        // A credit memo that is not a return's (no HC return id) is not R9's to judge.
        assertEquals("UNEXPLAINED", conclude(RETURNS, "GORJANA_RETURN_CREDIT_CUSTOMER_SCOPE",
                [tranId: "CM1", customerName: null, shipAddressId: null]))
    }

    private static String conclude(String scope, Map record) {
        return conclude(FILE, scope, record)
    }

    private static String conclude(String file, String scope, Map record, String bucket = "FINDING") {
        Closure<List<Map>> scoped = { String name ->
            rows(file, name).findAll { it.attribute("compareScopeId") == scope }.collect { Node node ->
                Map m = new LinkedHashMap(node.attributes())
                ["sequenceNum", "conditionSeq"].each { String k -> if (m[k] != null) m[k] = Integer.valueOf(m[k] as String) }
                m
            }
        }
        Map<String, String> labels = rows(file, "moqui.basic.Enumeration")
                .collectEntries { [(it.attribute("enumId") as String): it.attribute("description") as String] }
        List<Map> rules = RunConclusionStep.buildRules(scoped("darpan.rule.RuleSetConclusionRule"),
                scoped("darpan.rule.RuleSetConclusionCondition"), labels)
        Map side = [presence: "KEPT", record: record, firstFieldPresence: "KEPT", firstFieldRecord: record]
        Map absent = [presence: "ABSENT", record: null, firstFieldPresence: "ABSENT", firstFieldRecord: null]
        // The production engine, not the frozen flat oracle: R11 reads a nested list field with quantifier ANY.
        ConclusionTreeEngine engine = ConclusionTreeEngine.compile(rules)
        try {
            return engine.conclude([[bucket: bucket, sides: [FILE_1: side, FILE_2: absent]]])[0].code
        } finally {
            engine.close()
        }
    }
}
