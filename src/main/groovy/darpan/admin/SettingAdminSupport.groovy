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

    static Map update(def ec, String settingKey, String value, String note) {
        String text = value?.trim()
        String problem = DarpanSettings.problem(settingKey, text)
        if (problem != null) return [error: problem]
        String before = DarpanSettings.describe(settingKey).effectiveValue
        String cleanNote = note?.trim() ?: null
        String audit = "${settingKey}: ${before} -> ${text}${cleanNote ? " (${cleanNote})" : ""}".toString()
        // Saved and audited in a transaction of its own that commits here, so the setting this call returns (and every
        // read on this node from now on) is the stored value, not the one before the surrounding transaction commits.
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
        if (!DarpanSettings.catalog().containsKey(settingKey)) return [error: "No setting ${settingKey}.".toString()]
        def row = ec.entity.find(ENTITY).condition("settingKey", settingKey).useCache(false).one()
        if (row != null) {
            Map d = DarpanSettings.describe(settingKey)
            String audit = "${settingKey}: ${d.effectiveValue} -> default ${d.defaultValue}".toString()
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
}
