# Upgrade Data Review For Darpan 1.1.1

## Scope

- Backend compare range: `v1.1.0..main`
- Data directory reviewed: `data`
- This report lists candidate seed/config records that were added or modified between the compared refs.
- This patch release changes code and documentation only; no operator-facing seed/config records were added or modified.

## Candidate records

- No added or modified seed records were detected between the compared refs.
- `upgrade-data.xml` remains intentionally empty for `1.1.1`.

## Recommended operator review

- No release-scoped load action is required for this patch release.
- Fresh or rebuilt environments should continue using `./gradlew loadDarpanSetup` as before.
- State the final operator action in `release-notes.md` and `release-checklist.md`.
