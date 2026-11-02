package darpan.script

import darpan.facade.common.TenantAccessSupport
import groovy.json.JsonSlurper

/** Runs a draft script on the ScriptLane in JEXL or Groovy for the Settings > Script Lab screen; super-admin only. */
class ScriptLab {
    static final List<String> ENGINES = ['JEXL', 'GROOVY']

    static Map<String, Object> run(def ec, String engine, String profile, String body, String inputJson) {
        String e = engine?.trim()?.toUpperCase() ?: 'JEXL'
        if (!TenantAccessSupport.isSuperAdmin(ec)) return failure(e, 'NOT_ALLOWED', 'The script lab is limited to super-admin users.')
        if (!ENGINES.contains(e)) return failure(e, 'INPUT', "Unknown engine '${e}'.".toString())
        // scripts never hold a transaction, it would keep row locks and a pooled connection for the whole run
        if (ec.transaction.isTransactionInPlace()) return failure(e, 'TRANSACTION', 'A script cannot run inside an open transaction.')

        String text = inputJson?.trim() ?: 'null'
        if (text.length() > ScriptSupervisor.MAX_INPUT_JSON_CHARS) {
            return failure(e, 'INPUT', "The input is larger than ${ScriptSupervisor.MAX_INPUT_JSON_CHARS} characters.".toString())
        }
        Object input
        try {
            input = new JsonSlurper().parseText(text)
        } catch (Exception ignored) {
            return failure(e, 'INPUT', 'The input is not valid JSON.')
        }

        String tenantId = TenantAccessSupport.currentActiveTenantUserGroupId(ec)
        if (e == 'GROOVY') {
            return ScriptLane.run(tenantId, 'GROOVY', GroovyGuard.WALL_LIMIT_MS) { -> GroovyGuard.evaluate(body, input) }
        }
        String p = ScriptSupervisor.profileOf(profile) ?: ScriptSupervisor.SCRIPT
        long limitMs = ((ScriptSupervisor.LIMITS[p] as Map)?.timeoutMs as Long) ?: 2_000L
        return ScriptLane.run(tenantId, 'JEXL', limitMs) { -> ScriptSupervisor.evaluateHere(p, body, input) }
    }

    private static final String LINES = '{"lines": [{"sku": "MUG-01", "ordered": 5, "shipped": 3}, {"sku": "TEE-02", "ordered": 4, "shipped": 4}, {"sku": "CAP-03", "ordered": 7, "shipped": 2}]}'

    /** Same jobs in both engines, then each guard, then Groovy's two known in-process gaps. */
    static List<Map<String, String>> examples() {
        return [
            [key: 'j-owed', label: 'JEXL: units still owed', expect: 'ok, 7', engine: 'JEXL', profile: 'SCRIPT', input: LINES,
             body: 'var owed = 0;\nfor (var line : input.lines) {\n  var gap = line.ordered - line.shipped;\n  if (gap > 0) { owed = owed + gap; }\n}\nd:log(\'lines checked: \' + size(input.lines));\nowed'],
            [key: 'g-owed', label: 'Groovy: units still owed', expect: 'ok, 7', engine: 'GROOVY', profile: '', input: LINES,
             body: 'def owed = { line -> Math.max(0, line.ordered - line.shipped) }\nd.log("lines checked: ${input.lines.size()}")\ninput.lines.sum { owed(it) }'],
            [key: 'g-rich', label: 'Groovy: closures, sort, collect', expect: 'ok, a list', engine: 'GROOVY', profile: '', input: LINES,
             body: 'input.lines\n  .collect { [sku: it.sku, owed: Math.max(0, it.ordered - it.shipped)] }\n  .findAll { it.owed > 0 }\n  .sort { -it.owed }'],
            [key: 'j-loop', label: 'JEXL: endless loop', expect: 'stopped, UNITS', engine: 'JEXL', profile: 'SCRIPT', input: '{}', body: 'while (true) {}'],
            [key: 'g-loop', label: 'Groovy: endless loop', expect: 'stopped by the checkpoint', engine: 'GROOVY', profile: '', input: '{}', body: 'while (true) { }'],
            [key: 'j-grow', label: 'JEXL: text that doubles', expect: 'stopped, MEMORY', engine: 'JEXL', profile: 'SCRIPT', input: '{}',
             body: "var s = 'xxxxxxxxxx';\nwhile (true) { s = s + s; }"],
            [key: 'g-grow', label: 'Groovy: list that keeps growing', expect: 'stopped, MEMORY (64 MB)', engine: 'GROOVY', profile: '', input: '{}',
             body: 'def l = []\nwhile (true) { l << new byte[100_000] }'],
            [key: 'j-escape', label: 'JEXL: reach Java Runtime', expect: 'refused, NOT_ALLOWED', engine: 'JEXL', profile: 'SCRIPT', input: '{}',
             body: "''.class.forName('java.lang.Runtime')"],
            [key: 'g-exit', label: 'Groovy: System.exit', expect: 'refused before running', engine: 'GROOVY', profile: '', input: '{}', body: 'System.exit(0)'],
            [key: 'g-shell', label: 'Groovy: run a shell command', expect: 'refused before running', engine: 'GROOVY', profile: '', input: '{}', body: '"ls /".execute().text'],
            [key: 'j-range', label: 'JEXL: count to 200 million', expect: 'stopped, UNITS', engine: 'JEXL', profile: 'SCRIPT', input: '{}',
             body: 'var t = 0; for (var i : 1..200000000) { t = t + i; } t'],
            [key: 'g-gap-call', label: 'Groovy gap: one long library call', expect: 'STUCK: holds a lane thread for seconds', engine: 'GROOVY', profile: '', input: '{}',
             body: '(1..200_000_000L).sum()'],
            [key: 'g-gap-alloc', label: 'Groovy gap: one big allocation', expect: 'runs: 50 MB in one step, unseen', engine: 'GROOVY', profile: '', input: '{}',
             body: 'def s = "x" * 50_000_000\ns.size()'],
        ]
    }

    private static Map<String, Object> failure(String engine, String kind, String message) {
        return [ok: false, engine: engine, result: null, error: [kind: kind, message: message], units: 0L, elapsedMs: 0L, laneMs: 0L, logs: []]
    }
}
