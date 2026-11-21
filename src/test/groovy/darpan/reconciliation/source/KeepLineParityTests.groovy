package darpan.reconciliation.source

import darpan.common.FactText
import darpan.facade.common.TenantAccessSupport
import darpan.judge.PairFactSupport
import darpan.reconciliation.conclusion.JsonRecordStream
import darpan.reconciliation.core.JsonRecordLines
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonOutput
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Row
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.types.DataTypes
import org.apache.spark.sql.types.Metadata
import org.apache.spark.sql.types.StructField
import org.apache.spark.sql.types.StructType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext

import java.nio.file.Files
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 (plan 3b-2b, Task 7). Front-end B's parity suite: a Keep decision stored in moqui-rules (store#Rule), read
 * back and translated by KeepLineTranslator, run in Spark by KeepColumnSupport over the Keep text columns JsonRecordLines
 * writes, sets aside exactly the records moqui-rules' evaluate#RuleSet matches, record by record: the same rule, or none.
 *
 * moqui-rules is the oracle and is never changed to agree. Each record is one fact, built from the same records file the
 * way the judge builds it (read by JsonRecordStream, FactText.flat, then PairFactSupport.buildFact with the keyNames generate#PairFactType gives),
 * and judged in its own call, so a fact moqui-rules refuses is seen alone. The pair fact type is the real one, on a check
 * of the made-up systems ACME and BRAVO (side prefix acme); the record keys include a character nameKeys rewrites, a key
 * over the 40-character field name limit and two keys that differ only in case, so every rule reads its record key
 * through recordKeys. Runs as the tenant user with authz on; local Spark with ANSI on and no UI.
 *
 * Together with KeepColumnParityTests (front-end A), this class is the parity suite later tasks run.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KeepLineParityTests {

    private static final String A = "KREWE"
    private static final String PREF_KEY = TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY
    private static final String EDITOR_ID = "TEST_CUSTOMER_USER"
    private static final String EDITOR_USERNAME = "test.customer"
    private static final Timestamp FROM_DATE = Timestamp.valueOf("2026-04-21 00:00:00")
    private static final String FACADE_GRANT = "facade.ReconciliationFacadeServices.list#Reconciliations"
    private static final String LONG_KEY = "shipment.destination.address.postalCodeLine"
    private static final List<String> KEYS = ["status", "amount", "qty", "gift", "city", "order-ref", "Code", "code", LONG_KEY]
    private static final String REFUSED = "REFUSED: "

    private ExecutionContext ec
    private SparkSession spark
    private File workDir
    private String factTypeId
    /** record key to fact field name (generate#PairFactType keyNames.file1) */
    private Map<String, String> fieldOf
    private Map sideState
    private int decisionCount = 0

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "keep-line-parity")
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.loadSeedData(ec, "classpath://data/EntityTypeData.xml",
                "component://moqui-rules/data/RuleSeedData.xml", "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/JudgeSeedData.xml", "component://darpan/data/MappingSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        insert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: EDITOR_ID, fromDate: FROM_DATE])
        actAs(A)

        String checkId = ec.entity.sequencedIdPrimary("darpan.audit.AuditCheck", null, null)
        insert("darpan.audit.AuditCheck", [checkId: checkId, companyUserGroupId: A, checkCode: "keep-parity", label: "Keep parity"])
        insert("darpan.judge.CheckParameterSet", [checkId: checkId, parameterSetId: "DEFAULT", companyUserGroupId: A])
        ["region", "limit"].each { String name ->
            insert("darpan.judge.CheckParameter", [checkId: checkId, parameterSetId: "DEFAULT", parameterName: name, companyUserGroupId: A])
        }
        Map<String, String> names = PairFactSupport.nameKeys("acme", [:], KEYS)
        Map generated = ok(underFacadeGrant("judge.PairFactServices.generate#PairFactType", [checkId: checkId,
                file1SystemEnumId: "ACME", file2SystemEnumId: "BRAVO", sampleKeys: [file1: KEYS, file2: ["tranId"]],
                kindOverrides: [(names.amount): "FVT_DECIMAL", (names.qty): "FVT_INTEGER", (names.gift): "FVT_BOOLEAN",
                                param_limit: "FVT_DECIMAL"]]))
        factTypeId = generated.factTypeId
        fieldOf = ((Map) generated.keyNames).file1 as Map<String, String>
        assertEquals(names, fieldOf, "generate#PairFactType names the keys as nameKeys does")
        assertTrue(names.values().containsAll(["acme_order_ref", "acme_Code", "acme_code_2"]), names.toString())
        assertEquals(40, fieldOf[LONG_KEY].length(), "the long key's field name is cut at the limit")
        sideState = [factTypeId: factTypeId, file1: [prefix: "acme", setAside: [], keyNames: fieldOf],
                     file2: [prefix: "bravo", setAside: [], keyNames: ((Map) generated.keyNames).file2]] as Map

        spark = SparkSession.builder().appName("KeepLineParityTests").master("local[1]")
                .config("spark.ui.enabled", "false").config("spark.sql.ansi.enabled", "true").getOrCreate()
        workDir = Files.createTempDirectory("keep-line-parity").toFile()
    }

    @AfterAll
    void cleanup() {
        if (spark != null) spark.stop()
        workDir?.deleteDir()
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void actAsA() {
        actAs(A)
    }

    // ----------------------------------------------------------------------------------------------------- the cases

    @Test
    void equalsAndNotEqualsOnTextNumberAndYesNo() {
        List<Map> records = [
                [status: "open", amount: 24.00G, gift: true],
                [status: "OPEN", amount: 24, gift: "y"],
                [status: " open", amount: "24.0", gift: false],
                [status: "", amount: 25, gift: "N"],
                [:],
                [status: "closed", amount: -0.5G, gift: "true"],
                [status: "x", amount: " 24 ", gift: " Y "],
        ]
        List<String> got = parity([
                rule("status is open", 30, "OPEN", [cmp("01", "status", "ENTCO_EQUALS", "open")]),
                rule("amount is not 24", 20, "NOT_24", [cmp("01", "amount", "ENTCO_NOT_EQUALS", "24.00")]),
                rule("a gift", 10, "GIFT", [cmp("01", "gift", "ENTCO_EQUALS", "y")]),
        ], records)
        // text compares exactly and with case; numbers by value; no value is not equal to anything, so != holds for it
        assertEquals(["OPEN", "GIFT", null, "NOT_24", "NOT_24", "NOT_24", "GIFT"], got)

        // a whole-number field, the record key whose fact field name is cut at 40, and two keys that differ only in case
        List<Map> more = [[qty: 7, (LONG_KEY): "10001", code: "X"], [qty: 7.0G, Code: "X"], [qty: "7.00", code: "x"],
                          [qty: 8, (LONG_KEY): "10002", Code: "Y", code: "X"], [:]]
        assertEquals(["QTY_7", "QTY_7", "QTY_7", "LOWER_CODE", null], parity([
                rule("seven", 30, "QTY_7", [cmp("01", "qty", "ENTCO_EQUALS", "7")]),
                rule("lower code", 20, "LOWER_CODE", [cmp("01", "code", "ENTCO_EQUALS", "X")]),
                rule("postal", 10, "POSTAL", [cmp("01", LONG_KEY, "ENTCO_EQUALS", "10002")]),
        ], more))
        assertEquals([null, "UPPER_CODE", null, "POSTAL", null], parity([
                rule("postal", 10, "POSTAL", [cmp("01", LONG_KEY, "ENTCO_EQUALS", "10002")]),
                rule("upper code", 5, "UPPER_CODE", [cmp("01", "Code", "ENTCO_EQUALS", "X")]),
        ], more))
    }

    @Test
    void lessAndGreaterWithNoValue() {
        // Ruling 3b2b-21: no value holds for < and <=, and not for > or >= (Moqui's compareByOperator, null first)
        List<Map> records = [[:], [amount: null], [amount: 3], [amount: 5], [amount: "5.0"], [amount: 7.50G]]
        Map<String, List<String>> expected = [
                ENTCO_LESS      : ["HELD", "HELD", "HELD", null, null, null],
                ENTCO_LESS_EQ   : ["HELD", "HELD", "HELD", "HELD", "HELD", null],
                ENTCO_GREATER   : [null, null, null, null, null, "HELD"],
                ENTCO_GREATER_EQ: [null, null, null, "HELD", "HELD", "HELD"],
        ]
        expected.each { String op, List<String> answers ->
            assertEquals(answers, parity([rule("amount ${op} 5", 10, "HELD", [cmp("01", "amount", op, "5")])], records), op)
        }
        // a not over < with no value is >=, which does not hold for no value (prepare#NotLine pushes it to the leaf)
        assertEquals([null, null, null, "HELD", "HELD", "HELD"], parity([rule("not under 5", 10, "HELD", [
                line("01", "RCT_NOT"), cmp("02", "amount", "ENTCO_LESS", "5", "01")])], records))
        // text orders as Java compares it; an empty text is a value and orders first
        List<Map> cities = [[city: "apple"], [city: "Zebra"], [city: "m"], [city: ""], [:], [city: "n"]]
        assertEquals(["BEFORE_M", "BEFORE_M", null, "BEFORE_M", "BEFORE_M", null],
                parity([rule("before m", 10, "BEFORE_M", [cmp("01", "city", "ENTCO_LESS", "m")])], cities))
    }

    @Test
    void inAndNotInWithNumbersByValue() {
        List<Map> records = [[amount: 2.5G], [amount: 2.50G], [amount: "2.5"], [amount: 7.0G], [amount: 7], [amount: 8], [:]]
        assertEquals(["IN", "IN", "IN", "IN", "IN", null, null],
                parity([rule("listed", 10, "IN", [cmp("01", "amount", "ENTCO_IN", '["2.50", 7]')])], records))
        assertEquals([null, null, null, null, null, "OUT", "OUT"],
                parity([rule("not listed", 10, "OUT", [cmp("01", "amount", "ENTCO_NOT_IN", '["2.50", 7]')])], records))
        // on a text field a listed number is its plain text: 1e2 is "100"
        List<Map> statuses = [[status: "100"], [status: 100], [status: "1e2"], [status: "open"], [status: "Open"], [:]]
        assertEquals(["IN", "IN", null, "IN", null, null],
                parity([rule("listed", 10, "IN", [cmp("01", "status", "ENTCO_IN", '["open", 1e2]')])], statuses))
    }

    @Test
    void normalizeTextOnBlankAndSS() {
        List<Map> records = [[city: "Straße"], [city: " STRASSE\t"], [city: "strass"], [city: "   "], [city: ""], [:], [city: "ss"]]
        assertEquals(["SAME", "SAME", null, null, null, null, null], parity([rule("strasse", 10, "SAME",
                [cmp("01", "city", "ENTCO_EQUALS", "strasse", null, "Y")])], records))
        // a blank text is no value with normalizeText, so != and not in hold for it
        assertEquals([null, null, "OTHER", "OTHER", "OTHER", "OTHER", "OTHER"], parity([rule("not strasse", 10, "OTHER",
                [cmp("01", "city", "ENTCO_NOT_EQUALS", "STRASSE", null, "Y")])], records))
        assertEquals([null, null, null, null, null, null, "SS"], parity([rule("eszett", 10, "SS",
                [cmp("01", "city", "ENTCO_IN", '["ß"]', null, "Y")])], records))
        assertEquals(["OUT", "OUT", "OUT", "OUT", "OUT", "OUT", null], parity([rule("not eszett", 10, "OUT",
                [cmp("01", "city", "ENTCO_NOT_IN", '[" ß "]', null, "Y")])], records))
    }

    @Test
    void likeIgnoresCaseAndTrimsOnlyWithNormalizeText() {
        List<Map> records = [[city: "ABcd"], [city: " abcd"], [city: "xab"], [city: "ab"], [:], [city: "a\nb"], [city: "aXb "]]
        assertEquals(["LIKE", null, null, "LIKE", null, null, null],
                parity([rule("ab%", 10, "LIKE", [cmp("01", "city", "ENTCO_LIKE", "ab%")])], records))
        assertEquals(["LIKE", "LIKE", null, "LIKE", null, null, null],
                parity([rule("ab% trimmed", 10, "LIKE", [cmp("01", "city", "ENTCO_LIKE", "ab%", null, "Y")])], records))
        // not like holds for no value; _ is one character, a line break included
        assertEquals([null, "UNLIKE", "UNLIKE", null, "UNLIKE", "UNLIKE", "UNLIKE"],
                parity([rule("not ab%", 10, "UNLIKE", [cmp("01", "city", "ENTCO_NOT_LIKE", "AB%")])], records))
        assertEquals([null, null, null, null, null, "A_B", null],
                parity([rule("a_b", 10, "A_B", [cmp("01", "city", "ENTCO_LIKE", "a_b")])], records))
        assertEquals([null, null, null, null, null, "A_B", "A_B"],
                parity([rule("a_b trimmed", 10, "A_B", [cmp("01", "city", "ENTCO_LIKE", "a_b", null, "Y")])], records))
    }

    @Test
    void notOverAllAnyAndCompare() {
        List<Map> records = [
                [status: "open", amount: 3, code: "X", qty: 1],
                [status: "open", amount: 7, qty: 5],
                [status: "closed", amount: 3, Code: "X", qty: 3],
                [amount: 2],
                [:],
                [status: "open"],
                [qty: 2, code: "x"],
        ]
        // not (open and under 5): the all line turned around is (not open) or (5 or more), and no value is under 5
        assertEquals([null, "NOT_BOTH", "NOT_BOTH", "NOT_BOTH", "NOT_BOTH", null, "NOT_BOTH"], parity([rule("not both", 10, "NOT_BOTH", [
                line("01", "RCT_NOT"), line("02", "RCT_ALL", "01"),
                cmp("03", "status", "ENTCO_EQUALS", "open", "02"), cmp("04", "amount", "ENTCO_LESS", "5", "02")])], records))
        // any (code X, not (qty 3 or more)): not >= is <, which holds for no qty
        assertEquals(["EITHER", null, null, "EITHER", "EITHER", "EITHER", "EITHER"], parity([rule("either", 10, "EITHER", [
                line("01", "RCT_ANY"), cmp("02", "code", "ENTCO_EQUALS", "X", "01"),
                line("03", "RCT_NOT", "01"), cmp("04", "qty", "ENTCO_GREATER_EQ", "3", "03")])], records))
        // a not over several lines holds when none of them does
        assertEquals([null, null, null, "NONE", null, null, null], parity([rule("none", 10, "NONE", [
                line("01", "RCT_NOT"), cmp("02", "status", "ENTCO_IS_NOT_NULL", null, "01"),
                cmp("03", "qty", "ENTCO_IS_NOT_NULL", null, "01"), cmp("04", "amount", "ENTCO_IS_NULL", null, "01")])], records))
        // top lines all hold; a not over a not; a rule with no lines holds for every record, after the higher one
        assertEquals(["BOTH", "REST", "REST", "REST", "REST", "REST", "BOTH"], parity([
                rule("both", 20, "BOTH", [cmp("01", "status", "ENTCO_NOT_EQUALS", "closed"),
                                          line("02", "RCT_NOT"), line("03", "RCT_NOT", "02"), cmp("04", "amount", "ENTCO_LESS_EQ", "3", "03"),
                                          cmp("05", "qty", "ENTCO_IS_NOT_NULL")]),
                rule("rest", 10, "REST", []),
        ], records))
    }

    @Test
    void aParamLineIsJudgedAsMoquiRulesJudgesIt() {
        // the translator judges param lines with moqui-rules' ValueChecks and Moqui's in-memory condition, not a copy
        List<Map> rules = [
                rule("east", 40, "EAST", [cmp("01", "param_region", "ENTCO_EQUALS", "east", null, "Y", false),
                                          cmp("02", "status", "ENTCO_IS_NOT_NULL")]),
                rule("over the limit", 30, "OVER", [cmp("01", "param_limit", "ENTCO_GREATER", "10", null, null, false)]),
                rule("w region", 20, "W", [cmp("01", "param_region", "ENTCO_LIKE", "w%", null, null, false)]),
                rule("within the limit", 10, "WITHIN", [cmp("01", "param_limit", "ENTCO_LESS_EQ", "10", null, null, false)]),
        ]
        List<Map> records = [[status: "a"], [:]]
        Map<Map, List<String>> expected = [
                [region: " East ", limit: "12"]: ["EAST", "OVER"],
                [region: "west", limit: "9"]   : ["W", "W"],
                [region: "WEST"]               : ["W", "W"],
                [region: "", limit: "10.0"]    : ["WITHIN", "WITHIN"],
                [:]                            : ["WITHIN", "WITHIN"],   // no limit is under 10 (Ruling 3b2b-21)
                [region: "north", limit: "11"] : ["OVER", "OVER"],
        ]
        expected.each { Map params, List<String> answers -> assertEquals(answers, parity(rules, records, params), params.toString()) }

        // Every other operator on a param line, each against evaluate#RuleSet over a missing, a blank and some set values.
        // [operator, compare value, normalizeText, answers for a missing and a blank param]
        List<Map> regions = [[:], [region: ""], [region: "east"], [region: " West "], [region: "wren"], [region: "ss"], [region: "zulu"]]
        paramOperators("param_region", regions, [
                ["ENTCO_NOT_EQUALS", "east", null, ["HELD", "HELD"]],
                ["ENTCO_IN", '["east", " West "]', null, [null, null]],
                ["ENTCO_NOT_IN", '["east", " West "]', null, ["HELD", "HELD"]],
                ["ENTCO_IS_NULL", null, null, ["HELD", null]],          // without normalizeText a blank text is a value
                ["ENTCO_IS_NOT_NULL", null, null, [null, "HELD"]],
                ["ENTCO_LESS", "m", null, ["HELD", "HELD"]],            // no value, and an empty text, order first
                ["ENTCO_GREATER_EQ", "m", null, [null, null]],
                ["ENTCO_NOT_LIKE", "w%", null, ["HELD", "HELD"]],
                ["ENTCO_IN", '[" WEST ", "ß"]', "Y", [null, null]],    // with normalizeText a blank text is no value
                ["ENTCO_NOT_IN", '[" WEST ", "ß"]', "Y", ["HELD", "HELD"]],
                ["ENTCO_NOT_LIKE", "w%", "Y", ["HELD", "HELD"]],
        ])
        // A number param: a blank does not fit, so moqui-rules refuses the fact and the translator refuses the line (both, below).
        List<Map> limits = [[:], [limit: "10.0"], [limit: "2.5"], [limit: "11"], [limit: "-1"]]
        List<List> numberLines = [
                ["ENTCO_NOT_EQUALS", "10", null, ["HELD"]],
                ["ENTCO_IN", '["2.50", 10]', null, [null]],
                ["ENTCO_NOT_IN", '["2.50", 10]', null, ["HELD"]],
                ["ENTCO_LESS", "10", null, ["HELD"]],
                ["ENTCO_GREATER_EQ", "10", null, [null]],
                ["ENTCO_IS_NULL", null, null, ["HELD"]],
                ["ENTCO_IS_NOT_NULL", null, null, [null]],
        ]
        paramOperators("param_limit", limits, numberLines)
        numberLines.each { List spec ->
            String ruleSetId = storeDecision(paramRules("param_limit", spec))
            IllegalArgumentException refused = assertThrows(IllegalArgumentException) {
                KeepLineTranslator.translate(decisionRules(ruleSetId), "acme", fieldKinds(), [limit: ""] as Map<String, Object>, recordKeys())
            }
            assertTrue(refused.message.contains("parameter value  does not fit field param_limit"), refused.message)
            File source = recordsFile(ruleSetId, [[status: "a"]])
            String moqui = moquiReasons(ruleSetId, source, [limit: ""])[0]
            assertTrue(moqui?.startsWith(REFUSED) && moqui.contains("Value  does not fit field param_limit"), "${spec[0]}: ${moqui}")
        }
    }

    /**
     * One decision per line: the param line, and below it a record line that never holds (so the records file has a Keep
     * column). Each param set is run both ways (parity asserts each record); the first answers are pinned, and each line
     * must both hold and not hold across the sets, so no line passes on a constant.
     */
    private void paramOperators(String field, List<Map> paramSets, List<List> lines) {
        lines.each { List spec ->
            String ruleSetId = storeDecision(paramRules(field, spec))
            List<String> answers = paramSets.collect { Map params -> parityOn(ruleSetId, [[status: "a"]], params)[0] }
            String what = "${field} ${spec[0]} ${spec[1]} normalize ${spec[2]}: ${answers}"
            assertEquals(spec[3], answers.take(((List) spec[3]).size()), what)
            assertTrue(answers.contains("HELD") && answers.contains(null), what)
        }
    }

    private List<Map> paramRules(String field, List spec) {
        return [rule("param line", 20, "HELD", [cmp("01", field, spec[0] as String, spec[1] as String, null, spec[2] as String, false)]),
                rule("record line", 10, "NEVER", [cmp("01", "status", "ENTCO_IS_NULL")])]
    }

    @Test
    void aTextThatIsNotANumberOnANumberField() {
        // Accepted difference (Ruling 3b2b-13: a number field is try_cast to DECIMAL(38,18)): moqui-rules refuses a fact whose
        // number or yes/no field holds text that does not fit (convert#Fact), so the judge cannot run on it; Keep in Spark
        // reads that text as no value. "abc" and "" on a number field and "maybe" on a yes/no field show it.
        List<Map> records = [[amount: "abc"], [amount: ""], [amount: 5, gift: "maybe"], [amount: 5, gift: true]]
        Map both = bothWays([rule("no amount", 20, "NO_AMOUNT", [cmp("01", "amount", "ENTCO_IS_NULL")]),
                             rule("not a gift", 10, "NOT_GIFT", [cmp("01", "gift", "ENTCO_NOT_EQUALS", "true")])], records)
        List<String> moqui = (List<String>) both.moqui
        assertTrue(moqui[0].startsWith(REFUSED) && moqui[0].contains("Value abc does not fit field ${fieldOf.amount}"), moqui[0])
        assertTrue(moqui[1].startsWith(REFUSED) && moqui[1].contains("does not fit field ${fieldOf.amount}"), moqui[1])
        assertTrue(moqui[2].startsWith(REFUSED) && moqui[2].contains("Value maybe does not fit field ${fieldOf.gift}"), moqui[2])
        assertEquals(["NO_AMOUNT", "NO_AMOUNT", "NOT_GIFT", null], outcomes((List<String>) both.spark),
                "Spark reads the text as no value: is-null holds, and so does != for the yes/no")
        assertEquals(moqui[3], both.spark[3], "a fact that fits agrees")
    }

    @Test
    void exponentTextOnANumberFieldIsAnAcceptedDifference() {
        // Accepted difference (Ruling 3b2b-13): Spark's try_cast reads the text "1e2" as 100; moqui-rules' number is digits
        // with one optional sign and point, so it refuses the fact. A JSON number 1e2 is 100 in both.
        Map both = bothWays([rule("a hundred", 10, "HUNDRED", [cmp("01", "amount", "ENTCO_EQUALS", "100")])],
                [[amount: "1e2"], [amount: 1e2G], [amount: "100"]])
        assertEquals(["HUNDRED", "HUNDRED", "HUNDRED"], outcomes((List<String>) both.spark))
        assertTrue(((String) both.moqui[0]).startsWith(REFUSED) && ((String) both.moqui[0]).contains("Value 1e2 does not fit field ${fieldOf.amount}"),
                both.moqui[0] as String)
        assertEquals(both.spark.drop(1), both.moqui.drop(1))
    }

    @Test
    void moreThanTwentyIntegerDigitsIsAnAcceptedDifference() {
        // Accepted difference (Ruling 3b2b-13): DECIMAL(38,18) holds 20 integer digits. moqui-rules reads 21 digits as a
        // number, which is over 5; Spark's try_cast gives no value, which is not.
        Map both = bothWays([rule("over five", 10, "OVER", [cmp("01", "amount", "ENTCO_GREATER", "5")])],
                [[amount: 123456789012345678901G], [amount: 12345678901234567890G], [amount: "-123456789012345678901"]])
        assertEquals([null, "OVER", null], outcomes((List<String>) both.spark))
        assertEquals(["OVER", "OVER", null], outcomes((List<String>) both.moqui))
    }

    // ----------------------------------------------------------------------------------------------------- the two ways

    /**
     * Spark's answers, one per record as the rule's outcome (or null); asserts moqui-rules gives the same rule for every
     * record and refuses none.
     */
    private List<String> parity(List<Map> ruleSpecs, List<Map> records, Map params = [:]) {
        return parityOn(storeDecision(ruleSpecs), records, params)
    }

    /** [spark: reasons by record, moqui: "B:<ruleId>|<outcome>", null, or "REFUSED: ..." by record]. */
    private Map bothWays(List<Map> ruleSpecs, List<Map> records, Map params = [:]) {
        return bothWaysOn(storeDecision(ruleSpecs), records, params)
    }

    private Map bothWaysOn(String ruleSetId, List<Map> records, Map params) {
        File source = recordsFile(ruleSetId, records)
        return [spark: sparkReasons(ruleSetId, source, params, records.size()), moqui: moquiReasons(ruleSetId, source, params)]
    }

    /** parity over a decision already stored. */
    private List<String> parityOn(String ruleSetId, List<Map> records, Map params) {
        Map both = bothWaysOn(ruleSetId, records, params)
        records.eachWithIndex { Map record, int i ->
            assertEquals(both.moqui[i], both.spark[i], "record ${i} ${record} with params ${params}: moqui-rules vs Spark")
        }
        return outcomes((List<String>) both.spark)
    }

    private File recordsFile(String ruleSetId, List<Map> records) {
        File source = new File(workDir, "decision-${ruleSetId}-${decisionCount++}.json")
        source.setText(JsonOutput.toJson([metadata: [:], records: records]), "UTF-8")
        return source
    }

    /** fact field name to record key: generate#PairFactType keyNames.file1, inverted. */
    private Map<String, String> recordKeys() {
        return fieldOf.collectEntries { String key, String name -> [(name): key] } as Map<String, String>
    }

    /** The decision read back from moqui-rules, translated, and run over the records file in Spark. */
    private List<String> sparkReasons(String ruleSetId, File source, Map params, int count) {
        Map translated = KeepLineTranslator.translate(decisionRules(ruleSetId), "acme", fieldKinds(), params as Map<String, Object>, recordKeys())
        assertEquals([], translated.refusals)
        List<Map> rules = (List<Map>) translated.rules
        List<Map> injections = KeepRules.injections(rules)
        File lines = new File(workDir, source.name + JsonRecordLines.SUFFIX)
        assertEquals(count, JsonRecordLines.write(source, "records", lines, injections))
        List<StructField> fields = injections.collect { Map injection ->
            new StructField(injection.column as String, DataTypes.StringType, true, Metadata.empty())
        }
        fields.add(new StructField(KeepRules.ROW_COLUMN, DataTypes.LongType, true, Metadata.empty()))
        Dataset<Row> df = spark.read().schema(new StructType(fields as StructField[])).json(lines.path)
        Map<Long, String> byRow = [:]
        KeepColumnSupport.annotate(df, rules).select(KeepRules.ROW_COLUMN, KeepRules.REASON_COLUMN).collectAsList().each { Row row ->
            byRow.put(row.getLong(0), row.isNullAt(1) ? null : row.getString(1))
        }
        assertEquals(count, byRow.size())
        return (0..<count).collect { byRow.get(it as Long) }
    }

    /** evaluate#RuleSet on one fact per record, built from the same file as the judge builds a pair fact. */
    private List<String> moquiReasons(String ruleSetId, File source, Map params) {
        // Jackson, as JsonRecordStream reads records: Groovy 5's JsonSlurper turns an integer past Long into a wrong negative Long
        List<Map> records = []
        JsonRecordStream.forEachRecord(source, "records") { Map record -> records.add(record) }
        List<String> out = []
        records.eachWithIndex { Map record, int i ->
            Map fact = PairFactSupport.buildFact([compareId: "r" + i, file1: FactText.flat(record), file2: null], sideState, params, [:])
            Map result = underFacadeGrant("moqui.rules.RuleServices.evaluate#RuleSet", [ruleSetId: ruleSetId, facts: [fact]])
            if (result.errors) {
                out.add(REFUSED + result.errors.join("; "))
                return
            }
            List<Map> decisions = (List<Map>) ((Map) result.out).decisions
            assertEquals(1, decisions.size())
            Map first = ((List<Map>) decisions[0].matchedRules)?.find()
            out.add(first == null ? null : "B:${first.ruleId}|${first.outcomeId}".toString())
        }
        return out
    }

    /** A new decision on the pair fact type, its rules stored through store#Rule. */
    private String storeDecision(List<Map> ruleSpecs) {
        String ruleSetId = ok(underFacadeGrant("create#rules.RuleSet", [description: "Keep parity ${decisionCount}".toString(), firstMatchOnly: "Y",
                factTypes: [[factTypeId: factTypeId]], outcomes: ruleSpecs.collect { [outcomeId: it.outcome] }.unique()])).ruleSetId
        ruleSpecs.each { Map spec ->
            ok(underFacadeGrant("moqui.rules.RuleServices.store#Rule", [ruleSetId: ruleSetId, ruleName: spec.name, priority: spec.priority,
                    conditions: ((List<Map>) spec.lines).collect { new LinkedHashMap(it + [factTypeId: factTypeId]) },
                    actions: [[actionTypeId: "RAT_OUTCOME", outcomeId: spec.outcome]]]))
        }
        return ruleSetId
    }

    /** The decision's rules as saved, in the shape KeepLineTranslator.translate takes. */
    private List<Map> decisionRules(String ruleSetId) {
        return rows("rules.RuleSetRule", [ruleSetId: ruleSetId]).collect { def rule ->
            [ruleId: rule.ruleId, ruleName: rule.ruleName, priority: rule.priority,
             outcomeId: rows("rules.RuleSetAction", [ruleId: rule.ruleId]).find()?.outcomeId,
             conditions: rows("rules.RuleSetCondition", [ruleId: rule.ruleId]).collect { it.getMap() }] as Map
        }
    }

    private Map<String, String> fieldKinds() {
        return rows("rules.FactField", [factTypeId: factTypeId]).collectEntries { [(it.fieldName): it.valueTypeEnumId] } as Map<String, String>
    }

    private static List<String> outcomes(List<String> reasons) {
        return reasons.collect { String reason -> reason == null || reason.startsWith(REFUSED) ? reason : reason.substring(reason.indexOf('|') + 1) }
    }

    private static Map rule(String name, int priority, String outcome, List<Map> lines) {
        return [name: name, priority: priority, outcome: outcome, lines: lines]
    }

    private static Map line(String seq, String type, String parent = null) {
        return [conditionSeqId: seq, parentConditionSeqId: parent, sequenceNum: seq as Integer, conditionTypeId: type]
    }

    /** A compare line on a record key (its fact field name looked up) or, with keyed false, on a fact field as named. */
    private Map cmp(String seq, String key, String operator, String value = null, String parent = null, String normalizeText = null,
                    boolean keyed = true) {
        Map out = line(seq, "RCT_COMPARE", parent) + [fieldName: keyed ? fieldOf[key] : key, operatorEnumId: operator, compareValue: value]
        if (keyed) assertTrue(fieldOf.containsKey(key), "record key ${key} is in the pair fact type")
        if (normalizeText != null) out.normalizeText = normalizeText
        return out
    }

    // ----------------------------------------------------------------------------------------------------- harness

    private static Map ok(Map result) {
        assertEquals([], result.errors, "call failed")
        return result.out as Map
    }

    private Map underFacadeGrant(String serviceName, Map params) {
        def harnessFrame = ec.artifactExecution.peek()
        assertEquals("smokeTests", harnessFrame?.name, "test precondition: unexpected artifact stack")
        ec.artifactExecution.pop(harnessFrame)
        ec.artifactExecution.enableAuthz()
        try {
            assertFalse(ec.artifactExecution.authzDisabled, "test precondition: authz must be ON")
            def aei = ec.artifactExecution.push(FACADE_GRANT, ArtifactExecutionInfo.AT_SERVICE, ArtifactExecutionInfo.AUTHZA_ALL, true)
            try {
                return collect { ec.service.sync().name(serviceName).parameters(new HashMap(params)).call() }
            } finally {
                ec.artifactExecution.pop(aei)
            }
        } finally {
            ec.artifactExecution.disableAuthz()
            ec.artifactExecution.push("smokeTests", ArtifactExecutionInfo.AT_OTHER, ArtifactExecutionInfo.AUTHZA_ALL, false)
            ec.artifactExecution.setAnonymousAuthorizedAll()
        }
    }

    private Map collect(Closure<Map> call) {
        ec.message.clearAll()
        Map out = null
        try {
            out = call.call()
        } catch (Exception e) {
            ec.message.addError(e.toString())
        }
        List<String> errors = ec.message.errors.collect { it.toString() } +
                ec.message.validationErrors.collect { it.message?.toString() }
        ec.message.clearAll()
        return [out: out, errors: errors]
    }

    private List rows(String entityName, Map conditions) {
        return ec.entity.find(entityName).condition(conditions).disableAuthz().useCache(false).list()
    }

    private void insert(String entityName, Map fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }

    private void actAs(String tenant) {
        assertTrue(ec.user.internalLoginUser(EDITOR_USERNAME), "could not log in ${EDITOR_USERNAME}")
        ec.user.setPreference(PREF_KEY, tenant)
        TenantAccessSupport.syncUserContext(ec)
        ec.message.clearErrors()
    }
}
