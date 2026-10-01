package darpan.reconciliation.flowchart

/**
 * DAR-UI-048. A question's keys travel between questions as plain text, one key per line, so the next
 * question can read them from disk and only one key set is ever held in memory.
 */
class QuestionKeyFiles {

    static Set<String> read(File f) {
        Set<String> keys = new LinkedHashSet<String>()
        if (f == null || !f.isFile()) return keys
        f.eachLine("UTF-8") { String line ->
            String key = line?.trim()
            if (key) keys.add(key)
        }
        return keys
    }

    static long write(File f, Collection<String> keys) {
        f.parentFile?.mkdirs()
        List<String> sorted = (keys ?: []).findAll { it != null && it.toString().trim() }
                .collect { it.toString().trim() }.unique().sort()
        f.withWriter("UTF-8") { Writer w -> sorted.each { w.write(it); w.write("\n") } }
        return sorted.size()
    }
}
