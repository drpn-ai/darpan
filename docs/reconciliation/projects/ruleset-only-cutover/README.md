# RuleSet-Only Cutover Project

## Objective
Migrate reconciliation runtime behavior from mapping-driven routing to RuleSet-driven routing with a hard-break end state, executed in controlled micro-steps.

This project is documentation and execution orchestration only. It does not itself implement runtime code changes.

## End State
- Reconciliation execution paths use `ruleSetId` as the routing/config key.
- `reconciliationMappingId` is removed from active Generic and SFTP execution contracts.
- Mapping runtime router path is removed.
- Mapping UI route is hidden/disabled.
- Legacy mapping entities remain only as historical/read-only data during this migration window.

## Scope Guardrails
- Primary execution scope is limited to `runtime/component/darpan/**`.
- Any required changes outside `runtime/component/darpan/**` must be explicitly justified in the relevant ticket before implementation.
- Ticket implementations must preserve existing non-reconciliation behavior.

## Deliverables
- Decision-complete ticket pack in `tickets/`.
- Explicit dependency and wave execution guide.
- Standard closeout evidence template.

## Ticket Index
| Ticket | Title | Depends On | Wave |
|---|---|---|---|
| RSCUT-001 | RuleSetSourceConfig Data Model Foundation | None | Wave 1 |
| RSCUT-002 | Mapping to RuleSet Migration Service | RSCUT-001 | Wave 1 |
| RSCUT-003 | RuleSet Router Service (`reconcile#FilesByRuleSet`) | RSCUT-001 | Wave 2 |
| RSCUT-004 | Generic Reconciliation Backend Contract Cutover | RSCUT-003 | Wave 3 |
| RSCUT-005 | Generic Reconciliation UI Cutover | RSCUT-004 | Wave 3 |
| RSCUT-006 | SFTP Automation Backend Contract Cutover | RSCUT-003 | Wave 3 |
| RSCUT-007 | SFTP Automation Screen and Job Editor Cutover | RSCUT-006 | Wave 3 |
| RSCUT-008 | Mapping Runtime Decommission and Screen Disablement | RSCUT-005, RSCUT-007 | Wave 4 |
| RSCUT-009 | Seeds and Documentation Alignment | RSCUT-002, RSCUT-008 | Wave 4 |
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

## References
- Project execution order: `execution-order.md`
- Ticket authoring standard: `ticket-template.md`
- Evidence template: `validation-report-template.md`
- Ticket files: `tickets/RSCUT-001.md` to `tickets/RSCUT-010.md`
