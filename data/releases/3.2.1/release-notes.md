# Darpan 3.2.1

Release date: 2026-11-18

## Scope note

- **A hotfix on the 3.2 line** (Aditi, 2026-11-18: "push, tag and deploy 3.2.1"): DAR-BE-302 only. Publicly it folds
  into the 3.2 notes (one `updates.mdx` section and one GitHub release page for the line).
- Facade contract stays at **4**: no method added or removed; one service description changed.

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.2.0` | `v3.2.1` |
| `drpn-ai/darpan-docker-config` | `v3.2.0` | `v3.2.1` (only the darpan pin moves) |
| `drpn-ai/darpan-ui` | `v4.2.0` | no release: `main` gains the contract copy's description only; 4.2.0 stays deployed |
| `drpn-ai/darpan-admin-ui` | (no tag) | `main`: the admin app and CLI stop pre-checking a super admin count |
| darpan-hotwax, darpan-agent, shopify-darpan, netsuite-darpan, database-darpan, moqui-gql | unchanged | 3.2.0 pins |

## User-visible changes

- None for tenant users.

## Operator-visible changes

- **Requiring two-factor for super admins or for everyone needs the acting super admin's second way back in**: two
  factors (a passkey and an authenticator app), or unused recovery codes. Without one the request is refused with
  "Keep a second way back in before requiring two-factor for <role>: add a passkey or an authenticator app as a second
  factor, or make recovery codes." No longer needed: two super admins with two-factor on. Clearing a date is never
  blocked; the Darpan admin and tenant admin roles are not subject to the rule (DAR-BE-302).
- The admin app's Security page and `darpan-admin security two-factor policy set` show the backend's answer instead
  of pre-checking a super admin count.

## Upgrade data

- None: no seed changed since `v3.2.0`. `data/upgrade-data.xml` holds no records for 3.2.1.

## Operator actions after deploy

1. Smoke: `https://api.drpn.ai/` healthy; boot data loaded without skipped files.
2. Aditi: `darpan-admin --target prod security two-factor policy set everyone 2026-12-02 --fresh-code <app code> --yes`
   (browser approval of the sign-in first). Tell tenant users before the next 15:00 UTC reminder run.

## Verification

- Commands and results: `release-checklist.md`.

## Deferred items

- `hasWayBackIn` is read without the person lock (a factor removed in another tab at the same moment is not seen).
- Two CLI specs assert a non-zero exit for a backend refusal instead of the exact exit code.

## Rollback or fallback notes

- Roll back with `deploy.sh prod --ref v3.2.0`: the policy guard returns to "two super admins"; nothing else changes.
  A date already set stays set.
