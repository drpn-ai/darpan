---
name: darpan-gemini
description: Guide coding and verifying functionality within the Darpan component, ensuring local conventions and safety.
---

# Skill: darpan-gemini (v1.1)
## Goal
Guide coding and verifying functionality within the Darpan component, ensuring local conventions and safety.

## Use when
- Coding logic in `runtime/component/darpan/**`.
- Reviewing PRs or code in Darpan.
- Implementing reconciliation or JSON schema features.
- Implementing backend contracts consumed by `darpan-ui`.

## Don’t use when
- Modifying framework/base components (unless explicitly requested).
- writing generic Moqui logic unrelated to Darpan.

## Inputs
- required: Task context (Java/Groovy/XML)
- optional: Previous test results

## Procedure
1. **Scope Check**: Is this in `runtime/component/darpan`? If not, stop and confirm.
2. **Repo Boundary**: Keep custom UI/PWA work in `darpan-ui`; in this component, change only backend contracts/services/processing.
3. **Docs**: Update `docs/**` if behavior changes.
4. **Logic**:
    - Thin XML (routing/validation).
    - Groovy/Java for heavy lifting.
    - Spark for large datasets (keep sessions alive).
5. **Data**: Store inputs in `runtime://tmp/`, schemas in `runtime/schemas/json/`.
6. **Verify**: Run `./gradlew test` (targeted) or XML validation interactively.

## Guardrails
- **Compatibility**: Preserve Java 17 Spark flags.
- **Secrets**: No connection strings/secrets in logs/commits.
- **Safe**: Sanitize filenames `[A-Za-z0-9._-]`.
- **Pagination**: Use Moqui built-in widgets.
- **Boundary**: Do not add new custom UI/PWA surfaces under backend `screen/**`, `template/**`, or `theme-library/**`.

## Failure handling
- **Test Failure**: Rollback or fix logic immediately.
- **XML Error**: Validate against XSD before committing.

## Examples
**Example A (Reconciliation Output)**:
Input: DataFrame diff result.
Output: Save to `runtime://.../YYYYMMDD-HHmmss.csv`.
```groovy
df.coalesce(1).write().option("header", "true").csv(outputPath)
```
