package darpan.knowledge

import groovy.transform.CompileStatic

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * Parses one knowledge entry: a header between two '---' lines of 'name: value' pairs, then
 * '# <title>' as the first heading, then the body. Errors start with the origin so a log line or
 * the knowledge repo's check script names the file. No Moqui dependency.
 */
@CompileStatic
class KnowledgeEntryFile {

    static final int MAX_BODY_CHARS = 1500
    static final String DEFAULT_OWNER = 'Aditi Patel'
    private static final Set<String> TIERS = ['product', 'tenant', 'staff'] as Set<String>

    static Map parse(String markdown, String origin) {
        String text = (markdown ?: '').replace('\r\n', '\n').replace('\r', '\n')
        if (text.startsWith('﻿')) text = text.substring(1)
        List<String> lines = Arrays.asList(text.split('\n', -1))
        int i = 0
        while (i < lines.size() && lines.get(i).trim().isEmpty()) i++
        if (i >= lines.size() || lines.get(i).trim() != '---') throw bad(origin, 'missing header (expected a first line of ---)')
        i++

        Map<String, String> header = new LinkedHashMap<String, String>()
        boolean closed = false
        for (; i < lines.size(); i++) {
            String line = lines.get(i)
            if (line.trim() == '---') { closed = true; i++; break }
            if (line.trim().isEmpty()) continue
            int colon = line.indexOf(':')
            if (colon <= 0) throw bad(origin, "header line is not 'name: value': ${line.take(40)}")
            header.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim())
        }
        if (!closed) throw bad(origin, 'header is not closed with ---')

        String id = header.get('id')
        String tier = header.get('tier')
        String source = header.get('source')
        String reviewByText = header.get('reviewBy')
        if (!id) throw bad(origin, 'missing id')
        if (!tier) throw bad(origin, 'missing tier')
        if (!TIERS.contains(tier)) throw bad(origin, "tier must be product, tenant or staff, not '${tier}'")
        if (!source) throw bad(origin, 'missing source')
        if (!reviewByText) throw bad(origin, 'missing reviewBy')
        LocalDate reviewBy
        try {
            reviewBy = LocalDate.parse(reviewByText)
        } catch (DateTimeParseException ignored) {
            throw bad(origin, "reviewBy must be yyyy-MM-dd, not '${reviewByText}'")
        }

        while (i < lines.size() && lines.get(i).trim().isEmpty()) i++
        if (i >= lines.size() || !lines.get(i).startsWith('# ') || lines.get(i).substring(2).trim().isEmpty()) {
            throw bad(origin, "missing title (expected '# <title>' after the header)")
        }
        String title = lines.get(i).substring(2).trim()
        String body = lines.subList(i + 1, lines.size()).join('\n').trim()
        if (body.length() > MAX_BODY_CHARS) {
            throw bad(origin, "body is ${body.length()} characters, limit ${MAX_BODY_CHARS}")
        }

        return [key       : id, tier: tier, systems: csv(header.get('system')), kind: header.get('kind') ?: '',
                tags      : csv(header.get('tags')), source: source, owner: header.get('owner') ?: DEFAULT_OWNER,
                reviewBy  : reviewBy, supersedes: header.get('supersedes') ?: '', title: title, body: body] as Map
    }

    private static List<String> csv(String value) {
        List<String> out = new ArrayList<String>()
        if (!value) return out
        for (String part : value.split(',')) {
            String p = part.trim()
            if (p) out.add(p)
        }
        return out
    }

    private static IllegalArgumentException bad(String origin, String message) {
        return new IllegalArgumentException("${origin}: ${message}".toString())
    }
}
