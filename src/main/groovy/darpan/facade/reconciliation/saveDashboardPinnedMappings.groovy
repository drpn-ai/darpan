import darpan.facade.common.FacadeSupport
import darpan.facade.reconciliation.ReconciliationDashboardPreferenceSupport

def mappingFinder = ec.entity.find("darpan.mapping.ReconciliationMapping").useCache(false)

Set<String> validMappingIds = ((mappingFinder.list() ?: [])
        .collect { it.reconciliationMappingId as String }
        .findAll { it }) as Set<String>

pinnedReconciliationMappingIds = ReconciliationDashboardPreferenceSupport.savePinnedReconciliationMappingIds(
        ec,
        pinnedReconciliationMappingIds,
        validMappingIds
)

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
