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

- No CI job blocks changes by path. `.github/workflows/ci.yml` has no path filter or path guard;
  the boundary is held by review, by the `verifyOrganization` Gradle task, and by the wrapper's
  `plugins/darpan-workflows/scripts/check_repo_boundary.sh`, which the review-gate skills run to
  flag changed files under `screen/`, `template/` or `theme-library/`.
- The early UI mockups (`ui-mockups.md` and its SVGs) moved to `docs/archive/`; they are history.
- `./gradlew :runtime:component:darpan:verifyOrganization --console=plain` is run by hand (it is
  not wired into `check` or CI). It fails when:
  - a `location="component://darpan/..."` in `service/**` or `screen/**` XML points at a missing file;
  - a backticked path beginning runtime/component/darpan/ in `docs/**/*.md` does not exist;
  - a removed legacy artifact is back (the old Rules screen and rule-service XML, the party
    entities, the `UiFacadePoc` screen; the exact list is in `build.gradle`);
  - production service, screen or Groovy code references the sample namespace
    (`reconciliation.ReconciliationSampleServices`, `reconciliation/sample/`) or the `UiFacadePoc`
    surface, or service/screen XML references a `debug.*` service;
  - entity, service, screen, Groovy or docs files (outside `docs/history/` and
    `docs/reconciliation/history/`) name the legacy Rules screen, the old rule-service namespace,
    or the removed rule/party entities. Docs under `docs/archive/` get no exception, so archived
    files must avoid those names too;
  - the `Debug` subscreen in `screen/darpan.xml` is visible in the menu, or a `Rules` subscreen exists.
- The legacy backend screens (`screen/**`, `template/**`, `theme-library/**`) are super-admin only
  and are not extended. New UI goes to `darpan-ui` (tenant app) or `darpan-admin-ui` (operator app).
- Legacy backend UI POC surface `UiFacadePoc` is forbidden and must not be reintroduced.
