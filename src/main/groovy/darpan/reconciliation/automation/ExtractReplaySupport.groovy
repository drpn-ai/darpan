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
    /** Sidecars and outputs written beside an extract, never the extract itself. */
    static final Pattern NOT_AN_EXTRACT = ~/(?i).*(manifest\.json|[-_.](result|diff)[-_.].*|[-_.](result|diff)\.json)$/
    private static final String WHY = "extract replay: explicit companyUserGroupId condition applied"

    /**
     * The replay for one failed execution: side -> the extractor result a fresh read would have returned
     * (fileLocation, fileName, recordCount, exchangeManifestFileLocation). Throws IllegalStateException, in words,
     * when it cannot be replayed.
     */
    static Map<String, Map<String, Object>> replayResults(def ec, String tenantId, String executionId, boolean twoSided) {
        def execution = TenantScopedFinder.findGlobalUnscoped(ec, DarpanEntityConstants.RECONCILIATION_AUTOMATION_EXECUTION, WHY)
                .condition("companyUserGroupId", tenantId).condition("automationExecutionId", executionId).useCache(false).one()
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
        files.each { String side, Map<String, Object> file ->
            File onDisk = DataManagerSupport.resolveDirectoryFile(ec, file.fileLocation as String, false)
            if (onDisk == null || !onDisk.isFile() || onDisk.length() == 0) {
                throw new IllegalStateException("Execution ${executionId}: the ${side} extract ${file.fileName} is no longer on disk.")
            }
            file.recordCount = counts[side]
            file.dataAvailable = true
            file.replayedFromExecutionId = executionId
        }
        return files
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

    /** Record counts of the read steps that finished; a side missing here was not read in full. */
    private static Map<String, Integer> readCounts(def ec, String tenantId, String runResultId) {
        if (!runResultId) return [:]
        Map<String, String> sideOfStage = [(RunObservability.STAGE_EXTRACT_FILE1): SIDE_1, (RunObservability.STAGE_EXTRACT_FILE2): SIDE_2]
        return TenantScopedFinder.findGlobalUnscoped(ec, "darpan.reconciliation.ReconciliationRunStep", WHY)
                .condition("companyUserGroupId", tenantId).condition("reconciliationRunResultId", runResultId)
                .condition("statusEnumId", RunObservability.STATUS_SUCCESS).useCache(false).list()*.getMap()   // EntityList.findAll casts to Boolean
                .findAll { sideOfStage[readField(it, "stageCode") as String] != null }
                .collectEntries { [(sideOfStage[readField(it, "stageCode") as String]): readField(it, "recordCount") as Integer] }
    }
}
