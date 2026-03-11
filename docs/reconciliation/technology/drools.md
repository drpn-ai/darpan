# Drools for Configurable Reconciliation Logic

## Goal

Keep comparison and classification logic configurable without changing reconciliation service code for every rule change.

## Implementation in Darpan

- Rule configuration entities:
  - `runtime/component/darpan/entity/RuleEntities.xml`
  - `darpan.rule.RuleSet`
  - `darpan.rule.Rule`
- Production rule services:
  - `runtime/component/darpan/service/reconciliation/ReconciliationRuleEngineServices.xml`
  - Script implementation: `runtime/component/darpan/src/main/groovy/darpan/reconciliation/rule/RuleEngineServices.groovy`

## Execution model

1. Load active `Rule` rows for a `RuleSet`.
2. Build DRL from:
   - `ruleLogic` (full DRL block or inline Map condition), or
   - parsed `ruleText` (for example, `status is 'Pending'`).
3. Compile DRL to a `KieContainer`.
4. Execute facts as `Map` rows.
5. Return:
   - `results`
   - `matchedResults`
   - `firedRuleCount`
   - `warnings`

## Operational notes

- Cache key includes tenant + ruleSet; saves/deletes invalidate cache.
- Use `compile#RuleSet` for explicit pre-run validation.
- Rule actions can enrich facts; generated rules append `_matchedRuleIds` on matched rows.
- Keep large payload processing in Spark and avoid loading large datasets into rule facts unless required.

## Example

```xml
<service-call name="reconciliation.ReconciliationRuleEngineServices.execute#RuleSet">
    <parameter name="ruleSetId" value="INV_ADJ_DEFAULT_RS"/>
    <parameter name="dataList" from="itemResultRows"/>
    <parameter name="returnAllFacts" value="true"/>
</service-call>
```
