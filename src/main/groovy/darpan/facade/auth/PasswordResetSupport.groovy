package darpan.facade.auth

import darpan.facade.common.FacadeSupport
import darpan.facade.common.TenantScopedFinder
import org.moqui.entity.EntityCondition
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Timestamp

import static darpan.common.ValueSupport.normalize

/** DAR-BE-028 — forgot password by emailed code, and the recovery email that makes it reachable.
 *
 *  Spec: docs/superpowers/specs/2026-10-21-forgot-password-design.md (wrapper repo). */
class PasswordResetSupport {
    private static final Logger logger = LoggerFactory.getLogger(PasswordResetSupport.class)

    static final String TEMPLATE_RESET_CODE = "DARPAN_PASSWORD_RESET_CODE"
    static final String TEMPLATE_PASSWORD_CHANGED = "DARPAN_PASSWORD_CHANGED"
    static final String TEMPLATE_RECOVERY_SET = "DARPAN_RECOVERY_EMAIL_SET"
    static final String TEMPLATE_RECOVERY_CONFLICT = "DARPAN_RECOVERY_EMAIL_CONFLICT"

    static final String PURPOSE_PASSWORD_RESET = "DrpnRcPasswordReset"
    static final int CODE_LIFETIME_MINUTES = 15
    static final int MAX_FAILED_ATTEMPTS = 5
    static final int MAX_CODES_PER_ACCOUNT_PER_HOUR = 3
    static final int MAX_CODES_PER_IP_PER_HOUR = 20
    /** Above the login's 25ms: the sent branch writes rows and queues mail, so the floor must clear it. */
    static final long REPLY_FLOOR_MILLIS = 400L
    static final String CODE_FAILURE_MESSAGE = "That code didn't work. Request a new one."

    private static final String RESET_CODE_ENTITY = "darpan.auth.PasswordResetCode"
    private static final long HOUR_MILLIS = 3_600_000L
    private static final SecureRandom random = new SecureRandom()

    /** Every mail this feature sends goes through here, so tests can record sends instead of making them.
     *  Async, like the framework's own reset#Password: SMTP time never shows in the reply, and an SMTP
     *  failure lands in the server log rather than in the caller's answer. */
    static Closure sendEmail = { def ec, String emailTemplateId, String toAddress, Map bodyParameters ->
        ec.service.async().name("org.moqui.impl.EmailServices.send#EmailTemplate")
                .parameters([emailTemplateId: emailTemplateId, toAddresses: toAddress, bodyParameters: bodyParameters])
                .call()
    }

    /** First letter of the local part and the whole domain: enough for a user to recognise their own
     *  address, not enough to read someone else's off a shared screen. */
    static String maskEmail(String email) {
        if (email == null) return null
        int at = email.indexOf('@')
        if (at < 1) return "•••"
        return email.substring(0, 1) + "•••" + email.substring(at)
    }

    /** Ask for a reset code. The reply is the same for every outcome (code sent, no such account, disabled,
     *  no email on file, over a limit) in text AND in time, so it cannot be used to learn which accounts exist
     *  or have an address; the real outcome goes to the server log only. darpan-ui shows one fixed note. */
    static Map<String, Object> requestPasswordReset(def ec, Object usernameOrEmail) {
        long started = System.nanoTime()
        String value = normalize(usernameOrEmail)
        String outcome
        def account = findAccountForReset(ec, value)
        if (account == null) {
            outcome = "no such account"
        } else if (lockedOut(ec, account)) {
            // A permanent disable, or the framework's temporary lockout while it lasts: the user waits it out.
            outcome = "account disabled"
        } else if (!normalize(account.emailAddress)) {
            outcome = "no email on file"
        } else {
            Timestamp now = ec.user.nowTimestamp
            Timestamp hourAgo = new Timestamp(now.time - HOUR_MILLIS)
            String ip = normalize(ec.user.clientIp)
            int accountCodes = (int) codes(ec, "rate limit: codes per account").condition("userId", account.userId)
                    .condition("fromDate", EntityCondition.ComparisonOperator.GREATER_THAN_EQUAL_TO, hourAgo).count()
            // Counts codes ISSUED from the IP: a request that sends nothing writes no row, which is fine,
            // because only issued codes put mail in someone's inbox.
            int ipCodes = ip ? (int) codes(ec, "rate limit: codes per client IP").condition("requestIp", ip)
                    .condition("fromDate", EntityCondition.ComparisonOperator.GREATER_THAN_EQUAL_TO, hourAgo).count() : 0
            if (overLimit(accountCodes, ipCodes)) {
                outcome = "over the hourly limit (${accountCodes} for the account, ${ipCodes} from ${ip ?: 'unknown IP'})"
            } else {
                String code = issueCode(ec, account, ip, now)
                sendEmail(ec, TEMPLATE_RESET_CODE, (String) account.emailAddress,
                        [code: code, expiresMinutes: CODE_LIFETIME_MINUTES, username: account.username])
                outcome = "code sent"
            }
        }
        logger.info("Password reset requested for {}: {}", logSubject(account), outcome)
        ec.message.clearAll()
        holdToFloor(started)
        return FacadeSupport.envelope(ec)
    }

    /** Check a code without using it up. Every failure (no account, no live code, wrong, expired, burned)
     *  gives the one CODE_FAILURE_MESSAGE. A wrong guess counts against the code, never against the account's
     *  failed logins: counting it there would let anyone lock an account out by guessing codes. */
    static Map<String, Object> verifyPasswordResetCode(def ec, Object usernameOrEmail, Object code) {
        long started = System.nanoTime()
        String codeValue = normalizeCode(code)
        def account = findAccountForReset(ec, normalize(usernameOrEmail))
        String resetCodeId = null
        if (account != null && !lockedOut(ec, account)) {
            resetCodeId = checkCodeLocked(ec, (String) account.userId, codeValue)
        }
        boolean verified = resetCodeId != null
        if (!verified) {
            ec.message.clearAll()
            ec.message.addError(CODE_FAILURE_MESSAGE)
            holdToFloor(started)
        }
        Map<String, Object> result = [verified: verified, userAccount: verified ? account : null,
                                      resetCodeId: resetCodeId]
        result.putAll(FacadeSupport.envelope(ec))
        return result
    }

    /** Set a new password with a verified code, then sign the account out everywhere and tell its email.
     *  The account's `disabled` is passed through, never left to update#PasswordInternal's N default, which
     *  would let a disabled account re-enable itself (DAR-BE-078). Password-policy refusals are reported in
     *  full, are reachable only with a right code, and neither use up nor count against the code. */
    static Map<String, Object> completePasswordReset(def ec, Object usernameOrEmail, Object code,
                                                     Object newPassword, Object newPasswordVerify) {
        Map<String, Object> check = verifyPasswordResetCode(ec, usernameOrEmail, code)
        if (!check.verified) return [passwordUpdated: false] + FacadeSupport.envelope(ec)

        def account = check.userAccount
        String userId = (String) account.userId
        Map updateOut = ec.service.sync().name("org.moqui.impl.UserServices.update#PasswordInternal").parameters([
                userId               : userId,
                newPassword          : newPassword?.toString(),
                newPasswordVerify    : newPasswordVerify?.toString(),
                requirePasswordChange: "N",
                // Never update#PasswordInternal's N default blindly (DAR-BE-078). A permanent disable or a live
                // lockout was refused above, so the only "Y" left here is a lockout that has expired, which the
                // realm itself treats as enabled; a reset is the correct login that would have cleared it.
                disabled             : "N",
        ]).call()

        if (updateOut?.updateSuccessful == true && !ec.message.hasError()) {
            ec.message.clearAll()
            Timestamp now = ec.user.nowTimestamp
            def row = codes(ec, "mark a reset code used").condition("resetCodeId", check.resetCodeId).one()
            row.usedDate = now
            row.thruDate = now
            row.update()
            signOutEverywhere(ec, userId)
            sendEmail(ec, TEMPLATE_PASSWORD_CHANGED, (String) account.emailAddress,
                    [username: account.username, changedAt: ec.l10n.format(now, null)])
            logger.info("Password reset completed for {}", logSubject(account))
            return [passwordUpdated: true] + FacadeSupport.envelope(ec)
        }

        if (updateOut?.passwordIssues == true) {
            AuthFacadeSupport.promotePasswordPolicyMessagesToErrors(ec)
        } else {
            ec.message.clearAll()
            ec.message.addError(CODE_FAILURE_MESSAGE)
        }
        return [passwordUpdated: false] + FacadeSupport.envelope(ec)
    }

    /** End every session the account has, failing closed: unlike AuthFacadeSupport.deleteAllLoginKeysForUser,
     *  a failure here is not swallowed, so it rolls the reset back instead of reporting a reset that left an
     *  old session alive. Login keys cover darpan-ui (header token and persistent cookie); hasLoggedOut=Y is the
     *  framework's broadcast that evicts session-authenticated requests elsewhere (UserFacadeImpl, legacy /apps),
     *  and the account's next successful login clears it. */
    private static void signOutEverywhere(def ec, String userId) {
        TenantScopedFinder.findGlobalUnscoped(ec, "moqui.security.UserLoginKey",
                "self-scoped auth: delete all login keys for userId after a password reset")
                .condition("userId", userId).deleteAll()
        // Also clears an expired lockout's leftovers, as the realm does on a correct login.
        ec.service.sync().name("update#moqui.security.UserAccount").parameters([userId: userId, hasLoggedOut: "Y",
                disabledDateTime: null, successiveFailedLogins: 0]).call()
    }

    /** True for a permanent disable, or the framework's temporary lockout until disable-minutes have passed.
     *  Mirrors MoquiShiroRealm.loginPrePassword: nothing clears disabled=Y when a lockout expires (only a correct
     *  login does), so reading the flag alone would shut a locked-out user out of reset for good. */
    static boolean lockedOut(def ec, def account) {
        if ("Y" != normalize(account.disabled)) return false
        Timestamp since = (Timestamp) account.disabledDateTime
        if (since == null) return true
        Integer minutes = (ec.ecfi.confXmlRoot.first("user-facade").first("login").attribute("disable-minutes") as Integer) ?: 30
        return since.time + minutes * 60_000L > ec.user.nowTimestamp.time
    }

    /** Who a log line is about: the matched userId, never the raw username or email that was typed. */
    static String logSubject(def account) {
        return account?.userId ? "userId ${account.userId}".toString() : "unmatched input"
    }

    /** Exact username, then case-insensitive username (both exactly as login resolves them), then email.
     *  An email shared by more than one row (only possible under a case-sensitive collation) matches none. */
    static def findAccountForReset(def ec, String usernameOrEmail) {
        if (!usernameOrEmail) return null
        def account = AuthFacadeSupport.findUserAccountForPasswordChange(ec, usernameOrEmail)
        if (account != null) return account
        def byEmail = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.security.UserAccount",
                "self-scoped auth: resolve account by email for anonymous password reset")
                .condition(ec.entity.conditionFactory.makeCondition("emailAddress",
                        EntityCondition.ComparisonOperator.EQUALS, usernameOrEmail).ignoreCase())
                .useCache(false).list()
        return byEmail.size() == 1 ? byEmail[0] : null
    }

    /** Digits only, so a code pasted with spaces or a trailing newline still matches. */
    static String normalizeCode(Object code) {
        return code == null ? "" : code.toString().replaceAll(/\D/, "")
    }

    static boolean overLimit(int accountCodesLastHour, int ipCodesLastHour) {
        return accountCodesLastHour >= MAX_CODES_PER_ACCOUNT_PER_HOUR || ipCodesLastHour >= MAX_CODES_PER_IP_PER_HOUR
    }

    /** Ends the account's live codes, then stores a new one: only the newest emailed code ever works. */
    private static String issueCode(def ec, def account, String ip, Timestamp now) {
        String userId = (String) account.userId
        codes(ec, "end the account's previous reset codes").condition("userId", userId)
                .condition("purposeEnumId", PURPOSE_PASSWORD_RESET).condition("usedDate", null)
                .condition("thruDate", EntityCondition.ComparisonOperator.GREATER_THAN, now)
                .useCache(false).list().each { row ->
            row.thruDate = now
            row.update()
        }
        // The same generator as the framework's create#SingleUseAuthcFactor; bound 1_000_000 so 999999 can occur.
        String code = random.nextInt(1_000_000).toString().padLeft(6, '0')
        byte[] saltBytes = new byte[16]
        random.nextBytes(saltBytes)
        String salt = saltBytes.encodeHex().toString()
        ec.entity.makeValue(RESET_CODE_ENTITY).setAll([
                userId        : userId,
                purposeEnumId : PURPOSE_PASSWORD_RESET,
                codeHash      : ec.ecfi.getSimpleHash(code, salt),
                codeSalt      : salt,
                fromDate      : now,
                thruDate      : new Timestamp(now.time + CODE_LIFETIME_MINUTES * 60_000L),
                failedAttempts: 0,
                requestIp     : ip,
        ]).setSequencedIdPrimary().create()
        return code
    }

    /** The resetCodeId when the code is live and right for the account, else null.
     *
     *  Check and count are one step under a row lock, in their own transaction. Two reasons: parallel guesses
     *  must each be counted before the next is compared, or a burst of them all slips past the cap; and the
     *  caller is about to fail with an error, which rolls its own transaction back, so a counter written there
     *  would roll back with it and give unlimited guesses. */
    private static String checkCodeLocked(def ec, String userId, String codeValue) {
        return (String) ec.transaction.runRequireNew(null, "Could not check a password reset code", {
            Timestamp now = ec.user.nowTimestamp
            def row = codes(ec, "self-scoped auth: lock the live reset code to check a guess").condition("userId", userId)
                    .condition("purposeEnumId", PURPOSE_PASSWORD_RESET).condition("usedDate", null)
                    .condition("thruDate", EntityCondition.ComparisonOperator.GREATER_THAN, now)
                    .orderBy("-fromDate").forUpdate(true).useCache(false).list()?.find { true }
            if (row == null) return null
            // Re-check under the lock: the WHERE above can be evaluated before the lock is granted, so a guess
            // that waited behind the fifth can arrive holding a row the fifth has just ended.
            if (((row.failedAttempts ?: 0) as int) >= MAX_FAILED_ATTEMPTS || !(((Timestamp) row.thruDate) > now)) return null
            if (codeValue.length() == 6 && hashMatches(codeValue, (String) row.codeSalt, (String) row.codeHash, ec)) {
                return (String) row.resetCodeId
            }
            int attempts = ((row.failedAttempts ?: 0) as int) + 1
            row.failedAttempts = attempts
            if (attempts >= MAX_FAILED_ATTEMPTS) row.thruDate = now
            row.update()
            return null
        })
    }

    private static boolean hashMatches(String code, String salt, String expectedHash, def ec) {
        if (!salt || !expectedHash) return false
        String actual = ec.ecfi.getSimpleHash(code, salt)
        return MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8), expectedHash.getBytes(StandardCharsets.UTF_8))
    }

    private static def codes(def ec, String reason) {
        return TenantScopedFinder.findGlobalUnscoped(ec, RESET_CODE_ENTITY, reason)
    }

    /** Hold an anonymous reply open until REPLY_FLOOR_MILLIS, so how fast it came back says nothing about
     *  which branch ran. Overshooting means the masking stopped working, so say so. */
    static void holdToFloor(long startedNanos) {
        long elapsedMillis = (System.nanoTime() - startedNanos).intdiv(1_000_000L)
        long remainingMillis = REPLY_FLOOR_MILLIS - elapsedMillis
        if (remainingMillis <= 0) {
            logger.warn("Password reset reply took {}ms, past the {}ms floor — replies may again be told apart by timing",
                    elapsedMillis, REPLY_FLOOR_MILLIS)
            return
        }
        try {
            Thread.sleep(remainingMillis)
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt()
        }
    }
}
