# Rule Engine Services (Production)

This document describes the production RuleSet/Rule service contracts used by reconciliation flows and Rule Engine UI screens.

## Service file

- `runtime/component/darpan/service/reconciliation/ReconciliationRuleEngineServices.xml`
- Script implementation:
  - `runtime/component/darpan/src/main/groovy/darpan/reconciliation/rule/RuleEngineServices.groovy`

## Service contracts

- `reconciliation.ReconciliationRuleEngineServices.compile#RuleSet`
  - Inputs: `ruleSetId` (required), `useCache` (default `false`), `forceRebuild` (default `true`)
  - Outputs: `ruleCount`, `drlText`, `warnings`, `error`
- `reconciliation.ReconciliationRuleEngineServices.execute#RuleSet`
  - Inputs: `ruleSetId`, `dataList` (`List`), `returnAllFacts` (default `false`)
  - Outputs: `results`, `matchedResults`, `firedRuleCount`, `ruleCount`, `warnings`, `error`
- `reconciliation.ReconciliationRuleEngineServices.execute#RuleSetJson`
  - Inputs: `ruleSetId`, `jsonData`, `returnAllFacts` (default `false`)
  - Outputs: same as `execute#RuleSet`
- `reconciliation.ReconciliationRuleEngineServices.save#RuleSet`
  - Creates or updates RuleSet metadata.
  - `ruleSetId` is optional; if blank, an ID is generated from `ruleSetName`.
- `reconciliation.ReconciliationRuleEngineServices.save#Rule`
  - Creates or updates a Rule with validation.
  - Requires `ruleSetId`.
  - Requires at least one of `ruleText` or `ruleLogic`.
  - `ruleId` is optional; when blank, an ID is generated.
- `reconciliation.ReconciliationRuleEngineServices.delete#Rule`
  - Deletes a Rule and invalidates the related RuleSet cache.
- `reconciliation.ReconciliationRuleEngineServices.delete#RuleSet`
  - Deletes RuleSet.
  - Blocks delete when rules exist unless `forceDeleteRules=true`.
- `reconciliation.ReconciliationRuleEngineServices.clear#RuleSetCache`
  - Clears one RuleSet cache entry or all cached entries.

## Operational behavior

- Cache invalidates on:
  - `save#RuleSet`
  - `save#Rule`
  - `delete#Rule`
  - `delete#RuleSet`
- Rule parsing supports user-friendly condition text (for example: `status is Pending`).
- Generated rules add `_matchedRuleIds` to matched fact maps.

## Example

```xml
<service-call name="reconciliation.ReconciliationRuleEngineServices.save#Rule">
    <parameter name="ruleSetId" value="INV_ADJ_DEFAULT_RS"/>
    <parameter name="ruleText" value="nsRecordCount greater than readDbRecordCount"/>
    <parameter name="enabled" value="Y"/>
    <parameter name="validateOnSave" value="true"/>
</service-call>
```

Expected effect:
- Rule is created (or updated if `ruleId` is passed).
- RuleSet cache is invalidated.
- RuleSet compilation is validated when `validateOnSave=true`.
