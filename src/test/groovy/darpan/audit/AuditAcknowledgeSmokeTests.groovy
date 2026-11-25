package darpan.audit

import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import darpan.reconciliation.support.SharedMoqui
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.nio.file.Path
import java.sql.Date
import java.time.LocalDate
import java.time.ZoneId

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-361. Acknowledge, change and reopen one report row: every open line of the order under its statement plus the
 * later-step lines waiting under it, one note and one remind date, a user event per finding. Another tenant's finding id
 * reads as not found and nothing is written.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SharedMoqui
class AuditAcknowledgeSmokeTests {

    private static final String FACADE = "facade.AuditFacadeServices"
    private static final String LEDGER = "audit.AuditLedgerServices"
    private static final String MINE = "KREWE"
    private static final String OTHER = "TACK_OTHER"
    private static final String ZONE = "America/Los_Angeles"
    private static final List<String> ACK_EVENTS = ["AUDFE_ACKNOWLEDGED", "AUDFE_ACK_CHANGED", "AUDFE_ACK_REOPENED"]

    private ExecutionContext ec
    /** recordKey -> findingId, per statement code: ids["R1"]["M101/01"]. */
    private Map<String, Map<String, String>> ids = [:]
    private String otherFinding

    @BeforeAll
    void setup() {
        Path backendRoot = ReconciliationSmokeTestSupport.resolveBackendRoot()
        ec = ReconciliationSmokeTestSupport.initMoqui(backendRoot, "audit-acknowledge")
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/DarpanSystemSourceSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)   // signs in a KREWE editor, KREWE active
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            insert("moqui.security.UserGroup", [userGroupId: OTHER, description: "Other tenant"])
            [MINE, OTHER].each { String t ->
                insert("darpan.auth.TenantSetting", [companyUserGroupId: t, timeZone: ZONE])
                insert("darpan.audit.Audit", [auditId: "${t}_ACK".toString(), companyUserGroupId: t, auditCode: "ack-audit",
                        auditName: "Acknowledge", periodEnumId: "AUDP_MONTH"])
                [["R1", 1L], ["R2", 2L]].each { List st ->
                    insert("darpan.audit.AuditStatement", [statementId: "${t}_ACK_${st[0]}".toString(), companyUserGroupId: t,
                            auditId: "${t}_ACK".toString(), statementCode: st[0], categoryEnumId: "AUDC_ORDERS",
                            weightEnumId: "AUDSW_FIRM", sequenceNum: st[1], statementText: "Statement ${st[0]}.".toString()])
                    insert("darpan.audit.AuditCheck", [checkId: "${t}_ACK_C${st[0]}".toString(), companyUserGroupId: t,
                            checkCode: "ack-check-${st[0]}".toString().toLowerCase(), label: "Check ${st[0]}".toString()])
                    insert("darpan.audit.AuditStatementCheck", [checkId: "${t}_ACK_C${st[0]}".toString(),
                            statementId: "${t}_ACK_${st[0]}".toString(), companyUserGroupId: t, sequenceNum: 1L])
                }
            }
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
        // Each test has its own order, so the shared ledger is recorded once.
        record(MINE, "R1", ["M101/01", "M101/02", "M102/01", "M103/01", "M104/01", "M105/01", "M106/01", "M107/01"], ["INV-108"])
        record(MINE, "R2", ["M101/01", "M105/01"], [])
        otherFinding = record(OTHER, "R1", ["M901/01"], [])["M901/01"]
        // M104 was fixed this morning.
        boolean off = ec.artifactExecution.disableAuthz()
        try {
            ec.entity.find("darpan.audit.AuditFinding").condition("findingId", ids.R1["M104/01"]).one()
                    .set("stateEnumId", "AUDFS_FIXED").update()
        } finally {
            if (!off) ec.artifactExecution.enableAuthz()
        }
    }

    private Map<String, String> record(String tenant, String statementCode, List<String> items, List<String> invoices) {
        List<Map> findings = items.collect { String item ->
            [statementCode: statementCode, recordKey: item, headline: "Item ${item} is wrong".toString(),
             records: [[systemEnumId: "OMS_RETURNS_GQL", recordType: "orderItem", recordId: item]]]
        } + invoices.collect { String inv ->
            [statementCode: statementCode, recordKey: inv, headline: "Invoice ${inv} is wrong".toString(),
             records: [[systemEnumId: "NETSUITE_SUITEQL", recordType: "invoice", recordId: inv]]]
        }
        Map out = ec.service.sync().name("${LEDGER}.record#CheckFindings").parameters([companyUserGroupId: tenant,
                checkId: "${tenant}_ACK_C${statementCode}".toString(), reportDate: Date.valueOf("2026-10-08"), findings: findings]).call()
        assertFalse(ec.message.hasError(), ec.message.errorsString)
        Map<String, String> byKey = [items, invoices].flatten().withIndex().collectEntries { k, i -> [(k): (out.findingIds as List)[i]] }
        if (tenant == MINE) ids.computeIfAbsent(statementCode) { [:] }.putAll(byKey)
        return byKey
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void clearMessages() {
        ec.message.clearErrors()
    }

    private Map call(String verbNoun, Map params) {
        Map out = ec.service.sync().name("${FACADE}.${verbNoun}").parameters(params).call() ?: [:]
        Map result = out + [hadError: ec.message.hasError(), errorText: ec.message.errorsString]
        ec.message.clearErrors()
        return result
    }

    /** Authz off: the tenant filter is skipped, so another tenant's rows would show here (a real cross-tenant check). */
    private List<Map> unscoped(Closure<List> read) {
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try { return read()*.getMap() as List<Map> }
        finally { if (!wasDisabled) ec.artifactExecution.enableAuthz() }
    }

    private List<Map> acks(List<String> findingIds) {
        return unscoped { ec.entity.find("darpan.audit.AuditFindingAcknowledgement").condition("findingId", "in", findingIds).list() }
    }

    private List<Map> events(List<String> findingIds, String type) {
        return unscoped { ec.entity.find("darpan.audit.AuditFindingEvent").condition("findingId", "in", findingIds)
                .condition("eventTypeEnumId", type).list() }
    }

    private static LocalDate today() { LocalDate.now(ZoneId.of(ZONE)) }

    @Test
    void acknowledgeCoversTheWholeRow() {
        List<String> row = [ids.R1["M101/01"], ids.R1["M101/02"], ids.R2["M101/01"]]
        Map out = call("acknowledge#AuditFinding", [findingId: ids.R1["M101/01"], note: "  Known: the 3PL is late  "])
        assertFalse(out.hadError as boolean, out.errorText as String)

        List<Map> rows = acks(row)
        assertEquals(row.toSet(), rows*.findingId.toSet(), "both R1 lines of order M101 and the R2 line waiting under them")
        assertEquals(["Known: the 3PL is late"], rows*.note.unique())
        assertEquals([Date.valueOf(today().plusDays(5))], rows*.remindDate.unique(), "today in the tenant's zone + remindDays (5)")
        assertEquals([MINE], rows*.companyUserGroupId.unique())
        assertEquals([ec.user.userId], rows*.acknowledgedByUserId.unique())
        assertEquals([], acks([ids.R1["M105/01"], ids.R1["M102/01"]]), "another order's lines are untouched")

        List<Map> evs = events(row, "AUDFE_ACKNOWLEDGED")
        assertEquals(row.toSet(), evs*.findingId.toSet())
        assertEquals(3, evs.size())
        assertEquals(["AUDAT_USER"], evs*.byTypeEnumId.unique())
        assertEquals([ec.user.userId], evs*.byId.unique())
        assertEquals([Date.valueOf(today())], evs*.eventDate.unique())

        Map summary = out.acknowledgement as Map
        assertEquals("Known: the 3PL is late", summary.note)
        assertEquals(ec.user.userId, (summary.by as Map).id)
        assertEquals(today().plusDays(5).toString(), summary.remindOn)
        assertFalse(summary.reminded as boolean)
        assertTrue((summary.at as String) ==~ /\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d[-+]\d\d:\d\d/, summary.at as String)

        // Acknowledging again from the waiting line replaces its own unit's row, not a second one.
        Map again = call("acknowledge#AuditFinding", [findingId: ids.R2["M101/01"], note: "Second word", remind: false])
        assertFalse(again.hadError as boolean, again.errorText as String)
        assertEquals(1, acks([ids.R2["M101/01"]]).size())
        assertEquals("Second word", acks([ids.R2["M101/01"]])[0].note)
        assertNull(acks([ids.R2["M101/01"]])[0].remindDate)
    }

    @Test
    void remindOffStoresNoDate() {
        String inv = ids.R1["INV-108"]
        Map out = call("acknowledge#AuditFinding", [findingId: inv, note: "Known", remind: false])
        assertFalse(out.hadError as boolean, out.errorText as String)
        List<Map> rows = acks([inv])
        assertEquals(1, rows.size(), "a finding that is not an order line is its own row")
        assertNull(rows[0].remindDate)
        assertNull((out.acknowledgement as Map).remindOn)
        assertEquals([inv], AuditReportAssembler.unitMemberIds(ec, MINE, inv))
    }

    @Test
    void blankNoteRefused() {
        String f = ids.R1["M103/01"]
        Map out = call("acknowledge#AuditFinding", [findingId: f, note: "   "])
        assertTrue(out.hadError as boolean)
        assertTrue((out.errorText as String).contains("Add a note"), out.errorText as String)
        assertEquals([], acks([f]))
        assertEquals([], events([f], "AUDFE_ACKNOWLEDGED"))
    }

    @Test
    void fixedFindingRefused() {
        String f = ids.R1["M104/01"]
        Map out = call("acknowledge#AuditFinding", [findingId: f, note: "Known"])
        assertTrue(out.hadError as boolean)
        assertTrue((out.errorText as String).contains("Only an open finding can be acknowledged"), out.errorText as String)
        assertEquals([], acks([f]))
        assertEquals([], events([f], "AUDFE_ACKNOWLEDGED"))
    }

    @Test
    void otherTenantsFindingIsNotFound() {
        [["acknowledge#AuditFinding", [note: "Known"]], ["update#AuditFindingAcknowledgement", [note: "Known", remind: true]],
         ["delete#AuditFindingAcknowledgement", [:]]].each { List c ->
            Map out = this.call(c[0] as String, [findingId: otherFinding] + (c[1] as Map))
            assertTrue(out.hadError as boolean, c[0] as String)
            assertTrue((out.errorText as String).contains("Finding ${otherFinding} was not found"), out.errorText as String)
        }
        assertEquals([], acks([otherFinding]))
        ACK_EVENTS.each { assertEquals([], events([otherFinding], it), it) }
    }

    @Test
    void changeRewritesTheNoteAndKeepsTheRemindDay() {
        List<String> row = [ids.R1["M106/01"]]
        assertFalse(call("acknowledge#AuditFinding", [findingId: row[0], note: "First"]).hadError as boolean)
        Date remind = acks(row)[0].remindDate as Date

        Map out = call("update#AuditFindingAcknowledgement", [findingId: row[0], note: "Second", remind: true])
        assertFalse(out.hadError as boolean, out.errorText as String)
        Map ack = acks(row)[0]
        assertEquals("Second", ack.note)
        assertEquals(remind, ack.remindDate, "a remind day not yet reached stays")
        assertEquals(ec.user.userId, ack.lastChangedByUserId)
        assertNotNull(ack.lastChangedStamp)
        assertEquals("Second", (out.acknowledgement as Map).note)
        assertEquals(1, events(row, "AUDFE_ACK_CHANGED").size())

        assertFalse(call("update#AuditFindingAcknowledgement", [findingId: row[0], note: "Third", remind: false]).hadError as boolean)
        assertNull(acks(row)[0].remindDate, "remind off")
        assertFalse(call("update#AuditFindingAcknowledgement", [findingId: row[0], note: "Fourth", remind: true]).hadError as boolean)
        assertEquals(Date.valueOf(today().plusDays(5)), acks(row)[0].remindDate, "remind back on counts from today")
        assertEquals(3, events(row, "AUDFE_ACK_CHANGED").size())

        Map blank = call("update#AuditFindingAcknowledgement", [findingId: row[0], note: " ", remind: true])
        assertTrue((blank.errorText as String).contains("Add a note"), blank.errorText as String)
        Map none = call("update#AuditFindingAcknowledgement", [findingId: ids.R1["M107/01"], note: "x", remind: true])
        assertTrue((none.errorText as String).contains("is not acknowledged"), none.errorText as String)
    }

    @Test
    void reopenDeletesTheRow() {
        List<String> row = [ids.R1["M105/01"], ids.R2["M105/01"]]
        assertFalse(call("acknowledge#AuditFinding", [findingId: row[0], note: "Known"]).hadError as boolean)
        assertEquals(2, acks(row).size())

        Map out = call("delete#AuditFindingAcknowledgement", [findingId: row[0]])
        assertFalse(out.hadError as boolean, out.errorText as String)
        assertEquals([], acks(row))
        List<Map> evs = events(row, "AUDFE_ACK_REOPENED")
        assertEquals(row.toSet(), evs*.findingId.toSet())
        assertEquals(["AUDAT_USER"], evs*.byTypeEnumId.unique())
    }

    private void insert(String entityName, Map<String, Object> fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }
}
