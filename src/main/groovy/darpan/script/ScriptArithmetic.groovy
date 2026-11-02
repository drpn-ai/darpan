package darpan.script

import groovy.transform.CompileStatic
import org.apache.commons.jexl3.JexlArithmetic
import org.apache.commons.jexl3.JexlEngine

import java.lang.reflect.Array
import java.math.MathContext

/**
 * DAR-BE-164. The arithmetic every tenant script runs through, and the meter.
 *
 * <ul>
 *   <li>Every operator, every loop condition and every loop step spends a unit (ScriptBudget), so a loop with an empty
 *   body still runs out: the spike showed {@code while (true) {}} and {@code for (var i : 1..100000000) {}} both stop
 *   here.</li>
 *   <li>Property and index reads are overloaded so a script can only read a map's keys and a list's items. Without
 *   this, {@code input.class} returned the java.lang.Class of the input map.</li>
 *   <li>Strings, lists, maps and whole numbers are capped, because JEXL has no memory limit of its own.</li>
 *   <li>{@code =~} on text is a Java regex match (catastrophic backtracking cannot be interrupted), so it is
 *   refused; {@code d:has} covers plain containment.</li>
 * </ul>
 */
@CompileStatic
class ScriptArithmetic extends JexlArithmetic {

    static final int MAX_NUMBER_BITS = 256

    ScriptArithmetic() { super(true) }

    ScriptArithmetic(boolean strict, MathContext context, int scale) { super(strict, context, scale) }

    /** JEXL builds a fresh arithmetic when per-call options differ; it must stay this class. */
    @Override
    protected JexlArithmetic createWithOptions(boolean strict, MathContext context, int scale) {
        return new ScriptArithmetic(strict, context, scale)
    }

    /**
     * Text joined with + is length-checked before it is built. Joining text with a list or map is refused: its
     * toString would build the whole collection first, outside every cap.
     */
    @Override
    Object add(Object l, Object r) {
        ScriptBudget.charge()
        // DAR-BE-175: + on a list joins (list + list) or appends (list + item), counted and capped like any list.
        // Before, JEXL fell back to text and list + list returned "[I@6eeb90e5[I@..." as a success.
        if (isListLike(l)) return joined(l, r)
        if (isListLike(r)) throw new ScriptLimitException("ACCESS", "Put the list first: list + item, or list + list.")
        if (l instanceof Map || r instanceof Map) {
            throw new ScriptLimitException("ACCESS", "+ does not combine a map; set its keys one at a time: m[key] = value.")
        }
        if (l instanceof CharSequence || r instanceof CharSequence) {
            if (!ScriptBudget.isScalar(l) || !ScriptBudget.isScalar(r)) {
                throw new ScriptLimitException("ACCESS", "Text can only be joined with text or numbers; use d:join for a list.")
            }
            ScriptBudget.checkLength(scalarLength(l) + scalarLength(r))
        }
        return guard(super.add(l, r))
    }

    private static boolean isListLike(Object x) {
        return (x instanceof Collection) || (x != null && x.getClass().isArray())
    }

    /** A list the script built itself (DAR-BE-175). Only these grow in place under +=; input lists are never changed. */
    static class ScriptList extends ArrayList<Object> { }

    /**
     * SELF_ADD overload: list += item / list += list. Appends IN PLACE to a list the script built (one unit and a size
     * check per added item), so building a list in a loop costs O(n), not a fresh copy per append (O(n^2), which
     * spent the whole budget at about 2,000 items). Anything else falls back to ordinary + (numbers, text, and a
     * first copy of any other list into a ScriptList).
     */
    Object selfAdd(Object l, Object r) {
        // JEXL treats selfAdd as IN PLACE and ignores its result. Anything but a ScriptList (an input list, a [] literal)
        // goes back to ordinary l = l + r, whose new ScriptList IS assigned; every later += then appends in place.
        if (!(l instanceof ScriptList)) return JexlEngine.TRY_FAILED
        ScriptBudget.charge()
        ScriptList list = (ScriptList) l
        if (isListLike(r)) {
            for (Object item : listItems(r)) { ScriptBudget.charge(); list.add(item); ScriptBudget.checkSize(list.size()) }
        } else {
            list.add(r); ScriptBudget.checkSize(list.size())
        }
        return list
    }

    /** A new list: the left list's items, then the right list's items or the right item. One unit and a size check per item. */
    private static List joined(Object left, Object right) {
        List out = new ScriptList()
        for (Object item : listItems(left)) { ScriptBudget.charge(); out.add(item); ScriptBudget.checkSize(out.size()) }
        if (isListLike(right)) {
            for (Object item : listItems(right)) { ScriptBudget.charge(); out.add(item); ScriptBudget.checkSize(out.size()) }
        } else {
            ScriptBudget.charge(); out.add(right); ScriptBudget.checkSize(out.size())
        }
        return out
    }

    private static Iterable listItems(Object x) {
        return x instanceof Collection ? (Collection) x : arrayItems(x)
    }

    private static long scalarLength(Object x) {
        if (x == null) return 4L
        if (x instanceof CharSequence) return ((CharSequence) x).length()
        if (x instanceof BigDecimal) return ScriptBudget.plainLength((BigDecimal) x)
        if (x instanceof BigInteger) return ((BigInteger) x).bitLength().intdiv(3) + 2L
        return 40L
    }
    @Override Object subtract(Object l, Object r) { ScriptBudget.charge(); return guard(super.subtract(l, r)) }
    @Override
    Object multiply(Object l, Object r) {
        ScriptBudget.charge()
        try {
            return guard(super.multiply(l, r))
        } catch (ScriptLimitException e) {
            throw e
        } catch (ArithmeticException e) {
            // JEXL 3.7 has its own BigInteger precision cap; report it as the same limit.
            if (e.message?.contains('precision')) throw new ScriptLimitException("NUMBER", "A number grew too large.")
            throw e
        }
    }
    @Override Object divide(Object l, Object r) { ScriptBudget.charge(); return guard(super.divide(l, r)) }
    @Override Object mod(Object l, Object r) { ScriptBudget.charge(); return guard(super.mod(l, r)) }
    @Override Object shiftLeft(Object l, Object r) { ScriptBudget.charge(); return guard(super.shiftLeft(l, r)) }
    @Override boolean testPredicate(Object o) { ScriptBudget.charge(); return super.testPredicate(o) }
    @Override boolean lessThan(Object l, Object r) { ScriptBudget.charge(); return super.lessThan(l, r) }
    @Override boolean greaterThan(Object l, Object r) { ScriptBudget.charge(); return super.greaterThan(l, r) }
    @Override boolean lessThanOrEqual(Object l, Object r) { ScriptBudget.charge(); return super.lessThanOrEqual(l, r) }
    @Override boolean greaterThanOrEqual(Object l, Object r) { ScriptBudget.charge(); return super.greaterThanOrEqual(l, r) }
    /**
     * == on lists and maps is walked here, one unit per node at every depth, so a nested comparison costs what it
     * does (the default deep equals is one uncharged, uninterruptible call).
     */
    @Override
    boolean equals(Object l, Object r) {
        ScriptBudget.charge()
        boolean lData = l instanceof Map || l instanceof Collection
        boolean rData = r instanceof Map || r instanceof Collection
        if (!lData && !rData) return super.equals(l, r)
        if (l.is(r)) return true
        if (l instanceof Map && r instanceof Map) {
            Map a = (Map) l, b = (Map) r
            if (a.size() != b.size()) return false
            for (Map.Entry e : (Set<Map.Entry>) a.entrySet()) {
                if (!b.containsKey(e.key) || !equals(e.value, b.get(e.key))) return false
            }
            return true
        }
        if (l instanceof Collection && r instanceof Collection) {
            Iterator i = ((Collection) l).iterator(), j = ((Collection) r).iterator()
            while (i.hasNext() && j.hasNext()) {
                if (!equals(i.next(), j.next())) return false
            }
            return !i.hasNext() && !j.hasNext()
        }
        return false
    }

    @Override
    Iterable<?> createRange(Object from, Object to) {
        ScriptBudget.charge()
        return super.createRange(from, to)
    }

    @Override
    Boolean contains(Object container, Object value) {
        ScriptBudget.charge()
        if (container instanceof CharSequence || container instanceof java.util.regex.Pattern) {
            throw new ScriptLimitException("ACCESS", "Pattern matching (=~ on text) is not available; use d:has(text, part).")
        }
        if (container instanceof Collection) ScriptBudget.charge(((Collection) container).size())
        return super.contains(container, value)
    }

    /** FOR_EACH overload: the loop's collection, metered one unit per step. */
    Iterator<?> forEach(Object collection) {
        ScriptBudget.charge()
        Iterator<?> source
        if (collection instanceof Map) source = ((Map) collection).values().iterator()
        else if (collection instanceof Iterable) source = ((Iterable) collection).iterator()
        else if (collection != null && collection.getClass().isArray()) source = arrayItems(collection).iterator()
        else if (collection == null) source = Collections.emptyIterator()
        else throw new ScriptLimitException("ACCESS", "A loop needs a list or a map.")
        return new Iterator<Object>() {
            boolean hasNext() { source.hasNext() }
            Object next() { ScriptBudget.charge(); source.next() }
        }
    }

    /** PROPERTY_GET overload: x.key reads a map key or a list index, nothing else. */
    Object propertyGet(Object target, Object key) {
        ScriptBudget.charge()
        if (target instanceof Map) {
            Map map = (Map) target
            Object value = map.get(key)
            if (value == null && key instanceof Number) value = map.get(key.toString())
            // Legal (optional fields, ?? defaults), but remembered: if this null later breaks a calculation, the
            // error can name the field the record does not have, and list the ones it does.
            if (value == null && !map.containsKey(key) && !(key instanceof Number && map.containsKey(key.toString()))) {
                ScriptBudget.noteMissing(key, map)
            }
            return value
        }
        if (target instanceof List || (target != null && target.getClass().isArray())) {
            List items = target instanceof List ? (List) target : arrayItems(target)
            Integer index = key instanceof Number ? ((Number) key).intValue() : (key?.toString()?.isInteger() ? key.toString().toInteger() : null)
            if (index == null) throw new ScriptLimitException("ACCESS", "A list has no field '${key}'; use a number, like items[0].".toString())
            if (index < 0 || index >= items.size()) {
                throw new ScriptLimitException("ACCESS", "There is no item ${index} in a list of ${items.size()}.".toString())
            }
            return items.get(index)
        }
        throw new ScriptLimitException("ACCESS", "'${key}' can only be read from a map or a list.".toString())
    }

    /** ARRAY_GET overload: x[key], same rule. */
    Object arrayGet(Object target, Object key) { propertyGet(target, key) }

    /** PROPERTY_SET overload: m.key = v on a map the script made or was given; lists by index in range. */
    Object propertySet(Object target, Object key, Object value) {
        ScriptBudget.charge()
        if (target instanceof Map) {
            Map map = (Map) target
            if (!map.containsKey(key)) ScriptBudget.checkSize(map.size() + 1)
            map.put(key, value)
            return value
        }
        if (target instanceof List) {
            List list = (List) target
            int index = key instanceof Number ? ((Number) key).intValue() : -1
            if (index < 0 || index >= list.size()) {
                throw new ScriptLimitException("ACCESS", "There is no item ${key} in a list of ${list.size()}.".toString())
            }
            list.set(index, value)
            return value
        }
        if (target != null && target.getClass().isArray()) {
            int index = key instanceof Number ? ((Number) key).intValue() : -1
            if (index < 0 || index >= Array.getLength(target)) {
                throw new ScriptLimitException("ACCESS", "There is no item ${key} in a list of ${Array.getLength(target)}.".toString())
            }
            Array.set(target, index, value)
            return value
        }
        throw new ScriptLimitException("ACCESS", "Only a map or a list can be changed.")
    }

    /** ARRAY_SET overload: x[key] = v, same rule. */
    Object arraySet(Object target, Object key, Object value) { propertySet(target, key, value) }

    static List arrayItems(Object array) {
        int n = Array.getLength(array)
        List out = new ArrayList(n)
        for (int i = 0; i < n; i++) out.add(Array.get(array, i))
        return out
    }

    /** Caps what one operator may produce: text length, and whole numbers past 256 bits (x = x * x doubles each pass). */
    static Object guard(Object value) {
        if (value instanceof String) return ScriptBudget.checkString((String) value)
        if (value instanceof BigInteger && ((BigInteger) value).bitLength() > MAX_NUMBER_BITS) {
            throw new ScriptLimitException("NUMBER", "A number grew too large.")
        }
        if (value instanceof BigDecimal && ((BigDecimal) value).unscaledValue().bitLength() > MAX_NUMBER_BITS) {
            throw new ScriptLimitException("NUMBER", "A number grew too large.")
        }
        if (value instanceof BigDecimal) ScriptBudget.checkDecimal((BigDecimal) value)
        return value
    }
}
