import darpan.facade.common.FacadeSupport
import darpan.facade.common.PilotAccessSupport
import darpan.facade.reconciliation.PilotReconciliationSupport

import java.sql.Timestamp

int page = Math.max(0, FacadeSupport.normalizeInt(pageIndex, 0))
int size = Math.max(1, Math.min(200, FacadeSupport.normalizeInt(pageSize, 20)))
String mappingId = FacadeSupport.normalize(reconciliationMappingId)
String search = FacadeSupport.normalize(query)?.toLowerCase()

generatedOutputs = []
pagination = [pageIndex: page, pageSize: size, totalCount: 0, pageCount: 1]

String outputLocation = PilotAccessSupport.resolveGenericOutputLocation(ec)
File outputDir = ec.resource.getLocationReference(outputLocation)?.getFile()

if (outputDir?.exists()) {
    List<Map> rows = []
    (outputDir.listFiles() ?: [] as File[])
            .findAll { File file -> file.isFile() && PilotReconciliationSupport.isSupportedOutputFile(file.name) }
            .sort { File left, File right -> Long.compare(right.lastModified(), left.lastModified()) }
            .each { File file ->
                Map outputDocument = [:]
                if (PilotReconciliationSupport.sourceFormatForFile(file.name) == "json") {
                    try {
                        outputDocument = PilotReconciliationSupport.parseGeneratedOutputText(file.getText("UTF-8"))
                    } catch (Exception ignored) {
                        outputDocument = [:]
                    }
                }

                Map<String, Object> descriptor = PilotReconciliationSupport.buildGeneratedOutputDescriptor(
                        file.name,
                        outputDocument,
                        file.length(),
                        new Timestamp(file.lastModified())
                )

                if (!PilotReconciliationSupport.matchesGeneratedOutputDescriptor(descriptor, mappingId, search)) return
                rows.add(descriptor)
            }

    int totalCount = rows.size()
    int fromIndex = Math.min(page * size, totalCount)
    int toIndex = Math.min(fromIndex + size, totalCount)
    generatedOutputs = rows.subList(fromIndex, toIndex)
    pagination = [
            pageIndex : page,
            pageSize  : size,
            totalCount: totalCount,
            pageCount : Math.max(1, Math.ceil(totalCount / (double) size) as int)
    ]
}

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
