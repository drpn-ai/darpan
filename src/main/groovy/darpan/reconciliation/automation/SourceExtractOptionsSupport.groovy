package darpan.reconciliation.automation

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import static darpan.common.ValueSupport.normalize
import static darpan.common.ValueSupport.readField

/**
 * DAR-UI-064. A run side's extract options: parameters its extract service takes beyond the config id and window, chosen
 * per run (gorjana R8 reads OMS returns WITHOUT a Shopify reference, R16 only the cancelled ones). They used to live only
 * in an automation source's safeMetadataJson, so a manual run of the same check read a different population.
 *
 * <p>Config over code: the connector declares what it accepts (SourceSystemConnector.extractOptionsJson,
 * [{name, label, type}], type boolean or text), the run side stores its choice (RuleSetCompareSource.extractParametersJson),
 * and both run paths read it. A new option is a seed edit, never a UI release.</p>
 *
 * <p>Saving is strict (an undeclared name or a wrong type is refused, in words); running is lenient (an option the
 * connector no longer declares is dropped), so a seed change never fails a scheduled run.</p>
 */
class SourceExtractOptionsSupport {

    static final List<String> OPTION_TYPES = ["boolean", "text"].asImmutable()

    /** What a connector map (SourceSystemConnectorSupport) declares; [] when nothing or unreadable. */
    static List<Map<String, Object>> declaredOptions(Map<String, Object> connector) {
        Object raw = parse(connector?.get("extractOptionsJson"))
        if (!(raw instanceof List)) return []
        return ((List) raw).findAll { it instanceof Map && normalize(((Map) it).name) }.collect { Map o ->
            String type = normalize(o.type)?.toLowerCase()
            [name : normalize(o.name), label: normalize(o.label) ?: normalize(o.name),
             type : type in OPTION_TYPES ? type : "boolean"] as Map<String, Object>
        }
    }

    /**
     * The options as stored JSON, or null when none are set (false / blank values are not stored: absent IS the default).
     * Refuses, with the reason, an option the side's connector does not declare or a value of the wrong type.
     */
    static String validatedJson(Map<String, Object> connector, Object submitted, String sideLabel = "This side") {
        Object raw
        try {
            raw = parse(submitted)
        } catch (Exception ignored) {
            throw new IllegalArgumentException("${sideLabel}'s options are not readable.")
        }
        if (raw == null) return null
        if (!(raw instanceof Map)) throw new IllegalArgumentException("${sideLabel}'s options must be a set of named values.")
        Map<String, Map<String, Object>> declared = declaredOptions(connector).collectEntries { [(it.name): it] }
        Map<String, Object> kept = new TreeMap<>()
        ((Map) raw).each { Object k, Object v ->
            String name = normalize(k)
            Map<String, Object> option = declared[name]
            if (option == null) {
                throw new IllegalArgumentException("${sideLabel} has no option '${name}'" +
                        (declared ? "; it takes ${declared.keySet().join(', ')}." : "; its source takes no options."))
            }
            if (option.type == "boolean") {
                if (!(v instanceof Boolean) && !(v?.toString() in ["true", "false"])) {
                    throw new IllegalArgumentException("${sideLabel}'s ${option.label} is on or off, got: ${v}")
                }
                if (v == true || v?.toString() == "true") kept[name] = true
            } else {
                String text = normalize(v)
                if (text != null && text.length() > 200) throw new IllegalArgumentException("${sideLabel}'s ${option.label} is too long.")
                if (text) kept[name] = text
            }
        }
        return kept ? JsonOutput.toJson(kept) : null
    }

    /**
     * For a save service: the side's options validated against its system's connector, or null with the reason on
     * ec.message. Also checks duplicateKeyHandling, the side's other per-run choice (blank or FINDING, DAR-BE-063).
     */
    static String validatedJsonOrError(def ec, String systemEnumId, Object submitted, String sideLabel) {
        try {
            return validatedJson(SourceSystemConnectorSupport.resolve(ec, normalize(systemEnumId)), submitted, sideLabel)
        } catch (IllegalArgumentException e) {
            ec.message.addError(e.message)
            return null
        }
    }

    /** Blank (a repeated key fails a ruled run) or FINDING (every record under it is a finding); anything else is refused. */
    static String duplicateKeyHandlingOrError(def ec, Object submitted, String sideLabel) {
        String value = normalize(submitted)?.toUpperCase()
        if (!value) return null
        if (value != "FINDING") {
            ec.message.addError("${sideLabel}'s repeated-key handling must be blank or FINDING, got: ${submitted}")
            return null
        }
        return value
    }

    /** The stored options a run passes on: only names the connector still declares. Never throws. */
    static Map<String, Object> runtimeOptions(Map<String, Object> connector, Object stored) {
        Object raw
        try {
            raw = parse(stored)
        } catch (Exception ignored) {
            return [:]
        }
        if (!(raw instanceof Map)) return [:]
        Set<String> names = declaredOptions(connector)*.name as Set<String>
        return ((Map<String, Object>) raw).findAll { k, v -> names.contains(k as String) && v != null } as Map<String, Object>
    }

    /** A source row's stored options; guarded, because an EntityValue throws on a field its entity does not declare. */
    static Object storedOn(Object source) {
        if (source == null) return null
        if (source.metaClass.respondsTo(source, "isField", String)) {
            return source.isField("extractParametersJson") ? readField(source, "extractParametersJson") : null
        }
        return source instanceof Map ? ((Map) source).get("extractParametersJson") : null
    }

    /** Adds the options to a service's params without displacing anything already there (config id, window, filters). */
    static Map<String, Object> mergeUnder(Map<String, Object> params, Map<String, Object> options) {
        options?.each { String k, Object v -> if (!params.containsKey(k)) params[k] = v }
        return params
    }

    private static Object parse(Object value) {
        if (value == null) return null
        if (value instanceof Map || value instanceof List) return value
        String text = value.toString().trim()
        return text ? new JsonSlurper().parseText(text) : null
    }
}
