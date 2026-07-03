# Repository Boundary: `darpan-backend` vs `darpan-ui`

## Ownership

- `darpan-backend` owns Moqui backend contracts, services, entities, and reconciliation processing.
- `darpan-ui` owns custom UI and PWA behavior.

## Rule

Do not implement or extend custom UI/PWA surfaces in `darpan-backend`.

## Concrete example

- If you need a new reconciliation dashboard page, implement that page in `darpan-ui`.
- If new data is required by that page, add/extend the backend service/API contract in `darpan-backend` only.

## Operational notes

- Backend CI blocks changes under these UI-focused paths:
  - `screen/**`
  - `template/**`
  - `theme-library/**`
  - `docs/assets/ui-mockups/**`
  - `docs/reconciliation/ui-mockups.md`
- Legacy backend UI POC surface `UiFacadePoc` is forbidden and must not be reintroduced.
