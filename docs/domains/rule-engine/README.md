# Rule Engine Domain

## Purpose

Production rule-set CRUD, compilation, and execution behavior used by reconciliation flows.

## Production Entrypoints

- Route: `runtime/component/darpan/screen/Reconciliation/RuleEngine/RuleSets.xml`
- Services: `runtime/component/darpan/service/reconciliation/ReconciliationRuleEngineServices.xml`
- Groovy helpers: `runtime/component/darpan/src/main/groovy/darpan/reconciliation/rule/RuleEngineSupport.groovy`, `runtime/component/darpan/src/main/groovy/darpan/reconciliation/rule/RuleConditionParser.groovy`

## Operational Notes

- Rule engine production screens should not expose sample/debug execution endpoints.
- Validate with `./gradlew :runtime:component:darpan:verifyOrganization --console=plain` after route/service changes.
