package darpan.dataset

import darpan.facade.common.DataManagerSupport
import groovy.json.JsonOutput

import java.security.MessageDigest

/** DAR-BE-318 plan 3a: pure helpers for the kept copy of a dataset. */
class DatasetSupport {

    /** SHA-256 hex (64 characters) of the options as canonical JSON: map keys sorted at every depth. */
    static String optionsHash(Map options) {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalJson(options).getBytes("UTF-8"))
        return digest.collect { String.format("%02x", it & 0xff) }.join()
    }

    static String canonicalJson(Object options) {
        return JsonOutput.toJson(canonical(options == null ? [:] : options))
    }

    private static Object canonical(Object value) {
        if (value instanceof Map) {
            Map sorted = new LinkedHashMap()
            ((Map) value).keySet().collect { it.toString() }.sort().each { String key -> sorted.put(key, canonical(((Map) value).get(key))) }
            return sorted
        }
        if (value instanceof Collection) return ((Collection) value).collect { canonical(it) }
        return value
    }

    /**
     * &lt;root&gt;/datasets/&lt;safe tenant&gt;/&lt;safe datasetId&gt;/, with the same safe-token rule as the run folders. Main code
     * passes the data manager root through DatasetRefreshSupport.datasetDir(ec, ...), which honours overrides (ruling P3a-7).
     */
    static File datasetDir(File root, String tenantId, String datasetId) {
        return new File(new File(new File(root, "datasets"), DataManagerSupport.safeToken(tenantId, "tenant")),
                DataManagerSupport.safeToken(datasetId, "dataset"))
    }
}
