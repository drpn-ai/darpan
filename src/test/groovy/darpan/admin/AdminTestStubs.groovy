package darpan.admin

import darpan.reconciliation.support.FakeEntityFacade

import java.sql.Timestamp

/**
 * The stubbed ExecutionContext the admin support unit tests share (DAR-OPS-029 S28a: it replaces five hand copies).
 * No Moqui: plain Groovy objects that answer the calls darpan.admin.* makes. ec.entity is the shared FakeEntityFacade
 * (DAR-OPS-029 S99-200): a fresh finder per find(), over the rows given for each entity, answering as Moqui's does.
 */
class AdminTestStubs {

    static final String SUPER_ADMIN_ID = "ADMIN_USER"
    static final String PLAIN_USER_ID = "PLAIN_USER"

    static Expando executionContext(Map overrides = [:]) {
        return new Expando(
                user: overrides.user ?: new UserStub(),
                entity: overrides.entity ?: new FakeEntityFacade(),
                message: overrides.message ?: new MessageFacadeStub(),
                service: overrides.service ?: new ServiceFacadeStub(),
                l10n: overrides.l10n ?: new Expando(timeZone: "UTC"),
                resource: new Expando(properties: [:]))
    }

    /** A DARPAN_SUPER_ADMIN member, over these tables (entity name: rows). Overrides (user, service, message) win. */
    static Expando superAdminEc(Map<String, List<Map>> tables = [:], Map overrides = [:]) {
        tables["moqui.security.UserGroupMember"] = tables["moqui.security.UserGroupMember"] ?:
                [[userGroupId: "DARPAN_SUPER_ADMIN", userId: SUPER_ADMIN_ID] as Map]
        return executionContext([user  : new UserStub(userId: SUPER_ADMIN_ID, username: "darpan.admin"),
                                 entity: new FakeEntityFacade(tables: tables)] + overrides)
    }

    /** A DARPAN_USER member and nothing more: every admin gate refuses them. */
    static Expando plainUserEc(Map overrides = [:]) {
        return executionContext([user  : new UserStub(userId: PLAIN_USER_ID, username: "hotwax.user"),
                                 entity: new FakeEntityFacade(tables: ["moqui.security.UserGroupMember":
                                         [[userGroupId: "DARPAN_USER", userId: PLAIN_USER_ID] as Map]])] + overrides)
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
