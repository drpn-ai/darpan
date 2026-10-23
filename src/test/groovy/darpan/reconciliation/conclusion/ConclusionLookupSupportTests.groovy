package darpan.reconciliation.conclusion

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-118. A conclusion lookup asks another system only about the findings that reach a rule reading what it attaches
 * (Aditi 2026-10-23: "make sure third party data look ups are as minimal as possible"). Siblings are tried in sequence and
 * the first match wins, so a finding reached a lookup rule when its parent matched and it matched nothing earlier there.
 */
class ConclusionLookupSupportTests {

    private static final Map LOOKUP = [name: "orderInNetsuite", subject: "FILE_1", idField: "orderNetsuiteId",
                                       systemEnumId: "NETSUITE_SUITEQL", sourceConfigId: "NS_SO_BACKORDER", keyField: "internalId"]

    private static Map rule(int seq, Integer parent, String field, String bucket = null) {
        return [sequenceNum: seq, parentSequenceNum: parent, conclusionEnumId: "C${seq}".toString(), label: "C${seq}".toString(),
                appliesToBucket: bucket, conditions: [[subject: "FILE_1", fieldExpression: field, operator: "NOT_BLANK"]]]
    }

    // Root 5 reads the lookup, for RULE rows only; root 10 has children 11 (plain), 12 (reads the lookup), 13 (plain).
    private static final List<Map> NODES = ConclusionRuleLogicGenerator.assignNodes([
            rule(5, null, "orderInNetsuite.backordered", "RULE"), rule(10, null, "statusId"),
            rule(11, 10, "a"), rule(12, 10, "orderInNetsuite.internalId") + [conditions: [
                    [subject: "FILE_1", fieldExpression: "statusId", operator: "IN", conditionValues: ["RETURN_COMPLETED"]],
                    [subject: "FILE_1", fieldExpression: "orderInNetsuite.internalId", operator: "NOT_BLANK"]]],
            rule(13, 10, "b"), rule(3, null, "c")])

    private static String id(int seq) { NODES.find { it.sequenceNum == seq }.nodeId }

    private static boolean reaches(Integer reachedSeq, String bucket = "MISSING_FROM_FILE_2", String status = "RETURN_COMPLETED") {
        Map plan = ConclusionLookupSupport.plan(NODES, [LOOKUP])
        Map sides = [FILE_1: [presence: "KEPT", record: [statusId: status], records: [[statusId: status]]]]
        return ConclusionLookupSupport.reaches(plan, "orderInNetsuite", reachedSeq == null ? null : id(reachedSeq), bucket, sides)
    }

    @Test
    void onlyAFindingThatTriedALookupRuleNeedsTheLookup() {
        assertFalse(reaches(3), "concluded by an earlier root: never tried the lookup rules")
        assertTrue(reaches(10), "matched 10 and none of its children: 12 was tried")
        assertFalse(reaches(11), "matched 11 before 12 was tried")
        assertTrue(reaches(13), "12 was tried and failed before 13")
        assertFalse(reaches(13, "MISSING_FROM_FILE_2", "RETURN_REQUESTED"), "12 also needs a completed return: no answer can change it")
        assertTrue(reaches(12), "matched 12 on a blank read: the answer may change it")
        assertFalse(reaches(null), "unexplained: 10 never matched, and root 5 is for RULE rows")
        assertTrue(reaches(null, "RULE"), "an unexplained RULE row tried root 5")
        assertFalse(reaches(3, "RULE"), "root 3 matched before root 5 was tried")
    }

    @Test
    void aLookupIsRefusedUnlessEveryPartIsNamed() {
        assertEquals([LOOKUP], ConclusionLookupSupport.parse(groovy.json.JsonOutput.toJson([LOOKUP])))
        assertEquals([], ConclusionLookupSupport.parse(null))
        assertThrows(IllegalArgumentException) { ConclusionLookupSupport.parse('[{"name":"x"}]') }
        assertThrows(IllegalArgumentException) { ConclusionLookupSupport.parse(groovy.json.JsonOutput.toJson([LOOKUP + [subject: "FILE_3"]])) }
        assertThrows(IllegalArgumentException) { ConclusionLookupSupport.parse(groovy.json.JsonOutput.toJson([LOOKUP + [name: "a.b"]])) }
    }

    @Test
    void theAnswerIsAttachedToEveryRecordOfTheSubjectSideByItsId() {
        Map side = [presence: "KEPT", record: [orderNetsuiteId: "81"], records: [[orderNetsuiteId: "81"], [orderNetsuiteId: "82"]]]
        Map attached = ConclusionLookupSupport.attach(side, LOOKUP, ["81": [internalId: "81", backordered: "2"]])
        assertEquals("2", ((Map) attached.record).orderInNetsuite.backordered)
        assertEquals([[internalId: "81", backordered: "2"], null], ((List<Map>) attached.records)*.orderInNetsuite)
        assertFalse(((Map) side.record).containsKey("orderInNetsuite"), "the side's own records are not changed")
    }
}
