# RuleSet Compare Scope Cutover Project

## Objective
Move reconciliation configuration and decisioning onto RuleSet-owned compare scopes while preserving the existing extraction, normalization, Spark anti-join, and Diff output behavior.

The important split is:

- RuleSet compare scope defines the object being compared and the primary ID expression for each file side.
- The base compare stage extracts primary IDs, emits missing-object Diff rows, and builds matched object pairs.
- DRL rules run only on matched pairs where the primary ID exists in both files.
- Mapping entities become migration input and historical configuration after the RuleSet compare-scope contract reaches parity.

Example: for Product comparison, the compare scope uses `productId` as the primary ID. The base compare stage emits missing Product Diffs. DRL rules then check fields such as SKU and price on matched Product pairs.

This project is documentation and execution orchestration only. It does not itself implement runtime code changes.

## End State
- Generic and SFTP reconciliation use `ruleSetId` plus an optional `compareScopeId` instead of `reconciliationMappingId`.
- RuleSet compare-scope config stores file side, system, file type, schema, primary ID expression, record/root expression, and ID normalizer.
- Missing-object Diffs are emitted before DRL execution:
  - `MISSING_IN_FILE_1`: primary ID exists in file 2 but not file 1.
  - `MISSING_IN_FILE_2`: primary ID exists in file 1 but not file 2.
- DRL rules receive matched-pair facts and emit field/business-rule Diffs such as SKU or price mismatches.
- Existing Diff output shape, counts, filenames, and downstream consumers remain stable or are extended compatibly.
- Mapping entities are deprecated only after migration/parity evidence exists.

## Scope Guardrails
- Primary execution scope is limited to `runtime/component/darpan/` and its descendants.
- Any required changes outside `runtime/component/darpan/` must be explicitly justified in the relevant ticket before implementation.
- Preserve the existing compare implementation semantics while removing the long-term Mapping entity dependency.
- Do not run DRL for objects missing from either file unless a later ticket explicitly adds override behavior.
- Ticket implementations must preserve existing non-reconciliation behavior.

## Deliverables
- Decision-complete ticket pack in `tickets/`.
- Explicit dependency and wave execution guide.
- Standard closeout evidence template.

## Ticket Index
| Ticket | Title | Depends On | Wave |
|---|---|---|---|
| RSCUT-001 | RuleSet Compare Scope Model Foundation | None | Wave 1 |
| RSCUT-002 | Compare Scope Extraction Adapter | RSCUT-001 | Wave 1 |
| RSCUT-003 | Base Missing-Object Diff Stage | RSCUT-002 | Wave 2 |
| RSCUT-004 | DRL Matched-Pair Rule Execution | RSCUT-003 | Wave 2 |
| RSCUT-005 | Generic Backend RuleSet Compare Contract | RSCUT-004 | Wave 3 |
| RSCUT-006 | Generic UI RuleSet Compare Scope Selector | RSCUT-005 | Wave 3 |
| RSCUT-007 | SFTP Automation RuleSet Compare Contract | RSCUT-004 | Wave 3 |
| RSCUT-008 | SFTP Screen RuleSet Compare Scope Selector | RSCUT-007 | Wave 3 |
| RSCUT-009 | Mapping Migration and Deprecation | RSCUT-006, RSCUT-008 | Wave 4 |
| RSCUT-010 | Final Verification and Cutover Evidence | RSCUT-009 | Wave 4 |

## Execution Model
1. Execute tickets in `execution-order.md` sequence.
2. For each ticket, complete all acceptance criteria and attach evidence using `validation-report-template.md`.
3. Do not start a dependent ticket until the prerequisite ticket is closed with evidence.
4. If a ticket fails validation, execute the rollback plan in that ticket before retry.

## Required Validation Discipline
- Every ticket run must include command outputs and pass/fail notes.
- No ticket is complete without explicit evidence for contract behavior and regression checks.
- Runtime-impacting tickets must include at least one negative-path validation.
- Every runtime validation must prove that RuleSet compare-scope reconciliation still emits the existing Diff contract.
- Parity checks must compare representative outputs against the current Mapping-backed baseline before Mapping deprecation.

## References
- Project execution order: `execution-order.md`
- Ticket authoring standard: `ticket-template.md`
- Evidence template: `validation-report-template.md`
- Ticket files: `tickets/RSCUT-001.md` to `tickets/RSCUT-010.md`
