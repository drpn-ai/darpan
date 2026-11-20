package darpan.peak

import darpan.audit.AuditSettingsSupport
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.automation.AutomationExecutionSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.sql.Date
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-328: PeakPeriodLoader reads one tenant's peak periods on a scheduler thread (no active tenant), and
 * tenantTimeZoneFor reads that tenant's own zone. Real Moqui, real entities.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PeakPeriodLoaderSmokeTests {
    private static final String A = "KREWE"
    private static final String B = "GORJANA"
    private static final LocalDate FROM = LocalDate.of(2026, 11, 20)

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "peak-period-loader-smoke")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AutomationSeedData.xml")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml")
        create("moqui.security.UserGroup", [userGroupId: B, description: "Gorjana",
                                            groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])

        period("PA_ACTIVE", A, "2026-11-24", "2026-12-01", "Y", "08:00", "23:00")
        period("PA_INACTIVE", A, "2026-11-24", "2026-12-01", "N", "08:00", "23:00")
        period("PA_PAST", A, "2026-10-01", "2026-10-31", "Y", null, null)
        period("PA_BAD_HOURS", A, "2026-12-10", "2026-12-12", "Y", "8am", "23:00")
        period("PB_ACTIVE", B, "2026-11-24", "2026-12-01", "Y", "09:00", "21:00")

        automation("AUTO_PK_CHECK", A)
        automation("AUTO_PK_DIRECT", A)
        create("darpan.audit.Audit", [auditId: "AUDIT_PK", companyUserGroupId: A, auditCode: "month-close-pk",
                                      auditName: "Month close", periodEnumId: "AUDP_MONTH"])
        create("darpan.audit.AuditStatement", [statementId: "AUDIT_PK_R1", companyUserGroupId: A, auditId: "AUDIT_PK",
                                               statementCode: "R1", categoryEnumId: "AUDC_ORDERS", weightEnumId: "AUDSW_FIRM",
                                               statementText: "Every Shopify order reaches the OMS."])
        create("darpan.audit.AuditCheck", [checkId: "AUDIT_PK_CHECK", companyUserGroupId: A, checkCode: "orders-pk",
                                           label: "Orders", automationId: "AUTO_PK_CHECK"])
        create("darpan.audit.AuditStatementCheck", [checkId: "AUDIT_PK_CHECK", statementId: "AUDIT_PK_R1", companyUserGroupId: A])

        create("darpan.peak.PeakPeriodRunTime", [runTimeId: "RT_AUDIT", peakPeriodId: "PA_ACTIVE", companyUserGroupId: A,
                                                 auditId: "AUDIT_PK", runTime: "06:00"])
        create("darpan.peak.PeakPeriodRunTime", [runTimeId: "RT_DIRECT", peakPeriodId: "PA_ACTIVE", companyUserGroupId: A,
                                                 automationId: "AUTO_PK_DIRECT", onDate: Date.valueOf("2026-11-28"), runTime: "05:30"])
        create("darpan.peak.PeakPeriodRunTime", [runTimeId: "RT_BAD", peakPeriodId: "PA_ACTIVE", companyUserGroupId: A,
                                                 automationId: "AUTO_PK_DIRECT", runTime: "6am"])
    }

    @AfterAll
    void cleanup() { ReconciliationSmokeTestSupport.cleanupMoqui(ec) }

    @Test
    void loadsOnlyThisTenantsActivePeriods() {
        assertEquals(["PA_ACTIVE"], PeakPeriodLoader.load(ec, A, FROM)*.peakPeriodId,
                "inactive, ended and malformed periods are left out; B's period never shows for A")
        assertEquals(["PB_ACTIVE"], PeakPeriodLoader.load(ec, B, FROM)*.peakPeriodId)
        assertEquals([], PeakPeriodLoader.load(ec, "NO_SUCH_TENANT", FROM))

        PeakWindow w = PeakPeriodLoader.load(ec, A, FROM)[0]
        assertEquals(LocalDate.of(2026, 11, 24), w.fromDate)
        assertEquals(LocalDate.of(2026, 12, 1), w.thruDate)
        assertEquals(LocalTime.of(8, 0), w.peakStart)
        assertEquals(LocalTime.of(23, 0), w.peakEnd)
    }

    @Test
    void aPeriodThatEndedTheDayBeforeIsStillLoadedForMidnightPeakHours() {
        assertEquals(["PA_PAST"], PeakPeriodLoader.load(ec, A, LocalDate.of(2026, 11, 1))
                .findAll { it.peakPeriodId == "PA_PAST" }*.peakPeriodId)
        assertTrue(PeakPeriodLoader.load(ec, A, LocalDate.of(2026, 11, 2)).every { it.peakPeriodId != "PA_PAST" })
    }

    @Test
    void auditMoveExpandsToItsCheckAutomations() {
        List<RunTimeMove> moves = PeakPeriodLoader.load(ec, A, FROM)[0].moves
        Set<String> checkAutomations = AuditSettingsSupport.automationsOfAudit(ec, A, [auditId: "AUDIT_PK"])*.automationId as Set<String>

        assertEquals(["AUTO_PK_CHECK"] as Set, checkAutomations)
        assertEquals(2, moves.size(), "the malformed 6am row is skipped, the others load")
        RunTimeMove auditMove = moves.find { it.runTime == LocalTime.of(6, 0) }
        assertEquals(checkAutomations, auditMove.automationIds)
        assertEquals(null, auditMove.onDate)
        RunTimeMove direct = moves.find { it.runTime == LocalTime.of(5, 30) }
        assertEquals(["AUTO_PK_DIRECT"] as Set, direct.automationIds)
        assertEquals(LocalDate.of(2026, 11, 28), direct.onDate)
    }

    @Test
    void tenantTimeZoneForReadsTheTenantsOwnSetting() {
        create(TenantAccessSupport.TENANT_SETTING_ENTITY_NAME, [companyUserGroupId: A, timeZone: "America/Los_Angeles"])
        create(TenantAccessSupport.TENANT_SETTING_ENTITY_NAME, [companyUserGroupId: B, timeZone: "Not/AZone"])

        assertEquals(ZoneId.of("America/Los_Angeles"), TenantAccessSupport.tenantTimeZoneFor(ec, A))
        assertEquals(ZoneId.of("UTC"), TenantAccessSupport.tenantTimeZoneFor(ec, B), "an invalid zone reads as UTC")
        assertEquals(ZoneId.of("UTC"), TenantAccessSupport.tenantTimeZoneFor(ec, "NO_SUCH_TENANT"))
    }

    private void period(String id, String tenant, String from, String thru, String active, String start, String end) {
        create("darpan.peak.PeakPeriod", [peakPeriodId: id, companyUserGroupId: tenant, periodName: id,
                                          fromDate: Date.valueOf(from), thruDate: Date.valueOf(thru), isActive: active,
                                          peakHoursStart: start, peakHoursEnd: end])
    }

    private void automation(String id, String tenant) {
        create("darpan.reconciliation.ReconciliationAutomation", [automationId: id, automationName: id, companyUserGroupId: tenant,
                inputModeEnumId: AutomationExecutionSupport.AUTOMATION_INPUT_API_RANGE, savedRunId: "PK_RUN", isActive: "Y"])
    }

    private void create(String entityName, Map fields) {
        boolean alreadyDisabled = ec.artifactExecution.disableAuthz()
        try {
            ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
        } finally {
            if (!alreadyDisabled) ec.artifactExecution.enableAuthz()
        }
    }
}
