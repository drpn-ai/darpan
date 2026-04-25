import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantAccessSupport
import darpan.facade.reconciliation.ReconciliationOutputSupport

String fileNameValue = FacadeSupport.normalize(fileName)
String requestedFormat = (FacadeSupport.normalize(format) ?: "json").toLowerCase()

if (!fileNameValue) ec.message.addError("fileName is required")
if (!ec.message.hasError() && (fileNameValue.contains("..") || fileNameValue.contains("/") || fileNameValue.contains("\\"))) {
    ec.message.addError("fileName is invalid")
}

String sourceFormat = ReconciliationOutputSupport.sourceFormatForFile(fileNameValue)
if (!ec.message.hasError() && !sourceFormat) {
    ec.message.addError("Unsupported generated output '${fileNameValue}'.")
}

if (!ec.message.hasError() && !ReconciliationOutputSupport.availableFormatsForSource(sourceFormat).contains(requestedFormat)) {
    ec.message.addError("Format '${requestedFormat}' is not available for generated output '${fileNameValue}'.")
}

if (!ec.message.hasError()) {
    String outputLocation = TenantAccessSupport.resolveGenericOutputLocation(ec)
    File outputDir = ec.resource.getLocationReference(outputLocation)?.getFile()
    File generatedOutputFile = outputDir != null ? new File(outputDir, fileNameValue) : null
    if (generatedOutputFile == null || !generatedOutputFile.exists() || !generatedOutputFile.isFile()) {
        ec.message.addError("Generated output '${fileNameValue}' was not found.")
    } else {
        String rawText = generatedOutputFile.getText("UTF-8")
        String contentText = rawText
        if (requestedFormat == "csv" && sourceFormat == "json") {
            Map outputDocument = ReconciliationOutputSupport.parseGeneratedOutputText(rawText)
            contentText = ReconciliationOutputSupport.renderDifferencesCsv(outputDocument)
        }

        outputFile = [
                fileName        : fileNameValue,
                downloadFileName: ReconciliationOutputSupport.deriveDownloadFileName(fileNameValue, requestedFormat),
                sourceFormat    : sourceFormat,
                format          : requestedFormat,
                contentType     : ReconciliationOutputSupport.contentTypeForFormat(requestedFormat),
                contentText     : contentText,
        ]
    }
}

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
