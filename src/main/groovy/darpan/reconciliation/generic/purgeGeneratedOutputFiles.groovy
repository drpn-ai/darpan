import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.reconciliation.ReconciliationOutputSupport
import groovy.io.FileType
import org.slf4j.LoggerFactory

def logger = LoggerFactory.getLogger("darpan.reconciliation.generic.PurgeGeneratedOutputFiles")

def normalize = { val -> val?.toString()?.trim() }

def resolveOutputLocation = { -> DataManagerSupport.resolveReconciliationRunsLocation(ec) }

String currentUserId = TenantAccessSupport.currentUserId(ec)
if (currentUserId && currentUserId != "_NA_") {
    TenantAccessSupport.requireSuperAdmin(ec, "Only super-admin users may purge reconciliation outputs.")
}

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

String outputLocationValue = normalize(outputLocation)
boolean usingDefaultOutputLocation = !outputLocationValue
String resolvedOutputLocation = usingDefaultOutputLocation ?
        resolveOutputLocation() :
        TenantAccessSupport.resolveScopedRuntimeLocation(ec, outputLocationValue)
def outputDirRef = ec.resource.getLocationReference(resolvedOutputLocation)

long cutoffMillis = ec.user.nowTimestamp.getTime() - (resolvedRetentionDays * 24L * 60L * 60L * 1000L)
int scanned = 0
int deletedRows = 0
int retained = 0
List<Map> failed = []

def scanOutputFile = { String fileName, long lastModified, Closure<Boolean> deleteFile ->
    if (!fileName) return
    String lowerName = fileName.toLowerCase()
    if (usingDefaultOutputLocation) {
        if (!ReconciliationOutputSupport.isGeneratedResultFile(fileName)) return
    } else if (!(lowerName.endsWith(".csv") || lowerName.endsWith(".json"))) {
        return
    }

    scanned++
    if (lastModified > cutoffMillis) {
        retained++
        return
    }

    try {
        boolean deletedOk = deleteFile.call()
        if (deletedOk) {
            deletedRows++
        } else {
            failed.add([fileName: fileName, errorMessage: "Delete returned false"])
        }
    } catch (Exception e) {
        failed.add([fileName: fileName, errorMessage: e.message ?: "Delete failed"])
    }
}
def scanEntry = { entry ->
    if (!entry.isFile()) return

    scanOutputFile(entry.getFileName(), entry.getLastModified() ?: 0L) {
        def entryFile = entry.getFile()
        if (entryFile != null) return entryFile.delete()
        if (entry.metaClass.respondsTo(entry, "delete")) return entry.delete()
        return false
    }
}
def scanFile = { File file ->
    if (!file.isFile()) return
    scanOutputFile(file.name, file.lastModified()) { -> file.delete() }
}

if (outputDirRef != null && outputDirRef.getExists()) {
    File outputDirFile = outputDirRef.getFile()
    if (usingDefaultOutputLocation && outputDirFile?.exists()) {
        outputDirFile.eachFileRecurse(FileType.FILES) { File file ->
            scanFile(file)
        }
    } else {
        def entries = outputDirRef.getDirectoryEntries() ?: []
        entries.each { entry -> scanEntry(entry) }
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
