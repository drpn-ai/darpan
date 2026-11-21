package darpan.knowledge

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-OPS-026: resetForTests clears product tier, generations and tenant indexes; idempotent. */
class KnowledgeSupportResetTests {
    @Test
    void resetRestoresInitialStatics() {
        KnowledgeSupport.@GENERATIONS.put('t1', 5L)
        KnowledgeSupport.@product = new KnowledgeSupport.ProductTier(path: '/x')
        KnowledgeSupport.resetForTests()
        assertTrue(KnowledgeSupport.@GENERATIONS.isEmpty())
        assertTrue(KnowledgeSupport.@TENANT_INDEXES.isEmpty())
        assertNull(KnowledgeSupport.@product)
        KnowledgeSupport.resetForTests()
        assertEquals(0, KnowledgeSupport.@GENERATIONS.size())
    }
}
