package darpan.admin

import darpan.config.DarpanSettings
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.Moqui
import org.moqui.context.ArtifactAuthorizationException
import org.moqui.context.ExecutionContext

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.*

/**
 * DAR-BE-304: admin.SettingAdminServices through the real service engine, authz ON, as a real DARPAN_SUPER_ADMIN on a thread
 * of its own. Edge cases: a save is live the moment the call returns, a refused value saves nothing, reset follows the default
 * again, the silent-vs-reaper rule holds for both keys through the service, a JVM property shows as its own source, and only
 * a super admin gets in.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SettingAdminServicesSmokeTests {
    private ExecutionContext ec

    private static final String SUPER_ID = "SETSVC_SUPER"
    private static final String SUPER = "setsvc.super"
    private static final String TENANT_ID = "SETSVC_TENANT"
    private static final String TENANT = "setsvc.tenant"
    private static final String DADMIN_ID = "SETSVC_DADMIN"
    private static final String DADMIN = "setsvc.dadmin"
    private static final String PLAIN_ID = "SETSVC_PLAIN"
    private static final String PLAIN = "setsvc.plain"
    private static final String S = "admin.SettingAdminServices."
    private static final String MAX = "darpan.reconciliation.automation.maxConcurrentExecutions"
    private static final String SILENT = "darpan.reconciliation.runHealth.silentAnyStageMinutes"
    private static final String REAPER = "darpan.reconciliation.runHealth.reaperStaleMinutes"

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "setting-admin-services")
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @BeforeEach
    void resetFixtures() {
        ec.entity.find("darpan.config.DarpanSetting").disableAuthz().list().each { it.delete() }
        DarpanSettings.expire()
        AuditLogTestSupport.wipe(ec, "darpan.admin.AdminAuditLog")
        [[SUPER_ID, SUPER], [TENANT_ID, TENANT], [DADMIN_ID, DADMIN], [PLAIN_ID, PLAIN]].each { id, name ->
            ec.entity.makeValue("moqui.security.UserAccount").setAll([userId: id, username: name, userFullName: name,
                    emailAddress: "${name}@example.com", disabled: "N", requirePasswordChange: "N"]).createOrUpdate()
        }
        [[SUPER_ID, "DARPAN_SUPER_ADMIN"], [TENANT_ID, "DARPAN_TENANT_ADMIN"], [DADMIN_ID, "DARPAN_ADMIN"]].each { id, group ->
            if (!ec.entity.find("moqui.security.UserGroupMember").condition([userGroupId: group, userId: id])
                    .disableAuthz().useCache(false).count()) {
                ec.entity.makeValue("moqui.security.UserGroupMember").setAll([userGroupId: group, userId: id,
                        fromDate: Timestamp.valueOf("2026-01-01 00:00:00")]).create()
            }
        }
        ec.message.clearErrors()
    }

    @AfterEach
    void clearProps() {
        [MAX, SILENT, REAPER].each { System.clearProperty(it) }
        DarpanSettings.expire()
    }

    @Test
    void theListIsEveryCatalogKeyAtItsDefault() {
        List<Map> all = ok(svc("get#Settings", [:])).result.settings
        assertEquals(DarpanSettings.catalog().keySet(), all*.key.toSet())
        Map max = all.find { it.key == MAX }
        assertEquals("DEFAULT", max.source)
        assertTrue(all*.source.every { it in ["DEFAULT", "PROPERTY"] }, "no row exists yet")
        assertEquals("8", max.effectiveValue)
        assertNull(max.rowValue)
        assertEquals(MAX, ok(svc("get#Setting", [settingKey: MAX])).result.setting.key)
        assertTrue(svc("get#Setting", [settingKey: "darpan.nope"]).errors.any { it.contains("No setting darpan.nope.") })
    }

    @Test
    void anEditIsLiveWhenTheCallReturnsAuditedAndResetFollowsTheDefault() {
        Map out = ok(svc("update#Setting", [settingKey: MAX, value: " 4 ", note: "load test"])).result.setting
        assertEquals("ROW", out.source)
        assertEquals("4", out.rowValue)
        assertEquals("load test", out.note)
        assertEquals(SUPER_ID, out.lastChangedByUserId)
        assertEquals(4, AutomationExecutionSupport.maxConcurrentExecutions(), "no restart, no wait")
        assertEquals(["${MAX}: 8 -> 4 (load test)".toString()], auditRows()*.detailText)
        assertEquals("DarpanSetting", auditRows()[0].targetType)
        assertEquals(MAX, auditRows()[0].targetId)
        assertEquals(S + "update#Setting", auditRows()[0].serviceName)

        // saving the default's value still pins it as a row
        ok(svc("update#Setting", [settingKey: MAX, value: "8"]))
        assertEquals("ROW", ok(svc("get#Setting", [settingKey: MAX])).result.setting.source)
        assertEquals("${MAX}: 4 -> 8".toString(), auditRows()[1].detailText)

        Map reset = ok(svc("reset#Setting", [settingKey: MAX])).result.setting
        assertEquals("DEFAULT", reset.source)
        assertNull(reset.rowValue)
        assertEquals(8, AutomationExecutionSupport.maxConcurrentExecutions())
        assertEquals("${MAX}: 8 -> default 8".toString(), auditRows()[2].detailText)
        assertEquals(S + "reset#Setting", auditRows()[2].serviceName)

        // nothing to reset: ok, no audit row
        ok(svc("reset#Setting", [settingKey: MAX]))
        assertEquals(3, auditRows().size())
        assertTrue(svc("reset#Setting", [settingKey: "darpan.nope"]).errors.any { it.contains("No setting") })
    }

    private static final String HOOK = "errors.chat.webhookUrl"
    private static final String GOOGLE_URL = "https://chat.googleapis.com/v1/spaces/AAA/messages?key=k&token=abcdef123456"
    private static final String SLACK_URL = "https://hooks.slack.com/services/T0000/B0000/secretsecret"

    @Test
    void theErrorsWebhookAcceptsGoogleChatAndSlackAndNeverShowsOrAuditsTheWholeUrl() {
        Map out = ok(svc("update#Setting", [settingKey: HOOK, value: GOOGLE_URL])).result.setting
        assertEquals("\u2026123456", out.rowValue)
        assertEquals("\u2026123456", out.effectiveValue)
        assertEquals(GOOGLE_URL, DarpanSettings.stringValue(HOOK), "stored and read in full")
        assertEquals("\u2026123456", ok(svc("get#Setting", [settingKey: HOOK])).result.setting.rowValue)
        assertEquals("\u2026123456", ok(svc("get#Settings", [:])).result.settings.find { it.key == HOOK }.rowValue)

        Map slack = ok(svc("update#Setting", [settingKey: HOOK, value: SLACK_URL])).result.setting
        assertEquals("\u2026secret", slack.effectiveValue)

        Map reset = ok(svc("reset#Setting", [settingKey: HOOK])).result.setting
        assertNull(reset.rowValue)

        List<String> audits = auditRows()*.detailText
        assertEquals(3, audits.size())
        assertEquals("${HOOK}: null -> \u2026123456".toString(), audits[0])
        assertEquals("${HOOK}: \u2026123456 -> \u2026secret".toString(), audits[1])
        audits.each { assertFalse(it.contains("token=") || it.contains("hooks.slack.com") || it.contains("chat.googleapis.com"), "audit holds the whole webhook: ${it}") }
    }

    @Test
    void theErrorsWebhookRefusesOtherHostsAndNothingIsSaved() {
        ["https://evil.example.com/v1/spaces/AAA/messages?key=k&token=t", "http://chat.googleapis.com/v1/spaces/A/messages?key=k&token=t",
         "https://hooks.slack.com/services/T0000", "not a url"].each { String v ->
            Map out = svc("update#Setting", [settingKey: HOOK, value: v])
            assertTrue(out.hasError, "'${v}' must be refused")
            assertFalse(out.errors.any { it.contains(v) }, "the refusal must not echo the value: ${out.errors}")
        }
        assertEquals(0, ec.entity.find("darpan.config.DarpanSetting").disableAuthz().useCache(false).count())
        assertEquals([], auditRows())
    }

    @Test
    void theErrorsEmailTakesAnAddressListAndRefusesAnythingElseWithoutEchoingIt() {
        String key = "errors.email.to"
        ["ops@drpn", "ops@drpn.test, not-an-address", "ops@drpn.test;;bad@", "ops at drpn.test"].each { String v ->
            Map out = svc("update#Setting", [settingKey: key, value: v])
            assertTrue(out.hasError, "'${v}' must be refused")
            assertTrue(out.errors.any { it.contains(key) }, "${out.errors}")
            assertFalse(out.errors.any { it.contains(v) || it.contains("not-an-address") || it.contains("bad@") }, "the refusal must not echo the value: ${out.errors}")
        }
        assertEquals(0, ec.entity.find("darpan.config.DarpanSetting").disableAuthz().useCache(false).count())
        Map saved = ok(svc("update#Setting", [settingKey: key, value: "ops@drpn.test; oncall@drpn.test"])).result.setting
        assertEquals("ops@drpn.test; oncall@drpn.test", saved.effectiveValue)
    }

    @Test
    void theUnexplainedEmailIsValidatedLikeTheErrorsEmail() {
        String key = "audit.unexplained.email.to"
        ["ops@drpn", "ops@drpn.test, not-an-address", "ops at drpn.test"].each { String v ->
            Map out = svc("update#Setting", [settingKey: key, value: v])
            assertTrue(out.hasError, "'${v}' must be refused")
            assertTrue(out.errors.any { it.contains(key) }, "${out.errors}")
            assertFalse(out.errors.any { it.contains(v) || it.contains("not-an-address") }, "the refusal must not echo the value: ${out.errors}")
        }
        assertEquals(0, ec.entity.find("darpan.config.DarpanSetting").disableAuthz().useCache(false).count())
        assertEquals([], auditRows())
        Map saved = ok(svc("update#Setting", [settingKey: key, value: "ops@drpn.test, oncall@drpn.test"])).result.setting
        assertEquals("ops@drpn.test, oncall@drpn.test", saved.effectiveValue)
    }

    @Test
    void aRefusedValueSavesNothingAndSaysWhy() {
        ["0", "abc", "", "33"].each { String v ->
            Map out = svc("update#Setting", [settingKey: MAX, value: v])
            assertTrue(out.hasError, "'${v}' must be refused")
            if (v) assertTrue(out.errors.any { it.contains(MAX) }, "${out.errors}")
        }
        assertTrue(svc("update#Setting", [settingKey: "darpan.nope", value: "1"]).errors.any { it.contains("Unknown setting") })
        assertEquals(0, ec.entity.find("darpan.config.DarpanSetting").disableAuthz().useCache(false).count())
        assertEquals([], auditRows())
        assertEquals(8, AutomationExecutionSupport.maxConcurrentExecutions())
    }

    @Test
    void silentAnyStageMustStayBelowTheReaperInBothDirections() {
        // reaper default 120, silent default 90
        Map tooHigh = svc("update#Setting", [settingKey: SILENT, value: "120"])
        assertTrue(tooHigh.errors.any { it.contains("must stay below ${REAPER}".toString()) }, "${tooHigh.errors}")
        Map tooLow = svc("update#Setting", [settingKey: REAPER, value: "90"])
        assertTrue(tooLow.errors.any { it.contains("must stay above ${SILENT}".toString()) }, "${tooLow.errors}")
        assertEquals(0, ec.entity.find("darpan.config.DarpanSetting").disableAuthz().useCache(false).count())
        assertEquals([], auditRows())
        // raising the reaper first then the silent value is how it is done
        ok(svc("update#Setting", [settingKey: REAPER, value: "240"]))
        ok(svc("update#Setting", [settingKey: SILENT, value: "200"]))
        assertEquals("200", ok(svc("get#Setting", [settingKey: SILENT])).result.setting.effectiveValue)
    }

    @Test
    void aResetNeverLeavesSilentAndReaperOutOfOrderAndANoteHasALimit() {
        ok(svc("update#Setting", [settingKey: REAPER, value: "240"]))
        ok(svc("update#Setting", [settingKey: SILENT, value: "200"]))
        Map r1 = svc("reset#Setting", [settingKey: REAPER])
        assertTrue(r1.errors.any { it.contains(SILENT) && it.contains("120") }, "${r1.errors}")
        assertEquals("240", ok(svc("get#Setting", [settingKey: REAPER])).result.setting.rowValue)
        // the other direction: silent row 200 under reaper default 120 is not possible, so fake it with a property
        ok(svc("update#Setting", [settingKey: SILENT, value: "100"]))
        ok(svc("reset#Setting", [settingKey: REAPER]))   // 100 < 120 fine
        System.setProperty(SILENT, "130")
        DarpanSettings.expire()
        Map r2 = svc("reset#Setting", [settingKey: SILENT])  // property 130 would sit above reaper 120
        assertTrue(r2.errors.any { it.contains(REAPER) }, "${r2.errors}")
        assertEquals("100", ok(svc("get#Setting", [settingKey: SILENT])).result.setting.rowValue)
        Map longNote = svc("update#Setting", [settingKey: MAX, value: "4", note: "x" * 256])
        assertTrue(longNote.errors.any { it.contains("255") }, "${longNote.errors}")
        assertEquals(0, ec.entity.find("darpan.config.DarpanSetting").condition("settingKey", MAX).disableAuthz().useCache(false).count())
        ok(svc("update#Setting", [settingKey: MAX, value: "4", note: "x" * 255]))
    }

    @Test
    void resettingAnOnOffOrListSettingIsNotReadAsANumber() {
        String flag = "darpan.reconciliation.automation.verifyMissingDiffs"
        String list = "darpan.twoFactor.reminderDaysBefore"
        ok(svc("update#Setting", [settingKey: flag, value: "false"]))
        ok(svc("update#Setting", [settingKey: list, value: "5,2"]))
        assertEquals("DEFAULT", ok(svc("reset#Setting", [settingKey: flag])).result.setting.source)
        assertEquals("DEFAULT", ok(svc("reset#Setting", [settingKey: list])).result.setting.source)
        assertEquals(0, ec.entity.find("darpan.config.DarpanSetting").disableAuthz().useCache(false).count())
        assertEquals(["${flag}: false -> default true".toString(), "${list}: 5,2 -> default 7,3,1,0".toString()],
                auditRows()*.detailText.findAll { it.contains("default") })
    }

    @Test
    void aJvmPropertyShowsAsItsOwnSourceAndARowBeatsIt() {
        System.setProperty(MAX, "6")
        DarpanSettings.expire()
        Map s = ok(svc("get#Setting", [settingKey: MAX])).result.setting
        assertEquals("PROPERTY", s.source)
        assertEquals("6", s.propertyValue)
        assertEquals("6", s.effectiveValue)
        ok(svc("update#Setting", [settingKey: MAX, value: "3"]))
        Map row = ok(svc("get#Setting", [settingKey: MAX])).result.setting
        assertEquals("ROW", row.source)
        assertEquals("6", row.propertyValue)
        assertEquals("${MAX}: 6 -> 3".toString(), auditRows()[0].detailText)
        ok(svc("reset#Setting", [settingKey: MAX]))
        assertEquals("${MAX}: 3 -> property 6".toString(), auditRows()[1].detailText)
        assertEquals("PROPERTY", ok(svc("get#Setting", [settingKey: MAX])).result.setting.source)
    }

    @Test
    void onlyASuperAdminGetsIn() {
        ["get#Settings": [:], "get#Setting": [settingKey: MAX], "update#Setting": [settingKey: MAX, value: "2"],
         "reset#Setting": [settingKey: MAX]].each { String method, Map params ->
            [PLAIN, TENANT, DADMIN].each { String who ->
                Map out = svc(method, params, who)
                assertTrue(out.authzDenied != null || out.hasError, "${method} must refuse ${who}")
            }
        }
        assertEquals(0, ec.entity.find("darpan.config.DarpanSetting").disableAuthz().useCache(false).count())
        assertEquals([], auditRows())
    }

    private List auditRows() {
        return ec.entity.find("darpan.admin.AdminAuditLog").orderBy("auditTimestamp").disableAuthz().useCache(false).list()
    }

    private Map svc(String method, Map params, String username = SUPER) {
        Map holder = [:]
        Thread worker = new Thread({
            ExecutionContext tec = Moqui.getExecutionContextFactory().getExecutionContext()
            try {
                if (!tec.user.internalLoginUser(username)) throw new IllegalStateException("could not log in ${username}")
                try {
                    holder.result = tec.service.sync().name(S + method).parameters(new LinkedHashMap(params)).call()
                } catch (ArtifactAuthorizationException e) {
                    holder.authzDenied = e
                }
                holder.errors = new ArrayList<String>(tec.message.errors) + (tec.message.validationErrors*.message ?: [])
                holder.hasError = tec.message.hasError()
            } catch (Throwable t) {
                holder.failure = t
            } finally {
                tec.destroy()
            }
        } as Runnable, "setting-admin-smoke")
        worker.start()
        worker.join()
        if (holder.failure) throw (Throwable) holder.failure
        return holder
    }

    private static Map ok(Map out) {
        assertNull(out.authzDenied, "unexpected authz denial: ${out.authzDenied}")
        assertFalse(out.hasError as boolean, "unexpected errors: ${out.errors}")
        return out
    }
}
