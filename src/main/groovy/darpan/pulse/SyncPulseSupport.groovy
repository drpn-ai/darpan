package darpan.pulse

import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.ops.ErrorChatSupport
import darpan.peak.AllowanceSpec
import darpan.peak.PeakPeriodLoader
import darpan.peak.PeakPeriodSupport
import darpan.peak.PeakWindow
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.automation.RunAdmission
import darpan.reconciliation.automation.SourceSystemConnectorSupport
import darpan.reconciliation.notification.TenantNotificationSupport
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.entity.EntityCondition
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
 * <p><strong>Admission.</strong> A due pulse goes through the automation scheduler's RunAdmission as a light run
 * (submitLight, DAR-BE-379) with label {@code pulse <syncPairId>}: its own small lane, outside the run caps, so the
 * tenant's audit filling its share does not skip it. Refused (the light lane full, or memory holding new starts) writes a
 * SKIPPED sample and is not queued; a pulse of the same pair still in flight is skipped silently. An admitted pulse runs on
 * RunAdmission's thread with a fresh execution context, made there and destroyed after, as the pair's tenant.</p>
 *
 * <p>Groovy, not XML actions: the scan reads every tenant's pairs through the unscoped finder with a reason, hands work
 * to RunAdmission's threads, and the pulse holds its own execution context and authz frame.</p>
 */
class SyncPulseSupport {
    private static final Logger logger = LoggerFactory.getLogger(SyncPulseSupport)

    static final String OK = "SPS_OK"
    static final String FAILED = "SPS_FAILED"
    static final String SKIPPED = "SPS_SKIPPED"
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
     * about everything and correctness never depends on it. Write-through to SyncPulsePresentKey (task 14): a cold cache
     * (after a restart) loads the pair's rows still inside the window, unless SyncPair.presentSignature differs, then the
     * rows are deleted and it starts empty. signature is what the target answers by (the check, its upstream
     * side, its compare scope and that scope's last update); a change drops the set. Allowance and cadence are not in it: presence in the target does not
     * depend on them. A key deleted in the target after it was seen keeps counting as present until it prunes out of the
     * window (about allowance + 2 x cadence + 7 minutes); the daily check covers deletions.
     */
    private static class PresentSet {
        final String signature
        final String tenantId
        final Map<String, Instant> keys = new ConcurrentHashMap<String, Instant>()
        PresentSet(String signature, String tenantId) { this.signature = signature; this.tenantId = tenantId }
    }

    private static final ConcurrentHashMap<String, PresentSet> PRESENT_BY_PAIR = new ConcurrentHashMap<String, PresentSet>()

    static void resetForTests() { PRESENT_BY_PAIR.clear(); presentCap = DEFAULT_PRESENT_CAP; resetHooks() }

    /** Test seam: forgets the in-memory sets only (a restart); the table rows stay. */
    static void forgetPresentMemoryForTests() { PRESENT_BY_PAIR.clear() }

    /** The pair's remembered keys (a copy); empty when none. For tests and the engine. */
    static Set<String> presentKeysOf(String syncPairId) { return new LinkedHashSet<String>(PRESENT_BY_PAIR.get(syncPairId)?.keys?.keySet() ?: []) }

    /** Forgets a pair's present keys in memory only (the pair's rows are the caller's to delete). */
    static void dropPresentMemory(String syncPairId) { if (syncPairId) PRESENT_BY_PAIR.remove(syncPairId) }

    /** Forgets a pair's present keys: memory and rows (pair inactive, no longer pulseable or deleted). */
    static void dropPresent(def ec, String tenantId, String syncPairId) {
        if (!syncPairId) return
        PRESENT_BY_PAIR.remove(syncPairId)
        writePresent(ec, "drop") { deletePresentRows(ec, tenantId, syncPairId, null) }
    }

    private static def presentRows(def ec, String tenantId, String syncPairId) {
        return TenantScopedFinder.findGlobalUnscopedPerRequest(ec, "darpan.pulse.SyncPulsePresentKey", WHY)
                .condition("companyUserGroupId", tenantId).condition("syncPairId", syncPairId).useCache(false)
    }

    /** Deletes the pair's rows, or only those whose source was created before olderThan (found, then deleted row by row). */
    private static void deletePresentRows(def ec, String tenantId, String syncPairId, Instant olderThan) {
        def find = presentRows(ec, tenantId, syncPairId)
        if (olderThan != null) find.condition("sourceCreatedStamp", EntityCondition.LESS_THAN, Timestamp.from(olderThan))
        find.deleteAll()
    }

    /** Runs a present-key write in its own transaction. It never throws: one WARN, and the in-memory set stays. */
    private static boolean writePresent(def ec, String what, Closure write) {
        try {
            ec.transaction.runRequireNew(60, "Sync pulse present keys (${what}) could not be written".toString(), write)
            return true
        } catch (Throwable t) {
            logger.warn("Sync pulse present keys ({}) not written: {}", what, t.message ?: t.toString())
            clearErrors(ec)
            return false
        }
    }

    /**
     * The pair's set for these settings. Warm cache under the same signature: as is. A signature change (warm or cold)
     * deletes the pair's rows and stores the new signature; cold under the same signature loads the rows inside the window.
     */
    private static PresentSet presentSetFor(def ec, Map pair, String signature, Instant windowFrom) {
        String syncPairId = normalize(pair.syncPairId)
        String tenantId = normalize(pair.companyUserGroupId)
        PresentSet set = PRESENT_BY_PAIR.get(syncPairId)
        if (set != null && set.signature == signature) return set
        PresentSet fresh = new PresentSet(signature, tenantId)
        try {
            String stored = null
            if (set == null) {
                def row = TenantScopedFinder.findGlobalUnscopedPerRequest(ec, "darpan.pulse.SyncPair", WHY)
                        .condition("syncPairId", syncPairId).condition("companyUserGroupId", tenantId).useCache(false).list()?.find()
                stored = normalize(row?.get("presentSignature"))
            }
            if (set == null && stored == signature) {
                presentRows(ec, tenantId, syncPairId).condition("sourceCreatedStamp", EntityCondition.GREATER_THAN_EQUAL_TO, Timestamp.from(windowFrom))
                        .list().each { row ->
                    if (fresh.keys.size() < presentCap) fresh.keys.put(row.get("recordKey") as String, (row.get("sourceCreatedStamp") as Timestamp).toInstant())
                }
            } else {
                writePresent(ec, "reset") {
                    deletePresentRows(ec, tenantId, syncPairId, null)
                    def pairRow = TenantScopedFinder.findGlobalUnscopedPerRequest(ec, "darpan.pulse.SyncPair", WHY)
                            .condition("syncPairId", syncPairId).condition("companyUserGroupId", tenantId).useCache(false).list()?.find()
                    if (pairRow != null) { pairRow.setAll([presentSignature: signature]); pairRow.update() }
                }
            }
        } catch (Throwable t) {
            logger.warn("Sync pulse present keys of {} not loaded, starting empty: {}", syncPairId, t.message ?: t.toString())
            clearErrors(ec)
            fresh.keys.clear()
        }
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
            scopeStamp = TenantScopedFinder.findGlobalUnscopedPerRequest(ec, "darpan.rule.RuleSetCompareScope", WHY).condition("compareScopeId", scopeId)
                    .condition("companyUserGroupId", normalize(pair.companyUserGroupId)).useCache(false).list()?.find()?.get("lastUpdatedStamp")
        }
        return [normalize(pair.automationId), normalize(pair.upstreamSide), scopeId, normalize(automation?.get("ruleSetId")), scopeStamp].join("|")
    }

    /** Adds the keys the target returned, with their created times, up to PRESENT_CAP; drops keys older than windowFrom. */
    private static Map<String, Instant> rememberPresent(PresentSet set, Collection<String> returned, Map<String, Instant> createdByKey, Instant windowFrom) {
        Map<String, Instant> added = [:]
        returned.each { String k ->
            Instant at = createdByKey.get(k)
            if (at == null || at.isBefore(windowFrom)) return
            if (set.keys.size() >= presentCap && !set.keys.containsKey(k)) return
            set.keys.put(k, at)
            added.put(k, at)
        }
        return added
    }

    private static final int PRESENT_WRITE_CHUNK = 500

    /** Stores the keys just remembered, 500 per transaction; never fails the pulse. */
    private static void storePresent(def ec, Map pair, Map<String, Instant> added) {
        if (!added) return
        String tenantId = normalize(pair.companyUserGroupId)
        String syncPairId = normalize(pair.syncPairId)
        // chunks, each in its own transaction: one bad key or a timeout loses at most its chunk, not every key of a big batch
        added.entrySet().toList().collate(PRESENT_WRITE_CHUNK).each { List<Map.Entry<String, Instant>> chunk ->
            writePresent(ec, "add ${chunk.size()} keys".toString()) {
                // one read per chunk; a key already stored (another pulse got there first) is skipped
                Set<String> stored = presentRows(ec, tenantId, syncPairId).condition("recordKey", EntityCondition.IN, chunk.collect { it.key }).list()
                        .collect { it.get("recordKey") as String } as Set<String>
                chunk.each { Map.Entry<String, Instant> e ->
                    if (stored.contains(e.key)) return
                    ec.entity.makeValue("darpan.pulse.SyncPulsePresentKey").setAll([syncPairId: syncPairId, recordKey: e.key,
                            companyUserGroupId: tenantId, sourceCreatedStamp: Timestamp.from(e.value)]).create()
                }
            }
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
            Set<String> activeIds = pairs.collect { normalize(it.get("syncPairId")) } as Set<String>
            // an inactive or deleted pair drops its set and its rows
            new ArrayList<String>(PRESENT_BY_PAIR.keySet()).findAll { !activeIds.contains(it) }.each { String id ->
                PresentSet gone = PRESENT_BY_PAIR.remove(id)
                if (gone != null) writePresent(ec, "drop") { deletePresentRows(ec, gone.tenantId, id, null) }
            }
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
        def factory = ec.factory
        Map pulsePair = new LinkedHashMap(pair)
        Map admitted = admission.submitLight(label, { -> runAdmitted(factory, pulsePair, now) } as Runnable)
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
     * calls authenticate="true" extracts), and the pair's tenant, sampling at the scan's now. Never throws.
     */
    private static void runAdmitted(def factory, Map pair, Instant scanNow) {
        def pulseEc = factory.getExecutionContext()
        ArtifactExecutionInfo aei = null
        try {
            Instant now = scanNow
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
     * pair. A pair whose check is missing, archived, inactive or whose upstream endpoint a pulse cannot read (canPulse) is skipped silently (skipped: true,
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
            if (automation == null) { dropPresent(ec, tenantId, syncPairId); return empty }
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
            PresentSet known = presentSetFor(ec, pair, presentSignature(ec, pair, automation), window.from as Instant)
            known.keys.entrySet().removeIf { Map.Entry<String, Instant> e -> e.value.isBefore(window.from as Instant) }
            if (presentRows(ec, tenantId, syncPairId).condition("sourceCreatedStamp", EntityCondition.LESS_THAN, Timestamp.from(window.from as Instant)).count() > 0) {
                writePresent(ec, "prune") { deletePresentRows(ec, tenantId, syncPairId, window.from as Instant) }
            }
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
            if (settled.status == OK) {
                storePresent(ec, pair, rememberPresent(known, returned.findAll { toAsk.contains(it) }, createdByKey, window.from as Instant))
            }
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

    /** SyncPulseSample.message is text-medium: VARCHAR(255). */
    static final int MESSAGE_MAX = 255

    /**
     * DAR-BE-382. A sample's message cut to fit its column, "..." marking the cut. Prod 2026-11-26: a Spark error past 255
     * characters rolled back the FAILED sample itself (the old cut was at 4,000).
     */
    static String sampleMessage(String message) {
        if (message == null || message.length() <= MESSAGE_MAX) return message
        return message.take(MESSAGE_MAX - 3) + "..."
    }

    /** A SKIPPED sample (admission refused) and the pair's stamp; never throws. */
    static Map recordSkipped(def ec, Map pair, Instant now, Integer allowance, String message) {
        try {
            int openCount = openOrders(ec, pair).size()
            Map sample = null
            ec.transaction.runUseOrBegin(60, "Sync pulse ${pair.syncPairId} could not record a skipped sample".toString()) {
                sample = writeSample(ec, pair, now, [statusEnumId: SKIPPED, allowanceMinutes: allowance, pastAllowanceCount: openCount, message: sampleMessage(message)])
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
                    closed << updateOrder(ec, o, [closedStamp: stamp, lastCheckedStamp: stamp, closeReason: ARRIVED])
                } else if (unasked.contains(key)) {
                    stillOpen << o
                } else if (leftTheCheck(o, now, allowance, windowFrom, upstreamKeys)) {
                    // never "synced": it left the check (cancelled or filtered upstream, or the daily check owns it now)
                    dropped << updateOrder(ec, o, [closedStamp: stamp, lastCheckedStamp: stamp, closeReason: DROPPED])
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
        String message = sampleMessage(reasons ? reasons.join("; ") : "The pulse could not read.")
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
     * The pair's check, when it may pulse: present in the pair's tenant, active, not archived, and an endpoint a pulse can
     * read upstream (canPulse) on the pair's upstream side. Else null.
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
        if (!canPulse(ec, normalize(source?.get("systemEnumId")))) return null
        return new LinkedHashMap(automation.getMap())
    }

    /**
     * DAR-BE-377. The fields a pulse reads an upstream record of systemEnumId by, from its connector row: [createdField (when
     * the record was created), nameField (what to show, or null for the key)]; null when the connector sets no
     * pulseCreatedField, so a pulse cannot read that endpoint upstream. Which systems qualify is data, never a name in code.
     */
    static Map pulseFieldsOf(def ec, String systemEnumId) {
        Map connector = systemEnumId ? SourceSystemConnectorSupport.resolveBySystemEnumId(ec, systemEnumId) : null
        String created = normalize(connector?.get("pulseCreatedField"))
        return created ? [createdField: created, nameField: normalize(connector.get("pulseNameField"))] : null
    }

    /** DAR-BE-377. A pulse can read systemEnumId as a check's upstream side (pulseFieldsOf is set). */
    static boolean canPulse(def ec, String systemEnumId) { return pulseFieldsOf(ec, systemEnumId) != null }

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
    private static final String PAST_RED = "#d93025"
    private static final String CLEAR_GREEN = "#188038"
    /** SyncPulseOrder.closeReason: it reached the target, or it left the check (cancelled, filtered, aged out). */
    static final String ARRIVED = "ARRIVED"
    static final String DROPPED = "DROPPED"
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

    /** "hourly", "every 2h", "every 30m". */
    static String everyWords(Integer minutes) {
        int m = Math.max(0, minutes ?: 0)
        if (m == 60) return "hourly"
        return "every ${allowanceWords(m)}".toString()
    }

    /** One pair's period line: "HotWax → NetSuite · hourly · late after 6h", "(2 pairs)" when several say the same. */
    static String itemWords(Map i) {
        return "${i.label}${DOT}${everyWords(i.cadence as Integer)}${DOT}late after ${allowanceWords(i.allowance as Integer)}${pairsWords(i.count as int)}".toString()
    }

    private static String oldestWords(Map oldest) {
        return oldest != null ? ageWords(oldest.get("ageMinutes") as Number) : "none"
    }

    /** The past message: two lines. */
    static String pastText(String label, Integer allowance, int pastCount, Map oldest, Integer waiting) {
        return "${label}: ${pastCount} past the ${allowanceWords(allowance)} allowance.\n".toString() +
                "Oldest ${oldest?.get('orderName') ?: 'unknown'}, ${oldestWords(oldest)}${DOT}${waiting ?: 0} waiting".toString()
    }

    static String clearText(String label, Integer allowance, Map oldest, Integer waiting, int closedCount, int droppedCount = 0) {
        return "${label}: caught up. Nothing past the ${allowanceWords(allowance)} allowance.\n".toString() +
                "${waiting ?: 0} waiting${DOT}oldest ${oldestWords(oldest)}${DOT}${closedCount} ${closedCount == 1 ? 'finding' : 'findings'} closed".toString() +
                (droppedCount > 0 ? "${DOT}${droppedCount} left the check".toString() : "")
    }

    /** The pulse's page (DAR-UI-154), naming its tenant so the app switches into it (DAR-BE-293); null without the app's address. */
    static String pulseUrl(String base, String syncPairId, String tenantId) {
        String b = normalize(base)?.replaceAll('/+$', '')
        if (!b || !normalize(syncPairId)) return null
        String url = "${b}/pulses/${URLEncoder.encode(normalize(syncPairId), 'UTF-8')}".toString()
        return normalize(tenantId) ? "${url}?tenantId=${URLEncoder.encode(normalize(tenantId), 'UTF-8')}".toString() : url
    }

    /** A post's last line opens its pulse (DAR-BE-391): "<url|Open the pulse>" is a link in Google Chat and Slack text alike. */
    static String withPulseLink(String text, String url) {
        return url ? "${text}\n<${url}|Open the pulse>".toString() : text
    }

    /** DAR-BE-391 (Aditi 2026-11-27, seen in her test room): a past post's card: the pair, how many are past, the oldest in red. */
    static Map pastCard(String label, Integer allowance, int pastCount, Map oldest) {
        String name = normalize(oldest?.get("orderName")) ?: "unknown"
        return [title  : label, subtitle: "${pastCount} past the ${allowanceWords(allowance)} allowance".toString(),
                widgets: [[decoratedText: [topLabel : "Oldest", wrapText: true, startIcon: [materialIcon: [name: "schedule"]],
                                           text     : "<font color=\"${PAST_RED}\">${esc(name)}</font>${DOT}waiting ${oldestWords(oldest)}".toString()]]]]
    }

    /** DAR-BE-391: a caught-up post's card: the pair, nothing past, what the episode closed in green. No waiting row (Aditi). */
    static Map clearCard(String label, Integer allowance, int closedCount, int droppedCount) {
        String closed = "${closedCount} ${closedCount == 1 ? 'finding' : 'findings'} closed"
        String text = "<font color=\"${CLEAR_GREEN}\">${closed}</font>" + (droppedCount > 0 ? "${DOT}${droppedCount} left the check" : "")
        return [title  : label, subtitle: "Caught up${DOT}nothing past the ${allowanceWords(allowance)} allowance".toString(),
                widgets: [[decoratedText: [topLabel: "This episode", text: text.toString(), wrapText: true, startIcon: [materialIcon: [name: "task_alt"]]]]]]
    }

    /**
     * The message a pulse post becomes. With a card: the card alone in Google Chat (Darpan header, its rows, "Open the pulse"),
     * the words in fallbackText (Chat's notifications), and slackText (the words + the link) for a Slack room. Without one (the
     * hourly summary): the words and the link as text.
     */
    static Map pulseMessage(Map decision, String link) {
        String text = decision.text as String
        Map card = decision.card as Map
        if (card == null) return [text: withPulseLink(text, link)]
        List<Map> widgets = new ArrayList<Map>(card.widgets as List<Map>)
        if (link) widgets << [buttonList: [buttons: [[text: "Open the pulse", onClick: [openLink: [url: link]]]]]]
        return [fallbackText: text, slackText: withPulseLink(text, link),
                cardsV2     : [[cardId: "pulse-${decision.kind}".toString(), card: [
                        header  : [title: card.title, subtitle: card.subtitle, imageUrl: "https://drpn.ai/favicon.png", imageType: "CIRCLE"],
                        sections: [[widgets: widgets]]]]]]
    }

    static String summaryText(String label, Integer waiting, Map oldest, int pastCount) {
        return "${label}: ${waiting ?: 0} waiting${DOT}oldest ${oldestWords(oldest)}${DOT}${pastCount} past allowance".toString()
    }

    /**
     * DAR-BE-382. The alerts-room notice for two pulses in a row that did not finish (FAILED or SKIPPED): the tenant, the
     * pair, and the last pulse's own reason. It never goes to the pair's chat space, which is the client's room.
     */
    static String failureText(String tenantId, String label, String lastReason) {
        return ("${tenantId}${DOT}${label}: 2 pulses in a row did not finish." +
                (lastReason ? "\nLast: ${lastReason}" : "")).toString()
    }

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
            return twoInARow ? [kind: "failure", text: null, lastPostedPastCount: priorPosted]
                    : [kind: "none", text: null, lastPostedPastCount: priorPosted]
        }
        if (status != OK) return [kind: "none", text: null, lastPostedPastCount: priorPosted]
        int past = (result.get("pastCount") ?: 0) as int
        Map oldest = result.get("oldest") as Map
        Integer waiting = result.get("waitingCount") as Integer
        if (prior == 0 && past > 0) {
            return [kind: "past", text: pastText(label, allowance, past, oldest, waiting), card: pastCard(label, allowance, past, oldest),
                    lastPostedPastCount: past]
        }
        if (past == 0 && prior > 0) {
            // the episode's own counts when the caller read them; this pulse's alone otherwise (an episode that began before they were kept)
            int closedCount = (result.get("episodeClosed") ?: ((result.get("closed") ?: []) as List).size()) as int
            int droppedCount = (result.get("episodeDropped") ?: ((result.get("dropped") ?: []) as List).size()) as int
            return [kind: "clear", text: clearText(label, allowance, oldest, waiting, closedCount, droppedCount),
                    card: clearCard(label, allowance, closedCount, droppedCount), lastPostedPastCount: 0]
        }
        // a count that changed but stays above zero announces nothing (the hourly summary carries it); the stored count follows
        Integer stored = past != prior ? (Integer) past : priorPosted
        if (hourlySummary && topOfHour) {
            return [kind: "summary", text: summaryText(label, waiting, oldest, past), lastPostedPastCount: stored]
        }
        return [kind: "none", text: null, lastPostedPastCount: stored]
    }

    /**
     * Posts what a finished pulse earned to the pair's chat space: a past/clear change or a summary at the first pulse of a
     * tenant-local hour. DAR-BE-382: two pulses in a row that did not finish go to Darpan's alerts room instead
     * (ErrorChatSupport.postAlertText), never the pair's chat space, with or without one. Nothing else without a
     * chatSpaceId; a failed post is logged and never fails the pulse.
     */
    private static void postAfterPulse(def ec, Map pair, Instant now, Map result) {
        try {
            if (result == null || result.get("skipped") == true || result.get("sample") == null) return
            String tenantId = normalize(pair?.get("companyUserGroupId"))
            def row = tenantFind(ec, "darpan.pulse.SyncPair", [syncPairId: normalize(pair?.get("syncPairId"))], tenantId)
            String chatSpaceId = normalize(row?.get("chatSpaceId"))
            if (!tenantId || row == null) return
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
            Timestamp episodeStart = row.get("episodeStartStamp") as Timestamp
            if (episodeStart != null && (result.get("pastCount") ?: 0) as int == 0 && (row.get("lastPostedPastCount") ?: 0) as int > 0) {
                result = new LinkedHashMap(result) + episodeCounts(ec, tenantId, normalize(pair.syncPairId), episodeStart)
            }
            Map decision = decidePost(names.label as String, result, row.get("lastPostedPastCount") as Integer,
                    samples.collect { normalize(it.get("statusEnumId")) }, normalize(row.get("hourlySummary")) == "Y", topOfHour)
            if (decision.kind == "failure") {
                String lastReason = normalize((result.get("sample") as Map)?.get("message"))
                if (!ErrorChatSupport.postAlertText(ec, failureText(tenantId, names.label as String, lastReason))) {
                    logger.warn("Sync pulse {} (tenant {}): 2 pulses in a row did not finish; no alerts room took the notice",
                            pair.syncPairId, tenantId)
                }
                return
            }
            if (!chatSpaceId) return
            String link = decision.text == null ? null
                    : pulseUrl(TenantNotificationSupport.resolveAppBaseUrl(ec), normalize(pair.syncPairId), tenantId)
            boolean posted = decision.text == null || postMessage(ec, tenantId, chatSpaceId, pulseMessage(decision, link))
            Integer wanted = decision.lastPostedPastCount as Integer
            // the episode opens with the 0 -> >0 post and ends with the >0 -> 0 post
            boolean episodeChange = posted && (decision.kind == "past" || decision.kind == "clear")
            if (posted && (wanted != (row.get("lastPostedPastCount") as Integer) || episodeChange)) {
                ec.transaction.runUseOrBegin(30, "Sync pulse ${pair.syncPairId} could not store its posted count".toString()) {
                    row.set("lastPostedPastCount", wanted)
                    if (decision.kind == "past") row.set("episodeStartStamp", Timestamp.from(now))
                    else if (decision.kind == "clear") row.set("episodeStartStamp", null)
                    row.update()
                }
            }
        } catch (Throwable t) {
            logger.warn("Sync pulse {} chat post skipped: {}", pair?.syncPairId, t.message ?: t.toString())
            clearErrors(ec)
        }
    }

    /** [episodeClosed, episodeDropped]: the pair's orders closed since the episode began, split by how they closed. */
    private static Map episodeCounts(def ec, String tenantId, String pairId, Timestamp episodeStart) {
        List<Map> closedRows = (TenantScopedFinder.findGlobalUnscopedPerRequest(ec, "darpan.pulse.SyncPulseOrder", WHY)
                .condition("syncPairId", pairId).condition("companyUserGroupId", tenantId)
                .condition("closedStamp", EntityCondition.GREATER_THAN_EQUAL_TO, episodeStart).useCache(false).list() ?: [])
                .collect { it.getMap() as Map }
        return [episodeClosed : closedRows.count { normalize(it.get("closeReason")) != DROPPED },
                episodeDropped: closedRows.count { normalize(it.get("closeReason")) == DROPPED }]
    }

    /** One post; false when it could not be delivered. Never throws. */
    /** A pulse or period message (a card with fallbackText, or plain text) to a room: a Slack room takes the words as text. Never throws. */
    private static boolean postMessage(def ec, String tenantId, String chatSpaceId, Map message) {
        if (!tenantId || !chatSpaceId || !message) return false
        try {
            def space = TenantScopedFinder.findOneOwnedByTenant(ec, "darpan.reconciliation.TenantChatSpace", "chatSpaceId", chatSpaceId,
                    tenantId, "sync pulse post — owner re-checked against the tenant")
            Map destination = TenantNotificationSupport.describeDestination(ec, tenantId, chatSpaceId, space)
            if (destination == null) return false
            boolean slack = destination.deliveryMode == TenantNotificationSupport.DELIVERY_SLACK_BOT ||
                    destination.chatProviderEnumId == TenantNotificationSupport.PROVIDER_SLACK
            // slackText (a pulse card's words + link) is Slack's; Google Chat would refuse the unknown field
            Map result = TenantNotificationSupport.deliverToDestination(destination,
                    slack ? [text: message.slackText ?: message.text ?: message.fallbackText] : message.findAll { it.key != "slackText" })
            if (result?.ok == false) logger.warn("Sync pulse post to space {} returned status {}", chatSpaceId, result?.statusCode)
            return result != null && result.get("ok") != false
        } catch (Throwable t) {
            logger.warn("Sync pulse post to space {} failed: {}", chatSpaceId, t.message ?: t.toString())
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

    /**
     * DAR-BE-388. What each client room is told about a peak period: one line per pair in that room the period touches and
     * that is checked during it, "HotWax → NetSuite · hourly · late after 6h"; the same line for several pairs
     * once, with "(N pairs)". A row's null value is the pair's own; a cadence of 0 is off. pairsById holds only the pairs that
     * can pulse, so the pairs a period carries only to move their check's start (returns, month-close checks) are never said.
     * Peak hours are Darpan's own scheduling and are never said. Rooms with nothing to say are left out.
     */
    static Map<String, List<String>> clientLines(List<Map> rows, Map<String, Map> pairsById, Map<String, String> labelByPair) {
        return clientItems(rows, pairsById, labelByPair).collectEntries { String space, List<Map> items ->
            [(space): items.collect { Map i -> itemWords(i) }]
        } as Map<String, List<String>>
    }

    /** clientLines' items by room: [label, cadence, allowance, count], in row order. */
    static Map<String, List<Map>> clientItems(List<Map> rows, Map<String, Map> pairsById, Map<String, String> labelByPair) {
        Map<String, Map<List, Map>> bySpace = [:]
        (rows ?: []).each { Map row ->
            String id = normalize(row.get("syncPairId"))
            Map pair = pairsById?.get(id)
            String space = normalize(pair?.get("chatSpaceId"))
            if (!space) return
            Integer cadence = (row.get("pulseEveryMinutes") != null ? row.get("pulseEveryMinutes") : pair.get("pulseEveryMinutes")) as Integer
            if (cadence == null || cadence <= 0) return
            Integer allowance = (row.get("allowanceMinutes") != null ? row.get("allowanceMinutes") : pair.get("allowanceMinutes")) as Integer
            String label = labelByPair?.get(id) ?: id
            Map<List, Map> items = bySpace.computeIfAbsent(space) { new LinkedHashMap<List, Map>() }
            Map item = items.computeIfAbsent([label, cadence, allowance]) { [label: label, cadence: cadence, allowance: allowance, count: 0] }
            item.count = (item.count as int) + 1
        }
        return bySpace.collectEntries { String space, Map<List, Map> items -> [(space): new ArrayList<Map>(items.values())] } as Map<String, List<Map>>
    }

    private static String pairsWords(int count) { return count > 1 ? " (${count} pairs)".toString() : "" }

    /**
     * A period's chat message: a Google Chat card alone (Darpan header, the period as title, "Today only" / "Until Nov 29" /
     * "Normal settings are back" as subtitle, one line per item; Aditi 2026-11-27: "no message... just card"), with the same
     * words as plain lines in fallbackText, which Chat shows only in notifications and which a Slack room takes as its text.
     * With no items (an end) it is a plain message (Aditi: "ended one can be a message... that as a card is not good").
     */
    static Map periodMessage(String title, String subtitle, List<Map> items) {
        List<String> lines = (items ?: []).collect { Map i -> itemWords(i) }
        String text = lines ? ([title + ". " + subtitle + ":"] + lines).join("\n") : "${title}.${subtitle ? ' ' + subtitle + '.' : ''}".toString()
        if (!lines) return [text: text]
        Map card = [header: [title: title, imageUrl: "https://drpn.ai/favicon.png", imageType: "CIRCLE"] + (subtitle ? [subtitle: subtitle] : [:]),
                    sections: [[widgets: lines.collect { String line -> [decoratedText: [text: esc(line), wrapText: true]] }]]]
        return [fallbackText: text, cardsV2: [[cardId: "peak-period", card: card]]]
    }

    private static String esc(Object value) {
        return (value ?: "").toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    /**
     * Once per period per boundary, to each room with something to say (clientLines): "<name> started. Until <MMM d>:"
     * ("Today only:" for a one-day period) + that room's lines, and "<name> ended. Normal settings are back." to the rooms its
     * start spoke to, except a room another period covering today speaks to (back to back: the next start says the new
     * settings); just "<name> ended." while another period covers today, since its other settings still hold. The period's startPostedStamp / endPostedStamp say the boundary was done, posted or not; a tenant with no
     * chat space stamps nothing (a space added later still gets the start). An end is only done for a start that was.
     */
    private static void postPeriodBoundaries(def ec, List<Map> pairs, Instant now, Map<String, Map> tenantDays) {
        pairs.groupBy { normalize(it.get("companyUserGroupId")) }.each { String tenantId, List<Map> tenantPairs ->
            if (!tenantId) return
            try {
                if (!tenantPairs.any { normalize(it.get("chatSpaceId")) }) return
                Map day = tenantDays.computeIfAbsent(tenantId) { String t -> tenantDay(ec, t, now) }
                LocalDate today = day.date as LocalDate
                List periods = (TenantScopedFinder.findGlobalUnscopedPerRequest(ec, "darpan.peak.PeakPeriod", WHY)
                        .condition("companyUserGroupId", tenantId).useCache(false).list() ?: [])
                        .findAll { normalize(it.get("isActive")) != "N" && dateOf(it.get("fromDate")) != null && dateOf(it.get("thruDate")) != null }
                        .sort { dateOf(it.get("fromDate")) }
                Map<String, Map<String, List<Map>>> linesByPeriod = [:]
                Closure<Map<String, List<Map>>> linesOf = { def p ->
                    String id = normalize(p.get("peakPeriodId"))
                    if (!linesByPeriod.containsKey(id)) linesByPeriod[id] = periodLines(ec, tenantId, tenantPairs, id)
                    return linesByPeriod[id]
                }
                periods.each { period ->
                    LocalDate from = dateOf(period.get("fromDate")), thru = dateOf(period.get("thruDate"))
                    boolean started = period.get("startPostedStamp") != null
                    boolean ended = period.get("endPostedStamp") != null
                    String name = normalize(period.get("periodName"))
                    Map<String, Map> messageBySpace = [:]
                    String stampField
                    if (!started && !ended && !today.isBefore(from) && !today.isAfter(thru)) {
                        String until = thru == from ? "Today only" : "Until ${MONTH_DAY.format(thru)}".toString()
                        linesOf(period).each { String space, List<Map> items -> messageBySpace[space] = periodMessage("${name} started".toString(), until, items) }
                        stampField = "startPostedStamp"
                    } else if (started && !ended && today.isAfter(thru)) {
                        List others = periods.findAll { it != period && !today.isBefore(dateOf(it.get("fromDate"))) && !today.isAfter(dateOf(it.get("thruDate"))) }
                        Set<String> continuing = others.collectMany { linesOf(it).keySet() } as Set<String>
                        Map message = periodMessage("${name} ended".toString(), others ? null : "Normal settings are back", [])
                        linesOf(period).keySet().findAll { !continuing.contains(it) }.each { String space -> messageBySpace[space] = message }
                        stampField = "endPostedStamp"
                    } else {
                        return
                    }
                    messageBySpace.each { String space, Map message -> postMessage(ec, tenantId, space, message) }
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

    /** clientItems for one period of the tenant: its PeakPeriodSync rows over the tenant's active pairs that can pulse. */
    private static Map<String, List<Map>> periodLines(def ec, String tenantId, List<Map> tenantPairs, String periodId) {
        List<Map> rows = (TenantScopedFinder.findGlobalUnscopedPerRequest(ec, "darpan.pulse.PeakPeriodSync", WHY)
                .condition("peakPeriodId", periodId).condition("companyUserGroupId", tenantId).useCache(false).list() ?: [])
                .collect { it.getMap() as Map }.sort { normalize(it.get("syncPairId")) }
        Set<String> touched = rows.collect { normalize(it.get("syncPairId")) } as Set<String>
        Map<String, Map> pairsById = [:]
        Map<String, String> labels = [:]
        tenantPairs.findAll { touched.contains(normalize(it.get("syncPairId"))) && normalize(it.get("chatSpaceId")) }.each { Map p ->
            if (pulseableAutomation(ec, p) == null) return
            String id = normalize(p.get("syncPairId"))
            pairsById[id] = p
            Map n = pairNames(ec, p)
            labels[id] = "${n.upstream} ${ARROW} ${n.target}".toString() // a pulse reads orders, so the record word is left out
        }
        return clientItems(rows, pairsById, labels)
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
