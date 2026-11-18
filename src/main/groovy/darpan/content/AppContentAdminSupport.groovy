package darpan.content

import groovy.transform.CompileDynamic

/**
 * DAR-BE-313: the computation behind admin.AppContentAdminServices. Everything here only reads and decides; the services
 * (XML actions) do the entity writes and the audit rows. The caller has checked super admin, so every entity read runs as
 * that super admin under the admin.* artifact's inherited authz, never disableAuthz.
 *
 * A prepare* result is {error} (nothing to say but that), or {key, problems, whereShown, fields, detail}: problems is empty
 * when the change is valid, fields is the columns to store (update: only those that change), detail is the audit line.
 */
@CompileDynamic
class AppContentAdminSupport {
    static final String ENTRY = "darpan.content.AppContentEntry"
    static final int DETAIL_VALUE_MAX = 60
    static final List<String> EDITABLE = ["title", "body", "aliases", "hidden", "matchPattern", "routeName", "conditionKind", "anchorTestId", "sequence"]

    static Map limits() {
        return [renderedMax: AppContentRules.MAX_ENTRY, hintMax: AppContentRules.MAX_HINT, aliasMax: AppContentRules.MAX_ALIAS,
                aliasesMax: AppContentRules.MAX_ALIASES, patternMax: AppContentRules.MAX_PATTERN, wildcardsMax: AppContentRules.MAX_STARS]
    }

    static Map vocabulary(def ec) {
        Map<String, List<String>> anchors = [:]
        ec.entity.find("darpan.content.AppRouteAnchor").orderBy("routeName,anchorTestId").list().each { def a ->
            anchors.get(a.routeName, new ArrayList<String>()) << (a.anchorTestId as String)
        }
        List<Map> routes = ec.entity.find("darpan.content.AppRoute").orderBy("routeName").list().collect { def r ->
            [routeName: r.routeName, routePath: r.routePath, anchors: anchors.get(r.routeName as String) ?: []] as Map
        }
        return [routes: routes, conditionKinds: ["ALWAYS", "ANCHOR_PRESENT"], limits: limits()]
    }

    private static String str(def v) { return v == null ? "" : v.toString() }

    private static String clip(def v) {
        String s = v == null || str(v).isEmpty() ? "-" : str(v)
        return s.length() > DETAIL_VALUE_MAX ? s.take(DETAIL_VALUE_MAX) + "..." : s
    }

    private static String joinAliases(def aliases) {
        List<String> n = AppContentRules.normalizedAliases(aliases)
        return n.isEmpty() ? null : n.join("|")
    }

    private static Integer sequenceOf(def v) {
        if (v == null || str(v).trim().isEmpty()) return null
        try { return Integer.valueOf(str(v).trim()) } catch (NumberFormatException ignored) { return null }
    }

    /** "Y" for a true-ish value, otherwise "N". */
    private static String flag(def v) { return (v == true || str(v).toLowerCase() in ["y", "true"]) ? "Y" : "N" }

    // ---- where it is shown ------------------------------------------------------------------------------------

    /** The preview of a hint's place: its route, its condition and its position among the route's other served hints. */
    static String hintWhereShown(def ec, String routeName, String conditionKind, String anchor, Integer sequence, String selfKey) {
        def route = routeName ? ec.entity.find("darpan.content.AppRoute").condition("routeName", routeName).one() : null
        String path = route != null ? route.routePath : routeName
        String when = conditionKind == AppContentRules.ANCHOR_PRESENT ? "when ${anchor} is on the page" : "always"
        String mark = AppContentRules.currentSeedMark(ec)
        List<Map> others = []
        ec.entity.find(ENTRY).condition("contentKind", AppContentRules.HINT).condition("routeName", routeName).list().each { def r ->
            Map m = r.getMap()
            if (str(m.contentKey) == selfKey || AppContentRules.retired(m, mark) || str(m.hidden) == "Y" || !AppContentRules.servable(m)) return
            others << [key: m.contentKey, seq: (m.sequence != null ? m.sequence : m.defaultSequence) as Integer, text: str(m.body ?: m.defaultBody)]
        }
        others.sort { a, b -> (a.seq ?: 0) <=> (b.seq ?: 0) ?: str(a.key) <=> str(b.key) }
        int before = sequence == null ? others.size() : others.count { (it.seq ?: 0) <= sequence } as int
        String s = "${path} · ${when} · position ${before + 1} of ${others.size() + 1}".toString()
        List<String> near = []
        if (before > 0) near << "after \"${others[before - 1].text.take(40)}\"".toString()
        if (before < others.size()) near << "before \"${others[before].text.take(40)}\"".toString()
        return near.isEmpty() ? s : s + " (" + near.join("; ") + ")"
    }

    static String labelsWhereShown(String kind, List<String> aliases, String pattern) {
        List<String> parts = new ArrayList<String>(aliases)
        if (pattern) parts << "pattern " + pattern
        return (kind == AppContentRules.ACTION ? "answers controls named " : "answers labels: ") + parts.join(", ")
    }

    private static String whereFor(def ec, String kind, Map eff) {
        if (kind == AppContentRules.HINT) {
            return hintWhereShown(ec, str(eff.routeName), str(eff.conditionKind), str(eff.anchorTestId), sequenceOf(eff.sequence), str(eff.contentKey))
        }
        return labelsWhereShown(kind, AppContentRules.normalizedAliases(eff.aliases), str(eff.matchPattern))
    }

    // ---- create / update --------------------------------------------------------------------------------------

    static Map prepareCreate(def ec, Map ctx) {
        String kind = str(ctx.kind)
        String slug = str(ctx.slug)
        String key = "admin:" + slug
        boolean hint = kind == AppContentRules.HINT
        Map proposed = [contentKind: kind, contentKey: key, source: AppContentRules.ADMIN, title: ctx.title, body: ctx.body]
        EDITABLE.each { String f -> if (!["title", "body", "hidden"].contains(f) && ctx.containsKey(f)) proposed.put(f, ctx.get(f)) }
        List<String> problems = new ArrayList<String>(AppContentRules.problems(ec, null, proposed))
        if (AppContentRules.KINDS.contains(kind) && ec.entity.find(ENTRY).condition("contentKind", kind).condition("contentKey", key).one() != null) {
            problems << "an entry ${key} already exists".toString()
        }
        Map fields = [contentKind: kind, contentKey: key, source: AppContentRules.ADMIN, hidden: "N",
                title: hint ? null : (str(ctx.title) ?: null), body: str(ctx.body) ?: null,
                aliases: joinAliases(ctx.aliases), matchPattern: str(ctx.matchPattern) ?: null,
                routeName: str(ctx.routeName) ?: null, conditionKind: str(ctx.conditionKind) ?: null,
                anchorTestId: str(ctx.anchorTestId) ?: null, sequence: sequenceOf(ctx.sequence)]
        String where = problems.isEmpty() ? whereFor(ec, kind, fields) : null
        String detail = "created ${kind} ${key}: ".toString() + ["title", "body", "aliases", "matchPattern", "routeName", "conditionKind", "anchorTestId", "sequence"]
                .findAll { fields.get(it) != null }.collect { "${it}: ${clip(fields.get(it))}".toString() }.join("; ")
        return [key: key, problems: problems, whereShown: where, fields: fields, detail: detail]
    }

    static Map prepareUpdate(def ec, Map ctx) {
        String kind = str(ctx.kind)
        String key = str(ctx.key)
        def found = ec.entity.find(ENTRY).condition("contentKind", kind).condition("contentKey", key).useCache(false).one()
        if (found == null) return [error: "No entry ${kind} ${key}.".toString()]
        Map row = found.getMap()
        boolean seed = str(row.source) == AppContentRules.SEED
        if (AppContentRules.retired(row, AppContentRules.currentSeedMark(ec))) {
            return [error: "${key} was dropped by a release; delete it.".toString()]
        }
        Map proposed = [:]
        EDITABLE.each { String f -> if (ctx.containsKey(f)) proposed.put(f, ctx.get(f)) }
        List<String> problems = new ArrayList<String>(AppContentRules.problems(ec, row, proposed))

        Map fields = [:]
        proposed.each { String f, def v ->
            def stored
            switch (f) {
                case "title": stored = (str(v).isEmpty() || (seed && str(v) == str(row.defaultTitle))) ? null : str(v); break
                case "body": stored = (str(v).isEmpty() || (seed && str(v) == str(row.defaultBody))) ? null : str(v); break
                case "aliases": stored = joinAliases(v); break
                case "hidden": stored = flag(v) == "Y" ? "Y" : (seed ? null : "N"); break
                case "sequence": stored = sequenceOf(v); break
                default: stored = str(v) ?: null
            }
            if (stored != row.get(f)) fields.put(f, stored)
        }
        Map eff = new LinkedHashMap(row)
        fields.each { k, v -> eff.put(k, v) }
        String where = problems.isEmpty() && !fields.isEmpty() ? whereFor(ec, kind, eff) : null
        String detail = "updated ${kind} ${key}: ".toString() + fields.collect { f, v -> "${f}: ${clip(row.get(f))} -> ${clip(v)}".toString() }.join("; ")
        return [key: key, problems: problems, whereShown: where, fields: fields, detail: detail, row: row]
    }

    static Map prepareReset(def ec, String kind, String key) {
        def found = ec.entity.find(ENTRY).condition("contentKind", kind).condition("contentKey", key).useCache(false).one()
        if (found == null) return [error: "No entry ${kind} ${key}.".toString()]
        Map row = found.getMap()
        if (str(row.source) != AppContentRules.SEED) return [error: "${key} is an admin entry with no default; delete it instead.".toString()]
        Map fields = [:]
        ["title", "body", "aliases", "hidden"].each { String f -> if (row.get(f) != null) fields.put(f, null) }
        String detail = "reset ${kind} ${key}: ".toString() + fields.keySet().collect { "${it}: ${clip(row.get(it))} -> default".toString() }.join("; ")
        return [key: key, fields: fields, detail: detail]
    }

    static Map prepareDelete(def ec, String kind, String key) {
        def found = ec.entity.find(ENTRY).condition("contentKind", kind).condition("contentKey", key).useCache(false).one()
        if (found == null) return [error: "No entry ${kind} ${key}.".toString()]
        Map row = found.getMap()
        if (str(row.source) == AppContentRules.SEED && !AppContentRules.retired(row, AppContentRules.currentSeedMark(ec))) {
            return [error: "${key} is a seed entry: hide it instead (or reset it to its default).".toString()]
        }
        String detail = "deleted ${kind} ${key}: title: ${clip(row.title ?: row.defaultTitle)}; body: ${clip(row.body ?: row.defaultBody)}".toString()
        return [key: key, detail: detail]
    }

    // ---- list -------------------------------------------------------------------------------------------------

    static List<Map> list(def ec, Map ctx) {
        String kind = str(ctx.kind)
        String source = str(ctx.source)
        String words = str(ctx.words).trim().toLowerCase()
        boolean editedOnly = ctx.editedOnly == true || str(ctx.editedOnly).toLowerCase() in ["y", "true"]
        String mark = AppContentRules.currentSeedMark(ec)
        List<Map> rows = ec.entity.find(ENTRY).list().collect { it.getMap() as Map }

        Map<String, Set<String>> seedLabels = [:]
        rows.each { Map r ->
            if (str(r.source) != AppContentRules.SEED || AppContentRules.retired(r, mark)) return
            Set<String> labels = new LinkedHashSet<String>()
            if (r.matchLabels) labels.addAll(str(r.matchLabels).split(/\|/) as List<String>)
            if (r.aliases) labels.addAll(str(r.aliases).split(/\|/) as List<String>)
            labels.remove("")
            seedLabels.put(str(r.contentKey), labels)
        }

        List<Map> out = []
        rows.each { Map r ->
            boolean retired = AppContentRules.retired(r, mark)
            boolean isSeed = str(r.source) == AppContentRules.SEED
            boolean edited = !isSeed || r.title || r.body || r.aliases || str(r.hidden) == "Y"
            if (kind && str(r.contentKind) != kind) return
            if (source && str(r.source) != source) return
            if (editedOnly && !edited) return
            String title = str(r.title ?: r.defaultTitle)
            String body = str(r.body ?: r.defaultBody)
            if (words) {
                String hay = [r.contentKey, title, body, r.aliases, r.matchPattern, r.matchLabels, r.defaultTitle, r.defaultBody].collect { str(it) }.join(" ").toLowerCase()
                if (!hay.contains(words)) return
            }
            String shadowedBy = null
            if (!isSeed && !retired) {
                List<String> mine = AppContentRules.normalizedAliases(r.aliases)
                String pattern = str(r.matchPattern)
                shadowedBy = seedLabels.find { String k, Set<String> labels ->
                    labels.any { String l -> mine.contains(l) || (pattern && AppContentRules.matchWildcard(pattern, l)) }
                }?.key
            }
            Integer seq = (r.sequence != null ? r.sequence : r.defaultSequence) as Integer
            String routePath = null
            if (r.routeName) routePath = ec.entity.find("darpan.content.AppRoute").condition("routeName", r.routeName).one()?.routePath
            out << ([kind: r.contentKind, key: r.contentKey, source: r.source, title: title ?: null, body: body ?: null,
                    defaultTitle: r.defaultTitle, defaultBody: r.defaultBody, aliases: r.aliases ? (str(r.aliases).split(/\|/) as List<String>).findAll { it } : [],
                    matchPattern: r.matchPattern, hidden: str(r.hidden) == "Y", routeName: r.routeName, routePath: routePath,
                    conditionKind: r.conditionKind, anchorTestId: r.anchorTestId, sequence: seq, defaultSequence: r.defaultSequence,
                    whereShown: r.whereShown ?: (isSeed ? null : whereFor(ec, str(r.contentKind), r)), edited: edited, retired: retired,
                    shadowedBy: shadowedBy, createdByUserId: r.createdByUserId, lastChangedByUserId: r.lastChangedByUserId,
                    lastChangedDate: r.lastChangedDate != null ? (r.lastChangedDate as java.sql.Timestamp).toInstant().toString() : null] as Map)
        }
        out.sort { Map a, Map b -> str(a.kind) <=> str(b.kind) ?: str(a.routeName) <=> str(b.routeName) ?: (a.sequence ?: 0) <=> (b.sequence ?: 0) ?: str(a.key) <=> str(b.key) }
        return out
    }
}
