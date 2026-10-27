package darpan.reconciliation.automation

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-142. Finding a failed window's extracts in its run folder, for attempts made before the runner stamped them on
 * the execution (gorjana's month close of 2026-10-27, executions 101793 and 101796). Laid out as the runner writes it:
 * side 1 in <stamp>/, a parallel side 2 in <stamp>-FILE_2/, sidecars beside each extract.
 */
class ExtractReplaySupportTests {

    private static final String RUN = "runtime://datamanager/reconciliation-runs/101793"

    @TempDir
    File runDir

    private void write(String dir, String... names) {
        File folder = new File(runDir, dir)
        folder.mkdirs()
        names.each { new File(folder, it).text = "{}" }
    }

    @Test
    void picksEachSidesOneExtractAndItsExchangeManifest() {
        write("20261027-152223914", "shopify-orders-1.json", "shopify-orders-1.jsonl")
        write("20261027-152223912-FILE_2", "oms-orders-1.json", "oms-orders-1.exchange-manifest.json")

        Map picked = ExtractReplaySupport.pickFromRunFolder(runDir, RUN, true)

        assertNull(picked.error, picked.error as String)
        assertEquals("${RUN}/20261027-152223914/shopify-orders-1.json".toString(), picked.files.FILE_1.fileLocation)
        assertEquals("${RUN}/20261027-152223912-FILE_2/oms-orders-1.json".toString(), picked.files.FILE_2.fileLocation)
        assertEquals("${RUN}/20261027-152223912-FILE_2/oms-orders-1.exchange-manifest.json".toString(),
                picked.files.FILE_2.exchangeManifestFileLocation)
        assertNull(picked.files.FILE_1.exchangeManifestFileLocation)
    }

    @Test
    void takesTheNewestFolderAndNeverARecheckFolder() {
        write("20261027-090000000", "old.json")
        write("20261027-152223914", "new.json")
        write("20261027-160000000-FILE_1-recheck", "recheck.json")

        Map picked = ExtractReplaySupport.pickFromRunFolder(runDir, RUN, false)

        assertEquals("new.json", picked.files.FILE_1.fileName)
        assertEquals(["FILE_1"], picked.files.keySet() as List)
    }

    @Test
    void refusesWhenASideIsMissingOrAmbiguous() {
        write("20261027-152223914", "a.json", "b.json")
        Map ambiguous = ExtractReplaySupport.pickFromRunFolder(runDir, RUN, false)
        assertTrue((ambiguous.error as String).contains("holds 2 extract files (a.json, b.json)"), ambiguous.error as String)

        Map noSide2 = ExtractReplaySupport.pickFromRunFolder(runDir, RUN, true)
        assertTrue((noSide2.error as String).contains("FILE_1") || (noSide2.error as String).contains("FILE_2"), noSide2.error as String)

        assertTrue((ExtractReplaySupport.pickFromRunFolder(new File(runDir, "absent"), RUN, false).error as String).contains("not on disk"))
    }

    @Test
    void outputsAndSidecarsAreNotExtracts() {
        write("20261027-152223914", "oms-items.json", "RUN1-result.json", "RUN1-diff.json", "x.manifest.json")

        Map picked = ExtractReplaySupport.pickFromRunFolder(runDir, RUN, false)

        assertEquals("oms-items.json", picked.files.FILE_1.fileName, picked.error as String)
    }
}
