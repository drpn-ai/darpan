# Rule Engine Services (Production)

This document describes the production RuleSet/Rule service contracts used by reconciliation flows and Rule Engine UI screens.

## Service file

- `runtime/component/darpan/service/reconciliation/ReconciliationRuleEngineServices.xml`
- XML-backed orchestration:
  - `compile#RuleSet`
  - `execute#RuleSet`
  - `execute#RuleSetMatchedPairs`
  - `save#RuleSet`
  - `save#Rule`
  - `delete#Rule`
  - `delete#RuleSet`
  - `clear#RuleSetCache`
- Shared helper for generated identifier tokens, cache, DRL, and Drools/KIE runtime logic:
  - `runtime/component/darpan/src/main/groovy/darpan/reconciliation/rule/RuleEngineSupport.groovy`

## Service contracts

- `reconciliation.ReconciliationRuleEngineServices.compile#RuleSet`
  - Inputs: `ruleSetId` (required), `useCache` (default `false`), `forceRebuild` (default `true`)
  - Outputs: `kieContainer`, `ruleCount`, `drlText`, `warnings`, `error`
- `reconciliation.ReconciliationRuleEngineServices.execute#RuleSet`
  - Inputs: `ruleSetId`, `dataList` (`List`), `returnAllFacts` (default `false`)
  - Outputs: `results`, `matchedResults`, `firedRuleCount`, `ruleCount`, `warnings`, `error`
- `reconciliation.ReconciliationRuleEngineServices.execute#RuleSetMatchedPairs`
  - Inputs: `ruleSetId`, `dataList` (`List` of matched-pair facts), `returnAllFacts` (default `false`)
  - Outputs: `diffResults`, `matchedResults`, `firedRuleCount`, `ruleCount`, `warnings`, `error`
- `reconciliation.ReconciliationRuleEngineServices.save#RuleSet`
  - Creates or updates RuleSet metadata.
  - `ruleSetId` is optional; when blank, Moqui entity-auto create generates the primary ID.
- `reconciliation.ReconciliationRuleEngineServices.save#Rule`
  - Creates or updates a Rule with validation.
  - Requires `ruleSetId`.
  - Requires at least one of `ruleText` or `ruleLogic`.
  - `ruleId` is optional; when blank, Moqui entity-auto create generates the primary ID.
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
- Cache invalidation is tenant-scoped through `RuleEngineSupport`, so XML-backed CRUD, compile, and execution services share the same Groovy-managed cache entries.
- `compile#RuleSet`, `execute#RuleSet`, and `execute#RuleSetMatchedPairs` stay XML-backed at the service layer: XML handles parameter/default normalization, empty-input branching, output assignment, and success messages.
- RuleSet/Rule CRUD keeps ID validation, enabled/boolean coercion, sequence selection, entity store/delete, cache invalidation calls, and optional compile orchestration in XML actions.
- `RuleEngineSupport` retains the practical Groovy boundary because it manages generated identifier tokens, DRL generation, KIE container/session lifecycle, tenant-scoped cache entries, fact copying, and matched-pair diff extraction.
- Rule parsing supports user-friendly condition text (for example: `status is Pending`).
- Field-comparison rules may store expression JSON with structured `preActions`, currently entries like `{ "fieldSide": "file1", "action": "STRING_TO_INT" }` or `{ "fieldSide": "file2", "action": "STRING_TO_NUMBER" }`, so generated DRL can normalize selected field values before applying the operator.
- Generated rules add `_matchedRuleIds` to matched fact maps.
- Compiled DRL resources are written under a package-aligned resource path so Drools package validation succeeds for tenant-scoped RuleSets.
- RuleSet execution services are file type agnostic. Callers must parse CSV, JSON, or other source payloads into compare-ready `dataList` facts before calling `execute#RuleSet` or `execute#RuleSetMatchedPairs`.

Example generic execution call:

```xml
<service-call name="reconciliation.ReconciliationRuleEngineServices.execute#RuleSet"
        in-map="[ruleSetId:ruleSetId, dataList:dataList, returnAllFacts:false]"
        out-map="executionOut"/>
```

## Matched-pair contract

`execute#RuleSetMatchedPairs` is the RuleSet cutover entrypoint for DRL that compares matched objects from both file sides. Each fact is a `Map` with:

- `compareScopeId`
- `objectType`
- `primaryId`
- `file1`
- `file2`

Rule-generated diff rows are normalized to:

- `diffType`
- `compareScopeId`
- `objectType`
- `primaryId`
- `field`
- `file1Value`
- `file2Value`
- `ruleId`
- `severity`
- `message`

Use `RuleDiffSupport` from DRL to emit those rows. Example:

```drl
rule "PRODUCT_SKU_MISMATCH"
when
    $m : Map(this["file1"] != null, this["file2"] != null)
    eval(RuleDiffSupport.valuesDiffer(((Map) $m.get("file1")).get("sku"), ((Map) $m.get("file2")).get("sku")))
then
    RuleDiffSupport.addFieldMismatch(
        $m,
        kcontext.getRule().getName(),
        "sku",
        ((Map) $m.get("file1")).get("sku"),
        ((Map) $m.get("file2")).get("sku"),
        "WARN",
        "SKU mismatch"
    );
end
```

If DRL fires but emits no diff rows, `execute#RuleSetMatchedPairs` returns a warning so caller pipelines can catch incomplete rule authoring before cutover.

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
