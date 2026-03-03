# Rule Workspace LLM DRL Generation

## What Changed

`Rules/RuleWorkspace` now calls `darpan.rule.RuleServices.generate#DrlFromPrompt` to convert natural-language rule requirements into Drools DRL.

- Screen: `runtime/component/darpan/screen/Rules/RuleWorkspace.xml`
- Service definition: `runtime/component/darpan/service/darpan/rule/RuleServices.xml`
- Service implementation: `runtime/component/darpan/src/main/groovy/darpan/rule/service/generateDrlFromPrompt.groovy`

## Configuration

Preferred configuration is now in-app:

- Navigate to `Settings -> LLM`.
- In `LLM Settings`, choose a provider (`OpenAI` or `Gemini`) and save only:
  - API Key
- Saving also switches the active provider used by Rule Workspace generation.
- Model/base URL/timeout are applied automatically using provider defaults (or existing saved values).

Fallback environment variables (used only when app settings are missing):

- OpenAI:
  - `OPENAI_API_KEY`
  - `OPENAI_MODEL` (default `gpt-4.1-mini`)
  - `OPENAI_API_BASE_URL` (default `https://api.openai.com`)
  - `OPENAI_TIMEOUT_SECONDS` (default `45`)
- Gemini:
  - `GEMINI_API_KEY`
  - `GEMINI_MODEL` (default `gemini-2.0-flash`)
  - `GEMINI_API_BASE_URL` (default `https://generativelanguage.googleapis.com`)
  - `GEMINI_TIMEOUT_SECONDS` (default `45`)

Resolution order:
1. Active provider from `moqui.service.message.SystemMessageRemote` with ID `RULE_WORKSPACE_LLM_ACTIVE`
2. Provider settings from `OPENAI_RULE_WORKSPACE` or `GEMINI_RULE_WORKSPACE` (encrypted `password` stores API key)
3. Provider-specific environment/config fallbacks above

If no key is available, settings are disabled, or the LLM call fails, the service returns a deterministic DRL template and warning messages so the workspace remains usable.

## Service Signature

### `darpan.rule.RuleServices.generate#DrlFromPrompt`

Inputs:
- `tenantId` (`String`, default `DEFAULT`)
- `prompt` (`String`, required)
- `ruleContext` (`String`, optional JSON)
- `ruleId` (`String`, optional)

Outputs:
- `drlText` (`String`)
- `explanation` (`String`)
- `warnings` (`List<String>`)

## Example Call

```groovy
def out = ec.service.sync()
        .name("darpan.rule.RuleServices.generate#DrlFromPrompt")
        .parameters([
                tenantId   : "DEFAULT",
                prompt     : "If OMS status is ORDER_CANCELLED and Shopify cancelled_at is null, flag syncIssue",
                ruleContext: '{"source":"shopify-vs-oms"}'
        ])
        .call()
```

Expected behavior:
- `out.drlText` contains DRL with `package darpan.rule` and `import java.util.Map`
- `out.explanation` summarizes generation
- `out.warnings` includes ambiguity or fallback notes when applicable
