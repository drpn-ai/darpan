package darpan.facade.common

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull

class DataManagerSupportTests {

    @Test
    void dataManagerLocationDefaultsToRuntimeDataManager() {
        def ec = new Expando(resource: new Expando(properties: [:]))

        assertEquals("runtime://datamanager", DataManagerSupport.resolveDataManagerLocation(ec))
    }

    @Test
    void runArtifactNamesUseRunIdAndOriginalExtension() {
        assertEquals("Order_Id_Run_file1.csv",
                DataManagerSupport.runArtifactFileName("Order Id Run", "file1", "orders.csv"))
        assertEquals("Order_Id_Run_result.json",
                DataManagerSupport.runArtifactFileName("Order Id Run", "result", "result.json"))
    }

    @Test
    void schemaFileNamesStayInsideDataManagerSchemaFolder() {
        assertEquals("Order_Payload.json", DataManagerSupport.schemaFileName("Order Payload"))
        assertEquals("orders.schema.json", DataManagerSupport.schemaFileName("orders.schema.json"))
    }

    @Test
    void relativePathsRejectTraversalAndAbsoluteLocations() {
        assertEquals("reconciliation-runs/OrderId/20260428-120000000/OrderId_result.json",
                DataManagerSupport.normalizeRelativePath("reconciliation-runs/OrderId/20260428-120000000/OrderId_result.json"))
        assertNull(DataManagerSupport.normalizeRelativePath("../OrderId_result.json"))
        assertNull(DataManagerSupport.normalizeRelativePath("/tmp/OrderId_result.json"))
        assertNull(DataManagerSupport.normalizeRelativePath("runtime://datamanager/reconciliation-runs/OrderId_result.json"))
    }
}
