package darpan.facade.common

import org.junit.jupiter.api.Test

import java.nio.file.Files

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

class DataManagerSupportTests {

    private static def ecReturningFile(File targetFile) {
        return new Expando(resource: new Expando(
                getLocationReference: { String location -> new Expando(getFile: { -> targetFile }) }))
    }

    @Test
    void writeTextCreatesMissingParentDirectoriesAndWrites() {
        File tempRoot = Files.createTempDirectory("dm-support-test").toFile()
        try {
            File target = new File(tempRoot, "reconciliation-runs/RunId/20260702-000000000/file1-api/RunId_file1.jsonl")
            DataManagerSupport.writeText(ecReturningFile(target), "runtime://datamanager/ignored", "line1")
            assertEquals("line1", target.text)
        } finally {
            tempRoot.deleteDir()
        }
    }

    @Test
    void writeTextSurfacesRealCauseWhenParentDirectoryCannotBeCreated() {
        File tempRoot = Files.createTempDirectory("dm-support-test").toFile()
        try {
            File blocker = new File(tempRoot, "file1-api")
            blocker.text = "occupies the directory path segment"
            File target = new File(blocker, "RunId_file1.jsonl")
            IllegalStateException thrown = assertThrows(IllegalStateException, {
                DataManagerSupport.writeText(ecReturningFile(target), "runtime://datamanager/ignored", "line1")
            })
            assertTrue(thrown.message.contains(blocker.absolutePath),
                    "expected failing directory path in message but got: ${thrown.message}")
            assertTrue(thrown.cause instanceof IOException,
                    "expected underlying IOException as cause but got: ${thrown.cause}")
        } finally {
            tempRoot.deleteDir()
        }
    }

    @Test
    void resolveDirectoryFileFailsLoudlyWhenCreationIsBlocked() {
        File tempRoot = Files.createTempDirectory("dm-support-test").toFile()
        try {
            File blocker = new File(tempRoot, "datamanager")
            blocker.text = "occupies the directory path segment"
            def ec = new Expando(
                    resource: new Expando(getLocationReference: { String location -> null }),
                    factory: new Expando(getRuntimePath: { -> tempRoot.absolutePath }))
            IllegalStateException thrown = assertThrows(IllegalStateException, {
                DataManagerSupport.resolveDirectoryFile(ec, "runtime://datamanager/reconciliation-runs/RunId", true)
            })
            assertTrue(thrown.message.contains("datamanager"),
                    "expected failing directory path in message but got: ${thrown.message}")
        } finally {
            tempRoot.deleteDir()
        }
    }

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
