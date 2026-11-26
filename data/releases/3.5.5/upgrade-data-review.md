# Upgrade Data Review For Darpan 3.5.5

## Scope

- Backend compare range: `v3.5.4..HEAD`
- Data directory reviewed: `data`

## Candidate records

- None: `git diff v3.5.4..HEAD -- data/ entity/ service/` is empty; DAR-BE-383 changes Groovy only.

## Recommended operator review

- None: no record, entity, column or setting changes. Prod moves from 3.5.4, whose records are already loaded.

## Operator action

- None after the boot: no migration, no app release.
