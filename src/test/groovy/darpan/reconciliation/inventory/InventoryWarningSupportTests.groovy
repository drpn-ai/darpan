package darpan.reconciliation.inventory

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertIterableEquals

class InventoryWarningSupportTests {

    @Test
    void normalizeWarningTextsReturnsUniqueTrimmedStrings() {
        List<String> warnings = InventoryWarningSupport.normalizeWarningTexts([
                "  JDBC URL generated  ",
                null,
                "",
                "JDBC URL generated",
                "NS fetch delayed"
        ])

        assertIterableEquals(["JDBC URL generated", "NS fetch delayed"], warnings)
    }

    @Test
    void normalizeWarningTextsAcceptsLegacyWarningRowsForMigrationSafety() {
        List<String> warnings = InventoryWarningSupport.normalizeWarningTexts([
                [warningMessage: "  NS warning  "],
                [warningMessage: ""],
                [otherField: "ignored"],
                [warningMessage: "Read DB warning"]
        ])

        assertIterableEquals(["NS warning", "Read DB warning"], warnings)
    }
}
