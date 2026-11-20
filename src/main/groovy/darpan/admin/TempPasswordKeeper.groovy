package darpan.admin

import darpan.config.DarpanSettings
import darpan.facade.common.TenantScopedFinder
import groovy.transform.CompileDynamic
import org.moqui.entity.EntityCondition

import java.security.MessageDigest
import java.sql.Timestamp

/** DAR-BE-325 (Aditi 2026-11-20: "include the temporary password in the email always" / "there should be a way to temp
 *  save the password"). Moqui keeps only a password's hash, and the welcome email goes out on a tenant grant that may
 *  come long after the admin set the password, so the temporary password an admin sets is kept here, encrypted
 *  (darpan.auth.TempPasswordKeep, encrypt="true", rotated with every other secret). The only reader and writer. */
@CompileDynamic
class TempPasswordKeeper {

    static final String ENTITY = "darpan.auth.TempPasswordKeep"
    static final String KEEP_DAYS_SETTING = "darpan.admin.tempPasswordKeepDays"
    private static final long DAY_MILLIS = 86_400_000L

    /** Keeps the temporary password just set for userId (replacing an earlier one) and drops every expired row, so
     *  rows nobody reads never linger. Call only after the framework accepted the password. */
    static void keep(def ec, String userId, String tempPassword) {
        Timestamp now = ec.user.nowTimestamp
        rows(ec, "drop expired kept temporary passwords")
                .condition("keptDate", EntityCondition.LESS_THAN, cutoff(now)).deleteAll()
        rows(ec, "replace the user's kept temporary password").condition("userId", userId).deleteAll()
        // The calling admin service's authz (DARPAN_ADMIN_API, inherited) covers this write, as it does the audit row.
        ec.entity.makeValue(ENTITY).setAll([userId: userId, tempPassword: tempPassword, keptDate: now]).create()
    }

    /** The temporary password for this account's welcome email, or null. Only while it is still the account's
     *  password (an admin or the person changed it some other way), the account must still change it, and it is
     *  within the keep period; a row that fails any of these is deleted. */
    static String forWelcome(def ec, def account) {
        String userId = (String) account.userId
        def row = rows(ec, "read the kept temporary password for a welcome email").condition("userId", userId).one()
        if (row == null) return null
        String tempPassword = (String) row.tempPassword
        boolean usable = account.requirePasswordChange == "Y" && tempPassword &&
                !((Timestamp) row.keptDate).before(cutoff(ec.user.nowTimestamp)) && matchesHash(ec, account, tempPassword)
        if (!usable) {
            drop(ec, userId)
            return null
        }
        return tempPassword
    }

    /** Called by the TempPasswordKeep SECA once a password the person chose has committed. */
    static void drop(def ec, String userId) {
        if (userId) rows(ec, "drop a kept temporary password").condition("userId", userId).deleteAll()
    }

    private static boolean matchesHash(def ec, def account, String password) {
        String stored = (String) account.currentPassword
        if (!stored) return false
        String hashed = ec.ecfi.getSimpleHash(password, (String) (account.passwordSalt ?: ""),
                (String) account.passwordHashType, account.passwordBase64 == "Y")
        return MessageDigest.isEqual(stored.getBytes("UTF-8"), hashed.getBytes("UTF-8"))
    }

    private static Timestamp cutoff(Timestamp now) {
        return new Timestamp(now.time - DarpanSettings.intValue(KEEP_DAYS_SETTING) * DAY_MILLIS)
    }

    private static def rows(def ec, String reason) {
        return TenantScopedFinder.findGlobalUnscoped(ec, ENTITY, reason)
    }
}
