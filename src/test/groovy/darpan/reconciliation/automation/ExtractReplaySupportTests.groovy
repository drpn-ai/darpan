package darpan.reconciliation.automation

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.sql.Timestamp

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
        // Prod gorjana 101796: the OMS items extract sits beside the rows its unitState filter dropped.
        write("20261027-152223914", "oms-items.json", "oms-items.excluded.json", "RUN1-result.json", "RUN1-diff.json", "x.manifest.json")

        Map picked = ExtractReplaySupport.pickFromRunFolder(runDir, RUN, false)

        assertEquals("oms-items.json", picked.files.FILE_1.fileName, picked.error as String)
    }

    @Test
    void aReplayNeverTakesKeepArtifactsForAnExtract() {
        // Plan 3b-2b: the Keep summary and the set-aside rows sit beside the extract and are found by name.
        write("20261027-152223914", "acme-orders.json", "acme-orders.keep.json", "acme-orders.set-aside.rows")

        Map picked = ExtractReplaySupport.pickFromRunFolder(runDir, RUN, false)

        assertEquals("acme-orders.json", picked.files.FILE_1.fileName, picked.error as String)
    }

    /** DAR-OPS-029 S07a QA: the runner stamps a relative path; a replay opened it as a Moqui location and never found it. */
    @Test
    void aStampedPathResolvesUnderTheDataManagerWhetherRelativeOrALocation() {
        String dm = "runtime://datamanager"
        String rel = "reconciliation-runs/101793/20261027-152223914/oms.json"
        write("reconciliation-runs/101793/20261027-152223914", "oms.json")

        Map relative = ExtractReplaySupport.resolveStamped(rel, dm, runDir)
        assertEquals(rel, relative.dataManagerPath)
        assertEquals("${dm}/${rel}".toString(), relative.fileLocation)
        assertTrue((relative.file as File).isFile())
        assertEquals(rel, ExtractReplaySupport.resolveStamped("${dm}/${rel}".toString(), dm, runDir).dataManagerPath)

        assertNull(ExtractReplaySupport.resolveStamped("../outside.json", dm, runDir))
        assertNull(ExtractReplaySupport.resolveStamped("runtime://elsewhere/x.json", dm, runDir))
        assertNull(ExtractReplaySupport.resolveStamped("/etc/passwd", dm, runDir))
        assertNull(ExtractReplaySupport.resolveStamped(" ", dm, runDir))
    }

    @Test
    void aSideReadTwiceCountsItsLatestReadWhateverOrderTheRowsCameIn() {
        // DAR-OPS-029 S07a: beginStep mints a step per attempt, and the read had no order, so the count was the last row returned.
        Timestamp early = Timestamp.valueOf("2026-10-27 15:00:00"), late = Timestamp.valueOf("2026-10-27 15:40:00")
        List<Map> steps = [
                [reconciliationRunStepId: "100002", stageCode: "EXTRACT_FILE1", startedDate: late, recordCount: 412],
                [reconciliationRunStepId: "100001", stageCode: "EXTRACT_FILE1", startedDate: early, recordCount: 9],
                [reconciliationRunStepId: "100003", stageCode: "EXTRACT_FILE2", startedDate: early, recordCount: 380],
                [reconciliationRunStepId: "100004", stageCode: "COMPARE", startedDate: late, recordCount: 1],
        ]
        assertEquals([FILE_1: 412, FILE_2: 380], ExtractReplaySupport.countsBySide(steps))
        assertEquals([FILE_1: 412, FILE_2: 380], ExtractReplaySupport.countsBySide(steps.reverse()))
        // the same start time: the later sequenced id, "100000" after "99999"
        assertEquals([FILE_1: 7], ExtractReplaySupport.countsBySide([
                [reconciliationRunStepId: "100000", stageCode: "EXTRACT_FILE1", startedDate: early, recordCount: 7],
                [reconciliationRunStepId: "99999", stageCode: "EXTRACT_FILE1", startedDate: early, recordCount: 3]]))
        assertEquals([:], ExtractReplaySupport.countsBySide([]))
    }

    @Test
    void anUploadedSideIsNotALandedReadSoARetryNeverResumesFromIt() {
        // DAR-OPS-029 OC106: an upload records a finished step with no count (RunExtractSupport.stageTextInput) and a retry
        // never reuses it (only extractApiSource replays a side), so a run whose one finished side was an upload has nothing
        // to retry from. Only a read with its count is landed; zero records is still a read.
        Timestamp at = Timestamp.valueOf("2026-10-27 15:00:00")
        Map<String, Integer> counts = ExtractReplaySupport.countsBySide([
                [reconciliationRunStepId: "1", stageCode: "EXTRACT_FILE1", startedDate: at, recordCount: null],
                [reconciliationRunStepId: "2", stageCode: "EXTRACT_FILE2", startedDate: at, recordCount: 0]])
        assertEquals([ExtractReplaySupport.SIDE_2] as Set, ExtractReplaySupport.sidesReadInFull(counts) as Set)
        assertEquals([] as Set, ExtractReplaySupport.sidesReadInFull([FILE_1: null]) as Set, "an upload alone: nothing landed")
        assertEquals([] as Set, ExtractReplaySupport.sidesReadInFull([:]) as Set)
    }

}
