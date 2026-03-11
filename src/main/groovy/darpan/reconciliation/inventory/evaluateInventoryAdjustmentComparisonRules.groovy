import org.slf4j.LoggerFactory

def logger = LoggerFactory.getLogger("darpan.reconciliation.inventory.evaluateInventoryAdjustmentComparisonRules")

def normalize = { Object value -> value?.toString()?.trim() }
def normalizeBool = { Object value, boolean defaultValue ->
    if (value == null) return defaultValue
    if (value instanceof Boolean) return (boolean) value
    String raw = normalize(value)?.toLowerCase()
    if (["y", "yes", "true", "1"].contains(raw)) return true
    if (["n", "no", "false", "0"].contains(raw)) return false
    return defaultValue
}

String ruleSetIdToUse = normalize(ruleSetId)
boolean returnAll = normalizeBool(returnAllFacts, true)
List inputRows = (dataList instanceof List) ? (List) dataList : []

if (!ruleSetIdToUse) throw new IllegalArgumentException("ruleSetId is required")
if (!inputRows) {
    results = []
    matchedResults = []
    ruleCount = 0
    firedRuleCount = 0
    warnings = []
    return
}

Map ruleExec = ec.service.sync()
        .name("reconciliation.ReconciliationRuleEngineServices.execute#RuleSet")
        .parameters([
                ruleSetId    : ruleSetIdToUse,
                dataList     : inputRows,
                returnAllFacts: returnAll
        ])
        .call()

if (ruleExec == null) {
    results = []
    matchedResults = []
    ruleCount = 0
    firedRuleCount = 0
    warnings = []
    error = "RuleSet ${ruleSetIdToUse} execution returned no result."
    logger.error(error)
    return
}

if (ruleExec.error) {
    results = (returnAll ? inputRows : [])
    matchedResults = []
    ruleCount = (ruleExec.ruleCount ?: 0) as int
    firedRuleCount = (ruleExec.firedRuleCount ?: 0) as int
    warnings = (ruleExec.warnings ?: []) as List
    error = "RuleSet ${ruleSetIdToUse} execution failed: ${ruleExec.error}"
    logger.error(error)
    return
}

results = (ruleExec.results ?: []) as List
matchedResults = (ruleExec.matchedResults ?: []) as List
ruleCount = (ruleExec.ruleCount ?: 0) as int
firedRuleCount = (ruleExec.firedRuleCount ?: 0) as int
warnings = (ruleExec.warnings ?: []) as List

logger.info("Inventory comparison ruleSet={} rows={} fired={} ruleCount={}",
        ruleSetIdToUse, results.size(), firedRuleCount, ruleCount)
