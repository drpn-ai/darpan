package darpan.dataset

import darpan.facade.common.DataManagerSupport
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/**
 * Test only: test.KeptCopyTestServices.project#MadeUpKeptRecords, a made-up system's side of darpan's kept-copy projection
 * contract (dataset.DatasetServices.project#KeptRecords). Its extract keeps the asked fields plus "always"; a copy that kept
 * only some fields stands in only when it kept every field the side keeps and filters on; it writes "<file>.madeup.json"
 * beside its file and answers its location as madeUpFileLocation. Every call is recorded in CALLS.
 */
class MadeUpKeptRecords {
    static final List<Map> CALLS = Collections.synchronizedList([])

    static Map<String, Object> project(def ec, Map ctx) {
        CALLS << new LinkedHashMap(ctx)
        Map copy = (ctx.copy ?: [:]) as Map, side = (ctx.side ?: [:]) as Map
        Set<String> keep = side.keepFields ? ((side.keepFields as List<String>) + ["always"]) as Set<String> : null
        if (copy.keptFields != null) {
            if (keep == null) return [servingProblem: "the made-up copy keeps some fields, the side wants all"]
            List<String> missing = ((keep - ["always"]) + (side.filterFields ?: [])).findAll { !(it in (copy.keptFields as List)) } as List<String>
            if (missing) return [servingProblem: "the made-up copy lacks ${missing.sort().join(', ')}".toString(), missingFields: missing.sort()]
        }
        if (!ctx.recordsFile) return [:]
        long count = 0
        new File(ctx.outputFile as String).withWriter("UTF-8") { Writer w ->
            new File(ctx.recordsFile as String).eachLine("UTF-8") { String line ->
                Map record = (Map) new JsonSlurper().parseText(line)
                w.write(JsonOutput.toJson(keep == null ? record : record.findAll { k, v -> k in keep }))
                w.write("\n")
                count++
            }
        }
        String name = (ctx.fileName as String).replaceAll(/\.json$/, "") + ".madeup.json"
        File dir = DataManagerSupport.resolveDirectoryFile(ec, ctx.outputLocation as String, true)
        new File(dir, name).text = JsonOutput.toJson([records: count])
        String location = DataManagerSupport.childLocation(ctx.outputLocation as String, name)
        return [recordCount: count, writtenLocations: [location], resultFields: [madeUpFileLocation: location]]
    }
}
