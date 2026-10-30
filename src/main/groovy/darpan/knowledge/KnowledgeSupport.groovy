package darpan.knowledge

import darpan.facade.common.TenantAccessSupport
import darpan.facade.common.TenantScopedFinder
import jakarta.transaction.Synchronization
import org.moqui.entity.EntityCondition
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Date
import java.sql.Timestamp
import java.time.LocalDate
import java.util.stream.Collectors
import java.util.stream.Stream

/**
 * DAR-BE-161. The knowledge base wired to Moqui: one product index (files under the configured path, loaded once per
 * JVM) and one index per tenant (that tenant's ACTIVE KnowledgeEntry rows, built on first search). A search opens the
 * product index and the named tenant's index and nothing else, so one tenant's entry can never reach another.
 *
 * Every tenant read names the tenant as an explicit companyUserGroupId condition (the admin fence carries no tenant
 * filter). A product file that holds a private shape or any tenant's identifier is dropped; a tenant write that names
 * another tenant's identifier or holds customer personal data is refused. Both are logged by origin and reason kind
 * only, never the matched value.
 */
class KnowledgeSupport {

    static final String PRODUCT_PATH_PROPERTY = 'darpan.knowledge.productPath'
    static final String ALLOW_DIRECT_WRITES_PROPERTY = 'darpan.knowledge.allowDirectWrites'
    static final String ENTRY_ENTITY = 'darpan.knowledge.KnowledgeEntry'
    static final String ACTIVE = 'ACTIVE'
    static final String RETIRED = 'RETIRED'
    static final int TENANT_INDEX_CACHE_SIZE = 16
    /** Shapes that are a tenant's customers' personal data; order and return ids are allowed in the tenant tier. */
    static final Set<String> CUSTOMER_DATA_SHAPES = ['shopifyGid', 'email', 'phone'] as Set<String>

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeSupport)

    /** The connection fields whose URLs name a tenant's hosts (entity -> URL field). */
    private static final Map<String, String> CONNECTION_URL_FIELDS = [
            'darpan.hotwax.HotWaxOmsRestSourceConfig': 'baseUrl',
            'darpan.shopify.ShopifyAuthConfig'       : 'shopApiUrl',
            'darpan.reconciliation.NsRestletConfig'  : 'endpointUrl',
            'darpan.reconciliation.NsAuthConfig'     : 'tokenUrl',
    ]

    // ---------------------------------------------------------------- product tier state

    private static final Object PRODUCT_LOCK = new Object()
    private static volatile ProductTier product

    private static class ProductTier {
        String path
        KnowledgeIndex index
        List<Map> entries = []
    }

    // ---------------------------------------------------------------- tenant tier state (guarded by TENANT_LOCK)

    private static final Object TENANT_LOCK = new Object()
    private static final Map<String, Long> GENERATIONS = new HashMap<String, Long>()
    /** Access-ordered, so the first key is the least recently searched; cacheTenantIndex evicts from the front. */
    private static final LinkedHashMap<String, KnowledgeIndex> TENANT_INDEXES =
            new LinkedHashMap<String, KnowledgeIndex>(TENANT_INDEX_CACHE_SIZE, 0.75f, true)

    // ---------------------------------------------------------------- reads

    /**
     * Product hits plus, when a tenant is named, that tenant's hits, merged by score. tenantTier is none (no tenant
     * named), deactivated (no tenant hits) or loaded; productTier is absent when no product entry is loaded.
     */
    static Map search(def ec, String tenantId, String words, String system, String kind, Integer limit) {
        int cap = (limit == null || limit < 1) ? KnowledgeIndex.DEFAULT_LIMIT : Math.min(limit, KnowledgeIndex.MAX_LIMIT)
        LocalDate today = LocalDate.now()
        List<Map> hits = []
        ProductTier tier = productTier(ec)
        if (tier.index != null) hits.addAll(tier.index.search(words, system, kind, cap, today))

        String tenantTier = 'none'
        String tenant = tenantId?.trim()
        if (tenant) {
            if (isDeactivated(ec, tenant)) {
                tenantTier = 'deactivated'
                dropTenantIndex(tenant)
            } else {
                tenantTier = 'loaded'
                hits.addAll(withTenantIndex(ec, tenant) { KnowledgeIndex index -> index.search(words, system, kind, cap, today) })
            }
        }
        hits.sort { Map a, Map b -> Float.compare((b.score ?: 0f) as float, (a.score ?: 0f) as float) }
        List<Map> out = hits.take(cap).collect { Map hit -> hit + [reviewBy: hit.reviewBy?.toString()] }
        return [hits: out, productTier: tier.index != null ? 'loaded' : 'absent', tenantTier: tenantTier]
    }

    /** The entry by key: the product tier first, then the named tenant's ACTIVE row. Null when neither holds it. */
    static Map entry(def ec, String tenantId, String key) {
        if (!key?.trim()) return null
        Map found = productTier(ec).index?.get(key.trim())
        if (found != null) return publicEntry(found)
        String tenant = tenantId?.trim()
        if (!tenant || isDeactivated(ec, tenant)) return null
        def row = firstRow(tenantRows(ec, tenant).condition('entryKey', key.trim()).condition('statusId', ACTIVE))
        return row == null ? null : publicEntry(rowToEntry(row))
    }

    /** Entries due for a re-read (reviewBy on or before today + withinDays, expired ones included): product, plus the tenant's. */
    static List<Map> forReview(def ec, String tenantId, int withinDays) {
        LocalDate due = LocalDate.now().plusDays(Math.max(withinDays, 0))
        List<Map> entries = productTier(ec).entries.findAll { Map e -> !((LocalDate) e.reviewBy).isAfter(due) }
        String tenant = tenantId?.trim()
        if (tenant && !isDeactivated(ec, tenant)) {
            tenantRows(ec, tenant).condition('statusId', ACTIVE)
                    .condition('reviewByDate', EntityCondition.LESS_THAN_EQUAL_TO, Date.valueOf(due))
                    .list().each { entries.add(rowToEntry(it)) }
        }
        return entries.sort { Map a, Map b -> ((LocalDate) a.reviewBy) <=> ((LocalDate) b.reviewBy) ?: (a.key as String) <=> (b.key as String) }
                .collect { Map e -> [key: e.key, tier: e.tier, title: e.title, source: e.source, owner: e.owner, reviewBy: e.reviewBy.toString()] }
    }

    /**
     * Every tenant's identifiers, for the leak check: [value, kind, caseSensitive, tenantId]. Company groups give
     * tenantId (case-sensitive) and label; connection URLs give host and, for NetSuite, account. Cross-tenant on
     * purpose, so only the encrypted-free URL fields are selected.
     */
    static List<Map> tenantIdentifiers(def ec) {
        Set<String> seen = new HashSet<String>()
        List<Map> out = []
        Closure addId = { String value, String kind, boolean caseSensitive, String tenantId ->
            String v = value?.trim()
            if (!v || !seen.add("${kind}|${tenantId}|${caseSensitive ? v : v.toLowerCase()}".toString())) return
            out.add([value: v, kind: kind, caseSensitive: caseSensitive, tenantId: tenantId])
        }
        TenantScopedFinder.findGlobalUnscoped(ec, 'moqui.security.UserGroup', 'knowledge leak check: every tenant id and label')
                .condition('groupTypeEnumId', TenantAccessSupport.DARPAN_COMPANY_GROUP_TYPE_ENUM_ID)
                .selectField('userGroupId').selectField('description').useCache(false).list().each { group ->
            String tenant = group.userGroupId as String
            addId(tenant, 'tenantId', true, tenant)
            addId(group.description as String, 'label', false, tenant)
        }
        CONNECTION_URL_FIELDS.each { String entityName, String field ->
            if (!ec.entity.isEntityDefined(entityName)) return
            TenantScopedFinder.findGlobalUnscoped(ec, entityName, 'knowledge leak check: every tenant connection host')
                    .selectField('companyUserGroupId').selectField(field).useCache(false).list().each { row ->
                String tenant = row.companyUserGroupId as String
                String host = hostOf(row.get(field) as String)
                if (!host) return
                addId(host, 'host', false, tenant)
                netsuiteAccounts(host).each { String account -> addId(account, 'account', false, tenant) }
            }
        }
        return out
    }

    // ---------------------------------------------------------------- writes (local staff only)

    /**
     * Upserts one tenant entry by (tenant, key). Refused, with nothing written, when direct writes are off, the text is
     * not a tenant entry, names another tenant's identifier, or holds customer personal data.
     */
    static Map store(def ec, String tenantId, String markdown) {
        String tenant = tenantId?.trim()
        if (!writesAllowed(ec) || !tenant) return null
        Map parsed
        try {
            parsed = KnowledgeEntryFile.parse(markdown, 'tenant:' + tenant)
        } catch (IllegalArgumentException e) {
            ec.message.addError(e.message)
            return null
        }
        String key = parsed.key as String
        if (parsed.tier != 'tenant') {
            ec.message.addError("Entry ${key} has tier ${parsed.tier}; only tier tenant entries are stored here.".toString())
            return null
        }
        List<String> reasons = []
        KnowledgeLeakCheck.shapeHits(markdown).findAll { CUSTOMER_DATA_SHAPES.contains(it) }.each { reasons.add("customer data (${it})".toString()) }
        List<Map> others = tenantIdentifiers(ec).findAll { Map id -> id.tenantId != tenant }
        KnowledgeLeakCheck.identifierHits(markdown, others, null).each { reasons.add("another tenant's ${it}".toString()) }
        if (reasons) {
            logger.warn("knowledge entry tenant:{}/{} refused: {}", tenant, key, reasons.join(', '))
            ec.message.addError("Entry ${key} was not stored: it holds ${reasons.join(', ')}.".toString())
            return null
        }

        Map fields = [companyUserGroupId: tenant, entryKey: key, title: parsed.title, body: parsed.body,
                      systems: (parsed.systems as List).join(','), entryKind: parsed.kind ?: null, tags: (parsed.tags as List).join(','),
                      sourceText: parsed.source, ownerName: parsed.owner, reviewByDate: Date.valueOf((LocalDate) parsed.reviewBy),
                      supersedesKey: parsed.supersedes ?: null, statusId: ACTIVE]
        def existing = firstRow(tenantRows(ec, tenant).condition('entryKey', key))
        boolean created = existing == null
        if (created) {
            ec.entity.makeValue(ENTRY_ENTITY).setAll(fields)
                    .set('knowledgeEntryId', TenantScopedFinder.mintId(ec, ENTRY_ENTITY, 'knowledgeEntryId', 'KE_'))
                    .set('createdByUserId', ec.user.userId).set('createdDate', new Timestamp(System.currentTimeMillis())).create()
        } else {
            def row = TenantScopedFinder.findOneOwnedByTenant(ec, ENTRY_ENTITY, 'knowledgeEntryId', existing.knowledgeEntryId, tenant,
                    'knowledge store: the row this tenant already holds under the key')
            if (row == null) { ec.message.addError("Entry ${key} was not found.".toString()); return null }
            row.setAll(fields).update()
        }
        invalidateAfterWrite(ec, tenant)
        return [entry: publicEntry(parsed + [tier: 'tenant']), created: created]
    }

    /** Retires the tenant's entry with this key (kept for history, never searched). Same write gate as store. */
    static boolean retire(def ec, String tenantId, String key) {
        String tenant = tenantId?.trim()
        if (!writesAllowed(ec) || !tenant) return false
        def found = key?.trim() ? firstRow(tenantRows(ec, tenant).condition('entryKey', key.trim())) : null
        def row = found == null ? null : TenantScopedFinder.findOneOwnedByTenant(ec, ENTRY_ENTITY, 'knowledgeEntryId',
                found.knowledgeEntryId, tenant, 'knowledge retire: the row this tenant holds under the key')
        if (row == null) {
            ec.message.addError("Entry ${key ?: '(missing)'} was not found.".toString())
            return false
        }
        row.set('statusId', RETIRED).update()
        invalidateAfterWrite(ec, tenant)
        return true
    }

    /** Drops the tenant's cached index; the next search rebuilds it from the rows. */
    static void invalidate(String tenantId) {
        if (!tenantId) return
        synchronized (TENANT_LOCK) {
            GENERATIONS.put(tenantId, (GENERATIONS.get(tenantId) ?: 0L) + 1L)
            TENANT_INDEXES.remove(tenantId)?.close()
        }
    }

    // ---------------------------------------------------------------- product loading

    private static ProductTier productTier(def ec) {
        String path = System.getProperty(PRODUCT_PATH_PROPERTY)?.trim() ?: null
        ProductTier current = product
        if (current != null && current.path == path) return current
        synchronized (PRODUCT_LOCK) {
            if (product != null && product.path == path) return product
            product = loadProduct(ec, path)
            return product
        }
    }

    private static ProductTier loadProduct(def ec, String path) {
        ProductTier tier = new ProductTier(path: path)
        if (!path) {
            logger.info("knowledge product tier absent: {} is not set", PRODUCT_PATH_PROPERTY)
            return tier
        }
        Path root = Paths.get(path)
        Path productDir = root.resolve('product')
        if (!Files.isDirectory(productDir) || !Files.isReadable(productDir)) {
            logger.warn("knowledge product tier absent: {} has no readable product directory", path)
            return tier
        }
        List<Path> files
        try {
            Stream<Path> walk = Files.walk(productDir)
            try {
                files = walk.filter { Path p -> Files.isRegularFile(p) && p.fileName.toString().endsWith('.md') }.sorted()
                        .collect(Collectors.toList())
            } finally {
                walk.close()
            }
        } catch (IOException | UncheckedIOException e) {
            logger.warn("knowledge product tier absent: {} could not be listed ({})", path, e.class.simpleName)
            return tier
        }

        List<Map> identifiers = tenantIdentifiers(ec)
        Set<String> keys = new HashSet<String>()
        for (Path file in files) {
            String origin = root.relativize(file).toString()
            List<String> reasons = []
            Map parsed = null
            String text = null
            try {
                text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
            } catch (IOException ignored) {
                reasons.add('unreadable')
            }
            if (text != null) {
                try {
                    parsed = KnowledgeEntryFile.parse(text, origin)
                } catch (IllegalArgumentException ignored) {
                    reasons.add('unparseable')   // the parse message can quote the file, so only its kind is logged
                }
            }
            if (parsed != null) {
                if (parsed.tier != 'product') reasons.add("tier ${parsed.tier}".toString())
                KnowledgeLeakCheck.shapeHits(text).each { reasons.add("shape ${it}".toString()) }
                KnowledgeLeakCheck.identifierHits(text, identifiers, null).each { reasons.add("tenant ${it}".toString()) }
                if (!reasons && !keys.add(parsed.key as String)) reasons.add('duplicate key')
            }
            if (reasons) {
                logger.warn("knowledge entry {} dropped: {}", origin, reasons.join(', '))
                continue
            }
            tier.entries.add(parsed)
        }
        if (tier.entries.isEmpty()) {
            logger.warn("knowledge product tier absent: no entry under {} loaded", productDir)
            return tier
        }
        tier.index = KnowledgeIndex.build(tier.entries)
        logger.info("knowledge product tier loaded: {} entries, {} dropped", tier.entries.size(), files.size() - tier.entries.size())
        return tier
    }

    // ---------------------------------------------------------------- tenant indexes

    private static <T> T withTenantIndex(def ec, String tenant, Closure<T> work) {
        long generation
        synchronized (TENANT_LOCK) {
            KnowledgeIndex cached = TENANT_INDEXES.get(tenant)
            if (cached != null) return work.call(cached)
            generation = GENERATIONS.get(tenant) ?: 0L
        }
        List<Map> entries = tenantRows(ec, tenant).condition('statusId', ACTIVE).list().collect { rowToEntry(it) }
        KnowledgeIndex built = KnowledgeIndex.build(entries)
        synchronized (TENANT_LOCK) {
            KnowledgeIndex cached = TENANT_INDEXES.get(tenant)
            if (cached != null) {
                built.close()
                return work.call(cached)
            }
            if ((GENERATIONS.get(tenant) ?: 0L) == generation) {
                TENANT_INDEXES.put(tenant, built)
                while (TENANT_INDEXES.size() > TENANT_INDEX_CACHE_SIZE) {
                    Iterator<Map.Entry<String, KnowledgeIndex>> eldest = TENANT_INDEXES.entrySet().iterator()
                    eldest.next().value.close()
                    eldest.remove()
                }
                return work.call(built)
            }
        }
        // A write landed while this index was built: answer from it once, never cache it.
        try {
            return work.call(built)
        } finally {
            built.close()
        }
    }

    private static void dropTenantIndex(String tenant) {
        synchronized (TENANT_LOCK) {
            TENANT_INDEXES.remove(tenant)?.close()
        }
    }

    /** Invalidates now and again once the write's transaction completes, so no search caches the pre-commit rows. */
    private static void invalidateAfterWrite(def ec, String tenant) {
        invalidate(tenant)
        if (ec.transaction.isTransactionInPlace()) {
            ec.transaction.registerSynchronization(new Synchronization() {
                @Override void beforeCompletion() { }
                @Override void afterCompletion(int status) { invalidate(tenant) }
            })
        }
    }

    // ---------------------------------------------------------------- helpers

    /** The tenant's rows, always with an explicit tenant condition (the admin fence carries no tenant filter). */
    private static def tenantRows(def ec, String tenant) {
        return ec.entity.find(ENTRY_ENTITY).condition('companyUserGroupId', tenant).useCache(false)
    }

    /** The first row of a find, by list() (never one(): the key here is not the primary key). */
    private static def firstRow(def find) {
        def rows = find.list()
        return rows.isEmpty() ? null : rows.get(0)
    }

    private static boolean isDeactivated(def ec, String tenant) {
        def setting = TenantScopedFinder.findOneOwnedByTenant(ec, 'darpan.auth.TenantSetting', 'companyUserGroupId', tenant, tenant,
                'knowledge: is the tenant deactivated')
        return setting?.disabled == 'Y'
    }

    private static boolean writesAllowed(def ec) {
        if (System.getProperty(ALLOW_DIRECT_WRITES_PROPERTY) == 'true') return true
        ec.message.addError("Knowledge entries are written on a local instance only (${ALLOW_DIRECT_WRITES_PROPERTY} is not true here).".toString())
        return false
    }

    private static Map rowToEntry(def row) {
        return [key       : row.entryKey, tier: 'tenant', systems: csv(row.systems as String), kind: row.entryKind ?: '',
                tags      : csv(row.tags as String), source: row.sourceText, owner: row.ownerName ?: KnowledgeEntryFile.DEFAULT_OWNER,
                reviewBy  : ((Date) row.reviewByDate).toLocalDate(), supersedes: row.supersedesKey ?: '', title: row.title,
                body      : row.body ?: ''] as Map
    }

    /** The entry as services answer it: reviewBy as yyyy-MM-dd. */
    private static Map publicEntry(Map entry) {
        return [key: entry.key, tier: entry.tier, systems: entry.systems, kind: entry.kind, tags: entry.tags, source: entry.source,
                owner: entry.owner, reviewBy: entry.reviewBy?.toString(), supersedes: entry.supersedes, title: entry.title, body: entry.body]
    }

    private static List<String> csv(String value) {
        return (value ?: '').split(',').collect { it.trim() }.findAll { it } as List<String>
    }

    /** The lower-cased host of a URL (scheme optional); null for blank, unparseable or dot-less hosts such as localhost. */
    static String hostOf(String url) {
        String text = url?.trim()
        if (!text) return null
        String host
        try {
            host = new URI(text.contains('://') ? text : 'https://' + text).host
        } catch (URISyntaxException ignored) {
            return null
        }
        host = host?.toLowerCase(Locale.ROOT)
        return host && host.contains('.') ? host : null
    }

    /**
     * The NetSuite account id a host names (1234567-sb1.restlets.api.netsuite.com): the label as written, its
     * 1234567_SB1 form, and the numeric production account.
     */
    static List<String> netsuiteAccounts(String host) {
        if (!host?.endsWith('.netsuite.com')) return []
        String label = host.substring(0, host.indexOf('.'))
        if (!(label ==~ /\d{4,}(-[a-z]+\d*)?/)) return []
        Set<String> out = new LinkedHashSet<String>([label, label.replace('-', '_').toUpperCase(Locale.ROOT), label.replaceAll(/-.*$/, '')])
        return new ArrayList<String>(out)
    }
}
