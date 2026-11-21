package darpan.pulse

import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.peak.AllowanceSpec
import darpan.peak.PeakPeriodLoader
import darpan.peak.PeakPeriodSupport
import darpan.peak.PeakWindow
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.automation.RunAdmission
import darpan.reconciliation.notification.TenantNotificationSupport
import org.moqui.context.ArtifactExecutionInfo
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
    /** One tick of the job. */
    static final Duration TICK = Duration.ofMinutes(5)

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

    // ------------------------------------------------------------------------------------------- present keys

    /** Most keys remembered per pair (the cap is per pair); beyond it nothing more is added and those keys are simply asked about again. */
    static final int DEFAULT_PRESENT_CAP = 200000
    private static volatile int presentCap = DEFAULT_PRESENT_CAP

    /** Seam for tests: a smaller cap. resetForTests restores the default. */
    static void setPresentCapForTests(int cap) { presentCap = cap }

    /**
     * Per pair, the keys the target has already confirmed present, each with its source created time (DAR-BE-328 task 13:
     * a pulse asks the target only about keys not in here). In memory only: empty after a restart, so the first pulse asks
     * about everything and correctness never depends on it. signature is what the target answers by (the check, its upstream
     * side, its compare scope and that scope's last update); a change drops the set. Allowance and cadence are not in it: presence in the target does not
     * depend on them. A key deleted in the target after it was seen keeps counting as present until it prunes out of the
     * window (about allowance + 2 x cadence + 7 minutes); the daily check covers deletions.
     */
    private static class PresentSet {
        final String signature
        final Map<String, Instant> keys = new ConcurrentHashMap<String, Instant>()
        PresentSet(String signature) { this.signature = signature }
    }

    private static final ConcurrentHashMap<String, PresentSet> PRESENT_BY_PAIR = new ConcurrentHashMap<String, PresentSet>()

    static void resetForTests() { PRESENT_BY_PAIR.clear(); presentCap = DEFAULT_PRESENT_CAP }

    /** The pair's remembered keys (a copy); empty when none. For tests and the engine. */
    static Set<String> presentKeysOf(String syncPairId) { return new LinkedHashSet<String>(PRESENT_BY_PAIR.get(syncPairId)?.keys?.keySet() ?: []) }

    /** Forgets a pair's present keys (settings changed, pair inactive or deleted). */
    static void dropPresent(String syncPairId) { if (syncPairId) PRESENT_BY_PAIR.remove(syncPairId) }

    /** The pair's set for these settings: a set made under other settings is dropped and a fresh one started. */
    private static PresentSet presentSetFor(String syncPairId, String signature) {
        PresentSet set = PRESENT_BY_PAIR.get(syncPairId)
        if (set != null && set.signature == signature) return set
        PresentSet fresh = new PresentSet(signature)
        PRESENT_BY_PAIR.put(syncPairId, fresh)
        return fresh
    }

    /**
     * The check, side and compare scope the target is asked through. The scope row's own last update changes when its keys
     * or targets are edited; the automation's stamp is not used (every scheduled fire moves it).
     */
    private static String presentSignature(def ec, Map pair, Map automation) {
        String scopeId = normalize(automation?.get("compareScopeId"))
        Object scopeStamp = null
        if (scopeId) {
            scopeStamp = ec.entity.find("darpan.rule.RuleSetCompareScope").condition("compareScopeId", scopeId)
                    .condition("companyUserGroupId", normalize(pair.companyUserGroupId)).disableAuthz().useCache(false).list()?.find()?.get("lastUpdatedStamp")
        }
        return [normalize(pair.automationId), normalize(pair.upstreamSide), scopeId, normalize(automation?.get("ruleSetId")), scopeStamp].join("|")
    }

    /** Adds the keys the target returned, with their created times, up to PRESENT_CAP; drops keys older than windowFrom. */
    private static void rememberPresent(PresentSet set, Collection<String> returned, Map<String, Instant> createdByKey, Instant windowFrom) {
        returned.each { String k ->
            Instant at = createdByKey.get(k)
            if (at == null || at.isBefore(windowFrom)) return
            if (set.keys.size() >= presentCap && !set.keys.containsKey(k)) return
            set.keys.put(k, at)
        }
    }

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

    /**
     * The read window of one pulse: (now − allowance − margin, now]. The margin is the longest gap between two pulses after
     * a skip, 2 × cadence + DUE_SLACK + one tick, so an order passing the allowance in that gap is still read. A null
     * cadence adds no margin.
     */
    static Map pulseWindow(Instant now, int allowanceMinutes, Integer cadenceMinutes) {
        long cadence = Math.max(0, cadenceMinutes ?: 0)
        long margin = cadence > 0 ? 2L * cadence + DUE_SLACK.toMinutes() + TICK.toMinutes() : 0L
        long back = Math.max(0, allowanceMinutes) + margin
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
     * @return [scanTimestamp, pairCount, dueCount, submittedCount, queuedCount, skippedCount]
     */
    static Map runDuePulses(def ec, Timestamp nowTimestamp) {
        Instant now = (nowTimestamp ?: (ec.user?.nowTimestamp as Timestamp) ?: new Timestamp(System.currentTimeMillis())).toInstant()
        Map out = [scanTimestamp: Timestamp.from(now), pairCount: 0, dueCount: 0, submittedCount: 0, queuedCount: 0, skippedCount: 0]
        try {
            RunAdmission admission = AutomationExecutionSupport.getRunAdmission()
            List pairs = (TenantScopedFinder.findGlobalUnscopedPerRequest(ec, "darpan.pulse.SyncPair", SCAN_WHY)
                    .useCache(false).list() ?: []).findAll { (normalize(it.get("isActive")) ?: "Y") == "Y" }
            out.pairCount = pairs.size()
            Set<String> activeIds = pairs.collect { normalize(it.get("syncPairId")) } as Set<String>
            PRESENT_BY_PAIR.keySet().retainAll(activeIds)   // an inactive or deleted pair drops its set
            if (!pairs) return out
            admission.drain()
            Map<String, Map> tenantDays = [:]
            pairs.each { row ->
                Map pair = new LinkedHashMap(row.getMap())
                try {
                    String outcome = scanPair(ec, admission, pair, now, tenantDays)
                    if (outcome) out.dueCount = (out.dueCount as int) + 1
                    if (outcome == "submitted") out.submittedCount = (out.submittedCount as int) + 1
                    if (outcome == "queued") out.queuedCount = (out.queuedCount as int) + 1
                    if (outcome == SKIPPED) out.skippedCount = (out.skippedCount as int) + 1
                } catch (Throwable t) {
                    logger.warn("Sync pulse scan skipped pair {} (tenant {}): {}", pair.syncPairId, pair.companyUserGroupId,
                            t.message ?: t.toString())
                    clearErrors(ec)
                }
            }
            try {
                postPeriodBoundaries(ec, pairs.collect { new LinkedHashMap(it.getMap()) }, now, tenantDays)
            } catch (Throwable t) {
                logger.warn("Sync pulse period posts failed: {}", t.message ?: t.toString())
                clearErrors(ec)
            }
        } catch (Throwable t) {
            logger.error("Sync pulse scan failed: {}", t.message ?: t.toString())
            clearErrors(ec)
        }
        return out
    }

    /** One pair of the scan: null when not due or skipped silently, else "submitted", "queued" or SKIPPED. */
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
        // A pulse that waits for a slot (canStartNow raced another start) samples the moment it starts, never the scan's now.
        AtomicBoolean waited = new AtomicBoolean(false)
        Map admitted = admission.submit(tenantId, label, { -> runAdmitted(factory, pulsePair, waited.get() ? null : now) } as Runnable)
        if (admitted.submitted == true && admitted.queued == true) {
            waited.set(true)
            logger.debug("Sync pulse {} waits for a slot (tenant {}, position {}); it samples when it starts", syncPairId, tenantId,
                    admitted.position)
            return "queued"
        }
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
     * calls authenticate="true" extracts), and the pair's tenant. A null now (the pulse waited for its slot) is the moment it
     * starts. Never throws.
     */
    private static void runAdmitted(def factory, Map pair, Instant scanNow) {
        def pulseEc = factory.getExecutionContext()
        ArtifactExecutionInfo aei = null
        try {
            Instant now = scanNow ?: ((pulseEc.user.nowTimestamp as Timestamp)?.toInstant() ?: Instant.now())
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
        Map out = pulse(ec, pair, now)
        postAfterPulse(ec, pair, now, out)
        return out
    }

    private static Map pulse(def ec, Map pair, Instant now) {
        String tenantId = normalize(pair?.get("companyUserGroupId"))
        String syncPairId = normalize(pair?.get("syncPairId"))
        Map empty = [status: null, sample: null, newlyPast: [], closed: [], waitingCount: null, oldest: null, pastCount: null,
                     priorPastCount: null, skipped: true]
        if (!tenantId || !syncPairId || now == null) return empty
        Integer allowance = null
        List<Map> open = null
        try {
            Map automation = pulseableAutomation(ec, pair)
            if (automation == null) { dropPresent(syncPairId); return empty }
            Map day = tenantDay(ec, tenantId, now)
            Map effective = effectiveValues(pair, day.windows as List<PeakWindow>, periodRows(ec, pair), day.date as LocalDate)
            allowance = effective.allowanceMinutes as Integer
            claimPulse(ec, pair, now)
            open = openOrders(ec, pair)
            Map window = pulseWindow(now, allowance, effective.pulseEveryMinutes as Integer)

            Map up = readUpstream(ec, pair, automation, window.from as Instant, window.thru as Instant)
            if (up?.ok != true) return failed(ec, pair, now, allowance, open, up?.errors)
            List<Map> records = (up.records ?: []) as List<Map>

            Set<String> keys = new LinkedHashSet<String>()
            Map<String, Instant> createdByKey = [:]
            records.each { Map r -> String k = normalize(r.key); if (k) { keys << k; createdByKey.put(k, r.createdAt as Instant) } }
            open.each { Map o ->
                String k = o.recordKey as String
                keys << k
                if (!createdByKey.containsKey(k) && o.sourceCreatedStamp != null) createdByKey.put(k, (o.sourceCreatedStamp as Timestamp).toInstant())
            }
            // the target is asked only about keys it has not already confirmed; a confirmed key still counts as present
            PresentSet known = presentSetFor(syncPairId, presentSignature(ec, pair, automation))
            known.keys.entrySet().removeIf { Map.Entry<String, Instant> e -> e.value.isBefore(window.from as Instant) }
            Set<String> confirmed = keys.findAll { known.keys.containsKey(it) } as Set<String>
            Set<String> toAsk = keys.findAll { !confirmed.contains(it) } as Set<String>
            Map target = presentInTarget(ec, pair, automation, toAsk)
            if (target?.ok != true) return failed(ec, pair, now, allowance, open, target?.errors)

            Set<String> returned = keySet(target.present as Collection)
            Set<String> present = new LinkedHashSet<String>(returned)
            present.addAll(confirmed)
            Set<String> unasked = keySet(target.unasked as Collection)
            Map sorted = classify(records, present, now, allowance, unasked)
            Set<String> upstreamKeys = new LinkedHashSet<String>()
            records.each { Map r -> String k = normalize(r.key); if (k) upstreamKeys << k }
            Map settled = settle(ec, pair, now, allowance, open, sorted, present, unasked, window.from as Instant, upstreamKeys)
            if (settled.status == OK) rememberPresent(known, returned.findAll { toAsk.contains(it) }, createdByKey, window.from as Instant)
            return settled
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
            Map out = [status: SKIPPED, sample: sample, newlyPast: [], closed: [], waitingCount: null, oldest: null,
                       pastCount: openCount, priorPastCount: openCount, skipped: false]
            postAfterPulse(ec, pair, now, out)
            return out
        } catch (Throwable t) {
            logger.warn("Sync pulse {} could not record a skipped sample: {}", pair?.syncPairId, t.message ?: t.toString())
            clearErrors(ec)
            return [status: SKIPPED, sample: null, newlyPast: [], closed: [], skipped: false]
        }
    }

    /** Opens, touches and closes the pair's orders, writes the OK sample and stamps the pair, in one transaction. */
    private static Map settle(def ec, Map pair, Instant now, int allowance, List<Map> open, Map sorted, Set<String> present,
                              Set<String> unasked, Instant windowFrom, Set<String> upstreamKeys) {
        Timestamp stamp = Timestamp.from(now)
        Map<String, Map> openByKey = open.collectEntries { Map o -> [(o.recordKey as String): o] }
        List<Map> waiting = sorted.waiting as List<Map>
        List<Map> past = sorted.past as List<Map>
        List<Map> closed = [], dropped = [], newlyPast = [], stillOpen = []
        Map sample = null, oldest = null
        ec.transaction.runUseOrBegin(120, "Sync pulse ${pair.syncPairId} could not save its sample".toString()) {
            open.each { Map o ->
                String key = o.recordKey as String
                if (present.contains(key)) {
                    closed << updateOrder(ec, o, [closedStamp: stamp, lastCheckedStamp: stamp])
                } else if (unasked.contains(key)) {
                    stillOpen << o
                } else if (leftTheCheck(o, now, allowance, windowFrom, upstreamKeys)) {
                    // never "synced": it left the check (cancelled or filtered upstream, or the daily check owns it now)
                    dropped << updateOrder(ec, o, [closedStamp: stamp, lastCheckedStamp: stamp])
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
        return [status   : OK, sample: sample, newlyPast: newlyPast, closed: closed, dropped: dropped, waitingCount: waiting.size(), oldest: oldest,
                pastCount: stillOpen.size(), priorPastCount: open.size(), skipped: false]
    }

    /**
     * Whether an open order that did not arrive in the target has left the check: its source created time is inside the
     * pulse's read window yet the upstream read did not return it, or it is older than the window and has been open longer
     * than the allowance + 24h (the daily check owns it from there).
     */
    static boolean leftTheCheck(Map order, Instant now, int allowanceMinutes, Instant windowFrom, Set<String> upstreamKeys) {
        Object created = order.get("sourceCreatedStamp")
        if (created != null) {
            Instant createdAt = created instanceof Instant ? (Instant) created : ((Date) created).toInstant()
            if (!createdAt.isBefore(windowFrom)) return !upstreamKeys.contains(order.get("recordKey") as String)
        }
        Object first = order.get("firstPastStamp")
        if (first == null) return false
        Instant firstAt = first instanceof Instant ? (Instant) first : ((Date) first).toInstant()
        return Duration.between(firstAt, now) > Duration.ofMinutes(Math.max(0, allowanceMinutes)).plus(Duration.ofHours(24))
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
                      otherSystemId     : normalize(record.otherKey),
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

    /**
     * Claims the pulse: stamps lastPulseStamp in its OWN transaction before any read, so a sample write that fails later
     * cannot roll the stamp back and leave the pair due on every tick.
     */
    private static void claimPulse(def ec, Map pair, Instant now) {
        ec.transaction.runRequireNew(30, "Sync pulse ${pair.syncPairId} could not claim its pulse".toString()) {
            def row = TenantScopedFinder.findGlobalUnscopedPerRequest(ec, "darpan.pulse.SyncPair", WHY)
                    .condition("syncPairId", normalize(pair.syncPairId)).condition("companyUserGroupId", normalize(pair.companyUserGroupId))
                    .useCache(false).list().find()
            if (row == null) return
            row.setAll([lastPulseStamp: Timestamp.from(now)])
            row.update()
        }
    }

    private static void stampPair(def ec, Map pair, Instant now, String status) {
        def row = TenantScopedFinder.findGlobalUnscopedPerRequest(ec, "darpan.pulse.SyncPair", WHY)
                .condition("syncPairId", normalize(pair.syncPairId)).condition("companyUserGroupId", normalize(pair.companyUserGroupId))
                .useCache(false).list().find()
        if (row == null) return
        row.setAll([lastPulseStamp: Timestamp.from(now), lastPulseStatusEnumId: status])
        row.update()
    }

    // ------------------------------------------------------------------------------------------- chat posts

    private static final String ARROW = "→"
    private static final String DOT = " · "
    private static final String DASH = "–"
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)

    /** "6h" for whole hours, else "90m". */
    static String allowanceWords(Integer minutes) {
        int m = Math.max(0, minutes ?: 0)
        return (m >= 60 && m % 60 == 0) ? "${m.intdiv(60)}h".toString() : "${m}m".toString()
    }

    /** "6h 40m", "6h", "40m". */
    static String ageWords(Number minutes) {
        long m = Math.max(0L, minutes?.longValue() ?: 0L)
        long h = m.intdiv(60L)
        long r = m % 60L
        if (h == 0L) return "${r}m".toString()
        return (r == 0L ? "${h}h" : "${h}h ${r}m").toString()
    }

    private static String cadenceWords(Integer minutes) {
        if (minutes == null) return null
        if (minutes <= 0) return "pulse off"
        if (minutes == 60) return "pulse every hour"
        if (minutes % 60 == 0) return "pulse every ${minutes.intdiv(60)} hours".toString()
        return "pulse every ${minutes} min".toString()
    }

    private static String oldestWords(Map oldest) {
        return oldest != null ? ageWords(oldest.get("ageMinutes") as Number) : "none"
    }

    /** The past message: two lines. */
    static String pastText(String label, Integer allowance, int pastCount, Map oldest, Integer waiting) {
        return "${label}: ${pastCount} past the ${allowanceWords(allowance)} allowance.\n".toString() +
                "Oldest ${oldest?.get('orderName') ?: 'unknown'}, ${oldestWords(oldest)}${DOT}${waiting ?: 0} waiting".toString()
    }

    static String clearText(String label, Integer allowance, Map oldest, Integer waiting, int closedCount) {
        return "${label}: caught up. Nothing past the ${allowanceWords(allowance)} allowance.\n".toString() +
                "${waiting ?: 0} waiting${DOT}oldest ${oldestWords(oldest)}${DOT}${closedCount} findings closed".toString()
    }

    static String summaryText(String label, Integer waiting, Map oldest, int pastCount) {
        return "${label}: ${waiting ?: 0} waiting${DOT}oldest ${oldestWords(oldest)}${DOT}${pastCount} past allowance".toString()
    }

    static String failureText(String label) { return "${label}: the sync pulse could not read for 2 pulses.".toString() }

    /**
     * The post a finished pulse earns, or null. result is runPulse's map; priorPosted the pair's lastPostedPastCount (null =
     * never posted); recent the pair's newest sample statuses, newest first (the pulse's own sample first).
     *
     * @return [kind: past|clear|summary|failure|none, text, lastPostedPastCount (to store; unchanged when kind has none)]
     */
    static Map decidePost(String label, Map result, Integer priorPosted, List<String> recent, boolean hourlySummary,
                          boolean topOfHour) {
        String status = result?.get("status")
        int prior = priorPosted ?: 0
        Integer allowance = (result?.get("sample") as Map)?.get("allowanceMinutes") as Integer
        if (status == FAILED || status == SKIPPED) {
            List<String> r = recent ?: []
            boolean twoInARow = r.size() >= 2 && r[0] != OK && r[1] != OK && (r.size() == 2 || r[2] == OK)
            return twoInARow ? [kind: "failure", text: failureText(label), lastPostedPastCount: priorPosted]
                    : [kind: "none", text: null, lastPostedPastCount: priorPosted]
        }
        if (status != OK) return [kind: "none", text: null, lastPostedPastCount: priorPosted]
        int past = (result.get("pastCount") ?: 0) as int
        Map oldest = result.get("oldest") as Map
        Integer waiting = result.get("waitingCount") as Integer
        if (prior == 0 && past > 0) {
            return [kind: "past", text: pastText(label, allowance, past, oldest, waiting), lastPostedPastCount: past]
        }
        if (past == 0 && prior > 0) {
            return [kind: "clear", text: clearText(label, allowance, oldest, waiting, ((result.get("closed") ?: []) as List).size()),
                    lastPostedPastCount: 0]
        }
        // a count that changed but stays above zero announces nothing (the hourly summary carries it); the stored count follows
        Integer stored = past != prior ? (Integer) past : priorPosted
        if (hourlySummary && topOfHour) {
            return [kind: "summary", text: summaryText(label, waiting, oldest, past), lastPostedPastCount: stored]
        }
        return [kind: "none", text: null, lastPostedPastCount: stored]
    }

    /**
     * Posts what a finished pulse earned to the pair's chat space: a past/clear change, a summary at the first pulse of a
     * tenant-local hour, or the failure notice. Nothing without a chatSpaceId; a failed post is logged and never fails the pulse.
     */
    private static void postAfterPulse(def ec, Map pair, Instant now, Map result) {
        try {
            if (result == null || result.get("skipped") == true || result.get("sample") == null) return
            String tenantId = normalize(pair?.get("companyUserGroupId"))
            def row = tenantFind(ec, "darpan.pulse.SyncPair", [syncPairId: normalize(pair?.get("syncPairId"))], tenantId)
            String chatSpaceId = normalize(row?.get("chatSpaceId"))
            if (!tenantId || !chatSpaceId) return
            List<Map> samples = (TenantScopedFinder.findGlobalUnscopedPerRequest(ec, "darpan.pulse.SyncPulseSample", WHY)
                    .condition("syncPairId", normalize(pair.syncPairId)).condition("companyUserGroupId", tenantId)
                    .orderBy("-sampleStamp").limit(3).useCache(false).list() ?: []).collect { it.getMap() as Map }
            ZoneId zone = TenantAccessSupport.tenantTimeZoneFor(ec, tenantId)
            boolean topOfHour = true
            if (samples.size() >= 2) {
                Instant prev = (samples[1].get("sampleStamp") as Timestamp).toInstant()
                topOfHour = !prev.atZone(zone).truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                        .equals(now.atZone(zone).truncatedTo(java.time.temporal.ChronoUnit.HOURS))
            }
            Map names = pairNames(ec, pair)
            Map decision = decidePost(names.label as String, result, row.get("lastPostedPastCount") as Integer,
                    samples.collect { normalize(it.get("statusEnumId")) }, normalize(row.get("hourlySummary")) == "Y", topOfHour)
            boolean posted = decision.text == null || postChat(ec, tenantId, chatSpaceId, decision.text as String)
            Integer wanted = decision.lastPostedPastCount as Integer
            if (posted && wanted != (row.get("lastPostedPastCount") as Integer)) {
                ec.transaction.runUseOrBegin(30, "Sync pulse ${pair.syncPairId} could not store its posted count".toString()) {
                    row.set("lastPostedPastCount", wanted)
                    row.update()
                }
            }
        } catch (Throwable t) {
            logger.warn("Sync pulse {} chat post skipped: {}", pair?.syncPairId, t.message ?: t.toString())
            clearErrors(ec)
        }
    }

    /** One post; false when it could not be delivered. Never throws. */
    private static boolean postChat(def ec, String tenantId, String chatSpaceId, String text) {
        if (!tenantId || !chatSpaceId || !text) return false
        try {
            Map result = TenantNotificationSupport.postToChatSpace(ec, tenantId, chatSpaceId, [text: text])
            return result != null && result.get("ok") != false
        } catch (Throwable t) {
            logger.warn("Sync pulse chat post to space {} failed: {}", chatSpaceId, t.message ?: t.toString())
            clearErrors(ec)
            return false
        }
    }

    /** "<upstream system> -> <target system> orders" for chat and the facades; "Source -> Target orders" when unresolved. Never throws. */
    static String pairLabel(def ec, Map pair) {
        try {
            return pairNames(ec, pair).get("label") as String
        } catch (Throwable t) {
            logger.warn("Sync pulse pair label fell back: {}", t.message ?: t.toString())
            clearErrors(ec)
            return "Source ${ARROW} Target orders".toString()
        }
    }

    /** [waitingCount, pastAllowanceCount]: the latest OK sample's waiting count (0 if none) and the pair's open orders. */
    static Map pairCounts(def ec, String tenantId, String syncPairId) {
        String tenant = normalize(tenantId)
        String pairId = normalize(syncPairId)
        if (!tenant || !pairId) return [waitingCount: 0, pastAllowanceCount: 0]
        def sample = TenantScopedFinder.findGlobalUnscopedPerRequest(ec, "darpan.pulse.SyncPulseSample", WHY)
                .condition("syncPairId", pairId).condition("companyUserGroupId", tenant).condition("statusEnumId", OK)
                .orderBy("-sampleStamp").limit(1).useCache(false).list()?.find()
        Integer waiting = sample?.get("waitingCount") as Integer
        long open = TenantScopedFinder.findGlobalUnscopedPerRequest(ec, "darpan.pulse.SyncPulseOrder", WHY)
                .condition("syncPairId", pairId).condition("companyUserGroupId", tenant).condition("closedStamp", null)
                .useCache(false).count()
        return [waitingCount: waiting ?: 0, pastAllowanceCount: (int) open]
    }

    /** [upstream, target, label]: the system names of the pair's check, "<upstream> -> <target> orders". */
    private static Map pairNames(def ec, Map pair) {
        String tenantId = normalize(pair.get("companyUserGroupId"))
        String upstreamSide = normalize(pair.get("upstreamSide"))
        List sources = TenantScopedFinder.findGlobalUnscopedPerRequest(ec, "darpan.reconciliation.ReconciliationAutomationSource", WHY)
                .condition("automationId", normalize(pair.get("automationId"))).condition("companyUserGroupId", tenantId)
                .useCache(false).list() ?: []
        String up = systemName(ec, sources.find { normalize(it.get("fileSide")) == upstreamSide }?.get("systemEnumId")) ?: "Source"
        String target = systemName(ec, sources.find { normalize(it.get("fileSide")) != upstreamSide }?.get("systemEnumId")) ?: "Target"
        return [upstream: up, target: target, label: "${up} ${ARROW} ${target} orders".toString()]
    }

    private static String systemName(def ec, Object enumId) {
        return TenantNotificationSupport.resolveSystemSourceName(ec, enumId) ?: normalize(enumId)
    }

    private static def tenantFind(def ec, String entity, Map keys, String tenantId) {
        def find = TenantScopedFinder.findGlobalUnscopedPerRequest(ec, entity, WHY)
        keys.each { String k, Object v -> find.condition(k, v) }
        // list(), not one(): one() drops the tenant condition beside a full PK.
        return find.condition("companyUserGroupId", tenantId).useCache(false).list().find()
    }

    /** "NetSuite sync allowance 6h . pulse every hour . heavy runs wait 08:00-23:00"; empty when the period overrides nothing. */
    static String overridesLine(Map period, List<Map> syncRows, Map<String, Map> namesByPair) {
        List<String> parts = []
        List<Map> withAllowance = (syncRows ?: []).findAll { it.get("allowanceMinutes") != null }
        Set<Integer> allowances = withAllowance.collect { it.get("allowanceMinutes") as Integer } as LinkedHashSet
        if (allowances.size() == 1) {
            String target = namesByPair?.get(withAllowance[0].get("syncPairId"))?.get("target") ?: "Sync"
            parts << "${target} sync allowance ${allowanceWords(allowances.first())}".toString()
        } else if (allowances.size() > 1) {
            withAllowance.each { Map r ->
                String label = namesByPair?.get(r.get("syncPairId"))?.get("label") ?: r.get("syncPairId")
                parts << "${label} allowance ${allowanceWords(r.get('allowanceMinutes') as Integer)}".toString()
            }
        }
        Set<Integer> cadences = (syncRows ?: []).findAll { it.get("pulseEveryMinutes") != null }
                .collect { it.get("pulseEveryMinutes") as Integer } as LinkedHashSet
        cadences.each { Integer c -> parts << cadenceWords(c) }
        String from = normalize(period.get("peakHoursStart")), thru = normalize(period.get("peakHoursEnd"))
        if (from && thru) parts << "heavy runs wait ${from}${DASH}${thru}".toString()
        return parts.join(DOT)
    }

    /**
     * Once per period per boundary: "<name> started. Until <MMM d>:" + its overrides, and "<name> ended. Normal settings are
     * back.", to every distinct chat space of the tenant's active pairs. The period's startPostedStamp / endPostedStamp say it
     * was done; a tenant with no chat space posts and stamps nothing (a space added later still gets the start). An end is only
     * posted for a start that was.
     */
    private static void postPeriodBoundaries(def ec, List<Map> pairs, Instant now, Map<String, Map> tenantDays) {
        pairs.groupBy { normalize(it.get("companyUserGroupId")) }.each { String tenantId, List<Map> tenantPairs ->
            if (!tenantId) return
            try {
                List<String> spaces = tenantPairs.collect { normalize(it.get("chatSpaceId")) }.findAll { it }.unique()
                if (!spaces) return
                Map day = tenantDays.computeIfAbsent(tenantId) { String t -> tenantDay(ec, t, now) }
                LocalDate today = day.date as LocalDate
                List periods = TenantScopedFinder.findGlobalUnscopedPerRequest(ec, "darpan.peak.PeakPeriod", WHY)
                        .condition("companyUserGroupId", tenantId).useCache(false).list() ?: []
                periods.findAll { normalize(it.get("isActive")) != "N" }.each { period ->
                    LocalDate from = dateOf(period.get("fromDate")), thru = dateOf(period.get("thruDate"))
                    if (from == null || thru == null) return
                    boolean started = period.get("startPostedStamp") != null
                    boolean ended = period.get("endPostedStamp") != null
                    String text = null
                    String stampField = null
                    if (!started && !ended && !today.isBefore(from) && !today.isAfter(thru)) {
                        text = startText(ec, tenantId, tenantPairs, period.getMap() as Map, thru)
                        stampField = "startPostedStamp"
                    } else if (started && !ended && today.isAfter(thru)) {
                        text = "${normalize(period.get('periodName'))} ended. Normal settings are back.".toString()
                        stampField = "endPostedStamp"
                    }
                    if (text == null) return
                    spaces.each { String space -> postChat(ec, tenantId, space, text) }
                    ec.transaction.runUseOrBegin(30, "Sync pulse could not stamp the ${stampField} of a peak period".toString()) {
                        period.set(stampField, Timestamp.from(now))
                        period.update()
                    }
                }
            } catch (Throwable t) {
                logger.warn("Sync pulse period posts for tenant {} skipped: {}", tenantId, t.message ?: t.toString())
                clearErrors(ec)
            }
        }
    }

    private static String startText(def ec, String tenantId, List<Map> tenantPairs, Map period, LocalDate thru) {
        String periodId = normalize(period.get("peakPeriodId"))
        Map<String, Map> names = [:]
        tenantPairs.each { Map p -> names[normalize(p.get("syncPairId"))] = pairNames(ec, p) }
        List<Map> rows = (TenantScopedFinder.findGlobalUnscopedPerRequest(ec, "darpan.pulse.PeakPeriodSync", WHY)
                .condition("peakPeriodId", periodId).condition("companyUserGroupId", tenantId).useCache(false).list() ?: [])
                .collect { it.getMap() as Map }.findAll { names.containsKey(normalize(it.get("syncPairId"))) }
                .sort { normalize(it.get("syncPairId")) }
        String head = "${normalize(period.get('periodName'))} started. Until ${MONTH_DAY.format(thru)}:".toString()
        String line = overridesLine(period, rows, names)
        return line ? head + "\n" + line : head
    }

    private static LocalDate dateOf(Object value) {
        if (value == null) return null
        if (value instanceof java.sql.Date) return ((java.sql.Date) value).toLocalDate()
        if (value instanceof java.util.Date) return new java.sql.Date(((java.util.Date) value).time).toLocalDate()
        return LocalDate.parse(value.toString().take(10))
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
