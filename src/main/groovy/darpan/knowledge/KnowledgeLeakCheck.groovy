package darpan.knowledge

import groovy.json.JsonSlurper
import groovy.transform.CompileStatic

import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Keeps tenant-private data out of shared knowledge. Returns only shape names and identifier kinds,
 * never the matched values, so a hit is safe to log. No Moqui dependency.
 *
 * The shapes live in classpath resource darpan/knowledge/private-shapes.json
 * ([{name, regex, allow?}]); the knowledge repo's check script reads the same file.
 */
@CompileStatic
class KnowledgeLeakCheck {

    private static final String SHAPES_RESOURCE = '/darpan/knowledge/private-shapes.json'

    private static class Shape {
        String name
        Pattern pattern
        Set<String> allow = new HashSet<String>()
    }

    private static final List<Shape> SHAPES = loadShapes()

    private static List<Shape> loadShapes() {
        InputStream stream = KnowledgeLeakCheck.getResourceAsStream(SHAPES_RESOURCE)
        if (stream == null) throw new IllegalStateException("Missing resource ${SHAPES_RESOURCE}")
        List parsed
        try {
            parsed = (List) new JsonSlurper().parse(stream, 'UTF-8')
        } finally {
            stream.close()
        }
        List<Shape> shapes = new ArrayList<Shape>()
        for (Object o : parsed) {
            Map m = (Map) o
            Shape s = new Shape()
            s.name = (String) m.get('name')
            s.pattern = Pattern.compile((String) m.get('regex'))
            List allow = (List) m.get('allow')
            if (allow != null) for (Object a : allow) s.allow.add(((String) a).toLowerCase())
            shapes.add(s)
        }
        return shapes
    }

    /** Names of private-looking shapes found in text, sorted and distinct. */
    static List<String> shapeHits(String text) {
        TreeSet<String> hits = new TreeSet<String>()
        if (!text) return new ArrayList<String>()
        for (Shape s : SHAPES) {
            Matcher m = s.pattern.matcher(text)
            while (m.find()) {
                if (s.allow.contains(m.group().toLowerCase())) continue
                hits.add(s.name)
                break
            }
        }
        return new ArrayList<String>(hits)
    }

    /**
     * Kinds of a tenant's identifiers found in text, sorted and distinct. An identifier is
     * [value, kind, caseSensitive]. Allow phrases are removed (case-insensitive) before matching.
     */
    static List<String> identifierHits(String text, List<Map> identifiers, Set<String> allowPhrases) {
        TreeSet<String> hits = new TreeSet<String>()
        if (!text || !identifiers) return new ArrayList<String>()
        String cleaned = text
        if (allowPhrases) {
            for (String phrase : allowPhrases) {
                if (!phrase) continue
                cleaned = Pattern.compile(Pattern.quote(phrase), Pattern.CASE_INSENSITIVE).matcher(cleaned).replaceAll(' ')
            }
        }
        for (Map id : identifiers) {
            String value = (String) id.get('value')
            String kind = (String) id.get('kind')
            if (!value) continue
            boolean caseSensitive = Boolean.TRUE == id.get('caseSensitive')
            int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE
            String quoted = Pattern.quote(value)
            String regex = kind == 'host' ? quoted : '\\b' + quoted + '\\b'
            if (kind == 'host') flags |= Pattern.CASE_INSENSITIVE
            if (Pattern.compile(regex, flags).matcher(cleaned).find()) hits.add(kind)
        }
        return new ArrayList<String>(hits)
    }
}
