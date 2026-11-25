# Darpan 3.4.10 Technical Changelog

## Versioning decision

- Patch on the 3.4 line: two optional parameters added to `save#RuleSetRun` (additive; contract stays 5), GraphQL unchanged.

## Source ranges

- darpan `v3.4.9..v3.4.10`, darpan-ui `v4.4.11..v4.4.12`, darpan-docker-config `v3.4.9..v3.4.10`.

## Backend

### drpn-ai/darpan `v3.4.9..v3.4.10`

- `74e4db7a` DAR-UI-148: `save#RuleSetRun` `file1ExtractOptions` / `file2ExtractOptions` (validated by
  `SourceExtractOptionsSupport.validatedJsonOrError` before any write; set on the side's `extractParametersJson` only when
  sent); `RunSideOptionsSmokeTests.saveKeepsTheOptionsWhenOmittedAndReplacesThemWhenSent`; contract snapshot and openapi.
- Release commit: component version 3.4.10, `openapi.json` version 3.4.10, this pack.

## UI

### drpn-ai/darpan-ui `v4.4.11..v4.4.12`

- `d182053` DAR-UI-148: `storedExtractOptions` loads a side's stored options into the run draft (`buildRuleSetDraft`); the
  run manager's "Read options" section (one `AppToggleSwitch` per declared boolean option of the side's own endpoint row,
  saved through `save#RuleSetRun` with the side's options sent explicitly); the UI contract copy and generated types.
- Release commit: app version 4.4.12.

### drpn-ai/darpan-docker-config `v3.4.9..v3.4.10`

- `docker/prod/Dockerfile`: `DARPAN_REF v3.4.10`.

## Data and configuration

- No upgrade data.

## Validation and rollout notes

- Focused tests and gates: `release-checklist.md`. Rollout: docker-config `v3.4.10` via the deployer, then the app from the
  `v4.4.12` copy after a browser look.

## References

- Backlog: DAR-UI-148 (parent DAR-BE-368).
