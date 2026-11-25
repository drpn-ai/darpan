package darpan.config

import groovy.transform.Immutable

/**
 * One catalog entry (DAR-BE-304): what a setting is, its default, and the range a value must stay in.
 * For INT_LIST the min and max apply to each entry and maxItems bounds the list length.
 */
@Immutable
class SettingDefinition {
    String key
    /** INT, BOOLEAN, DECIMAL, INT_LIST or STRING. */
    String type
    String area
    /** minutes, hours, days, percent, count, runs, usd, factor, switch, text, ms, tokens or chars. */
    String unit
    String description
    String defaultValue
    BigDecimal min
    BigDecimal max
    Integer maxItems
    /** Key of another setting whose effective value this one must stay strictly below. */
    String lessThan
    /** True when the setting may have no value at all (null default): unset means the feature is off, e.g. keep forever. */
    Boolean optional
    /**
     * Names the admin service that owns writes to this key (e.g. retention). The generic settings editor refuses a managed
     * key and points there, because that service carries a guard the generic editor cannot (a fresh preview).
     */
    String managedBy
    /** True for a STRING that is a credential (a webhook URL): listed and shown masked, never in full. */
    Boolean secret
}
