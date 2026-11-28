package darpan.reconciliation.support

import org.junit.jupiter.api.Test
import org.moqui.entity.EntityCondition

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows

/** DAR-OPS-029 S39: the shared fake finder answers as Moqui's EntityFind does (unit pool, no Moqui). */
class FakeEntityFindTests {
    private static final List<Map> ROWS = [
            [chatSpaceId: "CS_A", companyUserGroupId: "TENANT_A", name: "A room"],
            [chatSpaceId: "CS_B", companyUserGroupId: "TENANT_B", name: "B room"],
            [chatSpaceId: "CS_B2", companyUserGroupId: "TENANT_B", name: "B room two"],
    ]

    private static FakeEntityFind chatSpaces() { new FakeEntityFind(["chatSpaceId"], ROWS) }

    // The OC66 shape: a tenant condition beside a full PK does NOT scope one(); Moqui keeps only the PK.
    @Test
    void oneWithAFullPkIgnoresATenantConditionBesideIt() {
        Map row = chatSpaces().condition("chatSpaceId", "CS_B").condition("companyUserGroupId", "TENANT_A").one()
        assertEquals("B room", row?.name, "a foreign row comes back: the tenant condition beside the PK is dropped")
        assertEquals([chatSpaceId: "CS_B"], chatSpaces().condition([chatSpaceId: "CS_B", companyUserGroupId: "TENANT_A"]).oneConditions())
    }

    @Test
    void listAndCountApplyEveryCondition() {
        FakeEntityFind f = chatSpaces().condition("chatSpaceId", "CS_B").condition("companyUserGroupId", "TENANT_A")
        assertEquals([], f.list())
        assertEquals(0L, f.count())
        assertEquals(2L, chatSpaces().condition("companyUserGroupId", "TENANT_B").count())
    }

    @Test
    void oneWithoutAFullPkAppliesEveryConditionAndTakesTheFirstOfSeveral() {
        assertEquals("A room", chatSpaces().condition("companyUserGroupId", "TENANT_A").one()?.name)
        assertEquals("B room", chatSpaces().condition("companyUserGroupId", "TENANT_B").one()?.name, "Moqui returns the first row, it does not throw")
        assertEquals("B room two", chatSpaces().condition("companyUserGroupId", "TENANT_B").orderBy("-name").one()?.name)
    }

    @Test
    void anEmptyPkValueFindsNothing() {
        assertNull(chatSpaces().condition("chatSpaceId", "").condition("companyUserGroupId", "TENANT_A").one())
        assertNull(chatSpaces().condition("chatSpaceId", null).one())
    }

    @Test
    void oneWithNoConditionFindsNothing() {
        assertNull(new FakeEntityFind(["chatSpaceId"], [ROWS[0]]).one(), "Moqui's one() returns null with no condition")
    }

    // S99-200: Moqui drops only extra EQUALS conditions; a date or IN condition stays in the where clause.
    @Test
    void anOverConstrainedOneStillAppliesNonEqualityConditions() {
        Timestamp now = new Timestamp(1_000_000L)
        List<Map> rows = [[id: "M1", tenant: "T1", fromDate: new Timestamp(10L), thruDate: new Timestamp(500L)]]
        assertNull(new FakeEntityFind(["id"], rows).condition("id", "M1").condition("tenant", "T2")
                .conditionDate("fromDate", "thruDate", now).one(), "an expired row is not found by PK")
        assertNull(new FakeEntityFind(["id"], rows).condition("id", "M1").condition("tenant", "IN", ["T2"]).one())
        assertEquals("M1", new FakeEntityFind(["id"], rows).condition("id", "M1").condition("tenant", "T2").one()?.id)
    }

    @Test
    void conditionDateKeepsOpenAndCurrentRows() {
        Timestamp at = new Timestamp(1000L)
        List<Map> rows = [
                [id: "open", fromDate: null, thruDate: null],
                [id: "current", fromDate: new Timestamp(1000L), thruDate: new Timestamp(2000L)],
                [id: "future", fromDate: new Timestamp(1001L), thruDate: null],
                [id: "ended", fromDate: null, thruDate: new Timestamp(1000L)],
        ]
        assertEquals(["open", "current"], new FakeEntityFind(["id"], rows).conditionDate("fromDate", "thruDate", at).list()*.id)
        assertEquals(["a"], new FakeEntityFind(["id"], [[id: "a", validFrom: null, validThru: null]])
                .conditionDate("validFrom", "validThru", null).list()*.id, "a null moment means now")
    }

    @Test
    void threeArgumentConditionsFollowSqlAndRefuseUnknownOperators() {
        List<Map> rows = [[id: "a", n: 1], [id: "b", n: 2], [id: "c", n: null]]
        FakeEntityFind f = new FakeEntityFind(["id"], rows)
        assertEquals(["a", "b"], f.condition("id", "in", ["a", "b", "z"]).list()*.id)
        assertEquals(["b"], new FakeEntityFind(["id"], rows).condition("n", EntityCondition.ComparisonOperator.GREATER_THAN, 1).list()*.id)
        assertEquals(["a"], new FakeEntityFind(["id"], rows).condition("n", "not-equals", 2).list()*.id, "a NULL column never compares")
        assertEquals([], new FakeEntityFind(["id"], rows).condition("id", EntityCondition.IN, []).list(), "IN of nothing matches nothing")
        assertEquals(["c"], new FakeEntityFind(["id"], rows).condition("n", "equals", null).list()*.id)
        assertThrows(UnsupportedOperationException) { new FakeEntityFind(["id"], rows).condition("id", "like", "a%") }
    }

    @Test
    void listSortsPagesAndCountIgnoresPaging() {
        List<Map> rows = [[id: "a", n: 2], [id: "b", n: null], [id: "c", n: 1], [id: "d", n: 2]]
        assertEquals(["d", "a", "c", "b"], new FakeEntityFind(["id"], rows).orderBy(["-n", "-id"]).list()*.id)
        assertEquals(["b", "c", "a", "d"], new FakeEntityFind(["id"], rows).orderBy("n,id").list()*.id, "NULL sorts lowest")
        assertEquals(["c", "a"], new FakeEntityFind(["id"], rows).orderBy("n ASC").offset(1).limit(2).list()*.id)
        assertEquals(4L, new FakeEntityFind(["id"], rows).limit(1).count())
    }

    @Test
    void updateAllAndDeleteAllChangeTheSharedRows() {
        List<Map> rows = [[id: "a", t: "T1"], [id: "b", t: "T2"], [id: "c", t: "T1"]]
        assertEquals(2L, new FakeEntityFind(["id"], rows).condition("t", "T1").updateAll([t: "T9"]))
        assertEquals(["T9", "T2", "T9"], rows*.t)
        assertEquals(1L, new FakeEntityFind(["id"], rows).condition("id", "b").deleteAll())
        assertEquals(["a", "c"], rows*.id)
    }

    @Test
    void theFacadeIssuesAFreshRecordedFinderPerFindAndRefusesUndeclaredTables() {
        FakeEntityFacade entity = new FakeEntityFacade().table("Chat", ["chatSpaceId"], new ArrayList<Map>(ROWS))
        assertEquals(1L, entity.find("Chat").condition("companyUserGroupId", "TENANT_A").disableAuthz().count())
        assertEquals(3L, entity.find("Chat").count(), "the first find's condition does not narrow the second")
        assertEquals([true, false], entity.issuedFor("Chat")*.authzDisabled)
        entity.find("Chat").condition("chatSpaceId", "CS_A").deleteAll()
        assertEquals(2, entity.rows("Chat").size())
        assertThrows(IllegalStateException) { entity.find("Undeclared") }
    }

    @Test
    void aCompositePkMustBeWholeToOverrideOtherConditions() {
        List<Map> rows = [[a: "1", b: "x", tenant: "T1"], [a: "1", b: "y", tenant: "T2"]]
        // Half a PK is not a full PK: every condition applies.
        assertEquals("y", new FakeEntityFind(["a", "b"], rows).condition([a: "1", tenant: "T2"]).one()?.b)
        // The whole PK wins over the tenant condition.
        assertEquals("T1", new FakeEntityFind(["a", "b"], rows).condition([a: "1", b: "x", tenant: "T2"]).one()?.tenant)
    }
}
