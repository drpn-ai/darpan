# Technical Changelog For Darpan 3.3.1

This file is the engineer-facing companion to the user release notes. Keep it curated and diffable.

## Versioning decision

- **Patch** on the 3.3 line: one behaviour fix, no method added or removed, contract version 4, no service changed.
  Only darpan and darpan-docker-config are tagged. The tag sits off `main` (`v3.3.0` + the fix + this release commit):
  `main` also carries DAR-BE-319, DAR-UI-132 and DAR-BE-320, which change an entity, the contract and seeds, so they
  wait for 3.4.

## Source ranges

| Repo | Range | Commits |
| --- | --- | --- |
| darpan | `v3.3.0..v3.3.1` | 1 (6e27cc52, the cherry-pick of `main`'s b6b7fdaf) |
| darpan-docker-config | `v3.3.0..v3.3.1` | 0 |

Commit counts exclude each repo's release-metadata commit.

## Backend

### Fixed

- `ReconciliationSavedRunSupport.extractReadFields` (DAR-CLIENT-006): the fields sent as `readFields` to the HotWax
  order read are the side's enabled rules' fields AND its conclusion conditions' top-level fields. Before, both run
  paths sent only the rules' (DAR-BE-239), so a read narrowed by filters never selected a field only the tree read,
  and the condition saw a blank. The conditions' fields come from the new `conclusionReadFieldsForSide`, which the
  projection path (`resolveExtractKeepFields`, DAR-BE-097) now shares instead of its own copy; brain rows stay excluded
  (DAR-BE-192). Only the HotWax OMS order extract declares `readFields`, and it drops names it cannot select.

## UI

- None: no UI change; darpan-ui stays at 4.3.0.

## Data and configuration

- No upgrade records. docker-config: `DARPAN_REF` v3.3.1; every other pin unchanged.

## Validation and rollout notes

- Commands and results: `release-checklist.md`.

## References

- https://github.com/drpn-ai/darpan/compare/v3.3.0...v3.3.1
- https://github.com/drpn-ai/darpan-docker-config/compare/v3.3.0...v3.3.1
