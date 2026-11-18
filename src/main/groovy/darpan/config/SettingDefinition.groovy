package darpan.config

import groovy.transform.Immutable

/**
 * One catalog entry (DAR-BE-304): what a setting is, its default, and the range a value must stay in.
 * For INT_LIST the min and max apply to each entry and maxItems bounds the list length.
 */
@Immutable
class SettingDefinition {
    String key
    /** INT, BOOLEAN, DECIMAL or INT_LIST. */
    String type
    String area
    /** minutes, days, percent, count, runs, usd, factor, switch, ms, tokens or chars. */
    String unit
    String description
    String defaultValue
    BigDecimal min
    BigDecimal max
    Integer maxItems
    /** Key of another setting whose effective value this one must stay strictly below. */
    String lessThan
}
