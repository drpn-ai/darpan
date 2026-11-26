# Docs Impact For Darpan 3.4.11

Every shipped item needs a decision in each column: the page path you changed
(for example `guides/audits.mdx`), or `none: <reason>`. `verify_release_docs.py`
fails when a page named here was not changed in its docs repo since the previous
release tag. Release notes alone do not count as documenting a change.

## Product docs (docs.drpn.ai)

| Item | Change | How-to | Reference | Troubleshooting | Setup / service details |
| --- | --- | --- | --- | --- | --- |
| DAR-CLI-008 (CLI 0.3.0) | The CLI makes any change the role allows in the app (`darpan call --yes`, `darpan config restore`); credentials, sign-in and installs stay in the app; scripts/deploy commands removed | getting-started/set-up-darpan-cli.mdx (what the CLI can change, `call --yes`, `config restore`, 0.3.0) | reference/auth-and-access.mdx (CLI reach) | getting-started/set-up-darpan-cli.mdx (the "changes something; nothing was sent" message) | none: no connection setup changes |
| DAR-CLI-008 (role check) | A view-only member can no longer switch a connection's endpoints | none: editors' flow unchanged | reference/auth-and-access.mdx (CLI reach paragraph names the service checks) | none: a view member sees the app's usual "view access" message | none: connection setup unchanged for editors |

## GraphQL reference (developers.drpn.ai)

- SDL compared against: none: schema unchanged; `verify_release_docs.py` compares the prod SDL after deploy
- Snapshot refreshed: none: schema unchanged
- GraphQL changelog: none: schema unchanged
- Hand-written GraphQL pages: none: no query, field or example changed

## Service catalog and public OpenAPI

- `api-reference/service-catalog.mdx`: `store#SourceConfigEndpointAccess` needs edit access
- `api-reference/openapi.json` regenerated with `scripts/public_openapi.py` at version 3.4.11
- `api-reference/changelog.mdx`: a 3.4.11 entry under contract 5 (no method added or removed; one role check; the CLI reaches tenant-app writes)

## Engineering and self-host docs

- Runtime baseline matches `build.gradle`: yes, unchanged
- Backend pages: none: no engine or data change
- Self-host and deploy pages: self-host/production.mdx (3.4.11 under "Upgrading to 3.4": darpan only, no upgrade data, no migration, CLI 0.3.0)
- Engineering process pages: none: no process change

## Release notes and roadmap

- `releases/updates.mdx`: the 3.4 section names v3.4.11 and CLI 0.3.0, with its items
- `releases/roadmap.mdx` "Recently shipped": the 3.4 card names v3.4.11. "Up next" unchanged.
