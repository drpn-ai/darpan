package darpan.reconciliation.support

import org.junit.jupiter.api.Test

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
    void oneWithoutAFullPkAppliesEveryConditionAndRefusesTwoMatches() {
        assertEquals("A room", chatSpaces().condition("companyUserGroupId", "TENANT_A").one()?.name)
        assertThrows(IllegalStateException) { chatSpaces().condition("companyUserGroupId", "TENANT_B").one() }
    }

    @Test
    void anEmptyPkValueFindsNothing() {
        assertNull(chatSpaces().condition("chatSpaceId", "").condition("companyUserGroupId", "TENANT_A").one())
        assertNull(chatSpaces().condition("chatSpaceId", null).one())
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
