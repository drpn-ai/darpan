package darpan.admin

import darpan.audit.AuditDigestSupport
import darpan.config.DarpanSettings
import darpan.reconciliation.notification.TenantNotificationSupport
import groovy.transform.CompileDynamic

/**
 * DAR-BE-304: the work behind admin.SettingAdminServices (list, show, edit and reset an instance setting). A refusal is
 * {error: "..."} and has saved nothing. Validation is DarpanSettings.problem, so an edit that would break a rule
 * between two settings (silent-any-stage against the reaper time) is refused with that reason. Saving the default's
 * value still stores a row (an explicit pin); reset is how a setting follows its default again. The caller has checked
 * super admin.
 */
@CompileDynamic
class SettingAdminSupport {
    private static final String ENTITY = DarpanSettings.ENTITY

    static Map show(def ec, String settingKey) {
        if (!DarpanSettings.catalog().containsKey(settingKey)) return [error: "No setting ${settingKey}.".toString()]
        return [setting: DarpanSettings.describe(settingKey)]
    }

    static final int NOTE_MAX = 255
    static final String ERRORS_CHAT_WEBHOOK_KEY = 'errors.chat.webhookUrl'

    /** The errors chat space is a Google Chat or Slack incoming webhook (host-pinned, as a tenant's is); null when text is fine or blank. */
    private static String webhookProblem(String settingKey, String text) {
        if (settingKey != ERRORS_CHAT_WEBHOOK_KEY || !text) return null
        String google = TenantNotificationSupport.validateGoogleChatWebhookUrl(text)
        if (google == null) return null
        String slack = TenantNotificationSupport.validateSlackWebhookUrl(text)
        if (slack == null) return null
        return "${settingKey} must be a Google Chat (chat.googleapis.com) or Slack (hooks.slack.com) webhook URL.".toString()
    }

    static final String ERRORS_EMAIL_KEY = 'errors.email.to'

    /** The error email's addresses: every comma- or semicolon-separated entry is an address (AuditDigestSupport.emailsOf); never echoes the value. */
    private static String emailListProblem(String settingKey, String text) {
        if (settingKey != ERRORS_EMAIL_KEY || !text) return null
        List<String> entries = text.split(/[,;]/)*.trim().findAll { it }
        if (entries && AuditDigestSupport.emailsOf(text).size() == entries.unique().size()) return null
        return "${settingKey} must be one or more email addresses, comma-separated.".toString()
    }

    static Map update(def ec, String settingKey, String value, String note) {
        String refused = managedRefusal(settingKey)
        if (refused != null) return [error: refused]
        String text = value?.trim()
        String problem = DarpanSettings.problem(settingKey, text)
        if (problem != null) return [error: problem]
        String hookProblem = webhookProblem(settingKey, text)
        if (hookProblem != null) return [error: hookProblem]
        String emailProblem = emailListProblem(settingKey, text)
        if (emailProblem != null) return [error: emailProblem]
        String cleanNote = note?.trim() ?: null
        if (cleanNote != null && cleanNote.length() > NOTE_MAX) {
            return [error: "The note is ${cleanNote.length()} characters; the most is ${NOTE_MAX}.".toString()]
        }
        save(ec, settingKey, text, cleanNote, "admin.SettingAdminServices.update#Setting")
        if (ec.message.hasError()) return [error: ec.message.errorsString]
        return [setting: DarpanSettings.describe(settingKey)]
    }

    /**
     * Saves a value and its audit row in a transaction of its own that commits here, then makes it live on this node. The caller
     * has validated the value and checked super admin. Saving writes no check of the key's managedBy: the service that manages
     * the key (retention) writes through here, the generic editor does not (update, reset). The audit row reads
     * `key: old effective -> new (note)`. expireAfterCommit waits for the CALLER's commit, so inside a larger transaction the
     * setting this call returns (and every read on this node) would still be the old value; committing first lets us expire at
     * once. The price: a caller's larger transaction does not contain this write, so a later failure there does not undo it.
     */
    static void save(def ec, String settingKey, String text, String cleanNote, String serviceName) {
        String before = DarpanSettings.describe(settingKey).effectiveValue
        String audit = "${settingKey}: ${before} -> ${DarpanSettings.masked(settingKey, text)}${cleanNote ? " (${cleanNote})" : ""}".toString()
        ec.transaction.runRequireNew(null, "Could not save setting ${settingKey}", {
            ec.service.sync().name("store#" + ENTITY).parameters([settingKey: settingKey, settingValue: text, note: cleanNote,
                    lastChangedByUserId: ec.user.userId, lastChangedDate: ec.user.nowTimestamp]).requireNewTransaction(false).call()
            record(ec, serviceName, settingKey, audit)
        })
        DarpanSettings.expire()
        DarpanSettings.expireAfterCommit(ec)
    }

    /**
     * Deletes the production value (and audits it) so the key follows its JVM property or default again; no row, nothing
     * changes. Same transaction and managedBy stance as save. Returns what is in force afterwards, as text (null: nothing).
     */
    static String remove(def ec, String settingKey, String serviceName) {
        def row = ec.entity.find(ENTITY).condition("settingKey", settingKey).useCache(false).one()
        if (row == null) return DarpanSettings.describe(settingKey).effectiveValue
        Map d = DarpanSettings.describeUnmasked(settingKey)
        def sd = DarpanSettings.catalog().get(settingKey)
        String prop = d.propertyValue
        boolean byProperty = prop != null && DarpanSettings.parse(sd, prop) != null
        String after = byProperty ? prop : d.defaultValue
        String audit = "${settingKey}: ${DarpanSettings.masked(settingKey, d.effectiveValue)} -> ${byProperty ? "property" : "default"} ${DarpanSettings.masked(settingKey, after)}".toString()
        ec.transaction.runRequireNew(null, "Could not reset setting ${settingKey}", {
            ec.entity.find(ENTITY).condition("settingKey", settingKey).useCache(false).forUpdate(true).one()?.delete()
            record(ec, serviceName, settingKey, audit)
        })
        DarpanSettings.expire()
        DarpanSettings.expireAfterCommit(ec)
        return DarpanSettings.masked(settingKey, after)
    }

    private static void record(def ec, String serviceName, String settingKey, String audit) {
        // A system caller (a migration) has no user: its row names the system user, as a run's does.
        if (ec.user.userId) AdminAuditSupport.record(ec, serviceName, "DarpanSetting", settingKey, audit)
        else AdminAuditSupport.recordRun(ec, serviceName, "DarpanSetting", settingKey, audit)
    }

    /** Why the generic editor will not touch this key, or null when it may: a managed key is edited only where its guard lives. */
    static String managedRefusal(String settingKey) {
        String owner = DarpanSettings.catalog().get(settingKey)?.managedBy
        if (owner == null) return null
        return "${settingKey} is managed by ${owner}: set it on the Schedule page (Retention), where a shorter period needs a fresh preview.".toString()
    }

    static Map reset(def ec, String settingKey) {
        def sd = DarpanSettings.catalog().get(settingKey)
        if (sd == null) return [error: "No setting ${settingKey}.".toString()]
        String refused = managedRefusal(settingKey)
        if (refused != null) return [error: refused]
        def row = ec.entity.find(ENTITY).condition("settingKey", settingKey).useCache(false).one()
        if (row != null) {
            Map d = DarpanSettings.describeUnmasked(settingKey)
            // What is in force once the row is gone: a valid JVM property, else the default.
            String prop = d.propertyValue
            boolean byProperty = prop != null && DarpanSettings.parse(sd, prop) != null
            String after = byProperty ? prop : d.defaultValue
            String problem = orderProblem(sd, after)
            if (problem != null) return [error: problem]
            remove(ec, settingKey, "admin.SettingAdminServices.reset#Setting")
            if (ec.message.hasError()) return [error: ec.message.errorsString]
        }
        return [setting: DarpanSettings.describe(settingKey)]
    }

    /** Null when `after` as this key's value keeps every lessThan pair in order; otherwise why not, naming the other key. */
    private static String orderProblem(def sd, String after) {
        List above = DarpanSettings.catalog().values().findAll { it.lessThan == sd.key }
        if (sd.lessThan == null && above.isEmpty()) return null // on/off and list settings are not ordered against anything
        BigDecimal v = new BigDecimal(after)
        if (sd.lessThan != null) {
            String other = DarpanSettings.describe(sd.lessThan).effectiveValue
            if (!(v < new BigDecimal(other))) {
                return "Resetting ${sd.key} would put it at ${after}, not below ${sd.lessThan} (${other}). Reset ${sd.lessThan} first, or lower ${sd.key} below ${other}.".toString()
            }
        }
        for (def a : above) {
            String low = DarpanSettings.describe(a.key).effectiveValue
            if (!(new BigDecimal(low) < v)) {
                return "Resetting ${sd.key} would put it at ${after}, not above ${a.key} (${low}). Reset ${a.key} first, or lower it below ${after}.".toString()
            }
        }
        return null
    }
}
