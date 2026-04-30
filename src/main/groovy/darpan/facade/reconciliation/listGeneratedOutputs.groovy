import darpan.facade.common.FacadeSupport
import darpan.facade.reconciliation.ReconciliationOutputSupport

import java.sql.Timestamp

int page = Math.max(0, FacadeSupport.normalizeInt(pageIndex, 0))
int size = Math.max(1, Math.min(200, FacadeSupport.normalizeInt(pageSize, 20)))
String savedRunIdFilter = FacadeSupport.normalize(savedRunId ?: reconciliationMappingId)
String search = FacadeSupport.normalize(query)?.toLowerCase()

generatedOutputs = []
pagination = [pageIndex: page, pageSize: size, totalCount: 0, pageCount: 1]

List<Map<String, Object>> outputFiles = ReconciliationOutputSupport.listGeneratedOutputFiles(ec)
if (outputFiles) {
    List<Map> rows = []
    outputFiles
            .sort { Map left, Map right -> Long.compare(((File) right.file).lastModified(), ((File) left.file).lastModified()) }
            .each { Map outputFile ->
                File file = (File) outputFile.file
                String fileName = outputFile.fileName as String
                if (ReconciliationOutputSupport.canAccessGeneratedOutputFile(ec, file, fileName)) {
                    Map outputDocument = ReconciliationOutputSupport.parseOutputDocument(file)

                    Map<String, Object> descriptor = ReconciliationOutputSupport.buildGeneratedOutputDescriptor(
                            fileName,
                            outputDocument,
                            file.length(),
                            new Timestamp(file.lastModified())
                    )
                    if (outputFile.runResult?.reconciliationRunResultId) {
                        descriptor.reconciliationRunResultId = outputFile.runResult.reconciliationRunResultId
                    }

                    if (ReconciliationOutputSupport.matchesGeneratedOutputDescriptor(descriptor, savedRunIdFilter, search)) {
                        rows.add(descriptor)
                    }
                }
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
