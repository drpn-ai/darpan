package darpan.facade.common

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.sanitizeFileToken

class DataManagerSupport {
    private static final Logger logger = LoggerFactory.getLogger(DataManagerSupport.class)

    static final String DEFAULT_DATA_MANAGER_LOCATION = "runtime://datamanager"
    static final String RECONCILIATION_RUNS_PATH = "reconciliation-runs"
    static final String SCHEMA_PATH = "schemas/json"

    static String resolveDataManagerLocation(def ec) {
        String configured = normalize(ec?.resource?.properties?.get("darpan.data.manager.location") ?:
                ec?.resource?.properties?.get("darpan.data-manager.location") ?:
                ec?.resource?.properties?.get("data.manager.location") ?:
                ec?.resource?.properties?.get("data-manager.location") ?:
                System.getProperty("darpan.data.manager.location") ?:
                System.getProperty("darpan.data-manager.location") ?:
                System.getProperty("data.manager.location") ?:
                System.getProperty("data-manager.location"))
        return configured ?: DEFAULT_DATA_MANAGER_LOCATION
    }

    static String resolveReconciliationRunsLocation(def ec) {
        return childLocation(resolveDataManagerLocation(ec), RECONCILIATION_RUNS_PATH)
    }

    static String resolveReconciliationRunLocation(def ec, Object runId, Object timestamp) {
        return childLocation(childLocation(resolveReconciliationRunsLocation(ec), safeToken(runId, "run")),
                safeToken(timestamp, formatRunTimestamp(ec)))
    }

    static String resolveSchemaLocation(def ec, Object schemaName) {
        return childLocation(childLocation(resolveDataManagerLocation(ec), SCHEMA_PATH), schemaFileName(schemaName))
    }

    static String formatRunTimestamp(def ec) {
        try {
            if (ec?.l10n != null && ec?.user?.nowTimestamp != null) {
                return ec.l10n.format(ec.user.nowTimestamp, "yyyyMMdd-HHmmssSSS")
            }
        } catch (Exception e) {
            logger.debug("L10n run timestamp formatting failed; using SimpleDateFormat fallback", e)
        }
        return new SimpleDateFormat("yyyyMMdd-HHmmssSSS").format(new Date())
    }

    static String runArtifactFileName(Object runId, String artifactName, Object originalFileName = null) {
        String runToken = safeToken(runId, "run")
        String artifactToken = safeToken(artifactName, "artifact")
        return "${runToken}_${artifactToken}${extensionFromName(originalFileName)}"
    }

    static String schemaFileName(Object schemaName) {
        String safeName = safeToken(schemaName, "schema")
        return safeName.toLowerCase().endsWith(".json") ? safeName : "${safeName}.json"
    }

    static String safeToken(Object rawValue, String fallback) {
        String normalized = normalize(rawValue)
        if (!normalized) return fallback

        String token = sanitizeFileToken(normalized.tokenize("/\\").last(), fallback)
                .replaceAll(/^\.+/, "")
        return token ?: fallback
    }

    static String normalizeRelativePath(Object rawPath) {
        String normalized = normalize(rawPath)
        if (!normalized) return null
        normalized = normalized.replace("\\", "/")
        if (normalized.startsWith("/") || normalized.contains("://")) return null

        List<String> parts = normalized.split("/").findAll { it != null && it.length() > 0 }
        if (parts.isEmpty()) return null
        if (parts.any { it == "." || it == ".." || !(it ==~ /[A-Za-z0-9._-]+/) }) return null

        return parts.join("/")
    }

    // File.mkdirs() reports failure only as a false return, so a directory that cannot be created
    // (read-only mount, wrong volume ownership, a file occupying a path segment) used to surface
    // later as a misleading FileNotFoundException "No such file or directory" on stream open.
    // Files.createDirectories throws the real IOException (AccessDeniedException, "Read-only file
    // system", FileAlreadyExistsException) so the failure names its actual cause.
    static void ensureDirectory(File directory, String purpose) {
        if (directory == null || directory.isDirectory()) return
        try {
            Files.createDirectories(directory.toPath())
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create ${purpose} directory ${directory.absolutePath} " +
                    "(process user: ${System.getProperty('user.name')}): ${e.getClass().simpleName}: ${e.message}. " +
                    "Check the datamanager volume mount (readOnly flag, ownership/fsGroup) and that no file " +
                    "occupies a parent path segment.", e)
        }
    }

    static File resolveDirectoryFile(def ec, String location, boolean create = true) {
        def ref = ec?.resource?.getLocationReference(location)
        File directory = ref?.getFile()
        if (directory == null && location?.startsWith("runtime://")) {
            String runtimePath = ec?.factory?.getRuntimePath()
            if (runtimePath) directory = new File(runtimePath, location.replace("runtime://", ""))
        }
        if (create) ensureDirectory(directory, "datamanager")
        return directory
    }

    static File resolveDataManagerFile(def ec, Object relativePath, boolean createParent = false) {
        String safePath = normalizeRelativePath(relativePath)
        if (!safePath) return null

        File root = resolveDirectoryFile(ec, resolveDataManagerLocation(ec), createParent)
        if (root == null) return null

        File candidate = new File(root, safePath)
        if (!isUnderDirectory(root, candidate)) return null
        if (createParent) ensureDirectory(candidate.parentFile, "datamanager")
        return candidate
    }

    static String relativeDataManagerPath(def ec, File file) {
        if (file == null) return null
        File root = resolveDirectoryFile(ec, resolveDataManagerLocation(ec), false)
        if (root == null || !isUnderDirectory(root, file)) return file.name
        return root.canonicalFile.toPath().relativize(file.canonicalFile.toPath()).toString().replace(File.separator, "/")
    }

    static String writeText(def ec, String location, Object payload) {
        String text = payload?.toString() ?: ""
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8)
        def ref = ec?.resource?.getLocationReference(location)
        File targetFile = ref?.getFile()
        if (targetFile != null) {
            ensureDirectory(targetFile.parentFile, "datamanager output")
            targetFile.withOutputStream { outputStream ->
                outputStream.write(bytes)
            }
            return location
        }

        ref?.putStream(new ByteArrayInputStream(bytes))
        return location
    }

    // Moves an already-written work file into its final location without re-reading it into
    // memory — the streaming counterpart to writeText for large extracts. File-backed locations
    // get an atomic rename (same-volume work files make this a metadata-only operation, and
    // readers never observe a half-written file); other resource locations are fed the work
    // file as a stream and the work file is removed afterwards.
    static String moveIntoLocation(def ec, File workFile, String location) {
        def ref = ec?.resource?.getLocationReference(location)
        File targetFile = ref?.getFile()
        if (targetFile != null) {
            ensureDirectory(targetFile.parentFile, "datamanager output")
            try {
                Files.move(workFile.toPath(), targetFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(workFile.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return location
        }

        workFile.withInputStream { InputStream inputStream ->
            ref?.putStream(inputStream)
        }
        workFile.delete()
        return location
    }

    static String childLocation(String base, String child) {
        String normalizedBase = normalize(base)
        String normalizedChild = normalize(child)
        if (!normalizedBase) return normalizedChild
        if (!normalizedChild) return normalizedBase
        return normalizedBase + (normalizedBase.endsWith("/") ? "" : "/") + normalizedChild
    }

    protected static String extensionFromName(Object rawName) {
        String normalized = normalize(rawName)
        if (!normalized) return ""
        String fileName = normalized.tokenize("/\\").last()
        int dotIndex = fileName.lastIndexOf(".")
        if (dotIndex <= 0 || dotIndex == fileName.length() - 1) return ""
        String extension = fileName.substring(dotIndex).toLowerCase()
        return extension ==~ /\.[a-z0-9]+/ ? extension : ""
    }

    protected static boolean isUnderDirectory(File root, File candidate) {
        if (root == null || candidate == null) return false
        return candidate.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())
    }
}
