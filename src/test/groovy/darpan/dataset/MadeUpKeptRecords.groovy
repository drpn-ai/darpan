package darpan.dataset

import darpan.facade.common.DataManagerSupport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/**
 * Test only: test.KeptCopyTestServices.project#MadeUpKeptRecords, a made-up system's side of darpan's kept-copy projection
 * contract (dataset.DatasetServices.project#KeptRecords). Its extract keeps the asked fields plus "always"; a copy that kept
 * only some fields stands in only when it kept every field the side keeps and filters on; it writes "<file>.madeup.json"
 * beside its file and answers its location as madeUpFileLocation; its read cannot select a field named unread*. Every call
 * is recorded in CALLS.
 *
 * S99-214 (S16a Q4) hooks, set by a test and cleared in its finally: {@link #errorOnCall} adds that error on every call (the
 * service then answers nothing, as a failed one does); {@link #onCheck} runs during the serving check (a call with neither
 * recordsFile nor unionFields), between the slice reading the copy's manifest and opening its records.
 */
class MadeUpKeptRecords {
    static final List<Map> CALLS = Collections.synchronizedList([])
    /** A file outside any slice folder the hostile mode lists as written (M2: core must never delete it). */
    static File outsideFile
    static volatile String errorOnCall
    static volatile Closure onCheck

    static Map<String, Object> project(def ec, Map ctx) {
        CALLS << new LinkedHashMap(ctx)
        if (errorOnCall) {
            ec.message.addError(errorOnCall)
            return [:]
        }
        if (!ctx.recordsFile && ctx.unionFields == null && onCheck != null) onCheck.call()
        // a planned dataset read: the made-up read cannot select a field named unread*
        if (ctx.unionFields != null) return [keepableFields: (ctx.unionFields as List).findAll { !(it as String).startsWith("unread") }]
        Map copy = (ctx.copy ?: [:]) as Map, side = (ctx.side ?: [:]) as Map
        Set<String> keep = side.keepFields ? ((side.keepFields as List<String>) + ["always"]) as Set<String> : null
        if (copy.keptFields != null) {
            if (keep == null) return [servingProblem: "the made-up copy keeps some fields, the side wants all"]
            List<String> missing = ((keep - ["always"]) + (side.filterFields ?: [])).findAll { !(it in (copy.keptFields as List)) } as List<String>
            if (missing) return [servingProblem: "the made-up copy lacks ${missing.sort().join(', ')}".toString(), missingFields: missing.sort()]
        }
        if (!ctx.recordsFile) return [:]
        long count = 0
        String last = null
        new File(ctx.outputFile as String).withWriter("UTF-8") { Writer w ->
            new File(ctx.recordsFile as String).eachLine("UTF-8") { String line ->
                Map record = (Map) new JsonSlurper().parseText(line)
                last = JsonOutput.toJson(keep == null ? record : record.findAll { k, v -> k in keep })
                w.write(last)
                w.write("\n")
                count++
            }
            // hostile mode M3 (S99-214, S16a Q2): one line more than it declares; only core's written-line count can see it
            if ("hostileLines" in ((side.keepFields ?: []) as List) && last != null) { w.write(last); w.write("\n") }
        }
        String name = (ctx.fileName as String).replaceAll(/\.json$/, "") + ".madeup.json"
        File dir = DataManagerSupport.resolveDirectoryFile(ec, ctx.outputLocation as String, true)
        new File(dir, name).text = JsonOutput.toJson([records: count])
        String location = DataManagerSupport.childLocation(ctx.outputLocation as String, name)
        List keepAsked = (side.keepFields ?: []) as List
        // hostile modes: a result key the slice answers itself (M1), a listed path outside the slice and a short count (M2)
        if ("hostileResult" in keepAsked) {
            return [recordCount: count, writtenLocations: [location], resultFields: [madeUpFileLocation: location, fileLocation: "/elsewhere.json", served: false]]
        }
        if ("hostileCleanup" in keepAsked) {
            return [recordCount: count + 1, writtenLocations: [location, outsideFile.absolutePath], resultFields: [:]]
        }
        return [recordCount: count, writtenLocations: [location], resultFields: [madeUpFileLocation: location]]
    }
}
