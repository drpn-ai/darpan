package darpan.facade.auth

import darpan.admin.AuditLogTestSupport
import darpan.facade.auth.support.TwoFactorTestSupport
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

import java.sql.Timestamp
import java.time.Instant

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-297: the daily reminder to anyone two-factor will be required for who has not set it up. reminderDue is pure (the
 * cadence: never sent, then 7, 3, 1 and 0 days before, then weekly after); the job service is run against a booted Moqui on
 * H2 with the mail seam recording what would be sent.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TwoFactorReminderSmokeTests extends TwoFactorTestSupport {
    private static final String PASSWORD = "Tf-passw0rd-one!"
    private static final String TENANT = "TFR_TENANT"
    private static final String JOB = "auth.TwoFactorServices.send#TwoFactorReminders"
    private static final String[][] PEOPLE = [["TFR_NOFACTOR", "tfr.nofactor"], ["TFR_APP", "tfr.app"], ["TFR_PASSKEY", "tfr.passkey"],
            ["TFR_NOMAIL", "tfr.nomail"], ["TFR_DISABLED", "tfr.disabled"]]

    TwoFactorReminderSmokeTests() { super(PASSWORD) }

    @BeforeAll
    void setup() {
        boot("two-factor-reminder-smoke")
        ec.entity.makeValue("moqui.security.UserGroup").setAll([userGroupId: TENANT, description: "TFR tenant"]).createOrUpdate()
    }

    @BeforeEach
    void people() {
        PEOPLE.each { String[] p ->
            upsertAccount(p[0], p[1])
            ec.entity.makeValue("darpan.auth.TenantUserPermissionGroupMember").setAll([tenantUserGroupId: TENANT, userId: p[0],
                    permissionUserGroupId: "DARPAN_TENANT_USER", fromDate: new Timestamp(System.currentTimeMillis() - 60_000L)]).createOrUpdate()
        }
        account("TFR_NOMAIL").set("emailAddress", null).update()
        account("TFR_DISABLED").set("disabled", "Y").set("disabledDateTime", null).update()
        factor("TFR_APP", TwoFactorRecords.TYPE_TOTP, TwoFactorRecords.ACTIVE)
        factor("TFR_PASSKEY", TwoFactorRecords.TYPE_PASSKEY, TwoFactorRecords.ACTIVE)
        setPolicy(TwoFactorPolicy.EVERYONE, new Timestamp(System.currentTimeMillis() + 3 * 86_400_000L))
    }

    // ---- reminderDue (pure) ----

    private static Timestamp d(String iso) { Timestamp.from(Instant.parse(iso + "T15:00:00Z")) }

    @Test
    @DisplayName("reminderDue: first, 7/3/1/0 days before, weekly after, never twice in a day")
    void reminderDueCadence() {
        Timestamp dec2 = d("2026-12-02")
        assertTrue(TwoFactorPolicy.reminderDue(dec2, d("2026-11-18"), null))
        assertTrue(TwoFactorPolicy.reminderDue(dec2, d("2026-11-25"), d("2026-11-18")), "7 days left")
        assertFalse(TwoFactorPolicy.reminderDue(dec2, d("2026-11-24"), d("2026-11-18")), "8 days left")
        assertTrue(TwoFactorPolicy.reminderDue(dec2, d("2026-11-29"), d("2026-11-25")), "3 days left")
        assertTrue(TwoFactorPolicy.reminderDue(dec2, d("2026-12-01"), d("2026-11-29")), "1 day left")
        assertTrue(TwoFactorPolicy.reminderDue(dec2, d("2026-12-02"), d("2026-11-29")), "0 days left")
        assertFalse(TwoFactorPolicy.reminderDue(dec2, d("2026-12-02"), d("2026-12-02")), "already today")
        assertTrue(TwoFactorPolicy.reminderDue(dec2, d("2026-12-09"), d("2026-12-02")), "a week after")
        assertFalse(TwoFactorPolicy.reminderDue(dec2, d("2026-12-08"), d("2026-12-02")), "six days after")
        assertTrue(TwoFactorPolicy.reminderDue(dec2, d("2026-12-20"), d("2026-12-09")))
    }

    // ---- the job ----

    @Test
    @DisplayName("a covered person with no factor and an email gets one reminder and a REMINDER_SENT event; a second run the same day sends nothing")
    void remindsOncePerDay() {
        Map first = call(JOB, [:])
        assertEquals([], first.errors)
        List mine = sent.findAll { it.templateId == TwoFactorSupport.TEMPLATE_REMINDER }
        assertEquals(["tfr.nofactor@example.com"], mine*.to, "${sent}")
        assertEquals(1, events("TFR_NOFACTOR", "REMINDER_SENT").size())
        assertNotNull(mine[0].params.requiredDate)
        assertNotNull(mine[0].params.appUrl)

        sent.clear()
        assertEquals([], call(JOB, [:]).errors)
        assertEquals([], sent.findAll { it.templateId == TwoFactorSupport.TEMPLATE_REMINDER })
        assertEquals(1, events("TFR_NOFACTOR", "REMINDER_SENT").size())
    }

    @Test
    @DisplayName("the deadline is named on the calendar it is stored on, whatever the reader's zone")
    void namesTheStoredDayNotTheReadersDay() {
        account("TFR_NOFACTOR").set("timeZone", "America/Los_Angeles").update()
        setPolicy(TwoFactorPolicy.EVERYONE, Timestamp.from(Instant.parse("2026-12-02T00:00:00Z")))
        assertEquals([], call(JOB, [:]).errors)
        Map mail = sent.find { it.to == "tfr.nofactor@example.com" }
        assertNotNull(mail, "${sent}")
        assertEquals("2 December 2026", mail.params.requiredDate)
        assertTrue((mail.params.subjectLine as String).endsWith("by 2 December 2026") || mail.params.past == true, "${mail.params}")
    }

    @Test
    @DisplayName("the reminder's subject words are copy blocks: shipped by default, an edit is sent, a breakout falls back")
    void theSubjectLineIsACopyBlock() {
        setPolicy(TwoFactorPolicy.EVERYONE, Timestamp.from(Instant.parse("2026-12-02T00:00:00Z")))
        assertEquals([], call(JOB, [:]).errors)
        assertEquals("Set up two-factor sign-in for Darpan by 2 December 2026", sent.find { it.to == "tfr.nofactor@example.com" }.params.subjectLine)
        def block = ec.entity.makeValue("darpan.email.EmailCopyBlock").setAll([emailTemplateId: TwoFactorSupport.TEMPLATE_REMINDER, blockId: "subject.before",
                defaultText: 'Set up two-factor sign-in for Darpan by ${requiredDate}']).createOrUpdate()
        try {
            Map<String, String> cases = ['Two-factor by ${requiredDate}, please': "Two-factor by 2 December 2026, please",
                                         'Say "hi" ${requiredDate}': "Set up two-factor sign-in for Darpan by 2 December 2026"]
            for (Map.Entry<String, String> c : cases.entrySet()) {
                block.set("copyText", c.key).update()
                AuditLogTestSupport.wipe(ec, TwoFactorRecords.EVENT)
                sent.clear()
                assertEquals([], call(JOB, [:]).errors)
                assertEquals(c.value, sent.find { it.to == "tfr.nofactor@example.com" }.params.subjectLine, c.key)
            }
        } finally {
            block.set("copyText", null).update()
        }
    }

    @Test
    @DisplayName("people with an app or passkey, no email, or a disabled account are not reminded; no email means no event")
    void skipsThoseNotToBeReminded() {
        call(JOB, [:])
        ["TFR_APP", "TFR_PASSKEY", "TFR_NOMAIL", "TFR_DISABLED"].each {
            assertEquals(0, events(it, "REMINDER_SENT").size(), it)
        }
        assertFalse(sent.any { it.to in ["tfr.app@example.com", "tfr.passkey@example.com", "tfr.disabled@example.com"] }, "${sent}")
    }

    @Test
    @DisplayName("no policy date, no reminder")
    void noDateNoReminder() {
        setPolicy(TwoFactorPolicy.EVERYONE, null)
        call(JOB, [:])
        assertEquals([], sent.findAll { it.templateId == TwoFactorSupport.TEMPLATE_REMINDER })
        assertEquals(0, sentCount(), "no REMINDER_SENT event for anyone")
        assertEquals(0, TwoFactorPolicy.sendReminders(ec), "an early return: nothing sent")
    }

    @Test
    @DisplayName("a person whose only tenant is disabled gets no reminder")
    void disabledTenantMemberIsNotReminded() {
        ec.entity.makeValue("darpan.auth.TenantSetting").setAll([companyUserGroupId: TENANT, disabled: "Y"]).createOrUpdate()
        try {
            call(JOB, [:])
            assertEquals([], sent.findAll { it.templateId == TwoFactorSupport.TEMPLATE_REMINDER })
            assertEquals(0, events("TFR_NOFACTOR", "REMINDER_SENT").size())
        } finally {
            ec.entity.find("darpan.auth.TenantSetting").condition("companyUserGroupId", TENANT).disableAuthz().deleteAll()
        }
    }

    private int sentCount() {
        return PEOPLE.sum { String[] p -> events(p[0], "REMINDER_SENT").size() } as int
    }

    private void factor(String userId, String type, String status) {
        ec.entity.makeValue(TwoFactorRecords.FACTOR).setAll([userId: userId, factorTypeEnumId: type, statusId: status,
                createdDate: ec.user.nowTimestamp, confirmedDate: ec.user.nowTimestamp]).setSequencedIdPrimary().create()
    }
}
