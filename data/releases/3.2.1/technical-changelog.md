# Technical Changelog For Darpan 3.2.1

This file is the engineer-facing companion to the user release notes. Keep it curated and diffable.

## Versioning decision

- **Patch** on the 3.2 line: one behaviour fix, no method added or removed, contract version 4, one service
  description changed. Only darpan and darpan-docker-config are tagged; darpan-ui stays at 4.2.0 (its `main` carries
  the contract copy's description only).

## Source ranges

| Repo | Range | Commits |
| --- | --- | --- |
| darpan | `v3.2.0..v3.2.1` | 1 |
| darpan-docker-config | `v3.2.0..v3.2.1` | 0 |
| darpan-admin-ui | `main` (no tags) | 1 (7c29011) |
| darpan-ui | `main` (no release) | 1 (52a3d21, contract copy) |

Commit counts exclude each repo's release-metadata commit.

## Backend

### Changed

- `admin.SecurityAdminServices.update#TwoFactorPolicy` (DAR-BE-302): for ADMIN, DARPAN_SUPER_ADMIN or EVERYONE, a
  date needs the acting super admin's second way back in (`TwoFactorPolicy.hasWayBackIn`: two or more ACTIVE factors,
  or unused recovery codes), checked before the fresh proof so a refusal spends no code; an acting admin with no
  factor gets the NOT_ENROLLED message. `TwoFactorPolicy.MIN_ENROLLED_SUPER_ADMINS` removed;
  `enrolledSuperAdminCount` stays on the board as information.

## UI

- darpan-ui: no release; `main` carries the contract copy's new `update#TwoFactorPolicy` description only (52a3d21).

## darpan-admin-ui (`main`)

- The Security page and `security two-factor policy set` no longer pre-check a super admin count
  (`superAdminPolicyBlock`, `SUPER_ADMIN_POLICY_GROUPS`, `MIN_ENROLLED_SUPER_ADMINS` removed); the backend's answer
  is shown.

## Data and configuration

- No upgrade records. docker-config: `DARPAN_REF` v3.2.1; every other pin unchanged.

## Validation and rollout notes

- Commands and results: `release-checklist.md`.

## References

- https://github.com/drpn-ai/darpan/compare/v3.2.0...v3.2.1
- https://github.com/drpn-ai/darpan-docker-config/compare/v3.2.0...v3.2.1
