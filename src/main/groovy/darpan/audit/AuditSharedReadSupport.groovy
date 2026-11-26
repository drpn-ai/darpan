package darpan.audit

import darpan.facade.reconciliation.RunVerificationSupport
import darpan.reconciliation.conclusion.ExcludedRecordsSidecar
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.Timestamp

/**
 * DAR-BE-320 (read economy). One read that several audit checks of a morning share: gorjana R10, R11 and R12 read one
 * NetSuite query over one window from one start. The first check to need it reads it, single flight under an
 * AuditSharedRead row; every other check waits for that read and copies its file into its own run folder, where its own
 * re-check appends to it and its own Keep and tree judge it. The key holds everything the read depends on, so a check
 * whose window or start differs reads on its own.
 *
 * A read that fails fails every check that waited on it, with its reason (it is not read again three times). A check
 * that finds a failed read when it starts, a read past its expiry, or a reader silent past STALE_MILLIS, reads it again.
 */
class AuditSharedReadSupport {

    private static final Logger logger = LoggerFactory.getLogger(AuditSharedReadSupport.class)

    static final String READING = "AUDSHR_READING"
    static final String DONE = "AUDSHR_DONE"
    static final String FAILED = "AUDSHR_FAILED"

    /** A claim that fails this many times in a row is no lost race: the read fails with its reason. */
    static final int MAX_LOST_CLAIMS = 3
    static final long DEFAULT_POLL_MILLIS = 5_000L
    static final long DEFAULT_WAIT_MILLIS = 3L * 3_600_000L
    // below the wait, so a waiter can take over from a reader that went silent (it also does as soon as the reader's run ends)
    static final long DEFAULT_STALE_MILLIS = 2L * 3_600_000L
    // checks of one morning start within minutes of each other; a run hours later (a person's re-run) reads afresh
    static final long DEFAULT_SHARE_MILLIS = 2L * 3_600_000L

    /** How often a waiting check looks again; how long it waits; when a reader is taken as gone; how long a read is shared. */
    static long pollMillis = DEFAULT_POLL_MILLIS
    static long waitMillis = DEFAULT_WAIT_MILLIS
    static long staleMillis = DEFAULT_STALE_MILLIS
    static long shareMillis = DEFAULT_SHARE_MILLIS

    static void resetForTests() {
        pollMillis = DEFAULT_POLL_MILLIS
        waitMillis = DEFAULT_WAIT_MILLIS
        staleMillis = DEFAULT_STALE_MILLIS
        shareMillis = DEFAULT_SHARE_MILLIS
    }

    /** The share key: SHA-256 of the parts (tenant, system, connection, extract service, window, start), in order. */
    static String key(List<Object> parts) {
        String text = parts.collect { it instanceof java.util.Date ? ((java.util.Date) it).time.toString() : (it?.toString() ?: "") }.join("\u0001")
        return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)).encodeHex().toString()
    }

    /**
     * The read for `sharedReadKey`, read once. read() is this check's own read (a source result with fileLocation), run
     * only when no other check holds or finished it; copyLocation(fileName) is where this check's copy of another check's
     * file goes; checkpoint() runs between waits (a cancel throws out of it). Answers [result, shared: true when copied].
     */
    static Map readOnce(def ec, String tenantId, String sharedReadKey, String executionId, Closure<Map> read,
                        Closure<String> copyLocation, Closure checkpoint = null) {
        long waitedSince = 0L
        int lostClaims = 0
        while (true) {
            Map row = inNewTransaction(ec) { find(ec, tenantId, sharedReadKey) }
            long now = System.currentTimeMillis()
            String status = row?.statusEnumId as String
            if (status == DONE && !expired(row, now)) {
                Map copied = copyOf(ec, row, copyLocation)
                if (copied != null) {
                    logger.info("Shared read ${sharedReadKey.take(12)} copied from execution ${row.readerExecutionId}")
                    return [result: copied, shared: true]
                }
            } else if (status == FAILED && waitedSince > 0L) {
                throw new IllegalStateException("The shared read this check waited on failed: ${row.errorMessage ?: 'no reason given'}")
            } else if (status == READING && !stale(row, now) && readerRunning(ec, tenantId, row)) {
                if (waitedSince == 0L) waitedSince = now
                if (now - waitedSince > waitMillis) {
                    throw new IllegalStateException("The shared read held by execution ${row.readerExecutionId} did not finish in " +
                            "${waitMillis.intdiv(60_000L)} minutes")
                }
                checkpoint?.call()
                Thread.sleep(pollMillis)
                continue
            }
            // Nobody holds it (none yet, failed before this check started, expired, file gone or reader gone): claim it.
            boolean claimed
            try {
                claimed = inNewTransaction(ec) { claim(ec, tenantId, sharedReadKey, executionId, row) } as boolean
            } catch (Exception lost) {
                // two checks created the row at once: the other one's create committed, this one's rolled back. Anything
                // else repeats on every try, so a few lost claims in a row is a failure, never a loop.
                if (++lostClaims >= MAX_LOST_CLAIMS) {
                    throw new IllegalStateException("The shared read could not be claimed: ${lost.message}", lost)
                }
                logger.info("Shared read ${sharedReadKey.take(12)} was claimed by another check first: ${lost.message}")
                claimed = false
            }
            if (!claimed) continue
            return [result: readAsReader(ec, tenantId, sharedReadKey, executionId, read), shared: false]
        }
    }

    private static Map readAsReader(def ec, String tenantId, String sharedReadKey, String executionId, Closure<Map> read) {
        Map result
        try {
            result = (Map) read.call()
            // The reader's own file is appended to by its re-check later; the share is a copy kept as read.
            String pristine = pristineCopy(ec, (String) result?.fileLocation)
            // no file: the extractor could not say what changed, which its waiters read as a refusal (the whole window)
            Map<String, Object> fields = pristine == null ?
                    [statusEnumId: FAILED, errorMessage: "${AuditRecentReadSupport.CHANGED_READ_REFUSED}: its extractor cannot say what changed".toString()] as Map<String, Object> :
                    [statusEnumId: DONE, fileLocation: pristine, resultJson: JsonOutput.toJson(withDatesKept(result)),
                     expiresDate: new Timestamp(System.currentTimeMillis() + shareMillis)] as Map<String, Object>
            inNewTransaction(ec) { finish(ec, tenantId, sharedReadKey, executionId, fields) }
        } catch (Throwable t) {
            // never leave the row READING: its waiters fail with this reason (or, on a refusal, read their whole window)
            try {
                inNewTransaction(ec) { finish(ec, tenantId, sharedReadKey, executionId, [statusEnumId: FAILED, errorMessage: truncate(t.message ?: t.toString(), 4000)]) }
            } catch (Throwable markFailed) {
                logger.warn("Shared read ${sharedReadKey.take(12)} could not be marked failed: ${markFailed.message}")
            }
            throw t
        }
        return result
    }

    /**
     * Whether the run holding the row is still running: a reader whose run failed, was reaped or ended in a restart leaves
     * its row READING, and its waiters take over instead of waiting out the clock.
     */
    private static boolean readerRunning(def ec, String tenantId, Map row) {
        String readerId = row.readerExecutionId as String
        if (!readerId) return true
        def execution = darpan.facade.common.TenantScopedFinder.findGlobalUnscopedPerRequest(ec,
                "darpan.reconciliation.ReconciliationAutomationExecution", UNSCOPED_REASON)
                .condition("automationExecutionId", readerId).useCache(false).one()
        if (execution == null || execution.companyUserGroupId != tenantId) return false
        return execution.statusEnumId == darpan.reconciliation.automation.AutomationExecutionSupport.STATUS_RUNNING
    }

    private static boolean expired(Map row, long now) {
        Timestamp expires = row.expiresDate as Timestamp
        return expires != null && expires.time <= now
    }

    private static boolean stale(Map row, long now) {
        Timestamp started = row.startedDate as Timestamp
        return started == null || now - started.time > staleMillis
    }

    /** This check's copy of a finished read, or null when its file is gone (the caller then reads it again). */
    private static Map copyOf(def ec, Map row, Closure<String> copyLocation) {
        File from = RunVerificationSupport.resolveLocationFile(ec, row.fileLocation as String)
        if (from == null || !from.isFile()) return null
        String location = copyLocation.call(from.name.replaceAll(/\.shared$/, ""))
        File to = RunVerificationSupport.resolveLocationFile(ec, location)
        if (to == null) throw new IllegalStateException("The shared read could not be copied to ${location}")
        to.parentFile?.mkdirs()
        Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        File sidecar = new File(from.parentFile, ExcludedRecordsSidecar.fileNameFor(from.name))
        if (sidecar.isFile()) {
            Files.copy(sidecar.toPath(), new File(to.parentFile, ExcludedRecordsSidecar.fileNameFor(to.name)).toPath(),
                    StandardCopyOption.REPLACE_EXISTING)
        }
        Map result = withDatesRestored((Map) new JsonSlurper().parseText((String) row.resultJson ?: "{}"))
        // the calls were the reader's: the read report counts them once, under the check that made them
        result.requestMetadata = [sharedReadFrom: row.readerExecutionId]
        result.fileLocation = location
        result.dataManagerPath = location
        result.fileName = to.name
        result.sharedReadFrom = row.readerExecutionId
        return result
    }

    /** A copy of the reader's file (and its sidecar) beside it, as the read wrote it; its location, or null with no file. */
    private static String pristineCopy(def ec, String location) {
        File file = RunVerificationSupport.resolveLocationFile(ec, location)
        if (file == null || !file.isFile()) return null
        String sharedLocation = location + ".shared"
        File shared = RunVerificationSupport.resolveLocationFile(ec, sharedLocation)
        Files.copy(file.toPath(), shared.toPath(), StandardCopyOption.REPLACE_EXISTING)
        File sidecar = new File(file.parentFile, ExcludedRecordsSidecar.fileNameFor(file.name))
        if (sidecar.isFile()) {
            Files.copy(sidecar.toPath(), new File(shared.parentFile, ExcludedRecordsSidecar.fileNameFor(shared.name)).toPath(),
                    StandardCopyOption.REPLACE_EXISTING)
        }
        return sharedLocation
    }

    private static final String UNSCOPED_REASON = "DAR-BE-320 shared morning read, keyed by its share key; a scheduled run " +
            "has no active tenant, so the row's tenant is checked against the run's own"

    /** The share row, locked for this transaction; null when there is none or it is another tenant's. */
    private static def row(def ec, String tenantId, String sharedReadKey) {
        def value = darpan.facade.common.TenantScopedFinder.findGlobalUnscopedPerRequest(ec, "darpan.audit.AuditSharedRead", UNSCOPED_REASON)
                .condition("sharedReadKey", sharedReadKey).useCache(false).forUpdate(true).one()
        if (value != null && value.companyUserGroupId != tenantId) {
            throw new IllegalStateException("Shared read ${sharedReadKey.take(12)} belongs to another tenant")
        }
        return value
    }

    private static Map find(def ec, String tenantId, String sharedReadKey) {
        def value = row(ec, tenantId, sharedReadKey)
        return value == null ? null : new LinkedHashMap(value.getMap())
    }

    /**
     * Claims the row for this check: creates it, or takes over the one it saw (same status and start, so two checks never
     * both take it over). False when another check got there first; the caller looks again.
     */
    private static boolean claim(def ec, String tenantId, String sharedReadKey, String executionId, Map seen) {
        Timestamp now = new Timestamp(System.currentTimeMillis())
        Map<String, Object> fields = [companyUserGroupId: tenantId, statusEnumId: READING, readerExecutionId: executionId,
                                      fileLocation: null, resultJson: null, errorMessage: null, startedDate: now,
                                      completedDate: null, expiresDate: null] as Map<String, Object>
        def current = row(ec, tenantId, sharedReadKey)
        if (seen == null) {
            if (current != null) return false
            ec.entity.makeValue("darpan.audit.AuditSharedRead").setAll(fields).set("sharedReadKey", sharedReadKey).create()
            return true
        }
        if (current == null || current.statusEnumId != seen.statusEnumId || current.startedDate != seen.startedDate) return false
        current.setAll(fields).update()
        return true
    }

    /** The reader's outcome on the row, only while the row is still its own (a reader taken over never overwrites). */
    private static void finish(def ec, String tenantId, String sharedReadKey, String executionId, Map<String, Object> fields) {
        def current = row(ec, tenantId, sharedReadKey)
        if (current == null || current.readerExecutionId != executionId) return
        current.setAll(fields + [completedDate: new Timestamp(System.currentTimeMillis())]).update()
    }

    /** The closure in a transaction of its own, so a waiting check sees the reader's commits and its own claim is visible. */
    private static Object inNewTransaction(def ec, Closure body) {
        // the row is the run's own bookkeeping, written whoever the run runs as (a scheduled run has no user)
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            return ec.transaction.runRequireNew(null, "AuditSharedRead failed", body)
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
    }

    /** A result's top-level dates (readWindowFrom/Thru, which the read report takes as dates) as {"__date": millis}. */
    static Map withDatesKept(Map result) {
        return (result ?: [:]).collectEntries { k, v -> [(k): v instanceof java.util.Date ? [__date: ((java.util.Date) v).time] : v] }
    }

    /** withDatesKept undone: each {"__date": millis} back to a Timestamp. */
    static Map withDatesRestored(Map result) {
        return new LinkedHashMap((result ?: [:]).collectEntries { k, v ->
            [(k): v instanceof Map && ((Map) v).keySet() == (["__date"] as Set) ? new Timestamp(((Number) ((Map) v).__date).longValue()) : v]
        })
    }

    private static String truncate(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max)
    }
}
