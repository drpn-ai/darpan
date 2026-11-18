package darpan.config

import darpan.facade.common.TenantScopedFinder
import groovy.json.JsonSlurper
import jakarta.transaction.Status
import jakarta.transaction.Synchronization
import org.moqui.impl.context.ExecutionContextFactoryImpl
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Typed read of Darpan's operational settings (DAR-BE-304). Precedence: a DarpanSetting row, then the JVM system
 * property of the same key, then the catalog default. A row or property that does not parse or is outside the
 * catalog range is skipped (WARN once per key and value) and the next source is used, so a bad row never breaks a
 * run. A reader never throws for a known key; an unknown key or the wrong typed getter is a programming error.
 *
 * The table is tiny, so it is held as one in-memory snapshot, reloaded when older than REFRESH_MILLIS and expired
 * after the commit of every edit. A failed reload keeps the last snapshot.
 */
class DarpanSettings {
    private static final Logger logger = LoggerFactory.getLogger(DarpanSettings)

    static final String ENTITY = 'darpan.config.DarpanSetting'
    static final String CATALOG_RESOURCE = 'darpan/settings-catalog.json'
    static final long REFRESH_MILLIS = 30_000L
    private static final List<String> AREA_ORDER = ['Runs', 'Run health', 'Two-factor', 'Agent']
    private static final String WHY = 'instance settings: tenant-neutral tunables'

    /** Seam: row map by key ([settingValue, note, lastChangedByUserId, lastChangedDate]); null means no rows. */
    static Closure<Map<String, Map>> rowLoader = { -> loadRowsFromDatabase() }
    /** Seam: current time in milliseconds. */
    static Closure<Long> clock = { -> System.currentTimeMillis() }

    private static final class Snapshot {
        final Map<String, Map> rows
        final long loadedAt
        final boolean expired
        Snapshot(Map<String, Map> rows, long loadedAt, boolean expired) {
            this.rows = rows; this.loadedAt = loadedAt; this.expired = expired
        }
    }

    private static volatile Snapshot snapshot
    private static volatile Map<String, SettingDefinition> catalogCache
    private static volatile Closure<List<ClassLoader>> loaderSource
    private static final String CORE_KEY = 'darpan.reconciliation.automation.maxConcurrentExecutions'
    private static final AtomicBoolean reloading = new AtomicBoolean(false)
    private static final AtomicLong expireCount = new AtomicLong(0)
    private static final Set<String> warned = ConcurrentHashMap.newKeySet()

    // ------------------------------------------------------------------ typed reads

    static int intValue(String key) { return (int) read(key, 'INT') }
    static boolean booleanValue(String key) { return (boolean) read(key, 'BOOLEAN') }
    static BigDecimal decimalValue(String key) { return (BigDecimal) read(key, 'DECIMAL') }
    static List<Integer> intListValue(String key) { return (List<Integer>) read(key, 'INT_LIST') }

    private static Object read(String key, String type) {
        SettingDefinition sd = definition(key)
        if (sd.type != type) {
            throw new IllegalArgumentException("Setting ${key} is ${sd.type}, not ${type}".toString())
        }
        try {
            return resolve(sd).value
        } catch (Throwable t) {
            logger.warn("Setting {} read failed, using the catalog default: {}", key, t.toString())
            return parse(sd, sd.defaultValue)
        }
    }

    // ------------------------------------------------------------------ catalog

    /** Every settings-catalog.json on the class path (one per component jar), by key in file order. */
    static Map<String, SettingDefinition> catalog() {
        Map<String, SettingDefinition> cached = catalogCache
        if (cached != null) return cached
        synchronized (DarpanSettings) {
            if (catalogCache != null) return catalogCache
            Map<String, SettingDefinition> loaded = loadCatalog()
            // A catalog without the core darpan file (a thread that cannot see it) is served but never kept: the next read retries.
            if (loaded.containsKey(CORE_KEY)) catalogCache = loaded
            else warnOnce('nocore', "Settings catalog has no core darpan entries ({} found); will retry on the next read", loaded.size())
            return loaded
        }
    }

    /** The loaders that can see catalog files: this class's own (the component jar) and the thread's context loader. */
    private static List<ClassLoader> catalogLoaders() {
        if (loaderSource != null) return loaderSource.call()
        List<ClassLoader> out = [DarpanSettings.classLoader]
        ClassLoader ctx = Thread.currentThread().contextClassLoader
        if (ctx != null && !out.contains(ctx)) out.add(ctx)
        return out
    }

    private static Map<String, SettingDefinition> loadCatalog() {
        Map<String, SettingDefinition> all = new LinkedHashMap<>()
        Map<String, String> from = [:]
        try {
            Set<String> seen = new HashSet<>()
            List<URL> found = new ArrayList<>()
            for (ClassLoader cl : catalogLoaders()) {
                Enumeration<URL> e = cl.getResources(CATALOG_RESOURCE)
                while (e.hasMoreElements()) {
                    URL u = e.nextElement()
                    if (seen.add(u.toString())) found.add(u)
                }
            }
            for (URL url : found) {
                String source = null
                try {
                    source = url.toString()
                    Map<String, SettingDefinition> part = url.openStream().withCloseable { parseCatalog(it.getText('UTF-8'), source) }
                    part.each { String k, SettingDefinition d ->
                        if (all.containsKey(k)) {
                            warnOnce('dup|' + k + '|' + source, "Setting {} is in two catalogs ({} and {}); keeping the first", k, from[k], source)
                        } else {
                            all.put(k, d)
                            from.put(k, source)
                        }
                    }
                } catch (Exception e) {
                    warnOnce('bad|' + source, "Skipping unreadable settings catalog {}: {}", source, e.toString())
                }
            }
        } catch (Exception e) {
            warnOnce('scan', "Could not scan for settings catalogs: {}", e.toString())
        }
        return Collections.unmodifiableMap(all)
    }

    /** Parses one catalog file (throws when the file is not valid JSON); an entry that is malformed, repeated or has an invalid default is skipped with a WARN. */
    static Map<String, SettingDefinition> parseCatalog(String json, String source) {
        Map<String, SettingDefinition> out = new LinkedHashMap<>()
        Object parsed = new JsonSlurper().parseText(json)
        for (Object o : (parsed.settings as List)) {
            Map m = (Map) o
            SettingDefinition d
            try {
                d = new SettingDefinition(
                        key: m.key, type: m.type, area: m.area, unit: m.unit, description: m.description,
                        defaultValue: m.defaultValue == null ? null : m.defaultValue.toString(),
                        min: m.min == null ? null : new BigDecimal(m.min.toString()),
                        max: m.max == null ? null : new BigDecimal(m.max.toString()),
                        maxItems: m.maxItems == null ? null : ((Number) m.maxItems).intValue(),
                        lessThan: m.lessThan)
            } catch (Exception e) {
                warnOnce('entry|' + source + '|' + m.key, "Skipping malformed setting {} in {}: {}", m.key, source, e.toString())
                continue
            }
            if (!d.key || !(d.type in ['INT', 'BOOLEAN', 'DECIMAL', 'INT_LIST'])) {
                warnOnce('entry|' + source + '|' + m.key, "Skipping malformed setting {} in {}", m.key, source)
            } else if (out.containsKey(d.key)) {
                warnOnce('entry|' + source + '|' + d.key, "Setting {} twice in {}; keeping the first", d.key, source)
            } else if (parse(d, d.defaultValue) == null) {
                warnOnce('entry|' + source + '|' + d.key, "Skipping setting {} in {}: its default '{}' is not valid", d.key, source, d.defaultValue)
            } else {
                out.put(d.key, d)
            }
        }
        return out
    }

    private static final int WARNED_CAP = 1000

    /** WARN once per id; past WARNED_CAP remembered ids it still warns, without remembering. */
    private static void warnOnce(String id, String format, Object... args) {
        if (warned.size() >= WARNED_CAP) {
            logger.warn(format, args)
        } else if (warned.add(id)) {
            logger.warn(format, args)
        }
    }

    private static SettingDefinition definition(String key) {
        SettingDefinition d = key == null ? null : catalog().get(key)
        if (d == null) throw new IllegalArgumentException("Unknown setting: ${key}".toString())
        return d
    }

    // ------------------------------------------------------------------ parsing

    /** The typed value of text under a definition, or null when it does not parse or is outside the range. */
    static Object parse(SettingDefinition sd, String text) {
        if (text == null) return null
        String t = text.trim()
        if (t.isEmpty()) return null
        try {
            switch (sd.type) {
                case 'INT':
                    BigDecimal i = new BigDecimal(t)
                    if (i.scale() > 0 && i.stripTrailingZeros().scale() > 0) return null
                    return inRange(sd, i) ? i.intValueExact() : null
                case 'DECIMAL':
                    BigDecimal d = new BigDecimal(t)
                    return inRange(sd, d) ? d : null
                case 'BOOLEAN':
                    if (t.equalsIgnoreCase('true')) return Boolean.TRUE
                    if (t.equalsIgnoreCase('false')) return Boolean.FALSE
                    return null
                case 'INT_LIST':
                    List<Integer> list = []
                    for (String part : t.split(',', -1)) {
                        BigDecimal e = new BigDecimal(part.trim())
                        if (e.stripTrailingZeros().scale() > 0 || !inRange(sd, e)) return null
                        list.add(e.intValueExact())
                    }
                    if (list.isEmpty() || (sd.maxItems != null && list.size() > sd.maxItems)) return null
                    if (list.toSet().size() != list.size()) return null
                    return Collections.unmodifiableList(list)
                default:
                    return null
            }
        } catch (NumberFormatException | ArithmeticException ignored) {
            return null
        }
    }

    private static boolean inRange(SettingDefinition sd, BigDecimal v) {
        return (sd.min == null || v >= sd.min) && (sd.max == null || v <= sd.max)
    }

    // ------------------------------------------------------------------ resolution

    private static Map resolve(SettingDefinition sd) {
        String rowText = rows().get(sd.key)?.settingValue?.toString()
        Object value = acceptable(sd, 'row', rowText)
        if (value != null) return [value: value, source: 'ROW', text: rowText]
        String propText = System.getProperty(sd.key)
        value = acceptable(sd, 'property', propText)
        if (value != null) return [value: value, source: 'PROPERTY', text: propText]
        return [value: parse(sd, sd.defaultValue), source: 'DEFAULT', text: sd.defaultValue]
    }

    private static Object acceptable(SettingDefinition sd, String origin, String text) {
        if (text == null) return null
        Object v = parse(sd, text)
        if (v == null) {
            String shown = text.length() > 200 ? text.substring(0, 200) + '...' : text
            warnOnce(sd.key + '|' + origin + '|' + shown, "Setting {}: ignoring {} value '{}' (not a valid {} in range); using the next source", sd.key, origin, shown, sd.type)
        }
        return v
    }

    private static String render(Object value) {
        return value instanceof List ? ((List) value).join(',') : value.toString()
    }

    // ------------------------------------------------------------------ snapshot

    private static Map<String, Map> rows() {
        Snapshot snap = snapshot
        long now = clock.call()
        if (snap == null) {
            synchronized (DarpanSettings) {
                if (snapshot == null) reload(now, null)
                return snapshot.rows
            }
        }
        if ((snap.expired || now - snap.loadedAt >= REFRESH_MILLIS) && reloading.compareAndSet(false, true)) {
            try { reload(now, snap) } finally { reloading.set(false) }
        }
        return snapshot.rows
    }

    private static void reload(long now, Snapshot previous) {
        long seen = expireCount.get()
        try {
            Map<String, Map> loaded = rowLoader.call()
            Map<String, Map> copy = new HashMap<>()
            loaded?.each { String k, Object v -> copy.put(k, v instanceof Map ? (Map) v : [settingValue: v]) }
            snapshot = new Snapshot(Collections.unmodifiableMap(copy), now, expireCount.get() != seen)
        } catch (Throwable t) {
            logger.warn("Settings reload failed, keeping the last snapshot: {}", t.toString())
            Map<String, Map> keep = previous != null ? previous.rows : Collections.<String, Map> emptyMap()
            snapshot = new Snapshot(keep, now, false)
        }
    }

    /** Makes the next read reload. */
    static void expire() {
        expireCount.incrementAndGet()
        Snapshot snap = snapshot
        if (snap != null) snapshot = new Snapshot(snap.rows, snap.loadedAt, true)
    }

    /** Expires the snapshot once the surrounding transaction has completed (now when there is none). */
    static void expireAfterCommit(def ec) {
        if (ec != null && ec.transaction.isTransactionInPlace()) {
            ec.transaction.registerSynchronization(new Synchronization() {
                @Override void beforeCompletion() { }
                @Override void afterCompletion(int status) { if (status == Status.STATUS_COMMITTED) expire() }
            })
        } else {
            expire()
        }
    }

    private static Map<String, Map> loadRowsFromDatabase() {
        def ecfi = org.moqui.Moqui.executionContextFactory
        if (ecfi == null) return null
        boolean created = false
        def ec = ((ExecutionContextFactoryImpl) ecfi).activeContext.get()
        if (ec == null) {
            ec = ecfi.getExecutionContext()
            created = true
        }
        try {
            Map<String, Map> out = [:]
            TenantScopedFinder.findGlobalUnscopedPerRequest(ec, ENTITY, WHY).useCache(false).list().each { row ->
                out.put(row.settingKey as String, [
                        settingValue: row.settingValue, note: row.note,
                        lastChangedByUserId: row.lastChangedByUserId, lastChangedDate: row.lastChangedDate])
            }
            return out
        } finally {
            if (created) ((ExecutionContextFactoryImpl) ecfi).destroyActiveExecutionContext()
        }
    }

    // ------------------------------------------------------------------ describe and validate

    static Map describe(String key) {
        SettingDefinition d = definition(key)
        Map r = resolve(d)
        Map row = rows().get(key)
        return [
                key: d.key, type: d.type, area: d.area, unit: d.unit, description: d.description,
                defaultValue: d.defaultValue, min: d.min, max: d.max, maxItems: d.maxItems, lessThan: d.lessThan,
                propertyValue: System.getProperty(key), rowValue: row?.settingValue?.toString(),
                effectiveValue: render(r.value), source: r.source,
                note: row?.note, lastChangedByUserId: row?.lastChangedByUserId, lastChangedDate: row?.lastChangedDate]
    }

    /** Every setting, ordered by area then catalog order. */
    static List<Map> describeAll() {
        List<String> keys = new ArrayList<>(catalog().keySet())
        List<String> indexed = keys.collect { it }
        keys.sort(false) { String a, String b ->
            int c = areaRank(catalog().get(a).area) <=> areaRank(catalog().get(b).area)
            return c != 0 ? c : indexed.indexOf(a) <=> indexed.indexOf(b)
        }
        return keys.collect { describe(it) }
    }

    private static int areaRank(String area) {
        int i = AREA_ORDER.indexOf(area)
        return i < 0 ? AREA_ORDER.size() : i
    }

    /** Null when text is a valid value for the key; otherwise why not. */
    static String problem(String key, String text) {
        SettingDefinition d = key == null ? null : catalog().get(key)
        if (d == null) return "Unknown setting: ${key}".toString()
        Object v = parse(d, text)
        if (v == null) return "${key}: '${text}' is not ${expectation(d)}".toString()
        if (d.lessThan != null) {
            Object other = effectiveOf(d.lessThan)
            if (other != null && !(((Number) v) < ((Number) other))) {
                return "${key} must stay below ${d.lessThan} (now ${other})".toString()
            }
        }
        for (SettingDefinition above : catalog().values().findAll { it.lessThan == key }) {
            Object low = effectiveOf(above.key)
            if (low != null && !(((Number) low) < ((Number) v))) {
                return "${key} must stay above ${above.key} (now ${low})".toString()
            }
        }
        return null
    }

    private static Object effectiveOf(String key) {
        SettingDefinition d = catalog().get(key)
        return d == null ? null : resolve(d).value
    }

    private static String expectation(SettingDefinition d) {
        switch (d.type) {
            case 'BOOLEAN': return 'true or false'
            case 'INT_LIST':
                return "a comma-separated list of ${d.maxItems != null ? "1 to ${d.maxItems}" : 'some'} different whole numbers, each ${d.min} to ${d.max}".toString()
            case 'DECIMAL': return "a number from ${d.min} to ${d.max}".toString()
            default: return "a whole number from ${d.min} to ${d.max}".toString()
        }
    }

    // ------------------------------------------------------------------ test support

    static void resetForTest() {
        rowLoader = { -> loadRowsFromDatabase() }
        clock = { -> System.currentTimeMillis() }
        snapshot = null
        catalogCache = null
        loaderSource = null
        warned.clear()
        reloading.set(false)
    }
}
