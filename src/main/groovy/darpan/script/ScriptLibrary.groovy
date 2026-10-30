package darpan.script

import groovy.transform.CompileStatic

import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/**
 * DAR-BE-164. The {@code d:} namespace: the only functions a tenant script can call. Every function takes and returns
 * plain JSON values (text, numbers, true/false, lists, maps, null), so nothing it hands back leads anywhere, and charges
 * units for the work it does. There is no clock and no randomness: the same input always gives the same result.
 *
 * <p>A request this library cannot serve becomes a new function here (core code), never a looser profile.
 * Names avoid JEXL's reserved words (size, empty, new, not, and, or...).</p>
 */
@CompileStatic
class ScriptLibrary {

    /** Every function and its one-line help, for the editor. A field, not a property, so Groovy adds no getter. */
    public static final List<Map<String, String>> CATALOG = [
            [name: 'dec(x)', help: 'x as a decimal number (text or number); null stays null'],
            [name: 'money(x)', help: 'x rounded half-up to 2 decimals'],
            [name: 'round(x, places)', help: 'x rounded half-up to places decimals'],
            [name: 'abs(x)', help: 'x without its sign'],
            [name: 'min(a, b) / max(a, b)', help: 'the smaller / larger of two numbers'],
            [name: 'str(x)', help: 'x as text'],
            [name: 'lower(s) / upper(s) / trim(s)', help: 'text case and surrounding spaces'],
            [name: 'has(s, part)', help: 'true when text s contains part'],
            [name: 'begins(s, part) / ends(s, part)', help: 'true when text s starts / ends with part'],
            [name: 'replace(s, from, to)', help: 'every from in s replaced with to (plain text, no patterns)'],
            [name: 'split(s, sep)', help: 'text s split on sep into a list'],
            [name: 'join(list, sep)', help: 'the list as one text, sep between items'],
            [name: 'len(x)', help: 'characters in a text, items in a list or keys in a map'],
            [name: 'keys(map) / vals(map)', help: "a map's keys / values as a list"],
            [name: 'pluck(list, field)', help: 'field from each map in the list'],
            [name: 'where(list, field, value)', help: 'the maps in the list whose field equals value'],
            [name: 'count(list, field, value)', help: 'how many maps in the list have field equal to value'],
            [name: 'sum(list) / sum(list, field)', help: 'the total of the numbers, or of field across maps'],
            [name: 'groupCount(list, field)', help: 'a map of each value of field to how many maps have it'],
            [name: 'sorted(list) / distinct(list)', help: 'the list sorted / with repeats removed'],
            [name: 'day(x)', help: 'a date or timestamp as yyyy-MM-dd (UTC)'],
            [name: 'daysBetween(a, b) / hoursBetween(a, b)', help: 'whole days / hours from a to b (dates, timestamps or epoch ms)'],
            [name: 'log(x)', help: 'adds x to the run log and returns it'],
    ].collect { it as Map<String, String> }.asImmutable()

    // -- numbers

    static BigDecimal dec(Object x) {
        ScriptBudget.charge()
        if (x == null) return null
        if (x instanceof BigDecimal) return (BigDecimal) x
        if (x instanceof Number) return new BigDecimal(x.toString())
        String text = x.toString().trim().replace(',', '')
        if (text.isEmpty()) return null
        if (text.length() > 64) throw new ScriptLimitException("NUMBER", "'${text.take(20)}…' is too long to be a number.".toString())
        try {
            return new BigDecimal(text)
        } catch (NumberFormatException ignored) {
            throw new IllegalArgumentException("'${text}' is not a number.".toString())
        }
    }

    static BigDecimal money(Object x) { round(x, 2) }

    static BigDecimal round(Object x, Object places) {
        BigDecimal value = dec(x)
        int scale = places == null ? 0 : dec(places).intValue()
        if (scale < 0 || scale > 12) throw new IllegalArgumentException("round takes 0 to 12 places.")
        return value?.setScale(scale, RoundingMode.HALF_UP)
    }

    static BigDecimal abs(Object x) { dec(x)?.abs() }

    static BigDecimal min(Object a, Object b) { BigDecimal x = dec(a), y = dec(b); x == null ? y : (y == null ? x : x.min(y)) }

    static BigDecimal max(Object a, Object b) { BigDecimal x = dec(a), y = dec(b); x == null ? y : (y == null ? x : x.max(y)) }

    // -- text

    static String str(Object x) {
        ScriptBudget.charge()
        if (x == null) return null
        return ScriptBudget.checkString(x instanceof BigDecimal ? ((BigDecimal) x).toPlainString() : x.toString())
    }

    static String lower(Object s) { str(s)?.toLowerCase(Locale.ROOT) }

    static String upper(Object s) { str(s)?.toUpperCase(Locale.ROOT) }

    static String trim(Object s) { str(s)?.trim() }

    static boolean has(Object s, Object part) {
        String text = str(s), p = str(part)
        ScriptBudget.charge(1 + (text?.length() ?: 0).intdiv(64))
        return text != null && p != null && text.contains(p)
    }

    static boolean begins(Object s, Object part) { String text = str(s), p = str(part); text != null && p != null && text.startsWith(p) }

    static boolean ends(Object s, Object part) { String text = str(s), p = str(part); text != null && p != null && text.endsWith(p) }

    static String replace(Object s, Object from, Object to) {
        String text = str(s), f = str(from)
        if (text == null || !f) return text
        ScriptBudget.charge(1 + text.length().intdiv(64))
        return ScriptBudget.checkString(text.replace(f, str(to) ?: ''))
    }

    static List<String> split(Object s, Object sep) {
        String text = str(s), separator = str(sep)
        if (text == null) return []
        if (!separator) throw new IllegalArgumentException("split needs a separator.")
        List<String> parts = []
        int from = 0
        while (true) {
            ScriptBudget.charge()
            int at = text.indexOf(separator, from)
            parts << (at < 0 ? text.substring(from) : text.substring(from, at))
            ScriptBudget.checkSize(parts.size())
            if (at < 0) break
            from = at + separator.length()
        }
        return parts
    }

    static String join(Object list, Object sep) {
        List items = asList(list, 'join')
        ScriptBudget.charge(items.size())
        return ScriptBudget.checkString(items.collect { it instanceof BigDecimal ? ((BigDecimal) it).toPlainString() : String.valueOf(it) }.join(str(sep) ?: ''))
    }

    // -- lists and maps

    static int len(Object x) {
        ScriptBudget.charge()
        if (x == null) return 0
        if (x instanceof CharSequence) return ((CharSequence) x).length()
        if (x instanceof Collection) return ((Collection) x).size()
        if (x instanceof Map) return ((Map) x).size()
        if (x.getClass().isArray()) return java.lang.reflect.Array.getLength(x)
        throw new IllegalArgumentException("len takes text, a list or a map.")
    }

    static List keys(Object map) { Map m = asMap(map, 'keys'); ScriptBudget.charge(m.size()); new ArrayList(m.keySet()) }

    static List vals(Object map) { Map m = asMap(map, 'vals'); ScriptBudget.charge(m.size()); new ArrayList(m.values()) }

    static List pluck(Object list, Object field) {
        List items = asList(list, 'pluck')
        ScriptBudget.charge(items.size())
        return items.collect { it instanceof Map ? ((Map) it).get(field) : null }
    }

    static List where(Object list, Object field, Object value) {
        List items = asList(list, 'where')
        ScriptBudget.charge(items.size())
        return items.findAll { it instanceof Map && same(((Map) it).get(field), value) }
    }

    static int count(Object list, Object field, Object value) { where(list, field, value).size() }

    static BigDecimal sum(Object list) { sum(list, null) }

    static BigDecimal sum(Object list, Object field) {
        List items = asList(list, 'sum')
        ScriptBudget.charge(items.size())
        BigDecimal total = BigDecimal.ZERO
        items.each { Object item ->
            Object value = field == null ? item : (item instanceof Map ? ((Map) item).get(field) : null)
            BigDecimal number = value == null ? null : dec(value)
            if (number != null) total = total.add(number)
        }
        return total
    }

    static Map<String, Integer> groupCount(Object list, Object field) {
        List items = asList(list, 'groupCount')
        ScriptBudget.charge(items.size())
        Map<String, Integer> out = new LinkedHashMap<>()
        items.each { Object item ->
            String key = String.valueOf(item instanceof Map ? ((Map) item).get(field) : null)
            out.put(key, (out.get(key) ?: 0) + 1)
        }
        return out
    }

    static List sorted(Object list) {
        List items = new ArrayList(asList(list, 'sorted'))
        ScriptBudget.charge(items.size() * 4L)
        items.sort { Object a, Object b ->
            (a instanceof Number && b instanceof Number) ? dec(a) <=> dec(b) : String.valueOf(a) <=> String.valueOf(b)
        }
        return items
    }

    static List distinct(Object list) {
        List items = asList(list, 'distinct')
        ScriptBudget.charge(items.size())
        return new ArrayList(new LinkedHashSet(items))
    }

    // -- dates (from inputs only; there is no clock)

    static String day(Object x) {
        Instant at = instant(x)
        return at == null ? null : at.atOffset(ZoneOffset.UTC).toLocalDate().toString()
    }

    static Long daysBetween(Object a, Object b) {
        Instant from = instant(a), to = instant(b)
        return (from == null || to == null) ? null : Duration.between(from, to).toDays()
    }

    static Long hoursBetween(Object a, Object b) {
        Instant from = instant(a), to = instant(b)
        return (from == null || to == null) ? null : Duration.between(from, to).toHours()
    }

    // -- log

    static Object log(Object x) {
        ScriptBudget.charge()
        ScriptBudget.log(x instanceof BigDecimal ? ((BigDecimal) x).toPlainString() : String.valueOf(x))
        return x
    }

    // -- helpers (not public: the permissions admit public static methods only)

    private static List asList(Object x, String fn) {
        if (x == null) return []
        if (x instanceof List) return (List) x
        if (x instanceof Collection) return new ArrayList((Collection) x)
        if (x.getClass().isArray()) return ScriptArithmetic.arrayItems(x)
        throw new IllegalArgumentException("${fn} takes a list.".toString())
    }

    private static Map asMap(Object x, String fn) {
        if (x == null) return [:]
        if (x instanceof Map) return (Map) x
        throw new IllegalArgumentException("${fn} takes a map.".toString())
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) return dec(a).compareTo(dec(b)) == 0
        return a == b || (a != null && b != null && a.toString() == b.toString())
    }

    private static Instant instant(Object x) {
        ScriptBudget.charge()
        if (x == null) return null
        if (x instanceof Number) return Instant.ofEpochMilli(((Number) x).longValue())
        String text = x.toString().trim()
        if (text.isEmpty()) return null
        try { return Instant.parse(text) } catch (DateTimeParseException ignored) { }
        try { return OffsetDateTime.parse(text).toInstant() } catch (DateTimeParseException ignored) { }
        try { return LocalDateTime.parse(text.replace(' ', 'T')).toInstant(ZoneOffset.UTC) } catch (DateTimeParseException ignored) { }
        try { return LocalDate.parse(text.take(10)).atStartOfDay().toInstant(ZoneOffset.UTC) } catch (DateTimeParseException ignored) { }
        if (text.isLong()) return Instant.ofEpochMilli(text.toLong())
        throw new IllegalArgumentException("'${text.take(40)}' is not a date or timestamp.".toString())
    }
}
