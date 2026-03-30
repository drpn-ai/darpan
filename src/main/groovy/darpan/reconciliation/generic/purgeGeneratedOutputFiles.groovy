import darpan.facade.common.PilotAccessSupport
import org.slf4j.LoggerFactory

def logger = LoggerFactory.getLogger("darpan.reconciliation.generic.PurgeGeneratedOutputFiles")

def normalize = { val -> val?.toString()?.trim() }

def resolveOutputLocation = { -> "runtime://tmp/reconciliation/generic/output" }

PilotAccessSupport.requireSuperAdmin(ec, "Only super-admin users may purge reconciliation outputs.")

if (ec.message.hasError()) {
    retentionDays = retentionDays == null ? 15 : retentionDays
    outputLocation = normalize(outputLocation) ?: resolveOutputLocation()
    cutoffTimestamp = null
    scannedCount = 0
    deletedCount = 0
    retainedCount = 0
    failedFiles = []
    statusMessage = "Purge skipped."
    return
}

Integer resolvedRetentionDays
if (retentionDays == null) {
    resolvedRetentionDays = 15
} else {
    resolvedRetentionDays = retentionDays as Integer
}
if (resolvedRetentionDays < 0) {
    throw new IllegalArgumentException("retentionDays must be 0 or greater")
}

String resolvedOutputLocation = PilotAccessSupport.resolveScopedRuntimeLocation(ec, normalize(outputLocation) ?: resolveOutputLocation())
def outputDirRef = ec.resource.getLocationReference(resolvedOutputLocation)

long cutoffMillis = ec.user.nowTimestamp.getTime() - (resolvedRetentionDays * 24L * 60L * 60L * 1000L)
int scanned = 0
int deletedRows = 0
int retained = 0
List<Map> failed = []

if (outputDirRef != null && outputDirRef.getExists()) {
    def entries = outputDirRef.getDirectoryEntries() ?: []
    entries.each { entry ->
        if (!entry.isFile()) return

        String fileName = entry.getFileName()
        if (!fileName) return
        String lowerName = fileName.toLowerCase()
        if (!(lowerName.endsWith(".csv") || lowerName.endsWith(".json"))) return

        scanned++
        long lastModified = entry.getLastModified() ?: 0L
        if (lastModified > cutoffMillis) {
            retained++
            return
        }

        try {
            boolean deletedOk = false
            def entryFile = entry.getFile()
            if (entryFile != null) {
                deletedOk = entryFile.delete()
            } else if (entry.metaClass.respondsTo(entry, "delete")) {
                deletedOk = entry.delete()
            }

            if (deletedOk) {
                deletedRows++
            } else {
                failed.add([fileName: fileName, errorMessage: "Delete returned false"])
            }
        } catch (Exception e) {
            failed.add([fileName: fileName, errorMessage: e.message ?: "Delete failed"])
        }
    }
}

retentionDays = resolvedRetentionDays
outputLocation = resolvedOutputLocation
cutoffTimestamp = cutoffMillis
scannedCount = scanned
deletedCount = deletedRows
retainedCount = retained
failedFiles = failed
statusMessage = "Purge complete. Scanned=${scanned}, Deleted=${deletedRows}, Retained=${retained}, Failed=${failed.size()}"

if (failed) {
    logger.warn("Reconciliation purge completed with failures: ${statusMessage}")
} else {
    logger.info("Reconciliation purge completed: ${statusMessage}")
}
