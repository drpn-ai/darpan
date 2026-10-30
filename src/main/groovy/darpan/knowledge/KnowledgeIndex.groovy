package darpan.knowledge

import groovy.transform.CompileStatic
import org.apache.lucene.analysis.CharArraySet
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.LongPoint
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.BoostQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.Query
import org.apache.lucene.search.ScoreDoc
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.ByteBuffersDirectory

import java.time.LocalDate
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * In-memory keyword index over knowledge entries (the shape KnowledgeEntryFile.parse returns). Words match the
 * analyzed title (boost 3), tags (2) and body (1); an exact code such as QUERY_COST_BUDGET or R2 matches the
 * raw 'codes' field (boost 4). System and kind are filters, and entries past their reviewBy never return.
 * Built once from a list, immutable afterwards. No Moqui dependency.
 */
@CompileStatic
class KnowledgeIndex implements Closeable {

    static final int DEFAULT_LIMIT = 5
    static final int MAX_LIMIT = 20
    static final int SNIPPET_CHARS = 300
    private static final int MAX_QUERY_TERMS = 40
    private static final Pattern RAW_TOKEN = Pattern.compile('[A-Za-z0-9_\\-#]+')
    private static final Pattern TWO_CAPITALS = Pattern.compile('[A-Z].*[A-Z]')
    private static final CharArraySet STOP_WORDS = new CharArraySet(Arrays.asList(
            'a', 'an', 'and', 'are', 'as', 'at', 'be', 'but', 'by', 'for', 'if', 'in', 'into', 'is', 'it', 'no', 'not',
            'of', 'on', 'or', 'such', 'that', 'the', 'their', 'then', 'there', 'these', 'they', 'this', 'to', 'was',
            'will', 'with'), true)

    private final ByteBuffersDirectory directory = new ByteBuffersDirectory()
    private final StandardAnalyzer analyzer = new StandardAnalyzer(STOP_WORDS)
    private final DirectoryReader reader
    private final IndexSearcher searcher
    private final Map<String, Map> entriesByKey = new LinkedHashMap<String, Map>()

    private KnowledgeIndex(List<Map> entries) {
        IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))
        try {
            for (Map entry in entries) {
                String key = entry.get('key') as String
                entriesByKey.put(key, entry)
                writer.addDocument(toDocument(entry))
            }
        } finally {
            writer.close()
        }
        reader = DirectoryReader.open(directory)
        searcher = new IndexSearcher(reader)
    }

    static KnowledgeIndex build(List<Map> entries) {
        return new KnowledgeIndex(entries ?: new ArrayList<Map>())
    }

    int size() { return entriesByKey.size() }

    Map get(String key) { return key == null ? null : entriesByKey.get(key) }

    List<Map> search(String words, String system, String kind, int limit, LocalDate today) {
        int cap = limit < 1 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT)
        String text = (words ?: '').trim()
        if (text.isEmpty()) return new ArrayList<Map>()

        BooleanQuery.Builder terms = new BooleanQuery.Builder()
        int clauses = 0
        for (String term in analyzedTerms(text)) {
            if (clauses >= MAX_QUERY_TERMS) break
            terms.add(new BooleanClause(boosted('title', term, 3f), BooleanClause.Occur.SHOULD))
            terms.add(new BooleanClause(boosted('tags', term, 2f), BooleanClause.Occur.SHOULD))
            terms.add(new BooleanClause(boosted('body', term, 1f), BooleanClause.Occur.SHOULD))
            clauses++
        }
        for (String code in codeTokens(text)) {
            if (clauses >= MAX_QUERY_TERMS) break
            terms.add(new BooleanClause(boosted('codes', code, 4f), BooleanClause.Occur.SHOULD))
            clauses++
        }
        if (clauses == 0) return new ArrayList<Map>()

        BooleanQuery.Builder query = new BooleanQuery.Builder()
        query.add(terms.build(), BooleanClause.Occur.MUST)
        if (system) query.add(new TermQuery(new Term('system', system.toLowerCase(Locale.ROOT))), BooleanClause.Occur.FILTER)
        if (kind) query.add(new TermQuery(new Term('kind', kind.toLowerCase(Locale.ROOT))), BooleanClause.Occur.FILTER)
        LocalDate asOf = today ?: LocalDate.now()
        query.add(LongPoint.newRangeQuery('reviewBy', asOf.toEpochDay(), Long.MAX_VALUE), BooleanClause.Occur.FILTER)

        List<Map> hits = new ArrayList<Map>()
        for (ScoreDoc hit in searcher.search(query.build(), cap).scoreDocs) {
            Map entry = entriesByKey.get(searcher.storedFields().document(hit.doc).get('key'))
            String body = (entry.get('body') ?: '') as String
            hits.add([key     : entry.get('key'), tier: entry.get('tier'), title: entry.get('title'),
                      snippet : body.length() > SNIPPET_CHARS ? body.substring(0, SNIPPET_CHARS) : body,
                      source  : entry.get('source'), reviewBy: entry.get('reviewBy'), score: (Object) hit.score] as Map)
        }
        return hits
    }

    @Override
    void close() {
        reader.close()
        analyzer.close()
        directory.close()
    }

    private static Query boosted(String field, String term, float boost) {
        return new BoostQuery(new TermQuery(new Term(field, term)), boost)
    }

    private List<String> analyzedTerms(String text) {
        List<String> out = new ArrayList<String>()
        analyzer.tokenStream('body', text).withCloseable { stream ->
            CharTermAttribute attr = stream.addAttribute(CharTermAttribute)
            stream.reset()
            while (stream.incrementToken()) out.add(attr.toString())
            stream.end()
        }
        return out
    }

    /** Raw tokens that look like codes: hold a digit, an underscore or two capitals. Lowercased, deduplicated. */
    private static Set<String> codeTokens(String text) {
        Set<String> out = new LinkedHashSet<String>()
        Matcher m = RAW_TOKEN.matcher(text)
        while (m.find()) {
            String token = m.group()
            if (token.find('[0-9_]') || TWO_CAPITALS.matcher(token).find()) out.add(token.toLowerCase(Locale.ROOT))
        }
        return out
    }

    private static Document toDocument(Map entry) {
        String title = (entry.get('title') ?: '') as String
        String body = (entry.get('body') ?: '') as String
        List<String> tags = (entry.get('tags') ?: new ArrayList<String>()) as List<String>
        String tagText = tags.join(' ')
        Document doc = new Document()
        doc.add(new StringField('key', entry.get('key') as String, Field.Store.YES))
        doc.add(new TextField('title', title, Field.Store.NO))
        doc.add(new TextField('tags', tagText, Field.Store.NO))
        doc.add(new TextField('body', body, Field.Store.NO))
        for (String code in codeTokens(title + ' ' + tagText + ' ' + body)) doc.add(new StringField('codes', code, Field.Store.NO))
        for (Object s in (entry.get('systems') ?: new ArrayList()) as List) doc.add(new StringField('system', (s as String).toLowerCase(Locale.ROOT), Field.Store.NO))
        if (entry.get('kind')) doc.add(new StringField('kind', (entry.get('kind') as String).toLowerCase(Locale.ROOT), Field.Store.NO))
        doc.add(new LongPoint('reviewBy', (entry.get('reviewBy') as LocalDate).toEpochDay()))
        return doc
    }
}
