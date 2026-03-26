# Darpan AGENTS.md

Component-generic guidance lives here; user-specific preferences are in `/Users/aditipatel/sandbox/darpan/AGENTS.md`.

## Load strategy (token-efficient)
- Start with `runtime/component/darpan/SKILLS.md`.
- Use only the guidance needed for the active task.
- Prefer task skills when available:
  - `$darpan-ui-change-guardrails`
  - `$darpan-reconciliation-rules-first`
  - `$darpan-xml-doc-consistency`
  - `$darpan-production-cleanup-review`
- Read feature docs in `runtime/component/darpan/docs/**` only for the feature being changed.

## Scope and ownership
- Keep changes in `runtime/component/darpan/**` unless explicitly requested.
- Put business logic in `src/**`; expose contracts in `service/*.xml`; keep entity model changes in `entity/*.xml`.
- Use generic, system-neutral naming for entities/services/screens/config.

## Service and XML conventions
- Define explicit `in-parameters` and `out-parameters` with type/default/description.
- Keep XML `default-value` plain (example: `default-value="value"`), not extra-quoted.
- Keep XML declarative; move non-trivial logic to Groovy/Java in `src/**`.
- Prefer `component://` and `runtime://` locations over absolute paths.
- Do not add backward-compatibility aliases in services/contracts unless explicitly requested.

## Reconciliation conventions
- Validate inputs early and return structured validation errors.
- Use Spark for large transforms; avoid `collect()` on large datasets.
- Keep compare logic and source-SQL template selection configurable in Drools/RuleSet artifacts.
- Emit diff outputs consistently under `runtime://tmp/reconciliation/**` with sanitized timestamped names.
- Prefer app-managed encrypted settings for external API credentials; use environment variables only as fallback.
- Mask credentials/secrets in logs and config.

## UI conventions
- For Darpan UI tasks, use provided mockups as source of truth and match layout/copy/control structure unless the request explicitly asks for deviations.
- Use dark monochrome style only for current PWA/sample-page UI work: whitespace-heavy layouts, white/gray text on black/charcoal surfaces, and console-like monospaced typography.
- Do not use legacy light/accent visual patterns unless explicitly requested with new approved mockups.
- Use Moqui built-in pagination; avoid custom pagination unless unavoidable.
- Isolate pagination state per list when multiple lists are on one screen.
- Build dropdown options in actions/scripts, not complex inline `from` literals.
- Use enumeration label fallback `enumCode ?: description ?: enumId`.
- When moving/removing screens, verify legacy direct URLs or document restart/cache-refresh requirement.

## Docs and verification
- When behavior changes, update closest docs in `runtime/component/darpan/docs/**` in the same change.
- Include one concrete example plus operational impact notes (paths/params/schema names) when relevant.
- Validate XML after every entity/service/screen edit and fix errors before completion.
- Tests are not automatic; run targeted checks as needed and report what was not run.

## Ticket workflow
- Use Linear as the source of truth for issue status, roadmap placement, and execution notes.
- Use GitHub only for branches, commits, pull requests, and code review artifacts tied back to the Linear issue ID.
- Do not create or manage GitHub Issues for component work unless explicitly requested.
