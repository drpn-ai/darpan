package darpan.content

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * DAR-BE-313: the rules every mascot entry (AppContentEntry) obeys, as one pure class the admin services (Task 3) and the
 * tests share. The matching vocabulary for data is closed and validated, never code: plain aliases, at most one wildcard
 * pattern (no RegExp is ever built from data), and for a hint a known route, condition and anchor.
 *
 * {@link #problems} takes the stored row (null on create) and the proposed values (a Map; a key that is absent means
 * "unchanged") and returns every problem, or an empty list. Everything that needs the database (shadowing, routes, anchors)
 * is skipped when ec is null, so the word/alias/pattern rules stay testable on their own.
 */
class AppContentRules {
    static final String GLOSSARY = "MASCOT_GLOSSARY"
    static final String ACTION = "MASCOT_ACTION"
    static final String HINT = "MASCOT_HINT"
    static final Set<String> KINDS = [GLOSSARY, ACTION, HINT] as Set<String>
    static final String SEED = "SEED"
    static final String ADMIN = "ADMIN"
    static final String ALWAYS = "ALWAYS"
    static final String ANCHOR_PRESENT = "ANCHOR_PRESENT"
    static final Set<String> CONDITIONS = [ALWAYS, ANCHOR_PRESENT] as Set<String>

    static final int MAX_TITLE = 60
    static final int MAX_ENTRY = 200
    static final int MAX_HINT = 160
    static final int MAX_ALIASES = 10
    static final int MAX_ALIAS = 60
    static final int MAX_PATTERN = 60
    static final int MAX_STARS = 2
    static final int MIN_LITERAL = 4
    static final int MAX_SEQUENCE = 9999
    static final int MAX_KEY = 80

    static final Pattern ADMIN_KEY = Pattern.compile(/^admin:[a-z0-9]+(-[a-z0-9]+)*$/)
    static final Pattern ANCHOR_ID = Pattern.compile(/^[a-z0-9-]{1,80}$/)
    private static final Pattern TOKEN = Pattern.compile(/\{[A-Za-z0-9_]+\}/)

    /** Copied verbatim from darpan-ui's mascotVoice.spec.ts PERFORMED_FLAVOUR: a mood performed, not earned. */
    static final List<Pattern> PERFORMED_FLAVOUR = [
            Pattern.compile(/\bsorry\b/, Pattern.CASE_INSENSITIVE),
            Pattern.compile(/\bawkward\b/, Pattern.CASE_INSENSITIVE),
            Pattern.compile(/\bdisappointing\b/, Pattern.CASE_INSENSITIVE),
            Pattern.compile(/\bhappily\b/, Pattern.CASE_INSENSITIVE),
            Pattern.compile(/\bhoarding\b/, Pattern.CASE_INSENSITIVE),
            Pattern.compile(/\balas\b/, Pattern.CASE_INSENSITIVE),
            Pattern.compile(/\boops\b/, Pattern.CASE_INSENSITIVE),
            Pattern.compile(/\bunfortunately\b/, Pattern.CASE_INSENSITIVE),
            Pattern.compile(/\bI will know\b/),
            Pattern.compile(/\bI’ll know\b/),
    ]

    /** Copied verbatim from the same file's BUZZWORDS (VOICE_GUIDE.md hard-fail list). */
    static final List<Pattern> BUZZWORDS = [
            Pattern.compile(/\bleverage[sd]?\b/, Pattern.CASE_INSENSITIVE),
            Pattern.compile(/\brobust\b/, Pattern.CASE_INSENSITIVE),
            Pattern.compile(/\bseamless(ly)?\b/, Pattern.CASE_INSENSITIVE),
            Pattern.compile(/\bbest-in-class\b/, Pattern.CASE_INSENSITIVE),
            Pattern.compile(/\bmission-critical\b/, Pattern.CASE_INSENSITIVE),
            Pattern.compile(/\beffortless(ly)?\b/, Pattern.CASE_INSENSITIVE),
            Pattern.compile(/\bcutting-edge\b/, Pattern.CASE_INSENSITIVE),
    ]

    /** JS `\s` (incl. NBSP and the BOM) and JS trim(), which Java's trim() is not. */
    private static final String JS_SPACE = /(?U)[\s\uFEFF]/

    private static String jsTrim(String s) { return s.replaceAll("^" + JS_SPACE + "+|" + JS_SPACE + "+\$", "") }

    /** Port of darpan-ui mascotTargets.normalizeLabel; the same three vectors are pinned in both repos. */
    static String normalize(String text) {
        if (text == null) return ""
        String s = jsTrim(text.replaceAll(JS_SPACE + "+", " "))
        s = jsTrim(s.replaceAll(/[:：]$/, ""))
        s = s.replaceAll("(?U)" + /\s*\([^)]*\)$/, "")
        s = jsTrim(s.replaceAll("(?iU)" + /^source \d+\s+/, ""))
        return s.toLowerCase(Locale.ROOT)
    }

    /**
     * `*` means one or more characters. Anchored prefix, the middle parts found in order, anchored suffix: linear in the
     * text for a pattern the rules allow (at most 2 stars, 60 chars). No RegExp is built.
     */
    static boolean matchWildcard(String pattern, String text) {
        if (pattern == null || text == null) return false
        List<String> parts = pattern.split(/\*/, -1) as List<String>
        if (parts.size() == 1) return pattern == text
        String first = parts.first()
        String last = parts.last()
        if (!text.startsWith(first)) return false
        int pos = first.length()
        for (int i = 1; i < parts.size() - 1; i++) {
            int idx = text.indexOf(parts[i], pos + 1)
            if (idx < 0) return false
            pos = idx + parts[i].length()
        }
        int lastStart = text.length() - last.length()
        return lastStart >= pos + 1 && text.endsWith(last)
    }

    static List<String> patternProblems(String pattern) {
        List<String> out = []
        if (pattern == null) return out
        if (pattern.trim().isEmpty()) { out << "pattern is empty"; return out }
        if (pattern.length() > MAX_PATTERN) { out << "pattern is over ${MAX_PATTERN} characters".toString(); return out }
        if (pattern.contains("<") || pattern.contains(">")) out << "pattern cannot contain < or >"
        if (pattern.contains("\n") || pattern.contains("\r")) out << "pattern must be on one line"
        if (pattern != pattern.trim()) out << "pattern cannot start or end with a space"
        if (pattern != pattern.toLowerCase()) out << "pattern must be lowercase"
        if (normalize(pattern) != pattern) out << "pattern must be in normalised form (single spaces, no trailing colon or (note), lowercase)"
        int stars = pattern.count("*")
        if (stars > MAX_STARS) out << "pattern can have at most ${MAX_STARS} *".toString()
        if (pattern.contains("**")) out << "pattern cannot have ** (two * in a row)"
        if (pattern.replace("*", "").length() < MIN_LITERAL) out << "pattern needs at least ${MIN_LITERAL} characters besides *".toString()
        if (stars == 0 && pattern.length() >= MIN_LITERAL) out << "pattern needs a * (use an alias for an exact label)"
        return out
    }

    /** Aliases arrive as a List or a `|`-joined String; empties are kept so an empty alias is refused, not dropped. */
    static List<String> aliasList(def aliases) {
        if (aliases == null) return []
        if (aliases instanceof Collection) return (aliases as Collection).collect { it == null ? "" : it.toString() }
        String s = aliases.toString()
        return s.isEmpty() ? [] : (s.split(/\|/, -1) as List<String>)
    }

    static List<String> aliasProblems(def aliases) {
        List<String> out = []
        List<String> list = aliasList(aliases)
        if (list.size() > MAX_ALIASES) out << "at most ${MAX_ALIASES} aliases".toString()
        Set<String> seen = new HashSet<String>()
        list.each { String raw ->
            if (raw.contains("<") || raw.contains(">")) { out << "alias '${raw.take(20)}' cannot contain < or >".toString(); return }
            if (aliases instanceof Collection && raw.contains("|")) { out << "alias '${raw.take(20)}' cannot contain |".toString(); return }
            if (raw.contains("\n") || raw.contains("\r")) { out << "an alias must be on one line"; return }
            String n = normalize(raw)
            if (n.isEmpty()) { out << "an alias cannot be empty"; return }
            if (n.length() > MAX_ALIAS) { out << "alias '${n.take(20)}...' is over ${MAX_ALIAS} characters".toString(); return }
            if (!seen.add(n)) out << "alias '${n}' is listed twice".toString()
        }
        return out
    }

    /** The aliases as stored: normalised, empties and repeats dropped. Only after aliasProblems is clean. */
    static List<String> normalizedAliases(def aliases) {
        return aliasList(aliases).collect { normalize(it) }.findAll { it }.unique()
    }

    static Set<String> tokens(String text) {
        Set<String> out = new LinkedHashSet<String>()
        if (text == null) return out
        Matcher m = TOKEN.matcher(text)
        while (m.find()) out << m.group()
        return out
    }

    /**
     * The words rules, with no database. title/body are the effective words (the edit, else the default). For a SEED row
     * defaultTitle/defaultBody pin the {token} slots; an ADMIN row may use none.
     */
    static List<String> wordProblems(String kind, String source, String title, String body, String defaultTitle, String defaultBody) {
        List<String> out = []
        boolean hint = kind == HINT
        if (!hint) {
            if (title == null || title.isEmpty()) out << "title is required"
            else out.addAll(lineProblems("title", title))
            if (title != null && title.length() > MAX_TITLE) out << "title is over ${MAX_TITLE} characters".toString()
        } else if (title != null && !title.isEmpty()) {
            out << "a hint has no title"
        }
        if (body == null || body.isEmpty()) out << (hint ? "text is required" : "body is required")
        else out.addAll(lineProblems(hint ? "text" : "body", body))
        if (body != null && !body.isEmpty()) {
            if (hint) {
                if (body.length() > MAX_HINT) out << "text is over ${MAX_HINT} characters".toString()
            } else {
                if (Character.isUpperCase(body.charAt(0))) out << "body must start lowercase"
                if (title != null && title.length() + 3 + body.length() > MAX_ENTRY) {
                    out << "title and body together are over ${MAX_ENTRY} characters".toString()
                }
            }
            if (PERFORMED_FLAVOUR.any { it.matcher(body).find() } || (title != null && PERFORMED_FLAVOUR.any { it.matcher(title).find() })) {
                out << "performed flavour: name the value's real failure mode instead of a mood"
            }
            if (BUZZWORDS.any { it.matcher(body).find() } || (title != null && BUZZWORDS.any { it.matcher(title).find() })) {
                out << "buzzword (VOICE_GUIDE hard-fail list)"
            }
        }
        if (source == SEED) {
            if (tokens(body) != tokens(defaultBody)) out << "body must keep exactly the {token} slots the default has".toString()
            if (!hint && tokens(title) != tokens(defaultTitle)) out << "title must keep exactly the {token} slots the default has".toString()
        } else {
            if (!tokens(body).isEmpty() || !tokens(title).isEmpty()) out << "an admin entry cannot use {token} slots"
        }
        return out
    }

    private static List<String> lineProblems(String field, String text) {
        List<String> out = []
        if (text.contains("<") || text.contains(">")) out << "${field} cannot contain < or >".toString()
        if (text.contains("\n") || text.contains("\r")) out << "${field} must be on one line".toString()
        if (text != text.trim()) out << "${field} cannot start or end with a space".toString()
        return out
    }

    /** The mark of the newest seed load, or null when none was loaded (then no SEED row counts as retired). */
    static String currentSeedMark(def ec) {
        def v = ec.entity.find("darpan.content.AppContentSeed").condition("seedId", "CURRENT").one()
        return v == null ? null : v.seedMark
    }

    /** A SEED row a later release no longer generates: its mark is not the current one. ADMIN rows never are. */
    static boolean retired(Map row, String currentMark) {
        return currentMark != null && str(row.source) == SEED && str(row.seedMark) != currentMark
    }

    /** A vocabulary row (AppRoute, AppRouteAnchor) the current release still has. No CURRENT row means no filter, as for entries. */
    static boolean current(def row, String currentMark) {
        return currentMark == null || str(row.seedMark) == currentMark
    }

    /** The AppRoute a release still has, or null (unknown, or dropped by a later release). */
    static def currentRoute(def ec, String routeName) {
        def route = ec.entity.find("darpan.content.AppRoute").condition("routeName", routeName).one()
        return route != null && current(route, currentSeedMark(ec)) ? route : null
    }

    /** Whether a route's anchor is one the current release still has. */
    static boolean anchorKnown(def ec, String routeName, String anchor) {
        def a = ec.entity.find("darpan.content.AppRouteAnchor").condition("routeName", routeName).condition("anchorTestId", anchor).one()
        return a != null && current(a, currentSeedMark(ec))
    }

    /**
     * The compiled bundled regex matchers a SEED row carries (`matchRegex`, one per line). The sources are release code written by
     * the generator and are the JS/Java common subset; one that does not compile here is skipped, never thrown.
     */
    static List<Pattern> bundledPatterns(def matchRegex) {
        List<Pattern> out = []
        str(matchRegex).split(/\n/).each { String source ->
            if (source.isEmpty()) return
            try { out << Pattern.compile(source) } catch (java.util.regex.PatternSyntaxException ignored) { }
        }
        return out
    }

    /** Whether the bundle's own regex matchers (the rows' matchRegex) answer this label. */
    static boolean bundledPatternAnswers(def matchRegex, String label) {
        return bundledPatterns(matchRegex).any { it.matcher(label).find() }
    }

    /** Whether a stored row still obeys the rules, so a row written around them is not served. No database. */
    static boolean servable(Map row) {
        String kind = str(row.contentKind)
        String source = str(row.source)
        if (!KINDS.contains(kind) || (source != SEED && source != ADMIN)) return false
        if (source == ADMIN || row.title || row.body) {
            String t = source == SEED ? str(row.title ?: row.defaultTitle) : str(row.title)
            String b = source == SEED ? str(row.body ?: row.defaultBody) : str(row.body)
            if (!wordProblems(kind, source, t ?: null, b ?: null, str(row.defaultTitle), str(row.defaultBody)).isEmpty()) return false
        }
        if (row.aliases && !aliasProblems(row.aliases).isEmpty()) return false
        if (row.matchPattern && !patternProblems(str(row.matchPattern)).isEmpty()) return false
        return true
    }

    private static String str(def v) { return v == null ? "" : v.toString() }

    /** The value after the proposal: a key absent from proposed keeps the stored row's. */
    private static def eff(Map row, Map proposed, String field) {
        if (proposed != null && proposed.containsKey(field)) return proposed.get(field)
        return row?.get(field)
    }

    static List<String> problems(def ec, Map row, Map proposed) {
        List<String> out = []
        proposed = proposed ?: [:]
        boolean create = row == null
        String kind = str(eff(row, proposed, "contentKind") ?: eff(row, proposed, "kind"))
        String key = str(eff(row, proposed, "contentKey") ?: eff(row, proposed, "key"))
        String source = str(eff(row, proposed, "source"))
        if (!KINDS.contains(kind)) { out << "unknown kind '${kind}'".toString(); return out }
        if (source != SEED && source != ADMIN) { out << "source must be SEED or ADMIN"; return out }
        if (key.isEmpty()) { out << "key is required"; return out }
        boolean hint = kind == HINT

        if (create) {
            if (source == SEED) { out << "SEED entries come from a release, not from an edit" }
            else if (!ADMIN_KEY.matcher(key).matches() || key.length() > MAX_KEY) out << "an admin key is admin: and a kebab slug of at most ${MAX_KEY} characters".toString()
        } else {
            // A SEED row may change only title, body, aliases and hidden. Its identity, binding and pattern are fixed.
            if (proposed.containsKey("contentKind") && str(proposed.contentKind) != str(row.contentKind)) out << "kind cannot change"
            if (proposed.containsKey("contentKey") && str(proposed.contentKey) != str(row.contentKey)) out << "key cannot change"
            if (proposed.containsKey("source") && str(proposed.source) != str(row.source)) out << "source cannot change"
            if (source == SEED) {
                ["matchPattern", "routeName", "conditionKind", "anchorTestId", "sequence"].each { String f ->
                    if (proposed.containsKey(f) && str(proposed.get(f)) != str(row.get(f))) out << "${f} cannot change on a seed entry".toString()
                }
            }
        }

        // Words.
        String defaultTitle = str(row?.defaultTitle)
        String defaultBody = str(row?.defaultBody)
        String title = str(eff(row, proposed, "title") ?: (source == SEED ? defaultTitle : ""))
        String body = str(eff(row, proposed, "body") ?: (source == SEED ? defaultBody : ""))
        boolean wordsGiven = create || proposed.containsKey("title") || proposed.containsKey("body")
        if (wordsGiven) out.addAll(wordProblems(kind, source, title ?: null, body ?: null, defaultTitle, defaultBody))

        // Matching vocabulary.
        def aliases = eff(row, proposed, "aliases")
        String pattern = str(eff(row, proposed, "matchPattern"))
        List<String> aliasList = normalizedAliases(aliases)
        if (hint) {
            if (!aliasList.isEmpty() || !pattern.isEmpty()) out << "a hint takes no aliases or pattern"
        } else {
            if (proposed.containsKey("aliases")) out.addAll(aliasProblems(aliases))
            if (proposed.containsKey("matchPattern") && !pattern.isEmpty()) {
                if (source == SEED) out << "a seed entry cannot take a pattern"
                out.addAll(patternProblems(pattern))
            }
            if (source == ADMIN && aliasList.isEmpty() && pattern.isEmpty()) out << "an entry needs at least one alias or a pattern"
            if (ec != null && (proposed.containsKey("aliases") || proposed.containsKey("matchPattern"))) {
                out.addAll(shadowProblems(ec, kind, key, aliasList, pattern))
            }
        }

        // Hint binding.
        if (hint) {
            String routeName = str(eff(row, proposed, "routeName"))
            String condition = str(eff(row, proposed, "conditionKind"))
            String anchor = str(eff(row, proposed, "anchorTestId"))
            def seq = eff(row, proposed, "sequence")
            if (source == ADMIN || create) {
                if (routeName.isEmpty()) out << "a hint needs a route"
                else if (ec != null && currentRoute(ec, routeName) == null) out << "unknown route '${routeName}'".toString()
                if (!CONDITIONS.contains(condition)) out << "condition must be ALWAYS or ANCHOR_PRESENT"
                else if (condition == ALWAYS && !anchor.isEmpty()) out << "an ALWAYS hint takes no anchor"
                else if (condition == ANCHOR_PRESENT) {
                    if (!ANCHOR_ID.matcher(anchor).matches()) out << "anchor must be a data-testid of lowercase letters, digits and -"
                    else if (ec != null && !routeName.isEmpty() && !anchorKnown(ec, routeName, anchor)) {
                        out << "unknown anchor '${anchor}' on route '${routeName}'".toString()
                    }
                }
                Integer n = null
                try { n = seq == null || seq.toString().trim().isEmpty() ? null : Integer.valueOf(seq.toString().trim()) } catch (NumberFormatException ignored) { }
                if (n == null) out << "sequence must be a whole number 0-${MAX_SEQUENCE}".toString()
                else if (n < 0 || n > MAX_SEQUENCE) out << "sequence must be 0-${MAX_SEQUENCE}".toString()
            }
        }
        return out.unique()
    }

    /** Every label already answered: each row's matchLabels and the bundle's regex matchers, plus every other row's aliases; patterns are checked against them. */
    private static List<String> shadowProblems(def ec, String kind, String key, List<String> aliases, String pattern) {
        List<String> out = []
        Set<String> labels = new HashSet<String>()
        Set<String> otherPatterns = new HashSet<String>()
        List<Pattern> bundled = []
        String mark = currentSeedMark(ec)
        ec.entity.find("darpan.content.AppContentEntry").list().each { def r ->
            if (retired(r.getMap(), mark)) return
            boolean self = r.contentKind == kind && r.contentKey == key
            if (r.matchRegex) bundled.addAll(bundledPatterns(r.matchRegex))
            if (r.matchLabels) labels.addAll(str(r.matchLabels).split(/\|/) as List<String>)
            if (!self && r.aliases) labels.addAll(str(r.aliases).split(/\|/) as List<String>)
            if (!self && r.matchPattern) otherPatterns << str(r.matchPattern)
        }
        labels.remove("")
        aliases.each { String a ->
            if (labels.contains(a)) out << "alias '${a}' is already answered".toString()
            else if (bundled.any { it.matcher(a).find() }) out << "alias '${a}' is already answered by a built-in pattern".toString()
            else if (otherPatterns.any { matchWildcard(it, a) }) out << "alias '${a}' is already answered by a pattern".toString()
        }
        if (pattern) {
            if (otherPatterns.contains(pattern)) out << "pattern '${pattern}' is already used".toString()
            def hit = labels.find { matchWildcard(pattern, it) }
            if (hit != null) out << "pattern '${pattern}' would answer '${hit}', which is already answered".toString()
        }
        return out
    }
}
