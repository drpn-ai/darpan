package darpan.facade.reconciliation

import darpan.facade.common.DataManagerSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-271 — raw extracts go after 7 days; nothing else does. What could bite: a saved run writes its result
 * into the same folder as its extracts, so a folder-level delete would take results the 15-day job owns.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExtractRetentionPurgeTests {
    private static final String LOCATION_PROPERTY = "darpan.data.manager.location"
    private ExecutionContext ec
    private File tempRoot
    private File runsDir

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "extract-retention-purge")
        // The purge deletes for real: point the data manager at a throwaway folder, never the runtime's own data.
        tempRoot = Files.createTempDirectory("darpan-extract-purge").toFile().canonicalFile
        System.setProperty(LOCATION_PROPERTY, "file:" + tempRoot.absolutePath)
        runsDir = ec.resource.getLocationReference(DataManagerSupport.resolveReconciliationRunsLocation(ec)).getFile().canonicalFile
        assertTrue(runsDir.path.startsWith(tempRoot.path), "refusing to run: the runs folder ${runsDir} is not the temp folder")
        runsDir.mkdirs()
    }

    @AfterAll
    void cleanup() {
        System.clearProperty(LOCATION_PROPERTY)
        tempRoot?.deleteDir()
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @Test
    void onlyOldExtractsAndTheirSidecarsGoResultsAndOtherFoldersStay() {
        long day = 86400000L
        long old = System.currentTimeMillis() - 10L * day
        long fresh = System.currentTimeMillis() - 2L * day
        File oldSide1 = extractFile("PURGE_T1/20261101-101010101/oms-orders-1.json", old)
        File oldManifest = extractFile("PURGE_T1/20261101-101010101/oms-orders-1.exchange-manifest.json", old)
        File oldExcluded = extractFile("PURGE_T1/20261101-101010101/oms-orders-1.excluded.json", old)
        File oldPartial = extractFile("PURGE_T1/20261101-101010101/oms-orders-extract-9.partial", old)
        File oldSide2 = extractFile("PURGE_T1/20261101-101010202-FILE_2/netsuite-orders.json", old)
        File oldRecheck = extractFile("PURGE_T1/20261101-101010303-FILE_1-recheck/oms-orders-recheck.json", old)
        File oldSavedRunExtract = extractFile("RS_PURGE_T2/20261101-101010404/RS_PURGE_T2_file1.csv", old)
        File oldResult = extractFile("RS_PURGE_T2/20261101-101010404/RS_PURGE_T2_result.json", old)
        File freshExtract = extractFile("PURGE_T1/20261111-101010505/oms-orders-2.json", fresh)
        File notAnExtractFolder = extractFile("PURGE_T1/notes/old-notes.json", old)

        Map result = (Map) ec.service.sync().name("reconciliation.ReconciliationGenericServices.purge#ExtractFiles")
                .parameters([retentionDays: 7]).disableAuthz().call()

        List<File> purged = [oldSide1, oldManifest, oldExcluded, oldPartial, oldSide2, oldRecheck, oldSavedRunExtract]
        for (File f : purged) {
            assertFalse(f.exists(), "should be purged: " + f.path)
        }
        assertTrue(oldResult.exists(), "a result file is the 15-day job's, even inside an extract folder")
        assertTrue(freshExtract.exists())
        assertTrue(notAnExtractFolder.exists(), "only extract folders are touched")
        assertFalse(new File(runsDir, "PURGE_T1/20261101-101010202-FILE_2").exists(), "an emptied extract folder is removed")
        assertTrue(new File(runsDir, "RS_PURGE_T2/20261101-101010404").exists(), "a folder still holding a result stays")
        assertTrue(((Number) result.deletedCount).intValue() >= 7, String.valueOf(result.statusMessage))
        assertTrue(((List) result.failedFiles).isEmpty(), String.valueOf(result.failedFiles))
    }

    @Test
    void zeroDaysIsRefusedSoAnExtractBeingWrittenIsNeverPurged() {
        ec.message.clearErrors()
        ec.service.sync().name("reconciliation.ReconciliationGenericServices.purge#ExtractFiles")
                .parameters([retentionDays: 0]).disableAuthz().call()
        assertTrue(ec.message.hasError())
        ec.message.clearErrors()
    }

    @Test
    void theDownloadIsOfferedOnlyWhileTheExtractIsOnDisk() {
        File present = extractFile("PURGE_T3/20261112-101010101/oms-orders-3.json", System.currentTimeMillis())
        String presentPath = "reconciliation-runs/PURGE_T3/20261112-101010101/oms-orders-3.json"
        String gonePath = "reconciliation-runs/PURGE_T3/20261101-101010101/oms-orders-gone.json"
        Map withFile = OutputDescriptorSupport.sourceFileDescriptor(ec, [file1DataManagerPath: presentPath, file1Name: "oms-orders-3.json"], "file1", "OMS")
        Map withoutFile = OutputDescriptorSupport.sourceFileDescriptor(ec, [file1DataManagerPath: gonePath, file1Name: "oms-orders-gone.json"], "file1", "OMS")
        assertTrue(present.exists())
        assertEquals(true, withFile.canDownload)
        assertEquals(false, withoutFile.canDownload)
    }

    private File extractFile(String relative, long lastModified) {
        File f = new File(runsDir, relative)
        f.parentFile.mkdirs()
        f.text = "{}"
        f.setLastModified(lastModified)
        return f
    }
}
