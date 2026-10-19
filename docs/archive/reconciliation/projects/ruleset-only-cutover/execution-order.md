# Execution Order

## Dependency Graph
```text
RSCUT-001 -> RSCUT-002 -> RSCUT-003 -> RSCUT-004
RSCUT-004 -> RSCUT-005 -> RSCUT-006
RSCUT-004 -> RSCUT-007 -> RSCUT-008
RSCUT-006 + RSCUT-008 -> RSCUT-009
RSCUT-009 -> RSCUT-010
```

No dependency cycles are allowed.

## Wave Plan

### Wave 1: RuleSet Compare Scope Foundation
- RSCUT-001
- RSCUT-002

#### Wave 1 Exit Gate
- RuleSet compare scopes can represent the object identity that Mapping previously represented.
- File-side source config captures primary ID expression, file type, schema, record/root expression, and normalizer.
- Existing extraction logic has a reusable adapter that does not require Mapping entities.

### Wave 2: Diff Pipeline Split
- RSCUT-003
- RSCUT-004

#### Wave 2 Exit Gate
- Missing-object Diffs are emitted before DRL runs.
- Matched object pairs are produced for primary IDs present in both files.
- DRL rules execute only on matched pairs and can emit field/business-rule Diffs.
- Existing Diff output contract is preserved.

### Wave 3: Caller Cutover
- RSCUT-005
- RSCUT-006
- RSCUT-007
- RSCUT-008

#### Wave 3 Exit Gate
- Generic and SFTP backend contracts support `ruleSetId` and `compareScopeId`.
- Generic and SFTP screens select RuleSet compare scopes instead of Mapping records.
- Existing Mapping-backed flows remain available until migration and parity evidence are complete.

### Wave 4: Migration, Deprecation, Final Validation
- RSCUT-009
- RSCUT-010

#### Wave 4 Exit Gate
- Existing Mapping rows can be migrated or translated into RuleSet compare-scope config.
- Mapping entities and screens are explicitly deprecated after successful migration evidence.
- Final evidence confirms missing-object and matched-pair DRL parity against baseline outputs.

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
