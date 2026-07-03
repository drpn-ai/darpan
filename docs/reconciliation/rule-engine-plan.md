# Rule Engine Project Plan

## Overview
The goal of this phase is to integrate **Apache Drools** to provide a flexible, scalable Rule Engine for the Darpan component. The current Mapping-backed compare path remains the baseline during migration, but the target contract moves object identity and file-side primary ID extraction into RuleSet compare scopes. DRL rules run on matched object pairs and emit Diff outcomes.

## Goals
1.  **Flexibility**: Support complex logic (AND/OR, grouping, mathematical operations) without hardcoding.
2.  **Usability**: Users define rules in readable sentences.
3.  **Extensibility**: Easy to add new rule types or actions in the backend.
4.  **Performance**: Efficiently process large datasets using Drools' RETE algorithm.

## System Design

### Data Model
New entities will be introduced to store rule definitions:

*   **RuleSet**: Represents a group of rules (e.g., "Shopify vs OMS Validation").
*   **Rule**: A single logical check.
    *   `ruleText`: "If cancelledAt is not NULL and status is Cancelled..."
    *   `logic`: The internal representation (DRL or expression) used by the engine.
*   **RuleSetCompareScope**: The compared object, such as Product, Order, or InventoryItem.
*   **RuleSetCompareSource**: One file-side source definition with schema, record/root expression, primary ID expression, and optional normalizer.

### Service Architecture
The core service `executeRules` will:
1.  **Load Config**: Fetch the requested `RuleSet`, compare scope, and active rules.
2.  **Compile Logic**: Dynamically generate a Drools Knowledge Base (caching this for performance).
3.  **Load Data**: Accept matched object pairs produced by the base compare stage.
4.  **Execute**:
    *   Insert each matched pair as a "Fact" into the Drools session.
    *   Fire all rules.
5.  **Collect Results**: Each rule firing will add a Diff result to a global results list.
6.  **Return**: The final list of Diffs is returned or appended to the existing Diff artifact.

### Integration with Drools
We will use the generic `Map<String, Object>` interface so that we don't need to generate POJOs for every dataset. Matched-pair facts should include `objectType`, `compareScopeId`, `primaryId`, `file1`, and `file2`. Drools supports accessing Map keys directly in rules (e.g., `this["file1"]["sku"] != this["file2"]["sku"]`).

## Work Breakdown

### Phase 1: Foundation (Current)
- [ ] Add Drools dependencies to `build.gradle`.
- [ ] Create `RuleSet` and `Rule` entities.
- [ ] Define RuleSet compare-scope configuration.
- [ ] Preserve `ReconciliationMapping` as the current baseline and migration input until the cutover ticket deprecates it.

### Phase 2: Core Service Implementation
- [ ] Implement XML-backed rule-engine services with Drools initialization in `reconciliation/rule/RuleEngineSupport.groovy`.
- [ ] Create a "Rule Compiler" that converts the `Rule` entity data into a valid DRL file string.
- [ ] Implement the execution loop (insert facts -> fire rules -> collect results).

### Phase 3: Rule Logic Parsing
- [ ] Develop a parser to translate "Human Readable" sentences into DRL conditions.
    - *Example*: "statusId is 'Cancelled'" -> `this["statusId"] == "Cancelled"`
- [ ] Support standard operators: `is`, `is not`, `contains`, `> usage`, etc.

### Phase 4: User Interface
- [ ] Create screens to Manage RuleSets (Create/Edit/Delete).
- [ ] Create a Rule Editor (Add rules to a set, reorder them).
- [ ] Create a "Test Bench" to run a RuleSet against a pasted JSON sample.

## Compare-Scope RuleSet Guide
Target reconciliation configuration starts with a RuleSet compare scope. For example, a Product scope can use `productId` as the primary ID for both file sides. The base compare stage emits missing Product Diffs before rules run. DRL rules then evaluate matched Product pairs for business mismatches, such as same product ID with different SKU or price.

Mapping entities remain available as current baseline configuration and migration input until the migration/deprecation ticket completes with parity evidence.
