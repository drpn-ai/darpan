package darpan.datasource

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

/** DAR-OPS-026: resetForTests restores both transport factories. */
class DataDefinedExtractResetTests {
    @Test void resetRestoresTransportFactories() {
        Closure original = DataDefinedExtract.transportFactory
        Closure originalRest = DataDefinedExtract.restTransportFactory
        DataDefinedExtract.transportFactory = { Map d, Map c -> "fake" }
        DataDefinedExtract.restTransportFactory = { Map d, Map c -> "fake" }
        DataDefinedExtract.resetForTests()
        assertSame(original, DataDefinedExtract.transportFactory)
        assertSame(originalRest, DataDefinedExtract.restTransportFactory)
        DataDefinedExtract.resetForTests()
        assertSame(original, DataDefinedExtract.transportFactory)
    }
}
