import darpan.facade.common.FacadeSupport

String fileNameValue = FacadeSupport.normalize(fileName)
if (!fileNameValue) {
    ec.message.addError("fileName is required")
}
if (!ec.message.hasError() && (fileNameValue.contains("..") || fileNameValue.contains("/") || fileNameValue.contains("\\"))) {
    ec.message.addError("fileName is invalid")
}

if (!ec.message.hasError()) {
    Map deleteResult = ec.service.sync()
            .name("reconciliation.ReconciliationGenericServices.delete#GeneratedOutputFile")
            .parameters([filename: fileNameValue])
            .call()

    deleted = deleteResult.deleted
    deletedFileName = deleteResult.deletedFileName ?: fileNameValue
    statusMessage = deleteResult.statusMessage
}

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
