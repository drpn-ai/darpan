import darpan.facade.reconciliation.ReconciliationOutputSupport
import org.slf4j.LoggerFactory

def logger = LoggerFactory.getLogger("darpan.reconciliation.generic.DeleteGeneratedOutputFile")

def normalize = { val -> val?.toString()?.trim() }

String fileNameToDelete = normalize(filename)
if (!fileNameToDelete) {
    throw new IllegalArgumentException("filename is required")
}
if (!ReconciliationOutputSupport.isSafeOutputPath(fileNameToDelete)) {
    throw new IllegalArgumentException("Invalid filename")
}

File targetFile = ReconciliationOutputSupport.resolveGeneratedOutputFile(ec, fileNameToDelete)
if (targetFile == null || !targetFile.exists() || !targetFile.isFile()) {
    deleted = false
    deletedFileName = fileNameToDelete
    statusMessage = "File not found: ${fileNameToDelete}"
    logger.warn("Delete requested for missing reconciliation output ${fileNameToDelete}")
    return
}
if (!ReconciliationOutputSupport.canAccessGeneratedOutputFile(ec, targetFile, fileNameToDelete)) {
    throw new IllegalArgumentException("Generated output '${fileNameToDelete}' is not available in your active tenant.")
}

boolean deletedOk = false
if (targetFile != null) {
    deletedOk = targetFile.delete()
}

deleted = deletedOk
deletedFileName = fileNameToDelete
if (deletedOk) {
    statusMessage = "Deleted ${fileNameToDelete}"
    logger.info("Deleted generated reconciliation output ${fileNameToDelete}")
} else {
    statusMessage = "Unable to delete ${fileNameToDelete}"
    logger.warn("Failed to delete generated reconciliation output ${fileNameToDelete}")
}
