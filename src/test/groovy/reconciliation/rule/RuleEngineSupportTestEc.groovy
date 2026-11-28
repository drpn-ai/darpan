package reconciliation.rule

import darpan.facade.common.TenantAccessSupport

import java.sql.Timestamp

/**
 * A stub ExecutionContext for RuleEngineSupport without Moqui: a signed-in user whose active tenant is {@code tenant},
 * and in-memory finders. The same shape TenantScopedFinderTests uses. Shared by the tenant-denial and invariant tests.
 */
class RuleEngineSupportTestEc {

    /** An ec whose user is active in {@code tenant}; {@code ruleSet} answers RuleSet reads, {@code rules} Rule reads. */
    static Expando ec(String tenant, Map ruleSet, List<Map> rules = []) {
        return new Expando(
                user    : new UserStub(userId: "EX_USER", preferences: [(TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY): tenant]),
                entity  : new EntityFacadeStub(finders: [
                        "moqui.security.UserGroupAndMember": new FinderStub(listResult: [
                                [userGroupId: tenant, userId: "EX_USER", description: tenant,
                                 groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID]]),
                        "darpan.rule.RuleSet": new FinderStub(oneResult: ruleSet),
                        "darpan.rule.Rule"   : new FinderStub(listResult: rules),
                ]),
                message : new MessageFacadeStub(),
                resource: new Expando(properties: [:])
        )
    }

    static class UserStub {
        String userId
        Timestamp nowTimestamp = new Timestamp(System.currentTimeMillis())
        Map<String, Object> preferences = [:]

        Object getPreference(String key) { preferences[key] }
        void setPreference(String key, Object value) { preferences[key] = value }
    }

    static class MessageFacadeStub {
        List<String> errors = []
        void addError(String msg) { errors << msg }
        boolean hasError() { !errors.isEmpty() }
    }

    static class EntityFacadeStub {
        Map<String, FinderStub> finders = [:]

        FinderStub find(String entityName) {
            FinderStub template = finders[entityName]
            // A fresh finder per find(), so one query's conditions never narrow the next one.
            return new FinderStub(oneResult: template?.oneResult, listResult: template?.listResult ?: [])
        }
    }

    static class FinderStub {
        Map<String, Object> conditions = [:]
        Object oneResult
        List listResult = []

        FinderStub condition(String field, Object value) { conditions[field] = value; this }
        FinderStub conditionDate(String f, String t, Object m) { this }
        FinderStub useCache(boolean v) { this }
        FinderStub disableAuthz() { this }
        FinderStub orderBy(List fields) { this }

        Object one() {
            if (oneResult instanceof Map && !conditions.every { k, v -> oneResult[k] == v }) return null
            return oneResult
        }

        List list() {
            listResult.findAll { row ->
                if (!(row instanceof Map)) return true
                conditions.every { k, v -> row[k] == v }
            }
        }
    }
}
