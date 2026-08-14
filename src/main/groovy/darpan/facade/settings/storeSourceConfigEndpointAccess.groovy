import darpan.facade.common.SharedConfigAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.reconciliation.automation.SourceEndpointAccessSupport

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.readString

// Endpoint enablement is a property of the CREDENTIAL, so it is owner-controlled and global to the
// config row: a tenant holding only ConfigTenantAccess can read the list but not change it. This
// matches canReadOrders, which was one column on the owner's row.
Map<String, String> typeRow = SharedConfigAccessSupport.configType(configTypeEnumId as String)
if (typeRow == null) {
    ec.message.addError("Unknown config type '${configTypeEnumId}'.".toString())
    return
}

def parent = TenantScopedFinder.findTenantScopedByIdQuiet(ec, typeRow.entityName, typeRow.pkField, configId)
if (parent == null) {
    ec.message.addError("${typeRow.label} ${configId} not found.".toString())
    return
}

String ownerUserGroupId = readString(parent, "companyUserGroupId")

List<Map<String, Object>> catalog =
        SourceEndpointAccessSupport.listEndpointsForConfig(ec, configTypeEnumId as String, configId as String)
if (catalog.isEmpty()) {
    ec.message.addError("No source endpoints are registered for ${typeRow.label}. Load the component's seed data.".toString())
    return
}

Set<String> requested = ((enabledSystemEnumIds ?: []) as List)
        .collect { normalize(it) }.findAll { it } as Set<String>

Set<String> known = catalog.collect { it.systemEnumId as String } as Set<String>
Set<String> unknown = requested - known
if (unknown) {
    // Fail loudly rather than silently ignoring: a caller naming an unregistered endpoint has a bug,
    // and swallowing it would look like a successful save that changed nothing.
    ec.message.addError("Not registered for ${typeRow.label}: ${unknown.sort().join(', ')}.".toString())
    return
}

// Write one row per catalog endpoint. Rows for enabled endpoints are stored as 'Y' rather than
// deleted so a previously disabled endpoint visibly flips back; absent still means enabled for
// endpoints that ship later and were never in this catalog.
catalog.each { Map<String, Object> endpoint ->
    String systemEnumId = endpoint.systemEnumId as String
    ec.entity.makeValue(SourceEndpointAccessSupport.ENTITY_NAME)
            .setAll([configTypeEnumId  : configTypeEnumId,
                     configId          : configId,
                     systemEnumId      : systemEnumId,
                     companyUserGroupId: ownerUserGroupId,
                     isEnabled         : requested.contains(systemEnumId) ? "Y" : "N"])
            .createOrUpdate()
}

stored = true
