package darpan.script

import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import darpan.facade.reconciliation.ReconciliationOutputSupport
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.notification.TenantNotificationSupport
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.sql.Timestamp

/**
 * DAR-BE-167. A script put to work (SuiteScript's scheduled-script deployment): on its schedule, read the newest result
 * of a saved run (or the script's sample), run the script, and post the text it returns to a chat space.
 *
 * <p>The script stays pure. It cannot reach a network or write anything; it RETURNS text, and this class delivers it
 * through the same chat path as run notifications (TenantNotificationSupport.postToChatSpace, which re-checks the
 * space belongs to the tenant). Null or empty text means "nothing to say": nothing is sent.</p>
 *
 * <p>Every execution runs as the deployment's own tenant (TenantAccessSupport.withSystemTenant), so the scheduled path
 * reads exactly what that tenant could read, and evaluation happens with no transaction open (ScriptSupervisor).</p>
 */
class ScriptDeploymentSupport {

    private static final Logger logger = LoggerFactory.getLogger(ScriptDeploymentSupport.class)

    static final String ENTITY = 'darpan.script.TenantScriptDeployment'

    static final String SENT = 'SENT'
    static final String NOTHING_TO_SEND = 'NOTHING_TO_SEND'
    static final String SCRIPT_ERROR = 'SCRIPT_ERROR'
    static final String NO_INPUT = 'NO_INPUT'
    static final String NOT_DELIVERED = 'NOT_DELIVERED'
    static final String SCRIPT_MISSING = 'SCRIPT_MISSING'

    /** Chat messages are cut here; Google Chat's own limit is 4,096 characters. */
    static final int MAX_MESSAGE_CHARS = 4_000

    /** The next time a schedule fires after `from`, in the tenant's zone; null when the expression is not a schedule. */
    static Timestamp nextRunDate(def ec, String tenantId, String scheduleExpr, Timestamp from) {
        if (!scheduleExpr?.trim()) return null
        String zone = TenantNotificationSupport.resolveTenantZone(ec, tenantId).id
        Timestamp now = from ?: new Timestamp(System.currentTimeMillis())
        return AutomationExecutionSupport.resolveNextScheduledFireTime(
                [scheduleExpr: scheduleExpr.trim(), windowTimeZone: zone], now, now)
    }

    /**
     * Runs one deployment. send=false is a preview: the script runs and the text comes back, nothing is posted and the
     * deployment's last-run fields are left alone. Never throws.
     * {status, message, text, run, inputSummary, fileName, delivery}
     */
    static Map<String, Object> execute(def ec, def deployment, boolean send) {
        String tenantId = deployment.companyUserGroupId?.toString()
        return TenantAccessSupport.withSystemTenant(tenantId) {
            Map<String, Object> outcome
            try {
                outcome = executeAsTenant(ec, tenantId, deployment, send)
            } catch (Throwable t) {
                logger.warn("Script deployment {} failed: {}", deployment.tenantScriptDeploymentId, t.toString())
                outcome = [status: SCRIPT_ERROR, message: "The deployment failed: ${t.message ?: t.getClass().simpleName}".toString()]
            }
            // A deployment reports through its outcome, never by failing the caller (the scan runs many).
            ec.message.clearErrors()
            return outcome
        }
    }

    private static Map<String, Object> executeAsTenant(def ec, String tenantId, def deployment, boolean send) {
        def script = TenantScopedFinder.findOneOwnedByTenant(ec, 'darpan.script.TenantScript', 'tenantScriptId',
                deployment.tenantScriptId, tenantId, 'DAR-BE-167 deployment script, owner re-checked against the deployment tenant')
        if (script == null || script.isActive == 'N') {
            return [status: SCRIPT_MISSING, message: 'The script was deleted or switched off.']
        }

        String fileName = null
        if (script.inputSource == TenantScriptSupport.SOURCE_RUN_RESULT) {
            fileName = newestResultFileName(ec, deployment.inputSavedRunId?.toString())
            if (!fileName) {
                return [status: NO_INPUT, message: 'The saved run has no finished result to read yet.']
            }
        }
        Map<String, Object> prepared = TenantScriptSupport.runInput(ec, script.inputSource?.toString(), null,
                script.sampleInputJson?.toString(), fileName)
        if (prepared == null) {
            String why = (ec.message.errors ?: ['The input could not be read.']).join(' ')
            return [status: NO_INPUT, message: why, fileName: fileName]
        }

        Map<String, Object> run = ScriptSupervisor.evaluate(script.scriptProfile, script.scriptBody?.toString(), prepared.input)
        Map<String, Object> base = [run: run, inputSummary: prepared.inputSummary, fileName: fileName] as Map<String, Object>
        if (!run.ok) {
            Map error = run.error as Map
            return base + [status: SCRIPT_ERROR, message: "${error?.line ? 'Line ' + error.line + ': ' : ''}${error?.message}".toString()]
        }

        Object textOrError = messageText(run.result)
        if (textOrError instanceof Map) return base + (Map<String, Object>) textOrError
        String text = (String) textOrError
        if (!text) return base + [status: NOTHING_TO_SEND, message: 'The script returned no text, so nothing was sent.', text: '']
        if (!send) return base + [status: 'PREVIEW', message: 'Preview only: nothing was sent.', text: text]

        Map<String, Object> delivery = TenantNotificationSupport.postToChatSpace(ec, tenantId,
                deployment.chatSpaceId?.toString(), [text: text])
        if (delivery == null) {
            return base + [status: NOT_DELIVERED, text: text, message: 'The chat space was deleted, switched off or has no webhook.']
        }
        if (delivery.ok != true) {
            return base + [status: NOT_DELIVERED, text: text, delivery: delivery,
                           message: "The chat space refused the message (${delivery.statusCode ?: '?'}${delivery.providerMessage ? ', ' + delivery.providerMessage : ''}).".toString()]
        }
        return base + [status: SENT, text: text, delivery: delivery, message: "Sent ${text.length()} characters.".toString()]
    }

    /**
     * What a chat deployment's script returned, as message text: text as it is, or a map's 'text' field. Null or ''
     * means nothing to send. Anything else is an error outcome (a Map) naming what the script should return.
     */
    static Object messageText(Object result) {
        Object value = result instanceof Map ? ((Map) result).get('text') : result
        if (value == null) return ''
        if (!(value instanceof CharSequence)) {
            return [status : SCRIPT_ERROR,
                    message: "A chat deployment's script returns text (or a map with a 'text' field); this one returned ${result instanceof Map ? 'a map without text' : 'a ' + describe(value)}.".toString()]
        }
        String text = value.toString().trim()
        if (text.length() > MAX_MESSAGE_CHARS) {
            String note = "\n… cut at ${String.format('%,d', MAX_MESSAGE_CHARS)} characters"
            text = text.substring(0, MAX_MESSAGE_CHARS - note.length()) + note
        }
        return text
    }

    private static String describe(Object value) {
        if (value instanceof Number) return 'number'
        if (value instanceof Boolean) return 'true/false value'
        if (value instanceof List) return 'list'
        return value.getClass().simpleName
    }

    /** The newest result file of a saved run that the current (system) tenant can read; null when there is none. */
    static String newestResultFileName(def ec, String savedRunId) {
        if (!savedRunId) return null
        Map<String, Object> listed = ReconciliationOutputSupport.listGeneratedOutputs(ec, savedRunId, null, null, 0, 20)
        List outputs = (listed?.generatedOutputs instanceof List) ? (List) listed.generatedOutputs : []
        Map newest = (Map) outputs.find { Object row ->
            row instanceof Map && ((Map) row).fileName && ((Map) row).resultAvailable != false &&
                    (((Map) row).savedRunId == null || ((Map) row).savedRunId == savedRunId)
        }
        return newest?.fileName?.toString()
    }

    /** Stamps a real run (scheduled or sent by hand) on the deployment. A preview is never recorded. */
    static void recordOutcome(def ec, def deployment, Map<String, Object> outcome) {
        Timestamp now = ec.user.nowTimestamp
        Map run = outcome.run instanceof Map ? (Map) outcome.run : [:]
        String message = outcome.message?.toString()
        ec.transaction.runUseOrBegin(30, 'Error recording a script deployment run', {
            deployment.set('lastRunDate', now)
            deployment.set('lastRunStatus', outcome.status?.toString())
            deployment.set('lastRunMessage', message && message.length() > 255 ? message.substring(0, 254) + '…' : message)
            deployment.set('lastRunUnits', run.units != null ? ((Number) run.units).longValue() : null)
            deployment.update()
        })
    }

    /**
     * The scheduled pass: every active deployment whose nextRunDate has come, each claimed (its next run date moved
     * forward) before it runs, so a slow run or an overlapping tick never sends twice. Returns one line per run.
     */
    static Map<String, Object> scanDue(def ec, Timestamp now, int limit) {
        Timestamp nowValue = now ?: new Timestamp(System.currentTimeMillis())
        List due = TenantScopedFinder.findGlobalUnscoped(ec, ENTITY,
                'DAR-BE-167 scheduled scan across tenants; each deployment then runs as its own tenant')
                .condition('isActive', 'Y')
                .condition('nextRunDate', org.moqui.entity.EntityCondition.LESS_THAN_EQUAL_TO, nowValue)
                .orderBy('nextRunDate').limit(Math.max(1, limit)).useCache(false).list()
        List<Map<String, Object>> results = []
        due.each { def deployment ->
            String id = deployment.tenantScriptDeploymentId
            Timestamp next = nextRunDate(ec, deployment.companyUserGroupId?.toString(), deployment.scheduleExpr?.toString(), nowValue)
            boolean claimed = ec.transaction.runUseOrBegin(30, 'Error claiming a script deployment run', {
                def fresh = TenantScopedFinder.findGlobalUnscoped(ec, ENTITY, 'DAR-BE-167 claim re-read of one deployment')
                        .condition('tenantScriptDeploymentId', id).forUpdate(true).useCache(false).one()
                if (fresh == null || fresh.isActive != 'Y' || fresh.nextRunDate == null || fresh.nextRunDate.after(nowValue)) return false
                fresh.set('nextRunDate', next)
                fresh.update()
                return true
            }) as boolean
            if (!claimed) return
            Map<String, Object> outcome = execute(ec, deployment, true)
            def latest = TenantScopedFinder.findGlobalUnscoped(ec, ENTITY, 'DAR-BE-167 record outcome on the claimed deployment')
                    .condition('tenantScriptDeploymentId', id).useCache(false).one()
            if (latest != null) recordOutcome(ec, latest, outcome)
            logger.info("Script deployment {} ({}) ran: {} {}", id, deployment.companyUserGroupId, outcome.status, outcome.message)
            results << [tenantScriptDeploymentId: id, status: outcome.status, message: outcome.message, nextRunDate: next]
        }
        return [ran: results.size(), results: results]
    }

    static Map<String, Object> toDeploymentMap(def row) {
        if (row == null) return null
        return [
                tenantScriptDeploymentId: row.tenantScriptDeploymentId,
                tenantScriptId          : row.tenantScriptId,
                scheduleExpr            : row.scheduleExpr,
                inputSavedRunId         : row.inputSavedRunId,
                chatSpaceId             : row.chatSpaceId,
                isActive                : row.isActive == 'Y',
                nextRunDate             : row.nextRunDate,
                lastRunDate             : row.lastRunDate,
                lastRunStatus           : row.lastRunStatus,
                lastRunMessage          : row.lastRunMessage,
                lastRunUnits            : row.lastRunUnits,
        ]
    }
}
