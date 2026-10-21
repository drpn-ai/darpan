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
import java.util.concurrent.atomic.AtomicBoolean

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

    static final String MAIL_FROM_ENV = "DARPAN_MAIL_FROM"
    static final String EMAIL_SERVER_ID = "SYSTEM"

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
    private static final AtomicBoolean notConfiguredWarned = new AtomicBoolean(false)

    /** Every mail this feature sends goes through here, so tests can record sends instead of making them.
     *  Async, like the framework's own reset#Password: SMTP time never shows in the reply, and an SMTP
     *  failure lands in the server log rather than in the caller's answer. */
    static Closure sendEmail = { def ec, String emailTemplateId, String toAddress, Map bodyParameters ->
        ec.service.async().name("org.moqui.impl.EmailServices.send#EmailTemplate")
                .parameters([emailTemplateId: emailTemplateId, toAddresses: toAddress, bodyParameters: bodyParameters])
                .call()
    }

    /** True when a reset email could actually go out: a SYSTEM EmailServer with an SMTP host, and a from
     *  address. Darpan has no startup hook, so the first caller that finds it unconfigured logs the one WARN
     *  that tells a deploy "codes will never arrive". */
    static boolean isEmailConfigured(def ec) {
        List<String> missing = []
        def server = TenantScopedFinder.findGlobalUnscoped(ec, "moqui.basic.email.EmailServer",
                "framework email configuration, not tenant data")
                .condition("emailServerId", EMAIL_SERVER_ID).useCache(false).one()
        if (!server?.smtpHost) missing << "no EmailServer ${EMAIL_SERVER_ID} with an smtpHost".toString()
        if (!System.getenv(MAIL_FROM_ENV)) missing << "${MAIL_FROM_ENV} is not set".toString()
        if (missing && notConfiguredWarned.compareAndSet(false, true)) {
            logger.warn("Password reset email is not configured: {}", missing.join("; "))
        }
        return missing.isEmpty()
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
        } else if ("Y" == normalize(account.disabled)) {
            // Includes the framework's temporary lockout: a locked-out user waits it out, then resets.
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
        logger.info("Password reset requested for [{}]: {}", value, outcome)
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
        def resetCode = null
        boolean verified = false
        if (account != null && "Y" != normalize(account.disabled)) {
            resetCode = liveCode(ec, (String) account.userId)
            if (resetCode != null) {
                verified = codeValue.length() == 6 && hashMatches(codeValue, (String) resetCode.codeSalt, (String) resetCode.codeHash, ec)
                if (!verified) countFailedAttempt(ec, (String) resetCode.resetCodeId)
            }
        }
        if (!verified) {
            ec.message.clearAll()
            ec.message.addError(CODE_FAILURE_MESSAGE)
            holdToFloor(started)
        }
        Map<String, Object> result = [verified: verified, userAccount: verified ? account : null,
                                      resetCode: verified ? resetCode : null]
        result.putAll(FacadeSupport.envelope(ec))
        return result
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

    private static def liveCode(def ec, String userId) {
        Timestamp now = ec.user.nowTimestamp
        return codes(ec, "self-scoped auth: live reset code for anonymous password reset").condition("userId", userId)
                .condition("purposeEnumId", PURPOSE_PASSWORD_RESET).condition("usedDate", null)
                .condition("thruDate", EntityCondition.ComparisonOperator.GREATER_THAN, now)
                .orderBy("-fromDate").useCache(false).list()?.find { true }
    }

    private static boolean hashMatches(String code, String salt, String expectedHash, def ec) {
        if (!salt || !expectedHash) return false
        String actual = ec.ecfi.getSimpleHash(code, salt)
        return MessageDigest.isEqual(actual.getBytes(StandardCharsets.UTF_8), expectedHash.getBytes(StandardCharsets.UTF_8))
    }

    /** Its own transaction: the caller is about to fail with an error, which rolls its transaction back,
     *  and a counter that rolled back with it would give unlimited guesses. */
    private static void countFailedAttempt(def ec, String resetCodeId) {
        ec.transaction.runRequireNew(null, "Could not count a failed reset code attempt", {
            def row = codes(ec, "count a failed reset code attempt").condition("resetCodeId", resetCodeId)
                    .forUpdate(true).one()
            if (row == null) return
            int attempts = ((row.failedAttempts ?: 0) as int) + 1
            row.failedAttempts = attempts
            if (attempts >= MAX_FAILED_ATTEMPTS) row.thruDate = ec.user.nowTimestamp
            row.update()
        })
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
