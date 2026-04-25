import darpan.facade.common.FacadeSupport
import darpan.facade.reconciliation.ReconciliationDashboardPreferenceSupport
import darpan.facade.reconciliation.ReconciliationSavedRunSupport

int page = Math.max(0, FacadeSupport.normalizeInt(pageIndex, 0))
int size = Math.max(1, Math.min(200, FacadeSupport.normalizeInt(pageSize, 20)))
String search = FacadeSupport.normalize(query)?.toLowerCase()

List<Map<String, Object>> rows = ReconciliationSavedRunSupport.collectSavedRunRows(ec)
if (search) {
    rows = rows.findAll { Map<String, Object> row ->
        [
                row.savedRunId,
                row.runName,
                row.description,
                row.companyUserGroupId,
                row.companyLabel,
                row.runType,
                row.reconciliationMappingId,
                row.ruleSetId,
                row.compareScopeId,
                row.compareScopeDescription,
                *(row.systemOptions instanceof Collection ? row.systemOptions.collect { it?.label } : []),
                *(row.systemOptions instanceof Collection ? row.systemOptions.collect { it?.enumCode } : []),
        ].any { Object value ->
            value?.toString()?.toLowerCase()?.contains(search)
        }
    }
}

int totalCount = rows.size()
int fromIndex = Math.min(page * size, totalCount)
int toIndex = Math.min(fromIndex + size, totalCount)
savedRuns = rows.subList(fromIndex, toIndex)
pinnedSavedRunIds = ReconciliationDashboardPreferenceSupport.listPinnedSavedRunIds(
        ec,
        rows.collect { it.savedRunId as String }.findAll { it } as Set<String>
)
pagination = [
        pageIndex : page,
        pageSize  : size,
        totalCount: totalCount,
        pageCount : Math.max(1, Math.ceil(totalCount / (double) size) as int)
]

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
