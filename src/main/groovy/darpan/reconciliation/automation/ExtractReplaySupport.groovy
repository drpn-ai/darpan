package darpan.reconciliation.automation

import darpan.common.DarpanEntityConstants
import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantScopedFinder
import darpan.facade.reconciliation.RunObservability
import groovy.transform.CompileDynamic

import java.util.regex.Pattern

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.readField

/**
 * DAR-BE-142. Retry a failed automation window from the extracts it already read. A window that failed AFTER both
 * reads (compare, verify, conclude) used to be re-driven from the top, re-reading every system for nothing: gorjana's
 * month close (2026-10-27) spent 38 minutes reading HotWax for two compares that then failed in 0 s on a key config.
 * A replay hands the new attempt the old files, so the compare and everything after it run again with no API call.
 *
 * Where the files are: the execution's file1/file2DataManagerPath, stamped as each extract ends; else, for attempts
 * made before that stamp existed, the newest extract folder of each side under the execution's run folder
 * (side 1 in {@code <stamp>/}, a parallel side 2 in {@code <stamp>-FILE_2/}). Refused, in words, unless every read
 * step finished and each side resolves to exactly one extract file that is still on disk.
 */
@CompileDynamic
class ExtractReplaySupport {

    static final String SIDE_1 = "FILE_1"
    static final String SIDE_2 = "FILE_2"
    /** formatRunTimestamp: yyyyMMdd-HHmmssSSS. A "-recheck" or "-FILE_2" suffix is another folder. */
    static final Pattern SIDE_1_DIR = ~/^\d{8}-\d{9}$/
    static final Pattern SIDE_2_DIR = ~/^\d{8}-\d{9}-FILE_2$/
    /**
     * Sidecars and outputs written beside an extract, never the extract itself: the OMS exchange manifest, the rows a
     * source filter dropped (ExcludedRecordsSidecar, "<extract>.excluded.json", found by name by the conclude pass, so a
     * replay of the extract carries it), the Keep set-aside artifact (KeepArtifacts: ".keep.json" and ".set-aside.rows"), and
     * compare outputs.
     */
    static final Pattern NOT_AN_EXTRACT = ~/(?i).*(manifest\.json|\.excluded\.json|\.keep\.json|\.set-aside\.rows|[-_.](result|diff)[-_.].*|[-_.](result|diff)\.json)$/
    private static final String WHY = "extract replay: explicit companyUserGroupId condition applied"

    /**
     * The replay for one failed execution: side -> the extractor result a fresh read would have returned
     * (fileLocation, fileName, recordCount, exchangeManifestFileLocation). Throws IllegalStateException, in words,
     * when it cannot be replayed.
     */
    static Map<String, Map<String, Object>> replayResults(def ec, String tenantId, String executionId, boolean twoSided) {
        def execution = TenantScopedFinder.findOneOwnedByTenant(ec, DarpanEntityConstants.RECONCILIATION_AUTOMATION_EXECUTION,
                "automationExecutionId", executionId, tenantId, "extract replay: the execution's owner is checked on the row")
        if (execution == null) throw new IllegalStateException("Automation execution ${executionId} was not found.")
        Map<String, Integer> counts = readCounts(ec, tenantId, readField(execution, "reconciliationRunResultId") as String)
        List<String> sides = twoSided ? [SIDE_1, SIDE_2] : [SIDE_1]
        List<String> unread = sides.findAll { !counts.containsKey(it) }
        if (unread) {
            throw new IllegalStateException("Execution ${executionId} did not finish reading ${unread.join(' and ')}, " +
                    "so there is no complete extract to retry from; run it again instead.")
        }

        Map<String, Map<String, Object>> files = stamped(execution, sides)
        if (files == null) {
            String runLocation = DataManagerSupport.childLocation(DataManagerSupport.resolveReconciliationRunsLocation(ec),
                    DataManagerSupport.safeToken(executionId, "run"))
            Map picked = pickFromRunFolder(DataManagerSupport.resolveDirectoryFile(ec, runLocation, false), runLocation, twoSided)
            if (picked.error) throw new IllegalStateException("Execution ${executionId}: ${picked.error}")
            files = picked.files as Map<String, Map<String, Object>>
        }
        String dataManagerLocation = DataManagerSupport.resolveDataManagerLocation(ec)
        File root = DataManagerSupport.resolveDirectoryFile(ec, dataManagerLocation, false)
        files.each { String side, Map<String, Object> file ->
            Map<String, Object> landed = resolveStamped(file.fileLocation as String, dataManagerLocation, root)
            File onDisk = landed?.file as File
            if (onDisk == null || !onDisk.isFile() || onDisk.length() == 0) {
                throw new IllegalStateException("Execution ${executionId}: the ${side} extract ${file.fileName} is no longer on disk.")
            }
            // a location the compare can open, whichever shape was stamped (DAR-OPS-029 S07a). Only fileLocation: the runner stamps
            // dataManagerPath ?: fileLocation, so a retry stamps the same location form a fresh read does (one shape: DAR-BE-409)
            file.fileLocation = landed.fileLocation
            file.recordCount = counts[side]
            file.dataAvailable = true
            file.replayedFromExecutionId = executionId
        }
        return files
    }

    /**
     * DAR-BE-163. The sides a failed interactive run finished reading: side -> [dataManagerPath, fileName, recordCount].
     * Unlike replayResults, a side that was not read in full, or whose file is gone, is simply left out: the retry reads
     * that side again. So is an uploaded side (sidesReadInFull). The stamped path is data-manager relative and resolved
     * under its root only, so a run row can never point a retry at a file outside it.
     */
    static Map<String, Map<String, Object>> landedSides(def ec, String tenantId, String runResultId, def runRow) {
        Map<String, Integer> counts = readCounts(ec, tenantId, runResultId)
        Set<String> readInFull = sidesReadInFull(counts)
        Map<String, Map<String, Object>> landed = [:]
        [(SIDE_1): "file1", (SIDE_2): "file2"].each { String side, String field ->
            if (!readInFull.contains(side)) return
            Map<String, Object> file = landedFile(ec, runRow, "${field}DataManagerPath", "${field}Name")
            if (file != null) landed[side] = [dataManagerPath: file.dataManagerPath, fileName: file.fileName, recordCount: counts[side]] as Map<String, Object>
        }
        return landed
    }

    /**
     * Pure (DAR-OPS-029 OC106): the sides whose read finished WITH its count. An upload finishes its step with no count
     * (RunExtractSupport.stageTextInput) and a retry never reuses it (only extractApiSource replays a side), so it is not a
     * landed read: a run whose one finished side was an upload has nothing to retry from. Zero records is still a read.
     */
    static Set<String> sidesReadInFull(Map<String, Integer> countsBySide) {
        return ((countsBySide ?: [:]).findAll { String side, Integer count -> count != null }.keySet()) as Set<String>
    }

    static final String RESULT = "RESULT"

    /**
     * DAR-BE-159. What a SUCCEEDED execution landed and still has on disk, read-only, for a backtest: FILE_1 / FILE_2 (the
     * extracts) and RESULT (the concluded result document), each [dataManagerPath, fileName, file]. A file that is gone,
     * empty or outside the data manager root is left out. Empty for an execution that is not the tenant's or did not
     * succeed. The replay above stays failed-only (AutomationExecutionSupport.replayFromExecutionId); nothing here replays.
     */
    static Map<String, Map<String, Object>> landedFilesOfSucceeded(def ec, String tenantId, String executionId) {
        def execution = TenantScopedFinder.findOneOwnedByTenant(ec, DarpanEntityConstants.RECONCILIATION_AUTOMATION_EXECUTION,
                "automationExecutionId", executionId, tenantId, "backtest: the execution's owner is checked on the row")
        if (execution == null || readField(execution, "statusEnumId") != AutomationExecutionSupport.STATUS_SUCCEEDED) return [:]
        Map<String, Map<String, Object>> landed = [:]
        [(SIDE_1): ["file1DataManagerPath", "file1Name"], (SIDE_2): ["file2DataManagerPath", "file2Name"],
         (RESULT): ["resultDataManagerPath", "resultFileName"]].each { String part, List<String> fields ->
            Map<String, Object> file = landedFile(ec, execution, fields[0], fields[1])
            if (file != null) landed[part] = file
        }
        return landed
    }

    /**
     * Pure: each side's newest extract folder under one execution's run folder, and the one extract file in it.
     * Answers [files: [FILE_1: [fileLocation, fileName, exchangeManifestFileLocation?], FILE_2: ...]] or [error: words].
     */
    static Map<String, Object> pickFromRunFolder(File runDir, String runLocation, boolean twoSided) {
        if (runDir == null || !runDir.isDirectory()) return [error: "its run folder is not on disk"]
        List<File> dirs = (runDir.listFiles() ?: []).findAll { it.isDirectory() } as List<File>
        Map<String, Map<String, Object>> files = [:]
        Map<String, Pattern> patterns = twoSided ? [(SIDE_1): SIDE_1_DIR, (SIDE_2): SIDE_2_DIR] : [(SIDE_1): SIDE_1_DIR]
        for (Map.Entry<String, Pattern> entry : patterns) {
            File dir = dirs.findAll { it.name ==~ entry.value }.max { it.name }
            if (dir == null) return [error: "no ${entry.key} extract folder in its run folder"]
            List<File> extracts = (dir.listFiles() ?: []).findAll { it.isFile() && it.name.endsWith(".json") && !(it.name ==~ NOT_AN_EXTRACT) } as List<File>
            if (extracts.size() != 1) {
                return [error: "${entry.key} folder ${dir.name} holds ${extracts.size()} extract files (${extracts*.name.sort().join(', ')}), not one"]
            }
            String dirLocation = DataManagerSupport.childLocation(runLocation, dir.name)
            File manifest = (dir.listFiles() ?: []).find { it.isFile() && it.name.endsWith(".exchange-manifest.json") }
            files[entry.key] = [fileLocation: DataManagerSupport.childLocation(dirLocation, extracts[0].name), fileName: extracts[0].name] +
                    (manifest ? [exchangeManifestFileLocation: DataManagerSupport.childLocation(dirLocation, manifest.name)] : [:])
        }
        return [files: files]
    }

    /** The paths the runner stamped as each extract ended, or null when an attempt predates the stamp. */
    private static Map<String, Map<String, Object>> stamped(def execution, List<String> sides) {
        Map<String, Map<String, Object>> files = [:]
        for (String side : sides) {
            String field = side == SIDE_1 ? "file1" : "file2"
            String location = normalize(readField(execution, "${field}DataManagerPath"))
            if (!location) return null
            files[side] = [fileLocation: location, fileName: normalize(readField(execution, "${field}Name")) ?: location.tokenize("/").last()]
        }
        return files
    }

    /**
     * A row's landed file: [dataManagerPath, fileName, file], or null when the path is blank, outside the data manager root,
     * gone or empty. The path is data-manager relative and resolved under its root only.
     */
    private static Map<String, Object> landedFile(def ec, def row, String pathField, String nameField) {
        String dataManagerLocation = DataManagerSupport.resolveDataManagerLocation(ec)
        Map<String, Object> landed = resolveStamped(normalize(readField(row, pathField)), dataManagerLocation,
                DataManagerSupport.resolveDirectoryFile(ec, dataManagerLocation, false))
        File onDisk = landed?.file as File
        if (onDisk == null || !onDisk.isFile() || onDisk.length() == 0) return null
        return [dataManagerPath: landed.dataManagerPath, fileName: normalize(readField(row, nameField)) ?: onDisk.name, file: onDisk] as Map<String, Object>
    }

    /**
     * Pure (DAR-OPS-029 S07a): a stamped path, data-manager relative ("reconciliation-runs/...", what the runner stamps) or a
     * location under the data manager ("runtime://datamanager/reconciliation-runs/..."), as [dataManagerPath, fileLocation,
     * file]; null when blank or anywhere outside the data manager root. Every reader here goes through it: replayResults used to
     * open a relative stamp as a Moqui location (under the runtime, not the data manager), so a stamped replay never found it.
     */
    static Map<String, Object> resolveStamped(String stamped, String dataManagerLocation, File root) {
        String path = normalize(stamped)
        String location = normalize(dataManagerLocation)
        if (!path || root == null) return null
        if (location && path.startsWith(location + "/")) path = path.substring(location.length() + 1)
        String relative = DataManagerSupport.normalizeRelativePath(path)
        if (!relative) return null
        File file = new File(root, relative)
        if (!file.canonicalPath.startsWith(root.canonicalPath + File.separator)) return null
        return [dataManagerPath: relative, fileLocation: DataManagerSupport.childLocation(location, relative), file: file] as Map<String, Object>
    }

    /** Record counts of the read steps that finished; a side missing here was not read in full. */
    private static Map<String, Integer> readCounts(def ec, String tenantId, String runResultId) {
        if (!runResultId) return [:]
        return countsBySide(TenantScopedFinder.findGlobalUnscoped(ec, "darpan.reconciliation.ReconciliationRunStep", WHY)
                .condition("companyUserGroupId", tenantId).condition("reconciliationRunResultId", runResultId)
                .condition("statusEnumId", RunObservability.STATUS_SUCCESS).useCache(false).list()*.getMap())  // EntityList.findAll casts to Boolean
    }

    /**
     * Pure (DAR-OPS-029 S07a): each side's record count from its finished read steps. A side read twice in one run (beginStep
     * mints a row per attempt) takes its LATEST step, by startedDate then step id, never whichever row the database returned last.
     */
    static Map<String, Integer> countsBySide(List<Map> finishedSteps) {
        Map<String, String> sideOfStage = [(RunObservability.STAGE_EXTRACT_FILE1): SIDE_1, (RunObservability.STAGE_EXTRACT_FILE2): SIDE_2]
        return (finishedSteps ?: [])
                .findAll { sideOfStage[readField(it, "stageCode") as String] != null }
                .sort { Map a, Map b ->
                    String aId = a.reconciliationRunStepId as String, bId = b.reconciliationRunStepId as String
                    // sequenced ids: a shorter id is an older one ("99999" before "100000")
                    (a.startedDate as Comparable) <=> (b.startedDate as Comparable) ?: aId?.length() <=> bId?.length() ?: aId <=> bId
                }
                .collectEntries { [(sideOfStage[readField(it, "stageCode") as String]): readField(it, "recordCount") as Integer] }
    }
}
