package darpan.datasource

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-176. A derive step's rows are data and run as Drools rules from one fixed template (Aditi 2026-11-02:
 * computational logic "mostly should be written in Drools rather than code"). The DRL never holds definition text.
 */
class DeriveRulesTests {

    private static final List<Map> RULES = [
            [set: "stage", value: [map: [OPEN: "open", SHIPPED: "done"], from: "status", default: "other"]],
            [set: "issuedAt", value: [fn: "text", args: [[fn: "epochMillis", args: [[copy: "invoice.issued"]]]]]],
            [set: "total", value: [fn: "decimalPlain", args: [[copy: "invoice.total"]]]],
            [set: "who", value: [fn: "join", sep: " ", args: [[copy: "invoice.first"], [copy: "invoice.last"]]]],
            [set: "lineKey", value: [fn: "join", sep: ":", args: ["unkeyed", [copy: "line.lineNo"]]]],
            [set: "lineKey", when: [[field: "line.ref", op: "NOT_BLANK"], [field: "invoice.ref", op: "NOT_BLANK"]], value: [copy: "line.ref"]],
            [set: "fixed", value: [literal: "NONE"]],
            [set: "nothing", value: [literal: null]],
            [set: "seen", value: [fn: "firstPresent", args: [[copy: "line.note"], [copy: "stage"]]]],
    ]

    private static Map record(Map invoice, Map line, String status) {
        return [invoice: invoice, line: line, status: status]
    }

    @Test
    void rowsFireInOrderAsDroolsRules() {
        List<Map> records = [
                record([issued: "2026-10-01T00:00:00Z", total: "12.500", first: "Ada", last: null, ref: "R1"], [lineNo: "01", ref: "L1"], "OPEN"),
                record([issued: 1790812800000L, total: 3, first: " ", last: " "], [lineNo: "02", ref: "L2", note: "kept"], "LOST"),
                record([issued: "not a date", total: "x"], [lineNo: "03"], null),
        ]
        DeriveEngine engine = DeriveEngine.compile(RULES)
        try {
            engine.derive(records, "test")
        } finally {
            engine.close()
        }
        Map a = records[0], b = records[1], c = records[2]
        assertEquals(["open", "other", "other"], records*.stage)
        assertEquals(["1790812800000", "1790812800000", "not a date"], records*.issuedAt)
        assertEquals(new BigDecimal("12.5"), a.total)
        assertEquals(new BigDecimal("3"), b.total)
        assertEquals("x", c.total)
        assertEquals("Ada", a.who)
        assertNull(b.who)
        // Keyed only when both refs are there: b has a line ref but no invoice ref.
        assertEquals(["L1", "unkeyed:02", "unkeyed:03"], records*.lineKey)
        assertEquals(["NONE"] * 3, records*.fixed)
        assertTrue(a.containsKey("nothing") && a.nothing == null)
        // firstPresent reads a field an earlier row set.
        assertEquals(["open", "kept", "other"], records*.seen)
    }

    @Test
    void theRuleTextHoldsOnlyNumbersNeverDefinitionText() {
        String drl = DeriveEngine.drl(3)
        assertEquals(3, (drl =~ /rule "row\d+"/).size())
        assertFalse(drl.contains("stage") || drl.contains("OPEN"))
        assertTrue(drl.contains("salience 3") && drl.contains("salience 1"))
    }

    @Test
    void badRowsAreRefusedInWords() {
        List<String> errors = DeriveRules.check("Step 'd'", [
                [value: [literal: 1]],
                [set: "a", value: [copy: "x", literal: 2]],
                [set: "b", value: [fn: "eval", args: ["1"]]],
                [set: "c", value: [map: [A: 1]]],
                [set: "d", when: [[field: "x", op: "LIKE"]], value: [literal: 1]],
        ])
        ["rule 1 needs set", "rule 2: value is exactly one of", "rule 3: fn is one of text, decimal", "rule 4: map needs",
         "rule 5: a condition is one of"].each { String e -> assertTrue(errors.any { it.contains(e) }, e + " in " + errors) }
        assertEquals(["Step 'd': a derive step needs rules."], DeriveRules.check("Step 'd'", []))
    }

    @Test
    void aDeriveStepRunsInsideTheRunner() {
        Map d = AcmeLedgerFixture.definition()
        d.steps = ((List) d.steps) + [[id: "labels", kind: "derive", rules: [
                [set: "stage", value: [map: [OPEN: "open", SHIPPED: "done"], from: "status", default: "other"]],
                [set: "stage", when: [[field: "unitNo", op: "IN", values: [2]]], value: [literal: "second"]],
                [set: "issuedAt", value: [fn: "epochMillis", args: [[copy: "invoice.issued"]]]]]]]
        d.record = [fields: ["invoiceNo", "lineNo", "unitNo", "stage", "issuedAt"]]
        assertEquals([], DataSourceDefinitionSupport.check("GRAPHQL", d))
        List<Map> out = []
        new DataSourceRunner(d, new AcmeLedgerFixture.ScriptedTransport()).run([mode: "ids", keys: ["A1"], keyField: "invoiceNo"]) { out.addAll(it) }
        assertEquals([[invoiceNo: "A1", lineNo: "01", unitNo: 1, stage: "open", issuedAt: 1790830800000L],
                      [invoiceNo: "A1", lineNo: "02", unitNo: 1, stage: "done", issuedAt: 1790830800000L],
                      [invoiceNo: "A1", lineNo: "02", unitNo: 2, stage: "second", issuedAt: 1790830800000L]], out)
    }

    /**
     * DAR-OPS-029 S15b, lens E3: derive is pure and deterministic. The Drools rule base writes exactly what the rows
     * applied in order write, record for record, whatever order the records come in.
     */
    @Test
    void droolsWritesWhatTheRowsAppliedInOrderWrite() {
        List<Map> records = (0..<200).collect { int i ->
            record([seq: i, invoiceNo: "I${i.intdiv(3)}".toString(), total: "${i % 7}.50".toString()],
                    [lineNo: String.format('%02d', i % 4), qty: i % 5 == 0 ? "" : "${i % 5}".toString()], ["open", "done", null][i % 3])
        }
        List<Map> plain = records.collect { deepCopy(it) }
        plain.each { Map r -> RULES.each { Map rule -> if (DeriveRules.matches(r, rule)) DeriveRules.apply(r, rule) } }
        List<Map> shuffled = records.collect { deepCopy(it) }
        Collections.shuffle(shuffled, new Random(7))
        DeriveEngine engine = DeriveEngine.compile(RULES)
        try {
            engine.derive(shuffled, "parity")
        } finally {
            engine.close()
        }
        Closure<Integer> seq = { Map r -> (Integer) ((Map) r.invoice).seq }
        assertEquals(plain.sort(false, seq), shuffled.sort(false, seq))
    }

    /**
     * DAR-OPS-029 S15b (QA): when a row's `when` is read. Row 2 asks about a field row 1 set in the same step; the engine and
     * the rows applied in order must agree on it.
     */
    @Test
    void aRowsWhenSeesWhatAnEarlierRowOfTheStepSet() {
        List<Map> rules = [[set: "flag", value: [literal: "Y"]],
                           [set: "hit", when: [field: "flag", op: "IN", values: ["Y"]], value: [literal: "yes"]]]
        Map plain = [id: 1]
        rules.each { Map rule -> if (DeriveRules.matches(plain, rule)) DeriveRules.apply(plain, rule) }
        Map viaDrools = [id: 1]
        DeriveEngine engine = DeriveEngine.compile(rules)
        try {
            engine.derive([viaDrools], "when-timing")
        } finally {
            engine.close()
        }
        assertEquals(plain, viaDrools)
        assertEquals("yes", viaDrools.hit)
    }

    /**
     * DAR-OPS-029 S15b, lens E (no silent false): a missing field is BLANK, on purpose and in every op. IN and MATCHES do not
     * hold, NOT_IN does; a path through a list that reaches nothing is BLANK and NOT_IN anything. Numbers compare as numbers.
     */
    @Test
    void aMissingFieldIsBlankInEveryCondition() {
        Map r = [order: [lines: []]]
        assertTrue(RecordConditions.matches(r, [field: "order.status", op: "BLANK"]))
        assertFalse(RecordConditions.matches(r, [field: "order.status", op: "NOT_BLANK"]))
        assertFalse(RecordConditions.matches(r, [field: "order.status", op: "IN", values: ["", "NONE"]]), "a missing value is in no list, not even of blanks")
        assertTrue(RecordConditions.matches(r, [field: "order.status", op: "NOT_IN", values: ["NONE"]]))
        assertFalse(RecordConditions.matches(r, [field: "order.status", op: "MATCHES", values: [".*"]]))
        assertFalse(RecordConditions.matches(r, [field: "order.status", op: "AFTER_NOW"]))
        assertTrue(RecordConditions.matches(r, [field: "order.lines[*].sku", op: "BLANK"]))
        assertTrue(RecordConditions.matches(r, [field: "order.lines[*].sku", op: "NOT_IN", values: ["A"]]))
        assertFalse(RecordConditions.matches(r, [field: "order.lines[*].sku", op: "IN", values: ["A"]]))
        assertTrue(RecordConditions.matches([qty: 1], [field: "qty", op: "IN", values: ["1.0"]]), "1, \"1\" and 1.0 are one value")
    }

    /** OC173: a batch that reached the rule-fire cap may have rows left unfired, so it fails and writes nothing. */
    @Test
    void aBatchThatReachedTheFireCapFailsInsteadOfWritingPartialRecords() {
        int cap = reconciliation.rule.RuleEngineSupport.MAX_RULE_FIRES
        DeriveEngine.requireComplete([fired: cap - 1, halted: false])
        assertTrue(assertThrows(IllegalStateException) { DeriveEngine.requireComplete([fired: cap, halted: false]) }.message.contains("fire cap"))
        assertTrue(assertThrows(IllegalStateException) { DeriveEngine.requireComplete([fired: 3, halted: true]) }.message.contains("halted"))
    }

    /** OC174: a text with a leading zero is text: id "007" is not id "7"; "0", "0.5" and "-0.5" are still numbers. */
    @Test
    void aLeadingZeroKeepsATextAText() {
        assertFalse(RecordConditions.same("007", 7))
        assertFalse(RecordConditions.same("007", "7"))
        assertFalse(RecordConditions.matches([id: "007"], [field: "id", op: "IN", values: ["7"]]))
        assertTrue(RecordConditions.matches([id: "007"], [field: "id", op: "NOT_IN", values: [7]]))
        assertFalse(RecordConditions.same("-01", -1))
        assertTrue(RecordConditions.same("7", 7.0))
        assertTrue(RecordConditions.same("0", 0))
        assertTrue(RecordConditions.same("0.5", 0.5))
        assertTrue(RecordConditions.same("-0.5", new BigDecimal("-0.50")))
    }

    /** OC177 (Aditi 2026-11-28): a present amount that is not a number makes multiply/sum unknown; a blank still counts as 0. */
    @Test
    void anAmountThatIsNotANumberMakesTheArithmeticUnknownNotZero() {
        Map line = [price: "\$5", qty: 2, blankPrice: "", items: [[amount: "1.50"], [amount: "n/a"]], ok: [[amount: "1.50"], [amount: null]]]
        assertNull(DeriveRules.valueOf(line, [fn: "multiply", args: [[copy: "price"], [copy: "qty"]]]))
        assertEquals(new BigDecimal("0"), DeriveRules.valueOf(line, [fn: "multiply", args: [[copy: "blankPrice"], [copy: "qty"]]]))
        assertNull(DeriveRules.valueOf(line, [fn: "sum", args: [[copy: "items[*].amount"]]]))
        assertEquals(new BigDecimal("1.50"), DeriveRules.valueOf(line, [fn: "sum", args: [[copy: "ok[*].amount"]]]))
    }

    /** OC178 (Aditi 2026-11-28): AFTER_NOW compares against the run's reference time, so a replay reads the same. */
    @Test
    void afterNowReadsTheRunsReferenceTimeNotTheClock() {
        long y2000 = java.time.Instant.parse("2000-01-01T00:00:00Z").toEpochMilli()
        Map past = [thru: "2001-01-01T00:00:00Z"]
        assertTrue(RecordConditions.matches(past, [field: "thru", op: "AFTER_NOW"], y2000), "after the run's time, though long past")
        assertFalse(RecordConditions.matches(past, [field: "thru", op: "AFTER_NOW"]), "with no run time, the clock")
        Map r = [ids: [[value: "OLD", thru: "2001-01-01T00:00:00Z"], [value: "NEW"]]]
        List<Map> rules = [[set: "active", value: [fn: "firstWhere", list: "ids", field: "value",
                                                   when: [[field: "thru", op: "AFTER_NOW"]]]]]
        DeriveEngine engine = DeriveEngine.compile(rules)
        try {
            engine.derive([r], "reference-time", y2000)
        } finally {
            engine.close()
        }
        assertEquals("OLD", r.active, "the identification active at the run's time")
    }

    private static Map deepCopy(Map m) {
        return (Map) m.collectEntries { k, v -> [(k): v instanceof Map ? deepCopy((Map) v) : v] }
    }

    /**
     * The performance check (spec 4): a month of one tenant's unit records (~165k) through a realistic derive step, in
     * the runner's 1000-record batches. Runs only with DARPAN_PERF=1; prints its numbers.
     */
    @Test
    void aMonthOfRecordsThroughTheDeriveStep() {
        Assumptions.assumeTrue(System.getenv("DARPAN_PERF") == "1")
        List<Map> rules = [
                [set: "unitState", value: [map: [CREATED: "OPEN", APPROVED: "OPEN", COMPLETED: "COMPLETED", CANCELLED: "CANCELLED"], from: "item.statusId", default: "OTHER"]],
                [set: "lineKey", value: [fn: "join", sep: ":", args: ["unkeyed", [copy: "item.seq"]]]],
                [set: "lineKey", when: [[field: "item.externalId", op: "NOT_BLANK"], [field: "order.externalId", op: "NOT_BLANK"]], value: [copy: "item.externalId"]],
                [set: "orderDate", value: [fn: "text", args: [[fn: "epochMillis", args: [[copy: "order.orderDate"]]]]]],
                [set: "orderTotal", value: [fn: "decimalPlain", args: [[copy: "order.grandTotal"]]]],
                [set: "customerName", value: [fn: "join", sep: " ", args: [[copy: "order.first"], [copy: "order.last"]]]],
                [set: "constant", value: [literal: "NONE"]],
                [set: "empty", value: [literal: null]],
                [set: "orderId", value: [copy: "order.orderId"]],
                [set: "statusId", value: [copy: "item.statusId"]],
                [set: "facilityId", value: [copy: "group.facilityId"]],
                [set: "sku", value: [copy: "item.sku"]],
        ]
        int total = 165_155
        List<String> statuses = ["CREATED", "APPROVED", "COMPLETED", "CANCELLED", "HELD"]
        long compileStarted = System.nanoTime()
        DeriveEngine engine = DeriveEngine.compile(rules)
        long compileMs = (System.nanoTime() - compileStarted) / 1_000_000L
        long deriveNanos = 0L
        long plainNanos = 0L
        try {
            for (int start = 0; start < total; start += 1000) {
                List<Map> batch = (start..<Math.min(total, start + 1000)).collect { int i ->
                    [order: [orderId: "O${i.intdiv(2)}".toString(), externalId: i % 7 == 0 ? null : "E${i}".toString(),
                             orderDate: "2026-10-${String.format('%02d', 1 + i % 28)}T10:00:00Z", grandTotal: "${i % 300}.00".toString(), first: "F", last: "L"],
                     group: [facilityId: "F${i % 9}".toString()],
                     item : [seq: String.format('%05d', i % 5), externalId: i % 3 == 0 ? null : "X${i}".toString(), statusId: statuses[i % 5], sku: "S${i % 50}".toString()]] as Map
                }
                List<Map> twin = batch.collect { new LinkedHashMap(it) }
                long t = System.nanoTime()
                engine.derive(batch, "perf")
                deriveNanos += System.nanoTime() - t
                // The same rows without Drools, for what the rule engine itself costs.
                long p = System.nanoTime()
                twin.each { Map r -> rules.each { Map rule -> if (DeriveRules.matches(r, rule)) DeriveRules.apply(r, rule) } }
                plainNanos += System.nanoTime() - p
                assertEquals(twin, batch, "Drools and the rows applied in order write the same records")
            }
        } finally {
            engine.close()
        }
        long deriveMs = deriveNanos / 1_000_000L
        println "DERIVE_PERF records=${total} rules=${rules.size()} compileMs=${compileMs} deriveMs=${deriveMs} perRecordMicros=${(deriveNanos / 1000L / total)} plainLoopMs=${plainNanos / 1_000_000L}"
        assertTrue(deriveMs > 0)
    }
}
