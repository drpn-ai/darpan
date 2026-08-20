package darpan.reconciliation.automation

import darpan.reconciliation.notification.RunNotificationVoice
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
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Task 7 (gchat notifications): verifies StuckRunReaper.sweepEntity notifies subscriber spaces for every
 * ReconciliationRunResult row it reaps (marks FAILED), best-effort.
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

    @Test
    void reaperNotifiesReapedRunResults() {
        FakeStore store = new FakeStore()
        Timestamp stale = new Timestamp(System.currentTimeMillis() - (200 * 60_000L))
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
        List<Map<String, Object>> deliveries = []
        TenantNotificationSupport.setDeliveryHook { String webhookUrl, Map<String, Object> payload ->
            deliveries << [webhookUrl: webhookUrl, payload: payload]
            return [ok: true, statusCode: 200]
        }

        Map<String, Object> result
        try {
            result = StuckRunReaper.sweep(ec, 120)
        } finally {
            TenantNotificationSupport.resetDeliveryHook()
        }

        assertEquals(1, result.reapedRunResultCount)
        Map<String, Object> row = store.rows[DarpanEntityConstants.RECONCILIATION_RUN_RESULT][0]
        assertEquals(StuckRunReaper.STATUS_FAILED, row.statusEnumId)
        assertEquals(1, deliveries.size())
        String text = deliveries[0].payload.text as String
        assertTrue(text.contains("stuck-run watchdog"))
    }

    @Test
    void reaperSweepSurvivesNotifyFailure() {
        // Fix round 1 (review finding 2): a delivery-hook throw never reaches StuckRunReaper's own catch
        // — notifyRunCompleted's per-destination delivery loop already absorbs delivery exceptions
        // internally (TenantNotificationSupport.groovy ~lines 120-131), so that mechanism doesn't exercise
        // what this test claims. Instead force the exception out of the payload-BUILD call itself (the
        // `ec.service.sync()...build#RunCompletedPayload...call()` invocation has no internal try/catch,
        // unlike the delivery loop — see TenantNotificationSupport.notifyRunCompleted ~lines 111-115), so
        // it genuinely escapes notifyRunCompleted unguarded and must be absorbed by the reaper's own
        // best-effort catch: the row still flips FAILED and the count is still correct, no exception
        // propagates out of sweep().
        FakeStore store = new FakeStore()
        store.explodeOnBuildPayload = true
        Timestamp stale = new Timestamp(System.currentTimeMillis() - (200 * 60_000L))
        store.add(DarpanEntityConstants.RECONCILIATION_RUN_RESULT, [
                reconciliationRunResultId: "RUN_1",
                companyUserGroupId       : "TENANT_A",
                savedRunId               : "RS_ORDER",
                resultDataManagerPath    : "reconciliation-runs/RS_ORDER/result.json",
                statusEnumId             : StuckRunReaper.STATUS_PENDING,
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

        // No delivery hook is needed/set here: the payload-build call throws before deliverGoogleChat is
        // ever reached, so if this test regresses (production catch removed), sweep() itself would throw.
        Map<String, Object> result = StuckRunReaper.sweep(ec, 120)

        assertEquals(1, result.reapedRunResultCount)
        Map<String, Object> row = store.rows[DarpanEntityConstants.RECONCILIATION_RUN_RESULT][0]
        assertEquals(StuckRunReaper.STATUS_FAILED, row.statusEnumId)
    }

    // ---- Fake EC construction --------------------------------------------------------------------
    // StuckRunReaper is @CompileStatic with ExecutionContext-typed params and an explicit (EntityFind)
    // cast, so every layer below must be a genuine dynamic proxy of the real Moqui interface (JDK
    // Proxy.invoke() enforces return-type assignability per interface method, regardless of whether the
    // eventual caller uses the result statically or dynamically).

    private static class FakeStore {
        Map<String, List<Map<String, Object>>> rows = [:].withDefault { [] }
        Timestamp userNow = new Timestamp(System.currentTimeMillis())
        boolean explodeOnBuildPayload = false

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

    private static Map<String, Object> buildNotificationPayload(Map params) {
        String runName = ((params.runName)?.toString()?.trim()) ?: ((params.savedRunId)?.toString()?.trim()) ?: "reconciliation run"
        boolean runFailed = ((params.statusEnumId)?.toString()?.trim()) == "AUT_STAT_FAILED"
        String terminationReasonValue = ((params.terminationReason)?.toString()?.trim())
        // Delegates to the real renderer rather than reimplementing it — the reaper's notify really
        // does go through build#RunCompletedPayload, so a hand-rolled line stack here would keep this
        // suite green against copy the production path no longer produces.
        List<String> lines = RunNotificationVoice.renderLines(
                RunNotificationVoice.classify([runFailed: runFailed]) +
                        [runName: runName, priorCleanRuns: 0, completedMoment: null])
        if (runFailed) lines << "⚠ Status: FAILED — the ruleset did not fully evaluate; results may be incomplete.".toString()
        if (terminationReasonValue) lines << "⚠ ${terminationReasonValue}".toString()
        String resultId = ((params.reconciliationRunResultId)?.toString()?.trim())
        if (resultId) lines << "Result ID: ${resultId}".toString()
        return [payload: [text: lines.join("\n")]]
    }

    private static ServiceCallSync newServiceCallSync(FakeStore store) {
        ServiceCallSync[] selfHolder = new ServiceCallSync[1]
        String[] serviceNameHolder = [null]
        Map<String, Object>[] paramsHolder = [[:]]
        Map handlers = [
                name        : { String serviceName -> serviceNameHolder[0] = serviceName; selfHolder[0] },
                parameters  : { Map<String, Object> params -> paramsHolder[0] = params; selfHolder[0] },
                disableAuthz: { -> selfHolder[0] },
                call        : { ->
                    if (serviceNameHolder[0] == "reconciliation.ReconciliationNotificationServices.build#RunCompletedPayload") {
                        if (store.explodeOnBuildPayload) throw new RuntimeException("payload build failed")
                        return buildNotificationPayload(paramsHolder[0])
                    }
                    return [:]
                },
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
