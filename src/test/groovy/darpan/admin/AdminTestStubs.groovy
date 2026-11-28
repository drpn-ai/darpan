package darpan.admin

import java.sql.Timestamp

/**
 * The stubbed ExecutionContext the admin support unit tests share (DAR-OPS-029 S28a: it replaces five hand copies).
 * No Moqui: plain Groovy objects that answer the calls darpan.admin.* makes. Each ec.entity.find() returns a fresh
 * finder over the rows configured for that entity, as the real facade starts a fresh condition builder per find.
 */
class AdminTestStubs {

    static final String SUPER_ADMIN_ID = "ADMIN_USER"
    static final String PLAIN_USER_ID = "PLAIN_USER"

    static Expando executionContext(Map overrides = [:]) {
        return new Expando(
                user: overrides.user ?: new UserStub(),
                entity: overrides.entity ?: new EntityFacadeStub(),
                message: overrides.message ?: new MessageFacadeStub(),
                service: overrides.service ?: new ServiceFacadeStub(),
                l10n: overrides.l10n ?: new Expando(timeZone: "UTC"),
                resource: new Expando(properties: [:]))
    }

    /** A DARPAN_SUPER_ADMIN member, over these finders. Overrides (user, service, message) win. */
    static Expando superAdminEc(Map<String, FinderStub> finders = [:], Map overrides = [:]) {
        finders["moqui.security.UserGroupMember"] = finders["moqui.security.UserGroupMember"] ?:
                new FinderStub(oneResult: [userGroupId: "DARPAN_SUPER_ADMIN", userId: SUPER_ADMIN_ID])
        return executionContext([user  : new UserStub(userId: SUPER_ADMIN_ID, username: "darpan.admin"),
                                 entity: new EntityFacadeStub(finders: finders)] + overrides)
    }

    /** A DARPAN_USER member and nothing more: every admin gate refuses them. */
    static Expando plainUserEc(Map overrides = [:]) {
        return executionContext([user  : new UserStub(userId: PLAIN_USER_ID, username: "hotwax.user"),
                                 entity: new EntityFacadeStub(finders: ["moqui.security.UserGroupMember":
                                         new FinderStub(oneResult: [userGroupId: "DARPAN_USER", userId: PLAIN_USER_ID])])] + overrides)
    }

    static class UserStub {
        String userId
        String username
        Timestamp nowTimestamp = new Timestamp(System.currentTimeMillis())
        Map<String, Object> preferences = [:]
        Map<String, Object> context = [:]
        Object userAccount = new Expando(timeZone: "UTC")

        Object getPreference(String preferenceKey) { return preferences[preferenceKey] }

        void setPreference(String preferenceKey, Object preferenceValue) { preferences[preferenceKey] = preferenceValue }
    }

    static class MessageFacadeStub {
        List<String> errors = []

        void addError(String error) { errors << error }

        void addMessage(String message) {}

        boolean hasError() { return !errors.isEmpty() }
    }

    static class EntityFacadeStub {
        Map<String, FinderStub> finders = [:]

        FinderStub find(String entityName) {
            FinderStub template = finders[entityName] ?: new FinderStub()
            return new FinderStub(oneResult: template.oneResult, listResult: template.listResult)
        }
    }

    /** Equality, the comparisons the admin reads use, orderBy("-field") and limit. one() checks oneResult when it is set,
     *  else answers the first listed row. */
    static class FinderStub {
        List<Closure<Boolean>> predicates = []
        Object oneResult
        List listResult = []
        String orderField
        boolean descending
        Integer max

        FinderStub condition(String field, Object value) {
            predicates << { Map row -> row[field] == value }
            return this
        }

        FinderStub condition(String field, Object operator, Object value) {
            switch (operator.toString()) {
                case "GREATER_THAN_EQUAL_TO": predicates << { Map row -> row[field] != null && row[field] >= value }; break
                case "LESS_THAN": predicates << { Map row -> row[field] != null && row[field] < value }; break
                case "NOT_EQUAL": predicates << { Map row -> row[field] != value }; break
                default: throw new IllegalArgumentException("stub does not model ${operator}")
            }
            return this
        }

        FinderStub orderBy(String field) {
            descending = field.startsWith("-")
            orderField = descending ? field.substring(1) : field
            return this
        }

        FinderStub limit(Integer max) { return this.tap { it.max = max } }

        FinderStub useCache(boolean useCache) { return this }

        FinderStub conditionDate(String fromField, String thruField, Object moment) { return this }

        FinderStub disableAuthz() { return this }

        Object one() {
            if (oneResult instanceof Map) return predicates.every { it.call(oneResult) } ? oneResult : null
            List rows = list()
            return rows ? rows[0] : null
        }

        List list() {
            List rows = listResult.findAll { Object row -> !(row instanceof Map) || predicates.every { it.call(row as Map) } }
            if (orderField) rows = rows.sort(false) { Map a, Map b -> (a[orderField] <=> b[orderField]) * (descending ? -1 : 1) }
            return max != null ? rows.take(max) : rows
        }
    }

    static class ServiceFacadeStub {
        List<ServiceCallStub> calls = []
        Map<String, Map> resultsByName = [:]
        ServiceCallStub lastCall

        ServiceCallStub sync() {
            lastCall = new ServiceCallStub(facade: this)
            calls << lastCall
            return lastCall
        }
    }

    static class ServiceCallStub {
        ServiceFacadeStub facade
        String serviceName
        Map<String, Object> parametersMap = [:]
        boolean ignoredPreviousError

        ServiceCallStub name(String serviceName) { return this.tap { it.serviceName = serviceName } }

        ServiceCallStub ignorePreviousError(boolean ignore) { return this.tap { it.ignoredPreviousError = ignore } }

        ServiceCallStub parameters(Map<String, Object> parametersMap) { return this.tap { it.parametersMap = parametersMap } }

        Map<String, Object> call() { return facade?.resultsByName?.get(serviceName) ?: [:] }
    }
}
