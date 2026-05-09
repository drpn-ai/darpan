package darpan.facade.common

import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.sanitizeFileToken

class DataManagerSupport {
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
        } catch (Exception ignored) {
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

    static File resolveDirectoryFile(def ec, String location, boolean create = true) {
        def ref = ec?.resource?.getLocationReference(location)
        File directory = ref?.getFile()
        if (directory == null && location?.startsWith("runtime://")) {
            String runtimePath = ec?.factory?.getRuntimePath()
            if (runtimePath) directory = new File(runtimePath, location.replace("runtime://", ""))
        }
        if (create && directory != null && !directory.exists()) directory.mkdirs()
        return directory
    }

    static File resolveDataManagerFile(def ec, Object relativePath, boolean createParent = false) {
        String safePath = normalizeRelativePath(relativePath)
        if (!safePath) return null

        File root = resolveDirectoryFile(ec, resolveDataManagerLocation(ec), createParent)
        if (root == null) return null

        File candidate = new File(root, safePath)
        if (!isUnderDirectory(root, candidate)) return null
        if (createParent) candidate.parentFile?.mkdirs()
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
            targetFile.parentFile?.mkdirs()
            targetFile.withOutputStream { outputStream ->
                outputStream.write(bytes)
            }
            return location
        }

        ref?.putStream(new ByteArrayInputStream(bytes))
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
