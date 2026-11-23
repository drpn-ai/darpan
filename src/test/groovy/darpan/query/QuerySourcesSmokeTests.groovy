package darpan.query

import darpan.dataset.DatasetRefreshSupport
import darpan.dataset.KeptCopyStore
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import darpan.reconciliation.support.SharedMoqui
import groovy.json.JsonOutput
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ExecutionContext

import java.sql.Timestamp

import static darpan.query.QueryTestSupport.EDITOR_ID
import static darpan.query.QueryTestSupport.GORJANA
import static darpan.query.QueryTestSupport.KREWE
import static darpan.query.QueryTestSupport.insert
import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/** DAR-BE-334: the dataset and runResult read sources — the query's tenant only, kept fields only, capped. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@SharedMoqui
class QuerySourcesSmokeTests {
    private static final Timestamp RUN = Timestamp.valueOf("2026-11-21 09:00:00")
    private static final Timestamp READ_THRU = Timestamp.valueOf("2026-11-21 00:00:00")
    private ExecutionContext ec

    @BeforeAll
    void setup() {
        ec = QueryTestSupport.boot("query-sources")
        insert(ec, "moqui.basic.EnumerationType", [enumTypeId: "DatasetStatus", description: "Dataset status"])
        insert(ec, "moqui.basic.EnumerationType", [enumTypeId: "DatasetReadRule", description: "Dataset read rule"])
        insert(ec, "moqui.basic.Enumeration", [enumId: "DST_ACTIVE", enumTypeId: "DatasetStatus", description: "Active"])
        insert(ec, "moqui.basic.Enumeration", [enumId: "DRR_ROLLING", enumTypeId: "DatasetReadRule", description: "Rolling"])
        dataset("QS_DS_K", KREWE, true)
        dataset("QS_DS_G", GORJANA, true)
        dataset("QS_DS_EMPTY", KREWE, false)
        ["QS_CAT": "QsCategory", "QS_WT": "QsWeight", "QS_PER": "QsPeriod", "QS_OPEN": "QsState", "QS_CONC": "QsConclusion"].each { String id, String type ->
            insert(ec, "moqui.basic.EnumerationType", [enumTypeId: type, description: type])
            insert(ec, "moqui.basic.Enumeration", [enumId: id, enumTypeId: type, description: id == "QS_CONC" ? "Late shipment" : id])
        }
        audit("QS_AUD_K", KREWE)
        audit("QS_AUD_G", GORJANA)
        insert(ec, "jexl.script.UserScript", [userScriptId: "QS_SCRIPT_K", companyUserGroupId: KREWE, scriptName: "Backorders",
                scriptProfile: "QUERY", scriptBody: "size(input.orders)", isActive: "Y", ownerUserId: EDITOR_ID,
                readSpecJson: JsonOutput.toJson([inputs: [[name: "orders", source: "dataset", datasetId: "QS_DS_K", fields: ["id", "status"],
                        where: [field: "status", op: "equals", value: "B"]]]])])
    }

    @AfterAll
    void cleanup() {
        DatasetQuerySource.resetForTests()
        RunResultQuerySource.resetForTests()
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @AfterEach
    void reset() {
        DatasetQuerySource.resetForTests()
        RunResultQuerySource.resetForTests()
        ec.message.clearAll()
    }

    private void dataset(String id, String tenant, boolean withCopy) {
        insert(ec, "darpan.dataset.Dataset", [datasetId: id, companyUserGroupId: tenant, systemEnumId: "QS_SYS", sourceConfigId: "QS_CONN",
                optionsJson: "{}", optionsHash: id.padRight(64, "0"), statusId: "DST_ACTIVE", readRuleEnumId: "DRR_ROLLING",
                idField: "id", stampField: "updatedAt", windowField: "createdAt"])
        if (!withCopy) return
        List<Map> records = [
                [id: "1", status: "B", qty: 2, createdAt: "2026-11-20T10:00:00Z", updatedAt: "2026-11-20T10:00:00Z"],
                [id: "2", status: "A", qty: 4, createdAt: "2026-11-19T10:00:00Z", updatedAt: "2026-11-19T10:00:00Z"],
                [id: "3", status: "B", qty: 1, createdAt: "2026-11-18T10:00:00Z", updatedAt: "2026-11-18T10:00:00Z"],
                [id: "4", status: "B", qty: 9, createdAt: "2026-09-01T10:00:00Z", updatedAt: "2026-09-01T10:00:00Z"],
                [id: "5", status: "C", qty: 3, createdAt: "2026-11-17T10:00:00Z", updatedAt: "2026-11-17T10:00:00Z"]]
        KeptCopyStore.replace(DatasetRefreshSupport.datasetDir(ec, tenant, id), records.iterator(), "id", "updatedAt", "createdAt",
                Timestamp.valueOf("2026-08-01 00:00:00"), READ_THRU, READ_THRU, 2, null, false, null)
    }

    private void audit(String id, String tenant) {
        insert(ec, "darpan.audit.Audit", [auditId: id, companyUserGroupId: tenant, auditCode: id, auditName: "Month close", periodEnumId: "QS_PER"])
        insert(ec, "darpan.audit.AuditStatement", [statementId: id + "_S1", companyUserGroupId: tenant, auditId: id, statementCode: "R1",
                categoryEnumId: "QS_CAT", weightEnumId: "QS_WT", statementText: "Every order ships within 2 days", isActive: "Y"])
        insert(ec, "darpan.audit.AuditCheck", [checkId: id + "_C1", companyUserGroupId: tenant, checkCode: "SHIP", label: "Shipped on time"])
        [[n: "1", first: "2026-11-20", closed: null], [n: "2", first: "2026-09-01", closed: null],
         [n: "3", first: "2026-11-19", closed: Timestamp.valueOf("2026-11-20 08:00:00")]].each { Map f ->
            insert(ec, "darpan.audit.AuditFinding", [findingId: id + "_F" + f.n, companyUserGroupId: tenant, statementId: id + "_S1",
                    checkId: id + "_C1", recordKey: "SO-" + f.n, period: "2026-11", stateEnumId: "QS_OPEN", headline: "SO-" + f.n + " late",
                    firstFoundDate: java.sql.Date.valueOf(f.first as String), lastSeenDate: java.sql.Date.valueOf("2026-11-20"), timesSeen: 2,
                    conclusionEnumId: "QS_CONC", closedStamp: f.closed])
        }
    }

    private Map read(String service, Map input, Map extra = [:]) {
        Map out = ec.service.sync().name("query.QueryHostServices.${service}").parameters([input: input, ownerUserId: EDITOR_ID,
                ownerKey: KREWE, runDate: RUN, timeZone: "UTC", maxRows: 100] + extra).call()
        ec.message.clearErrors()
        return out
    }

    private static Map orders(Map extra = [:]) { [name: "orders", source: "dataset", datasetId: "QS_DS_K", fields: ["id", "status"]] + extra }

    @Test
    void aDatasetReadsKeptFieldsWithFilters() {
        Map out = read("read#DatasetSource", orders(where: [field: "status", op: "equals", value: "B"], orderBy: ["id"]))
        assertNull(out.error, "${out.error}")
        assertEquals(["1", "3", "4"], out.rows*.id)
        assertEquals(["id", "status"] as Set, (out.rows[0] as Map).keySet())
    }

    @Test
    void aFieldNoLongerKeptIsAReadError() {
        DatasetQuerySource.keptFieldsOf = { def c, Map ds -> ["id", "status", "createdAt", "updatedAt"] as Set<String> }
        Map out = read("read#DatasetSource", orders(fields: ["id", "qty"]))
        assertEquals("READ_ERROR", out.error?.kind)
        assertTrue((out.error.message as String).contains("qty is no longer kept"), "${out.error}")
    }

    @Test
    void anotherTenantsDatasetIsRefused() {
        Map out = read("read#DatasetSource", orders(datasetId: "QS_DS_G"))
        assertEquals("READ_NOT_ALLOWED", out.error?.kind)
    }

    @Test
    void anOwnerWhoLeftTheTenantReadsNothing() {
        Map out = read("read#DatasetSource", orders(datasetId: "QS_DS_G"), [ownerKey: GORJANA])
        assertEquals("READ_NOT_ALLOWED", out.error?.kind)
        assertTrue((out.error.message as String).contains("not a member"), "${out.error}")
    }

    @Test
    void aScheduledReadSeesTheScriptsTenant() {
        // through moqui-jexl: the read runs as the owner in a fresh context; the source takes the tenant from the saved query
        Map out = ec.service.sync().name("moqui.jexl.ScriptServices.run#UserScript").parameters([userScriptId: "QS_SCRIPT_K"]).call()
        String errors = ec.message.errorsString
        ec.message.clearErrors()
        assertTrue(out.ok as boolean, "${out.error} ${errors}")
        assertEquals(3, out.scriptResult)
        assertEquals(3L, (out.readCounts as Map).orders as long)
    }

    @Test
    void aDatasetWithNoCopyYetSaysSo() {
        Map out = read("read#DatasetSource", orders(datasetId: "QS_DS_EMPTY"))
        assertEquals("READ_ERROR", out.error?.kind)
        assertTrue((out.error.message as String).contains("no kept copy yet"), "${out.error}")
    }

    @Test
    void overTheRowCapStopsEarly() {
        Map out = read("read#DatasetSource", orders(), [maxRows: 2])
        assertEquals("READ_TOO_LARGE", out.error?.kind)
    }

    @Test
    void windowUsesTheDatasetWindowField() {
        Map out = read("read#DatasetSource", orders(where: [field: "status", op: "equals", value: "B"], window: [field: "ignored", lastDays: 30], orderBy: ["id"]))
        assertNull(out.error, "${out.error}")
        assertEquals(["1", "3"], out.rows*.id)
    }

    @Test
    void aRunResultReadsTheNewestDifferences() {
        RunResultQuerySource.resultFileOf = { def c, String savedRunId -> savedRunId == "QS_RUN" ? "result-1.json" : null }
        RunResultQuerySource.differencesPage = { def c, String fileName, int pageIndex, int pageSize ->
            pageIndex == 0 ? [pageCount: 1, differences: [[bucket: "MISSING", record: [key: "SO-1", data: [sku: "A", qty: 2]]],
                                                          [bucket: "MISSING", record: [key: "SO-2", data: [sku: "B", qty: 5]]]]] : [differences: []]
        }
        Map out = read("read#RunResultSource", [name: "diffs", source: "runResult", savedRunId: "QS_RUN", fields: ["key", "sku", "qty", "bucket"],
                where: [field: "qty", op: "greater", value: 3]])
        assertNull(out.error, "${out.error}")
        assertEquals([[key: "SO-2", sku: "B", qty: 5, bucket: "MISSING"]], out.rows)
        Map none = read("read#RunResultSource", [name: "diffs", source: "runResult", savedRunId: "NO_RUN", fields: ["key"]])
        assertEquals("READ_ERROR", none.error?.kind)
    }

    private static Map findings(Map extra = [:]) { [name: "findings", source: "auditFindings", auditId: "QS_AUD_K", fields: ["recordKey", "check", "conclusion"]] + extra }

    @Test
    void openFindingsOfAnAuditRead() {
        Map out = read("read#AuditFindingSource", findings(orderBy: ["recordKey"]))
        assertNull(out.error, "${out.error}")
        assertEquals([[recordKey: "SO-1", check: "Shipped on time", conclusion: "Late shipment"],
                      [recordKey: "SO-2", check: "Shipped on time", conclusion: "Late shipment"]], out.rows)
        assertEquals(["SO-1", "SO-2", "SO-3"], read("read#AuditFindingSource", findings(includeClosed: true, orderBy: ["recordKey"])).rows*.recordKey)
    }

    @Test
    void aFieldOutsideTheListIsRefused() {
        Map out = read("read#AuditFindingSource", findings(fields: ["recordKey", "diagnosisText"]))
        assertEquals("READ_ERROR", out.error?.kind)
        assertTrue((out.error.message as String).contains("diagnosisText"), "${out.error}")
    }

    @Test
    void anotherTenantsAuditIsRefused() {
        assertEquals("READ_NOT_ALLOWED", read("read#AuditFindingSource", findings(auditId: "QS_AUD_G")).error?.kind)
    }

    @Test
    void windowAppliesToFirstFound() {
        Map out = read("read#AuditFindingSource", findings(window: [lastDays: 30], orderBy: ["recordKey"]))
        assertNull(out.error, "${out.error}")
        assertEquals(["SO-1"], out.rows*.recordKey)
    }
}
