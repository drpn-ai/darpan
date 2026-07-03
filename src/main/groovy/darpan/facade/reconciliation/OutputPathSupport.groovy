package darpan.facade.reconciliation

import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport

import static darpan.common.ValueSupport.fileNameFromPath
import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.sanitizePathFileName

/**
 * Path, artifact, and file resolution plus safety checks and format/name helpers for
 * reconciliation generated outputs. Extracted from {@link ReconciliationOutputSupport}
 * (MACH P1 decomposition) with zero behavior change — that class remains the facade
 * orchestrator (and keeps thin static delegates for external callers), while tenant/ownership
 * gating (e.g. {@code resolveRunResultForArtifactPath}) also stays there.
 */
class OutputPathSupport {

    static boolean isSupportedOutputFile(String fileName) {
        String lower = sourceFormatForFile(fileName)
        return lower == "json" || lower == "csv"
    }

    static boolean isGeneratedResultFile(String fileName) {
        String normalized = fileName?.toLowerCase() ?: ""
        String basename = normalized.tokenize("/\\") ? normalized.tokenize("/\\").last() : normalized
        int extensionIndex = basename.lastIndexOf(".")
        String nameRoot = extensionIndex > 0 ? basename.substring(0, extensionIndex) : basename
        return isSupportedOutputFile(basename) && (nameRoot == "result" || nameRoot.endsWith("_result") || nameRoot.endsWith("-result"))
    }

    static String sourceFormatForFile(String fileName) {
        String normalized = fileName?.toLowerCase() ?: ""
        if (normalized.endsWith(".csv")) return "csv"
        if (normalized.endsWith(".json")) return "json"
        return ""
    }

    static boolean isSafeOutputPath(Object rawFileName) {
        String safePath = DataManagerSupport.normalizeRelativePath(rawFileName)
        if (safePath == null || !isSupportedOutputFile(safePath)) return false
        if (safePath.contains("/")) return isGeneratedResultFile(safePath)
        return true
    }

    static boolean isSafeReadableArtifactPath(def ec, Object rawFileName) {
        String safePath = normalizeDataManagerRelativePath(ec, rawFileName)
        if (safePath == null || !isSupportedOutputFile(safePath)) return false
        if (!safePath.contains("/")) return isSafeOutputPath(safePath)
        if (isSafeOutputPath(safePath)) return true
        return ReconciliationOutputSupport.resolveRunResultForArtifactPath(ec, safePath) != null ||
                resolveSourceArtifactResultFile(ec, safePath) != null
    }

    static File resolveGeneratedOutputFile(def ec, Object rawFileName) {
        String safePath = normalizeDataManagerRelativePath(ec, rawFileName)
        if (!safePath) return null

        File dataManagerFile = DataManagerSupport.resolveDataManagerFile(ec, safePath, false)
        if (dataManagerFile?.exists() && dataManagerFile.isFile() && isGeneratedResultFile(dataManagerFile.name)) {
            return dataManagerFile
        }

        if (!safePath.contains("/")) {
            File legacyOutputDir = ec?.resource?.getLocationReference(TenantAccessSupport.resolveGenericOutputLocation(ec))?.getFile()
            File legacyFile = legacyOutputDir != null ? new File(legacyOutputDir, safePath) : null
            if (legacyFile?.exists() && legacyFile.isFile() && isSupportedOutputFile(legacyFile.name)) return legacyFile
        }

        return null
    }

    static File resolveGeneratedOutputArtifactFile(def ec, Object rawFileName) {
        File resultFile = resolveGeneratedOutputFile(ec, rawFileName)
        if (resultFile != null) return resultFile

        String safePath = normalizeDataManagerRelativePath(ec, rawFileName)
        if (!safePath?.contains("/") || !isSupportedOutputFile(safePath)) return null
        if (ReconciliationOutputSupport.resolveRunResultForArtifactPath(ec, safePath) == null &&
                resolveSourceArtifactResultFile(ec, safePath) == null) return null

        File dataManagerFile = DataManagerSupport.resolveDataManagerFile(ec, safePath, false)
        return dataManagerFile?.exists() && dataManagerFile.isFile() ? dataManagerFile : null
    }

    protected static List<String> dataManagerPathCandidates(def ec, Object rawPath) {
        String relativePath = normalizeDataManagerRelativePath(ec, rawPath)
        if (!relativePath?.contains("/")) return []

        Set<String> candidates = [relativePath] as LinkedHashSet
        dataManagerLocationPrefixes(ec).each { String location ->
            candidates.add(DataManagerSupport.childLocation(location, relativePath))
        }
        candidates.add("/datamanager/${relativePath}".toString())
        candidates.add("/data-manager/${relativePath}".toString())
        return candidates as List<String>
    }

    protected static File resolveSourceArtifactResultFile(def ec, Object rawFileName) {
        String safePath = normalizeDataManagerRelativePath(ec, rawFileName)
        if (!safePath?.contains("/") || !isSupportedOutputFile(safePath) || isGeneratedResultFile(safePath)) return null

        File sourceFile = DataManagerSupport.resolveDataManagerFile(ec, safePath, false)
        if (sourceFile == null || !sourceFile.exists() || !sourceFile.isFile()) return null

        String runFolderPath = resolveSourceArtifactRunFolderPath(safePath)
        if (!runFolderPath) return null

        File runFolder = DataManagerSupport.resolveDataManagerFile(ec, runFolderPath, false)
        if (runFolder == null || !runFolder.exists() || !runFolder.isDirectory()) return null

        List<File> resultFiles = (runFolder.listFiles() ?: [] as File[])
                .findAll { File file -> file.isFile() && isGeneratedResultFile(file.name) }
                .sort { File file -> file.name.toLowerCase() }
        for (File resultFile : resultFiles) {
            String resultPath = DataManagerSupport.relativeDataManagerPath(ec, resultFile)
            Map<String, Object> sourceDetails =
                    OutputDescriptorSupport.buildGeneratedOutputSourceDetailsFromArtifactFolder(ec, resultPath, OutputDescriptorSupport.parseOutputDocument(resultFile))
            List<Map<String, Object>> files = sourceDetails?.files instanceof List ?
                    (List<Map<String, Object>>) sourceDetails.files : []
            if (files.any { Map<String, Object> fileDescriptor -> normalizeDataManagerRelativePath(ec, fileDescriptor.filePath) == safePath }) {
                return resultFile
            }
        }
        return null
    }

    protected static String resolveSourceArtifactRunFolderPath(String safePath) {
        String parent = parentPath(safePath)
        if (!parent) return ""

        String parentName = fileNameFromPath(parent)?.toLowerCase()
        if (parentName in ["file1", "file2", "file1-api", "file2-api"]) return parentPath(parent)
        return parent
    }

    protected static String normalizeDataManagerRelativePath(def ec, Object rawPath) {
        String normalized = normalize(rawPath)?.replace("\\", "/")
        if (!normalized) return null

        String relativePath = DataManagerSupport.normalizeRelativePath(normalized)
        if (relativePath) return relativePath

        for (String location : dataManagerLocationPrefixes(ec)) {
            if (normalized.startsWith(location + "/")) {
                return DataManagerSupport.normalizeRelativePath(normalized.substring(location.length() + 1))
            }
        }

        for (String location : ["/datamanager", "/data-manager"]) {
            if (normalized.startsWith(location + "/")) {
                return DataManagerSupport.normalizeRelativePath(normalized.substring(location.length() + 1))
            }
        }

        if (normalized.contains("://")) return null

        File root = DataManagerSupport.resolveDirectoryFile(ec, DataManagerSupport.resolveDataManagerLocation(ec), false)
        if (root == null) return null

        try {
            def rootPath = root.canonicalFile.toPath()
            def candidatePath = new File(normalized).canonicalFile.toPath()
            if (!candidatePath.startsWith(rootPath)) return null
            return rootPath.relativize(candidatePath).toString().replace(File.separator, "/")
        } catch (Exception ignored) {
            return null
        }
    }

    protected static List<String> dataManagerLocationPrefixes(def ec) {
        Set<String> locations = [] as LinkedHashSet
        [
                DataManagerSupport.resolveDataManagerLocation(ec),
                DataManagerSupport.DEFAULT_DATA_MANAGER_LOCATION,
                "runtime://data-manager",
        ].each { Object rawLocation ->
            String location = trimTrailingSlashes(normalize(rawLocation))
            if (location) locations.add(location)
        }
        return locations as List<String>
    }

    protected static String trimTrailingSlashes(String rawValue) {
        String value = normalize(rawValue)
        while (value?.endsWith("/") && value.length() > 1) {
            value = value.substring(0, value.length() - 1)
        }
        return value
    }

    // The path segment immediately under reconciliation-runs/ is the run-id token used to name the run
    // folder (see DataManagerSupport.resolveReconciliationRunLocation).
    protected static String extractRunFolderToken(String safePath) {
        if (!safePath) return null
        List<String> segments = safePath.tokenize("/")
        int idx = segments.indexOf(DataManagerSupport.RECONCILIATION_RUNS_PATH)
        return (idx >= 0 && idx + 1 < segments.size()) ? segments[idx + 1] : null
    }

    protected static File resolveSourceArtifactFile(def ec, String runFolderPath, String side) {
        List<Map<String, Object>> directories = [
                [path: DataManagerSupport.childLocation(runFolderPath, "${side}-api"), requireSideName: false],
                [path: DataManagerSupport.childLocation(runFolderPath, side), requireSideName: false],
                [path: runFolderPath, requireSideName: true],
        ]

        for (Map<String, Object> directoryEntry : directories) {
            String directoryPath = directoryEntry.path as String
            File directory = DataManagerSupport.resolveDataManagerFile(ec, directoryPath, false)
            if (directory == null || !directory.exists() || !directory.isDirectory()) continue

            List<File> candidates = (directory.listFiles() ?: [] as File[])
                    .findAll { File file ->
                        file.isFile() &&
                                isSupportedOutputFile(file.name) &&
                                !isGeneratedResultFile(file.name) &&
                                (directoryEntry.requireSideName != true || file.name.toLowerCase().contains(side.toLowerCase()))
                    }
                    .sort { File file -> sourceArtifactSortKey(file, side) }
            if (candidates) return candidates.first()
        }
        return null
    }

    protected static String sourceArtifactSortKey(File file, String side) {
        String name = file.name.toLowerCase()
        String sideToken = side.toLowerCase()
        String extensionRank = name.endsWith(".json") ? "0" : name.endsWith(".csv") ? "1" : "9"
        String sideRank = name.contains("_${sideToken}.") || name.contains("-${sideToken}.") ? "0" :
                name.contains(sideToken) ? "1" : "2"
        return "${extensionRank}:${sideRank}:${name}"
    }

    protected static String parentPath(Object rawPath) {
        String safePath = normalizeDataManagerRelativePath(null, rawPath)
        if (!safePath?.contains("/")) return ""
        return safePath.substring(0, safePath.lastIndexOf("/"))
    }

    protected static String sourceArtifactDisplayName(def ec, Object rawPath) {
        String safePath = normalizeDataManagerRelativePath(ec, rawPath)
        def runResult = ReconciliationOutputSupport.resolveRunResultForArtifactPath(ec, safePath)
        if (runResult == null) return ""
        if (safePath == normalizeDataManagerRelativePath(ec, runResult.file1DataManagerPath)) {
            return normalize(runResult.file1Name)
        }
        if (safePath == normalizeDataManagerRelativePath(ec, runResult.file2DataManagerPath)) {
            return normalize(runResult.file2Name)
        }
        return ""
    }

    static String sanitizeUploadFileName(String rawName, String fallbackBase = "file") {
        return sanitizePathFileName(rawName, fallbackBase)
    }

    static String deriveDownloadFileName(String sourceFileName, String requestedFormat) {
        String format = (requestedFormat ?: "").toLowerCase()
        if (!format) return sourceFileName

        String normalizedFileName = sanitizeUploadFileName(sourceFileName, "reconciliation-output")
        int extensionIndex = normalizedFileName.lastIndexOf(".")
        String baseName = extensionIndex > 0 ? normalizedFileName.substring(0, extensionIndex) : normalizedFileName
        return "${baseName}.${format}"
    }

    static String contentTypeForFormat(String requestedFormat) {
        switch ((requestedFormat ?: "").toLowerCase()) {
            case "csv":
                return "text/csv; charset=UTF-8"
            case "json":
            default:
                return "application/json; charset=UTF-8"
        }
    }

    static List<String> availableFormatsForSource(String sourceFormat) {
        switch ((sourceFormat ?: "").toLowerCase()) {
            case "json":
                return ["json", "csv"]
            case "csv":
                return ["csv"]
            default:
                return []
        }
    }
}
