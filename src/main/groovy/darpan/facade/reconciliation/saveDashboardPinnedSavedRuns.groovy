import darpan.facade.common.FacadeSupport
import darpan.facade.reconciliation.ReconciliationDashboardPreferenceSupport
import darpan.facade.reconciliation.ReconciliationSavedRunSupport

Set<String> validSavedRunIds = (ReconciliationSavedRunSupport.collectSavedRunRows(ec)
        .collect { it.savedRunId as String }
        .findAll { it }) as Set<String>

pinnedSavedRunIds = ReconciliationDashboardPreferenceSupport.savePinnedSavedRunIds(
        ec,
        pinnedSavedRunIds,
        validSavedRunIds
)

Map envelope = FacadeSupport.envelope(ec)
ok = envelope.ok
messages = envelope.messages
errors = envelope.errors
