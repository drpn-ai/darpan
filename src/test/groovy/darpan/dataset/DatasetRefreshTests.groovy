package darpan.dataset

import darpan.reconciliation.support.SharedMoqui
import darpan.facade.common.DataManagerSupport
import darpan.facade.common.TenantAccessSupport
import darpan.reconciliation.automation.SourceSystemConnectorSupport
import darpan.reconciliation.support.ReconciliationSmokeTestSupport
import groovy.json.JsonOutput
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext

import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNotEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * DAR-BE-318 plan 3a, Task 6. refresh#Dataset reads what changed since the copy's readThru minus the margin (daily) or the
 * whole horizon (weekly, first, forced), merges it into the kept copy under a lock, and logs a DatasetRead per read.
 * scan#DueDatasets starts the due refreshes, each under its dataset's tenant. The extractor is a stub passed per call:
 * it writes a {metadata, records} file where the read asked, as a live extract does. Authz is ON with the facade grant
 * for refreshes; the scan runs as the job does, anonymous.
 */
@SharedMoqui
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatasetRefreshTests {

    private static final String A = "KREWE"
    private static final String B = "GORJANA"
    private static final String A_ZONE = "America/Los_Angeles"
    private static final String B_ZONE = "Asia/Kolkata"
    private static final String PREF_KEY = TenantAccessSupport.ACTIVE_TENANT_PREFERENCE_KEY
    private static final String EDITOR_ID = "TEST_CUSTOMER_USER"
    private static final String EDITOR_USERNAME = "test.customer"
    private static final Timestamp FROM_DATE = Timestamp.valueOf("2026-04-21 00:00:00")
    private static final String FACADE_GRANT = "facade.ReconciliationFacadeServices.list#Reconciliations"
    private static final String SERVICES = "dataset.DatasetServices"
    private static final String ISO_EXTRACT = "reconciliation.KeptCopyTestExtractionServices.extract#MadeUpRecords"
    private static final String ROUNDED_EXTRACT = "reconciliation.KeptCopyTestExtractionServices.extract#MadeUpRoundedRecords"
    private static final long DAY = 86_400_000L
    private static final long HOUR = 3_600_000L
    /**
     * The instant the test records' dates count back from, taken once for the class. Taken per record, a second boundary
     * between two builds of "the same" order gave it a different createdAt and stamp, and the second read counted it changed
     * or added.
     */
    private static final Instant RECORD_NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS)

    private ExecutionContext ec

    @BeforeAll
    void setup() {
        ec = ReconciliationSmokeTestSupport.initMoqui(ReconciliationSmokeTestSupport.resolveBackendRoot(), "dataset-refresh")
        // the test data manager outlives the test DB, whose fresh sequence hands out the same dataset ids again
        new File(DataManagerSupport.resolveDirectoryFile(ec, DataManagerSupport.resolveDataManagerLocation(ec), true), "datasets").deleteDir()
        ec.entity.makeDataLoader().location("component://darpan/data/SecuritySeedData.xml").dummyFks(true).load()
        ReconciliationSmokeTestSupport.loadSeedData(ec, "classpath://data/EntityTypeData.xml", "component://darpan/data/AuditSeedData.xml",
                "component://darpan/data/DatasetSeedData.xml")
        ReconciliationSmokeTestSupport.seedCompanyScope(ec)
        insert("moqui.security.UserGroup", [userGroupId: B, description: "Gorjana",
                                            groupTypeEnumId: TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID])
        insert("moqui.security.UserGroupMember", [userGroupId: B, userId: EDITOR_ID, fromDate: FROM_DATE])
        insert(TenantAccessSupport.TENANT_USER_PERMISSION_GROUP_MEMBER_ENTITY_NAME, [tenantUserGroupId: B, userId: EDITOR_ID,
                permissionUserGroupId: TenantAccessSupport.DARPAN_COMPANY_EDITOR_GROUP_ID, fromDate: FROM_DATE])
        insert("moqui.security.UserGroupMember", [userGroupId: "DARPAN_USER", userId: EDITOR_ID, fromDate: FROM_DATE])
        insert(TenantAccessSupport.TENANT_SETTING_ENTITY_NAME, [companyUserGroupId: A, timeZone: A_ZONE])
        insert(TenantAccessSupport.TENANT_SETTING_ENTITY_NAME, [companyUserGroupId: B, timeZone: B_ZONE])
        // A made-up connector row: the read goes through the registry, as the live paths do.
        ec.entity.makeDataLoader().xmlText("""<entity-facade-xml type="seed">
            <darpan.reconciliation.SourceSystemConnector systemEnumId="KC_ISO" extractServiceName="${ISO_EXTRACT}"
                dateFromParameterName="windowStart" dateToParameterName="windowEnd" expectedSourceConfigType="KC_ISO_TYPE"
                configParameterName="projConfigId"
                preserveWindowInstants="Y" enabled="Y"/>
        </entity-facade-xml>""").dummyFks(true).load()
    }

    @AfterAll
    void cleanup() {
        ReconciliationSmokeTestSupport.cleanupMoqui(ec)
    }

    @BeforeEach
    void actAsTenantA() {
        actAs(EDITOR_USERNAME, A)
    }

    @Test
    void theFirstRefreshIsAFullRead() {
        String id = dataset("first")
        List<Map> calls = []
        Map res = refresh(id, stub(calls, [order(1, 5, 5), order(2, 4, 3)]))
        assertEquals([], res.errors)
        assertEquals("DRK_FULL", res.out.readKindEnumId)
        assertEquals("000001", res.out.readSeqId)
        assertEquals(0, res.out.missCount as int)

        assertEquals(1, calls.size())
        Map p = calls[0].params
        assertEquals(ISO_EXTRACT, calls[0].service)
        assertEquals("conn-first", p.projConfigId)
        assertEquals("createdAt", p.windowFieldName)
        assertEquals(A, p.companyUserGroupId)
        assertFalse(p.containsKey("sourceFilters"), "a dataset read takes no filters")
        // the read lands in the dataset's own folder, named in the data-manager location form
        String root = DataManagerSupport.resolveDataManagerLocation(ec)
        assertTrue(root.contains("test-data-manager"), "tests never write the dev datamanager: ${root}")
        assertEquals("${root}/datasets/${A}/${id}/reads/000001".toString(), p.outputLocation)

        def row = find("darpan.dataset.Dataset", [datasetId: id])
        Timestamp readThru = row.readThru as Timestamp
        assertNotNull(readThru)
        assertEquals(readThru.toInstant().minus(62, ChronoUnit.DAYS).toString(), p.windowStart)
        assertEquals(readThru.toInstant().toString(), p.windowEnd)
        assertNotNull(row.currentVersion)
        assertEquals(KeptCopyStore.manifest(dir(id)).version, row.currentVersion)
        assertEquals(2, row.recordCount as int)
        assertNotNull(row.lastFullReadAt)
        assertNull(row.refreshingSince)
        assertEquals(0, row.lastMissCount as int)
        assertNull(row.warningText)
        ZoneId zone = ZoneId.of(A_ZONE)
        Instant expectedNext = readThru.toInstant().atZone(zone).toLocalDate().plusDays(1).atTime(4, 0).atZone(zone).toInstant()
        assertEquals(expectedNext, (row.nextReadDate as Timestamp).toInstant())

        def read = find("darpan.dataset.DatasetRead", [datasetId: id, readSeqId: "000001"])
        assertEquals("DRS_DONE", read.statusId)
        assertEquals("DRK_FULL", read.readKindEnumId)
        assertEquals(A, read.companyUserGroupId)
        assertEquals(2, read.recordsRead as int)
        assertEquals(2, read.addedCount as int)
        assertEquals(readThru, read.readThru)
    }

    // DAR-OPS-029 S16a: an error a service leaves on the facade during a read fails the read, and its text (a table, an
    // address) goes only to the read's own error, never back out as a message in the caller's reply.
    @Test
    void aFailedReadsServiceErrorNeverRidesOutAsAMessage() {
        String id = dataset("leak")
        Closure leaky = { String svc, Map p ->
            ec.message.addError("SECRET_TABLE refused the read for ops@example.com")
            return [:]
        }
        Map res = refresh(id, leaky)
        assertFalse(res.errors.isEmpty(), "the read fails")
        assertFalse(res.messages.any { it.contains("SECRET_TABLE") || it.contains("ops@example.com") }, res.messages.toString())
    }

    @Test
    void aNextDayRefreshIsIncrementalFromReadThruMinusTwoDays() {
        String id = dataset("incremental")
        assertEquals([], refresh(id, stub([], [order(1, 5, 5), order(2, 4, 4)])).errors)
        Timestamp firstReadThru = find("darpan.dataset.Dataset", [datasetId: id]).readThru as Timestamp

        List<Map> calls = []
        Map res = refresh(id, stub(calls, [order(2, 4, 0, "#2 changed"), order(3, 0, 0)]))
        assertEquals([], res.errors)
        assertEquals("DRK_INCREMENTAL", res.out.readKindEnumId)
        assertEquals("000002", res.out.readSeqId)
        Map p = calls[0].params
        assertEquals("updatedAt", p.windowFieldName)
        assertEquals(firstReadThru.toInstant().minus(2, ChronoUnit.DAYS).toString(), p.windowStart)

        def row = find("darpan.dataset.Dataset", [datasetId: id])
        assertEquals(3, row.recordCount as int)
        assertTrue((row.readThru as Timestamp).after(firstReadThru) || row.readThru == firstReadThru)
        Map byId = KeptCopyStore.records(dir(id)).collectEntries { [(it.id): it.record] }
        assertEquals("#2 changed", byId["2"].name)
        def read = find("darpan.dataset.DatasetRead", [datasetId: id, readSeqId: "000002"])
        assertEquals("DRK_INCREMENTAL", read.readKindEnumId)
        assertEquals(1, read.changedCount as int)
        assertEquals(1, read.addedCount as int)
        assertEquals(firstReadThru.toInstant().minus(2, ChronoUnit.DAYS), (read.fromStamp as Timestamp).toInstant())
    }

    @Test
    void aWeekOldFullReadMakesTheNextOneFull() {
        String id = dataset("weekly")
        assertEquals([], refresh(id, stub([], [order(1, 5, 5)])).errors)
        assertEquals("DRK_INCREMENTAL", refresh(id, stub([], [order(1, 5, 1)])).out.readKindEnumId)

        update("darpan.dataset.Dataset", [datasetId: id, lastFullReadAt: new Timestamp(System.currentTimeMillis() - 8 * DAY)])
        List<Map> calls = []
        Map res = refresh(id, stub(calls, [order(1, 5, 1), order(4, 2, 2)]))
        assertEquals([], res.errors)
        assertEquals("DRK_FULL", res.out.readKindEnumId)
        assertEquals("createdAt", calls[0].params.windowFieldName)
        def row = find("darpan.dataset.Dataset", [datasetId: id])
        assertTrue((row.lastFullReadAt as Timestamp).time > System.currentTimeMillis() - DAY, "a full read moves lastFullReadAt")

        // six days old is still inside the week; forceFull reads in full anyway
        update("darpan.dataset.Dataset", [datasetId: id, lastFullReadAt: new Timestamp(System.currentTimeMillis() - 6 * DAY)])
        assertEquals("DRK_INCREMENTAL", refresh(id, stub([], [order(4, 2, 1)])).out.readKindEnumId)
        assertEquals("DRK_FULL", refresh(id, stub([], [order(1, 5, 1), order(4, 2, 1)]), true).out.readKindEnumId)
    }

    @Test
    void readAtIsTheFileWriteTime() {
        String id = dataset("read-at")
        long written = Instant.parse("2026-11-19T05:06:07Z").toEpochMilli()
        assertEquals([], refresh(id, stub([], [order(1, 5, 5)], written)).errors)
        def row = find("darpan.dataset.Dataset", [datasetId: id])
        def read = find("darpan.dataset.DatasetRead", [datasetId: id, readSeqId: "000001"])
        assertEquals(written, (read.readAt as Timestamp).time)
        assertEquals(written, (row.lastReadAt as Timestamp).time)
        assertEquals(written, (KeptCopyStore.manifest(dir(id)).readAt as Timestamp).time)
        // readThru is the moment the read started, not the file time (ruling 3a-7)
        assertNotEquals(written, (row.readThru as Timestamp).time)
        assertEquals(row.readThru, read.readThru)
    }

    @Test
    void aMissSetsTheWarningAndAZeroClearsIt() {
        String id = dataset("miss")
        assertEquals([], refresh(id, stub([], [order(1, 5, 5), order(2, 4, 4)])).errors)
        // the full read finds order 1 changed without moving its stamp: a miss
        Map full = refresh(id, stub([], [order(1, 5, 5, "#1 silently changed"), order(2, 4, 4)]), true)
        assertEquals([], full.errors)
        assertEquals("DRK_FULL", full.out.readKindEnumId)
        assertEquals(1, full.out.missCount as int)
        def row = find("darpan.dataset.Dataset", [datasetId: id])
        assertEquals(1, row.lastMissCount as int)
        assertNotNull(row.warningText)
        assertTrue(row.warningText.contains("1"), row.warningText)
        assertEquals(1, find("darpan.dataset.DatasetRead", [datasetId: id, readSeqId: "000002"]).missCount as int)

        Map next = refresh(id, stub([], [order(2, 4, 0)]))
        assertEquals([], next.errors)
        assertEquals(0, next.out.missCount as int)
        row = find("darpan.dataset.Dataset", [datasetId: id])
        assertEquals(0, row.lastMissCount as int)
        assertNull(row.warningText)
    }

    @Test
    void aRollingDatasetReadsInFullEveryDay() {
        String id = rolling("roll-daily")
        assertEquals("DRR_ROLLING", find("darpan.dataset.Dataset", [datasetId: id]).readRuleEnumId)
        List<Map> calls = []
        Map first = refresh(id, stub(calls, [order(1, 5, 5), order(2, 4, 4)]))
        assertEquals([], first.errors)
        assertEquals("DRK_FULL", first.out.readKindEnumId)
        // the next day's read is full again: the whole window by the window field, 60 days back
        calls.clear()
        Map second = refresh(id, stub(calls, [order(2, 4, 4), order(3, 1, 1)]))
        assertEquals([], second.errors)
        assertEquals("DRK_FULL", second.out.readKindEnumId)
        assertEquals("createdAt", calls[0].params.windowFieldName)
        Timestamp readThru = find("darpan.dataset.Dataset", [datasetId: id]).readThru as Timestamp
        assertEquals(readThru.toInstant().minus(60, ChronoUnit.DAYS).toString(), calls[0].params.windowStart)
        // a full read that lacks order 1 drops it from the copy
        assertEquals(["2", "3"], KeptCopyStore.records(dir(id)).collect { it.id }.sort())
        assertEquals("DRK_FULL", find("darpan.dataset.DatasetRead", [datasetId: id, readSeqId: "000002"]).readKindEnumId)
        // nextReadDate is still the next day at the read hour
        ZoneId zone = ZoneId.of(A_ZONE)
        Instant expectedNext = readThru.toInstant().atZone(zone).toLocalDate().plusDays(1).atTime(4, 0).atZone(zone).toInstant()
        assertEquals(expectedNext, (find("darpan.dataset.Dataset", [datasetId: id]).nextReadDate as Timestamp).toInstant())
    }

    /** Review Focus 1: a rolling dataset changes every day, so a changed record is not a miss and no warning is set. */
    @Test
    void aRollingReadCountsNoMissesAndSetsNoWarning() {
        String id = rolling("roll-miss")
        assertEquals([], refresh(id, stub([], [order(1, 5, 5), order(2, 4, 4)])).errors)
        // order 1 changes under an unchanged stamp, and order 9 appears from before the old readThru: both are misses on a changed-since dataset
        Map res = refresh(id, stub([], [order(1, 5, 5, "#1 changed"), order(2, 4, 4), order(9, 30, 30)]))
        assertEquals([], res.errors)
        assertNull(res.out.missCount)
        def row = find("darpan.dataset.Dataset", [datasetId: id])
        assertNull(row.lastMissCount)
        assertNull(row.warningText)
        def read = find("darpan.dataset.DatasetRead", [datasetId: id, readSeqId: "000002"])
        assertNull(read.missCount)
        assertEquals(1, read.addedCount as int)
        assertEquals(1, read.changedCount as int)
        assertEquals(0, read.removedCount as int)
        assertEquals(0L, read.agedOutCount as Long, "OC220: a full read writes its aged-out count, here none")
    }

    @Test
    void aRollingDatasetNeedsNoStampField() {
        Map res = call("store#Dataset", [systemEnumId: "KC_ISO", sourceConfigId: "conn-roll-nostamp", options: [:],
                idField: "id", windowField: "createdAt", readRuleEnumId: "DRR_ROLLING"])
        assertEquals([], res.errors)
        def row = find("darpan.dataset.Dataset", [datasetId: res.out.datasetId])
        assertEquals("createdAt", row.stampField)
        assertEquals([], refresh(res.out.datasetId as String, stub([], [order(1, 5, 5)])).errors)
        // a changed-since dataset still requires one
        Map bad = call("store#Dataset", [systemEnumId: "KC_ISO", sourceConfigId: "conn-cs-nostamp", options: [:],
                idField: "id", windowField: "createdAt"])
        assertFalse(bad.errors.isEmpty())
    }

    @Test
    void aRollingDatasetDefaultsToSixtyDays() {
        assertEquals(60, find("darpan.dataset.Dataset", [datasetId: rolling("roll-default")]).horizonDays as int)
        assertEquals(30, find("darpan.dataset.Dataset", [datasetId: rolling("roll-30", [horizonDays: 30])]).horizonDays as int)
        assertEquals(62, find("darpan.dataset.Dataset", [datasetId: dataset("cs-default")]).horizonDays as int)
    }

    // DAR-OPS-029 S16a, OC129 (moqui-qa-technician): a composite id's values are escaped before they are joined, so two
    // different records never share a copy id across reads, and an id with no '|' or '\\' keeps its exact key.
    @Test
    void compositeIdsWithTheSeparatorInAValueNeverCollideAcrossReads() {
        assertEquals("1|2|3", DatasetRefreshSupport.copyId([a: 1, b: "2", c: 3], ["a", "b", "c"]), "plain ids keep their key")
        assertNotEquals(DatasetRefreshSupport.copyId([orderId: "A|1", orderItemSeqId: "2"], ["orderId", "orderItemSeqId"]),
                DatasetRefreshSupport.copyId([orderId: "A", orderItemSeqId: "1|2"], ["orderId", "orderItemSeqId"]))
        // joined plainly both read "A\\||B": the escape must cover the backslash too
        assertNotEquals(DatasetRefreshSupport.copyId([orderId: "A\\", orderItemSeqId: "|B"], ["orderId", "orderItemSeqId"]),
                DatasetRefreshSupport.copyId([orderId: "A\\|", orderItemSeqId: "B"], ["orderId", "orderItemSeqId"]))
        String id = dataset("composite-pipe", [idField: "orderId,orderItemSeqId"])
        Map first = unit(1, 2, 1, 5, 5) + [orderId: "A|1", orderItemSeqId: "2"]
        assertEquals([], refresh(id, stub([], [first])).errors)
        // the next day's incremental read brings a different record whose plain join would equal the first one's
        Map second = unit(1, 2, 1, 4, 0, "other") + [orderId: "A", orderItemSeqId: "1|2"]
        Map res = refresh(id, stub([], [second]))
        assertEquals([], res.errors)
        assertEquals("DRK_INCREMENTAL", res.out.readKindEnumId)
        List<Map> kept = KeptCopyStore.records(dir(id)).collect { it.record as Map }
        assertEquals([["A", "1|2"], ["A|1", "2"]], kept.collect { [it.orderId, it.orderItemSeqId] }.sort { it.join('/') },
                "both records stay in the copy")
    }

    // DAR-OPS-029 S16a, extends OC92 (S42c): a dataset dropped while its refresh read (a privacy erase) leaves nothing of
    // that read on disk: the refresh finds its row gone at the swap and deletes the dataset's folder, raw read file included.
    @Test
    void aRefreshWhoseDatasetWasDroppedMidReadLeavesNothingOnDisk() {
        String id = dataset("dropped-mid-read")
        File folder = dir(id)
        Closure dropping = { String svc, Map p ->
            Map out = stub([], [order(1, 5, 5)]).call(svc, p)
            // what drop#ConnectionKeptData does to the rows, while this read is in flight
            ec.entity.find("darpan.dataset.DatasetRead").condition("datasetId", id).disableAuthz().deleteAll()
            ec.entity.find("darpan.dataset.Dataset").condition("datasetId", id).disableAuthz().deleteAll()
            return out
        }
        Map res = refresh(id, dropping)
        assertFalse(res.errors.isEmpty(), "the read fails: its dataset is gone")
        assertFalse(folder.exists(), "nothing of the dropped dataset stays on disk: ${folder.list()}")
    }

    /** Review Focus 2: two units of one order share orderId and line, and stay two copy records. */
    @Test
    void twoUnitsOfOneOrderStayTwoCopyRecords() {
        String id = dataset("composite", [idField: "orderId,orderItemSeqId,unitSeq"])
        assertEquals([], refresh(id, stub([], [unit(1, 1, 1, 5, 5), unit(1, 1, 2, 5, 5), unit(2, 1, 1, 4, 4)])).errors)
        Map byId = KeptCopyStore.records(dir(id)).collectEntries { [(it.id): it.record] }
        assertEquals(["1|1|1", "1|1|2", "2|1|1"], byId.keySet().sort())
        assertEquals("1|1|2", byId["1|1|2"]._copyId)
        assertEquals(3, find("darpan.dataset.Dataset", [datasetId: id]).recordCount as int)
        // the manifest names the dataset's own idField list, so serving matches it
        assertEquals("orderId,orderItemSeqId,unitSeq", KeptCopyStore.manifest(dir(id)).idField)
        assertTrue(DatasetRefreshSupport.sameSettings(find("darpan.dataset.Dataset", [datasetId: id]).getMap(),
                KeptCopyStore.manifest(dir(id))))
        // a merge changes one unit and leaves its sibling alone
        Map res = refresh(id, stub([], [unit(1, 1, 2, 5, 0, "changed")]))
        assertEquals([], res.errors)
        assertEquals("DRK_INCREMENTAL", res.out.readKindEnumId)
        byId = KeptCopyStore.records(dir(id)).collectEntries { [(it.id): it.record] }
        assertEquals(3, byId.size())
        assertEquals("changed", byId["1|1|2"].name)
        assertEquals("unit", byId["1|1|1"].name)
    }

    @Test
    void reStoringARollingDatasetWithoutTheRuleKeepsItRolling() {
        String id = rolling("roll-restore")
        assertEquals([], refresh(id, stub([], [order(1, 5, 5)])).errors)
        def before = find("darpan.dataset.Dataset", [datasetId: id])
        assertNotNull(before.lastFullReadAt)
        // a re-store that names neither the rule nor the stamp field
        Map again = call("store#Dataset", [systemEnumId: "KC_ISO", sourceConfigId: "conn-roll-restore", options: [:],
                idField: "id", windowField: "createdAt", marginDays: 3])
        assertEquals([], again.errors)
        assertEquals(id, again.out.datasetId)
        def row = find("darpan.dataset.Dataset", [datasetId: id])
        assertEquals("DRR_ROLLING", row.readRuleEnumId)
        assertEquals(60, row.horizonDays as int)
        assertEquals("createdAt", row.stampField)
        assertEquals(3, row.marginDays as int)
        assertEquals(before.lastFullReadAt, row.lastFullReadAt, "nothing that matters changed")
        // and a changed-since dataset re-stored without a rule or stamp keeps both
        String cs = dataset("cs-restore")
        assertEquals([], call("store#Dataset", [systemEnumId: "KC_ISO", sourceConfigId: "conn-cs-restore", options: [:],
                idField: "id", windowField: "createdAt"]).errors)
        def csRow = find("darpan.dataset.Dataset", [datasetId: cs])
        assertEquals("DRR_CHANGED_SINCE", csRow.readRuleEnumId)
        assertEquals("updatedAt", csRow.stampField)
    }

    @Test
    void changingToRollingDefaultsTheHorizonToSixty() {
        String id = dataset("to-rolling")
        assertEquals(62, find("darpan.dataset.Dataset", [datasetId: id]).horizonDays as int)
        assertEquals([], refresh(id, stub([], [order(1, 5, 5)])).errors)
        assertEquals([], call("store#Dataset", [systemEnumId: "KC_ISO", sourceConfigId: "conn-to-rolling", options: [:],
                idField: "id", windowField: "createdAt", readRuleEnumId: "DRR_ROLLING"]).errors)
        def row = find("darpan.dataset.Dataset", [datasetId: id])
        assertEquals("DRR_ROLLING", row.readRuleEnumId)
        assertEquals(60, row.horizonDays as int)
        assertNull(row.lastFullReadAt, "a rule change forces a full read")
        // an explicit horizon wins, and an omitted one on a later update is kept
        assertEquals([], call("store#Dataset", [systemEnumId: "KC_ISO", sourceConfigId: "conn-to-rolling", options: [:],
                idField: "id", windowField: "createdAt", horizonDays: 45]).errors)
        assertEquals([], call("store#Dataset", [systemEnumId: "KC_ISO", sourceConfigId: "conn-to-rolling", options: [:],
                idField: "id", windowField: "createdAt"]).errors)
        assertEquals(45, find("darpan.dataset.Dataset", [datasetId: id]).horizonDays as int)
    }

    @Test
    void theIdFieldIsStoredTrimmedAndNeverEmpty() {
        String id = dataset("id-trim", [idField: " orderId , orderItemSeqId ,"])
        assertEquals("orderId,orderItemSeqId", find("darpan.dataset.Dataset", [datasetId: id]).idField)
        Map empty = call("store#Dataset", [systemEnumId: "KC_ISO", sourceConfigId: "conn-id-empty", options: [:],
                idField: " , ", stampField: "updatedAt", windowField: "createdAt"])
        assertFalse(empty.errors.isEmpty())
        // "id," is the single field id: records key by it, not by a missing _copyId
        String single = dataset("id-comma", [idField: "id,"])
        assertEquals([], refresh(single, stub([], [order(1, 5, 5), order(2, 4, 4)])).errors)
        assertEquals(["1", "2"], KeptCopyStore.records(dir(single)).collect { it.id }.sort())
    }

    @Test
    void aRecordMissingAnIdPartFailsTheReadNamingIt() {
        String id = dataset("missing-part", [idField: "orderId,orderItemSeqId,unitSeq"])
        assertEquals([], refresh(id, stub([], [unit(1, 1, 1, 5, 5)])).errors)
        Map before = KeptCopyStore.manifest(dir(id))
        Map bad = unit(2, 1, 1, 5, 5)
        bad.remove("unitSeq")
        Map blank = unit(3, 1, 1, 5, 5)
        blank.unitSeq = " "
        Map res = refresh(id, stub([], [unit(1, 1, 1, 5, 5), bad, blank]), true)
        assertTrue(res.errors.any { it.contains("2 records lack a value") && it.contains("orderId=2") && it.contains("unitSeq=null") }, res.errors.toString())
        assertEquals(before.version, KeptCopyStore.manifest(dir(id)).version, "the copy is as it was")
        assertEquals("DRS_FAILED", find("darpan.dataset.DatasetRead", [datasetId: id, readSeqId: "000002"]).statusId)
        assertNotNull(find("darpan.dataset.Dataset", [datasetId: id]).nextReadDate)
    }

    @Test
    void twoRecordsWithTheSameCopyIdFailTheRead() {
        String id = dataset("dup-copy-id", [idField: "orderId,orderItemSeqId,unitSeq"])
        Map res = refresh(id, stub([], [unit(1, 1, 1, 5, 5), unit(2, 1, 1, 5, 5), unit(1, 1, 1, 5, 5, "again")]))
        assertTrue(res.errors.any { it.contains("1 records repeat a copy id") && it.contains("1|1|1") }, res.errors.toString())
        assertNull(KeptCopyStore.manifest(dir(id)), "nothing was swapped in")
        assertEquals("DRS_FAILED", find("darpan.dataset.DatasetRead", [datasetId: id, readSeqId: "000001"]).statusId)
    }

    /**
     * Ruling P3b1-15, on a made-up system: within one read, a record repeated with identical content (a page read twice) is
     * one record, under a single-field id and a composite one alike.
     */
    @Test
    void aRepeatedSingleIdWithIdenticalContentIsOneRecord() {
        String id = madeUpDataset("repeat-same", "id")
        List<Map> records = [order(1, 5, 5), order(2, 4, 4), order(1, 5, 5)]
        Map res = refresh(id, stub([], records))
        assertEquals([], res.errors)
        assertEquals(["1", "2"], KeptCopyStore.records(dir(id)).collect { it.id }.sort())
        assertEquals(2, KeptCopyStore.manifest(dir(id)).recordCount)
        assertEquals(2, find("darpan.dataset.DatasetRead", [datasetId: id, readSeqId: "000001"]).recordsRead as int, "the repeat is dropped")
        // the next, changed-since read repeating a record is merged once too
        Map again = refresh(id, stub([], [order(2, 4, 1), order(2, 4, 1)]))
        assertEquals([], again.errors)
        assertEquals(1, find("darpan.dataset.DatasetRead", [datasetId: id, readSeqId: "000002"]).recordsRead as int)

        // a composite id: the identical repeat no longer fails the read (it did under P3b1-3)
        String units = madeUpDataset("repeat-same-unit", "orderId,orderItemSeqId,unitSeq")
        assertEquals([], refresh(units, stub([], [unit(1, 1, 1, 5, 5), unit(1, 1, 2, 5, 5), unit(1, 1, 1, 5, 5)])).errors)
        assertEquals(["1|1|1", "1|1|2"], KeptCopyStore.records(dir(units)).collect { it.id }.sort())
    }

    /**
     * Ruling P3b1-15, on a made-up system: within one read, two records with one id and different content are not one record.
     * A single-field id used to keep one of them silently (the other lost); now the read fails, naming the id and the count,
     * and the copy stays as it was.
     */
    @Test
    void aRepeatedSingleIdWithDifferentContentFailsTheRead() {
        String id = madeUpDataset("repeat-differs", "id")
        assertEquals([], refresh(id, stub([], [order(5, 5, 5)])).errors)
        Map before = KeptCopyStore.manifest(dir(id))
        Map res = refresh(id, stub([], [order(1, 5, 5), order(2, 4, 4), order(1, 5, 5, "#1 again"), order(2, 4, 4, "#2 again")]), true)
        assertTrue(res.errors.any { it.contains("2 records repeat a copy id of id with different content") && it.contains("is 1.") },
                res.errors.toString())
        assertEquals(before.version, KeptCopyStore.manifest(dir(id)).version, "the copy is as it was")
        assertEquals("DRS_FAILED", find("darpan.dataset.DatasetRead", [datasetId: id, readSeqId: "000002"]).statusId)
    }

    /**
     * Final review I1, on a made-up system: a single-field id is guarded like a composite one (P3b1-3). Records without the
     * id used to key as "null" and collapse into one served record; now the read fails naming the count and the first
     * offender, on a full read and a changed-since one alike, and the copy stays as it was. An idField the records do not
     * carry (misnamed) fails the first read, and nothing is swapped in.
     */
    @Test
    void aSingleFieldRecordWithoutItsIdFailsTheRead() {
        String id = madeUpDataset("single-no-id", "id")
        assertEquals([], refresh(id, stub([], [order(1, 5, 5), order(2, 4, 4)])).errors)
        Map before = KeptCopyStore.manifest(dir(id))
        Map missing = order(3, 3, 3)
        missing.remove("id")
        Map blank = order(4, 3, 3)
        blank.id = " "
        Map full = refresh(id, stub([], [order(1, 5, 5), missing, blank, order(2, 4, 4)]), true)
        assertTrue(full.errors.any { it.contains("2 records lack a value for the id id") && it.contains("the first has id=null.") },
                full.errors.toString())
        assertEquals(before.version, KeptCopyStore.manifest(dir(id)).version, "the copy is as it was")
        assertEquals("DRS_FAILED", find("darpan.dataset.DatasetRead", [datasetId: id, readSeqId: "000002"]).statusId)
        Map changedSince = refresh(id, stub([], [blank]))
        assertTrue(changedSince.errors.any { it.contains("1 records lack a value for the id id") && it.contains("id= ") },
                changedSince.errors.toString())
        assertEquals(before.version, KeptCopyStore.manifest(dir(id)).version, "a merge is refused too")
        assertEquals(["1", "2"], KeptCopyStore.records(dir(id)).collect { it.id }.sort())

        String misnamed = madeUpDataset("single-misnamed", "orderNumber")
        Map res = refresh(misnamed, stub([], [order(1, 5, 5), order(2, 4, 4)]))
        assertTrue(res.errors.any { it.contains("2 records lack a value for the id orderNumber") }, res.errors.toString())
        assertNull(KeptCopyStore.manifest(dir(misnamed)), "nothing was swapped in")
        assertEquals("DRS_FAILED", find("darpan.dataset.DatasetRead", [datasetId: misnamed, readSeqId: "000001"]).statusId)
    }

    /** A made-up system (no real system's row): its extract is the test-only one; the stub stands in for it. */
    private String madeUpDataset(String conn, String idField) {
        ec.entity.makeDataLoader().xmlText("""<entity-facade-xml type="seed">
            <darpan.reconciliation.SourceSystemConnector systemEnumId="KC_PLAIN"
                extractServiceName="reconciliation.KeptCopyTestExtractionServices.extract#MadeUpRecords"
                dateFromParameterName="windowStart" dateToParameterName="windowEnd" expectedSourceConfigType="KC_PLAIN_TYPE"
                configParameterName="plainConfigId" preserveWindowInstants="Y" enabled="Y"/>
        </entity-facade-xml>""").dummyFks(true).load()
        Map res = call("store#Dataset", [systemEnumId: "KC_PLAIN", sourceConfigId: "conn-${conn}".toString(), options: [:],
                idField: idField, stampField: "updatedAt", windowField: "createdAt"])
        assertEquals([], res.errors)
        return res.out.datasetId
    }

    @Test
    void aSingleFieldIdIsUnchanged() {
        String id = dataset("single-id")
        assertEquals([], refresh(id, stub([], [order(1, 5, 5), order(2, 4, 4)])).errors)
        Map manifest = KeptCopyStore.manifest(dir(id))
        assertEquals("id", manifest.idField)
        assertEquals(["1", "2"], KeptCopyStore.records(dir(id)).collect { it.id }.sort())
        assertFalse(KeptCopyStore.records(dir(id)).any { it.record.containsKey("_copyId") })
        assertEquals("1|2|3", DatasetRefreshSupport.copyId([a: 1, b: "2", c: 3], ["a", "b", "c"]))
        assertEquals("7", DatasetRefreshSupport.copyId([id: 7], ["id"]))
    }

    /**
     * I2: changing idField keeps no copy built under the old one in service. The copy is refused at once (its manifest
     * names the old idField), the next refresh is full, and the rebuilt copy, keyed the new way, serves again.
     */
    @Test
    void changingTheIdFieldForcesAFullReadAndStopsServing() {
        // the test data manager outlives the test DB: no slice of an earlier run may stand in for this one's
        ["id-0", "id-1", "id-2"].each { locationFile(sliceLocation(it)).deleteDir() }
        String id = dataset("id-change")
        List<Map> records = [order(1, 3, 3) + [legacyResourceId: "L1"], order(2, 2, 1) + [legacyResourceId: "L2"]]
        assertEquals([], refresh(id, stub([], records)).errors)
        assertEquals("DRK_INCREMENTAL", refresh(id, stub([], [records[1]])).out.readKindEnumId)
        Timestamp start = new Timestamp(System.currentTimeMillis() - 4 * DAY)
        Closure<Map> sliceFor = { String token ->
            Timestamp end = find("darpan.dataset.Dataset", [datasetId: id]).readThru as Timestamp
            KeptCopySupport.sliceIfServed(ec, KeptCopySupport.side(SourceSystemConnectorSupport.resolve(ec, "KC_ISO"),
                    [projConfigId: "conn-id-change", outputLocation: sliceLocation(token), fileName: "slice.json"],
                    [tenantId: A, windowStart: start, windowEnd: end]))
        }
        Map control = sliceFor("id-0")
        assertTrue(control.served as boolean, control.reason as String)
        assertEquals(["1", "2"], json(control.extractFile as File).records*.id.sort())

        Map changed = call("store#Dataset", [systemEnumId: "KC_ISO", sourceConfigId: "conn-id-change", options: [:],
                idField: "legacyResourceId", stampField: "updatedAt", windowField: "createdAt"])
        assertEquals([], changed.errors)
        assertEquals(id, changed.out.datasetId)
        assertNull(find("darpan.dataset.Dataset", [datasetId: id]).lastFullReadAt, "the next refresh reads in full")
        Map refused = sliceFor("id-1")
        assertFalse(refused.served as boolean)
        assertEquals(KeptCopySupport.OTHER_SETTINGS, refused.reason)
        assertFalse(new File(locationFile(sliceLocation("id-1")), "slice.json").exists(), "nothing written")

        List<Map> calls = []
        Map full = refresh(id, stub(calls, records))
        assertEquals([], full.errors)
        assertEquals("DRK_FULL", full.out.readKindEnumId)
        assertEquals("createdAt", calls[0].params.windowFieldName)
        Map manifest = KeptCopyStore.manifest(dir(id))
        assertEquals("legacyResourceId", manifest.idField)
        assertEquals(["L1", "L2"], KeptCopyStore.records(dir(id)).collect { it.id }.sort(), "keyed the new way, no duplicates")
        Map served = sliceFor("id-2")
        assertTrue(served.served as boolean, served.reason as String)
        assertEquals(["1", "2"], json(served.extractFile as File).records*.id.sort())
    }

    /**
     * I1: an extract service that takes no windowFieldName (a deploy that leaves the extractor behind) would window the
     * daily read on createdAt and keep a stale copy. The refresh fails before reading, says why, and warns at once;
     * store#Dataset refuses to keep an active dataset of that system.
     */
    @Test
    void aRefreshFailsWhenTheExtractorStopsTakingTheWindowField() {
        String id = dataset("skew")
        assertEquals([], refresh(id, stub([], [order(1, 5, 5)])).errors)
        String versionBefore = KeptCopyStore.manifest(dir(id)).version
        def connector = ec.entity.find(SourceSystemConnectorSupport.ENTITY_NAME).condition("systemEnumId", "KC_ISO").disableAuthz().one()
        // the rounded-records extract service declares no windowFieldName
        String lineUnits = ROUNDED_EXTRACT
        assertNotNull(ec.service.getServiceDefinition(lineUnits), "test precondition: the made-up extract service is on the test classpath")
        assertFalse("windowFieldName" in ec.service.getServiceDefinition(lineUnits).getInParameterNames())
        connector.set("extractServiceName", lineUnits)
        connector.update()
        try {
            List<Map> calls = []
            Map res = refresh(id, stub(calls, [order(1, 5, 1)]))
            assertTrue(res.errors.any { it.contains("${lineUnits} takes no windowFieldName") }, res.errors.toString())
            assertEquals(0, calls.size(), "nothing is read")
            def row = find("darpan.dataset.Dataset", [datasetId: id])
            assertNotNull(row.warningText, "the first failure already warns")
            assertTrue(row.warningText.contains("takes no windowFieldName"), row.warningText)
            assertNull(row.refreshingSince)
            def read = find("darpan.dataset.DatasetRead", [datasetId: id, readSeqId: "000002"])
            assertEquals("DRS_FAILED", read.statusId)
            assertTrue(read.errorText.contains("takes no windowFieldName"), read.errorText)
            assertEquals(versionBefore, KeptCopyStore.manifest(dir(id)).version, "the copy is untouched")

            Map store = call("store#Dataset", [systemEnumId: "KC_ISO", sourceConfigId: "conn-skew", options: [:],
                    idField: "id", stampField: "updatedAt", windowField: "createdAt"])
            assertTrue(store.errors.any { it.contains("cannot be kept") && it.contains("takes no windowFieldName") }, store.errors.toString())
            // pausing it still works
            assertEquals([], call("store#Dataset", [systemEnumId: "KC_ISO", sourceConfigId: "conn-skew", options: [:],
                    idField: "id", stampField: "updatedAt", windowField: "createdAt", statusId: "DST_PAUSED"]).errors)
            assertEquals("DST_PAUSED", find("darpan.dataset.Dataset", [datasetId: id]).statusId)
        } finally {
            connector.set("extractServiceName", ISO_EXTRACT)
            connector.update()
        }
    }

    @Test
    void aSecondRefreshWhileOneRunsIsRefused() {
        String id = dataset("lock")
        List<Map> innerCalls = []
        List<String> innerErrors = []
        Closure outer = { String svc, Map p ->
            // a second refresh of the same dataset starts while this one reads (a job overlap, or a manual and a scheduled run)
            ec.message.clearAll()
            ec.service.sync().name("${SERVICES}.refresh#Dataset".toString()).parameters([datasetId: id, extractor: stub(innerCalls, [order(9, 1, 1)])]).call()
            innerErrors.addAll(ec.message.errors.collect { it.toString() })
            ec.message.clearAll()
            return stub([], [order(1, 5, 5)]).call(svc, p)
        }
        Map res = refresh(id, outer)
        assertEquals([], res.errors)
        assertEquals("DRK_FULL", res.out.readKindEnumId)
        assertEquals(0, innerCalls.size(), "the refused refresh never reads")
        assertEquals(1, innerErrors.size(), innerErrors.toString())
        assertTrue(innerErrors[0].contains("Dataset ${id} is already refreshing since"), innerErrors[0])
        assertEquals(1, reads(id).size(), "the refused refresh writes no read row")
        def row = find("darpan.dataset.Dataset", [datasetId: id])
        assertNull(row.refreshingSince)
        assertEquals(1, row.recordCount as int)
        assertEquals(1, KeptCopyStore.records(dir(id)).collect { it }.size())

        // a live lock (an hour old) refuses; a dead one (seven hours old, the holder crashed) is taken over
        Timestamp live = new Timestamp(System.currentTimeMillis() - HOUR)
        update("darpan.dataset.Dataset", [datasetId: id, refreshingSince: live])
        List<Map> calls = []
        Map refused = refresh(id, stub(calls, [order(1, 5, 1)]))
        assertTrue(refused.errors.any { it.contains("Dataset ${id} is already refreshing since") }, refused.errors.toString())
        assertEquals(0, calls.size())
        assertEquals(1, reads(id).size())
        assertEquals(live, find("darpan.dataset.Dataset", [datasetId: id]).refreshingSince, "a refusal leaves the holder's lock alone")

        update("darpan.dataset.Dataset", [datasetId: id, refreshingSince: new Timestamp(System.currentTimeMillis() - 7 * HOUR)])
        Map takenOver = refresh(id, stub(calls, [order(1, 5, 1)]))
        assertEquals([], takenOver.errors)
        assertEquals(1, calls.size())
        assertNull(find("darpan.dataset.Dataset", [datasetId: id]).refreshingSince)
    }

    @Test
    void aFailedExtractLeavesTheCopyAndClearsTheLock() {
        String id = dataset("failure")
        assertEquals([], refresh(id, stub([], [order(1, 5, 5), order(2, 4, 4)])).errors)
        File d = dir(id)
        Map manifestBefore = KeptCopyStore.manifest(d)
        List recordsBefore = KeptCopyStore.records(d).collect { it }
        Map rowBefore = find("darpan.dataset.Dataset", [datasetId: id]).getMap()
        Set<String> foldersBefore = versionFolders(d)

        // 1. the extractor throws
        Map thrown = refresh(id, { String svc, Map p -> throw new IllegalStateException("Source said no") } as Closure)
        assertTrue(thrown.errors.any { it.contains("Source said no") }, thrown.errors.toString())
        assertUntouched(id, manifestBefore, recordsBefore, rowBefore, foldersBefore)
        def failed = find("darpan.dataset.DatasetRead", [datasetId: id, readSeqId: "000002"])
        assertEquals("DRS_FAILED", failed.statusId)
        assertTrue(failed.errorText.contains("Source said no"), failed.errorText)

        // 2. the file breaks off mid-record, so the full read fails while it writes the new version
        Closure truncated = { String svc, Map p ->
            File out = new File(DataManagerSupport.resolveDirectoryFile(ec, p.outputLocation as String, true), p.fileName as String)
            String whole = JsonOutput.toJson([metadata: [kind: "truncated"], records: [order(1, 5, 5), order(2, 4, 4), order(3, 1, 1)]])
            out.setText(whole.substring(0, whole.length() - 40), "UTF-8")
            return [fileLocation: out.absolutePath, recordCount: 3]
        }
        Map broken = refresh(id, truncated, true)
        assertFalse(broken.errors.isEmpty())
        assertUntouched(id, manifestBefore, recordsBefore, rowBefore, foldersBefore)
        assertEquals("DRS_FAILED", find("darpan.dataset.DatasetRead", [datasetId: id, readSeqId: "000003"]).statusId)
        assertTrue(d.listFiles().every { !it.name.startsWith(".tmp-") }, "no temp folder is left behind")

        // 3. the file breaks off cleanly between two records: every record read parses, and the read still fails
        Closure cutBetween = { String svc, Map p ->
            File out = new File(DataManagerSupport.resolveDirectoryFile(ec, p.outputLocation as String, true), p.fileName as String)
            out.setText('{"metadata":{"kind":"cut"},"records":[' + JsonOutput.toJson(order(1, 5, 5, "#1 partial")) + ',', "UTF-8")
            return [fileLocation: out.absolutePath, recordCount: 2]
        }
        Map cut = refresh(id, cutBetween, true)
        assertTrue(cut.errors.any { it.contains("read 000004 failed") }, cut.errors.toString())
        assertUntouched(id, manifestBefore, recordsBefore, rowBefore, foldersBefore)

        // the next good read works from the untouched copy
        Map good = refresh(id, stub([], [order(2, 4, 0, "#2 later")]))
        assertEquals([], good.errors)
        assertEquals("000005", good.out.readSeqId)
        assertEquals(2, find("darpan.dataset.Dataset", [datasetId: id]).recordCount as int)
    }

    @Test
    void aTakenOverRefreshNeitherSwapsNorPrunesNorClearsTheNewLock() {
        String id = dataset("takeover")
        assertEquals([], refresh(id, stub([], [order(1, 5, 5), order(2, 4, 4)])).errors)
        File d = dir(id)

        // 1. A slow refresh started seven hours ago; while it reads, a later refresh finds its lock stale, takes it over and
        //    finishes. The slow one then must not swap, prune or touch the Dataset.
        List<String> laterErrors = []
        Closure slow = { String svc, Map p ->
            ec.user.setEffectiveTime(null)
            ec.message.clearAll()
            ec.service.sync().name("${SERVICES}.refresh#Dataset".toString()).parameters([datasetId: id,
                    extractor: stub([], [order(1, 5, 1, "#1 by the later refresh"), order(2, 4, 4), order(3, 1, 1)])]).call()
            laterErrors.addAll(ec.message.errors.collect { it.toString() })
            ec.message.clearAll()
            return stub([], [order(1, 5, 0, "#1 by the slow refresh")]).call(svc, p)
        }
        Map slowRes = startedHoursAgo(7) { refresh(id, slow) }
        assertEquals([], laterErrors)
        assertTrue(slowRes.errors.any { it.contains("lock taken over") }, slowRes.errors.toString())
        def row = find("darpan.dataset.Dataset", [datasetId: id])
        def later = find("darpan.dataset.DatasetRead", [datasetId: id, readSeqId: "000003"])
        assertEquals("DRS_DONE", later.statusId)
        def slowRead = find("darpan.dataset.DatasetRead", [datasetId: id, readSeqId: "000002"])
        assertEquals("DRS_FAILED", slowRead.statusId)
        assertTrue(slowRead.errorText.contains("lock taken over"), slowRead.errorText)
        assertEquals(3, reads(id).size(), "one row per read: no primary-key collision")
        assertEquals(KeptCopyStore.manifest(d).version, row.currentVersion, "the row names the version that is current")
        assertEquals(later.readThru, row.readThru, "the Dataset holds the later refresh's read, not the slow one's")
        Map names = KeptCopyStore.records(d).collectEntries { [(it.id): it.record.name] }
        assertEquals(["1": "#1 by the later refresh", "2": "#2", "3": "#3"], names)
        assertNull(row.refreshingSince)
        assertEquals(2, versionFolders(d).size(), "current and previous only: the slow refresh's version was deleted")
        assertTrue(d.listFiles().every { !it.name.startsWith(".tmp-") })

        // 2. The same, but the later refresh still holds the lock when the slow one ends: the slow one leaves its lock, and
        //    prunes nothing (a stray version folder and an old read folder survive).
        File orphan = new File(d, "v20200101-000000000")
        orphan.mkdirs()
        File oldRead = new File(new File(d, "reads"), "000001")
        oldRead.mkdirs()
        String versionBefore = KeptCopyStore.manifest(d).version
        Timestamp[] newLock = new Timestamp[1]
        Closure slowAgain = { String svc, Map p ->
            ec.user.setEffectiveTime(null)
            ec.service.sync().name("${SERVICES}.lock#DatasetRefresh".toString())
                    .parameters([datasetId: id, tenantId: A, now: ec.user.nowTimestamp]).call()
            newLock[0] = find("darpan.dataset.Dataset", [datasetId: id]).refreshingSince as Timestamp
            return stub([], [order(1, 5, 0, "#1 by the slow refresh")]).call(svc, p)
        }
        Map again = startedHoursAgo(7) { refresh(id, slowAgain) }
        assertTrue(again.errors.any { it.contains("lock taken over") }, again.errors.toString())
        assertNotNull(newLock[0])
        assertTrue(newLock[0].time > System.currentTimeMillis() - HOUR, "the new holder's lock is recent")
        assertEquals(newLock[0], find("darpan.dataset.Dataset", [datasetId: id]).refreshingSince, "the new lock is left to its holder")
        assertEquals(versionBefore, KeptCopyStore.manifest(d).version)
        assertTrue(orphan.isDirectory(), "the slow refresh pruned nothing")
        assertTrue(oldRead.isDirectory(), "the slow refresh pruned no read folder")
        assertEquals("DRS_FAILED", find("darpan.dataset.DatasetRead", [datasetId: id, readSeqId: "000004"]).statusId)
        assertEquals("DRS_RUNNING", find("darpan.dataset.DatasetRead", [datasetId: id, readSeqId: "000005"]).statusId)
    }

    @Test
    void aFailedReadBacksOff() {
        String id = dataset("backoff")
        assertFailsWithNextReadIn(id, 30)
        assertFailsWithNextReadIn(id, 60)
        def row = find("darpan.dataset.Dataset", [datasetId: id])
        assertNull(row.warningText, "backing off is not yet a warning")
        assertNull(row.refreshingSince)
    }

    @Test
    void afterMaxRetriesItWaitsForTomorrowWithAWarning() {
        String id = dataset("give-up")
        assertFailsWithNextReadIn(id, 30)
        assertFailsWithNextReadIn(id, 60)
        assertFailsTillTomorrow(id)
        def row = find("darpan.dataset.Dataset", [datasetId: id])
        assertTrue(row.warningText.contains("3 times") && row.warningText.contains("token revoked"), row.warningText)
        // a fourth failure, tomorrow, waits another day rather than starting the back-off again
        assertFailsTillTomorrow(id)
        assertTrue(find("darpan.dataset.Dataset", [datasetId: id]).warningText.contains("4 times"))
    }

    @Test
    void aSuccessResetsTheBackoff() {
        String id = dataset("reset")
        assertFailsWithNextReadIn(id, 30)
        assertFailsWithNextReadIn(id, 60)
        assertEquals([], refresh(id, stub([], [order(1, 5, 5)])).errors)
        def row = find("darpan.dataset.Dataset", [datasetId: id])
        ZoneId zone = ZoneId.of(A_ZONE)
        assertEquals(readHourNextDay(row.readThru as Timestamp, zone), (row.nextReadDate as Timestamp).toInstant())
        assertNull(row.warningText)
        assertFailsWithNextReadIn(id, 30)
    }

    @Test
    void tenantBCannotRefreshTenantAsDataset() {
        String idA = dataset("cross-tenant")
        assertEquals([], refresh(idA, stub([], [order(1, 5, 5)])).errors)
        Timestamp live = new Timestamp(System.currentTimeMillis() - HOUR)
        update("darpan.dataset.Dataset", [datasetId: idA, refreshingSince: live])
        Map rowBefore = find("darpan.dataset.Dataset", [datasetId: idA]).getMap()
        Map manifestBefore = KeptCopyStore.manifest(dir(idA))
        int readsBefore = reads(idA).size()

        actAs(EDITOR_USERNAME, B)
        List<Map> calls = []
        Map res = refresh(idA, stub(calls, [order(9, 1, 1)]), true)
        assertTrue(res.errors.any { it.contains("Dataset ${idA} does not exist") }, res.errors.toString())
        assertEquals(0, calls.size(), "the extractor is never called")
        assertEquals(readsBefore, reads(idA).size(), "no read row is written")
        assertEquals(rowBefore, find("darpan.dataset.Dataset", [datasetId: idA]).getMap(), "A's row and lock are untouched")
        assertEquals(manifestBefore, KeptCopyStore.manifest(dir(idA)))
        update("darpan.dataset.Dataset", [datasetId: idA, refreshingSince: null])
    }

    @Test
    void scanRunsOnlyDueActiveDatasetsEachUnderItsTenant() {
        Timestamp past = new Timestamp(System.currentTimeMillis() - HOUR)
        Timestamp future = new Timestamp(System.currentTimeMillis() + DAY)
        String aDue = dataset("scan-a-due")
        String aPaused = dataset("scan-a-paused", [statusId: "DST_PAUSED"])
        String aLater = dataset("scan-a-later")
        update("darpan.dataset.Dataset", [datasetId: aDue, nextReadDate: past])
        update("darpan.dataset.Dataset", [datasetId: aPaused, nextReadDate: past])
        update("darpan.dataset.Dataset", [datasetId: aLater, nextReadDate: future])
        actAs(EDITOR_USERNAME, B)
        String bNew = dataset("scan-b-new")      // never read: no nextReadDate, so due
        String bLocked = dataset("scan-b-locked")
        update("darpan.dataset.Dataset", [datasetId: bLocked, nextReadDate: past, refreshingSince: past])
        Set<String> mine = [aDue, aPaused, aLater, bNew, bLocked] as Set
        // other tests' datasets stay out of this scan
        ec.entity.find("darpan.dataset.Dataset").disableAuthz().useCache(false).list().each {
            if (!mine.contains(it.datasetId)) update("darpan.dataset.Dataset", [datasetId: it.datasetId, statusId: "DST_PAUSED"])
        }

        List<Map> calls = []
        Map res = asJob { ec.service.sync().name("${SERVICES}.scan#DueDatasets".toString()).parameters([extractor: stub(calls, [order(1, 3, 3)])]).call() }
        assertEquals([], res.errors)
        assertEquals(2, res.out.started as int)
        assertEquals(2, calls.size(), calls*.params*.outputLocation.toString())
        Map<String, Map> byDataset = calls.collectEntries { Map c ->
            [(c.params.outputLocation.toString().tokenize("/")[-3]): c]
        }
        assertEquals([aDue, bNew] as Set, byDataset.keySet())
        assertEquals(A, byDataset[aDue].tenant, "each refresh runs as its dataset's tenant")
        assertEquals(A, byDataset[aDue].params.companyUserGroupId)
        assertTrue(byDataset[aDue].params.outputLocation.contains("/datasets/${A}/${aDue}/"))
        assertEquals(B, byDataset[bNew].tenant)
        assertEquals(B, byDataset[bNew].params.companyUserGroupId)
        assertTrue(byDataset[bNew].params.outputLocation.contains("/datasets/${B}/${bNew}/"))

        assertEquals(1, reads(aDue).size())
        assertEquals(1, reads(bNew).size())
        [aPaused, aLater, bLocked].each { assertEquals(0, reads(it).size(), "${it} was not due") }
        assertTrue((find("darpan.dataset.Dataset", [datasetId: aDue]).nextReadDate as Timestamp).after(new Timestamp(System.currentTimeMillis())))
        assertEquals(past, find("darpan.dataset.Dataset", [datasetId: bLocked]).refreshingSince, "a live lock is left to its holder")
        // B's next read is at 04:00 in B's own zone
        ZoneId zone = ZoneId.of(B_ZONE)
        def bRow = find("darpan.dataset.Dataset", [datasetId: bNew])
        assertEquals(4, (bRow.nextReadDate as Timestamp).toInstant().atZone(zone).hour)
    }

    @Test
    void theScanJobIsSeededPaused() {
        ReconciliationSmokeTestSupport.loadSeedData(ec, "component://darpan/data/ReconciliationJobSeedData.xml",
                "component://darpan/data/zzScheduleJobDefaultsApply.xml")
        def job = ec.entity.find("moqui.service.job.ServiceJob").condition("jobName", "scan_DueDatasets_30m").disableAuthz().useCache(false).one()
        assertNotNull(job, "scan_DueDatasets_30m is seeded")
        assertEquals("dataset.DatasetServices.scan#DueDatasets", job.serviceName)
        assertEquals("Y", job.defaultPaused)
        assertEquals("Y", job.paused, "the job starts paused: nothing refreshes until it is switched on")
        assertEquals("0 0/30 * * * ?", job.defaultCronExpression)
        assertNotNull(ec.service.getServiceDefinition(job.serviceName as String))
    }

    // ---- helpers ----

    private void assertUntouched(String id, Map manifestBefore, List recordsBefore, Map rowBefore, Set<String> foldersBefore) {
        File d = dir(id)
        assertEquals(manifestBefore, KeptCopyStore.manifest(d), "the current version is untouched")
        assertEquals(recordsBefore, KeptCopyStore.records(d).collect { it })
        assertEquals(foldersBefore, versionFolders(d), "no partial version is left")
        def row = find("darpan.dataset.Dataset", [datasetId: id])
        assertNull(row.refreshingSince, "the lock is cleared")
        // nextReadDate is not among them: a failed read backs it off on purpose (aFailedReadBacksOff)
        ["currentVersion", "readThru", "lastReadAt", "lastFullReadAt", "recordCount"].each {
            assertEquals(rowBefore[it], row[it], it)
        }
    }

    /** A failing read; asserts the next read is backed off by about {@code minutes}. */
    private void assertFailsWithNextReadIn(String id, int minutes) {
        long before = System.currentTimeMillis()
        Map res = refresh(id, { String svc, Map p -> throw new IllegalStateException("token revoked") } as Closure)
        long after = System.currentTimeMillis()
        assertTrue(res.errors.any { it.contains("token revoked") }, res.errors.toString())
        long next = (find("darpan.dataset.Dataset", [datasetId: id]).nextReadDate as Timestamp).time
        assertTrue(next >= before + minutes * 60_000L - 1000 && next <= after + minutes * 60_000L + 1000,
                "next read about ${minutes} minutes out, was ${(next - after) / 60_000.0} minutes")
    }

    /** A failing read after maxRetries failures: the next read is tomorrow at the read hour, in the tenant's zone. */
    private void assertFailsTillTomorrow(String id) {
        ZoneId zone = ZoneId.of(A_ZONE)
        Instant before = readHourNextDay(new Timestamp(System.currentTimeMillis()), zone)
        Map res = refresh(id, { String svc, Map p -> throw new IllegalStateException("token revoked") } as Closure)
        Instant after = readHourNextDay(new Timestamp(System.currentTimeMillis()), zone)
        assertTrue(res.errors.any { it.contains("token revoked") }, res.errors.toString())
        Instant next = (find("darpan.dataset.Dataset", [datasetId: id]).nextReadDate as Timestamp).toInstant()
        assertTrue(next == before || next == after, "next read ${next}, expected ${after}")
    }

    private static Instant readHourNextDay(Timestamp from, ZoneId zone) {
        return from.toInstant().atZone(zone).toLocalDate().plusDays(1).atTime(4, 0).atZone(zone).toInstant()
    }

    /** Runs work as a refresh that started {@code hours} ago: its lock token is that old, so a later refresh may take it over. */
    private Map startedHoursAgo(int hours, Closure<Map> work) {
        ec.user.setEffectiveTime(new Timestamp(System.currentTimeMillis() - hours * HOUR))
        try {
            return work.call()
        } finally {
            ec.user.setEffectiveTime(null)
        }
    }

    private static Set<String> versionFolders(File d) {
        return (d.listFiles()?.findAll { it.isDirectory() && it.name.startsWith("v") }*.name ?: []) as Set<String>
    }

    /** A made-up order created createdDaysAgo and updated updatedDaysAgo. */
    private static Map order(int n, int createdDaysAgo, int updatedDaysAgo, String name = null) {
        Instant now = RECORD_NOW
        return [id: "${n}".toString(), name: name ?: "#${n}".toString(),
                createdAt: now.minus(createdDaysAgo, ChronoUnit.DAYS).toString(),
                updatedAt: now.minus(updatedDaysAgo, ChronoUnit.DAYS).toString()]
    }

    /** The test extractor: records the call and writes {metadata, records} where the read asked. */
    private Closure stub(List<Map> calls, List<Map> records, Long writtenAt = null) {
        return { String svc, Map p ->
            calls << [service: svc, params: new LinkedHashMap(p), tenant: TenantAccessSupport.currentActiveTenantUserGroupId(ec)]
            File out = new File(DataManagerSupport.resolveDirectoryFile(ec, p.outputLocation as String, true), p.fileName as String)
            out.setText(JsonOutput.toJson([metadata: [windowStart: p.windowStart, windowEnd: p.windowEnd], records: records]), "UTF-8")
            if (writtenAt != null) out.setLastModified(writtenAt)
            return [fileLocation: out.absolutePath, recordCount: records.size(), dataAvailable: !records.isEmpty()]
        }
    }

    private String rolling(String conn, Map extra = [:]) {
        Map res = call("store#Dataset", [systemEnumId: "KC_ISO", sourceConfigId: "conn-${conn}".toString(), options: [:],
                idField: "id", windowField: "createdAt", readRuleEnumId: "DRR_ROLLING"] + extra)
        assertEquals([], res.errors)
        return res.out.datasetId
    }

    /** One unit of an order line: its copy id is orderId|orderItemSeqId|unitSeq. */
    private static Map unit(int orderId, int seq, int unitSeq, int createdDaysAgo, int updatedDaysAgo, String name = "unit") {
        Instant now = RECORD_NOW
        return [orderId: "${orderId}".toString(), orderItemSeqId: "${seq}".toString(), unitSeq: "${unitSeq}".toString(), name: name,
                createdAt: now.minus(createdDaysAgo, ChronoUnit.DAYS).toString(),
                updatedAt: now.minus(updatedDaysAgo, ChronoUnit.DAYS).toString()]
    }

    private String dataset(String conn, Map extra = [:]) {
        Map res = call("store#Dataset", [systemEnumId: "KC_ISO", sourceConfigId: "conn-${conn}".toString(), options: [:],
                idField: "id", stampField: "updatedAt", windowField: "createdAt"] + extra)
        assertEquals([], res.errors)
        return res.out.datasetId
    }

    private Map refresh(String id, Closure extractor, boolean forceFull = false) {
        return call("refresh#Dataset", [datasetId: id, extractor: extractor, forceFull: forceFull])
    }

    private File dir(String id) {
        String tenant = find("darpan.dataset.Dataset", [datasetId: id]).companyUserGroupId
        File root = DataManagerSupport.resolveDirectoryFile(ec, DataManagerSupport.resolveDataManagerLocation(ec), true)
        return DatasetSupport.datasetDir(root, tenant, id)
    }

    private String sliceLocation(String token) {
        return DataManagerSupport.resolveReconciliationRunLocation(ec, "dr-${token}", "20261120-000000000")
    }

    private File locationFile(String location) {
        return location.startsWith("/") ? new File(location) : ec.resource.getLocationReference(location).getFile()
    }

    private static Map json(File f) {
        return (Map) new groovy.json.JsonSlurper().parse(f, "UTF-8")
    }

    private List reads(String id) {
        return ec.entity.find("darpan.dataset.DatasetRead").condition("datasetId", id).disableAuthz().useCache(false).list()
    }

    private Map call(String verbNoun, Map params) {
        return underFacadeGrant("${SERVICES}.${verbNoun}".toString(), params)
    }

    private def find(String entityName, Map pk) {
        return ec.entity.find(entityName).condition(pk).disableAuthz().useCache(false).one()
    }

    private void update(String entityName, Map fields) {
        ec.entity.makeValue(entityName).setAll(fields).update()
    }

    private Map underFacadeGrant(String serviceName, Map params) {
        def harnessFrame = ec.artifactExecution.peek()
        assertEquals("smokeTests", harnessFrame?.name, "test precondition: unexpected artifact stack")
        ec.artifactExecution.pop(harnessFrame)
        ec.artifactExecution.enableAuthz()
        try {
            assertFalse(ec.artifactExecution.authzDisabled, "test precondition: authz must be ON")
            def aei = ec.artifactExecution.push(FACADE_GRANT, ArtifactExecutionInfo.AT_SERVICE, ArtifactExecutionInfo.AUTHZA_ALL, true)
            try {
                return collect { ec.service.sync().name(serviceName).parameters(new HashMap(params)).call() }
            } finally {
                ec.artifactExecution.pop(aei)
            }
        } finally {
            restoreHarness()
        }
    }

    /** As the ServiceJob runs it: nobody logged in, authz on; the service's anonymous-all is the only grant. */
    private Map asJob(Closure<Map> work) {
        def harnessFrame = ec.artifactExecution.peek()
        assertEquals("smokeTests", harnessFrame?.name, "test precondition: unexpected artifact stack")
        ec.artifactExecution.pop(harnessFrame)
        ec.user.logoutUser()
        ec.artifactExecution.enableAuthz()
        try {
            assertNull(TenantAccessSupport.currentActiveTenantUserGroupId(ec), "test precondition: no tenant in the job")
            return collect(work)
        } finally {
            restoreHarness()
        }
    }

    private void restoreHarness() {
        ec.artifactExecution.disableAuthz()
        ec.artifactExecution.push("smokeTests", ArtifactExecutionInfo.AT_OTHER, ArtifactExecutionInfo.AUTHZA_ALL, false)
        ec.artifactExecution.setAnonymousAuthorizedAll()
    }

    private Map collect(Closure<Map> call) {
        ec.message.clearAll()
        Map out = null
        try {
            out = call.call()
        } catch (Exception e) {
            ec.message.addError(e.toString())
        }
        List<String> errors = ec.message.errors.collect { it.toString() } +
                ec.message.validationErrors.collect { it.message?.toString() }
        // what the caller's reply would carry as messages (a cleared error turns into a danger message)
        List<String> messages = ec.message.messages.collect { it.toString() }
        ec.message.clearAll()
        return [out: out, errors: errors, messages: messages]
    }

    private void actAs(String username, String tenant) {
        assertTrue(ec.user.internalLoginUser(username), "could not log in ${username}")
        if (tenant) ec.user.setPreference(PREF_KEY, tenant)
        TenantAccessSupport.syncUserContext(ec)
        ec.message.clearErrors()
    }

    private void insert(String entityName, Map<String, Object> fields) {
        ec.entity.makeValue(entityName).setAll(fields).createOrUpdate()
    }
}
