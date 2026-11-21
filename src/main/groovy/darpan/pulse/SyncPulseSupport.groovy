package darpan.pulse

import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.peak.AllowanceSpec
import darpan.peak.PeakPeriodLoader
import darpan.peak.PeakPeriodSupport
import darpan.peak.PeakWindow
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.automation.RunAdmission
import org.moqui.context.ArtifactExecutionInfo
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

import static darpan.common.ValueSupport.normalize

/**
 * DAR-BE-328 plan 3. The sync pulse engine: every few minutes (run#DuePulses) each active sync pair whose pulse is due
 * reads its check's upstream side for a short window, asks the target side which of those records it holds, and writes
 * one SyncPulseSample. A record absent past its allowance gets one open SyncPulseOrder, closed when the record arrives.
 *
 * <p><strong>Window.</strong> orderDate in (now − allowance − 2 × cadence, now], with the effective allowance and
 * cadence for the tenant-local date (a peak period's PeakPeriodSync values, else the pair's own). The extra cadence is
 * the margin that lets one skipped pulse lose nothing. Open orders are re-asked by key every pulse, so an order that has
 * left the window still closes when it arrives.</p>
 *
 * <p><strong>Failure posture.</strong> A failed read writes a FAILED sample and changes no order. A pulse never throws,
 * and run#DuePulses never throws: one bad pair never stops the others, and nothing here reaches the automation
 * scheduler.</p>
 *
 * <p><strong>Admission.</strong> A due pulse goes through the automation scheduler's RunAdmission with label
 * {@code pulse <syncPairId>}: refused (no free slot now) writes a SKIPPED sample and is not queued; a pulse of the same
 * pair still in flight is skipped silently. An admitted pulse runs on RunAdmission's thread with a fresh execution
 * context, made there and destroyed after, as the pair's tenant.</p>
 *
 * <p>Groovy, not XML actions: the scan reads every tenant's pairs through the unscoped finder with a reason, hands work
 * to RunAdmission's threads, and the pulse holds its own execution context and authz frame.</p>
 */
class SyncPulseSupport {
    private static final Logger logger = LoggerFactory.getLogger(SyncPulseSupport)

    static final String OK = "SPS_OK"
    static final String FAILED = "SPS_FAILED"
    static final String SKIPPED = "SPS_SKIPPED"
    static final String HOTWAX_ORDERS = "OMS_GQL"
    static final String LABEL_PREFIX = "pulse "
    /** The job ticks every 5 minutes with some jitter: a pulse is due this much early rather than a whole tick late. */
    static final Duration DUE_SLACK = Duration.ofMinutes(2)

    private static final String SCAN_WHY = "system-cron cross-tenant sweep of sync pairs; no active tenant in scheduler context"
    private static final String WHY = "sync pulse reads one pair's rows (no active tenant): explicit companyUserGroupId condition applied"

    // ------------------------------------------------------------------------------------------- test seams

    private static volatile Closure upstreamRead = null
    private static volatile Closure targetRead = null

    /**
     * Seams for tests: Closure(ec, pair, automation, from, thru) and Closure(ec, pair, automation, keys), answering as
     * SyncPulseReader.readUpstream / presentInTarget. A null argument leaves that reader as it is.
     */
    static void setReaders(Closure upstream, Closure target) {
        if (upstream != null) upstreamRead = upstream
        if (target != null) targetRead = target
    }

    static Closure getUpstreamReader() { return upstreamRead }

    static void resetHooks() { upstreamRead = null; targetRead = null }

    // ------------------------------------------------------------------------------------------- pure parts

    /**
     * Sorts the upstream records of one pulse: present in the target (presentKeys) is done; absent and younger than the
     * allowance is waiting; absent and at least allowanceMinutes old is past. A record whose key the target was never
     * asked (unasked) is neither: it is counted in unaskedCount only. Keys compare trimmed. Each waiting or past record
     * carries ageMinutes.
     *
     * @return [waiting: List, past: List, presentKeys: Set, unaskedCount: int]
     */
    static Map classify(List<Map> upstream, Set<String> present, Instant now, int allowanceMinutes,
                        Set<String> unasked = null) {
        Set<String> presentSet = keySet(present)
        Set<String> unaskedSet = keySet(unasked)
        Duration allowance = Duration.ofMinutes(Math.max(0, allowanceMinutes))
        List<Map> waiting = [], past = []
        Set<String> presentKeys = new LinkedHashSet<String>()
        int unaskedCount = 0
        (upstream ?: []).each { Map r ->
            String key = normalize(r.get("key"))
            Instant createdAt = r.get("createdAt") as Instant
            if (!key || createdAt == null) return
            if (presentSet.contains(key)) { presentKeys << key; return }
            if (unaskedSet.contains(key)) { unaskedCount++; return }
            Duration age = Duration.between(createdAt, now)
            Map out = new LinkedHashMap(r) + [key: key, ageMinutes: age.toMinutes()]
            if (age >= allowance) past << out else waiting << out
        }
        return [waiting: waiting, past: past, presentKeys: presentKeys, unaskedCount: unaskedCount]
    }

    /** Whether the pair's pulse is due at now: never with no cadence; at once when it has never pulsed. */
    static boolean isDue(Map pair, Integer effectiveCadence, Instant now) {
        if (effectiveCadence == null || effectiveCadence <= 0 || now == null) return false
        Object last = pair?.get("lastPulseStamp")
        if (last == null) return true
        Instant lastAt = last instanceof Instant ? (Instant) last : ((Date) last).toInstant()
        return !now.isBefore(lastAt.plus(Duration.ofMinutes(effectiveCadence)).minus(DUE_SLACK))
    }

    /** The read window of one pulse: (now − allowance − 2 × cadence, now]. A null cadence adds no margin. */
    static Map pulseWindow(Instant now, int allowanceMinutes, Integer cadenceMinutes) {
        long back = Math.max(0, allowanceMinutes) + 2L * Math.max(0, cadenceMinutes ?: 0)
        return [from: now.minus(Duration.ofMinutes(back)), thru: now]
    }

    /**
     * The pair's allowance and cadence on tenantDate: the active peak period's PeakPeriodSync values (periodRows, by
     * peakPeriodId; a null value means normal), else the pair's own.
     *
     * @return [allowanceMinutes, pulseEveryMinutes (null = pulse off), peakPeriodId (null outside a period)]
     */
    static Map effectiveValues(Map pair, List<PeakWindow> windows, Map<String, Map> periodRows, LocalDate tenantDate) {
        Map<String, Integer> periodAllowances = [:]
        (periodRows ?: [:]).each { String id, Map row ->
            Integer minutes = row?.get("allowanceMinutes") as Integer
            if (minutes != null) periodAllowances[id] = Math.max(0, minutes)
        }
        AllowanceSpec spec = new AllowanceSpec(Math.max(0, (pair?.get("allowanceMinutes") as Integer) ?: 0), periodAllowances)
        PeakWindow active = PeakPeriodSupport.activeOn(windows, tenantDate)
        Integer cadence = active != null ? (periodRows?.get(active.peakPeriodId)?.get("pulseEveryMinutes") as Integer) : null
        if (cadence == null) cadence = pair?.get("pulseEveryMinutes") as Integer
        return [allowanceMinutes : PeakPeriodSupport.effectiveAllowance(spec, windows, tenantDate),
                pulseEveryMinutes: cadence, peakPeriodId: active?.peakPeriodId]
    }

    // ------------------------------------------------------------------------------------------- the job

    /**
     * run#DuePulses: every active pair of every tenant, its effective cadence for the tenant date, and each due pulse
     * admitted (or SKIPPED). Never throws.
     *
     * @return [scanTimestamp, pairCount, dueCount, submittedCount, skippedCount]
     */
    static Map runDuePulses(def ec, Timestamp nowTimestamp) {
        Instant now = (nowTimestamp ?: (ec.user?.nowTimestamp as Timestamp) ?: new Timestamp(System.currentTimeMillis())).toInstant()
        Map out = [scanTimestamp: Timestamp.from(now), pairCount: 0, dueCount: 0, submittedCount: 0, skippedCount: 0]
        try {
            RunAdmission admission = AutomationExecutionSupport.getRunAdmission()
            List pairs = (TenantScopedFinder.findGlobalUnscopedPerRequest(ec, "darpan.pulse.SyncPair", SCAN_WHY)
                    .useCache(false).list() ?: []).findAll { (normalize(it.get("isActive")) ?: "Y") == "Y" }
            out.pairCount = pairs.size()
            if (!pairs) return out
            admission.drain()
            Map<String, Map> tenantDays = [:]
            pairs.each { row ->
                Map pair = new LinkedHashMap(row.getMap())
                try {
                    String outcome = scanPair(ec, admission, pair, now, tenantDays)
                    if (outcome) out.dueCount = (out.dueCount as int) + 1
                    if (outcome == "submitted") out.submittedCount = (out.submittedCount as int) + 1
                    if (outcome == SKIPPED) out.skippedCount = (out.skippedCount as int) + 1
                } catch (Throwable t) {
                    logger.warn("Sync pulse scan skipped pair {} (tenant {}): {}", pair.syncPairId, pair.companyUserGroupId,
                            t.message ?: t.toString())
                    clearErrors(ec)
                }
            }
        } catch (Throwable t) {
            logger.error("Sync pulse scan failed: {}", t.message ?: t.toString())
            clearErrors(ec)
        }
        return out
    }

    /** One pair of the scan: null when not due or skipped silently, else "submitted" or SKIPPED. */
    private static String scanPair(def ec, RunAdmission admission, Map pair, Instant now, Map<String, Map> tenantDays) {
        String tenantId = normalize(pair.companyUserGroupId)
        String syncPairId = normalize(pair.syncPairId)
        if (!tenantId || !syncPairId) return null
        Map day = tenantDays.computeIfAbsent(tenantId) { String t -> tenantDay(ec, t, now) }
        Map effective = effectiveValues(pair, day.windows as List<PeakWindow>, periodRows(ec, pair), day.date as LocalDate)
        if (!isDue(pair, effective.pulseEveryMinutes as Integer, now)) return null
        if (pulseableAutomation(ec, pair) == null) return null
        String label = LABEL_PREFIX + syncPairId
        if (admission.busy(label)) return null
        if (!admission.canStartNow(tenantId)) {
            recordSkipped(ec, pair, now, effective.allowanceMinutes as Integer, "No run slot was free; this pulse was skipped.")
            return SKIPPED
        }
        def factory = ec.factory
        Map pulsePair = new LinkedHashMap(pair)
        Map admitted = admission.submit(tenantId, label, { -> runAdmitted(factory, pulsePair, now) } as Runnable)
        if (admitted.submitted != true) {
            recordSkipped(ec, pair, now, effective.allowanceMinutes as Integer,
                    "This pulse was skipped: ${normalize(admitted.error) ?: 'no run slot was free'}".toString())
            return SKIPPED
        }
        return "submitted"
    }

    /**
     * An admitted pulse on RunAdmission's thread: a fresh execution context made here and destroyed after, an anonymous
     * login with an artifact authorized for all (as an anonymous-all service runs; the pulse writes its rows and the reader
     * calls authenticate="true" extracts), and the pair's tenant. Never throws.
     */
    private static void runAdmitted(def factory, Map pair, Instant now) {
        def pulseEc = factory.getExecutionContext()
        ArtifactExecutionInfo aei = null
        try {
            pulseEc.user.loginAnonymousIfNoUser()
            aei = pulseEc.artifactExecution.push("darpan.pulse.SyncPulseSupport", ArtifactExecutionInfo.AT_OTHER,
                    ArtifactExecutionInfo.AUTHZA_ALL, false)
            pulseEc.artifactExecution.setAnonymousAuthorizedAll()
            TenantAccessSupport.withSystemTenant(normalize(pair.companyUserGroupId)) { -> runPulse(pulseEc, pair, now) }
        } catch (Throwable t) {
            logger.error("Sync pulse {} failed: {}", pair.syncPairId, t.message ?: t.toString())
        } finally {
            try {
                if (aei != null) pulseEc.artifactExecution.pop(aei)
            } catch (Throwable ignored) { }
            pulseEc.destroy()
        }
    }

    // ------------------------------------------------------------------------------------------- one pulse

    /**
     * One pulse of a pair at now: reads, classifies, opens and closes SyncPulseOrder rows, writes the sample and stamps the
     * pair. A pair whose check is missing, archived, inactive or not HotWax upstream is skipped silently (skipped: true,
     * nothing written). Never throws: anything unexpected writes a FAILED sample and changes no order.
     *
     * @return [status, sample, newlyPast: List of order maps, closed: List of order maps, waitingCount,
     *         oldest: [orderName, ageMinutes] or null, pastCount, priorPastCount, skipped]
     */
    static Map runPulse(def ec, Map pair, Instant now) {
        String tenantId = normalize(pair?.get("companyUserGroupId"))
        String syncPairId = normalize(pair?.get("syncPairId"))
        Map empty = [status: null, sample: null, newlyPast: [], closed: [], waitingCount: null, oldest: null, pastCount: null,
                     priorPastCount: null, skipped: true]
        if (!tenantId || !syncPairId || now == null) return empty
        Integer allowance = null
        List<Map> open = null
        try {
            Map automation = pulseableAutomation(ec, pair)
            if (automation == null) return empty
            Map day = tenantDay(ec, tenantId, now)
            Map effective = effectiveValues(pair, day.windows as List<PeakWindow>, periodRows(ec, pair), day.date as LocalDate)
            allowance = effective.allowanceMinutes as Integer
            open = openOrders(ec, pair)
            Map window = pulseWindow(now, allowance, effective.pulseEveryMinutes as Integer)

            Map up = readUpstream(ec, pair, automation, window.from as Instant, window.thru as Instant)
            if (up?.ok != true) return failed(ec, pair, now, allowance, open, up?.errors)
            List<Map> records = (up.records ?: []) as List<Map>

            Set<String> keys = new LinkedHashSet<String>()
            records.each { Map r -> String k = normalize(r.key); if (k) keys << k }
            open.each { Map o -> keys << (o.recordKey as String) }
            Map target = presentInTarget(ec, pair, automation, keys)
            if (target?.ok != true) return failed(ec, pair, now, allowance, open, target?.errors)

            Set<String> present = keySet(target.present as Collection)
            Set<String> unasked = keySet(target.unasked as Collection)
            Map sorted = classify(records, present, now, allowance, unasked)
            return settle(ec, pair, now, allowance, open, sorted, present, unasked)
        } catch (Throwable t) {
            String reason = normalize(t.message) ?: t.class.simpleName
            logger.warn("Sync pulse {} (tenant {}) failed: {}", syncPairId, tenantId, reason)
            clearErrors(ec)
            try {
                if (open == null) open = openOrders(ec, pair)
                return failed(ec, pair, now, allowance, open, ["The pulse failed: ${reason}".toString()])
            } catch (Throwable again) {
                logger.error("Sync pulse {} (tenant {}) could not record its failure: {}", syncPairId, tenantId,
                        again.message ?: again.toString())
                clearErrors(ec)
                return empty + [skipped: false, status: FAILED]
            }
        }
    }

    /** A SKIPPED sample (admission refused) and the pair's stamp; never throws. */
    static Map recordSkipped(def ec, Map pair, Instant now, Integer allowance, String message) {
        try {
            int openCount = openOrders(ec, pair).size()
            Map sample = null
            ec.transaction.runUseOrBegin(60, "Sync pulse ${pair.syncPairId} could not record a skipped sample".toString()) {
                sample = writeSample(ec, pair, now, [statusEnumId: SKIPPED, allowanceMinutes: allowance, pastAllowanceCount: openCount, message: message])
                stampPair(ec, pair, now, SKIPPED)
            }
            return [status: SKIPPED, sample: sample, newlyPast: [], closed: [], waitingCount: null, oldest: null,
                    pastCount: openCount, priorPastCount: openCount, skipped: false]
        } catch (Throwable t) {
            logger.warn("Sync pulse {} could not record a skipped sample: {}", pair?.syncPairId, t.message ?: t.toString())
            clearErrors(ec)
            return [status: SKIPPED, sample: null, newlyPast: [], closed: [], skipped: false]
        }
    }

    /** Opens, touches and closes the pair's orders, writes the OK sample and stamps the pair, in one transaction. */
    private static Map settle(def ec, Map pair, Instant now, int allowance, List<Map> open, Map sorted, Set<String> present,
                              Set<String> unasked) {
        Timestamp stamp = Timestamp.from(now)
        Map<String, Map> openByKey = open.collectEntries { Map o -> [(o.recordKey as String): o] }
        List<Map> waiting = sorted.waiting as List<Map>
        List<Map> past = sorted.past as List<Map>
        List<Map> closed = [], newlyPast = [], stillOpen = []
        Map sample = null, oldest = null
        ec.transaction.runUseOrBegin(120, "Sync pulse ${pair.syncPairId} could not save its sample".toString()) {
            open.each { Map o ->
                String key = o.recordKey as String
                if (present.contains(key)) {
                    closed << updateOrder(ec, o, [closedStamp: stamp, lastCheckedStamp: stamp])
                } else if (unasked.contains(key)) {
                    stillOpen << o
                } else {
                    stillOpen << updateOrder(ec, o, [lastCheckedStamp: stamp])
                }
            }
            past.each { Map r ->
                if (openByKey.containsKey(r.key as String)) return
                Map created = createOrder(ec, pair, r, stamp)
                newlyPast << created
                stillOpen << created
            }
            oldest = oldestOf(waiting, stillOpen, now)
            String message = (sorted.unaskedCount as int) > 0 ? "${sorted.unaskedCount} keys could not be asked".toString() : null
            sample = writeSample(ec, pair, now, [statusEnumId: OK, allowanceMinutes: allowance, waitingCount: waiting.size(),
                    oldestWaitingMinutes: oldest != null ? ((oldest.ageMinutes as Long).intValue()) : null,
                    pastAllowanceCount: stillOpen.size(), message: message])
            stampPair(ec, pair, now, OK)
        }
        return [status   : OK, sample: sample, newlyPast: newlyPast, closed: closed, waitingCount: waiting.size(), oldest: oldest,
                pastCount: stillOpen.size(), priorPastCount: open.size(), skipped: false]
    }

    /**
     * The oldest record still waiting: the waiting records and the orders open after this pulse (their source created
     * stamp), by age. Null when none.
     */
    private static Map oldestOf(List<Map> waiting, List<Map> open, Instant now) {
        Map best = null
        waiting.each { Map r ->
            long age = r.ageMinutes as long
            if (best == null || age > (best.ageMinutes as long)) best = [orderName: r.orderName ?: r.key, ageMinutes: age]
        }
        open.each { Map o ->
            Timestamp created = o.sourceCreatedStamp as Timestamp
            if (created == null) return
            long age = Duration.between(created.toInstant(), now).toMinutes()
            if (best == null || age > (best.ageMinutes as long)) best = [orderName: o.orderName ?: o.recordKey, ageMinutes: age]
        }
        return best
    }

    private static Map failed(def ec, Map pair, Instant now, Integer allowance, List<Map> open, Collection errors) {
        List<String> reasons = (errors ?: []).collect { normalize(it) }.findAll { it } as List<String>
        String message = reasons ? reasons.join("; ") : "The pulse could not read."
        if (message.length() > 4000) message = message.take(3997) + "..."
        int openCount = open?.size() ?: 0
        Map sample = null
        ec.transaction.runUseOrBegin(60, "Sync pulse ${pair.syncPairId} could not record its failure".toString()) {
            sample = writeSample(ec, pair, now, [statusEnumId: FAILED, allowanceMinutes: allowance, pastAllowanceCount: openCount, message: message])
            stampPair(ec, pair, now, FAILED)
        }
        return [status   : FAILED, sample: sample, newlyPast: [], closed: [], waitingCount: null, oldest: null,
                pastCount: openCount, priorPastCount: openCount, skipped: false]
    }

    // ------------------------------------------------------------------------------------------- reads

    /**
     * The pair's check, when it may pulse: present in the pair's tenant, active, not archived, and HotWax orders
     * (OMS_GQL) on the pair's upstream side. Else null.
     */
    static Map pulseableAutomation(def ec, Map pair) {
        String tenantId = normalize(pair?.get("companyUserGroupId"))
        String automationId = normalize(pair?.get("automationId"))
        String upstreamSide = normalize(pair?.get("upstreamSide"))
        if (!tenantId || !automationId || !upstreamSide) return null
        // list(), not one(): one() drops a non-PK condition beside the full PK, and with it the tenant pin.
        def automation = TenantScopedFinder.findGlobalUnscopedPerRequest(ec, "darpan.reconciliation.ReconciliationAutomation", WHY)
                .condition("automationId", automationId).condition("companyUserGroupId", tenantId).useCache(false).list().find()
        if (automation == null) return null
        if (normalize(automation.get("isActive")) != "Y" || normalize(automation.get("isArchived")) == "Y") return null
        def source = TenantScopedFinder.findGlobalUnscopedPerRequest(ec, "darpan.reconciliation.ReconciliationAutomationSource", WHY)
                .condition("automationId", automationId).condition("fileSide", upstreamSide)
                .condition("companyUserGroupId", tenantId).useCache(false).list().find()
        if (normalize(source?.get("systemEnumId")) != HOTWAX_ORDERS) return null
        return new LinkedHashMap(automation.getMap())
    }

    /** The tenant's local date at now and its peak periods from that date. */
    private static Map tenantDay(def ec, String tenantId, Instant now) {
        ZoneId zone = TenantAccessSupport.tenantTimeZoneFor(ec, tenantId)
        LocalDate date = now.atZone(zone).toLocalDate()
        return [date: date, windows: PeakPeriodLoader.load(ec, tenantId, date)]
    }

    /** The pair's PeakPeriodSync rows by peakPeriodId. */
    private static Map<String, Map> periodRows(def ec, Map pair) {
        return (TenantScopedFinder.findGlobalUnscopedPerRequest(ec, "darpan.pulse.PeakPeriodSync", WHY)
                .condition("syncPairId", normalize(pair.syncPairId)).condition("companyUserGroupId", normalize(pair.companyUserGroupId))
                .useCache(false).list() ?: []).collectEntries { [(normalize(it.get("peakPeriodId"))): it.getMap()] }
    }

    private static List<Map> openOrders(def ec, Map pair) {
        return (TenantScopedFinder.findGlobalUnscopedPerRequest(ec, "darpan.pulse.SyncPulseOrder", WHY)
                .condition("syncPairId", normalize(pair.syncPairId)).condition("companyUserGroupId", normalize(pair.companyUserGroupId))
                .condition("closedStamp", null).orderBy("pulseOrderId").useCache(false).list() ?: [])
                .collect { new LinkedHashMap(it.getMap()) }
    }

    private static Map readUpstream(def ec, Map pair, Map automation, Instant from, Instant thru) {
        Closure hook = upstreamRead
        return (Map) (hook != null ? hook.call(ec, pair, automation, from, thru) : SyncPulseReader.readUpstream(ec, pair, automation, from, thru))
    }

    private static Map presentInTarget(def ec, Map pair, Map automation, Collection<String> keys) {
        Closure hook = targetRead
        return (Map) (hook != null ? hook.call(ec, pair, automation, keys) : SyncPulseReader.presentInTarget(ec, pair, automation, keys))
    }

    // ------------------------------------------------------------------------------------------- writes

    /** fields: statusEnumId, allowanceMinutes, waitingCount, oldestWaitingMinutes, pastAllowanceCount, message. */
    private static Map writeSample(def ec, Map pair, Instant now, Map fields) {
        def sample = ec.entity.makeValue("darpan.pulse.SyncPulseSample")
        sample.setAll([waitingCount: null, oldestWaitingMinutes: null, message: null] + fields +
                [syncPairId : normalize(pair.syncPairId), companyUserGroupId: normalize(pair.companyUserGroupId),
                 sampleStamp: Timestamp.from(now)])
        sample.setSequencedIdPrimary()
        sample.create()
        return new LinkedHashMap(sample.getMap())
    }

    private static Map createOrder(def ec, Map pair, Map record, Timestamp stamp) {
        def order = ec.entity.makeValue("darpan.pulse.SyncPulseOrder")
        order.setAll([syncPairId        : normalize(pair.syncPairId), companyUserGroupId: normalize(pair.companyUserGroupId),
                      recordKey         : record.key, orderName: normalize(record.orderName) ?: record.key,
                      sourceCreatedStamp: Timestamp.from(record.createdAt as Instant), firstPastStamp: stamp, lastCheckedStamp: stamp])
        order.setSequencedIdPrimary()
        order.create()
        return new LinkedHashMap(order.getMap())
    }

    private static Map updateOrder(def ec, Map order, Map fields) {
        def row = TenantScopedFinder.findGlobalUnscopedPerRequest(ec, "darpan.pulse.SyncPulseOrder", WHY)
                .condition("pulseOrderId", order.pulseOrderId).condition("companyUserGroupId", order.companyUserGroupId)
                .useCache(false).list().find()
        if (row == null) return order
        row.setAll(fields)
        row.update()
        return new LinkedHashMap(row.getMap())
    }

    private static void stampPair(def ec, Map pair, Instant now, String status) {
        def row = TenantScopedFinder.findGlobalUnscopedPerRequest(ec, "darpan.pulse.SyncPair", WHY)
                .condition("syncPairId", normalize(pair.syncPairId)).condition("companyUserGroupId", normalize(pair.companyUserGroupId))
                .useCache(false).list().find()
        if (row == null) return
        row.setAll([lastPulseStamp: Timestamp.from(now), lastPulseStatusEnumId: status])
        row.update()
    }

    // ------------------------------------------------------------------------------------------- small parts

    private static Set<String> keySet(Collection keys) {
        Set<String> out = new LinkedHashSet<String>()
        (keys ?: []).each { Object k -> String key = normalize(k); if (key) out << key }
        return out
    }

    private static void clearErrors(def ec) {
        try { ec?.message?.clearErrors() } catch (Throwable ignored) { }
    }
}
