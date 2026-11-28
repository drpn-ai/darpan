package darpan.reconciliation.automation

import darpan.common.DarpanEntityConstants
import darpan.reconciliation.notification.TenantNotificationSupport
import org.junit.jupiter.api.Test
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityFacade
import org.moqui.entity.EntityFind
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.moqui.context.UserFacade
import org.moqui.service.ServiceFacade
import org.moqui.service.ServiceCallSync

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * What StuckRunReaper.sweep tells whom for every row it reaps (marks FAILED), best-effort: Darpan's error space gets the
 * card, a notify-me subscriber only the neutral line (DAR-BE-015 for executions, DAR-BE-362's path for run results).
 *
 * StuckRunReaper is {@code @CompileStatic} with an {@code ExecutionContext}-typed parameter and an
 * explicit {@code (EntityFind)} cast internally, so a plain duck-typed Groovy fake (the pattern used in
 * AutomationExecutionSupportTests / SftpAutomationSupportTests, whose call sites are all dynamically
 * typed) is not assignable there. This harness instead builds genuine dynamic proxies (Groovy's
 * {@code Map.asType(Interface)} coercion, backed by {@code java.lang.reflect.Proxy}) for the real Moqui
 * interfaces StuckRunReaper's compiled bytecode requires: ExecutionContext, EntityFacade, EntityFind,
 * EntityList, EntityValue, UserFacade, ServiceFacade, ServiceCallSync. Only the methods actually invoked
 * by StuckRunReaper + TenantScopedFinder + TenantNotificationSupport are mapped; anything else throws
 * UnsupportedOperationException (harmless — never exercised by this scenario).
 */
class StuckRunReaperNotifyTests {

    /**
     * DAR-OPS-029 S07a (OUTPUT CHANGE). A reaped interactive run is a failed run: Darpan's error space gets one card with the
     * reason, its notify-me subscriber only the neutral line (DAR-BE-362's client.runfailed), never the watchdog text, and the
     * row keeps the reason in errorMessage. It used to post "⚠ Status: FAILED — the ruleset did not fully evaluate" plus the
     * watchdog line to the subscriber, write no reason (the statusMessage it wrote is on neither entity) and tell Darpan nothing.
     */
    @Test
    void aReapedRunResultPostsOneCardKeepsItsReasonAndItsSubscriberGetsOnlyTheNeutralLine() {
        FakeStore store = storeWithStaleRunResult(StuckRunReaper.STATUS_RUNNING)
        ExecutionContext ec = fakeEc(store)
        List<Map> cards = []
        AutomationExecutionSupport.errorCardPoster = { def ignoredEc, Map event -> cards << event; return true }
        AutomationExecutionSupport.errorNoticeCopy = { def ignoredEc -> AutomationExecutionSupportTests.opsNoticeSeedCopy() }
        List<Map<String, Object>> deliveries = []
        TenantNotificationSupport.setDeliveryHook { String webhookUrl, Map<String, Object> payload ->
            deliveries << [webhookUrl: webhookUrl, payload: payload]
            return [ok: true, statusCode: 200]
        }

        Map<String, Object> result
        try {
            result = StuckRunReaper.sweep(ec)
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
            AutomationExecutionSupport.resetForTests()
        }

        assertEquals(1, result.reapedRunResultCount)
        Map<String, Object> row = store.rows[DarpanEntityConstants.RECONCILIATION_RUN_RESULT][0]
        assertEquals(StuckRunReaper.STATUS_FAILED, row.statusEnumId)
        assertEquals(StuckRunReaper.REAPED_REASON, row.errorMessage, "the run-result page shows why")
        assertEquals(1, cards.size(), "one card for Darpan: ${cards}")
        assertEquals("FAILED", cards[0].kind)
        assertEquals(StuckRunReaper.REAPED_REASON, cards[0].errorFirstLine)
        assertEquals(1, deliveries.size(), "the subscriber is told once: ${deliveries}")
        assertEquals("RS_ORDER did not finish. Darpan has been alerted and is working on a fix.", deliveries[0].payload.text)
        assertFalse(deliveries.toString().contains("watchdog"), "no error text reaches a client")
    }

    @Test
    void aReapedRunResultKeepsTheErrorItAlreadyHad() {
        FakeStore store = storeWithStaleRunResult(StuckRunReaper.STATUS_PENDING)
        store.rows[DarpanEntityConstants.RECONCILIATION_RUN_RESULT][0].errorMessage = "OMS read timed out"
        AutomationExecutionSupport.errorCardPoster = { def ignoredEc, Map event -> true }
        AutomationExecutionSupport.errorNoticeCopy = { def ignoredEc -> AutomationExecutionSupportTests.opsNoticeSeedCopy() }
        try {
            StuckRunReaper.sweep(fakeEc(store))
        } finally {
            AutomationExecutionSupport.resetForTests()
        }
        assertEquals("OMS read timed out", store.rows[DarpanEntityConstants.RECONCILIATION_RUN_RESULT][0].errorMessage)
    }

    @Test
    void reaperSweepSurvivesNotifyFailure() {
        // Best-effort: a card post and a notice copy that both throw never stop the sweep; the row still flips FAILED.
        FakeStore store = storeWithStaleRunResult(StuckRunReaper.STATUS_PENDING)
        AutomationExecutionSupport.errorCardPoster = { def ignoredEc, Map event -> throw new IllegalStateException("chat down") }
        AutomationExecutionSupport.errorNoticeCopy = { def ignoredEc -> throw new IllegalStateException("copy unreadable") }
        Map<String, Object> result
        try {
            result = StuckRunReaper.sweep(fakeEc(store))
        } finally {
            AutomationExecutionSupport.resetForTests()
        }

        assertEquals(1, result.reapedRunResultCount)
        assertEquals(StuckRunReaper.STATUS_FAILED, store.rows[DarpanEntityConstants.RECONCILIATION_RUN_RESULT][0].statusEnumId)
    }

    @Test
    void aFreshOrFinishedRowIsNotReaped() {
        FakeStore store = storeWithStaleRunResult(StuckRunReaper.STATUS_RUNNING)
        store.rows[DarpanEntityConstants.RECONCILIATION_RUN_RESULT][0].lastUpdatedStamp = new Timestamp(System.currentTimeMillis())
        store.add(DarpanEntityConstants.RECONCILIATION_RUN_RESULT, [reconciliationRunResultId: "RUN_DONE", companyUserGroupId: "TENANT_A",
                statusEnumId: "AUT_STAT_SUCCESS", lastUpdatedStamp: new Timestamp(System.currentTimeMillis() - (500 * 60_000L))])
        Map<String, Object> result = StuckRunReaper.sweep(fakeEc(store))
        assertEquals(0, result.reapedRunResultCount)
        assertEquals([StuckRunReaper.STATUS_RUNNING, "AUT_STAT_SUCCESS"], store.rows[DarpanEntityConstants.RECONCILIATION_RUN_RESULT]*.statusEnumId)
    }

    /** DAR-OPS-029 S07a QA: the run result goes stale a sweep before its execution; the execution reports it, once. */
    @Test
    void aStaleRunResultOfALiveExecutionIsReapedButLeftToItsExecutionToReport() {
        FakeStore store = storeWithStaleRunResult(StuckRunReaper.STATUS_RUNNING)
        store.add(DarpanEntityConstants.RECONCILIATION_AUTOMATION_EXECUTION, [automationExecutionId: "EXEC_1", automationId: "AUTO_1",
                companyUserGroupId: "TENANT_A", statusEnumId: StuckRunReaper.STATUS_RUNNING, reconciliationRunResultId: "RUN_1",
                lastUpdatedStamp: new Timestamp(System.currentTimeMillis())])
        List<Map> cards = []
        AutomationExecutionSupport.errorCardPoster = { def ignoredEc, Map event -> cards << event; return true }
        AutomationExecutionSupport.errorNoticeCopy = { def ignoredEc -> AutomationExecutionSupportTests.opsNoticeSeedCopy() }
        List<Map> deliveries = []
        TenantNotificationSupport.setDeliveryHook { String webhookUrl, Map<String, Object> payload -> deliveries << payload; [ok: true, statusCode: 200] }
        Map<String, Object> result
        try {
            result = StuckRunReaper.sweep(fakeEc(store))
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
            AutomationExecutionSupport.resetForTests()
        }
        assertEquals([0, 1], [result.reapedExecutionCount, result.reapedRunResultCount])
        assertEquals(StuckRunReaper.REAPED_REASON, store.rows[DarpanEntityConstants.RECONCILIATION_RUN_RESULT][0].errorMessage)
        assertEquals([], cards, "no card yet: the execution reports the run")
        assertEquals([], deliveries)
    }

    private static FakeStore storeWithStaleRunResult(String status) {
        FakeStore store = new FakeStore()
        Timestamp stale = new Timestamp(System.currentTimeMillis() - (200 * 60_000L))
        store.add(DarpanEntityConstants.RECONCILIATION_RUN_RESULT, [
                reconciliationRunResultId: "RUN_1",
                companyUserGroupId       : "TENANT_A",
                savedRunId               : "RS_ORDER",
                resultDataManagerPath    : "reconciliation-runs/RS_ORDER/result.json",
                statusEnumId             : status,
                lastUpdatedStamp         : stale,
        ])
        store.add("darpan.reconciliation.TenantChatSpace", [
                chatSpaceId         : "CS_OPS",
                companyUserGroupId  : "TENANT_A",
                spaceName           : "Ops",
                googleChatWebhookUrl: "https://chat.googleapis.com/v1/spaces/AAA/messages?key=test-key&token=test-token",
                isActive            : "Y",
        ])
        store.add(DarpanEntityConstants.RUN_NOTIFY_SUBSCRIPTION, [
                reconciliationRunResultId: "RUN_1",
                chatSpaceId              : "CS_OPS",
        ])
        return store
    }

    @Test
    void aReapedExecutionEndsNowAndPostsOneFailedCardWhileItsSubscriberGetsOnlyTheNeutralLine() {
        // DAR-BE-015 final review: a reaped execution was FAILED with no completedDate, so the daily error email (it reads
        // executions by completedDate) and the card never saw it. Now it ends now, posts Darpan's card, and the run's
        // subscriber is told once, the neutral line only (the run-result sweep after it finds the run already told).
        FakeStore store = new FakeStore()
        Timestamp stale = new Timestamp(System.currentTimeMillis() - (200 * 60_000L))
        store.add("darpan.reconciliation.ReconciliationAutomation", [
                automationId      : "AUTO_1",
                automationName    : "Daily Orders",
                companyUserGroupId: "TENANT_A",
                savedRunId        : "RS_ORDER",
        ])
        store.add(DarpanEntityConstants.RECONCILIATION_AUTOMATION_EXECUTION, [
                automationExecutionId    : "EXEC_1",
                automationId             : "AUTO_1",
                companyUserGroupId       : "TENANT_A",
                statusEnumId             : StuckRunReaper.STATUS_RUNNING,
                triggerType              : "scheduled",
                scheduledDate            : stale,
                reconciliationRunResultId: "RUN_1",
                lastUpdatedStamp         : stale,
        ])
        store.add(DarpanEntityConstants.RECONCILIATION_RUN_RESULT, [
                reconciliationRunResultId: "RUN_1",
                companyUserGroupId       : "TENANT_A",
                savedRunId               : "RS_ORDER",
                resultDataManagerPath    : "reconciliation-runs/RS_ORDER/result.json",
                statusEnumId             : StuckRunReaper.STATUS_RUNNING,
                lastUpdatedStamp         : stale,
        ])
        store.add("darpan.reconciliation.TenantChatSpace", [
                chatSpaceId         : "CS_OPS",
                companyUserGroupId  : "TENANT_A",
                spaceName           : "Ops",
                googleChatWebhookUrl: "https://chat.googleapis.com/v1/spaces/AAA/messages?key=test-key&token=test-token",
                isActive            : "Y",
        ])
        store.add(DarpanEntityConstants.RUN_NOTIFY_SUBSCRIPTION, [
                reconciliationRunResultId: "RUN_1",
                chatSpaceId              : "CS_OPS",
        ])

        ExecutionContext ec = fakeEc(store)
        List<Map> cards = []
        AutomationExecutionSupport.errorCardPoster = { def ignoredEc, Map event -> cards << event; return true }
        AutomationExecutionSupport.errorNoticeCopy = { def ignoredEc -> AutomationExecutionSupportTests.opsNoticeSeedCopy() }
        List<Map<String, Object>> deliveries = []
        TenantNotificationSupport.setDeliveryHook { String webhookUrl, Map<String, Object> payload ->
            deliveries << [webhookUrl: webhookUrl, payload: payload]
            return [ok: true, statusCode: 200]
        }

        Map<String, Object> result
        try {
            result = StuckRunReaper.sweep(ec)
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
            AutomationExecutionSupport.resetForTests()
        }

        assertEquals(1, result.reapedExecutionCount)
        Map<String, Object> row = store.rows[DarpanEntityConstants.RECONCILIATION_AUTOMATION_EXECUTION][0]
        assertEquals(StuckRunReaper.STATUS_FAILED, row.statusEnumId)
        assertEquals(store.userNow, row.completedDate, "a reaped run ends now, so the daily error email lists it")
        assertEquals(store.userNow, row.lastUpdatedDate)
        assertEquals(StuckRunReaper.REAPED_REASON, row.errorMessage, "the daily email shows why")
        assertEquals(1, cards.size(), "one card for Darpan: ${cards}")
        assertEquals("FAILED", cards[0].kind)
        assertEquals(StuckRunReaper.REAPED_REASON, cards[0].errorFirstLine)
        assertEquals("AUTO_1", cards[0].automationId)
        assertEquals(1, deliveries.size(), "the subscriber is told once: ${deliveries}")
        assertEquals("Daily Orders did not run this morning. Darpan has been alerted and is working on a fix.", deliveries[0].payload.text)
        assertFalse(deliveries.toString().contains("watchdog"), "no error text reaches a client")
    }

    // ---- Fake EC construction --------------------------------------------------------------------
    // StuckRunReaper is @CompileStatic with ExecutionContext-typed params and an explicit (EntityFind)
    // cast, so every layer below must be a genuine dynamic proxy of the real Moqui interface (JDK
    // Proxy.invoke() enforces return-type assignability per interface method, regardless of whether the
    // eventual caller uses the result statically or dynamically).

    private static class FakeStore {
        Map<String, List<Map<String, Object>>> rows = [:].withDefault { [] }
        Timestamp userNow = new Timestamp(System.currentTimeMillis())

        void add(String entityName, Map<String, Object> fields) {
            rows[entityName] << new LinkedHashMap<String, Object>(fields)
        }
    }

    private static List<Map<String, Object>> matchRows(FakeStore store, String entityName,
            Map<String, Object> eqConditions, List<List> opConditions) {
        return store.rows[entityName].findAll { Map<String, Object> row ->
            boolean eqOk = eqConditions.every { String field, Object expected -> row.get(field) == expected }
            boolean opOk = opConditions.every { List cond ->
                String field = cond[0] as String
                String operator = cond[1] as String
                Object expected = cond[2]
                Object actual = row.get(field)
                switch (operator) {
                    case "in": return expected instanceof Collection && ((Collection) expected).contains(actual)
                    case "less": return actual != null && expected != null && ((Comparable) actual).compareTo(expected) < 0
                    case "greater": return actual != null && expected != null && ((Comparable) actual).compareTo(expected) > 0
                    default: return actual == expected
                }
            }
            eqOk && opOk
        }
    }

    private static EntityValue newEntityValue(Map<String, Object> fields) {
        EntityValue[] selfHolder = new EntityValue[1]
        Map handlers = [
                get        : { Object key -> fields.get(key as String) },
                set        : { Object key, Object value -> fields.put(key as String, value); selfHolder[0] },
                update     : { -> selfHolder[0] },
                containsKey: { Object key -> fields.containsKey(key as String) },
                keySet     : { -> fields.keySet() },
                entrySet   : { -> fields.entrySet() },
                size       : { -> fields.size() },
                isEmpty    : { -> fields.isEmpty() },
        ]
        EntityValue proxy = handlers as EntityValue
        selfHolder[0] = proxy
        return proxy
    }

    private static EntityList newEntityList(List<Map<String, Object>> matchedRows) {
        List<EntityValue> values = matchedRows.collect { newEntityValue(it) }
        Map handlers = [
                iterator: { -> values.iterator() },
                size    : { -> values.size() },
                isEmpty : { -> values.isEmpty() },
                get     : { int index -> values[index] },
        ]
        return handlers as EntityList
    }

    private static EntityFind newEntityFind(FakeStore store, String entityName) {
        Map<String, Object> eqConditions = [:]
        List<List> opConditions = []
        EntityFind[] selfHolder = new EntityFind[1]
        Map handlers = [
                condition  : { Object[] args ->
                    if (args.length == 2) {
                        eqConditions[(String) args[0]] = args[1]
                    } else if (args.length == 3) {
                        opConditions << [args[0], args[1], args[2]]
                    }
                    return selfHolder[0]
                },
                useCache   : { Object ignored -> selfHolder[0] },
                disableAuthz: { -> selfHolder[0] },
                one        : { ->
                    Map<String, Object> match = matchRows(store, entityName, eqConditions, opConditions)?.find()
                    return match ? newEntityValue(match) : null
                },
                list       : { -> newEntityList(matchRows(store, entityName, eqConditions, opConditions)) },
                count      : { -> matchRows(store, entityName, eqConditions, opConditions).size() as long },
                updateAll  : { Map<String, Object> fieldsToSet ->
                    List<Map<String, Object>> matches = matchRows(store, entityName, eqConditions, opConditions)
                    matches.each { Map<String, Object> row -> fieldsToSet.each { k, v -> row.put(k as String, v) } }
                    return matches.size() as long
                },
        ]
        EntityFind proxy = handlers as EntityFind
        selfHolder[0] = proxy
        return proxy
    }

    private static EntityFacade newEntityFacade(FakeStore store) {
        Map handlers = [find: { String entityName -> newEntityFind(store, entityName) }]
        return handlers as EntityFacade
    }

    private static UserFacade newUserFacade(FakeStore store) {
        Map handlers = [getNowTimestamp: { -> store.userNow }]
        return handlers as UserFacade
    }

    private static ServiceCallSync newServiceCallSync(FakeStore store) {
        ServiceCallSync[] selfHolder = new ServiceCallSync[1]
        Map handlers = [
                name        : { String ignored -> selfHolder[0] },
                parameters  : { Map<String, Object> ignored -> selfHolder[0] },
                disableAuthz: { -> selfHolder[0] },
                call        : { -> [:] },
        ]
        ServiceCallSync proxy = handlers as ServiceCallSync
        selfHolder[0] = proxy
        return proxy
    }

    private static ServiceFacade newServiceFacade(FakeStore store) {
        Map handlers = [sync: { -> newServiceCallSync(store) }]
        return handlers as ServiceFacade
    }

    private static ExecutionContext fakeEc(FakeStore store) {
        Map handlers = [
                getEntity : { -> newEntityFacade(store) },
                getUser   : { -> newUserFacade(store) },
                getService: { -> newServiceFacade(store) },
        ]
        return handlers as ExecutionContext
    }
}
