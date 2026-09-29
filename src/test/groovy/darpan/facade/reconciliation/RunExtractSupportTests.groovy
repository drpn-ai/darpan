package darpan.facade.reconciliation

import org.junit.jupiter.api.Test

import java.sql.Timestamp

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertNull
import static org.junit.jupiter.api.Assertions.assertTrue

/**
 * Pins the extract parameter map lifted out of runSavedRunDiff.groovy (pipeline-unification step 5a).
 *
 * This is the surface a reimplementation gets wrong, and it already has: the scheduled path's date
 * parameters default to fromDate/toDate where this one defaults to windowStart/windowEnd, and it sends
 * raw Timestamps where this sends ISO instants. Those divergences are documented on RunExtractSupport;
 * these tests make the interactive half of them a contract rather than an accident, so a later
 * convergence has to change a test on purpose instead of changing behaviour by surprise.
 *
 * Pure — no Moqui, no Spark, so it runs in the fast unitTest pool.
 */
class RunExtractSupportTests {

    private static final Timestamp FROM = Timestamp.valueOf("2026-08-01 00:00:00")
    private static final Timestamp THRU = Timestamp.valueOf("2026-09-01 00:00:00")
    private static final Map<String, Object> ARTIFACT = [location: "dbresource://darpan/runs/R1", runToken: "R1"]

    private static Map<String, Object> params(Map<String, Object> connector, Map<String, Object> overrides = [:]) {
        return RunExtractSupport.buildExtractParams(connector, "FILE_1", "CFG1", ARTIFACT, FROM, THRU,
                "shopify-orders-api.json",
                (List<String>) overrides.get("keepFields"),
                (List<Map<String, Object>>) overrides.get("excludeFilters"),
                (Map<String, Object>) overrides.get("progressContext"),
                overrides.get("windowTimeZone"))
    }

    // ------------------------------------------------- DATE-GRANULAR WINDOW (DAR-BE-057)

    /** "Midnight on this calendar day, in this zone" — the shape normalizeCalendarWindow produces. */
    private static Timestamp dayStartIn(String isoDate, String zone) {
        return Timestamp.from(java.time.LocalDate.parse(isoDate)
                .atStartOfDay(java.time.ZoneId.of(zone)).toInstant())
    }

    /**
     * A source that filters on a DATE column cannot be handed an instant. The window reaching the
     * extractor is already anchored as midnight-in-the-window's-zone, so recovering the calendar day
     * means reading it back IN THAT SAME ZONE. Read in any other zone the day moves, which is how a
     * run picked for Aug 1 from IST queried 2026-07-31 and reported its 15 findings under "Aug 1".
     */
    @Test
    void dateOnlyConnectorReceivesTheCalendarDayNotAnInstant() {
        Map<String, Object> p = RunExtractSupport.buildExtractParams(
                [windowDateOnly: true], "FILE_1", "CFG1", ARTIFACT,
                dayStartIn("2026-08-01", "Asia/Kolkata"), dayStartIn("2026-09-01", "Asia/Kolkata"),
                "ns-orders.json", null, null, null, "Asia/Kolkata")

        assertEquals("2026-08-01", p.windowStart)
        assertEquals("2026-09-01", p.windowEnd)
    }

    /**
     * The invariant, and the reason the zone must travel with the window rather than be assumed: a day
     * anchored in ANY zone comes back as that same day when read in the zone it was anchored in. The
     * old path read every one of them in UTC, so only UTC tenants got the day they asked for.
     */
    @Test
    void theCalendarDaySurvivesEveryAnchoringZone() {
        ["Asia/Kolkata", "America/Los_Angeles", "UTC", "Pacific/Auckland"].each { String zone ->
            Map<String, Object> p = RunExtractSupport.buildExtractParams(
                    [windowDateOnly: true], "FILE_1", "CFG1", ARTIFACT,
                    dayStartIn("2026-08-01", zone), dayStartIn("2026-08-02", zone),
                    "ns-orders.json", null, null, null, zone)

            assertEquals("2026-08-01", p.windowStart, "start moved for ${zone}")
            assertEquals("2026-08-02", p.windowEnd, "end moved for ${zone}")
        }
    }

    /** Unflagged connectors keep the ISO instant, byte for byte. */
    @Test
    void instantConnectorsAreUntouchedByTheDateOnlyBranch() {
        Map<String, Object> p = params([:], [windowTimeZone: "Asia/Kolkata"])

        assertEquals(RunExtractSupport.formatApiWindow(FROM), p.windowStart)
        assertEquals(RunExtractSupport.formatApiWindow(THRU), p.windowEnd)
    }

    /** "N" is a non-empty String and therefore truthy in Groovy; the flag must be read as a Boolean. */
    @Test
    void dateOnlyFalseIsNotMistakenForTrue() {
        Map<String, Object> p = params([windowDateOnly: false], [windowTimeZone: "Asia/Kolkata"])

        assertEquals(RunExtractSupport.formatApiWindow(FROM), p.windowStart)
    }

    // ---------------------------------------------------------------- parameter NAMES

    @Test
    void connectorlessDefaultsAreTheInteractiveOnes() {
        // NOT fromDate/toDate — that is the scheduled path's default, and conflating them would change
        // which window a manual run requests.
        Map<String, Object> p = params([:])
        assertEquals("CFG1", p.sourceConfigId)
        assertTrue(p.containsKey("windowStart"))
        assertTrue(p.containsKey("windowEnd"))
        assertFalse(p.containsKey("fromDate"))
    }

    @Test
    void connectorSuppliedNamesWin() {
        Map<String, Object> p = params([configParameterName: "omsRestSourceConfigId",
                                        dateFromParameterName: "orderDateFrom",
                                        dateToParameterName: "orderDateThru"])
        assertEquals("CFG1", p.omsRestSourceConfigId)
        assertTrue(p.containsKey("orderDateFrom"))
        assertTrue(p.containsKey("orderDateThru"))
        assertFalse(p.containsKey("windowStart"))
        assertFalse(p.containsKey("sourceConfigId"))
    }

    // ---------------------------------------------------------------- the window is an instant

    @Test
    void windowTravelsAsAnIsoInstantNotATimestamp() {
        // A Timestamp renders in the JVM default zone when stringified; the getters take instants. The
        // scheduled path passes Timestamps, so this is exactly the kind of difference that must be
        // pinned rather than assumed identical.
        Map<String, Object> p = params([:])
        assertEquals(FROM.toInstant().toString(), p.windowStart)
        assertEquals(THRU.toInstant().toString(), p.windowEnd)
        assertTrue(((String) p.windowStart).endsWith("Z"), p.windowStart as String)
    }

    @Test
    void aNullWindowBoundStaysNullRatherThanBecomingEpoch() {
        Map<String, Object> p = RunExtractSupport.buildExtractParams([:], "FILE_1", "CFG1", ARTIFACT,
                null, THRU, "f.json", null, null, null)
        assertNull(p.windowStart)
    }

    // ---------------------------------------------------------------- opt-in parameters

    @Test
    void preserveWindowInstantsOnlyWhenTheConnectorAsksForIt() {
        assertFalse(params([:]).containsKey("preserveWindowInstants"))
        assertEquals(true, params([preserveWindowInstants: true]).preserveWindowInstants)
    }

    @Test
    void windowFieldNameIsOmittedWhenBlankSoTheExtractorKeepsItsDefault() {
        assertFalse(params([windowFieldName: "   "]).containsKey("windowFieldName"))
        assertEquals("lastUpdatedTxStamp", params([windowFieldName: "lastUpdatedTxStamp"]).windowFieldName)
    }

    @Test
    void keepFieldsNeedBothAParameterNameAndANonEmptyList() {
        assertFalse(params([:], [keepFields: ["orderId"]]).containsKey("keepFields"))
        assertFalse(params([keepFieldsParameterName: "keepFields"], [keepFields: []]).containsKey("keepFields"))
        assertEquals(["orderId"], params([keepFieldsParameterName: "keepFields"], [keepFields: ["orderId"]]).keepFields)
    }

    @Test
    void excludeFiltersNeedBothAParameterNameAndANonEmptyList() {
        List<Map<String, Object>> rules = [[fieldExpression: "salesChannelEnumId", operator: "EXCLUDE_IN"]]
        assertFalse(params([:], [excludeFilters: rules]).containsKey("sourceFilters"))
        assertFalse(params([filterParameterName: "sourceFilters"], [excludeFilters: []]).containsKey("sourceFilters"))
        assertEquals(rules, params([filterParameterName: "sourceFilters"], [excludeFilters: rules]).sourceFilters)
    }

    // ---------------------------------------------------------------- progress heartbeat

    @Test
    void progressParametersRideOnlyWhenARunResultIdIsPresent() {
        assertFalse(params([:], [progressContext: [progressStageCode: "EXTRACT_FILE1"]])
                .containsKey("progressStageCode"))

        Map<String, Object> p = params([:], [progressContext: [reconciliationRunResultId: "RR1",
                                                               progressStageCode: "EXTRACT_FILE1"]])
        assertEquals("RR1", p.reconciliationRunResultId)
        assertEquals("EXTRACT_FILE1", p.progressStageCode)
        // Optional on purpose: only the second side can have a denominator, and gating the whole
        // heartbeat on one is what left the first extract reporting nothing at all.
        assertFalse(p.containsKey("expectedRecordCount"))
    }

    @Test
    void expectedRecordCountIsAddedWhenSupplied() {
        Map<String, Object> p = params([:], [progressContext: [reconciliationRunResultId: "RR1",
                                                               progressStageCode: "EXTRACT_FILE2",
                                                               expectedRecordCount: 1200]])
        assertEquals(1200, p.expectedRecordCount)
    }

    // ---------------------------------------------------------------- side naming

    @Test
    void sideTokenIsFileOneOnlyForFileSideOne() {
        assertEquals("file1", RunExtractSupport.sideToken("FILE_1"))
        assertEquals("file2", RunExtractSupport.sideToken("FILE_2"))
        assertEquals("file2", RunExtractSupport.sideToken(null))
    }

    @Test
    void outputLocationAndFileNameAreScopedToTheSide() {
        assertTrue(((String) params([:]).outputLocation).endsWith("file1-api"), params([:]).outputLocation as String)
        assertTrue(((String) params([:]).fileName).contains("file1"), params([:]).fileName as String)
    }

    @Test
    void formatApiWindowIsNullSafe() {
        assertNull(RunExtractSupport.formatApiWindow(null))
        assertEquals(FROM.toInstant().toString(), RunExtractSupport.formatApiWindow(FROM))
    }
}
