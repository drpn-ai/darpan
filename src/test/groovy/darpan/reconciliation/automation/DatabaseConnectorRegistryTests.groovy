package darpan.reconciliation.automation

import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

/**
 * DATABASE is registered purely as data: toConnectorMap already reads every field required, so no
 * entity, dispatcher or whitelist change is involved. These tests pin the contract that makes the
 * row work, including the two dispatch fences the resolved service name must clear.
 */
class DatabaseConnectorRegistryTests {

    private static String seed() { new File('data/SourceSystemConnectorSeedData.xml').text }

    private static String databaseRow() {
        String s = seed()
        int i = s.indexOf('systemEnumId="DATABASE"')
        assertTrue(i >= 0, 'no DATABASE connector row')
        return s.substring(s.lastIndexOf('<', i), s.indexOf('/>', i))
    }

    @Test void databaseRowCarriesTheRequiredContract() {
        String row = databaseRow()
        assertTrue(row.contains('extractServiceName="reconciliation.DatabaseExtractionServices.extract#DatabaseRecords"'))
        assertTrue(row.contains('configParameterName="databaseSourceQueryId"'))
        assertTrue(row.contains('configEntityName="darpan.database.DatabaseSourceQuery"'))
        assertTrue(row.contains('dateFromParameterName="windowStart"'))
        assertTrue(row.contains('dateToParameterName="windowEnd"'))
        assertTrue(row.contains('enabled="Y"'))
    }

    @Test void configTypeMatchesTheEndpointReshapeMap() {
        // The System/Endpoint reshape replaces this registry wholesale and already maps
        // DATABASE -> DATABASE_QUERY. Any other value is silently dropped at cutover.
        assertTrue(databaseRow().contains('expectedSourceConfigType="DATABASE_QUERY"'))
    }

    @Test void extractServiceNamePassesTheDispatchFence() {
        assertTrue(SourceSystemConnectorSupport.isAllowedExtractorServiceShape(
                'reconciliation.DatabaseExtractionServices.extract#DatabaseRecords'))
    }

    @Test void healthCheckServiceNamePassesTheProbeFence() {
        assertTrue(SourceSystemConnectorSupport.isAllowedProbeServiceShape(
                'facade.DatabaseFacadeServices.probe#DatabaseConnection'))
        // and the validate# name it replaced would NOT have
        assertFalse(SourceSystemConnectorSupport.isAllowedProbeServiceShape(
                'facade.DatabaseFacadeServices.validate#DatabaseConnection'))
    }

    @Test void rowDoesNotPopulateTheConsumerlessValidationSlot() {
        assertFalse(databaseRow().contains('validationServiceName'),
                'validationServiceName has no consumer anywhere; populating it is decoration')
    }

    @Test void rowOmitsHttpShapedFields() {
        String row = databaseRow()
        assertFalse(row.contains('sendUrlTemplate'), 'HTTP-shaped and does not route')
        assertFalse(row.contains('remoteSendServiceName'))
        assertFalse(row.contains('keepFieldsParameterName'), 'projection is the SELECT list')
    }
}
