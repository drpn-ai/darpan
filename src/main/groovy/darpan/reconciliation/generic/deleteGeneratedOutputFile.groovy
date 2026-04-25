import darpan.facade.common.TenantAccessSupport
import org.slf4j.LoggerFactory

def logger = LoggerFactory.getLogger("darpan.reconciliation.generic.DeleteGeneratedOutputFile")

def normalize = { val -> val?.toString()?.trim() }

def resolveOutputLocation = { -> TenantAccessSupport.resolveGenericOutputLocation(ec) }

String fileNameToDelete = normalize(filename)
if (!fileNameToDelete) {
    throw new IllegalArgumentException("filename is required")
}
if (fileNameToDelete.contains("..") || fileNameToDelete.contains("/") || fileNameToDelete.contains("\\")) {
    throw new IllegalArgumentException("Invalid filename")
}

String requestedOutputLocation = normalize(outputLocation)
String outputLoc = requestedOutputLocation ? TenantAccessSupport.resolveScopedRuntimeLocation(ec, requestedOutputLocation) : resolveOutputLocation()
def outputDirRef = ec.resource.getLocationReference(outputLoc)
if (outputDirRef == null || !outputDirRef.getExists()) {
    deleted = false
    deletedFileName = fileNameToDelete
    statusMessage = "Output directory not found: ${outputLoc}"
    logger.warn(statusMessage)
    return
}

def targetRef = outputDirRef.getChild(fileNameToDelete)
if (targetRef == null || !targetRef.getExists() || !targetRef.isFile()) {
    deleted = false
    deletedFileName = fileNameToDelete
    statusMessage = "File not found: ${fileNameToDelete}"
    logger.warn("Delete requested for missing reconciliation output ${fileNameToDelete} in ${outputLoc}")
    return
}

boolean deletedOk = false
def targetFile = targetRef.getFile()
if (targetFile != null) {
    deletedOk = targetFile.delete()
} else if (targetRef.metaClass.respondsTo(targetRef, "delete")) {
    deletedOk = targetRef.delete()
}

deleted = deletedOk
deletedFileName = fileNameToDelete
if (deletedOk) {
    statusMessage = "Deleted ${fileNameToDelete}"
    logger.info("Deleted generated reconciliation output ${fileNameToDelete} from ${outputLoc}")
} else {
    statusMessage = "Unable to delete ${fileNameToDelete}"
    logger.warn("Failed to delete generated reconciliation output ${fileNameToDelete} from ${outputLoc}")
}
