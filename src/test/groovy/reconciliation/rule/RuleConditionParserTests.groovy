package reconciliation.rule

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.assertEquals

class RuleConditionParserTests {

    @Test
    void notContainsConditionIsParsedBeforeContainsCondition() {
        assertEquals(
                'this["status"] == null || !this["status"].toString().contains("Pending")',
                RuleConditionParser.parseCondition("status not contains Pending")
        )
    }

    @Test
    void quotedSingleCharacterValueDoesNotBreakNormalization() {
        assertEquals(
                'this["status"] == "\'"',
                RuleConditionParser.parseCondition("status is '")
        )
    }
}
