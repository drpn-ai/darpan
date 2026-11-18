package darpan.admin

import darpan.config.DarpanSettings
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

    static Map update(def ec, String settingKey, String value, String note) {
        String text = value?.trim()
        String problem = DarpanSettings.problem(settingKey, text)
        if (problem != null) return [error: problem]
        String cleanNote = note?.trim() ?: null
        if (cleanNote != null && cleanNote.length() > NOTE_MAX) {
            return [error: "The note is ${cleanNote.length()} characters; the most is ${NOTE_MAX}.".toString()]
        }
        String before = DarpanSettings.describe(settingKey).effectiveValue
        String audit = "${settingKey}: ${before} -> ${text}${cleanNote ? " (${cleanNote})" : ""}".toString()
        // Saved and audited in a transaction of its own that commits here. expireAfterCommit waits for the CALLER's
        // commit, so inside a larger transaction the setting this call returns (and every read on this node) would still
        // be the old value; committing first lets us expire at once. The price: a caller's larger transaction does not
        // contain this write, so a later failure there does not undo it.
        ec.transaction.runRequireNew(null, "Could not save setting ${settingKey}", {
            ec.service.sync().name("store#" + ENTITY).parameters([settingKey: settingKey, settingValue: text, note: cleanNote,
                    lastChangedByUserId: ec.user.userId, lastChangedDate: ec.user.nowTimestamp]).requireNewTransaction(false).call()
            AdminAuditSupport.record(ec, "admin.SettingAdminServices.update#Setting", "DarpanSetting", settingKey, audit)
        })
        if (ec.message.hasError()) return [error: ec.message.errorsString]
        DarpanSettings.expire()
        DarpanSettings.expireAfterCommit(ec)
        return [setting: DarpanSettings.describe(settingKey)]
    }

    static Map reset(def ec, String settingKey) {
        def sd = DarpanSettings.catalog().get(settingKey)
        if (sd == null) return [error: "No setting ${settingKey}.".toString()]
        def row = ec.entity.find(ENTITY).condition("settingKey", settingKey).useCache(false).one()
        if (row != null) {
            Map d = DarpanSettings.describe(settingKey)
            // What is in force once the row is gone: a valid JVM property, else the default.
            String prop = d.propertyValue
            boolean byProperty = prop != null && DarpanSettings.parse(sd, prop) != null
            String after = byProperty ? prop : d.defaultValue
            String problem = orderProblem(sd, after)
            if (problem != null) return [error: problem]
            String audit = "${settingKey}: ${d.effectiveValue} -> ${byProperty ? "property" : "default"} ${after}".toString()
            ec.transaction.runRequireNew(null, "Could not reset setting ${settingKey}", {
                ec.entity.find(ENTITY).condition("settingKey", settingKey).useCache(false).forUpdate(true).one()?.delete()
                AdminAuditSupport.record(ec, "admin.SettingAdminServices.reset#Setting", "DarpanSetting", settingKey, audit)
            })
            if (ec.message.hasError()) return [error: ec.message.errorsString]
            DarpanSettings.expire()
            DarpanSettings.expireAfterCommit(ec)
        }
        return [setting: DarpanSettings.describe(settingKey)]
    }

    /** Null when `after` as this key's value keeps every lessThan pair in order; otherwise why not, naming the other key. */
    private static String orderProblem(def sd, String after) {
        BigDecimal v = new BigDecimal(after)
        if (sd.lessThan != null) {
            String other = DarpanSettings.describe(sd.lessThan).effectiveValue
            if (!(v < new BigDecimal(other))) {
                return "Resetting ${sd.key} would put it at ${after}, not below ${sd.lessThan} (${other}). Reset ${sd.lessThan} first, or lower ${sd.key} below ${other}.".toString()
            }
        }
        for (def above : DarpanSettings.catalog().values().findAll { it.lessThan == sd.key }) {
            String low = DarpanSettings.describe(above.key).effectiveValue
            if (!(new BigDecimal(low) < v)) {
                return "Resetting ${sd.key} would put it at ${after}, not above ${above.key} (${low}). Reset ${above.key} first, or lower it below ${after}.".toString()
            }
        }
        return null
    }
}
