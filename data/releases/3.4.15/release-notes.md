# Darpan 3.4.15

Release date: 2026-11-26

## Scope note

- **A backend patch on the 3.4 line** (Aditi, 2026-11-26 14:17 IST "yes" to committing DAR-BE-373 and starting the release). It
  carries everything on darpan `main` after `v3.4.14`: DAR-BE-373 (e12e8027) and the DAR-CLI-008 access fix (de186515, cut
  here at that session's request). Publicly it folds into the 3.4 notes.
- **Upgrade data**: none (0 records). No entity change, no migration. Contract stays 5 (243 methods). GraphQL unchanged.

## Repo targets

| Repo | From | To |
| --- | --- | --- |
| `drpn-ai/darpan` | `v3.4.14` | `v3.4.15` |
| `drpn-ai/darpan-docker-config` | `v3.4.14` | `v3.4.15` (DARPAN_REF move) |
| darpan-ui (v4.4.14), darpan-cli (v0.5.0), darpan-hotwax (v1.3.5), shopify-darpan (v0.11.3), netsuite-darpan (v0.7.2) and every other component | unchanged | unchanged |

## User-visible changes

- **The approved-orders audit check also reports completed orders that never reached NetSuite, with their causes**
  (DAR-BE-373). Its conclusion brain now gives a cause to completed orders as well as approved ones (on its way to
  NetSuite, an exchange waiting on its return, and the rest), and it can now name "Sold on a retired SKU" and "Product not
  in NetSuite": for an order NetSuite does not have, it asks HotWax for the order's items, only when no stronger cause
  already holds. Tenants whose order checks are not on the brain see no change.
- **Audit change proposals are for editors and admins** (DAR-CLI-008): `get#AuditChangeProposal` and
  `list#AuditChangeProposals` now refuse a view-only user, as the app already did, so the CLI has the same permissions as
  the app.

## Upgrade data

- None. The previous archived at `data/releases/3.4.14/upgrade-data.xml` (equals the `v3.4.14` tag's).

## Verification

- `release-checklist.md`.

## Rollback or fallback notes

- Roll back by redeploying `v3.4.14` (docker-config `v3.4.14`). Then the approved-orders check gives no brain cause to a
  completed order, and a view-only user can read change proposals again. A tenant tree saved for 3.4.15 (gorjana R7) must
  not be loaded on 3.4.14.
