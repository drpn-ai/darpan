package darpan.reconciliation.automation

import darpan.audit.AuditRecheckSupport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test

import java.nio.file.Files

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-192. A side read by the other side's keys (gorjana: HotWax returns read by the return ids NetSuite's credit memos
 * carry, so a memo made weeks after its return was entered still pairs with it) is asked for exactly those keys, never a
 * window, in AuditRecheckSupport.MAX_KEYS batches appended into one extract.
 */
class ReadByOtherSideKeysTests {

    private static File extract(List<Map> records) {
        File f = Files.createTempFile("side", ".json").toFile()
        f.deleteOnExit()
        f.text = JsonOutput.toJson([records: records])
        return f
    }

    private static List<Map> recordsOf(File f) {
        return (List<Map>) ((Map) new JsonSlurper().parse(f)).records
    }

    @Test
    void eachKeyIsAskedOnceInRecordOrderAndLaterBatchesAreAppended() {
        List<Map> memos = (1..1001).collect { [returnId: "M${it}".toString(), amount: it] } +
                [[returnId: "M1", amount: 1], [returnId: " ", amount: 0], [returnId: null, amount: 0]]
        List<List<String>> asked = []
        Map result = AutomationExecutionSupport.readByKeysOf(extract(memos), "returnId", "returnId") { List<String> keys ->
            asked << keys
            File f = extract(keys.collect { [returnId: it, returnCredit: 1] })
            return [result: [fileLocation: f.absolutePath, recordCount: keys.size()], file: f]
        }
        assertEquals([AuditRecheckSupport.MAX_KEYS, 1], asked*.size())
        assertEquals("M1", asked[0][0])
        assertEquals("M1001", asked[1][0])
        assertEquals(1001, result.recordCount)
        List<Map> merged = recordsOf(new File(result.fileLocation as String))
        assertEquals(1001, merged.size())
        assertEquals((1..1001).collect { "M${it}".toString() } as Set, merged*.returnId as Set)
    }

    @Test
    void aSideOneWithNoKeysReadsNothing() {
        boolean called = false
        Map result = AutomationExecutionSupport.readByKeysOf(extract([]), "returnId", "returnId") { List<String> keys -> called = true; [:] }
        assertFalse(called)
        assertEquals([:], result)
        assertEquals([:], AutomationExecutionSupport.readByKeysOf(null, "returnId", "returnId") { List<String> keys -> called = true; [:] })
        assertFalse(called)
    }

    @Test
    void onlyASideWhoseRunOptionsSayItIsReadByTheOtherSidesKeys() {
        assertTrue(AutomationExecutionSupport.readsByOtherSideKeys([safeMetadataJson: JsonOutput.toJson(
                [parameters: [omsRestSourceConfigId: "c", readByOtherSideKeys: true, withReturnCredit: true]])]))
        assertFalse(AutomationExecutionSupport.readsByOtherSideKeys([safeMetadataJson: JsonOutput.toJson([parameters: [omsRestSourceConfigId: "c"]])]))
        assertFalse(AutomationExecutionSupport.readsByOtherSideKeys([safeMetadataJson: JsonOutput.toJson([parameters: [readByOtherSideKeys: "yes"]])]))
        assertFalse(AutomationExecutionSupport.readsByOtherSideKeys([safeMetadataJson: null]))
    }
}
