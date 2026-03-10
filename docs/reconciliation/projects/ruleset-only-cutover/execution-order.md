# Execution Order

## Dependency Graph
```text
RSCUT-001 -> RSCUT-002
RSCUT-001 -> RSCUT-003
RSCUT-003 -> RSCUT-004 -> RSCUT-005
RSCUT-003 -> RSCUT-006 -> RSCUT-007
RSCUT-005 + RSCUT-007 -> RSCUT-008
RSCUT-002 + RSCUT-008 -> RSCUT-009
RSCUT-009 -> RSCUT-010
```

No dependency cycles are allowed.

## Wave Plan

### Wave 1: Foundation and Data Migration Capability
- RSCUT-001
- RSCUT-002

#### Wave 1 Exit Gate
- New RuleSet source configuration entity is present and queryable.
- Migration service supports dry-run and apply modes.
- Migration service is idempotent in repeated apply runs.

### Wave 2: RuleSet Routing Core
- RSCUT-003

#### Wave 2 Exit Gate
- New RuleSet-based router service executes and delegates to unified reconciliation.
- Presence-based rule execution contract is enforced.

### Wave 3: Caller Contract Cutover
- RSCUT-004
- RSCUT-005
- RSCUT-006
- RSCUT-007

#### Wave 3 Exit Gate
- Generic and SFTP active contracts use `ruleSetId`.
- UI forms and job editor no longer require mapping IDs.

### Wave 4: Decommission, Alignment, Final Validation
- RSCUT-008
- RSCUT-009
- RSCUT-010

#### Wave 4 Exit Gate
- Mapping runtime execution path removed.
- Mapping screens hidden/disabled.
- Seeds and docs aligned with RuleSet-first terminology.
- Final evidence report confirms cutover readiness and rollback checks.

## Operational Rules
1. A ticket cannot start until all dependencies are closed with evidence.
2. If validations fail, execute rollback before code/doc rework.
3. Every ticket completion must include:
   - commands executed
   - expected result vs actual result
   - residual risk summary
4. Do not batch-close tickets without per-ticket evidence.

## Suggested Branch Pattern
- `codex/rscut-001-<short-topic>`
- `codex/rscut-002-<short-topic>`
- ...

## Suggested Commit Pattern per Ticket
- `rscut-00x: <outcome-oriented summary>`

