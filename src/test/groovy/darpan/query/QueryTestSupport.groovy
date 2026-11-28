package darpan.query

import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-334: shared set-up for the query smoke tests. Boots Moqui through ReconciliationSmokeTestSupport, loads
 * Darpan's security seed and moqui-jexl's grants, seeds two tenants (KREWE with the test editor, GORJANA without), and
 * gives the production authz posture (no harness grant on the stack, authz on).
 */
class QueryTestSupport {
    static final String KREWE = "KREWE"
    static final String GORJANA = "GORJANA"
    static final String EDITOR_ID = "TEST_CUSTOMER_USER"
    static final String EDITOR_USERNAME = "test.customer"
    static final Timestamp FROM_DATE = Timestamp.valueOf("2026-04-21 00:00:00")

    static ExecutionContext boot(String name) {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ExecutionContext ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, name)
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ec.entity.makeDataLoader().location("component://moqui-jexl/data/JexlSecurityData.xml").dummyFks(true).load()
        ec.entity.makeDataLoader().location("component://darpan/data/QuerySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        insert(ec, "moqui.security.UserGroup", [userGroupId: GORJANA, description: "Gorjana",
                                                groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
        insert(ec, "moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: EDITOR_ID, fromDate: FROM_DATE])
        // UserFacade caches groups per login; switch user once so new memberships are seen
        insert(ec, "moqui.security.UserAccount", [userId: "QUERY_XT_OTHER", username: "query.xt.other",
                                                  userFullName: "Other", currentPassword: "", disabled: "N"])
        assertTrue(ec.user.internalLoginUser("query.xt.other"))
        actAsKreweEditor(ec)
        return ec
    }

    static void actAsKreweEditor(ExecutionContext ec) {
        assertTrue(ec.user.internalLoginUser(EDITOR_USERNAME), "could not log in ${EDITOR_USERNAME}")
        ec.user.setPreference(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY, KREWE)
    }

    static void insert(ExecutionContext ec, String entityName, Map<String, Object> fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }
}
